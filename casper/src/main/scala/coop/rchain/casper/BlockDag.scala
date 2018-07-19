package coop.rchain.casper

import cats.mtl.MonadState
import cats.{FlatMap, Functor}
import cats.effect.Bracket
import coop.rchain.blockstorage.BlockStore
import coop.rchain.casper.BlockDag.LatestMessages
import coop.rchain.casper.Estimator.{BlockHash, Validator}
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.metrics.Metrics

import scala.collection.immutable.{HashMap, HashSet}
import scala.language.higherKinds

final case class BlockDag[F[_]](idToBlocks: Map[Int, BlockMessage],
                                blockLookup: BlockStore[F],
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

  def apply[F[_]]()(
      implicit
      bracketF: Bracket[F, Exception]): BlockDag[F] =
    new BlockDag[F](
      HashMap.empty[Int, BlockMessage],
      BlockStore.createMapBased[F],
      HashMap.empty[BlockHash, HashSet[BlockHash]],
      LatestMessages.empty,
      HashMap.empty[Validator, LatestMessages],
      0,
      HashMap.empty[Validator, Int]
    )
}
