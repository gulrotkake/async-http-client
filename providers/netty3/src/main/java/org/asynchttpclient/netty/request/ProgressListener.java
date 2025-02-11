/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.request;

import java.nio.channels.ClosedChannelException;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.handler.ProgressAsyncHandler;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.future.StackTraceInspector;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProgressListener implements ChannelFutureProgressListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProgressListener.class);

    private final AsyncHttpClientConfig config;
    private final boolean notifyHeaders;
    private final AsyncHandler<?> asyncHandler;
    private final NettyResponseFuture<?> future;

    public ProgressListener(AsyncHttpClientConfig config,//
            AsyncHandler<?> asyncHandler,//
            NettyResponseFuture<?> future,//
            boolean notifyHeaders) {
        this.config = config;
        this.asyncHandler = asyncHandler;
        this.future = future;
        this.notifyHeaders = notifyHeaders;
    }

    private boolean abortOnThrowable(Throwable cause, Channel channel) {
        if (cause != null && future.getState() != NettyResponseFuture.STATE.NEW) {
            // The write operation failed. If the channel was cached, it means it got asynchronously closed.
            // Let's retry a second time.
            if (cause instanceof IllegalStateException || cause instanceof ClosedChannelException || StackTraceInspector.recoverOnReadOrWriteException(cause)) {
                LOGGER.debug(cause == null ? "" : cause.getMessage(), cause);
                Channels.silentlyCloseChannel(channel);

            } else {
                future.abort(cause);
            }
            return true;
        }
        return false;
    }
    
    public void operationComplete(ChannelFuture cf) {

        if (!abortOnThrowable(cf.getCause(), cf.getChannel())) {
            future.touch();

            /**
             * We need to make sure we aren't in the middle of an authorization process before publishing events as we will re-publish again the same event after the authorization,
             * causing unpredictable behavior.
             */
            Realm realm = future.getRequest().getRealm() != null ? future.getRequest().getRealm() : config.getRealm();
            boolean startPublishing = future.isInAuth() || realm == null || realm.getUsePreemptiveAuth();

            if (startPublishing && asyncHandler instanceof ProgressAsyncHandler) {
                if (notifyHeaders) {
                    ProgressAsyncHandler.class.cast(asyncHandler).onHeadersWritten();
                } else {
                    ProgressAsyncHandler.class.cast(asyncHandler).onContentWriten();
                }
            }
        }
    }

    public void operationProgressed(ChannelFuture cf, long amount, long current, long total) {
        future.touch();
        if (asyncHandler instanceof ProgressAsyncHandler) {
            ProgressAsyncHandler.class.cast(asyncHandler).onContentWriteProgress(amount, current, total);
        }
    }
}