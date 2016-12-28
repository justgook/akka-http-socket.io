import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}

class SessionRegistryActor(props: Props) extends Actor with ActorLogging {

  import SessionRegistryActor._

  override def postStop(): Unit = {
    log.error("!!SessionRegistryActor DIE!!")
  }

  def receive(incoming: Map[String, ActorRef], outgoing: Map[String, ActorRef]): Receive = {

    case AskForSID(eio, sessionId) => //First connection
      //TODO add switcher for protocol
      val sid:String = sessionId match {
        case Some(id) => id
        case None     =>
          val actor = context.actorOf(props)
          context watch actor
          val pair: (String, ActorRef) = java.util.UUID.randomUUID().toString -> actor
          context become receive(incoming + pair, outgoing)
          pair._1
      }
      sender() ! sid

    case UpdateOut(sid, out)                                      => //Register Out for SID (where to send)
      log.info("UpdateOut - $sid")
      incoming.get(sid) match {
        case Some(_) =>
          context become receive(incoming, outgoing + (sid -> out))
        case None    => log.error("cannot find out actor for sid please")
      }
    case Disconnect(sid)                                          =>
      val newIncoming = incoming.get(sid) match {
        case Some(actor) =>
          actor ! PoisonPill
          incoming - sid
        case None        =>
          log.error("cannot find out actor for sid please")
          incoming
      }
      val newOutgoing = outgoing.get(sid) match {
        case Some(_) => outgoing - sid
        case None    =>
          log.error("cannot find out actor for sid please")
          outgoing
      }
      context become receive(newIncoming, newOutgoing)
      log.debug("Sid {} gone", sid)
    case IncomingMessage(sid, packetPattern("2", _, pData))       =>
      outgoing.get(sid) match {
        case Some(out) => out ! OutgoingMessage(s"3$pData")
        case None      => log.warning("PING - cannot find actor for sid - {}", sid)
      }
      log.debug("Ping received")
    case IncomingMessage(sid, packetPattern("4", pDataId, pData)) =>
      incoming.get(sid) match {
        case Some(actor) =>
          actor ! IncomingMessage(sid, pData)
        case None        => log.error("MESSAGE cannot find out actor for sid ({})", sid)
      }
    case IncomingMessage(sid, message)                            => log.warning("Unknown IncomingMessage ({} - {})", sid, message)

    case BroadcastOutgoingMessage(text, excludeMe) =>
      //TODO add filter to exclude sender
      outgoing foreach { case (sid, out) => out ! OutgoingMessage(s"42$text") } //TODO find why need that "2"

    case OutgoingMessage(text) =>
      //TODO update me to some cleaner way
      incoming.find(_._2 == sender) match {
        case Some((sid, _)) =>
          outgoing.get(sid) match {
            case Some(out) => out ! OutgoingMessage(s"42$text") //TODO find why need that "2"
            case None      => log.warning("cannot find out pipe for - ", sid)
          }
        case None           => log.error("GOT OutgoingMessage({}) from Unknown Actor ({})", text, sender)
      }

    case Terminated(t) =>
      log.error("!got Terminate! - {}", t)
  }

  override def receive: Receive = receive(Map.empty, Map.empty)
}

object SessionRegistryActor {
  private val packetPattern = """(\d)(\d*)?(.*)""".r

  def props(actor: Props): Props = Props(new SessionRegistryActor(actor))

  case class AskForSID(eio: Int, sid: Option[String] = None)

  case class UpdateOut(sid: String, actor: ActorRef)

  case class Disconnect(sid: String)

  case class IncomingMessage(sid: String, message: String)

  case class OutgoingMessage(text: String)

  case class BroadcastOutgoingMessage(text: String, excludeMe: Boolean = false)

  private case class WrappedOutgoingMessage(sid: String, text: String)


}
