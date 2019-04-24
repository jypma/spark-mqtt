package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Timers }
import akka.util.ByteString
import nl.ypmania.sparkmqtt.data.Messages.{ Ack, HeaterCommand, HeaterState, Packet }
import nl.ypmania.sparkmqtt.data.Protobuf.MaybeProtobuf
import FS20CommandForwarder.AsInt
import MQTTActor.Topic
import MQTTActor.Topic._
import org.json4s.JsonAST.JObject
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods._
import scala.concurrent.duration._

class HeaterForwarder(udpServer: ActorRef, mqttActor: ActorRef) extends Actor with ActorLogging with Timers {
  import HeaterForwarder._

  val MaybeHeaterState = new MaybeProtobuf(HeaterState)
  val root = /("rf12") / "heater"
  val count = 12

  var haveRegistered = Set.empty[Int]
  var lastCommand = HeaterCommand()

  context.system.eventStream.subscribe(self, classOf[Packet])
  context.system.eventStream.subscribe(self, classOf[Ack])

  mqttActor ! MQTTActor.Subscribe(root./#)

  override def receive = {
    case msg@Packet(nodeId, _, _) if isHeater(nodeId) =>
      register(nodeId)
      stateActorFor(nodeId) ! msg

    case msg@Ack(nodeId, _) if isHeater(nodeId) =>
      commandActorFor(nodeId) ! msg

    case state: HeaterState =>
      val id = idOf(sender)
      log.debug("Heater {} is now {}", id, state)
      for (bit <- 0 until count) {
        mqttActor ! MQTTActor.Message(root / id / "state" / bit.toString,
          state.values.map(v => if ((v & (1 << bit)) > 0) "ON" else "OFF").getOrElse(""))
      }

    case MQTTActor.Message(Topic(Seq("rf12", "heater", AsInt(id), "command", AsInt(channel))), body, _) =>
      val on = body.utf8String.toLowerCase() match {
        case "on" => true
        case "true" => true
        case "1" => true
        case _ => false
      }
      appendToCommand(channel, on)
      log.info("Sending {}", lastCommand)
      commandActorFor(id) ! lastCommand
      timers.startSingleTimer(ForgetLastCommand, ForgetLastCommand, 1.minute)

    case ForgetLastCommand =>
      lastCommand = HeaterCommand()
  }

  def appendToCommand(channel: Int, on: Boolean): Unit = {
    if (on) {
      lastCommand = HeaterCommand(
        turnOn = Some(setBit(lastCommand.turnOn, channel)),
        turnOff = Some(clearBit(lastCommand.turnOff, channel)))
    } else {
      lastCommand = HeaterCommand(
        turnOn = Some(clearBit(lastCommand.turnOn, channel)),
        turnOff = Some(setBit(lastCommand.turnOff, channel)))
    }
  }

  def setBit(value: Option[Int], bit: Int) = value.getOrElse(0) | (1 << bit)
  def clearBit(value: Option[Int], bit: Int) = value.getOrElse(0) & ~(1 << bit)

  def isHeater(nodeId: Int) = (nodeId >> 8) == 'e'

  def stateActorFor(nodeId: Int): ActorRef = {
    val name = s"state-${nodeId & 0xFF}"
    context.child(name).getOrElse {
      context.actorOf(Props(new RxState(HeaterState)(nodeId, udpServer)), name)
    }
  }

  def commandActorFor(nodeId: Int): ActorRef = {
    val name = s"cmd-${nodeId & 0xFF}"
    context.child(name).getOrElse {
      context.actorOf(Props(new TxState(HeaterCommand)(HeaterCommand(), ('e' << 8) | nodeId, udpServer)), name)
    }
  }

  def idOf(actor: ActorRef): Int = {
    val name = actor.path.name
    name.substring(name.indexOf("-") + 1).toInt
  }

  def register(nodeId: Int): Unit = {
    if (!haveRegistered.contains(nodeId)) {
      // TODO register all 12 channels as switches

      haveRegistered += nodeId
    }
  }
}

object HeaterForwarder {
  case object ForgetLastCommand
}
