package nl.ypmania.sparkmqtt.data

import akka.util.ByteString
import com.github.os72.protobuf.dynamic.{ DynamicSchema, MessageDefinition }
import com.google.protobuf.{ DynamicMessage }
import scala.util.Try
import nl.ypmania.sparkmqtt.data.Messages.{Packet,Ack,Ping,RoomSensor}
import scalapb.{ GeneratedMessage, GeneratedMessageCompanion, Message }

package object Messages {
  import Protobuf._
  val MaybePacket = new MaybeProtobuf(Packet)
  val MaybeAck = new MaybeProtobuf(Ack)
  val MaybePing = new MaybeProtobuf(Ping)
  val MaybeRoomsensor = new MaybeProtobuf(RoomSensor, (r:RoomSensor) => (r.sender >> 8) == 'Q')
}

object Protobuf {
  class MaybeProtobuf[A <: GeneratedMessage with Message[A]](t: GeneratedMessageCompanion[A], p: A => Boolean = all) {
    def unapply(bytes: ByteString): Option[A] = Try(t.parseFrom(bytes.toArray)).toOption.filter(p)
  }

  def all[A]: A => Boolean = (a => true)

  implicit class ProtoExt[A <: GeneratedMessage with Message[A]](a: A) {
    def toByteString = ByteString(a.toByteArray)
  }
}
