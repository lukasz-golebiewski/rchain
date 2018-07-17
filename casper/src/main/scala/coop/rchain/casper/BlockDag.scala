package coop.rchain.casper

import cats.mtl.MonadState
import cats.{Applicative, Id, Monad}
import coop.rchain.blockstorage.BlockStore
import coop.rchain.casper.BlockDag.LatestMessages
import coop.rchain.casper.Estimator.{BlockHash, Validator}
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.metrics.Metrics
import coop.rchain.metrics.Metrics.MetricsNOP

import scala.collection.immutable.{HashMap, HashSet}

final case class BlockDag(idToBlocks: Map[Int, BlockMessage],
                          blockLookup: BlockStore[Id],
                          childMap: Map[BlockHash, Set[BlockHash]],
                          latestMessages: LatestMessages,
                          latestMessagesOfLatestMessages: Map[Validator, LatestMessages],
                          currentId: Int,
                          currentSeqNum: Map[Validator, Int])

object BlockDag {
  type LatestMessages = Map[Validator, BlockHash]
  object LatestMessages {
    def empty: LatestMessages = HashMap.empty[Validator, BlockHash]
  }

  def inMemStore(data: Map[BlockHash, BlockMessage] = Map.empty): BlockStore[Id] = {
    implicit val applicativeId: Applicative[Id] = new Applicative[Id] {
      override def pure[A](x: A): Id[A] = x

      override def ap[A, B](ff: Id[A => B])(fa: Id[A]): Id[B] = ff.apply(fa)
    }
    implicit val metrics: Metrics[Id] = new MetricsNOP[Id]()
    implicit val state: MonadState[Id, Map[BlockHash, BlockMessage]] =
      new MonadState[Id, Map[BlockHash, BlockMessage]] {

        val monad: Monad[Id] = implicitly[Monad[Id]]

        var map: Map[BlockHash, BlockMessage] = data

        def get: Id[Map[BlockHash, BlockMessage]] = monad.pure(map)

        def set(s: Map[BlockHash, BlockMessage]): Id[Unit] = ???

        def inspect[A](f: Map[BlockHash, BlockMessage] => A): Id[A] = ???

        def modify(f: Map[BlockHash, BlockMessage] => Map[BlockHash, BlockMessage]): Id[Unit] = {
          map = f(map)
          monad.pure(())
        }
      }
    BlockStore.createMapBased[Id]
  }

  def apply(store: BlockStore[Id] = inMemStore()): BlockDag =
    new BlockDag(
      HashMap.empty[Int, BlockMessage],
      store,
      HashMap.empty[BlockHash, HashSet[BlockHash]],
      LatestMessages.empty,
      HashMap.empty[Validator, LatestMessages],
      0,
      HashMap.empty[Validator, Int]
    )
}
