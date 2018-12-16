package com.github.lavrov.bittorrent
package dht

import java.net.InetSocketAddress
import java.nio.channels.InterruptedByTimeoutException

import cats._
import cats.data.NonEmptyList
import cats.implicits._
import fs2.io.udp.{Packet, Socket}
import com.github.lavrov.bencode.{decode, encode}
import com.github.lavrov.bittorrent.dht.message.{Message, Query, Response}
import fs2.Chunk

import scala.concurrent.duration.DurationInt

class Client[F[_]: Monad](selfId: NodeId, socket: Socket[F])(implicit M: MonadError[F, Throwable]) {

  def readMessage: F[Message] =
    for {
      packet <- socket.read(5.seconds.some)
      bc <- M.fromEither(
        decode(packet.bytes.toArray).left.map(e => new Exception(e.message)))
      message <- M.fromEither(
        Message.MessageFormat.read(bc).left.map(e => new Exception(e)))
    } yield message

  def sendMessage(address: InetSocketAddress, message: Message): F[Unit] =
    for {
      bc <- M.fromEither(Message.MessageFormat.write(message).left.map(new Exception(_)))
      bytes = encode(bc)
      _ <- socket.write(Packet(address, Chunk.byteVector(bytes.bytes)))

    } yield ()


  def getPeersAlgo(infoHash: InfoHash): F[List[PeerInfo]] = {
    def iteration(nodesToTry: NonEmptyList[NodeInfo]): F[List[PeerInfo]] = {
      val nodeInfo = nodesToTry.head
      val logic: F[List[PeerInfo]] =
        for {
          _ <- sendMessage(nodeInfo.address, Message.QueryMessage("aa", Query.GetPeers(selfId, infoHash)))
          _ = println(s"Querying $nodeInfo")
          m <- readMessage
          _ = println(s"Got message $m")
          response <- M.fromEither(
            m match {
              case Message.ResponseMessage("aa", bc) =>
                val reader =
                  Message.PeersResponseFormat.read.widen[Response].orElse(Message.NodesResponseFormat.read.widen[Response])
                reader(bc).leftMap(new Exception(_))
              case other =>
                Left(new Exception(s"Expected response but got $other"))
            }
          )
          peers <- response match {
            case Response.Nodes(_, nodes) => nodes.sortBy(n => NodeId.distance(n.id, infoHash)).toNel match {
              case Some(ns) => iteration(ns)
              case _ => M.raiseError(new Exception("Failed to find peers"))
            }
            case Response.Peers(_, peers) => M.pure(peers)
          }
        } yield peers
      logic.recoverWith {
        case e: InterruptedByTimeoutException =>
          nodesToTry.tail.toNel match {
            case Some(ns) => iteration(ns)
            case _ => M.raiseError(new Exception("Tried all nodes"))
          }
      }
    }
    for {
      _ <- sendMessage(Client.BootstrapNode, Message.QueryMessage("aa", Query.Ping(selfId)))
      m <- readMessage
      r <- M.fromEither(
        PartialFunction.condOpt(m){
          case Message.ResponseMessage(transactionId, response) =>
            Message.PingResponseFormat.read(response).leftMap(e => new Exception(e))
        }
          .toRight(new Exception("Got wrong message"))
          .flatten
      )
      r <- iteration(NonEmptyList.one(NodeInfo(r.id, Client.BootstrapNode)))
    } yield r
  }
}

object Client {
  val BootstrapNode = new InetSocketAddress("router.bittorrent.com", 6881)
}
