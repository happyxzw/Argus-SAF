/*
 * Copyright (c) 2018. Fengguo Wei and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Detailed contributors are listed in the CONTRIBUTOR.md
 */

package org.argus.jnsaf.client

import java.io.{BufferedInputStream, FileInputStream, FileNotFoundException, IOException}
import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.google.protobuf.ByteString
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import org.argus.jawa.core.io.Reporter
import org.argus.jawa.core.util._
import org.argus.jnsaf.server.jnsaf_grpc.{JNSafGrpc, LoadAPKRequest, LoadAPKResponse}
import org.argus.nativedroid.server.server._

//noinspection ScalaDeprecation
class JNSafClient(address: String, port: Int, reporter: Reporter) {
  final val TITLE = "JNSafClient"
  private val channel = ManagedChannelBuilder.forAddress(address, port).usePlaintext(true).build
  private val client = JNSafGrpc.stub(channel)
  private val blocking_client = NativeDroidServerGrpc.blockingStub(channel)

  private val loadedAPKs: MMap[FileResourceUri, String] = mmapEmpty



  @throws[InterruptedException]
  def shutdown(): Unit = {
    channel.shutdown.awaitTermination(5, TimeUnit.SECONDS)
  }

  private def startStream(fileUri: FileResourceUri): Option[String] = {
    val doneSignal = new CountDownLatch(1)
    val responseObserver = new StreamObserver[LoadAPKResponse]() {
      override def onNext(value: LoadAPKResponse): Unit = {
        loadedAPKs(fileUri) = value.apkDigest
        reporter.echo(TITLE,"Client LoadBinaryResponse onNext")
      }

      override def onError(t: Throwable): Unit = {
        reporter.echo(TITLE,"Client LoadBinaryResponse onError")
        doneSignal.countDown()
      }

      override def onCompleted(): Unit = {
        reporter.echo(TITLE,"Client LoadBinaryResponse onCompleted")
        doneSignal.countDown()
      }
    }
    val requestObserver = client.loadAPK(responseObserver)
    try {
      val file = FileUtil.toFile(fileUri)
      if (!file.exists) {
        reporter.echo(TITLE,"File does not exist")
        return None
      }
      try {
        val bInputStream = new BufferedInputStream(new FileInputStream(file))
        val bufferSize = 1024 * 1024 // 1M
        val buffer = new Array[Byte](bufferSize)
        var tmp = 0
        var size = 0
        while ( {
          tmp = bInputStream.read(buffer); tmp > 0
        }) {
          size += tmp
          val byteString = ByteString.copyFrom(buffer)
          val req = LoadAPKRequest(byteString)
          requestObserver.onNext(req)
        }
      } catch {
        case e: FileNotFoundException =>
          e.printStackTrace()
        case e: IOException =>
          e.printStackTrace()
      }
    } catch {
      case e: RuntimeException =>
        requestObserver.onError(e)
        throw e
    }
    requestObserver.onCompleted()
    // Receiving happens asynchronously
    if (!doneSignal.await(1, TimeUnit.MINUTES)) {
      reporter.error(TITLE, "loadBinary can not finish within 1 minutes")
    }
    loadedAPKs.get(fileUri)
  }

  def loadAPK(soFileUri: FileResourceUri): Option[String] = {
    try {
      startStream(soFileUri)
    } catch {
      case e: Exception =>
        e.printStackTrace()
        None
    }
  }

  private def getAPKHandle(soFileUri: FileResourceUri): String = {
    this.loadedAPKs.get(soFileUri) match {
      case Some(soHandle) => soHandle
      case None =>
        loadAPK(soFileUri) match {
          case Some(soHandle) => soHandle
          case None =>
            throw new RuntimeException(s"Load binary $soFileUri failed.")
        }
    }
  }
}