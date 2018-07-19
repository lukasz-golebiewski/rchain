package coop.rchain.blockstorage

import cats._
import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.BlockStore.BlockHash
import coop.rchain.casper.protocol.{BlockMessage, Header}
import coop.rchain.metrics.Metrics
import coop.rchain.metrics.Metrics.MetricsNOP
import org.scalactic.anyvals.PosInt
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scala.language.higherKinds

trait BlockStoreTest
    extends FlatSpecLike
    with Matchers
    with OptionValues
    with GeneratorDrivenPropertyChecks
    with BeforeAndAfterEach
    with BeforeAndAfterAll {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = PosInt(100))

  override def beforeEach(): Unit = {}

  override def afterEach(): Unit = {}

  def bm(v: Long, ts: Long): BlockMessage =
    BlockMessage().withHeader(Header().withVersion(v).withTimestamp(ts))

  def withStore[R](f: BlockStore[Id] => R): R

  //TODO make generative
  "Block Store" should "return None on get while it's empty" in withStore { store =>
    val key: BlockHash = ByteString.copyFrom("testkey", "utf-8")
    store.get(key) shouldBe None
  }

  //TODO make generative
  "Block Store" should "return Some(message) on get for a published key" in withStore { store =>
    val items = 0 to 100 map { i =>
      val key: BlockHash    = ByteString.copyFrom("testkey" + i, "utf-8")
      val msg: BlockMessage = bm(100L + i, 10000L + i)
      (key, msg)
    }
    items.foreach { case (k, v) => store.put(k, v) }
    items.foreach {
      case (k, v) =>
        store.get(k) shouldBe Some(v)
    }
    store.asMap() should have size items.size
  }

  "Block Store" should "overwrite existing value" in withStore { store =>
    val items = 0 to 100 map { i =>
      val key: BlockHash     = ByteString.copyFrom("testkey" + i, "utf-8")
      val msg1: BlockMessage = bm(100L + i, 10000L + i)
      val msg2: BlockMessage = bm(200L + i, 20000L + i)
      (key, msg1, msg2)
    }
    items.foreach { case (k, v1, _) => store.put(k, v1) }
    items.foreach { case (k, v1, _) => store.get(k) shouldBe Some(v1) }
    items.foreach { case (k, _, v2) => store.put(k, v2) }
    items.foreach { case (k, _, v2) => store.get(k) shouldBe Some(v2) }

    store.asMap() should have size items.size
  }
}

class InMemBlockStoreTest extends BlockStoreTest {
  override def withStore[R](f: BlockStore[Id] => R): R = {

    implicit val bracket: Bracket[Id, Exception] =
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
    implicit val metrics: Metrics[Id] = new MetricsNOP[Id]()(bracket)

    val store = BlockStore.createMapBased[Id]
    f(store)
  }
}

import java.nio.file.{Files, Path}
import org.lmdbjava._

class LMDBBlockStoreTest extends BlockStoreTest {

  val dbDir: Path = Files.createTempDirectory("block-store-test-")

  override def withStore[R](f: BlockStore[Id] => R): R = {

    implicit val bracket: Bracket[Id, Exception] =
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

    implicit val metrics: Metrics[Id] = new MetricsNOP[Id]()(bracket)

    val mapSize: Long = 1024L * 1024L * 4096L
    val env = Env
      .create()
      .setMapSize(mapSize)
      .setMaxDbs(1)
      .setMaxReaders(8)
      .open(dbDir.toFile, List(EnvFlags.MDB_NOTLS): _*)

    try {
      val store = LMDBBlockStore.create[Id](env, dbDir)
      f(store)
    } finally {
      env.close()
    }
  }

  override def afterAll(): Unit = {
    recursivelyDeletePath(dbDir)
    super.afterAll()
  }

  import java.nio.ByteBuffer
  import java.nio.file.attribute.BasicFileAttributes
  import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}

  private def makeDeleteFileVisitor: SimpleFileVisitor[Path] =
    new SimpleFileVisitor[Path] {
      override def visitFile(p: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.delete(p)
        FileVisitResult.CONTINUE
      }
      override def postVisitDirectory(p: Path, e: java.io.IOException): FileVisitResult = {
        Files.delete(p)
        FileVisitResult.CONTINUE
      }
    }

  def recursivelyDeletePath(p: Path): Path =
    Files.walkFileTree(p, makeDeleteFileVisitor)

}
