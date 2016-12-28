import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}

class SessionRegistryActor(props: Props) extends Actor with ActorLogging {

  import SessionRegistryActor._

  implicit val materializer = ActorMaterializer()

  override def postStop(): Unit = {
    log.error("!!SessionRegistryActor DIE!!")
  }

  def receive(actors: Map[String, ActorHolder], clientsConnections: Map[String, ActorRef]): Receive = {

    case AskForSID(eio, sessionId)                                => //First connection
      val result: String = sessionId match {
        case Some(id) => id
        case None     =>
          val sid = java.util.UUID.randomUUID().toString
          val actor = context.actorOf(props)
          context watch actor
          val fromActor: Sink[SessionRegistryActor.ActorRegistryCommunication, NotUsed] =
            Flow[SessionRegistryActor.ActorRegistryCommunication].map {
              case SessionRegistryActor.Pong(key)                                 => SessionRegistryActor.WrappedPong(sid, key)
              case SessionRegistryActor.Ping(key)                                 => SessionRegistryActor.WrappedMessage(sid, key) // server should not send Ping
              case SessionRegistryActor.Message(text)                             => SessionRegistryActor.WrappedMessage(sid, text)
              case SessionRegistryActor.BroadcastOutgoingMessage(text, excludeMe) => SessionRegistryActor.WrappedBroadcastOutgoingMessage(sid, text, excludeMe)
            }
            .to(Sink.actorRef[SessionRegistryActor.ActorRegistryCommunicationWrappers](self, Disconnect(sid)))
          val toActor = Source.actorRef[SessionRegistryActor.ActorRegistryCommunication](10, OverflowStrategy.fail)
          val ref = Flow[SessionRegistryActor.ActorRegistryCommunication]
                    .to(fromActor)
                    .runWith(toActor)
          context become receive(actors + (sid -> ActorHolder(actor, ref)), clientsConnections)
          sid
      }
      sender() ! result
    case UpdateOut(sid, out)                                      => //Register Out for SID (where to send)
      log.debug(s"UpdateOut - {}", sid)
      actors.get(sid) match {
        case Some(_) =>
          context become receive(actors, clientsConnections + (sid -> out))
        case None    => log.error("UpdateOut cannot find out actor for sid - {}", sid)
      }
    case Disconnect(sid)                                          =>
      val newIncoming = actors.get(sid) match {
        case Some(actor) =>
          actor.kill()
          actors - sid
        case None        =>
          log.error("Disconnect:newIncoming cannot find out actor for sid - {}", sid)
          actors
      }
      val newOutgoing = clientsConnections.get(sid) match {
        case Some(_) => clientsConnections - sid
        case None    =>
          log.error("Disconnect:newOutgoing cannot find out actor for sid - {}", sid)
          clientsConnections
      }
      context become receive(newIncoming, newOutgoing)
      log.debug("Sid {} gone", sid)
    case IncomingMessage(sid, packetPattern("2", _, pData))       =>
      actors.get(sid) match {
        case Some(actor) =>
          actor ! Ping(pData)
        case None        => log.error("PING cannot find out actor for sid ({})", sid)
      }
      log.debug("Ping received")
    case IncomingMessage(sid, packetPattern("4", pDataId, pData)) =>
      actors.get(sid) match {
        case Some(actor) =>
          actor ! Message(pData)
        case None        => log.error("MESSAGE cannot find out actor for sid ({})", sid)
      }
    case IncomingMessage(sid, packetPattern("5", pDataId, pData)) =>
      //      Before engine.io switches a transport, it tests, if server and client can communicate over this transport. If this test succeed, the client sends an upgrade packets which requests the server to flush its cache on the old transport and switch to the new transport.
      log.info("got Upgrade message ")
    case IncomingMessage(sid, message)                            => log.warning("Unknown IncomingMessage ({} - {})", sid, message)
    case WrappedPong(sid, pData)                                  =>
      clientsConnections.get(sid) match {
        case Some(out) =>
          out ! OutgoingMessage(s"3$pData")
        case None      => log.warning("WrappedPong - cannot find actor for sid - {}", sid)
      }
    case WrappedMessage(sid, pData)                               =>
      clientsConnections.get(sid) match {
        case Some(out) =>
          out ! OutgoingMessage(s"42$pData") //TODO find why need that "2"
        case None      => log.warning("PING - cannot find actor for sid - {}", sid)
      }
    case WrappedBroadcastOutgoingMessage(sid, text, excludeMe)    =>
      clientsConnections foreach { case (clientId, out) => if (!excludeMe || clientId != sid) out ! OutgoingMessage(s"42$text") }
    case Terminated(t)                                            =>
      log.error("!got Terminate! - {}", t)
    case t                                                        => log.info("SessionRegistryActor got unknown message - {}", t)

  }

  override def receive: Receive = receive(Map.empty, Map.empty)


}

object SessionRegistryActor {
  private val packetPattern = """(\d)(\d*)?(.*)""".r

  def props(actor: Props): Props = Props(new SessionRegistryActor(actor))

  sealed trait ActorRegistryCommunication

  sealed trait ActorRegistryCommunicationWrappers

  sealed trait ToActor extends ActorRegistryCommunication

  sealed trait FromActor extends ActorRegistryCommunication

  case class AskForSID(eio: Int, sid: Option[String] = None)

  case class UpdateOut(sid: String, actor: ActorRef)

  case class Disconnect(sid: String)

  case class IncomingMessage(sid: String, message: String)

  case class OutgoingMessage(text: String)

  case class BroadcastOutgoingMessage(text: String, excludeMe: Boolean = false) extends FromActor

  //TODO update to `Message(text: String, channel: String)`
  case class Message(text: String) extends ActorRegistryCommunication

  case class Ping(key: String) extends ToActor


  case class Pong(key: String) extends FromActor

  private case class ActorHolder(actor: ActorRef, sender: ActorRef) {
    def kill(): Unit = {
      actor ! PoisonPill
      sender ! PoisonPill
    }

    def !(msg: ActorRegistryCommunication): Unit = {
      actor.tell(msg, sender)
    }

  }
  private case class WrappedPing(sid: String, key: String) extends ActorRegistryCommunicationWrappers
  private case class WrappedPong(sid: String, key: String) extends ActorRegistryCommunicationWrappers

  private case class WrappedMessage(sid: String, text: String) extends ActorRegistryCommunicationWrappers

  private case class WrappedBroadcastOutgoingMessage(sid: String, text: String, excludeMe: Boolean = false) extends ActorRegistryCommunicationWrappers


}
