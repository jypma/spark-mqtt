package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorLogging, ActorRef, Timers }
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.PathMatcher
import akka.http.scaladsl.server.PathMatcher1
import akka.http.scaladsl.server.PathMatcher._
import akka.http.scaladsl.server.PathMatchers._
import MQTTActor.Topic
import MQTTActor.Topic._
import FS20.fromCode
import UdpServer.{MAC, SendFS20}
import akka.util.ByteString
import scala.concurrent.duration._

class FS20CommandForwarder(mqttActor: ActorRef, udpServer: ActorRef) extends Actor with ActorLogging with Timers {
  import FS20CommandForwarder._

  mqttActor ! MQTTActor.Subscribe(/("fs20")./#)

  private def repeatInterval = ((math.random() * 70) + 70).milliseconds

  private def send(packet: FS20.Packet, mac: MAC): Unit = {
    log.debug("Command to set {} to {} through {}", packet.address, packet.command, mac)
    udpServer ! SendFS20(mac, packet)
    timers.startSingleTimer(packet.address, Repeat(3, mac, packet), repeatInterval)
  }

  override def receive = {
    case MQTTActor.Message(
      Topic(Seq("fs20", FS20House(houseHi, houseLo), FS20Device(device), "command", MAC(mac))),
      FS20.Command.AsByteString(command), _) =>

      val address = FS20.Address(fromCode(houseHi), fromCode(houseLo), fromCode(device))
      send(FS20.Packet(address, command), mac)

    case MQTTActor.Message(
      Topic(Seq("fs20", FS20House(houseHi, houseLo), FS20Device(device), "brightness_command", MAC(mac))),
      AsInt(brightness), _) if 0 <= brightness && brightness <= 16 =>

      val address = FS20.Address(fromCode(houseHi), fromCode(houseLo), fromCode(device))
      val command = FS20.Command(brightness.toByte)
      send(FS20.Packet(address, command), mac)

    case msg@Repeat(n, mac, packet) =>
      udpServer ! SendFS20(mac, packet)

      if (n > 0) {
        timers.startSingleTimer(packet.address, msg.again, repeatInterval)
      }
  }
}

object FS20CommandForwarder {
  val FS20House = "([1-4]{4})([1-4]{4})".r
  val FS20Device = "([1-4]{4})".r

  object AsInt {
    def unapply(s: ByteString): Option[Int] = util.Try(s.utf8String.toInt).toOption
    def unapply(s: String): Option[Int] = util.Try(s.toInt).toOption
  }

  private case class Repeat(n: Int, mac: MAC, packet: FS20.Packet) {
    def again = copy(n = n -1)
  }
}
