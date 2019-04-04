package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorLogging, ActorRef }
import nl.ypmania.sparkmqtt.data.Messages.{ Ack, Packet }
import nl.ypmania.sparkmqtt.data.Protobuf.MaybeProtobuf
import scalapb.{ GeneratedMessage, GeneratedMessageCompanion, Message }


class RxState[T <: GeneratedMessage with Message[T]](t: GeneratedMessageCompanion[T])(NodeId: Int, udpServer: ActorRef)
    extends Actor with ActorLogging {
  val MaybeT = new MaybeProtobuf(t)

  override def receive = {
    case Packet(NodeId, seq, Some(MaybeT(newState))) =>
      log.debug("Got state {}", newState)
      context.parent ! newState
      udpServer ! UdpServer.SendAck(Ack(nodeId = NodeId, seq = seq))
  }
}

object RxState {

}
