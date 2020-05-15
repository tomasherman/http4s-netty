package org.http4s.netty

import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}

import cats.implicits._
import cats.effect.{IO, Timer}
import org.http4s.{HttpRoutes, Response}
import org.http4s.implicits._
import org.http4s.dsl.io._
import fs2._

import scala.concurrent.duration._

class NettyServerTest extends NettySuite {

  val server = resourceFixture(
    NettyServerBuilder[IO]
      .withHttpApp(NettyServerTest.routes)
      .withIdleTimeout(2.seconds)
      .withExecutionContext(munitExecutionContext)
      .withoutBanner
      .bindAny()
      .resource,
    "server"
  )
  val client = HttpClient.newHttpClient()

  test("simple") {
    val uri = server().baseUri / "simple"
    val s   = client.sendIO(HttpRequest.newBuilder(uri.toURI).build(), BodyHandlers.ofString())
    s.map { res =>
      assertEquals(res.body(), "simple path")
    }
  }

  test("no-content") {
    val uri = server().baseUri / "no-content"
    val s   = client.sendIO(HttpRequest.newBuilder(uri.toURI).build(), BodyHandlers.discarding())
    s.map { res =>
      assertEquals(res.statusCode(), 204)
    }
  }

  test("delayed") {
    val uri = server().baseUri / "delayed"
    val s   = client.sendIO(HttpRequest.newBuilder(uri.toURI).build(), BodyHandlers.ofString())
    s.map { res =>
      assertEquals(res.statusCode(), 200)
      assertEquals(res.body(), "delayed path")
    }
  }
  test("chunked") {
    val uri = server().baseUri / "chunked"
    val s   = client.sendIO(
      HttpRequest
        .newBuilder(uri.toURI)
        .timeout(java.time.Duration.ofSeconds(5))
        .POST(BodyPublishers.ofString("hello"))
        .build(),
      BodyHandlers.ofString()
    )
    s.map { res =>
      val transfer = res.headers().firstValue("Transfer-Encoding").orElse("not-chunked")
      assertEquals(transfer, "chunked")
      assertEquals(res.statusCode(), 200)
      assertEquals(res.body(), "hello")
    }
  }
  test("timeout") {
    val uri = server().baseUri / "timeout"
    val s   = client.sendIO(
      HttpRequest
        .newBuilder(uri.toURI)
        .timeout(java.time.Duration.ofSeconds(5))
        .build(),
      BodyHandlers.ofString()
    )
    s.attempt.map(e => assert(e.isLeft))
  }
}

object NettyServerTest {
  def routes(implicit timer: Timer[IO]) =
    HttpRoutes
      .of[IO] {
        case req @ _ -> Root / "echo"        => Ok(req.as[String])
        case GET -> Root / "simple"          => Ok("simple path")
        case req @ POST -> Root / "chunked"  =>
          Response[IO](Ok)
            .withEntity(Stream.eval(req.as[String]))
            .pure[IO]
        case GET -> Root / "timeout"         => IO.never
        case GET -> Root / "delayed"         =>
          timer.sleep(1.second) *>
            Ok("delayed path")
        case GET -> Root / "no-content"      => NoContent()
        case GET -> Root / "not-found"       => NotFound("not found")
        case GET -> Root / "empty-not-found" => NotFound()
        case GET -> Root / "internal-error"  => InternalServerError()
      }
      .orNotFound
}
