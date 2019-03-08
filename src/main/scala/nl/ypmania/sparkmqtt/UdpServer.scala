package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorLogging, ActorRef, Timers }
import akka.io.{ IO, Udp }
import akka.util.ByteString
import java.net.InetSocketAddress
import data.Messages.{MaybePacket, MaybeAck, MaybePing, MaybeRoomsensor}
import scala.concurrent.duration._

class UdpServer extends Actor with ActorLogging with Timers {
  import UdpServer._
  import context.system
  IO(Udp) ! Udp.Bind(self, new InetSocketAddress("0.0.0.0", 4124))

  var proxies = Map.empty[MAC,InetSocketAddress]

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
      case Udp.Received(ReceivedAck(MaybeAck(ack)), _) =>
        log.info("Received Ack: {}", ack)
        context.system.eventStream.publish(ack)
      case Udp.Received(ReceivedMessage(MaybeRoomsensor(msg)), _) =>
        log.info("Received Roomsensor: {}", msg)
        context.system.eventStream.publish(msg)
      case Udp.Received(ReceivedPing(MaybePing(ping)), src) =>
        val address = MAC(ByteString(ping.macAddress.toByteArray()))
        timers.startSingleTimer(ping.macAddress, RemoveProxy(address), 1.minute)
        if (proxies.get(address) != Some(src)) {
          proxies = proxies + (address -> src)
          log.info("Received Ping from {} at {}:{}, seen {}", address, src.getHostName, src.getPort,
            proxies.values.map(_.getHostName).mkString(", "))
        }
      case Udp.Received(data, remote) =>
        log.debug("Got {} unhandled bytes from {}", data.size, remote)
      case Udp.Unbind  ⇒ socket ! Udp.Unbind
      case Udp.Unbound ⇒ context.stop(self)

      case RemoveProxy(address) =>
        log.info("Removing proxy at {}", address)
        proxies -= address;
      case Send(mac, payload) if proxies.contains(mac) =>
        log.info("Sending {} bytes to {}", payload.length, proxies(mac).getHostName)
        socket ! Udp.Send(payload, proxies(mac))
    }
  }
}

object UdpServer {
  case class MAC(address: ByteString) {
    assume(address.length == 6)
    import MAC._

    override def toString = address.map("%02X" format _).mkString(":")
  }
  object MAC {
    private val digit = "([0-9a-fA-F]{2})"
    private val Valid = (digit + ":" + digit + ":" + digit + ":" + digit + ":" + digit + ":" + digit).r
    private def byte(hex: String): Byte = Integer.parseInt(hex, 16).toByte
    def unapply(s: String): Option[MAC] = s match {
      case Valid(a,b,c,d,e,f) =>
        Some(MAC(ByteString(byte(a), byte(b), byte(c), byte(d), byte(e), byte(f))))
      case _ =>
        None
    }
  }

  case class Send (mac: MAC, payload: ByteString) {

  }
  object Send {
    def apply(mac: MAC, packet: FS20.Packet): Send = Send(mac, 'F'.toByte +: packet.toByteString)
  }

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
  private val ReceivedPing = WithHeader('Q')
  private val ReceivedMessage = WithHeader('R',42)

  private case class RemoveProxy(mac: MAC)
}
