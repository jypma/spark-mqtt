package nl.ypmania.sparkmqtt
import akka.actor.ActorRef
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Timers
import MQTTActor.Topic
import MQTTActor.Topic._
import FS20.fromCode
import UdpServer.{MAC, SendFS20}
import akka.util.ByteString
import scala.concurrent.duration._
import org.json4s.native.JsonMethods.parse
import org.json4s.JsonDSL._
import scala.util.Try
import org.json4s.JsonAST._
import scala.concurrent.duration._

class OpenMQTTGatewayForwarder(mqttActor: ActorRef) extends Actor with ActorLogging with Timers {
  import OpenMQTTGatewayForwarder._

  mqttActor ! MQTTActor.Subscribe(/("home") / "OpenMQTTGateway" / "433toMQTT")
  val targetTopic = /("433toMQTT") / "deduplicated"

  var seen = Set.empty[Long]

  override def receive = {
    case MQTTActor.Message(_, content, _) =>
      for {
        json <- Try(parse(content.utf8String)).toOption
        v <- (json \ "value" \\ classOf[JInt]).headOption
      } yield {
        val value = v.toLong
        if (seen(value)) {
          log.info("Ignoring duplicate: {}", value)
        } else {
          seen += value
          mqttActor ! MQTTActor.Message(targetTopic, content)
          timers.startSingleTimer(value, Remove(value), 300.milliseconds)
        }
      }

    case Remove(value) =>
      seen -= value
  }
}

object OpenMQTTGatewayForwarder {
  case class Remove(value: Long)
}
