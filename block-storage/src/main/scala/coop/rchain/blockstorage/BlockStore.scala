package coop.rchain.blockstorage

import cats.MonadError
import cats.effect.Sync
import cats.effect.Bracket
import com.google.protobuf.ByteString
import coop.rchain.casper.protocol.BlockMessage

import scala.language.higherKinds

trait BlockStore[F[_]] {
  type BlockHash = ByteString

  def put(blockHash: BlockHash, blockMessage: BlockMessage): F[Unit]

  def get(blockHash: BlockHash): F[BlockMessage]

  def getChildren(blockHash: BlockHash): F[Set[BlockHash]]
}

object BlockStore {

  sealed trait BlockStoreError extends Throwable
  // some errors that extend BlockStoreError

  type BlockStoreMonadError[M[_]] = MonadError[M, BlockStoreError]

  type BlockStoreBracket[M[_]] = Bracket[M, BlockStoreError]

  def createMapBased[F[_]](implicit
                           monadErrorF: BlockStoreMonadError[F],
                           bracketF: BlockStoreBracket[F],
                           syncF: Sync[F]): BlockStore[F] =
    new BlockStore[F] {
      val blockLookup: Map[BlockHash, BlockMessage] = Map.empty
      val childMap: Map[BlockHash, Set[BlockHash]]  = Map.empty

      def put(blockHash: BlockHash, blockMessage: BlockMessage): F[Unit] = ???

      def get(blockHash: BlockHash): F[BlockMessage] = ???

      def getChildren(blockHash: BlockHash): F[Set[BlockHash]] = ???
    }
}
