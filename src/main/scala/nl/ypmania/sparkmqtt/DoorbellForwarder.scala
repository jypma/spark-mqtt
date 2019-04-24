package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorRef }
import UdpServer.Doorbell
import MQTTActor.Topic
import MQTTActor.Topic._
import akka.util.ByteString

class DoorbellForwarder(udpServer: ActorRef, mqttActor: ActorRef) extends Actor {
  val topic = /("rf12") / "doorbell" / "DB"

  context.system.eventStream.subscribe(self, classOf[Doorbell])

  override def receive = {
    case Doorbell(body) if body.headOption.contains(1) =>
      mqttActor ! MQTTActor.Message(topic, ByteString("RING"))
      // TODO have home assistant do this, once doorbell is flashed as normal TxState push button
      mqttActor ! MQTTActor.Message(/("sfx"), ByteString("doorbell.01.wav"))
      udpServer ! UdpServer.SendDoorbell(ByteString(2))
  }
}

object DoorbellForwarder {

}
