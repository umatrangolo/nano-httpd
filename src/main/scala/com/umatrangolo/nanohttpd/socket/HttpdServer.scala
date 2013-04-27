package com.umatrangolo.nanohttpd

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io._
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors._

import scala.io.Source

object Utils {
  def quietly(f: => Unit) {
    try { f } catch { case e: Exception => }
  }

  def closeQuietly(in: InputStream) { quietly { if (in != null) in.close() } }
  def closeQuietly(out: OutputStream) { quietly { if (out != null) out.close() } }
}

import Utils._

object NanoHttpdAcceptor {
  private[NanoHttpdAcceptor] val log: Logger = LoggerFactory.getLogger(classOf[NanoHttpdAcceptor])
  private[NanoHttpdAcceptor] val internalExecutor = newCachedThreadPool()
}

// threads that accepts requests and spawns new worker threads
class NanoHttpdAcceptor(
  private val port: Int, // the server port where to accept connections
  private val root: String // the root folder from where to fetch content
) extends Runnable {
  import NanoHttpdAcceptor._

  require(port > 1024, "Invalid port number: " + port)

  override def run() {
    val serverSocket = new ServerSocket(port)
    log.info("Acceptor %s started".format(this))

    log.info("Accepting connections on " + port)
    while (true) {
      try {
        val clientSocket = serverSocket.accept() // blocks waiting for request
        log.info("Connection request from " + clientSocket)
        internalExecutor.submit(new NanoHttpdWorker(clientSocket, root))
      } catch {
        case e: Exception => log.error("Error while accepting a connection", e)
      }
    }
  }
}

object NanoHttpdWorker {
  private[NanoHttpdWorker] val log: Logger = LoggerFactory.getLogger(classOf[NanoHttpdWorker])
}

class NanoHttpdWorker(clientSocket: Socket, root: String) extends Runnable {
  import NanoHttpdWorker._

  override def run() {
    val in = new BufferedInputStream(clientSocket.getInputStream())
    val out = new BufferedOutputStream(clientSocket.getOutputStream())

    try{
      val now = System.currentTimeMillis
      log.info("Processing " + clientSocket)
      val req = HttpRequest(in, out, root)
      req.execute()
      log.info("Processed " + clientSocket + ". Took: " + (System.currentTimeMillis - now) + " ms.")
    } finally {
      closeQuietly(in)
      closeQuietly(out)
    }
  }
}

trait HttpCmd {
  def execute()
}

case class HttpRequest(root: String, path: String, out: OutputStream) extends HttpCmd {
  import HttpRequest._

  override def execute() = {

    def dump(in: InputStream, out: OutputStream) {
      val bytes = new Array[Byte](64 * 1024)
      val numRead = in.read(bytes)
      if (numRead != -1) {
        out.write(bytes, 0, numRead)
        out.flush()
        dump(in, out)
      }
    }

    def emitInfo(statusCode: Int, contentLength: Long) {
      statusCode match {
        case 200 => out.write("HTTP/1.1 200 OK\r\n".getBytes("utf-8"))
        case 404 => out.write("HTTP/1.1 404 Not Found\r\n".getBytes("utf-8"))
      }
      out.write("Server: nano-httpd\r\n".getBytes("utf-8"))
      out.write("Content-Type: text/html; charset=UTF-8\r\n".getBytes("utf-8"))
      out.write("Content-Length: %s\r\n".format(contentLength).getBytes("utf-8"))
      out.write("\r\n".getBytes("utf-8"))
      out.flush()
    }

    log.info("Fetching content from %s".format(path))

    val file = new File(root + "/" + path)
    if (file.exists && file.isFile && file.getAbsolutePath.indexOf("..") == -1) {
      val fin = new BufferedInputStream(new FileInputStream(file))
      val length = file.length

      emitInfo(200, length)

      try {
        dump(fin, out)
      } catch {
        case e: Exception => throw new RuntimeException("Error while serving content", e)
      } finally {
        closeQuietly(fin)
      }
    } else {
      log.warn("Not found: %s".format(file.getAbsolutePath))
      emitInfo(404, 0)
    }
  }
}

object HttpRequest {
  private[HttpRequest] val log: Logger = LoggerFactory.getLogger(classOf[HttpRequest])

  def apply(in: InputStream, out: OutputStream, root: String): HttpRequest = {
    val lines = Source.fromInputStream(in).getLines

    if (lines.hasNext) {
      val req = lines.next()

      req.split(" ").toList match {
        case "GET" :: path :: "HTTP/1.1" :: Nil => new HttpRequest(root, path, out)
        case _ => throw new UnsupportedOperationException("Not impl yet!")
      }
    } else throw new RuntimeException("Request is empty: " + lines.mkString("\n"))
  }
}

object NanoHttpd extends App {
  private val log: Logger = LoggerFactory.getLogger(this.getClass)

  def parseArguments = args match {
    case Array("--port", n, "--root", s) => (n.toInt, s)
    case Array("--root", s, "--port", n) => (n.toInt, s)
  }

  if (args.size != 4) {
    println("usage: --port <port number> --root <root folder>")
    System.exit(-1)
  }

  val (port, root) = parseArguments
  log.info("Starting server on port %s with root folder: %s".format(port, root))

  val server = new Thread(new NanoHttpdAcceptor(port, root))
  server.setDaemon(false)
  server.start()
}
