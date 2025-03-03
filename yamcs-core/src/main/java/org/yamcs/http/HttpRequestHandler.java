package org.yamcs.http;

import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.api.MediaType;
import org.yamcs.http.api.Router;
import org.yamcs.http.websocket.WebSocketFrameHandler;
import org.yamcs.logging.Log;
import org.yamcs.security.User;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

/**
 * Handles handshakes and messages.
 *
 * We have following different request types
 * <ul>
 * <li>static requests - sent to the fileRequestHandler - do no go higher in the netty pipeline</li>
 * <li>websocket requests - the pipeline is modified to add the websocket handshaker.</li>
 * <li>load data requests - the pipeline is modified by the respective route handler</li>
 * <li>standard API calls (the vast majority) - the HttpObjectAgreggator is added upstream to collect (and limit) all
 * data from the http request in one object.</li>
 * </ul>
 * Because we support multiple http requests on one connection (keep-alive), we have to clean the pipeline when the
 * request type changes
 */
public class HttpRequestHandler extends ChannelInboundHandlerAdapter {

    public static final String ANY_PATH = "*";
    private static final String API_PATH = "api";
    private static final String AUTH_PATH = "auth";
    private static final String STATIC_PATH = "static";
    private static final String WEBSOCKET_PATH = "_websocket";

    public static final AttributeKey<User> CTX_USER = AttributeKey.valueOf("user");

    private static final Log log = new Log(HttpRequestHandler.class);

    public static final Object CONTENT_FINISHED_EVENT = new Object();
    private static StaticFileHandler fileRequestHandler = new StaticFileHandler();

    private static final FullHttpResponse BAD_REQUEST = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST,
            Unpooled.EMPTY_BUFFER);
    public static final String HANDLER_NAME_COMPRESSOR = "hndl_compressor";
    public static final String HANDLER_NAME_CHUNKED_WRITER = "hndl_chunked_writer";

    public static final byte[] NEWLINE_BYTES = "\r\n".getBytes();

    static {
        HttpUtil.setContentLength(BAD_REQUEST, 0);
    }

    private static TokenStore tokenStore = new TokenStore();

    private HttpServer httpServer;
    private String contextPath;
    private Router apiRouter;
    private AuthHandler authHandler;
    private HttpAuthorizationChecker authChecker;
    private boolean contentExpected = false;

    YConfiguration wsConfig;

    public HttpRequestHandler(HttpServer httpServer, Router apiRouter) {
        this.httpServer = httpServer;
        this.apiRouter = apiRouter;
        wsConfig = httpServer.getConfig().getConfig("webSocket");
        contextPath = httpServer.getContextPath();
        authHandler = new AuthHandler(tokenStore, contextPath);
        authChecker = new HttpAuthorizationChecker(tokenStore);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpMessage) {
            DecoderResult dr = ((HttpMessage) msg).decoderResult();
            if (!dr.isSuccess()) {
                log.warn("{} Exception while decoding http message: {}", ctx.channel().id().asShortText(), dr.cause());
                ctx.writeAndFlush(BAD_REQUEST);
                return;
            }
        }

        if (msg instanceof HttpRequest) {
            contentExpected = false;
            processRequest(ctx, (HttpRequest) msg);
            ReferenceCountUtil.release(msg);
        } else if (msg instanceof HttpContent) {
            if (contentExpected) {
                ctx.fireChannelRead(msg);
                if (msg instanceof LastHttpContent) {
                    ctx.fireUserEventTriggered(CONTENT_FINISHED_EVENT);
                }
            } else if (!(msg instanceof LastHttpContent)) {
                log.warn("{} unexpected http content received: {}", ctx.channel().id().asShortText(), msg);
                ReferenceCountUtil.release(msg);
                ctx.close();
            }
        } else {
            log.error("{} unexpected message received: {}", ctx.channel().id().asShortText(), msg);
            ReferenceCountUtil.release(msg);
        }
    }

    private void processRequest(ChannelHandlerContext ctx, HttpRequest req) {
        // We have this also on info level coupled with the HTTP response status
        // code, but this is on debug for an earlier reporting while debugging issues
        log.debug("{} {} {}", ctx.channel().id().asShortText(), req.method(), req.uri());

        try {
            handleRequest(ctx, req);
        } catch (IOException e) {
            log.warn("Exception while handling http request", e);
            sendPlainTextError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        } catch (HttpException e) {
            sendPlainTextError(ctx, req, e.getStatus(), e.getMessage());
        }
    }

    private void handleRequest(ChannelHandlerContext ctx, HttpRequest req)
            throws IOException, HttpException {
        cleanPipeline(ctx.pipeline());

        if (!req.uri().startsWith(contextPath)) {
            sendPlainTextError(ctx, req, NOT_FOUND);
            return;
        }

        String pathString = HttpUtils.getPathWithoutContext(req, contextPath);

        // Note: pathString starts with / so path[0] is always empty
        String[] path = pathString.split("/", 3);

        switch (path[1]) {
        case STATIC_PATH:
            if (path.length == 2) { // do not accept "/static/" (i.e. directory listing) requests
                sendPlainTextError(ctx, req, FORBIDDEN);
                return;
            }
            fileRequestHandler.handleStaticFileRequest(ctx, req, path[2]);
            return;
        case AUTH_PATH:
            ctx.pipeline().addLast(HANDLER_NAME_COMPRESSOR, new HttpContentCompressor());
            ctx.pipeline().addLast(new HttpObjectAggregator(65536));
            ctx.pipeline().addLast(authHandler);
            ctx.fireChannelRead(req);
            contentExpected = true;
            return;
        case API_PATH:
            verifyAuthentication(ctx, req);
            contentExpected = apiRouter.scheduleExecution(ctx, req, pathString);
            return;
        case WEBSOCKET_PATH:
            verifyAuthentication(ctx, req);
            if (path.length == 2) { // No instance specified
                prepareChannelForWebSocketUpgrade(ctx, req, null, null);
            } else {
                path = path[2].split("/", 2);
                if (YamcsServer.hasInstance(path[0])) {
                    if (path.length == 1) {
                        prepareChannelForWebSocketUpgrade(ctx, req, path[0], null);
                    } else {
                        prepareChannelForWebSocketUpgrade(ctx, req, path[0], path[1]);
                    }
                } else {
                    sendPlainTextError(ctx, req, NOT_FOUND);
                }
            }
            return;
        }

        // Maybe a plugin registered a custom handler
        Handler handler = httpServer.createHandler(path[1]);
        if (handler == null) {
            handler = httpServer.createHandler(ANY_PATH);
        }
        if (handler != null) {
            ctx.pipeline().addLast(HANDLER_NAME_COMPRESSOR, new HttpContentCompressor());
            ctx.pipeline().addLast(new HttpObjectAggregator(65536));
            ctx.pipeline().addLast(handler);
            ctx.fireChannelRead(req);
            contentExpected = true;
            return;
        }

        // Too bad.
        sendPlainTextError(ctx, req, NOT_FOUND);
    }

    private void verifyAuthentication(ChannelHandlerContext ctx, HttpRequest req) throws HttpException {
        User user = authChecker.verifyAuth(ctx, req);
        ctx.channel().attr(CTX_USER).set(user);
    }

    /**
     * Adapts Netty's pipeline for allowing WebSocket upgrade
     *
     * @param ctx
     *            context for this channel handler
     */
    private void prepareChannelForWebSocketUpgrade(ChannelHandlerContext ctx, HttpRequest req, String yamcsInstance,
            String processor) {
        contentExpected = true;
        ctx.pipeline().addLast(new HttpObjectAggregator(65536));

        int maxFrameLength = wsConfig.getInt("maxFrameLength");
        int maxDropped = wsConfig.getInt("connectionCloseNumDroppedMsg");
        int lo = wsConfig.getConfig("writeBufferWaterMark").getInt("low");
        int hi = wsConfig.getConfig("writeBufferWaterMark").getInt("high");
        WriteBufferWaterMark waterMark = new WriteBufferWaterMark(lo, hi);

        // Add websocket-specific handlers to channel pipeline
        String webSocketPath = req.uri();
        String subprotocols = "json, protobuf";
        ctx.pipeline().addLast(new WebSocketServerProtocolHandler(webSocketPath, subprotocols, false, maxFrameLength));

        HttpRequestInfo originalRequestInfo = new HttpRequestInfo(req);
        originalRequestInfo.setYamcsInstance(yamcsInstance);
        originalRequestInfo.setProcessor(processor);
        originalRequestInfo.setUser(ctx.channel().attr(CTX_USER).get());
        ctx.pipeline().addLast(new WebSocketFrameHandler(originalRequestInfo, maxDropped, waterMark));

        // Effectively trigger websocket-handler (will attempt handshake)
        ctx.fireChannelRead(req);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof NotSslRecordException) {
            log.info("Non TLS connection (HTTP?) attempted on the HTTPS port, closing channel");
        } else {
            log.error("Closing channel: {}", cause.getMessage());
        }
        ctx.close();
    }

    public static <T extends Message> ChannelFuture sendMessageResponse(ChannelHandlerContext ctx, HttpRequest req,
            HttpResponseStatus status, T responseMsg) {
        return sendMessageResponse(ctx, req, status, responseMsg, true);
    }

    public static <T extends Message> ChannelFuture sendMessageResponse(ChannelHandlerContext ctx, HttpRequest req,
            HttpResponseStatus status, T responseMsg, boolean autoCloseOnError) {
        ByteBuf body = ctx.alloc().buffer();
        MediaType contentType = getAcceptType(req);

        try {
            if (contentType == MediaType.PROTOBUF) {
                try (ByteBufOutputStream channelOut = new ByteBufOutputStream(body)) {
                    responseMsg.writeTo(channelOut);
                }
            } else if (contentType == MediaType.PLAIN_TEXT) {
                body.writeCharSequence(responseMsg.toString(), StandardCharsets.UTF_8);
            } else { // JSON by default
                contentType = MediaType.JSON;
                String str = JsonFormat.printer().preservingProtoFieldNames().print(responseMsg);
                body.writeCharSequence(str, StandardCharsets.UTF_8);
                body.writeBytes(NEWLINE_BYTES); // For curl comfort
            }
        } catch (IOException e) {
            return sendPlainTextError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.toString());
        }
        HttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status, body);
        HttpUtils.setContentTypeHeader(response, contentType);

        int txSize = body.readableBytes();
        HttpUtil.setContentLength(response, txSize);

        return sendResponse(ctx, req, response, autoCloseOnError);
    }

    public static ChannelFuture sendPlainTextError(ChannelHandlerContext ctx, HttpRequest req,
            HttpResponseStatus status) {
        return sendPlainTextError(ctx, req, status, status.toString());
    }

    public static ChannelFuture sendPlainTextError(ChannelHandlerContext ctx, HttpRequest req,
            HttpResponseStatus status, String msg) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status,
                Unpooled.copiedBuffer(msg + "\r\n", CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        return sendResponse(ctx, req, response, true);
    }

    public static ChannelFuture sendResponse(ChannelHandlerContext ctx, HttpRequest req, HttpResponse response,
            boolean autoCloseOnError) {
        if (response.status() == HttpResponseStatus.OK) {
            log.info("{} {} {} {}", ctx.channel().id().asShortText(), req.method(), req.uri(),
                    response.status().code());
            ChannelFuture writeFuture = ctx.writeAndFlush(response);
            if (!HttpUtil.isKeepAlive(req)) {
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
            return writeFuture;
        } else {
            if (req != null) {
                log.warn("{} {} {} {}", ctx.channel().id().asShortText(), req.method(), req.uri(),
                        response.status().code());
            } else {
                log.warn("{} malformed or illegal request. Sending back {}", ctx.channel().id().asShortText(),
                        response.status().code());
            }
            ChannelFuture writeFuture = ctx.writeAndFlush(response);
            if (autoCloseOnError) {
                writeFuture = writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
            return writeFuture;
        }
    }

    private void cleanPipeline(ChannelPipeline pipeline) {
        while (pipeline.last() != this) {
            pipeline.removeLast();
        }
    }

    /**
     * Returns the Accept header if present and not set to ANY or Content-Type header if present or JSON if none of the
     * headers is present or the Accept is present and set to ANY.
     */
    private static MediaType getAcceptType(HttpRequest req) {
        String acceptType = req.headers().get(HttpHeaderNames.ACCEPT);
        if (acceptType != null) {
            MediaType r = MediaType.from(acceptType);
            if (r == MediaType.ANY) {
                return getContentType(req);
            } else {
                return r;
            }
        } else {
            return getContentType(req);
        }
    }

    /**
     * @return The Content-Type header if present or else defaults to JSON.
     */
    public static MediaType getContentType(HttpRequest req) {
        String declaredContentType = req.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (declaredContentType != null) {
            return MediaType.from(declaredContentType);
        }
        return MediaType.JSON;
    }

    /**
     * Sends base HTTP response indicating the use of chunked transfer encoding NM 11-May-2018: We do not put the
     * ChunckedWriteHandler on the pipeline because the input is already chunked.
     * 
     */
    public static ChannelFuture startChunkedTransfer(ChannelHandlerContext ctx, HttpRequest req, MediaType contentType,
            String filename) {
        log.info("{} {} {} 200 starting chunked transfer", ctx.channel().id().asShortText(), req.method(), req.uri());
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);

        // Set Content-Disposition header so that supporting clients will treat
        // response as a downloadable file
        if (filename != null) {
            response.headers().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        }
        return ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    public static ChannelFuture writeChunk(ChannelHandlerContext ctx, ByteBuf buf) throws IOException {
        Channel ch = ctx.channel();
        if (!ch.isOpen()) {
            throw new ClosedChannelException();
        }
        ChannelFuture writeFuture = ctx.writeAndFlush(new DefaultHttpContent(buf));
        try {
            if (!ch.isWritable()) {
                boolean writeCompleted = writeFuture.await(10, TimeUnit.SECONDS);
                if (!writeCompleted) {
                    throw new IOException("Channel did not become writable in 10 seconds");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return writeFuture;
    }

    public static class ChunkedTransferStats {
        public int totalBytes = 0;
        public int chunkCount = 0;
        HttpMethod originalMethod;
        String originalUri;

        public ChunkedTransferStats(HttpMethod method, String uri) {
            originalMethod = method;
            originalUri = uri;
        }
    }
}
