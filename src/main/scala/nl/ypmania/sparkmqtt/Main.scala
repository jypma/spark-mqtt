package nl.ypmania.sparkmqtt

import akka.actor.{ ActorSystem, Props }

object Main extends App {
  implicit val system = ActorSystem()
  val mqttActor = system.actorOf(Props(new MQTTActor))
  val udpServer = system.actorOf(Props(new UdpServer))
  system.actorOf(Props(new FS20StateForwarder(mqttActor)))
  system.actorOf(Props(new FS20CommandForwarder(mqttActor, udpServer)))
  system.actorOf(Props(new RoomSensorForwarder(mqttActor)))
  system.actorOf(Props(new LampForwarder(udpServer, mqttActor)))
  system.actorOf(Props(new DoorbellForwarder(udpServer, mqttActor)))
  system.actorOf(Props(new HeaterForwarder(udpServer, mqttActor)))
  system.actorOf(Props(new DoorSensorForwarder(mqttActor)))
  system.actorOf(Props(new OpenMQTTGatewayForwarder(mqttActor)))
}
