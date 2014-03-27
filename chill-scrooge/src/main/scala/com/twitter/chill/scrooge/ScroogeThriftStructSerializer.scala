/*
Copyright 2013 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.twitter.chill.scrooge

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.twitter.scrooge.{ThriftStructSerializer, ThriftStructCodec, ThriftStruct}
import org.apache.thrift.protocol.{TBinaryProtocol, TProtocolFactory}
import scala.collection.mutable
import scala.util.Try

/**
 * Kryo serializer for Scrooge generated Thrift structs
 * this probably isn't thread safe, but neither is Kryo
 */

object ScroogeThriftStructSerializer {
  /* don't serialize classToCodec because it contains anonymous inner ThriftStructSerializers that have reference to
   * ScroogeThriftStructSerializer, which itself has a reference to classToCodec etc.
   */
  @transient lazy private[this] val classToTSS: mutable.Map[Class[_], ThriftStructSerializer[_]] = {
    mutable.Map()
  }

  private def getObject[T](companionClass: Class[T]): AnyRef =
    companionClass.getField("MODULE$").get(null)

  /**
   * For unions, we split on $ after the dot.
   * this is costly, but only done once per Class
   */
  private[this] def codecForUnion[T <: ThriftStruct](maybeUnion: Class[T]): Try[ThriftStructCodec[T]] =
    Try(getObject(Class.forName(maybeUnion.getName.reverse.dropWhile(_ != '$').reverse)))
      .map(_.asInstanceOf[ThriftStructCodec[T]])

  private[this] def codecForNormal[T <: ThriftStruct](thriftStructClass: Class[T]): Try[ThriftStructCodec[T]] =
    Try(getObject(Class.forName(thriftStructClass.getName + "$")))
      .map(_.asInstanceOf[ThriftStructCodec[T]])

  // the companion to a ThriftStruct generated by scrooge will always be its codec
  private[this] def constructCodec[T <: ThriftStruct](thriftStructClass: Class[T]): ThriftStructCodec[T] =
    codecForNormal(thriftStructClass)
      .orElse(codecForUnion(thriftStructClass))
      .get

  private[this] def constructThriftStructSerializer[T <: ThriftStruct](thriftStructClass: Class[T]): ThriftStructSerializer[T] = {
    // capture the codec here:
    val newCodec = constructCodec(thriftStructClass)
    new ThriftStructSerializer[T] {
      val protocolFactory = new TBinaryProtocol.Factory
      override def codec = newCodec
    }
  }

  def lookupThriftStructSerializer[T <: ThriftStruct](thriftStructClass: Class[_ <: T]): ThriftStructSerializer[T] = {
    val tss = classToTSS.getOrElseUpdate(thriftStructClass, constructThriftStructSerializer(thriftStructClass))
    tss.asInstanceOf[ThriftStructSerializer[T]]
  }

  def lookupThriftStructSerializer[T <: ThriftStruct](thriftStruct: T): ThriftStructSerializer[T] = {
    lookupThriftStructSerializer(thriftStruct.getClass)
  }

}

class ScroogeThriftStructSerializer[T <: ThriftStruct] extends Serializer[T] {
  import ScroogeThriftStructSerializer._
  override def write(kryo: Kryo, output: Output, thriftStruct: T): Unit = {
    try {
      val thriftStructSerializer = lookupThriftStructSerializer(thriftStruct)
      val serThrift = thriftStructSerializer.toBytes(thriftStruct)
      output.writeInt(serThrift.length, true)
      output.writeBytes(serThrift)
    } catch {
      case e: Exception => throw new RuntimeException("Could not serialize ThriftStruct of type " + thriftStruct.getClass, e)
    }
  }



  /* nb: thriftStructClass doesn't actually have type Class[T] it has type Class[_ <: T]
   * this lie is courtesy of the Kryo API
   * */
  override def read(kryo: Kryo, input: Input, thriftStructClass: Class[T]): T = {
    // code reviewers: is this use of an anonymous inner class ok, or should I separate it out into something outside?
    try {
      val thriftStructSerializer = lookupThriftStructSerializer(thriftStructClass)
      val tSize = input.readInt(true)
      val barr = new Array[Byte](tSize)
      input.readBytes(barr)
      thriftStructSerializer.fromBytes(barr)
    } catch {
      case e: Exception => throw new RuntimeException("Could not create ThriftStruct " + thriftStructClass, e)
    }
  }



}
