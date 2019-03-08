package nl.ypmania.sparkmqtt

import akka.actor.{ ActorSystem, Props }

object Main extends App {
  implicit val system = ActorSystem()
  val udpServer = system.actorOf(Props(new UdpServer))
  val mqttActor = system.actorOf(Props(new MQTTActor))
  system.actorOf(Props(new FS20StateForwarder(mqttActor)))
  system.actorOf(Props(new FS20CommandForwarder(mqttActor, udpServer)))
  system.actorOf(Props(new RoomSensorForwarder(mqttActor)))
}
