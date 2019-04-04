package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorLogging, ActorRef, Timers }
import nl.ypmania.sparkmqtt.data.Messages.{ Ack, Packet }
import scala.reflect.ClassTag
import scalapb.{ GeneratedMessage, GeneratedMessageCompanion, Message }
import nl.ypmania.sparkmqtt.data.Protobuf.MaybeProtobuf
import scala.concurrent.duration._

class TxState[T <: GeneratedMessage with Message[T]: ClassTag](t: GeneratedMessageCompanion[T])
  (initial: T, NodeId: Int, udpServer: ActorRef) extends Actor with ActorLogging with Timers {
  import TxState._

  val MaybeT = new MaybeProtobuf(t)
  var state = initial
  var lastSeq = 0

  override def receive = {
    case Ack(NodeId, seq) if lastSeq == seq =>
      timers.cancel("resend")

    case Ack(NodeId, seq) =>
      log.warning("Received ack with wrong seq {}, expected {}", seq, lastSeq)

    case t: T if t != state =>
      lastSeq = (lastSeq + 1) % 256
      log.info("Sending new state {} as seq {}", t, lastSeq)
      val pkt = Packet(NodeId, lastSeq, Some(t.toByteString))
      udpServer ! UdpServer.SendPacket(pkt)
      val resend = Resend(pkt)
      timers.startSingleTimer("resend", resend, resendDelay(resend))

    case t: T => // ignore same state

    case Resend(pkt, attempt) =>
      log.debug("Resending state, attempt {}", attempt)
      udpServer ! UdpServer.SendPacket(pkt)
      val resend = Resend(pkt, attempt + 1)
      timers.startSingleTimer("resend", resend, resendDelay(resend))

  }

  private val resendDelays = Vector(1,2,3,5,8,13,21,34,55,89,144)
  def resendDelay(r: Resend) = {
    val resendOffset = ((NodeId) ^ (NodeId >> 4) ^ (NodeId >> 8) ^ (NodeId >> 12)) & 0x0F;
    val delayIdx = Math.min(r.attempt, resendDelays.length - 1)
    100.milliseconds * (resendOffset + resendDelays(delayIdx))
  }
}

object TxState {
  private case class Resend(pkt: Packet, attempt: Int = 0)
}
