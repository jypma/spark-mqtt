package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.util.ByteString
import nl.ypmania.sparkmqtt.data.Messages.{ Ack, LampCommand, Packet }
import nl.ypmania.sparkmqtt.data.Protobuf.MaybeProtobuf
import nl.ypmania.sparkmqtt.data.Messages.LampState
import FS20CommandForwarder.AsInt
import MQTTActor.Topic
import MQTTActor.Topic._
import org.json4s.JsonAST.JObject
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods._

class LampForwarder(udpServer: ActorRef, mqttActor: ActorRef) extends Actor with ActorLogging {
  val MaybeLampState = new MaybeProtobuf(LampState)
  val root = /("rf12") / "lamp"

  var haveRegistered = Set.empty[Int]

  context.system.eventStream.subscribe(self, classOf[Packet])
  context.system.eventStream.subscribe(self, classOf[Ack])

  mqttActor ! MQTTActor.Subscribe(root./#)

  override def receive = {
    case msg@Packet(nodeId, _, _) if isLamp(nodeId) =>
      register(nodeId)
      stateActorFor(nodeId) ! msg

    case msg@Ack(nodeId, _) if isLamp(nodeId) =>
      commandActorFor(nodeId) ! msg

    case state: LampState =>
      val lampId = lampIdOf(sender)
      log.debug("Lamp {} is now {}", lampId, state)
      mqttActor ! MQTTActor.Message(root / lampId / "state" / "brightness", state.brightness.map(_.toString).getOrElse(""))

    case MQTTActor.Message(Topic(Seq("rf12", "lamp", AsInt(lampId), "command", "brightness")), AsInt(brightness), _)
        if 0 <= brightness && brightness <= 16 =>
      log.info("MQTT setting brightness to {}", brightness)
      commandActorFor(lampId) ! LampCommand(
        on = Some(if (brightness > 0) 1 else 0),
        brightness = Some(brightness))

    case MQTTActor.Message(Topic(Seq("rf12", "lamp", AsInt(lampId), "command")), cmd, _) =>
      val on = cmd.utf8String.toLowerCase() match {
        case "on" | "true" | "1" | "16" => true
        case _ => false
      }
      log.info("MQTT setting on: {}", on)
      commandActorFor(lampId) ! LampCommand(
        on = Some(if (on) 1 else 0))

  }

  def isLamp(nodeId: Int) = (nodeId >> 8) == 'l'

  def stateActorFor(nodeId: Int): ActorRef = {
    val name = s"state-${nodeId & 0xFF}"
    context.child(name).getOrElse {
      // TODO register in home assistant here
      context.actorOf(Props(new RxState(LampState)(nodeId, udpServer)), name)
    }
  }

  def commandActorFor(nodeId: Int): ActorRef = {
    val name = s"cmd-${nodeId & 0xFF}"
    context.child(name).getOrElse {
      context.actorOf(Props(new TxState(LampCommand)(LampCommand(), ('l' << 8) | nodeId, udpServer)), name)
    }
  }

  def lampIdOf(actor: ActorRef): Int = {
    val name = actor.path.name
    name.substring(name.indexOf("-") + 1).toInt
  }

  def register(nodeId: Int): Unit = {
    if (!haveRegistered.contains(nodeId)) {
      val id = nodeId & 0xFF;
      val topic = /("homeassistant") / "light" / s"rf12_lamp_${id}" / "config"
      val config: JObject =
        ("unique_id" -> s"rf12_lamp_${id}") ~
      ("platform" -> "mqtt") ~
      ("name" -> s"RF Lamp ${id}") ~
      ("command_topic" -> s"rf12/lamp/${id}/command") ~
      ("brightness_command_topic" -> s"rf12/lamp/${id}/command/brightness") ~
      ("payload_on" -> "ON") ~
      ("payload_off" -> "OFF") ~
      ("state_topic" -> s"rf12/lamp/${id}/state/brightness") ~
      ("brightness_scale" -> "16") ~
      ("state_value_template" -> "{% if (value != '0') %}ON{% else %}OFF{% endif %}") ~
      ("brightness_state_topic" -> s"rf12/lamp/${id}/state/brightness") ~
      ("on_command_type" -> "brightness")

      mqttActor ! MQTTActor.Message(topic, ByteString(pretty(render(config))), retained = true)
      haveRegistered += nodeId
    }
  }
}

object LampForwarder {

}
