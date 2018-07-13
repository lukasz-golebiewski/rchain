package coop.rchain.blockstorage

import cats.MonadError
import cats.effect.{Bracket, Sync}
import cats.mtl.MonadState
import com.google.protobuf.ByteString
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.metrics.Metrics

import scala.language.higherKinds

trait BlockStore[F[_]] {
  import BlockStore.BlockHash

  def put(blockHash: BlockHash, blockMessage: BlockMessage): F[Unit]

  def get(blockHash: BlockHash): F[Option[BlockMessage]]
}

object BlockStore {
  type BlockHash = ByteString

  sealed trait BlockStoreError extends Throwable
  // some errors that extend BlockStoreError

  type BlockStoreMonadError[M[_]] = MonadError[M, BlockStoreError]

  type BlockStoreBracket[M[_]] = Bracket[M, BlockStoreError]

  def createMapBased[F[_]](implicit
                           bracketF: BlockStoreBracket[F],
                           stateF: MonadState[F, Map[BlockHash, BlockMessage]],
                           metricsF: Metrics[F]): BlockStore[F] =
    new BlockStore[F] {
      def put(blockHash: BlockHash, blockMessage: BlockMessage): F[Unit] =
        bracketF.flatMap(stateF.get) { kids =>
          stateF.set(kids.updated(blockHash, blockMessage))
        }

      def get(blockHash: BlockHash): F[Option[BlockMessage]] =
        bracketF.map(stateF.get) { kids =>
          kids.get(blockHash)
        }
    }

  /** LMDB backed implementation
    */
  def create[F[_]](implicit
                   monadErrorF: BlockStoreMonadError[F],
                   bracketF: BlockStoreBracket[F],
                   syncF: Sync[F]): BlockStore[F] = ???
}
