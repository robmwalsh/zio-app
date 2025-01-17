package zio.app.internal

import boopickle.CompositePickler
import boopickle.Default._
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.{HttpHeaderNames, HttpHeaderValues}
import zhttp.http._
import zio._
import zio.stream.{UStream, ZStream}

import java.nio.ByteBuffer

object BackendUtils {
  implicit val exPickler: CompositePickler[Throwable] = exceptionPickler

  private val bytesContent: Header = (HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.BYTES)

  private def urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8")

  def makeRoute[R, E: Pickler, A: Pickler, B: Pickler](
      service: String,
      method: String,
      call: A => ZIO[R, E, B]
  ): HttpApp[R, Nothing] = {
    val service0 = urlEncode(service)
    val method0  = method
    Http.collectZIO { case post @ Method.POST -> !! / `service0` / `method0` =>
      post.bodyAsString.orDie.flatMap { str =>
        val byteBuffer = ByteBuffer.wrap(str.getBytes)
        val unpickled  = Unpickle[A].fromBytes(byteBuffer)
        call(unpickled)
          .map(ZioResponse.succeed)
          .catchAllCause(causeToResponseZio[E](_))
          .map(pickle[ZioResponse[E, B]](_))
      }
    }
  }

  def makeRouteNullary[R, E: Pickler, A: Pickler](
      service: String,
      method: String,
      call: ZIO[R, E, A]
  ): HttpApp[R, Nothing] = {
    val service0 = urlEncode(service)
    val method0  = method
    Http.collectZIO { case Method.GET -> !! / `service0` / `method0` =>
      call
        .map(ZioResponse.succeed)
        .catchAllCause(causeToResponseZio[E](_))
        .map(pickle[ZioResponse[E, A]](_))
    }
  }

  def makeRouteStream[R, E: Pickler, A: Pickler, B: Pickler](
      service: String,
      method: String,
      call: A => ZStream[R, E, B]
  ): HttpApp[R, Nothing] = {
    val service0 = service
    val method0  = method
    Http.collectZIO { case post @ Method.POST -> !! / `service0` / `method0` =>
      post.body.orDie.flatMap { str =>
        val byteBuffer = ByteBuffer.wrap(str.toArray)
        val unpickled  = Unpickle[A].fromBytes(byteBuffer)
        ZIO.environment[R].map { env =>
          makeStreamResponse(call(unpickled), env)
        }
      }
    }
  }

  def makeRouteNullaryStream[R, E: Pickler, A: Pickler](
      service: String,
      method: String,
      call: ZStream[R, E, A]
  ): HttpApp[R, Nothing] = {
    val service0 = service
    val method0  = method
    Http.collectZIO { case Method.GET -> !! / `service0` / `method0` =>
      ZIO.environment[R].map { env =>
        makeStreamResponse(call, env)
      }
    }
  }

  private def pickle[A: Pickler](value: A): Response = {
    val bytes: ByteBuffer = Pickle.intoBytes(value)
    val byteBuf           = Unpooled.wrappedBuffer(bytes)
    val httpData          = HttpData.fromByteBuf(byteBuf)
    println(s"PICKLING ${value}")

    Response(status = Status.OK, headers = Headers(bytesContent), data = httpData)
  }

  private def makeStreamResponse[A: Pickler, E: Pickler, R](
      stream: ZStream[R, E, A],
      env: ZEnvironment[R]
  ): Response = {
    val responseStream: ZStream[Any, Nothing, Byte] =
      stream
        .map(ZioResponse.succeed)
        .catchAllCause(causeToResponseStream(_))
        .mapConcatChunk { a =>
          Chunk.fromByteBuffer(Pickle.intoBytes(a))
        }
        .provideEnvironment(env)

    Response(data = HttpData.fromStream(responseStream))
  }

  private def causeToResponseStream[E: Pickler](cause: Cause[E]): UStream[ZioResponse[E, Nothing]] =
    cause.find {
      case Cause.Fail(failure, _) => ZStream(ZioResponse.fail(failure))
      case Cause.Die(die, _)      => ZStream(ZioResponse.die(die))
      // TODO: Fix ids.head
      case Cause.Interrupt(fiberId, _) => ZStream(ZioResponse.interrupt(fiberId.ids.head))
    }.get

  private def causeToResponseZio[E: Pickler](cause: Cause[E]): UIO[ZioResponse[E, Nothing]] =
    cause.find {
      case Cause.Fail(failure, _)      => UIO(ZioResponse.fail(failure))
      case Cause.Die(die, _)           => UIO(ZioResponse.die(die))
      case Cause.Interrupt(fiberId, _) => UIO(ZioResponse.interrupt(fiberId.ids.head))
    }.get
}

object CustomPicklers {
  implicit val nothingPickler: Pickler[Nothing] = new Pickler[Nothing] {
    override def pickle(obj: Nothing)(implicit state: PickleState): Unit = throw new Error("IMPOSSIBLE")
    override def unpickle(implicit state: UnpickleState): Nothing        = throw new Error("IMPOSSIBLE")
  }
}
