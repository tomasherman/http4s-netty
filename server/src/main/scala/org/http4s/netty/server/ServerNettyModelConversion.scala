package org.http4s.netty.server

import cats.implicits._
import cats.effect.{ConcurrentEffect, IO, Sync}
import com.typesafe.netty.http.DefaultWebSocketHttpResponse
import fs2.interop.reactivestreams._
import io.chrisdavenport.vault.Vault
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.handler.codec.http.websocketx.{
  BinaryWebSocketFrame,
  CloseWebSocketFrame,
  ContinuationWebSocketFrame,
  PingWebSocketFrame,
  PongWebSocketFrame,
  TextWebSocketFrame,
  WebSocketServerHandshakerFactory,
  WebSocketFrame => WSFrame
}
import io.netty.handler.codec.http.{
  DefaultHttpResponse,
  HttpHeaders,
  HttpResponseStatus,
  HttpVersion
}
import javax.net.ssl.SSLEngine
import org.http4s.{Header, Request, Response, HttpVersion => HV}
import org.http4s.netty.NettyModelConversion
import org.http4s.server.{SecureSession, ServerRequestKeys}
import org.http4s.websocket.{WebSocketContext, WebSocketFrame}
import org.http4s.websocket.WebSocketFrame._
import org.reactivestreams.{Processor, Subscriber, Subscription}
import scodec.bits.ByteVector

final class ServerNettyModelConversion[F[_]](implicit F: ConcurrentEffect[F])
    extends NettyModelConversion[F] {
  override protected def requestAttributes(
      optionalSslEngine: Option[SSLEngine],
      channel: Channel): Vault =
    super
      .requestAttributes(optionalSslEngine, channel)
      .insert(
        ServerRequestKeys.SecureSession,
        //Create SSLSession object only for https requests and if current SSL session is not empty. Here, each
        //condition is checked inside a "flatMap" to handle possible "null" values
        optionalSslEngine
          .flatMap(engine => Option(engine.getSession))
          .flatMap { session =>
            (
              Option(session.getId).map(ByteVector(_).toHex),
              Option(session.getCipherSuite),
              Option(session.getCipherSuite).map(CertificateInfo.deduceKeyLength),
              Option(CertificateInfo.getPeerCertChain(session))
            ).mapN(SecureSession.apply)
          }
      )

  /** Create a Netty response from the result */
  def toNettyResponseWithWebsocket(
      httpRequest: Request[F],
      httpResponse: Response[F],
      dateString: String,
      maxPayloadLength: Int
  ): F[DefaultHttpResponse] = {
    //Http version is 1.0. We can assume it's most likely not.
    var minorIs0 = false
    val httpVersion: HttpVersion =
      if (httpRequest.httpVersion == HV.`HTTP/1.1`)
        HttpVersion.HTTP_1_1
      else if (httpRequest.httpVersion == HV.`HTTP/1.0`) {
        minorIs0 = true
        HttpVersion.HTTP_1_0
      } else
        HttpVersion.valueOf(httpRequest.httpVersion.toString)

    httpResponse.attributes.lookup(org.http4s.server.websocket.websocketKey[F]) match {
      case Some(wsContext) if !minorIs0 =>
        toWSResponse(
          httpRequest,
          httpResponse,
          httpVersion,
          wsContext,
          dateString,
          maxPayloadLength)
      case _ =>
        F.pure(toNonWSResponse(httpRequest, httpResponse, httpVersion, dateString, minorIs0))
    }
  }

  /** Render a websocket response, or if the handshake fails eventually, an error
    * Note: This function is only invoked for http 1.1, as websockets
    * aren't supported for http 1.0.
    *
    * @param httpRequest The incoming request
    * @param httpResponse The outgoing http4s reponse
    * @param httpVersion The calculated netty http version
    * @param wsContext the websocket context
    * @param dateString
    * @return
    */
  private[this] def toWSResponse(
      httpRequest: Request[F],
      httpResponse: Response[F],
      httpVersion: HttpVersion,
      wsContext: WebSocketContext[F],
      dateString: String,
      maxPayloadLength: Int
  ): F[DefaultHttpResponse] =
    if (httpRequest.headers.exists(h =>
        h.name.toString.equalsIgnoreCase("Upgrade") && h.value.equalsIgnoreCase("websocket"))) {
      val wsProtocol = if (httpRequest.isSecure.exists(identity)) "wss" else "ws"
      val wsUrl = s"$wsProtocol://${httpRequest.serverAddr}${httpRequest.pathInfo}"
      val factory = new WebSocketServerHandshakerFactory(wsUrl, "*", true, maxPayloadLength)
      StreamSubscriber[F, WebSocketFrame].flatMap { subscriber =>
        F.delay {
          val processor = new Processor[WSFrame, WSFrame] {
            def onError(t: Throwable): Unit = subscriber.onError(t)

            def onComplete(): Unit = subscriber.onComplete()

            def onNext(t: WSFrame): Unit = subscriber.onNext(nettyWsToHttp4s(t))

            def onSubscribe(s: Subscription): Unit = subscriber.onSubscribe(s)

            def subscribe(s: Subscriber[_ >: WSFrame]): Unit =
              wsContext.webSocket.send.map(wsbitsToNetty).toUnicastPublisher.subscribe(s)
          }

          F.runAsync {
            subscriber
              .stream(Sync[F].unit)
              .through(wsContext.webSocket.receive)
              .compile
              .drain
          }(_ => IO.unit)
            .unsafeRunSync()
          val resp: DefaultHttpResponse =
            new DefaultWebSocketHttpResponse(httpVersion, HttpResponseStatus.OK, processor, factory)
          wsContext.headers.foreach(appendAllToNetty(_, resp.headers()))
          resp
        }.handleErrorWith(_ =>
          wsContext.failureResponse.map(
            toNonWSResponse(httpRequest, _, httpVersion, dateString, true)))
      }
    } else
      F.pure(toNonWSResponse(httpRequest, httpResponse, httpVersion, dateString, true))

  private[this] def appendAllToNetty(header: Header, nettyHeaders: HttpHeaders) = {
    nettyHeaders.add(header.name.toString(), header.value)
    ()
  }

  private[this] def wsbitsToNetty(w: WebSocketFrame): WSFrame =
    w match {
      case Text(str, last) => new TextWebSocketFrame(last, 0, str)
      case Binary(data, last) =>
        new BinaryWebSocketFrame(last, 0, Unpooled.wrappedBuffer(data.toArray))
      case Ping(data) => new PingWebSocketFrame(Unpooled.wrappedBuffer(data.toArray))
      case Pong(data) => new PongWebSocketFrame(Unpooled.wrappedBuffer(data.toArray))
      case Continuation(data, last) =>
        new ContinuationWebSocketFrame(last, 0, Unpooled.wrappedBuffer(data.toArray))
      case Close(data) => new CloseWebSocketFrame(true, 0, Unpooled.wrappedBuffer(data.toArray))
    }

  private[this] def nettyWsToHttp4s(w: WSFrame): WebSocketFrame =
    w match {
      case c: TextWebSocketFrame => Text(ByteVector(bytebufToArray(c.content())), c.isFinalFragment)
      case c: BinaryWebSocketFrame =>
        Binary(ByteVector(bytebufToArray(c.content())), c.isFinalFragment)
      case c: PingWebSocketFrame => Ping(ByteVector(bytebufToArray(c.content())))
      case c: PongWebSocketFrame => Pong(ByteVector(bytebufToArray(c.content())))
      case c: ContinuationWebSocketFrame =>
        Continuation(ByteVector(bytebufToArray(c.content())), c.isFinalFragment)
      case c: CloseWebSocketFrame => Close(ByteVector(bytebufToArray(c.content())))
    }
}
