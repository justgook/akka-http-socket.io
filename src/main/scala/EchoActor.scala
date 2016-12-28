import SessionRegistryActor._
import akka.actor.{Actor, ActorLogging, Props}

class EchoActor extends Actor with ActorLogging {
  log.info("EchoActor Created")

  override def postStop(): Unit = {
    log.error("!!EchoActor Died!!")
  }
  def receive: Receive = {
    case Message(text) =>
      sender() ! BroadcastOutgoingMessage(text)
    case Ping(data) =>
      log.info("EchoActor Got Ping - {}", data)
      sender() ! Pong(data)
    case msg           =>
      log.info(s"EchoActor received unknown $msg - $sender")
      sender() ! "121233123123 12312 312 321"
  }
}

object EchoActor {
  def props = Props(new EchoActor)
}