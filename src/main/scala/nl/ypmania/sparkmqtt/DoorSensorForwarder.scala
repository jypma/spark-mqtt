package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorLogging, ActorRef, Timers }
import akka.util.ByteString
import nl.ypmania.sparkmqtt.data.Messages.DoorSensor
import MQTTActor.Topic./
import MQTTActor.Topic
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods._
import scala.concurrent.duration._

class DoorSensorForwarder(mqttActor: ActorRef) extends Actor with ActorLogging with Timers {
  import DoorSensorForwarder._
  context.system.eventStream.subscribe(self, classOf[DoorSensor])

  var haveRegistered = Set.empty[Int]

  override def receive = {
    case msg: DoorSensor =>
      val topic = stateTopic(msg.sender & 0xFF)

      def send(t: Topic): (Double => Unit) = v => {
        mqttActor ! MQTTActor.Message(t, ByteString(v.toString), retained = true)
      }

      def sendB(t: Topic): (Boolean => Unit) = v => {
        mqttActor ! MQTTActor.Message(t, ByteString(if (v) "ON" else "OFF"), retained = true)
      }

      msg.supply.filter(_ > 20).map(_ / 1000.0).foreach(send(topic / "supply"))
      msg.open.map(_ > 0).foreach(sendB(topic / "open"))

      register(msg.sender & 0xFF)
      timers.startSingleTimer(msg.sender, Remove(msg.sender & 0xFF), 120.minutes)

    case Remove(id) =>
      log.warning("Not heard from {} for a while, removing readings from MQTT.", id)
      val topic = stateTopic(id)
      for (name <- Seq("supply", "open")) {
        mqttActor ! MQTTActor.Message(topic / name, ByteString.empty, retained = true)
      }
  }

  def stateTopic(id: Int) = /("rf12") / "doorsensor" / (id & 0xFF).toString

  def registerSensor(id: Int, top: String, dClass: String, unit: String, name: String): Unit = {
    val topic = /("homeassistant") / "sensor" / s"rf12_door_${id}_${top}" / "config"
    val config: JObject =
      ("name" -> name) ~
      ("unique_id" -> s"rf12_door_${id}_${dClass}") ~
      ("state_topic" -> (stateTopic(id) / top).name) ~
      ("device_class" -> dClass) ~
      ("unit_of_measurement" -> unit)
    mqttActor ! MQTTActor.Message(topic, ByteString(pretty(render(config))), retained = true)
  }

  def registerBinarySensor(id: Int, top: String, dClass: String, name: String): Unit = {
    val topic = /("homeassistant") / "binary_sensor" / s"rf12_door_${id}_${top}" / "config"
    val config: JObject =
      ("name" -> name) ~
      ("unique_id" -> s"rf12_door_${id}_${dClass}") ~
      ("state_topic" -> (stateTopic(id) / top).name) ~
      ("device_class" -> dClass)

    mqttActor ! MQTTActor.Message(topic, ByteString(pretty(render(config))), retained = true)
  }

  def register(id: Int): Unit = {
    if (!haveRegistered.contains(id)) {
      log.info("registering {}", id)

      registerSensor(id, "supply", "battery", "V", s"RF12 Door Battery ${id}")
      registerBinarySensor(id, "open", "door", s"RF12 Door ${id}")

      haveRegistered += id
    }
  }
}

object DoorSensorForwarder {
  private case class Remove(id: Int)
}
