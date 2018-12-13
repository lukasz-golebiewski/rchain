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
      r1 <- space.consume(List(c1), List(p1), Map(c1 -> true))
      _  = r1 shouldBe Right(None)
    } yield ()
  }

  behavior of "RSpace produce"

  it should "find a peek match for an existing non-linear consume" ignore withTestSpace { space =>
    for {
      r1 <- space.consume(List(c1), List(p1), Map(c1 -> true))
      _  = r1 shouldBe Right(None)
      r3 <- space.produce(c1, bob1, persist = false)
      _  = matchContResult(r3, List(c1), List(p1))
      _  = space.dataShouldNotExist(c1)
      r4 <- space.produce(c1, bob1, persist = false)
      _  = r4 shouldBe Right(None)
      _  = space.dataShouldExist(c1)
    } yield ()
  }

  it should "find a peek match for all existing non-linear consumes" ignore withTestSpace { space =>
    for {
      r1 <- space.consume(List(c1), List(p1), Map(c1 -> true))
      _  = r1 shouldBe Right(None)
      r2 <- space.consume(List(c1), List(p1), Map(c1 -> true))
      _  = r2 shouldBe Right(None)
      r3 <- space.produce(c1, bob1, persist = false)
      _  = matchContResult(r3, List(c1), List(p1))
      _  = space.dataShouldExist(c1)
      r4 <- space.produce(c1, bob1, persist = false)
      _  = r4 shouldBe matchContResult(r3, List(c1), List(p1))
      _  = space.dataShouldNotExist(c1)
      r5 <- space.produce(c1, bob1, persist = false)
      _  = r5 shouldBe Right(None)
      _  = space.dataShouldExist(c1)
    } yield ()
  }

  it should "find a peek match for existing non-linear consume or a regular match for existing non-linear consume" ignore withTestSpace {
    space =>
      def chooseMatch(result: Either[Nothing,
                                Option[(ContResult[Channel, Pattern, EntriesCaptor],
                                        Seq[Result[AddressEntry]])]]) = {
        if (result.peek) {
          matchContResult(result, List(c1), List(p1))
          space.produce(c1, bob1, persist = false)
        } else {
          Task { result }
        }
      }
      for {
        r1 <- space.consume(List(c1), List(p1), Map(c1 -> true))
        _  = r1 shouldBe Right(None)
        r2 <- space.consume(List(c1), List(p1), Map(c1 -> false))
        _  = r2 shouldBe Right(None)
        r3 <- space.produce(c1, bob1, persist = false)
        v  <- chooseMatch(r3)
        _  = matchContResult(v, List(c1), List(p1), isPeek = false)
        _  = space.dataShouldNotExist(c1)
      } yield ()
  }

  it should "find a peek match for an existing partially matched (not peek match) non-linear consume" ignore withTestSpace {
    space =>
      for {
        r1 <- space.consume(List(c1, c2), List(p1, p2), Map(c1 -> true, c2 -> false))
        _  = r1 shouldBe Right(None)
        r2 <- space.produce(c2, bob2, persist = false)
        _  = r2 shouldBe Right(None)
        r3 <- space.produce(c1, bob1, persist = false)
        _  = matchContResult(r3, List(c1, c2), List(p1, p2))
        r4 <- space.produce(c1, bob1, persist = false)
        _  = r4 shouldBe Right(None)
        _  = space.dataShouldExist(c1)
        _  = space.dataShouldNotExist(c2)
      } yield ()
  }

  it should "find a peek match for an existing partially matched (peek match) non-linear consume" ignore withTestSpace {
    space =>
      for {
        r1 <- space.consume(List(c1, c2), List(p1, p2), Map(c1 -> true, c2 -> true))
        _  = r1 shouldBe Right(None)
        r2 <- space.produce(c2, bob2, persist = false)
        _  = r2 shouldBe Right(None)
        r3 <- space.produce(c1, bob1, persist = false)
        _  = matchContResult(r3, List(c1, c2), List(p1, p2))
        r4 <- space.produce(c1, bob1, persist = false)
        _  = r4 shouldBe Right(None)
        _  = space.dataShouldExist(c1)
        _  = space.dataShouldExist(c2)
      } yield ()
  }

  val c1 = Channel("C1")
  val c2 = Channel("C2")
  val p1 = NameMatch(last = "V1")
  val p2 = NameMatch(last = "V2")
  val bob1 = bob.copy(name = Name("", "V1"))
  val bob2 = bob.copy(name = Name("", "V2"))

  implicit def eitherContResult2ContResult(v: Either[Nothing,
    Option[(ContResult[Channel, Pattern, EntriesCaptor], Seq[Result[AddressEntry]])]])
    : ContResult[Channel, Pattern, EntriesCaptor] = v.right.get.get._1

  implicit class RichSpace(space: T) {
    def consume(channels: Seq[Channel], patterns: Seq[NameMatch], peeks: Map[Channel, Boolean]) =
      space.consume(channels,
                    patterns,
                    new EntriesCaptor,
                    persist = false,
                    sequenceNumber = 0,
                    peeks)

    def dataShouldExist(c: Channel) =
      space.store.withTxn(space.store.createTxnRead()) { txn =>
        space.store.getData(txn, Seq(c)) should not be empty
      }

    def dataShouldNotExist(c: Channel) =
      space.store.withTxn(space.store.createTxnRead()) { txn =>
        space.store.getData(txn, Seq(c)) shouldBe empty
      }
  }

  def matchContResult(result: ContResult[Channel, Pattern, EntriesCaptor],
                         channels: Seq[Channel],
                         patterns: Seq[Pattern],
                         isPeek: Boolean = true) = {
    result.channels shouldBe channels
    result.patterns shouldBe patterns
    result.peek shouldBe isPeek
  }
}
