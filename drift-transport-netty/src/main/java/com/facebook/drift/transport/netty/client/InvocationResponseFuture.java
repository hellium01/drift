/*
 * Copyright (C) 2013 Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.drift.transport.netty.client;

import com.facebook.drift.TException;
import com.facebook.drift.protocol.TTransportException;
import com.facebook.drift.transport.client.ConnectionFailedException;
import com.facebook.drift.transport.client.InvokeRequest;
import com.facebook.drift.transport.netty.client.ConnectionManager.ConnectionParameters;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.Future;

import javax.annotation.concurrent.GuardedBy;

import java.io.IOException;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.Objects.requireNonNull;

class InvocationResponseFuture
        extends AbstractFuture<Object>
{
    private final InvokeRequest request;
    private final ConnectionParameters connectionParameters;
    private final ConnectionManager connectionManager;

    @GuardedBy("this")
    private Future<Channel> connectionFuture;

    @GuardedBy("this")
    private ThriftClientHandler.ThriftRequest invocationFuture;

    static InvocationResponseFuture createInvocationResponseFuture(InvokeRequest request, ConnectionParameters connectionParameters, ConnectionManager connectionManager)
    {
        InvocationResponseFuture future = new InvocationResponseFuture(request, connectionParameters, connectionManager);
        // invocation can not be started from constructor, because it may start threads that can call back into the unpublished object
        future.tryConnect();
        return future;
    }

    private InvocationResponseFuture(InvokeRequest request, ConnectionParameters connectionParameters, ConnectionManager connectionManager)
    {
        this.request = requireNonNull(request, "request is null");
        this.connectionParameters = requireNonNull(connectionParameters, "connectionConfig is null");
        this.connectionManager = requireNonNull(connectionManager, "connectionManager is null");

        // if this invocation is canceled, cancel the tasks
        super.addListener(() -> {
            if (super.isCancelled()) {
                onCancel(wasInterrupted());
            }
        }, directExecutor());
    }

    private synchronized void tryConnect()
    {
        try {
            connectionFuture = connectionManager.getConnection(connectionParameters, request.getAddress().getHostAndPort());
            connectionFuture.addListener(channelFuture -> {
                try {
                    if (channelFuture.isSuccess()) {
                        // Netty future listener generic type declaration requires a cast when used with a lambda
                        tryInvocation((Channel) channelFuture.getNow());
                    }
                    else {
                        fatalError(new ConnectionFailedException(request.getAddress(), channelFuture.cause()));
                    }
                }
                catch (Throwable t) {
                    fatalError(t);
                }
            });
        }
        catch (Throwable t) {
            fatalError(t);
        }
    }

    private synchronized void tryInvocation(Channel channel)
    {
        // is request already canceled
        if (isCancelled()) {
            connectionManager.returnConnection(channel);
            return;
        }

        try {
            invocationFuture = new ThriftClientHandler.ThriftRequest(request.getMethod(), request.getParameters(), request.getHeaders());
            Futures.addCallback(invocationFuture, new FutureCallback<Object>()
                    {
                        @Override
                        public void onSuccess(Object result)
                        {
                            try {
                                connectionManager.returnConnection(channel);
                                set(result);
                            }
                            catch (Throwable t) {
                                fatalError(t);
                            }
                        }

                        @Override
                        public void onFailure(Throwable t)
                        {
                            try {
                                connectionManager.returnConnection(channel);
                            }
                            finally {
                                fatalError(t);
                            }
                        }
                    },
                    directExecutor());

            ChannelFuture sendFuture = channel.writeAndFlush(invocationFuture);
            sendFuture.addListener(channelFuture -> {
                try {
                    if (!channelFuture.isSuccess()) {
                        fatalError(channelFuture.cause());
                    }
                }
                catch (Throwable t) {
                    fatalError(t);
                }
            });
        }
        catch (Throwable t) {
            try {
                connectionManager.returnConnection(channel);
            }
            finally {
                fatalError(t);
            }
        }
    }

    private synchronized void onCancel(boolean wasInterrupted)
    {
        if (connectionFuture != null) {
            connectionFuture.cancel(wasInterrupted);
        }
        if (invocationFuture != null) {
            invocationFuture.cancel(wasInterrupted);
        }
    }

    private void fatalError(Throwable throwable)
    {
        if (throwable instanceof IOException) {
            throwable = new TTransportException(throwable);
        }
        // exception in the future is expected to be a TException
        if (!(throwable instanceof Error) && !(throwable instanceof TException)) {
            throwable = new TException(throwable);
        }
        setException(throwable);
    }
}
