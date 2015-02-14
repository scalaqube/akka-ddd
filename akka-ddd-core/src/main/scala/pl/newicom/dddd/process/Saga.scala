package pl.newicom.dddd.process

import akka.actor.{ActorPath, ActorLogging, Props}
import akka.persistence.{RecoveryCompleted, AtLeastOnceDelivery, PersistentActor}
import pl.newicom.dddd.actor.{BusinessEntityActorFactory, GracefulPassivation, PassivationConfig}
import pl.newicom.dddd.aggregate._
import pl.newicom.dddd.delivery.protocol.alod._
import pl.newicom.dddd.messaging.MetaData.{DeliveryId, EventPosition}
import pl.newicom.dddd.messaging.command.CommandMessage
import pl.newicom.dddd.messaging.event.EventMessage
import pl.newicom.dddd.messaging.{Deduplication, Message}
import pl.newicom.dddd.process.SagaManager.EventDelivered

abstract class SagaActorFactory[A <: Saga] extends BusinessEntityActorFactory[A] {
  import scala.concurrent.duration._

  def props(pc: PassivationConfig): Props
  def inactivityTimeout: Duration = 1.minute
}

trait SagaConfig[A <: Saga] {
  /**
   * Name of Business Process Stream (bps)
   */
  def bpsName: String

  /**
   * Correlation ID identifies process instance. It is used to route EventMessage
   * messages created by [[SagaManager]] to [[Saga]] instance,
   */
  def correlationIdResolver: DomainEvent => EntityId

  /**
   * Might be used for events deserialization purposes
   */
  def interest: Option[List[Class[_]]] = None
}

trait Saga extends BusinessEntity with GracefulPassivation with PersistentActor
  with Deduplication with AtLeastOnceDelivery with ActorLogging {

  def sagaId = self.path.name

  override def id = sagaId

  override def persistenceId: String = sagaId

  override def aroundReceive(receive: Receive, msg: Any): Unit = {
    super.aroundReceive(receiveDuplicate(acknowledgeEvent).orElse(receive), msg)
  }

  override def receiveCommand: Receive = receiveCommandDeliveryReceipt orElse receiveEvent orElse receiveUnexpected

  def deliverCommand(office: ActorPath, command: Command): Unit = {
    deliver(office, deliveryId => {
      CommandMessage(command).withMetaAttribute(DeliveryId, deliveryId)
    })
  }

  def receiveCommandDeliveryReceipt: Receive = {
    case receipt: Delivered =>
      persist(receipt)(_updateState)
  }

  /**
   * Defines business process logic (state transitions).
   * State transition happens when raise(event) is called.
   * No state transition indicates the current event message could have been received out-of-order.
   */
  def receiveEvent: Receive

  override def receiveRecover: Receive = {
    case rc: RecoveryCompleted =>
      // do nothing
    case msg: Any =>
      _updateState(msg)

  }

  /**
   * Triggers state transition
   */
  def raise(em: EventMessage): Unit =
    persist(em) { persisted =>
      log.debug("Event message persisted: {}", persisted)
      _updateState(persisted)
      acknowledgeEvent(persisted)
    }

  /**
   * Event handler called on state transition
   */
  def updateState(e: DomainEvent)

  private def _updateState(msg: Any): Unit = msg match {
    case em: EventMessage =>
      messageProcessed(em)
      updateState(em.event)
    case receipt: Delivered =>
      confirmDelivery(receipt.deliveryId)
      log.debug(s"Delivery of command confirmed (receipt: $receipt)")
      // TODO allow Saga to react on command failure?
  }

  private def acknowledgeEvent(em: Message) {
    val deliveryReceipt = EventDelivered(em.getMetaAttribute(DeliveryId), em.getMetaAttribute(EventPosition))
    sender() ! deliveryReceipt
    log.debug(s"Delivery receipt (for received event) sent ($deliveryReceipt)")
  }

  def receiveUnexpected: Receive = {
    case em: EventMessage => handleUnexpectedEvent(em)
  }

  def handleUnexpectedEvent(em: EventMessage): Unit = {
    log.warning(s"Unhandled: $em") // unhandled event should be redelivered by SagaManager
  }

}

