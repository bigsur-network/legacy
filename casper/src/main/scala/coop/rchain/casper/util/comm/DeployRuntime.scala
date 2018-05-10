package coop.rchain.casper.util.comm

import cats.Monad
import cats.implicits._
import coop.rchain.casper.protocol.DeployString
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.catscontrib.{Capture, IOUtil, MonadOps}

import scala.io.Source
import scala.util.{Failure, Success, Try}

object DeployRuntime {
  //force Casper to propose a block
  def forcePropose[F[_]: DeployService]: F[Unit] = DeployService[F].propose()

  //Accepts a Rholang source file and deploys it to Casper
  def deployFileProgram[F[_]: Monad: Capture: DeployService](file: String): F[Unit] =
    Try(Source.fromFile(file).mkString) match {
      case Success(code) =>
        for {
          //TODO: have the client track the nonce
          nonce <- Capture[F].capture { scala.util.Random.nextInt(10000) }
          //TODO: allow user to specify their public key
          d        = DeployString().withNonce(nonce).withTerm(code)
          response <- DeployService[F].deploy(d)
          _ <- Capture[F].capture {
                println(s"Response: ${response._2}")
              }
        } yield ()

      case Failure(ex) =>
        Capture[F].capture { println(s"Error with given file: \n${ex.getMessage()}") }
    }

  //Simulates user requests by randomly deploying things to Casper.
  def deployDemoProgram[F[_]: Monad: Capture: DeployService]: F[Unit] =
    MonadOps.forever(singleDeploy[F])

  private def singleDeploy[F[_]: Monad: Capture: DeployService]: F[Unit] =
    for {
      id <- Capture[F].capture { scala.util.Random.nextInt(100) }
      d  = ProtoUtil.basicDeployString(id)
      _ <- Capture[F].capture {
            println(s"Sending the following to Casper: ${d.term}")
          }
      response <- DeployService[F].deploy(d)
      _ <- Capture[F].capture {
            println(s"Response: ${response._2}")
          }
      _ <- IOUtil.sleep[F](4000L)
    } yield ()
}
