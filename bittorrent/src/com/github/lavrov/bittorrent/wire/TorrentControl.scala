package com.github.lavrov.bittorrent.wire

import cats.data.Chain
import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.all._
import com.github.lavrov.bittorrent.protocol.message.Message
import com.github.lavrov.bittorrent.protocol.message.Message.Request
import com.github.lavrov.bittorrent.{FileStorage, MetaInfo, TorrentMetadata}
import logstage.LogIO
import scodec.bits.ByteVector
import fs2.Stream

trait TorrentControl[F[_]] {
  def getMetaInfo: MetaInfo
  def stats: F[TorrentControl.Stats]
  def downloadAll: Stream[F, TorrentControl.CompletePiece]
  def downloadAllSequentially: Stream[F, TorrentControl.CompletePiece]
  def downloadAllSequentiallyFrom(pieceIndex: Long): Stream[F, TorrentControl.CompletePiece]
  def close: F[Unit]
}

object TorrentControl {

  def apply[F[_]](
    metaInfo: MetaInfo,
    connectionManager: ConnectionManager[F],
    fileStorage: FileStorage[F]
  )(implicit F: Concurrent[F], timer: Timer[F], logger: LogIO[F]): F[TorrentControl[F]] =
    for {
      result <- Dispatcher.start(connectionManager).allocated
      (dispatcher, closeDispatcher) = result
      incompletePieces = buildQueue(metaInfo.parsed).toList
    } yield new TorrentControl[F] {
      def getMetaInfo = metaInfo
      def stats: F[Stats] =
        connectionManager.connected.count.map(Stats)
      def downloadAll: Stream[F, CompletePiece] =
        Stream
          .emits(incompletePieces)
          .covary[F]
          .parEvalMapUnordered(Int.MaxValue)(dispatcher.dispatch)
      def downloadAllSequentially: Stream[F, CompletePiece] =
        Stream
          .emits(incompletePieces)
          .covary[F]
          .parEvalMap(10)(dispatcher.dispatch)
      def downloadAllSequentiallyFrom(pieceIndex: Long): Stream[F, CompletePiece] =
        Stream
          .emits(incompletePieces.drop(pieceIndex.toInt))
          .covary[F]
          .parEvalMap(10)(dispatcher.dispatch)
      def close: F[Unit] =
        closeDispatcher
    }

  def downloadMetaInfo[F[_]](
    connectionManager: ConnectionManager[F]
  )(implicit F: Concurrent[F]): F[MetaInfo] =
    connectionManager.connected.stream
      .evalMap(_.downloadTorrentFile.attempt)
      .collectFirst {
        case Right(Some(metadata)) => metadata
      }
      .evalMap { bytes =>
        F.fromEither(MetaInfo.fromBytes(bytes))
      }
      .compile
      .lastOrError

  case class Stats(
    connected: Int
  )

  case class CompletePiece(index: Long, begin: Long, bytes: ByteVector)

  case class IncompletePiece(
    index: Long,
    begin: Long,
    size: Long,
    checksum: ByteVector,
    requests: List[Message.Request],
    downloadedSize: Long = 0,
    downloaded: Map[Message.Request, ByteVector] = Map.empty
  ) {
    def add(request: Request, bytes: ByteVector): IncompletePiece =
      copy(
        downloadedSize = downloadedSize + request.length,
        downloaded = downloaded.updated(request, bytes)
      )
    def isComplete: Boolean = size == downloadedSize
    def verified: Option[ByteVector] = {
      val joinedChunks: ByteVector =
        downloaded.toList.sortBy(_._1.begin).map(_._2).reduce(_ ++ _)
      if (joinedChunks.digest("SHA-1") == checksum) joinedChunks.some else none
    }
    def reset: IncompletePiece =
      copy(downloadedSize = 0, downloaded = Map.empty)
  }

  def buildQueue(metadata: TorrentMetadata): Chain[IncompletePiece] = {

    def downloadFile(
      pieceLength: Long,
      totalLength: Long,
      pieces: ByteVector
    ): Chain[IncompletePiece] = {
      var result = Chain.empty[IncompletePiece]
      def loop(index: Long): Unit = {
        val thisPieceLength =
          math.min(pieceLength, totalLength - index * pieceLength)
        if (thisPieceLength > 0) {
          val list =
            downloadPiece(index, thisPieceLength)
          result = result append IncompletePiece(
              index,
              index * pieceLength,
              thisPieceLength,
              pieces.drop(index * 20).take(20),
              list.toList
            )
          loop(index + 1)
        }
      }
      loop(0)
      result
    }

    def downloadPiece(pieceIndex: Long, length: Long): Chain[Message.Request] = {
      val chunkSize = 16 * 1024
      var result = Chain.empty[Message.Request]
      def loop(index: Long): Unit = {
        val thisChunkSize = math.min(chunkSize, length - index * chunkSize)
        if (thisChunkSize > 0) {
          val begin = index * chunkSize
          result = result append Message.Request(
              pieceIndex,
              begin,
              thisChunkSize
            )
          loop(index + 1)
        }
      }
      loop(0)
      result
    }

    downloadFile(metadata.pieceLength, metadata.files.map(_.length).sum, metadata.pieces)
  }

  sealed trait Error extends Exception
  object Error {
    case class EmptyMetadata() extends Error
  }
}