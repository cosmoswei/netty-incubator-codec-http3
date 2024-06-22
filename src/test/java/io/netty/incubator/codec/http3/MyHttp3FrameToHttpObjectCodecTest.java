/*
 * Copyright 2021 The Netty Project
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

package io.netty.incubator.codec.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DuplexChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.*;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;

public class MyHttp3FrameToHttpObjectCodecTest {

    @Test
    public void multipleFramesInFin() throws InterruptedException, CertificateException, ExecutionException {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Bootstrap bootstrap = new Bootstrap()
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            // initialized below
                        }
                    })
                    .group(group);

            SelfSignedCertificate cert = new SelfSignedCertificate();

            Channel server = bootstrap.bind("127.0.0.1", 0).sync().channel();
            server.pipeline().addLast(Http3.newQuicServerCodecBuilder()
                    .initialMaxData(10000000)
                    .initialMaxStreamDataBidirectionalLocal(1000000)
                    .initialMaxStreamDataBidirectionalRemote(1000000)
                    .initialMaxStreamsBidirectional(100)
                    .sslContext(QuicSslContextBuilder.forServer(cert.key(), null, cert.cert())
                            .applicationProtocols(Http3.supportedApplicationProtocols()).build())
                    .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ch.pipeline().addLast(new Http3ServerConnectionHandler(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

                                    Channel channel = ctx.channel();
                                    SocketAddress socketAddress = channel.remoteAddress();

                                    QuicStreamAddress quicStreamAddress = new QuicStreamAddress(1);
                                    InetSocketAddress socketAddress1 = (InetSocketAddress) socketAddress;
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
                            }));
                        }
                    })
                    .build());

            Channel client = bootstrap.bind("127.0.0.1", 0).sync().channel();
            client.config().setAutoRead(true);
            client.pipeline().addLast(Http3.newQuicClientCodecBuilder()
                    .initialMaxData(10000000)
                    .initialMaxStreamDataBidirectionalLocal(1000000)
                    .sslContext(QuicSslContextBuilder.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                            .applicationProtocols(Http3.supportedApplicationProtocols())
                            .build())
                    .build());

            QuicChannel quicChannel = QuicChannel.newBootstrap(client)
                    .handler(new ChannelInitializer<QuicChannel>() {
                        @Override
                        protected void initChannel(QuicChannel ch) {
                            ch.pipeline().addLast(new Http3ClientConnectionHandler());
                        }
                    })
                    .remoteAddress(server.localAddress())
                    .localAddress(client.localAddress())
                    .connect().get();

            BlockingQueue<Object> received = new LinkedBlockingQueue<>();
            QuicStreamChannel stream = Http3.newRequestStream(quicChannel, new Http3RequestStreamInitializer() {
                @Override
                protected void initRequestStream(QuicStreamChannel ch) {
                    ch.pipeline()
                            .addLast(new Http3FrameToHttpObjectCodec(false))
                            .addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    System.err.println("ChannelInboundHandlerAdapter.msg = " + msg);
                                    received.put(msg);
                                }
                            });
                }
            }).get();
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
            request.headers().add(HttpHeaderNames.HOST, "localhost");
            stream.writeAndFlush(request);
            HttpResponse respHeaders = (HttpResponse) received.poll(5, TimeUnit.SECONDS);
            assertThat(respHeaders.status(), is(HttpResponseStatus.OK));
            assertThat(respHeaders, not(instanceOf(LastHttpContent.class)));
            HttpContent respBody = (HttpContent) received.poll(5, TimeUnit.SECONDS);
            assertThat(respBody.content().toString(CharsetUtil.UTF_8), is("foo"));
            respBody.release();
            LastHttpContent last = (LastHttpContent) received.poll(5, TimeUnit.SECONDS);
            last.release();
        } finally {
            group.shutdownGracefully();
        }
    }
}
