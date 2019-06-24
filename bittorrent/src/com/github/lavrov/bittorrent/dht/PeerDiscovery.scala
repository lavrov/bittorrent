package com.github.lavrov.bittorrent.dht

import cats.syntax.all._
import cats.instances.all._
import cats.effect.Sync
import fs2.Stream
import com.github.lavrov.bittorrent.PeerInfo
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.dht.message.Message
import scodec.bits.ByteVector
import java.net.InetSocketAddress
import com.github.lavrov.bittorrent.dht.message.Query
import cats.effect.concurrent.Ref
import cats.data.NonEmptyList
import com.github.lavrov.bittorrent.dht.message.Response
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import cats.effect.Timer

import scala.concurrent.duration._

object PeerDiscovery {

  val BootstrapNode = new InetSocketAddress("router.bittorrent.com", 6881)

  val transactionId = ByteVector.encodeAscii("aa").right.get

  def start[F[_]](infoHash: InfoHash, client: Client[F])(
      implicit F: Sync[F], timer: Timer[F]
  ): F[Stream[F, PeerInfo]] = {
    import client.selfId
    for {
      logger <- Slf4jLogger.fromClass(getClass)
      _ <- client.sendMessage(
        BootstrapNode,
        Message.QueryMessage(transactionId, Query.Ping(selfId))
      )
      m <- client.readMessage
      r <- F.fromEither(
        m match {
          case Message.ResponseMessage(transactionId, response) =>
            Message.PingResponseFormat.read(response).leftMap(e => new Exception(e))
          case other =>
            Left(new Exception(s"Got wrong message $other"))
        }
      )
      seenPeers <- Ref.of(Set.empty[PeerInfo])
    } yield {
      val bootstrapNodeList = NonEmptyList.one(NodeInfo(r.id, BootstrapNode))
      def iteration(nodesToTry: NonEmptyList[NodeInfo]): Stream[F, PeerInfo] =
        Stream[F, NodeInfo](nodesToTry.toList: _*)
          .evalMap { nodeInfo =>
            for {
              _ <- client.sendMessage(
                nodeInfo.address,
                Message.QueryMessage(transactionId, Query.GetPeers(selfId, infoHash))
              )
              m <- client.readMessage
              response <- F.fromEither(
                m match {
                  case Message.ResponseMessage(transactionId, bc) =>
                    val reader =
                      Message.PeersResponseFormat.read
                        .widen[Response]
                        .orElse(Message.NodesResponseFormat.read.widen[Response])
                    reader(bc).leftMap(new Exception(_))
                  case other =>
                    Left(new Exception(s"Expected response but got $other"))
                }
              )
            } yield response
          }
          .flatMap { response =>
            response match {
              case Response.Nodes(_, nodes) =>
                nodes.sortBy(n => NodeId.distance(n.id, infoHash)).toNel match {
                  case Some(ns) => iteration(ns)
                  case _ => Stream.fixedDelay(10.seconds) >> iteration(bootstrapNodeList)
                }
              case Response.Peers(_, peers) =>
                Stream.eval(
                  seenPeers
                    .modify { value =>
                      val newPeers = peers.filterNot(value)
                      (value ++ newPeers, newPeers)
                    }
                ) >>= Stream.emits
            }
          }
          .recoverWith {
            case e =>
              Stream.eval(logger.debug(e)("Failed query")) *> Stream.empty
          }
      iteration(bootstrapNodeList)
    }
  }
}