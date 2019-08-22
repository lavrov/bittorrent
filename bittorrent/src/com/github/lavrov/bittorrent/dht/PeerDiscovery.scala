package com.github.lavrov.bittorrent.dht

import cats.syntax.all._
import cats.instances.all._
import cats.effect.Sync
import fs2.Stream
import com.github.lavrov.bittorrent.PeerInfo
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.dht.message.Message
import scodec.bits.ByteVector
import com.github.lavrov.bittorrent.dht.message.Query
import cats.effect.concurrent.Ref
import cats.data.NonEmptyList
import com.github.lavrov.bittorrent.dht.message.Response
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import cats.effect.Timer

import scala.concurrent.duration._
import scala.collection.immutable.ListSet
import scala.util.Random
import cats.MonadError
import io.chrisdavenport.log4cats.Logger

object PeerDiscovery {

  def start[F[_]](infoHash: InfoHash, client: Client[F])(
      implicit F: Sync[F]
  ): F[Stream[F, PeerInfo]] = {
    for {
      logger <- Slf4jLogger.fromClass(getClass)
      seenPeers <- Ref.of(Set.empty[PeerInfo])
      nodesToTry <- client.getTable.flatMap { nodes =>
        Ref.of(ListSet(nodes: _*))
      }
    } yield {
      start(infoHash, nodesToTry, seenPeers, client.getPeers, logger)
    }
  }

  private def start[F[_]](
      infoHash: InfoHash,
      nodesToTry: Ref[F, ListSet[NodeInfo]],
      seenPeers: Ref[F, Set[PeerInfo]],
      getPeers: (NodeInfo, InfoHash) => F[Either[Response.Nodes, Response.Peers]],
      logger: Logger[F]
  )(
      implicit F: MonadError[F, Throwable]
  ): Stream[F, PeerInfo] = {
    Stream
      .repeatEval(
        nodesToTry
          .modify {
            case list if list.isEmpty => (list, none)
            case list => (list.tail, list.head.some)
          }
          .flatMap {
            case Some(nodeInfo) => F.pure(nodeInfo)
            case None => F.raiseError[NodeInfo](ExhaustedNodeList())
          }
      )
      .evalMap { nodeInfo =>
        getPeers(nodeInfo, infoHash).attempt
      }
      .flatMap {
        case Right(response) =>
          response match {
            case Left(Response.Nodes(_, nodes)) =>
              Stream
                .eval(updateNodeList(nodesToTry, nodes, infoHash)) >> Stream.empty
            case Right(Response.Peers(_, peers)) =>
              Stream
                .eval(filerNewPeers(seenPeers, peers))
                .flatMap(Stream.emits)
                .evalTap(peer => logger.debug(s"Discovered peer $peer"))
          }
        case Left(e) =>
          Stream.eval(logger.debug(e)("Failed query")) *> Stream.empty
      }
  }

  def filerNewPeers[F[_]](
      seenPeers: Ref[F, Set[PeerInfo]],
      peers: List[PeerInfo]
  ): F[List[PeerInfo]] = {
    seenPeers
      .modify { value =>
        val newPeers = peers.filterNot(value)
        (value ++ newPeers, newPeers)
      }
  }

  def updateNodeList[F[_]](
      nodesToTry: Ref[F, ListSet[NodeInfo]],
      nodes: List[NodeInfo],
      infoHash: InfoHash
  ): F[Unit] = {
    val nodesSorted = nodes.sortBy(n => NodeId.distance(n.id, infoHash))
    nodesToTry.update(value => ListSet(nodesSorted: _*) ++ value)
  }

  case class ExhaustedNodeList() extends Exception
}
