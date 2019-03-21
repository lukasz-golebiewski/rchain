package coop.rchain.rholang.interpreter.accounting

import cats._
import cats.data._
import cats.effect._
import cats.effect.concurrent.MVar
import cats.implicits._
import cats.mtl._
import cats.mtl.implicits._

package object utils {

  def costLog[M[_]: Concurrent](): M[FunctorListen[M, Chain[Cost]]] =
    for {
      ref <- MVar.of(Chain.empty[Cost])
    } yield
      (new DefaultFunctorListen[M, Chain[Cost]] {
        override val functor: Functor[M] = implicitly[Functor[M]]
        def tell(l: Chain[Cost]): M[Unit] =
          for {
            c <- ref.take
            _ <- ref.put(c.concat(l))
          } yield ()
        def listen[A](fa: M[A]): M[(A, Chain[Cost])] =
          for {
            a <- fa
            r <- ref.read
          } yield ((a, r))
      })
}
