package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorLogging, ActorRef, Timers }
import akka.io.{ IO, Udp }
import akka.util.ByteString
import java.net.InetSocketAddress
import data.Messages.{MaybePacket, MaybeAck, MaybePing, MaybeRoomsensor, MaybeDoorSensor }
import nl.ypmania.sparkmqtt.data.Messages.{ Ack, Packet }
import scala.collection.immutable.HashSet
import scala.concurrent.duration._
import scala.util.Random
import github.gphat.censorinus.StatsDClient
import nl.ypmania.sparkmqtt.data.Messages.Ping

class UdpServer(stats: StatsDClient) extends Actor with ActorLogging with Timers {
  import UdpServer._
  import context.system
  import context.dispatcher

  IO(Udp) ! Udp.Bind(self, new InetSocketAddress("0.0.0.0", 4124))

  var proxies = Map.empty[MAC,InetSocketAddress]
  var preferredProxies = Map.empty[Int,Set[InetSocketAddress]]
  var recentlyReceived = Map.empty[Any,Set[InetSocketAddress]]

  def receive = {
    case Udp.Bound(local) ⇒
      context.become(ready(sender()))
  }

  def incoming(pkt: Any, src: InetSocketAddress)(f: => Unit): Unit = {
    val pass = recentlyReceived.get(pkt) match {
      case None =>
        // new packet, pass
        recentlyReceived += (pkt -> Set(src))
        true
      case Some(seen) if !seen.contains(src) =>
        // duplicate via different proxy, ignore
        recentlyReceived += (pkt -> (seen + src))
        false
      case Some(seen) =>
        // duplicate via same proxy, pass, toss out the rest to prevent new duplicates
        recentlyReceived += (pkt -> Set(src))
        true

    }
    if (pass) {
      f
    }
    timers.startSingleTimer(pkt, RemoveReceived(pkt), 100.milliseconds)
  }

  def ready(socket: ActorRef): Receive = {
    log.info("Bound to socket")

    {
      case Udp.Received(ReceivedFS20(FS20.Packet(packet)), src) => incoming(packet, src) {
        log.info("Received FS20: {}", packet)
        context.system.eventStream.publish(packet)
      }
      case Udp.Received(ReceivedPacket(MaybePacket(packet)), src) => incoming(packet, src) {
        log.info("Received Packet: {} from {}:{} via {}", packet, (packet.nodeId >> 8).toChar, (packet.nodeId & 0xFF), src)
        preferProxy(packet.nodeId, src)
        context.system.eventStream.publish(packet)
      }
      case Udp.Received(ReceivedAck(MaybeAck(ack)), src) => incoming(ack, src) {
        log.info("Received Ack: {}", ack)
        preferProxy(ack.nodeId, src)
        context.system.eventStream.publish(ack)
      }
      case Udp.Received(ReceivedMessage(MaybeRoomsensor(msg)), src) => incoming(msg, src) {
        log.info("Received Roomsensor: {}", msg)
        context.system.eventStream.publish(msg)
      }
      case Udp.Received(ReceivedMessage(MaybeDoorSensor(msg)), src) => incoming(msg, src) {
        log.info("Received DoorSensor: {}", msg)
        context.system.eventStream.publish(msg)
      }
      case Udp.Received(ReceivedPing(MaybePing(ping)), src) =>
        val address = MAC(ByteString(ping.macAddress.toByteArray()))
        timers.startSingleTimer(ping.macAddress, RemoveProxy(address), 1.minute)
        if (proxies.get(address) != Some(src)) {
          proxies = proxies + (address -> src)
          log.info("Received Ping from {} at {}:{}, seen {}", address, src.getHostName, src.getPort,
            proxies.map(t => t._1 + " -> " + t._2.getAddress()).mkString(", "))
        }
        val add = address.toString.replaceAll(":","")
        sendStats(s"proxy.${add}", ping)

      // TODO re-flash doorbell as a normal TxState "momentary button" class
      case Udp.Received(ReceivedDoorbell(body), src) =>
        // don't de-duplicate, the old protocol relies on retransmit to get more ack's
        log.info("Received doorbell: {}", body)
        preferProxy(0xFFFF, src)
        context.system.eventStream.publish(Doorbell(body))
      case SendDoorbell(body) if preferredProxies.contains(0xFFFF) =>
        for (addr <- preferredProxies(0xFFFF)) {
          socket ! Udp.Send(ByteString('R', 1, 'S', 'P', 'D', 'B') ++ body, addr)
        }
      case msg@SendDoorbell =>
        log.warning("Not sending {} to doorbell, since no proxy known for it", msg)
      case Udp.Received(SentAck(_), _) =>
        // we ignore acks that we sent out ourselves; they are just echoed back by other proxies.
      case Udp.Received(data, remote) =>
        log.debug("Unhandled {} from {}", data, remote)
      case Udp.Unbind  ⇒ socket ! Udp.Unbind
      case Udp.Unbound ⇒ context.stop(self)

      case RemoveProxy(address) =>
        log.info("Removing proxy at {}", address)
        proxies -= address;

      case SendFS20(mac, packet) if proxies.contains(mac) =>
        val payload = 'F'.toByte +: packet.toByteString
        log.info("Sending {} bytes to {}", payload.length, proxies(mac).getHostName)
        socket ! Udp.Send(payload, proxies(mac))
      case msg:SendFS20 =>
        log.warning("Not sending {}, since proxy is not available. Known are {}", msg,
          proxies.keySet.mkString(", "))

      case SendAck(ack) if preferredProxies.contains(ack.nodeId) =>
        val addr = randomProxy(ack.nodeId)
        log.debug("Sending {} ({}) to {} at {}", ack, ByteString(ack.toByteArray), proxies.find {  case (mac,a) => a == addr }.map(_._1), addr)
        socket ! Udp.Send(ByteString('R', 5) ++ ByteString(ack.toByteArray), addr)
      case SendAck(ack) =>
        log.warning("No proxy known for ack {}", ack)

      case SendPacket(pkt) if preferredProxies.contains(pkt.nodeId) =>
        val targets = Random.shuffle(preferredProxies(pkt.nodeId).toSeq)
        log.debug("Sending {} bytes to {}", pkt.body.map(_.size).getOrElse(0), targets.map(_.getHostName()))
        for ((addr, i) <- targets.zipWithIndex) {
          val msg = Udp.Send(ByteString('R', 2) ++ ByteString(pkt.toByteArray), addr)
          if (i == 0) {
            socket ! msg
          } else {
            context.system.scheduler.scheduleOnce(i * 100.milliseconds, socket, msg)
          }
        }
      case SendPacket(pkt) =>
        log.warning("No proxy known for nodeId {}", pkt.nodeId)

      case RemovePreferred(nodeId, addr) =>
        log.warning("Removing preferred proxy for {} at {}", nodeId, addr)
        preferredProxies += nodeId -> (preferredProxies.getOrElse(nodeId, HashSet.empty) - addr)

      case RemoveReceived(pkt) =>
        recentlyReceived -= pkt
    }
  }

  def randomProxy(nodeId: Int): InetSocketAddress = {
    val available = preferredProxies(nodeId).toSeq
    available(Random.nextInt(available.size))
  }

  def preferProxy(nodeId: Int, addr: InetSocketAddress): Unit = {
    preferredProxies += nodeId -> (preferredProxies.getOrElse(nodeId, HashSet.empty) + addr)
    timers.startSingleTimer((nodeId, addr), RemovePreferred(nodeId, addr), 24.hours)
  }

  def sendStats(prefix: String, ping: Ping): Unit = {
    stats.increment(s"$prefix.packetsOut", ping.packetsOut.getOrElse(0): Int)
    stats.increment(s"$prefix.packetsIn", ping.packetsIn.getOrElse(0): Int)
    stats.increment(s"$prefix.rfmWatchdogs", ping.rfmWatchdogs.getOrElse(0): Int)
    stats.increment(s"$prefix.espWatchdogs", ping.espWatchdogs.getOrElse(0): Int)
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

  case class SendAck(ack: Ack)
  case class SendPacket(packet: Packet)
  case class SendFS20(mac: MAC, packet: FS20.Packet)
  case class SendDoorbell(body: ByteString)

  case class Doorbell(body: ByteString)

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
  private val ReceivedAck = WithHeader('R',1)    // Ack from node to spark. Spark to node has header 5.
  private val SentAck = WithHeader('R', 5)
  private val ReceivedPing = WithHeader('Q')
  private val ReceivedMessage = WithHeader('R',42)
  private val ReceivedDoorbell = WithHeader('R',2,'D','B',' ',' ')

  private case class RemoveProxy(mac: MAC)
  private case class RemovePreferred(nodeId: Int, addr: InetSocketAddress)
  private case class RemoveReceived(pkt: Any)
}
