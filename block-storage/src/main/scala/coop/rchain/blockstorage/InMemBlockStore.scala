package coop.rchain.blockstorage

import cats.effect.{Bracket, ExitCase}
import cats._
import cats.mtl.MonadState
import coop.rchain.blockstorage.BlockStore.BlockHash
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.shared.SyncVarOps

import scala.language.higherKinds
import scala.concurrent.SyncVar

class InMemBlockStore[F[_]] private ()(
    implicit
    bracketF: Bracket[F, Exception])
    extends BlockStore[F] {

  implicit val applicative: Applicative[F] = bracketF

  import cats.implicits._
  protected[this] val stateRef: SyncVar[Map[BlockHash, BlockMessage]] =
    SyncVarOps.create[Map[BlockHash, BlockMessage]](Map.empty)

  def put(blockHash: BlockHash, blockMessage: BlockMessage): F[Unit] =
    for {
//      _ <- metricsF.incrementCounter("block-store-put")
      ret <- bracketF.bracket(applicative.pure(stateRef.take()))(state =>
              applicative.pure(stateRef.put(state.updated(blockHash, blockMessage))))(_ =>
              applicative.pure(()))
    } yield ret

  def get(blockHash: BlockHash): F[Option[BlockMessage]] =
    for {
//      _ <- metricsF.incrementCounter("block-store-get")
      ret <- bracketF.bracket(applicative.pure(stateRef.take()))(state =>
              applicative.pure(state.get(blockHash)))(state =>
              applicative.pure(stateRef.put(state)))
    } yield ret

  def getAll(): F[Seq[(BlockHash, BlockMessage)]] =
    bracketF.bracket(applicative.pure(stateRef.take()))(state => applicative.pure(state.toSeq))(
      state => applicative.pure(stateRef.put(state)))
}

object InMemBlockStore {
  def create[F[_]](
      implicit
      bracketF: Bracket[F, Exception]): BlockStore[F] =
    new InMemBlockStore()(bracketF)

  def bracketId: Bracket[Id, Exception] =
    new Bracket[Id, Exception] {
      def pure[A](x: A): cats.Id[A] = implicitly[Applicative[Id]].pure(x)

      // Members declared in cats.ApplicativeError
      def handleErrorWith[A](fa: cats.Id[A])(f: Exception => cats.Id[A]): cats.Id[A] =
        ??? //implicitly[ApplicativeError[Id, Exception]].handleErrorWith(fa)(f)

      def raiseError[A](e: Exception): cats.Id[A] = ???
      //implicitly[ApplicativeError[Id, Exception]].raiseError(e)

      // Members declared in cats.FlatMap
      def flatMap[A, B](fa: cats.Id[A])(f: A => cats.Id[B]): cats.Id[B] =
        implicitly[FlatMap[Id]].flatMap(fa)(f)
      def tailRecM[A, B](a: A)(f: A => cats.Id[Either[A, B]]): cats.Id[B] =
        implicitly[FlatMap[Id]].tailRecM(a)(f)

      def bracketCase[A, B](acquire: A)(use: A => B)(
          release: (A, ExitCase[Exception]) => Unit): B = {
        val state = acquire
        try {
          use(state)
        } finally {
          release(acquire, ExitCase.Completed)
        }
      }

    }
}
