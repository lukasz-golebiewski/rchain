package coop.rchain.casper.helper
import cats.effect.Concurrent
import coop.rchain.models.ListParWithRandom
import coop.rchain.rholang.interpreter.Runtime.SystemProcess
import coop.rchain.rholang.interpreter.{ContractCall, PrettyPrinter, RhoType}
import coop.rchain.shared.{Log, LogSource}

object RhoLoggerContract {
  val prettyPrinter = PrettyPrinter()

  def handleMessage[F[_]: Log: Concurrent](
      ctx: SystemProcess.Context[F]
  )(message: (Seq[ListParWithRandom], Int)): F[Unit] = {
    val isContractCall = new ContractCall(ctx.space, ctx.dispatcher)

    message match {
      case isContractCall(_, Seq(RhoType.String(logLevel), par)) =>
        val msg         = prettyPrinter.buildString(par)
        implicit val ev = LogSource.matLogSource

        logLevel match {
          case "trace" => Log[F].trace(msg)
          case "debug" => Log[F].debug(msg)
          case "info"  => Log[F].info(msg)
          case "warn"  => Log[F].warn(msg)
          case "error" => Log[F].error(msg)
        }
    }
  }
}
