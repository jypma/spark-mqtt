package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorLogging, ActorRef, Timers }
import akka.http.scaladsl.model.Uri.Path
import scala.concurrent.duration._

class FS20Forwarder(mqttActor: ActorRef) extends Actor with Timers with ActorLogging {
  import FS20Forwarder._

  private var seen = Set.empty[FS20.Packet]

  context.system.eventStream.subscribe(self, classOf[FS20.Packet])

  override def receive = {
    case packet: FS20.Packet if !seen.contains(packet) =>
      seen += packet
      // FS20 repeat speed really is only 100ms, but it takes a while for the UDP train to arrive.
      timers.startSingleTimer(packet, Unsee(packet), 300.milliseconds)
      forward(packet)
    case Unsee(packet) =>
      seen -= packet

    case packet: FS20.Packet => // ignore seen.
  }

  private def forward(packet: FS20.Packet): Unit = {
    val topic = topicFor(packet.address)
    log.debug("Forwarding {} to {}", packet, topic)
    mqttActor ! MQTTActor.Message(topic / "command", packet.command.toString)
  }

  private def topicFor(address: FS20.Address): Path = {
    Path("fs20") / address.houseCode / address.deviceCode
  }
}

object FS20Forwarder {
  private case class Unsee(packet: FS20.Packet)
}
