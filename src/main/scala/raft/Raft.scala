package raft

import scala.language.postfixOps
import akka.actor.{ Actor, ActorRef, FSM }
import scala.concurrent.duration._

/* types */
object Raft {
  type Term = Int
  type NodeId = ActorRef
}

// this might go elsewhere later
case class LogEntry(entry: String, term: Raft.Term)

/* messages */
sealed trait Message
case object Timeout extends Message

sealed trait Request extends Message
case class RequestVote(term: Raft.Term, candidateId: Raft.NodeId,
  lastLogIndex: Int, lastLogTerm: Raft.Term) extends Request
case class AppendEntries(term: Raft.Term, leaderId: Raft.NodeId,
  prevLogIndex: Int, prevLogTerm: Raft.Term, entries: List[LogEntry],
  leaderCommit: Int) extends Request

sealed trait Vote
case class DenyVote(term: Raft.Term) extends Vote
case class GrantVote(term: Raft.Term) extends Vote

sealed trait AppendReply
case class AppendFailure(term: Raft.Term) extends AppendReply
case class AppendSuccess(term: Raft.Term) extends AppendReply

/* states */
sealed trait Role
case object Leader extends Role
case object Follower extends Role
case object Candidate extends Role

/* state data */
case class Data(
  currentTerm: Raft.Term, // persisted all states
  votedFor: Option[Raft.NodeId], // persisted all states
  log: List[LogEntry], // persisted all states
  commitIndex: Int, // volatile all states
  lastApplied: Int, // volatile all states

  // leaders only
  nextIndex: Map[Raft.NodeId, Int] = Map(), // volatile
  matchIndex: Map[Raft.NodeId, Int] = Map(), // volatile 

  // candidates only 
  votesReceived: List[Raft.NodeId] = List(), // volatile

  // config data
  nodes: List[Raft.NodeId] = List() // persistent
  )

/* Consensus module */
class Raft() extends Actor with FSM[Role, Data] {
  startWith(Follower, Data(0, None, List(), 0, 0)) // TODO: move creation to function

  when(Follower) {
    case Event(rpc: RequestVote, data: Data) =>
      vote(rpc, data) match {
        case (msg: GrantVote, updData) =>
          resetTimer
          stay using (updData) replying (msg)
        case (msg: DenyVote, updData) =>
          stay using (updData) replying (msg)
      }
    case Event(rpc: AppendEntries, data: Data) =>
      resetTimer
      val (msg, upd) = append(rpc, data)
      stay using upd replying msg
    case Event(Timeout, data) =>
      goto(Candidate) using preparedForCandidate(data)
  }

  when(Candidate) {
    // voting events  
    case Event(rpc: RequestVote, data: Data) if rpc.candidateId == self =>
      val (msg, updData) = grant(rpc, data)
      stay using (updData) replying (msg)
    case Event(GrantVote(term), data: Data) if hasMajorityVotes(data) =>
      goto(Leader) using initialLeaderData(data)
    case Event(GrantVote(term), data: Data) =>
      stay using data.copy(votesReceived = sender :: data.votesReceived)

    // other   
    case Event(rpc: AppendEntries, data: Data) =>
      goto(Follower) using data
    case Event(Timeout, data: Data) =>
      goto(Candidate) using preparedForCandidate(data)
  }

  when(Leader) {
    case Event(_, _) =>
      stay
  }

  private def preparedForCandidate(toUpgrade: Data): Data = {
    val data = nextTerm(toUpgrade)
    val lastTerm = if (data.log.length > 0) data.log.last.term else 0
    val lastIndex = if (data.log.length > 0) data.log.length - 1 else 0
    data.nodes.map(t => t ! RequestVote(
      term = data.currentTerm,
      candidateId = self,
      lastLogIndex = lastIndex,
      lastLogTerm = lastTerm
    ))
    resetTimer
    data
  }

  initialize() // akka specific

  /*
   *  --- Internals ---
   */

  private def initialLeaderData(d: Data) = d.copy()

  private def hasMajorityVotes(d: Data) =
    // adds 1 since just received vote is not included
    ((d.votesReceived.length + 1) >= Math.ceil(d.nodes.length / 2.0))

  private def resetTimer = {
    cancelTimer("timeout")
    setTimer("timeout", Timeout, 200 millis, false) // should pick random time
  }

  private def nextTerm(data: Data): Data =
    data.copy(currentTerm = data.currentTerm + 1, votedFor = None)

  private def maxTerm(data: Data, term: Raft.Term): Raft.Term =
    Math.max(data.currentTerm, term)

  /*
   * AppendEntries handling 
   */
  private def append(rpc: AppendEntries, data: Data): (AppendReply, Data) = {
    if (leaderIsBehind(rpc, data)) appendFail(data)
    else if (!hasMatchingLogEntryAtPrevPosition(rpc, data)) appendFail(data)
    else appendSuccess(rpc, data)
  }

  private def leaderIsBehind(rpc: AppendEntries, data: Data): Boolean =
    rpc.term < data.currentTerm

  private def hasMatchingLogEntryAtPrevPosition(
    rpc: AppendEntries, data: Data): Boolean =
    (data.log.isDefinedAt(rpc.prevLogIndex) &&
      (data.log(rpc.prevLogIndex).term == rpc.prevLogTerm))

  private def appendFail(data: Data) =
    (AppendFailure(data.currentTerm), data)

  private def appendSuccess(rpc: AppendEntries, data: Data) = {
    // if newer entries exist in log these are not committed and can 
    // safely be removed - should add check during exhaustive testing
    // to ensure property holds
    // TODO: wrap in Log data structure
    val log = data.log.take(rpc.prevLogIndex) ::: rpc.entries
    val updatedData = data.copy(currentTerm = maxTerm(data, rpc.term), log = log)
    (AppendSuccess(updatedData.currentTerm), updatedData)
  }

  /*
   * Determine whether to grant or deny vote
   */
  private def vote(rpc: RequestVote, data: Data): (Vote, Data) =
    if (alreadyVoted(data)) deny(rpc, data)
    else if (rpc.term < data.currentTerm) deny(rpc, data)
    else if (rpc.term == data.currentTerm)
      if (candidateLogTermIsBehind(rpc, data)) deny(rpc, data)
      else if (candidateLogTermIsEqualButHasShorterLog(rpc, data)) deny(rpc, data)
      else grant(rpc, data) // follower and candidate are equal, grant
    else grant(rpc, data) // candidate is ahead, grant

  private def deny(rpc: RequestVote, d: Data) = {
    val newdata = d.copy(currentTerm = maxTerm(d, rpc.term))
    (DenyVote(newdata.currentTerm), newdata)
  }
  private def grant(rpc: RequestVote, d: Data): (Vote, Data) = {
    val newdata = d.copy(
      currentTerm = maxTerm(d, rpc.term),
      votedFor = Some(rpc.candidateId)
    )
    (GrantVote(newdata.currentTerm), newdata)
  }

  private def candidateLogTermIsBehind(rpc: RequestVote, data: Data) =
    data.log.last.term > rpc.lastLogTerm

  private def candidateLogTermIsEqualButHasShorterLog(rpc: RequestVote, data: Data) =
    (data.log.last.term == rpc.lastLogTerm) &&
      (data.log.length - 1 > rpc.lastLogIndex)

  private def alreadyVoted(data: Data): Boolean = data.votedFor match {
    case Some(_) => true
    case None => false
  }
}
