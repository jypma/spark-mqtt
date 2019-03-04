package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.PathMatcher
import akka.http.scaladsl.server.PathMatcher1
import akka.http.scaladsl.server.PathMatcher._
import akka.http.scaladsl.server.PathMatchers._
import MQTTActor.Topic
import MQTTActor.Topic._
import FS20.fromCode
import UdpServer.{MAC, Send}

class FS20CommandForwarder(mqttActor: ActorRef, udpServer: ActorRef) extends Actor with ActorLogging {
  import FS20CommandForwarder._

  mqttActor ! MQTTActor.Subscribe("fs20" /#)

  override def receive = {
    case MQTTActor.Message(topic, FS20.Command.AsByteString(command), _) =>
      topic.segments match {
        case List("fs20", FS20House(houseHi, houseLo), FS20Device(device), "command", MAC(mac)) =>
          val address = FS20.Address(fromCode(houseHi), fromCode(houseLo), fromCode(device))
          log.debug("Command to set {} to {} through {}", address, command, mac)
          udpServer ! Send(mac, FS20.Packet(address, command))
        case _ =>
      }
  }
}

object FS20CommandForwarder {
  val FS20House = "([1-4]{4})([1-4]{4})".r
  val FS20Device = "([1-4]{4})".r
}
