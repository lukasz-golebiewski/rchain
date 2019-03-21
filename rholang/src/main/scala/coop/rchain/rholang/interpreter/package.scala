package coop.rchain.rholang

import cats.effect.Sync

package object interpreter {

  type _error[F[_]] = Sync[F]

  def _error[F[_]](implicit ev: _error[F]): _error[F] = ev

}
