/*
 * Copyright (C) 2012 Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.airlift.drift.codec.internal.builtin;

import io.airlift.drift.codec.ThriftCodec;
import io.airlift.drift.codec.metadata.ThriftType;
import org.apache.thrift.protocol.TProtocol;

import javax.annotation.concurrent.Immutable;

import static java.util.Objects.requireNonNull;

@Immutable
public class LongThriftCodec
        implements ThriftCodec<Long>
{
    @Override
    public ThriftType getType()
    {
        return ThriftType.I64;
    }

    @Override
    public Long read(TProtocol protocol)
            throws Exception
    {
        requireNonNull(protocol, "protocol is null");
        return protocol.readI64();
    }

    @Override
    public void write(Long value, TProtocol protocol)
            throws Exception
    {
        requireNonNull(value, "value is null");
        requireNonNull(protocol, "protocol is null");
        protocol.writeI64(value);
    }
}
