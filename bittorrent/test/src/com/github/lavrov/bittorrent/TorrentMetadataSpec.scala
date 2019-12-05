package com.github.lavrov.bittorrent

import verify._

import com.github.lavrov.bencode._
import com.github.lavrov.bittorrent.TorrentMetadata.Info, Info.{File, MultipleFiles, SingleFile}
import scodec.bits.{Bases, BitVector, ByteVector}
import TestUtils.InputStreamExtensions

object TorrentMetadataSpec extends BasicTestSuite {

  test("encode file class") {
    val result = TorrentMetadata.FileFormat.write(Info.File(77, "abc" :: Nil))
    val expectation = Right(
      Bencode.BDictionary(
        Map(
          "length" -> Bencode.BInteger(77),
          "path" -> Bencode.BList(Bencode.BString("abc") :: Nil)
        )
      )
    )
    assert(result == expectation)
  }

  test("calculate info_hash") {
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAll()
    val Right(bc) = decode(source)
    val decodedResult = TorrentMetadata.RawInfoFormat.read(bc)
    val result = decodedResult
      .map(encode(_).digest("SHA-1"))
      .map(_.toHex(Bases.Alphabets.HexUppercase))
    val expectation = Right("8C4ADBF9EBE66F1D804FB6A4FB9B74966C3AB609")
    assert(result == expectation)
  }

  test("decode either a or b") {
    val input = Bencode.BDictionary(
      Map(
        "name" -> Bencode.BString("file_name"),
        "piece length" -> Bencode.BInteger(10),
        "pieces" -> Bencode.BString.Empty,
        "length" -> Bencode.BInteger(10)
      )
    )

    assert(
      TorrentMetadata.InfoFormat.read(input) == Right(
        SingleFile("file_name", 10, ByteVector.empty, 10, None)
      )
    )

    val input1 = Bencode.BDictionary(
      Map(
        "piece length" -> Bencode.BInteger(10),
        "pieces" -> Bencode.BString.Empty,
        "files" -> Bencode.BList(
          Bencode.BDictionary(
            Map(
              "length" -> Bencode.BInteger(10),
              "path" -> Bencode.BList(Bencode.BString("/root") :: Nil)
            )
          ) :: Nil
        )
      )
    )

    assert(
      TorrentMetadata.InfoFormat.read(input1) == Right(
        MultipleFiles(10, ByteVector.empty, File(10, "/root" :: Nil) :: Nil)
      )
    )
  }

  test("decode dictionary") {
    val input = Bencode.BDictionary(
      Map(
        "name" -> Bencode.BString("file_name"),
        "piece length" -> Bencode.BInteger(10),
        "pieces" -> Bencode.BString(ByteVector(10)),
        "length" -> Bencode.BInteger(10)
      )
    )

    assert(
      TorrentMetadata.SingleFileFormat.read(input) == Right(
        SingleFile("file_name", 10, ByteVector(10), 10, None)
      )
    )
  }

  test("decode ubuntu torrent") {
    assert(decode(BitVector.encodeAscii("i56e").right.get) == Right(Bencode.BInteger(56L)))
    assert(decode(BitVector.encodeAscii("2:aa").right.get) == Right(Bencode.BString("aa")))
    assert(
      decode(BitVector.encodeAscii("l1:a2:bbe").right.get) == Right(
        Bencode.BList(Bencode.BString("a") :: Bencode.BString("bb") :: Nil)
      )
    )
    assert(
      decode(BitVector.encodeAscii("d1:ai6ee").right.get) == Right(
        Bencode.BDictionary(Map("a" -> Bencode.BInteger(6)))
      )
    )
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAll()
    val Right(result) = decode(source)
    val decodeResult = TorrentMetadata.TorrentMetadataFormatLossless.read(result)
    assert(decodeResult.isRight)
  }

}
