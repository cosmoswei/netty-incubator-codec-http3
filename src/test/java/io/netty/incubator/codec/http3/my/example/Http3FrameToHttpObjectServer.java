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
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DuplexChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.http3.*;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;
import java.nio.CharBuffer;
import java.util.concurrent.TimeUnit;

public final class Http3FrameToHttpObjectServer {

    static final int PORT = 8080;

    private Http3FrameToHttpObjectServer() {
    }

    public static void main(String... args) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);

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
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new Http3ServerConnectionHandler(new ChannelInitializer<QuicStreamChannel>() {
                            @Override
                            protected void initChannel(QuicStreamChannel quicStreamChannel) throws Exception {
                                quicStreamChannel.pipeline()
                                        .addLast(new Http3FrameToHttpObjectCodec(true))
                                        .addLast(new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                        if (msg instanceof Http3HeadersFrame) {
                                            DefaultHttp3HeadersFrame responseHeaders = new DefaultHttp3HeadersFrame();
                                            responseHeaders.headers().status(HttpResponseStatus.OK.codeAsText());
                                            ctx.write(responseHeaders, ctx.voidPromise());
                                            ctx.write(new DefaultHttp3DataFrame(ByteBufUtil.encodeString(
                                                            ctx.alloc(), CharBuffer.wrap("foo"), CharsetUtil.UTF_8)),
                                                    ctx.voidPromise());
                                            // send a fin, this also flushes
                                            System.err.println("Http3ServerConnectionHandler.msg = " + msg);
                                            ((DuplexChannel) ctx.channel()).shutdownOutput();
                                        } else {
                                            super.channelRead(ctx, msg);
                                        }
                                    }
                                });
                            }
                        }));
                    }
                })
                .build();

        try {
            Bootstrap bs = new Bootstrap();
            Channel channel = bs.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(codec)
                    .bind(new InetSocketAddress(PORT)).sync().channel();
            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
}
