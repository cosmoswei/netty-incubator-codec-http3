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
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.http3.*;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.CharsetUtil;
import io.netty.util.NetUtil;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public final class Http3ClientExampleV2 {
    private Http3ClientExampleV2() {
    }

    public static void main(String... args) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            QuicSslContext context = QuicSslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols(Http3.supportedApplicationProtocols()).build();

            ChannelHandler codec = Http3.newQuicClientCodecBuilder()
                    .sslContext(context)
                    .maxIdleTimeout(500000, TimeUnit.MILLISECONDS)
                    .initialMaxData(10000000)
                    .initialMaxStreamDataBidirectionalLocal(1000000)
                    .build();
            Bootstrap bs = new Bootstrap();
            Channel channel = bs.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(codec)
                    .bind(0).sync().channel();


            QuicChannel quicChannel = QuicChannel.newBootstrap(channel)
                    .handler(new Http3ClientConnectionHandler())
                    .remoteAddress(new InetSocketAddress(NetUtil.LOCALHOST4, Http3ServerExample.PORT))
                    .connect()
                    .get();

            QuicStreamChannel streamChannel = Http3.newRequestStream(quicChannel,
                    new Http3RequestStreamInboundHandler() {
                        @Override
                        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
                            System.err.println("res head = " + frame.type());
                            ReferenceCountUtil.release(frame);
                        }

                        @Override
                        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
                            System.err.println("get res = " + frame.content().toString(CharsetUtil.US_ASCII));
                            ReferenceCountUtil.release(frame);
                        }

                        @Override
                        protected void channelInputClosed(ChannelHandlerContext ctx) {
                            // An exceptionCaught() event was fired, and it reached at the tail of the pipeline. It usually means the last handler in the pipeline did not handle the exception.
                            // 处理Close的时候出错了
                            System.err.println("get closed");
                            ctx.close();
                        }
                    }).sync().getNow();

            Http3HeadersFrame frame = new DefaultHttp3HeadersFrame();
            frame.headers().method("GET").path("/")
                    .authority(NetUtil.LOCALHOST4.getHostAddress() + ":" + Http3ServerExample.PORT)
                    .scheme("https");
            streamChannel.write(frame);
            streamChannel.writeAndFlush(new DefaultHttp3DataFrame(
                            Unpooled.wrappedBuffer("start".getBytes(StandardCharsets.UTF_8))))
                    .addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture channelFuture) throws Exception {
                            channelFuture.sync();
                        }
                    });
            Scanner scanner = new Scanner(System.in);
            while (scanner.hasNextLine()) {
                String msg = scanner.nextLine();
                DefaultHttp3DataFrame defaultHttp3DataFrame = new DefaultHttp3DataFrame(
                        Unpooled.wrappedBuffer(msg.getBytes(StandardCharsets.UTF_8)));
                streamChannel.writeAndFlush(defaultHttp3DataFrame)
                        .addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                                channelFuture.sync();
                            }
                        });

            }
            streamChannel.closeFuture().sync();
            quicChannel.close().sync();
            channel.close().sync();
            // Wait for the stream channel and quic channel to be closed (this will happen after we received the FIN).
            // 等待流通道和快速通道关闭(这将在我们收到FIN后发生)。
            // After this is done we will close the underlying datagram channel.
            // 完成此操作后，我们将关闭底层数据报通道。
//            streamChannel.closeFuture().sync();

            // After we received the response lets also close the underlying QUIC channel and datagram channel.
            // 在我们收到响应之后，还让我们关闭底层QUIC通道和数据报通道。
//            quicChannel.close().sync();
//            channel.close().sync();

        } finally {
            group.shutdownGracefully();
        }
    }
}
