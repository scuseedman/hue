/*
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera.hue.livy.repl.sparkr

import java.nio.file.Files
import java.util.concurrent.locks.ReentrantLock

import com.cloudera.hue.livy.repl.process.ProcessInterpreter
import org.apache.commons.codec.binary.Base64
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.json4s.{JValue, _}

import scala.annotation.tailrec
import scala.io.Source

private object SparkRInterpreter {
  val LIVY_END_MARKER = "----LIVY_END_OF_COMMAND----"
  val PRINT_MARKER = f"""print("$LIVY_END_MARKER")"""
  val EXPECTED_OUTPUT = f"""[1] "$LIVY_END_MARKER""""

  val PLOT_REGEX = (
    "(" +
      "(?:bagplot)|" +
      "(?:barplot)|" +
      "(?:boxplot)|" +
      "(?:dotchart)|" +
      "(?:hist)|" +
      "(?:lines)|" +
      "(?:pie)|" +
      "(?:pie3D)|" +
      "(?:plot)|" +
      "(?:qqline)|" +
      "(?:qqnorm)|" +
      "(?:scatterplot)|" +
      "(?:scatterplot3d)|" +
      "(?:scatterplot\\.matrix)|" +
      "(?:splom)|" +
      "(?:stripchart)|" +
      "(?:vioplot)" +
    ")"
    ).r.unanchored
}

private class SparkRInterpreter(process: Process)
  extends ProcessInterpreter(process)
{
  import SparkRInterpreter._

  implicit val formats = DefaultFormats

  private[this] var executionCount = 0

  final override protected def waitUntilReady(): Unit = {
    // Set the option to catch and ignore errors instead of halting.
    sendExecuteRequest("options(error = dump.frames)")
    executionCount = 0
  }

  override protected def sendExecuteRequest(command: String): Option[JValue] = synchronized {
    var code = command

    // Create a image file if this command is trying to plot.
    val tempFile = PLOT_REGEX.findFirstIn(code).map { case _ =>
      val tempFile = Files.createTempFile("", ".png")
      val tempFileString = tempFile.toAbsolutePath

      code = f"""png("$tempFileString")\n$code\ndev.off()"""

      tempFile
    }

    try {
      executionCount += 1

      var content = Map(
        "text/plain" -> (sendRequest(code) + takeErrorLines())
      )

      // If we rendered anything, pass along the last image.
      tempFile.foreach { case file =>
        val bytes = Files.readAllBytes(file)
        if (bytes.nonEmpty) {
          val image = Base64.encodeBase64String(bytes)
          content = content + (("image/png", image))
        }
      }

      Some(parse(write(
        Map(
          "status" -> "ok",
          "execution_count" -> (executionCount - 1),
          "data" -> content
        ))))
    } catch {
      case e: Error =>
        Some(parse(write(
        Map(
          "status" -> "error",
          "ename" -> "Error",
          "evalue" -> e.output,
          "data" -> Map(
            "text/plain" -> takeErrorLines()
          )
        ))))
      case e: Exited =>
        None
    } finally {
      tempFile.foreach(Files.delete)
    }

  }

  private def sendRequest(code: String): String = {
    stdin.println(code)
    stdin.flush()

    stdin.println(PRINT_MARKER)
    stdin.flush()

    readTo(EXPECTED_OUTPUT)
  }

  override protected def sendShutdownRequest() = {
    stdin.println("q()")
    stdin.flush()

    while (stdout.readLine() != null) {}
  }

  @tailrec
  private def readTo(marker: String, output: StringBuilder = StringBuilder.newBuilder): String = {
    var char = readChar(output)

    // Remove any ANSI color codes which match the pattern "\u001b\\[[0-9;]*[mG]".
    // It would be easier to do this with a regex, but unfortunately I don't see an easy way to do
    // without copying the StringBuilder into a string for each character.
    if (char == '\u001b') {
      if (readChar(output) == '[') {
        char = readDigits(output)

        if (char == 'm' || char == 'G') {
          output.delete(output.lastIndexOf('\u001b'), output.length)
        }
      }
    }

    if (output.endsWith(marker)) {
      val result = output.toString()
      result.substring(0, result.length - marker.length)
        .stripPrefix("\n")
        .stripSuffix("\n")
    } else {
      readTo(marker, output)
    }
  }

  private def readChar(output: StringBuilder): Char = {
    val byte = stdout.read()
    if (byte == -1) {
      throw new Exited(output.toString())
    } else {
      val char = byte.toChar
      System.out.print(char)
      output.append(char)
      char
    }
  }

  @tailrec
  private def readDigits(output: StringBuilder): Char = {
    val byte = stdout.read()
    if (byte == -1) {
      throw new Exited(output.toString())
    }

    val char = byte.toChar

    if (('0' to '9').contains(char)) {
      output.append(char)
      readDigits(output)
    } else {
      char
    }
  }

  private class Exited(val output: String) extends Exception {}
  private class Error(val output: String) extends Exception {}

  private[this] val _lock = new ReentrantLock()
  private[this] var stderrLines = Seq[String]()

  private def takeErrorLines(): String = {
    var lines: Seq[String] = null
    _lock.lock()
    try {
      lines = stderrLines
      stderrLines = Seq[String]()
    } finally {
      _lock.unlock()
    }

    lines.mkString("\n")
  }

  private[this] val stderrThread = new Thread("sparkr stderr thread") {
    override def run() = {
      val lines = Source.fromInputStream(process.getErrorStream).getLines()

      for (line <- lines) {
        System.out.print(line)
        _lock.lock()
        try {
          stderrLines :+= line
        } finally {
          _lock.unlock()
        }
      }
    }
  }

  stderrThread.setDaemon(true)
  stderrThread.start()
}
