package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorLogging, ActorRef, Timers }
import akka.util.ByteString
import nl.ypmania.sparkmqtt.data.Messages.RoomSensor
import MQTTActor.Topic./
import MQTTActor.Topic
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods._
import scala.concurrent.duration._

class RoomSensorForwarder(mqttActor: ActorRef) extends Actor with ActorLogging with Timers {
  import RoomSensorForwarder._
  context.system.eventStream.subscribe(self, classOf[RoomSensor])

  var haveRegistered = Set.empty[Int]

  override def receive = {
    case msg: RoomSensor =>
      val topic = stateTopic(msg.sender & 0xFF)

      def send(t: Topic): (Double => Unit) = v => {
        mqttActor ! MQTTActor.Message(t, ByteString(v.toString), retained = true)
      }

      def sendI(t: Topic): (Int => Unit) = v => {
        mqttActor ! MQTTActor.Message(t, ByteString(v.toString), retained = true)
      }

      msg.supply.filter(_ > 20).map(_ / 1000.0).foreach(send(topic / "supply"))
      msg.temp.filter(t => t > -200 && t < 400).map(_ / 10.0).foreach(send(topic / "temperature"))
      msg.humidity.filter(h => h > 10).map(_ / 10.0).foreach(send(topic / "humidity"))
      msg.lux.foreach(sendI(topic / "lux"))
      msg.motion.foreach(sendI(topic / "motion"))

      register(msg.sender & 0xFF)
      timers.startSingleTimer(msg.sender, Remove(msg.sender & 0xFF), 10.minutes)

    case Remove(id) =>
      log.warning("Not heard from {} for a while, removing readings from MQTT.", id)
      val topic = stateTopic(id)
      for (name <- Seq("supply", "temperature", "humidity", "lux", "motion")) {
        mqttActor ! MQTTActor.Message(topic / name, ByteString.empty, retained = true)
      }
  }

  def stateTopic(id: Int) = /("rf12") / "roomsensor" / (id & 0xFF).toString

  def registerSensor(id: Int, top: String, dClass: String, unit: String): Unit = {
    val topic = /("homeassistant") / "sensor" / s"rf12_${id}_${top}" / "config"
    val config: JObject =
      ("name" -> s"RF12 ${id} ${dClass}") ~
      ("state_topic" -> (stateTopic(id) / top).name) ~
      ("device_class" -> dClass) ~
      ("unit_of_measurement" -> unit)
    mqttActor ! MQTTActor.Message(topic, ByteString(pretty(render(config))), retained = true)
  }

  def registerBinarySensor(id: Int, top: String, dClass: String): Unit = {
    val topic = /("homeassistant") / "binary_sensor" / s"rf12_${id}_${top}" / "config"
    val config: JObject =
      ("name" -> s"RF12 ${id} ${dClass}") ~
      ("state_topic" -> (stateTopic(id) / top).name) ~
      ("device_class" -> dClass) ~
      ("off_delay" -> 5) ~
      ("payload_on" -> "true") ~
      ("value_template" -> "{% if (value != '0') %}true{% else %}false{% endif %}")

    mqttActor ! MQTTActor.Message(topic, ByteString(pretty(render(config))), retained = true)
  }

  def register(id: Int): Unit = {
    if (!haveRegistered.contains(id)) {
      log.info("registering {}", id)

      registerSensor(id, "temperature", "temperature", "°C")
      registerSensor(id, "humidity", "humidity", "%")
      registerSensor(id, "supply", "battery", "V")
      registerSensor(id, "lux", "illuminance", "lx")
      registerBinarySensor(id, "motion", "motion")

      haveRegistered += id
    }
  }
}

object RoomSensorForwarder {
  private case class Remove(id: Int)
}
