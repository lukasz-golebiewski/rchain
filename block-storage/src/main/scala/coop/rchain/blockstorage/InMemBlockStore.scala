package coop.rchain.blockstorage

import cats.FlatMap
import cats.mtl.MonadState
import coop.rchain.blockstorage.BlockStore.{BlockHash, BlockStoreBracket}
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.metrics.Metrics
import cats.implicits._
import cats.syntax._

import scala.language.higherKinds

class InMemBlockStore[F[_]] private ()(implicit
                                       flatMapF: FlatMap[F],
                                       stateF: MonadState[F, Map[BlockHash, BlockMessage]],
                                       metricsF: Metrics[F])
    extends BlockStore[F] {
  def put(blockHash: BlockHash, blockMessage: BlockMessage): F[Unit] =
    for {
      _ <- metricsF.incrementCounter("block-store-put")
      _ <- stateF.modify(state => state.updated(blockHash, blockMessage))
    } yield ()

  def get(blockHash: BlockHash): F[Option[BlockMessage]] =
    for {
      kids    <- stateF.get
      _       <- metricsF.incrementCounter("block-store-get")
      message = kids.get(blockHash)
    } yield message
}

object InMemBlockStore {
  def create[F[_]]()(implicit
                     flatMapF: FlatMap[F],
                     stateF: MonadState[F, Map[BlockHash, BlockMessage]],
                     metricsF: Metrics[F]): BlockStore[F] =
    new InMemBlockStore()
}
