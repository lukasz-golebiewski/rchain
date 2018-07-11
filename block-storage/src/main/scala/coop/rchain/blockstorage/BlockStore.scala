package coop.rchain.blockstorage

import cats.MonadError
import cats.effect.Sync

import scala.language.higherKinds


trait BlockStore[F[_]] {
  def put(blockHash: BlockHash, blockMessage: BlockMessage): F[Unit]

  def get(blockHash: BlockHash): F[BlockMessage]

  def getChildren(blockHash: BlockHash): F[Set[BlockHash]]
}

object BlockStore {

  sealed trait BlockStoreError extends Throwable
  // some errors that extend BlockStoreError

  type BlockStoreMonadError[M[_]] = MonadError[M, BlockStoreError]

  type BlockStoreBracket[M[_]] = Bracket[M, BlockStoreError]

  def create[F[_]](implicit
                   monadErrorF: BlockStoreMonadError[F],
                   bracketF: BlockStoreBracket[F],
                   syncF: Sync[F]): BlockStore[F] =
    new BlockStore[F] {

      def put(blockHash: BlockHash, blockMessage: BlockMessage): F[Unit] = {
        // use methods from Bracket, Sync, etc. here
      }

      def get(blockHash: BlockHash): F[BlockMessage] = {
        // use methods from Bracket, Sync, etc. here
      }
    }
}