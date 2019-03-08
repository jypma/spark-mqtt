package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorRef }
import akka.util.ByteString
import nl.ypmania.sparkmqtt.data.Messages.RoomSensor
import MQTTActor.Topic./
import MQTTActor.Topic
import org.json4s.JsonAST._
import org.json4s.JsonDSL._

class RoomSensorForwarder(mqttActor: ActorRef) extends Actor {
  context.system.eventStream.subscribe(self, classOf[RoomSensor])

  override def receive = {
    case msg: RoomSensor =>
      val topic = /("rf12") / "roomsensor" / (msg.sender & 0xFF).toString

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
  }
}

