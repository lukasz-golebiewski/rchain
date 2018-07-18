package coop.rchain.blockstorage

import cats.effect.Bracket
import cats._
import cats.mtl.MonadState
import coop.rchain.blockstorage.BlockStore.BlockHash
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.metrics.Metrics
import coop.rchain.shared.SyncVarOps

import scala.language.higherKinds

import scala.concurrent.SyncVar

class InMemBlockStore[F[_]] private ()(implicit
                                       bracketF: Bracket[F, Exception],
                                       metricsF: Metrics[F])
    extends BlockStore[F] {

  implicit val applicative: Applicative[F] = bracketF

  import cats.implicits._
  protected[this] val stateRef: SyncVar[Map[BlockHash, BlockMessage]] =
    SyncVarOps.create[Map[BlockHash, BlockMessage]](Map.empty)

  def put(blockHash: BlockHash, blockMessage: BlockMessage): F[Unit] =
    for {
      _ <- metricsF.incrementCounter("block-store-put")
      ret <- bracketF.bracket(applicative.pure(stateRef.take()))(state =>
              applicative.pure(stateRef.put(state.updated(blockHash, blockMessage))))(_ =>
              applicative.pure(()))
    } yield ret

  def get(blockHash: BlockHash): F[Option[BlockMessage]] =
    for {
      _ <- metricsF.incrementCounter("block-store-get")
      ret <- bracketF.bracket(applicative.pure(stateRef.take()))(state =>
              applicative.pure(state.get(blockHash)))(state =>
              applicative.pure(stateRef.put(state)))
    } yield ret

  def getAll(): F[Seq[(BlockHash, BlockMessage)]] =
    bracketF.bracket(applicative.pure(stateRef.take()))(state => applicative.pure(state.toSeq))(
      state => applicative.pure(stateRef.put(state)))
}

object InMemBlockStore {
  def create[F[_]](implicit
                   bracketF: Bracket[F, Exception],
                   metricsF: Metrics[F]): BlockStore[F] =
    new InMemBlockStore()(bracketF, metricsF)
}
