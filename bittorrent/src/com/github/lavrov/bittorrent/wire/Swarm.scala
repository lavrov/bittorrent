package com.github.lavrov.bittorrent.wire

import cats._
import cats.data.ContT
import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.effect.{Concurrent, Resource, Timer}
import cats.implicits._
import cats.tagless._
import cats.tagless.implicits._
import com.github.lavrov.bittorrent.PeerInfo
import com.github.lavrov.bittorrent.wire.Swarm.Connected
import fs2.Stream
import fs2.concurrent.{Queue, SignallingRef}
import logstage.LogIO

import scala.concurrent.duration._

trait Swarm[F[_]] {
  def connected: Connected[F]
}

object Swarm {

  def apply[F[_]](
    dhtPeers: Stream[F, PeerInfo],
    connect: PeerInfo => F[Connection[F]],
    maxConnections: Int = 10
  )(
    implicit F: Concurrent[F],
    timer: Timer[F],
    logger: LogIO[F]
  ): Resource[F, Swarm[F]] = Resource {
    for {
      stateRef <- Ref.of(Map.empty[PeerInfo, Connection[F]])
      lastConnected <- SignallingRef[F, Connection[F]](null)
      peerBuffer <- Queue.in[F].bounded[F, PeerInfo](10)
      reconnects <- Queue.in[F].unbounded[F, F[Unit]]
      fiber1 <- dhtPeers.through(peerBuffer.enqueue).compile.drain.start
      connectionFibers <- F
        .replicateA(
          maxConnections,
          openConnections[F](
            peerBuffer.dequeue1,
            reconnects,
            peerInfo =>
              for {
                _ <- logger.debug(s"Connecting to ${peerInfo.address}")
                connection <- connect(peerInfo)
                  .timeoutTo(1.second, F raiseError Error.ConnectTimeout(1.second))
                _ <- stateRef.update(_.updated(peerInfo, connection))
                _ <- lastConnected.set(connection)
                _ <- connection.disconnected
                _ <- stateRef.update(_ - peerInfo)
              } yield ()
          ).foreverM[Unit].start
        )
    } yield {
      val impl = new Impl(stateRef, lastConnected)
      val close = fiber1.cancel >> connectionFibers.traverse_(_.cancel) >> logger.info("Closed Swarm")
      (impl, close)
    }
  }

  private class Impl[F[_]](
    stateRef: Ref[F, Map[PeerInfo, Connection[F]]],
    lastConnected: SignallingRef[F, Connection[F]]
  )(implicit F: Monad[F])
      extends Swarm[F] {
    val connected: Connected[F] = new Connected[F] {
      def count: F[Int] = stateRef.get.map(_.size)
      def list: F[List[Connection[F]]] = stateRef.get.map(_.values.toList)
      def stream: Stream[F, Connection[F]] =
        Stream.evalSeq(stateRef.get.map(_.values.toList)) ++ lastConnected.discrete.tail
    }
  }

  private def openConnections[F[_]](discover: F[PeerInfo], reconnects: Queue[F, F[Unit]], connect: PeerInfo => F[Unit])(
    implicit F: Concurrent[F],
    timer: Timer[F],
    logger: LogIO[F]
  ): F[Unit] =
    for {
      continuation <- reconnects.tryDequeue1.flatMap {
        case Some(c) => c.pure[F]
        case None =>
          type Cont[A] = ContT[F, Unit, A]
          implicit val logger1: RoutineLogger[Cont] = RoutineLogger(logger).mapK(ContT.liftK)
          F.race(discover, reconnects.dequeue1).map {
            _.leftMap { discovered =>
              connectRoutine[Cont](discovered)(
                connect = ContT.liftF {
                  connect(discovered).attempt
                },
                coolDown = duration =>
                  ContT { k0 =>
                    val k = k0(())
                    (timer.sleep(duration) >> reconnects.enqueue1(k)).start.void
                  }
              ).run(_ => F.unit)
            }.merge
          }
      }
      _ <- continuation
    } yield ()

  private def connectRoutine[F[_]](
    peerInfo: PeerInfo
  )(connect: F[Either[Throwable, Unit]], coolDown: FiniteDuration => F[Unit])(
    implicit F: Monad[F],
    logger: RoutineLogger[F]
  ): F[Unit] = {
    val maxAttempts = 5
    def connectWithRetry(attempt: Int): F[Unit] = {
      connect
        .flatMap {
          case Right(_) => F.unit
          case Left(e) =>
            val cause = e.getMessage
            if (attempt == maxAttempts) logger.gaveUp
            else {
              val duration = (10 * attempt).seconds
              logger.connectionFailed(attempt, cause, duration) >> coolDown(duration) >> connectWithRetry(attempt + 1)
            }
        }
    }
    for {
      _ <- connectWithRetry(1)
      _ <- logger.disconnected
      _ <- connectRoutine(peerInfo)(connect, coolDown)
    } yield ()
  }

  @autoFunctorK
  trait RoutineLogger[F[_]] {

    def gaveUp: F[Unit]

    def connectionFailed(attempt: Int, cause: String, waitDuration: FiniteDuration): F[Unit]

    def disconnected: F[Unit]
  }

  object RoutineLogger {

    def apply[F[_]](logger: LogIO[F]): RoutineLogger[F] = new RoutineLogger[F] {

      def gaveUp: F[Unit] = logger.debug(s"Gave up")

      def connectionFailed(attempt: Int, cause: String, waitDuration: FiniteDuration): F[Unit] =
        logger.debug(s"Connection failed $attempt $cause. Retry connecting in at least $waitDuration")

      def disconnected: F[Unit] =
        logger.debug(s"Disconnected. Trying to reconnect.")
    }
  }

  trait Connected[F[_]] {
    def count: F[Int]
    def list: F[List[Connection[F]]]
    def stream: Stream[F, Connection[F]]
  }

  sealed class Error(message: String) extends Throwable(message)

  object Error {
    case class ConnectTimeout(duration: FiniteDuration) extends Error(s"Connect timeout after $duration")
  }
}
