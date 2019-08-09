package coop.rchain.casper

import com.google.protobuf.ByteString
import coop.rchain.rspace.Blake2b256Hash
import coop.rchain.rspace.trace._

import scala.collection.BitSet

final case class TuplespaceEvent(
    incoming: TuplespaceOperation,
    matched: Option[TuplespaceOperation]
)
final case class TuplespaceOperation(
    polarity: Polarity,
    cardinality: Cardinality,
    eventHash: Blake2b256Hash
)

trait Polarity
case object Send    extends Polarity
case object Receive extends Polarity

trait Cardinality
case object Linear    extends Cardinality
case object NonLinear extends Cardinality

object TuplespaceEvent {

  implicit private[this] def liftProduce(produce: Produce): TuplespaceOperation =
    TuplespaceOperation(Send, if (produce.persistent) NonLinear else Linear, produce.hash)

  implicit private[this] def liftConsume(consume: Consume): TuplespaceOperation =
    TuplespaceOperation(Receive, if (consume.persistent) NonLinear else Linear, consume.hash)

  def from(produce: Produce): (Blake2b256Hash, TuplespaceEvent) =
    produce.channelsHash -> TuplespaceEvent(
      produce,
      None
    )

  def from(consume: Consume): Option[(Blake2b256Hash, TuplespaceEvent)] = consume match {
    case Consume(singleChannelHash :: Nil, _, _, _) =>
      Some(
        singleChannelHash -> TuplespaceEvent(
          consume,
          None
        )
      )
    case _ => None
  }

  def from(
      comm: COMM,
      produces: Set[Produce]
  ): Option[(Blake2b256Hash, TuplespaceEvent)] =
    comm match {
      case COMM(consume, produce :: Nil, _) => {
        val incoming: TuplespaceOperation =
          if (produces.contains(produce)) produce else consume
        val matched: Option[TuplespaceOperation] = Some(
          if (incoming == liftProduce(produce)) consume else produce
        )
        Some(
          produce.channelsHash -> TuplespaceEvent(
            incoming,
            matched
          )
        )
      }
      case _ => None
    }

  implicit class TuplespaceEventOps(val ev: TuplespaceEvent) extends AnyVal {

    private[casper] def conflicts(other: TuplespaceEvent): Boolean =
      if (ev.incoming.polarity == other.incoming.polarity)
        (for {
          leftMatched  <- ev.matched
          rightMatched <- other.matched
        } yield leftMatched == rightMatched && rightMatched.cardinality == Linear).getOrElse(false)
      else
        !(
          ev.incoming.cardinality == Linear && other.incoming.cardinality == Linear && (ev.matched != None || other.matched != None) ||

            ev.incoming.cardinality == NonLinear && other.matched != None ||
            other.incoming.cardinality == NonLinear && ev.matched != None
        )
  }
}
