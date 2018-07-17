package coop.rchain.blockstorage

import cats.{Applicative, Id, Monad}
import cats.mtl.MonadState
import coop.rchain.blockstorage.BlockStore.{BlockHash, BlockStoreBracket}
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.metrics.Metrics
import coop.rchain.metrics.Metrics.MetricsNOP
import org.scalactic.anyvals.PosInt
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scala.language.higherKinds

class BlockStoreTest
    extends FlatSpec
    with Matchers
    with OptionValues
    with GeneratorDrivenPropertyChecks
    with BeforeAndAfterEach {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = PosInt(100))

  override def beforeEach() {}

  override def afterEach() {}

  def withStore[R](f: BlockStore[Id] => R): R = {
    implicit val applicativeId: Applicative[Id] = new Applicative[Id] {
      override def pure[A](x: A): Id[A] = x

      override def ap[A, B](ff: Id[A => B])(fa: Id[A]): Id[B] = ff.apply(fa)
    }
    implicit val metrics: Metrics[Id] = new MetricsNOP[Id]()
    implicit val state: MonadState[Id, Map[BlockHash, BlockMessage]] =
      new MonadState[Id, Map[BlockHash, BlockMessage]] {

        val monad: Monad[Id] = implicitly[Monad[Id]]

        var map: Map[BlockHash, BlockMessage] = Map.empty

        def get: Id[Map[BlockHash, BlockMessage]] = monad.pure(map)

        def set(s: Map[BlockHash, BlockMessage]): Id[Unit] = ???

        def inspect[A](f: Map[BlockHash, BlockMessage] => A): Id[A] = ???

        def modify(f: Map[BlockHash, BlockMessage] => Map[BlockHash, BlockMessage]): Id[Unit] = ???
      }
    val store = BlockStore.createMapBased[Id]
    f(store)
  }

}
