package nl.ypmania.sparkmqtt

import akka.util.ByteString

object FS20 {
  case class Packet(address: Address, command: Command, timerSeconds: Int)

  case class Address(houseHigh: Int, houseLow: Int, device: Int) {
    def houseCode: String = s"${code(houseHigh)}${code(houseLow)}"
    def deviceCode: String = s"${code(device)}"
    override def toString = s"${deviceCode}@${houseCode}"
  }

  case class Command(value: Int) extends AnyVal {
    override def toString = value match {
      case 0 => "OFF"
      case 1 => "DIM_1"
      case 2 => "DIM_2"
      case 3 => "DIM_3"
      case 4 => "DIM_4"
      case 5 => "DIM_5"
      case 6 => "DIM_6"
      case 7 => "DIM_7"
      case 8 => "DIM_8"
      case 9 => "DIM_9"
      case 10 => "DIM_10"
      case 11 => "DIM_11"
      case 12 => "DIM_12"
      case 13 => "DIM_13"
      case 14 => "DIM_14"
      case 15 => "DIM_15"
      case 16 => "ON_FULL"
      case 17 => "ON_PREVIOUS"
      case 18 => "TOGGLE"
      case 19 => "DIM_UP"
      case 20 => "DIM_DOWN"
      case 21 => "DIM_UP_DOWN"
      case 22 => "TIMER_SET"
      case 23 => "SEND_STATUS"
      case 24 => "TIMED_OFF"
      case 25 => "TIMED_ON_FULL"
      case 26 => "TIMED_ON_PREVIOUS"
      case 27 => "RESET"
      case _ => value.toString
    }
  }

  object Packet {
    def unapply(data: ByteString): Option[Packet] = data match {
      case d if d.length == 5 =>
        Some(Packet(Address(data(0), data(1), data(2)), Command(data(3)), 0))
      case d if d.length == 6 =>
        val t = data(4)
        val timerSeconds = (Math.pow(2, ((t >>> 4) & 0x0F)) * (t & 0x0F) * 0.25f).toInt
        Some(Packet(Address(data(0), data(1), data(2)), Command(data(3)), timerSeconds))
      case _ =>
        None
    }
  }

  private def code(i: Int): String = {
    var result = 0;
    result += ((i & 3) + 1);
    result += (((i >> 2) & 3) + 1) * 10;
    result += (((i >> 4) & 3) + 1) * 100;
    result += (((i >> 6) & 3) + 1) * 1000;
    result.toString()
  }
}
