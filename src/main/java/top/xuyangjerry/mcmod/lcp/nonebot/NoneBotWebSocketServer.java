package top.xuyangjerry.mcmod.lcp.nonebot;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.ReferenceCounted;
import top.xuyangjerry.mcmod.lcp.LightChatPatch;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class NoneBotWebSocketServer {
    /** HTTP 握手请求聚合上限（64KB 足够握手 headers） */
    private static final int HTTP_MAX_CONTENT_LENGTH = 65536;
    /**
     * WebSocket 帧载荷上限：需容纳图片 base64 编码（原图最大约 8MB，
     * base64 膨胀约 33% → ~11MB），设为 16MB 留出协议开销余量。
     */
    private static final int WS_MAX_FRAME_PAYLOAD = 16 * 1024 * 1024;

    private final int port;
    private final String token;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final Set<Channel> connectedClients = ConcurrentHashMap.newKeySet();
    private MessageHandler messageHandler;

    public interface MessageHandler {
        void onMessage(NoneBotMessage message);
        void onClientConnected();
        void onClientDisconnected();
    }

    public NoneBotWebSocketServer(int port, String token) {
        this.port = port;
        this.token = token;
    }

    public void setMessageHandler(MessageHandler handler) {
        this.messageHandler = handler;
    }

    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("http-codec", new HttpServerCodec())
                                .addLast("aggregator", new HttpObjectAggregator(HTTP_MAX_CONTENT_LENGTH))
                                .addLast("handler", new ServerHandler());
                    }
                });

        try {
            ChannelFuture future = bootstrap.bind(port).sync();
            serverChannel = future.channel();
            LightChatPatch.LOGGER.info("[LCP.NoneBot] WebSocket server started on port {}", port);
        } catch (InterruptedException e) {
            LightChatPatch.LOGGER.error("[LCP.NoneBot] Failed to start WebSocket server", e);
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        for (Channel client : connectedClients) {
            if (client.isOpen()) {
                client.close();
            }
        }
        connectedClients.clear();
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        LightChatPatch.LOGGER.info("[LCP.NoneBot] WebSocket server stopped");
    }

    public void broadcast(NoneBotMessage message) {
        if (connectedClients.isEmpty()) return;
        String json = message.toJson();
        for (Channel client : connectedClients) {
            if (client.isOpen()) {
                client.writeAndFlush(new TextWebSocketFrame(json));
            }
        }
    }

    public boolean hasConnectedClients() {
        return !connectedClients.isEmpty();
    }

    private class ServerHandler extends SimpleChannelInboundHandler<Object> {
        private WebSocketServerHandshaker handshaker;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest request) {
                handleHttpRequest(ctx, request);
            } else if (msg instanceof WebSocketFrame frame) {
                handleWebSocketFrame(ctx, frame);
            } else if (msg instanceof ReferenceCounted ref) {
                ref.release();
            }
        }

        private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
            if (!verifyToken(request.uri())) {
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            String host = request.headers().get(HttpHeaderNames.HOST);
            WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                    "ws://" + host + "/", null, false, WS_MAX_FRAME_PAYLOAD);
            handshaker = wsFactory.newHandshaker(request);
            if (handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            } else {
                handshaker.handshake(ctx.channel(), request);
                connectedClients.add(ctx.channel());
                if (messageHandler != null) {
                    messageHandler.onClientConnected();
                }
                LightChatPatch.LOGGER.info("[LCP.NoneBot] Client connected: {}", ctx.channel().remoteAddress());
            }
        }

        private boolean verifyToken(String uri) {
            try {
                URI parsed = new URI(uri);
                String query = parsed.getQuery();
                if (query == null) return false;
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if ("token".equals(kv[0]) && kv.length == 2) {
                        return token.equals(kv[1]);
                    }
                }
            } catch (URISyntaxException e) {
                return false;
            }
            return false;
        }

        private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame textFrame) {
                String json = textFrame.text();
                try {
                    NoneBotMessage message = NoneBotMessage.fromJson(json);
                    if ("ping".equals(message.getType())) {
                        ctx.writeAndFlush(new TextWebSocketFrame(NoneBotMessage.pong().toJson()));
                        return;
                    }
                    if (messageHandler != null) {
                        messageHandler.onMessage(message);
                    }
                } catch (Exception e) {
                    LightChatPatch.LOGGER.warn("[LCP.NoneBot] Failed to parse message: {}", json, e);
                }
            } else if (frame instanceof PingWebSocketFrame pingFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame(pingFrame.content().retain()));
            } else if (frame instanceof CloseWebSocketFrame closeFrame) {
                if (handshaker != null) {
                    handshaker.close(ctx.channel(), closeFrame.retain());
                } else {
                    ctx.close();
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            boolean removed = connectedClients.remove(ctx.channel());
            if (removed && messageHandler != null) {
                messageHandler.onClientDisconnected();
            }
            LightChatPatch.LOGGER.info("[LCP.NoneBot] Client disconnected: {}", ctx.channel().remoteAddress());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LightChatPatch.LOGGER.error("[LCP.NoneBot] WebSocket error", cause);
            ctx.close();
        }
    }
}
