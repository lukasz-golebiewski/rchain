package coop.rchain.rspace

import scala.collection.immutable.Seq

import coop.rchain.rspace.examples.AddressBookExample.{Entry => AddressEntry, _}
import coop.rchain.rspace.examples.AddressBookExample.implicits._

import monix.eval.Task
import org.scalatest._

class PeekSpec
    extends InMemoryStoreStorageExamplesTestsBase[Task]
    with TaskTests[Channel, Pattern, Nothing, AddressEntry, EntriesCaptor] {

  behavior of "an empty RSpace"

  it should "accept a peek consume" in withTestSpace { space =>
    for {
      r1 <- space.consumeOneChannelWithPeek
      _  = r1 shouldBe Right(None)
    } yield ()
  }

  it should "not accept mismatched peek designations" ignore withTestSpace { space => ??? }

  behavior of "RSpace produce"

  it should "find a peek match for an existing non-linear consume" ignore withTestSpace { space =>
    for {
      r1 <- space.consumeOneChannelWithPeek
      _  = r1 shouldBe Right(None)
      r3 <- space.produceOne
      _ = matchOneContResult(r3.right.get.get._1)
      r4 <- space.produceOne
      _ = r4 shouldBe Right(None)
    } yield ()
  }

  it should "find a peek match for all existing non-linear consumes" ignore withTestSpace { space =>
    for {
      r1 <- space.consumeOneChannelWithPeek
      _  = r1 shouldBe Right(None)
      r2 <- space.consumeOneChannelWithPeek
      _  = r2 shouldBe Right(None)
      r3 <- space.produceOne
      _ = matchOneContResult(r3.right.get.get._1)
      r4 <- space.produceOne
      _ = r4 shouldBe matchOneContResult(r3.right.get.get._1)
      r5 <- space.produceOne
      _ = r5 shouldBe Right(None)
    } yield ()
  }

  it should "find a peek match for existing non-linear consume or a regular match for existing non-linear consume" ignore withTestSpace { space =>
    val r = for {
      r1 <- space.consumeOneChannelWithPeek
      _  = r1 shouldBe Right(None)
      r2 <- space.consume(
        List(c1),
        List(p1),
        new EntriesCaptor,
        persist = false,
        sequenceNumber = 0,
        None)
      _  = r2 shouldBe Right(None)
      r3 <- space.produceOne
    } yield r3
    val nt = r.flatMap { v =>
      val x = v.right.get.get._1
      if(x.peek) {
        matchOneContResult(x)
        for {
          r3 <- space.produceOne
        } yield r3
      } else {
        Task {v}
      }
    }
    nt.map { v =>
      val result = v.right.get.get._1
      result.channels shouldBe Seq(c1)
      result.patterns shouldBe Seq(p1)
      result.peek shouldBe false
    }
  }

  it should "find a peek match for an exiting partially matched non-linear consume" ignore withTestSpace { space =>
    for {
      r1 <-       space
        .consume(
          List(c1, c2),
          List(p1, p2),
          new EntriesCaptor,
          persist = false,
          sequenceNumber = 0,
          Some(Seq(true, false))
        )
      _  = r1 shouldBe Right(None)
      r2 <- space.produce(c2, bob2, persist = false)
      _  = r2 shouldBe Right(None)
      r3 <- space.produceOne
      _ = matchTwoContResult(r3.right.get.get._1)
      r4 <- space.produceOne
      _ = r4 shouldBe Right(None)
      _ = space.store.withTxn(space.store.createTxnRead()) { txn =>
        space.store.getData(txn, Seq(c1)) should not be empty
        space.store.getData(txn, Seq(c2)) shouldBe empty
      }
    } yield ()
  }

  it should "find a peek match for an exiting partially matched non-linear consume" ignore withTestSpace { space =>
    for {
      r1 <- space.consumeTwoChannelsWithPeek
      _  = r1 shouldBe Right(None)
      r2 <- space.produce(c2, bob2, persist = false)
      _  = r2 shouldBe Right(None)
      r3 <- space.produceOne
      _ = matchTwoContResult(r3.right.get.get._1)
      r4 <- space.produceOne
      _ = r4 shouldBe Right(None)
      _ = space.store.withTxn(space.store.createTxnRead()) { txn =>
        space.store.getData(txn, Seq(c1)) should not be empty
        space.store.getData(txn, Seq(c2)) should not be empty
      }
    } yield ()
  }

  val c1   = Channel("C1")
  val c2   = Channel("C2")
  val p1   = NameMatch(last = "V1")
  val p2   = NameMatch(last = "V2")
  val bob1 = bob.copy(name = Name("", "V1"))
  val bob2 = bob.copy(name = Name("", "V2"))

  implicit class RichSpace(space: T) {
    def consumeOneChannelWithPeek = {
      space.consume(
        List(c1),
        List(p1),
        new EntriesCaptor,
        persist = false,
        sequenceNumber = 0,
        Some(Seq(true)))
    }
    def produceOne = {
      space.produce(c1, bob1, persist = false)
    }
    def consumeTwoChannelsWithPeek = {
      space
        .consume(
          List(c1, c2),
          List(p1, p2),
          new EntriesCaptor,
          persist = false,
          sequenceNumber = 0,
          Some(Seq(true, true))
        )
    }
  }

  def matchOneContResult(result: ContResult[Channel, Pattern, EntriesCaptor]) = {
    result.channels shouldBe Seq(c1)
    result.patterns shouldBe Seq(p1)
    result.peek shouldBe true
  }

  def matchTwoContResult(result: ContResult[Channel, Pattern, EntriesCaptor]) = {
    result.channels shouldBe Seq(c1, c2)
    result.patterns shouldBe Seq(p1, p2)
    result.peek shouldBe true
  }
}
