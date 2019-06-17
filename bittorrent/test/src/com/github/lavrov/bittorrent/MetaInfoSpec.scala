package com.github.lavrov.bittorrent

import org.scalatest.FlatSpec
import org.scalatest.MustMatchers._
import com.github.lavrov.bencode._
import com.github.lavrov.bittorrent.Info.{File, MultipleFiles, SingleFile}
import scodec.bits.{Bases, BitVector, ByteVector}

class MetaInfoSpec extends FlatSpec {

  it should "encode file class" in {
    MetaInfo.FileFormat.write(Info.File(77, "abc" :: Nil)) mustBe Right(
      Bencode.BDictionary(
        Map(
          "length" -> Bencode.BInteger(77),
          "path" -> Bencode.BList(Bencode.BString("abc") :: Nil)
        )
      )
    )
  }

  it should "calculate info_hash" in {
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAllBytes()
    val Right(result) = decode(source)
    val decodedResult = MetaInfo.RawInfoFormat.read(result)
    decodedResult
      .map(com.github.lavrov.bittorrent.util.sha1Hash)
      .map(_.toHex(Bases.Alphabets.HexUppercase)) mustBe Right(
      "8C4ADBF9EBE66F1D804FB6A4FB9B74966C3AB609"
    )
  }

  it should "decode either a or b" in {
    val input = Bencode.BDictionary(
      Map(
        "name" -> Bencode.BString("file_name"),
        "piece length" -> Bencode.BInteger(10),
        "pieces" -> Bencode.BString.Empty,
        "length" -> Bencode.BInteger(10)
      )
    )

    MetaInfo.InfoFormat.read(input) mustBe Right(
      SingleFile("file_name", 10, ByteVector.empty, 10, None)
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

    MetaInfo.InfoFormat.read(input1) mustBe Right(
      MultipleFiles(10, ByteVector.empty, File(10, "/root" :: Nil) :: Nil)
    )
  }

  it should "decode dictionary" in {
    val input = Bencode.BDictionary(
      Map(
        "name" -> Bencode.BString("file_name"),
        "piece length" -> Bencode.BInteger(10),
        "pieces" -> Bencode.BString(ByteVector(10)),
        "length" -> Bencode.BInteger(10)
      )
    )

    MetaInfo.SingleFileFormat.read(input) mustBe Right(
      SingleFile("file_name", 10, ByteVector(10), 10, None)
    )
  }

  it should "decode ubuntu torrent" in {
    decode(BitVector.encodeAscii("i56e").right.get) mustBe Right(Bencode.BInteger(56L))
    decode(BitVector.encodeAscii("2:aa").right.get) mustBe Right(Bencode.BString("aa"))
    decode(BitVector.encodeAscii("l1:a2:bbe").right.get) mustBe Right(
      Bencode.BList(Bencode.BString("a") :: Bencode.BString("bb") :: Nil)
    )
    decode(BitVector.encodeAscii("d1:ai6ee").right.get) mustBe Right(
      Bencode.BDictionary(Map("a" -> Bencode.BInteger(6)))
    )
    val source = getClass.getClassLoader
      .getResourceAsStream("bencode/ubuntu-18.10-live-server-amd64.iso.torrent")
      .readAllBytes()
    val Right(result) = decode(source)
    val decodeResult = MetaInfo.MetaInfoFormat.read(result)
    decodeResult.isRight mustBe true
  }


}
