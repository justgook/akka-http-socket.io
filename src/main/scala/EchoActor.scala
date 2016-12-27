import SessionRegistryActor.{BroadcastOutgoingMessage, IncomingMessage, OutgoingMessage}
import akka.actor.{Actor, ActorLogging, Props}

class EchoActor extends Actor with ActorLogging {
  log.info("EchoActor Created")

  def receive: Receive = {
    case IncomingMessage(sid, message) =>
      sender() ! BroadcastOutgoingMessage(message)
      sender() ! OutgoingMessage(message)
    case msg                           => log.info(s"EchoActor received unknown $msg")
  }
}

object EchoActor {
  def props = Props(new EchoActor)
}