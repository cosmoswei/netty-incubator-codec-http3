/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.incubator.codec.http3.my.example;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.http3.*;
import io.netty.incubator.codec.quic.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class Http3ServerExampleV2 {
    private static final byte[] CONTENT = "Hello World!\r\n".getBytes(CharsetUtil.US_ASCII);
    static final int PORT = 9999;

    private Http3ServerExampleV2() {
    }

    public static void main(String... args) throws Exception {
        int port;
        // Allow to pass in the port so we can also use it to run h3spec against
        if (args.length == 1) {
            port = Integer.parseInt(args[0]);
        } else {
            port = PORT;
        }
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        SelfSignedCertificate cert = new SelfSignedCertificate();
        QuicSslContext sslContext = QuicSslContextBuilder.forServer(cert.key(), null, cert.cert())
                .applicationProtocols(Http3.supportedApplicationProtocols()).build();
        ChannelHandler codec = Http3.newQuicServerCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(500000, TimeUnit.MILLISECONDS)
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamDataBidirectionalRemote(1000000)
                .initialMaxStreamsBidirectional(100)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel ch) {
                        // Called for each connection
                        ch.pipeline().addLast(new Http3ServerConnectionHandler(
                                new ChannelInitializer<QuicStreamChannel>() {
                                    // Called for each request-stream,
                                    @Override
                                    protected void initChannel(QuicStreamChannel ch) {
                                        ch.pipeline().addLast(new Http3RequestStreamInboundHandler() {
                                            @Override
                                            protected void channelRead(
                                                    ChannelHandlerContext ctx, Http3HeadersFrame frame) {
                                                System.err.println("this is head " + ctx);
                                                ReferenceCountUtil.release(frame);
                                            }

                                            @Override
                                            protected void channelRead(
                                                    ChannelHandlerContext ctx, Http3DataFrame frame) {
                                                System.err.println("this is body msg = " + frame.content().toString(CharsetUtil.US_ASCII));
                                                DefaultHttp3HeadersFrame responseHeaders = new DefaultHttp3HeadersFrame();
//                                                responseHeaders.headers().status(HttpResponseStatus.OK.codeAsText());
//                                                ctx.write(responseHeaders, ctx.voidPromise());
//                                                ctx.write(new DefaultHttp3DataFrame(ByteBufUtil.encodeString(
//                                                                ctx.alloc(), CharBuffer.wrap("foo"), CharsetUtil.UTF_8)),
//                                                        ctx.voidPromise());
                                                ReferenceCountUtil.release(frame);
                                            }

                                            @Override
                                            protected void channelInputClosed(ChannelHandlerContext ctx) {
                                                Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
                                                headersFrame.headers().status("200");
                                                headersFrame.headers().add("server", "netty");
                                                headersFrame.headers().addInt("content-length", CONTENT.length);
                                                ctx.write(headersFrame);
                                                System.err.println("this is close " + ctx);
                                                ctx.writeAndFlush(new DefaultHttp3DataFrame(
                                                                Unpooled.wrappedBuffer(CONTENT)))
                                                        .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
                                            }
                                        });
                                    }
                                })).addLast(new Http3RequestStreamInboundHandler() {
                            @Override
                            protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) throws Exception {

                            }

                            @Override
                            protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) throws Exception {

                            }

                            @Override
                            protected void channelInputClosed(ChannelHandlerContext ctx) throws Exception {

                            }
                        });
                    }
                }).build();
        try {
            Bootstrap bs = new Bootstrap();
            Channel channel = bs.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(codec)
                    .bind(new InetSocketAddress(port)).sync().channel();
            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    private static void res(ChannelHandlerContext ctx, Http3DataFrame frame) {
        String string = frame.content().toString(CharsetUtil.US_ASCII);
        string = "server's res : " + string;
        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().status("200");
        headersFrame.headers().add("server", "netty");
        headersFrame.headers().addInt("content-length", string.getBytes(StandardCharsets.UTF_8).length);
        ctx.write(headersFrame);
        System.err.println("io.netty.incubator.codec.http3.my.example.Http3ServerExample.res: " + string);
        ctx.writeAndFlush(new DefaultHttp3DataFrame(
                Unpooled.wrappedBuffer(string.getBytes(StandardCharsets.UTF_8))))
        ;
    }
}
