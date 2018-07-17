package coop.rchain.blockstorage

import cats.{FlatMap, Functor, MonadError}
import cats.effect.{Bracket, Sync}
import cats.mtl.MonadState
import com.google.protobuf.ByteString
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.metrics.Metrics

import scala.language.higherKinds

import cats.implicits._

trait BlockStore[F[_]] {
  import BlockStore.BlockHash

  implicit def functor: Functor[F]

  def put(blockHash: BlockHash, blockMessage: BlockMessage): F[Unit]

  def get(blockHash: BlockHash): F[Option[BlockMessage]]

  //FIXME carbon copy of map behavior
  def apply(blockHash: BlockHash): F[BlockMessage] = get(blockHash).map(_.get)

  def contains(blockHash: BlockHash): F[Boolean] = get(blockHash).map(_.isDefined)

  def getAll(): F[Seq[(BlockHash, BlockMessage)]]
}

object BlockStore {
  type BlockHash = ByteString
  sealed trait BlockStoreError extends Throwable
  // some errors that extend BlockStoreError

  type BlockStoreMonadError[M[_]] = MonadError[M, BlockStoreError]

  type BlockStoreBracket[M[_]] = Bracket[M, BlockStoreError]

  def createMapBased[F[_]](implicit
                           functorF: Functor[F],
                           flatMapF: FlatMap[F],
                           stateF: MonadState[F, Map[BlockHash, BlockMessage]],
                           metricsF: Metrics[F]): BlockStore[F] = InMemBlockStore.create()

  /** LMDB backed implementation
    */
  def create[F[_]](implicit
                   functorF: Functor[F],
                   monadErrorF: BlockStoreMonadError[F],
                   bracketF: BlockStoreBracket[F],
                   syncF: Sync[F]): BlockStore[F] = ???
}
