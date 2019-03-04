package nl.ypmania.sparkmqtt

import akka.actor.{ Actor, ActorLogging }
import akka.http.scaladsl.model.Uri.Path
import akka.util.ByteString
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import org.eclipse.paho.client.mqttv3._
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MQTTActor extends Actor with ActorLogging {
  import MQTTActor._

  val client = {
    val host = context.system.settings.config.getString("mqtt.host")
    val port = context.system.settings.config.getInt("mqtt.port")
    val publisherId = context.system.settings.config.getString("mqtt.publisher-id")

    val c = new MqttClient(s"tcp://${host}:${port}", publisherId, new MemoryPersistence())
    val options = new MqttConnectOptions()
    options.setAutomaticReconnect(true)
    options.setCleanSession(false)
    options.setConnectionTimeout(10)
    c.connect(options)
    c
  }

  def receive = {
    case Message(topic, data, retained) =>
      val msg = new MqttMessage(data.toArray)
      msg.setQos(0)
      msg.setRetained(retained)
      try {
        log.debug("Publishing to {}", topic)
        client.publish(topic.name, msg)
      } catch {
        case x: MqttException =>
          log.error(x, "Could not send MQTT message. Message dropped.")
      }

    case Subscribe(topicSpec) =>
      val target = sender
      val _self = self
      log.debug("Subscribing {} to {}", sender.path, topicSpec)
      client.subscribe(topicSpec.name, new IMqttMessageListener {
        override def messageArrived(topic: String, message: MqttMessage): Unit = {
          val msg = Message(Topic(topic), ByteString(message.getPayload), message.isRetained())
          target.tell(msg, _self)
        }
      })
  }
}

object MQTTActor {

  case class Topic(name: String) {
    def /(segment: String) = Topic(s"${name}/${URLEncoder.encode(segment,"UTF-8")}")
    def /+ = Topic(s"${name}/+")
    def /+/(segment: String) = Topic(s"${name}/+/${URLEncoder.encode(segment,"UTF-8")}")
    def /# = Topic(s"${name}/#")
    def segments: List[String] = name.split("/").toList
  }
  object Topic {
    implicit def toTopic(s: String) = Topic(URLEncoder.encode(s, "UTF-8"))
    def /(segment: String) = Topic(URLEncoder.encode(segment, "UTF-8"))
    def wildcard = Topic("#")
  }

  case class Message(topic: Topic, data: ByteString, retained: Boolean = false)
  object Message {
    def apply(topic: Topic, data: String): Message = Message(topic, ByteString(data))
  }

  case class Subscribe(topicSpec: Topic)
}
