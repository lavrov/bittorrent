package com.github.lavrov.bittorrent.dht

import java.net.InetSocketAddress

import cats.implicits._
import cats.effect.implicits._
import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.dht.message.{Message, Response}
import fs2.concurrent.Queue
import fs2.io.udp.SocketGroup
import logstage.LogIO

trait Node[F[_]] {

  def routingTable: RoutingTable[F]

  def client: Client[F]
}

object Node {

  def apply[F[_]](
    selfId: NodeId,
    port: Int
  )(implicit
    F: Concurrent[F],
    timer: Timer[F],
    cs: ContextShift[F],
    socketGroup: SocketGroup,
    logger: LogIO[F]
  ): Resource[F, Node[F]] =
    for {
      routingTable <- Resource.liftF { RoutingTable(selfId) }
      queryHandler = QueryHandler(selfId, routingTable)
      node <- Node(selfId, port, queryHandler, routingTable)
    } yield node

  def apply[F[_]](
    selfId: NodeId,
    port: Int,
    queryHandler: QueryHandler[F],
    routingTable: RoutingTable[F]
  )(implicit
    F: Concurrent[F],
    timer: Timer[F],
    cs: ContextShift[F],
    socketGroup: SocketGroup,
    logger: LogIO[F]
  ): Resource[F, Node[F]] = {
    for {
      messageSocket <- MessageSocket(port)
      responses <- Resource.liftF {
        Queue
          .unbounded[F, (InetSocketAddress, Either[Message.ErrorMessage, Message.ResponseMessage])]
      }
      client0 <- Client(selfId, messageSocket.writeMessage, responses)
      _ <-
        Resource
          .make(
            messageSocket.readMessage
              .flatMap {
                case (a, m: Message.QueryMessage) =>
                  logger.debug(s"Received $m") >>
                  queryHandler(m.query).flatMap { response =>
                    val responseMessage = Message.ResponseMessage(m.transactionId, response)
                    logger.debug(s"Responding with $responseMessage") >>
                    messageSocket.writeMessage(a, responseMessage)
                  }
                case (a, m: Message.ResponseMessage) => responses.enqueue1((a, m.asRight))
                case (a, m: Message.ErrorMessage) => responses.enqueue1((a, m.asLeft))
              }
              .recoverWith {
                case e: Throwable =>
                  logger.error(s"Failed to read message: $e")
              }
              .foreverM
              .start
          )(_.cancel)
      _ <- Resource.liftF {
        NodeBootstrap(client0).flatMap { nodeInfo =>
          logger.info(s"Bootstrapped with $nodeInfo") >>
          routingTable.insert(nodeInfo)
        }
      }
      routingTable0 = routingTable
    } yield new Node[F] {

      def routingTable: RoutingTable[F] = routingTable0

      def client: Client[F] =
        new Client[F] {

          def getPeers(nodeInfo: NodeInfo, infoHash: InfoHash): F[Either[Response.Nodes, Response.Peers]] =
            client0
              .getPeers(nodeInfo, infoHash)
              .flatTap { response =>
                routingTable.insert(nodeInfo)
              }

          def ping(address: InetSocketAddress): F[Response.Ping] =
            client0
              .ping(address)
              .flatTap { response =>
                routingTable.insert(NodeInfo(response.id, address))
              }
        }
    }
  }

}