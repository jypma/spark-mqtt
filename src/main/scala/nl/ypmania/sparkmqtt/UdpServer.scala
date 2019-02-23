package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.io.{ IO, Udp }
import akka.util.ByteString
import java.net.InetSocketAddress
import RFPacket.MaybePacket

class UdpServer extends Actor with ActorLogging {
  import UdpServer._
  import context.system
  IO(Udp) ! Udp.Bind(self, new InetSocketAddress("0.0.0.0", 4124))

  def receive = {
    case Udp.Bound(local) ⇒
      context.become(ready(sender()))
  }

  def ready(socket: ActorRef): Receive = {
    log.info("Bound to socket")

    {
      case Udp.Received(ReceivedFS20(FS20.Packet(packet)), _) =>
        log.info("Received FS20: {}", packet)
        context.system.eventStream.publish(packet)
      case Udp.Received(ReceivedPacket(MaybePacket(packet)), _) =>
        log.info("Received Packet: {}", packet)
        context.system.eventStream.publish(packet)
      case Udp.Received(ReceivedAck(MaybeAck(ack)), _) {

      }
      case Udp.Received(data, remote) =>
        log.info("Got {} bytes from {}", data.size, remote)
      case Udp.Unbind  ⇒ socket ! Udp.Unbind
      case Udp.Unbound ⇒ context.stop(self)
    }
  }
}

object UdpServer {
  private case class WithHeader(header: Byte*) {
    val prefix = ByteString(header: _*)
    def unapply(data: ByteString): Option[ByteString] = {
      if (data.startsWith(prefix))
        Some(data.drop(prefix.size))
      else
        None
    }
  }
  private val ReceivedFS20 = WithHeader('F')
  private val ReceivedPacket = WithHeader('R',3) // Packet from node to spark. Spark to node has header 2.
  private val ReceivedAck = WithHeader('R',1)    // All ack have this header
  private val receivedPing = WithHeader('P')
}
