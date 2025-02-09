/*
 * Copyright (c) 2008-2023, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.impl.protocol.codec;

import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.Generated;
import com.hazelcast.client.impl.protocol.codec.builtin.*;
import com.hazelcast.client.impl.protocol.codec.custom.*;

import javax.annotation.Nullable;

import static com.hazelcast.client.impl.protocol.ClientMessage.*;
import static com.hazelcast.client.impl.protocol.codec.builtin.FixedSizeTypesCodec.*;

/*
 * This file is auto-generated by the Hazelcast Client Protocol Code Generator.
 * To change this file, edit the templates or the protocol
 * definitions on the https://github.com/hazelcast/hazelcast-client-protocol
 * and regenerate it.
 */

/**
 * Adds a new executor configuration to a running cluster.
 * If an executor configuration with the given {@code name} already exists, then
 * the new configuration is ignored and the existing one is preserved.
 */
@SuppressWarnings("unused")
@Generated("524e1dc0ce1f826807754f708016b13b")
public final class DynamicConfigAddExecutorConfigCodec {
    //hex: 0x1B0800
    public static final int REQUEST_MESSAGE_TYPE = 1771520;
    //hex: 0x1B0801
    public static final int RESPONSE_MESSAGE_TYPE = 1771521;
    private static final int REQUEST_POOL_SIZE_FIELD_OFFSET = PARTITION_ID_FIELD_OFFSET + INT_SIZE_IN_BYTES;
    private static final int REQUEST_QUEUE_CAPACITY_FIELD_OFFSET = REQUEST_POOL_SIZE_FIELD_OFFSET + INT_SIZE_IN_BYTES;
    private static final int REQUEST_STATISTICS_ENABLED_FIELD_OFFSET = REQUEST_QUEUE_CAPACITY_FIELD_OFFSET + INT_SIZE_IN_BYTES;
    private static final int REQUEST_INITIAL_FRAME_SIZE = REQUEST_STATISTICS_ENABLED_FIELD_OFFSET + BOOLEAN_SIZE_IN_BYTES;
    private static final int RESPONSE_INITIAL_FRAME_SIZE = RESPONSE_BACKUP_ACKS_FIELD_OFFSET + BYTE_SIZE_IN_BYTES;

    private DynamicConfigAddExecutorConfigCodec() {
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings({"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD"})
    public static class RequestParameters {

        /**
         * executor's name
         */
        public java.lang.String name;

        /**
         * executor thread pool size
         */
        public int poolSize;

        /**
         * capacity of executor queue. A value of {@code 0} implies {@link Integer#MAX_VALUE}
         */
        public int queueCapacity;

        /**
         * {@code true} to enable gathering of statistics, otherwise {@code false}
         */
        public boolean statisticsEnabled;

        /**
         * name of an existing configured split brain protection to be used to determine the minimum number of members
         * required in the cluster for the lock to remain functional. When {@code null}, split brain protection does not
         * apply to this lock configuration's operations.
         */
        public @Nullable java.lang.String splitBrainProtectionName;

        /**
         * Name of the User Code Namespace applied to this instance.
         */
        public @Nullable java.lang.String userCodeNamespace;

        /**
         * True if the userCodeNamespace is received from the client, false otherwise.
         * If this is false, userCodeNamespace has the default value for its type.
         */
        public boolean isUserCodeNamespaceExists;
    }

    public static ClientMessage encodeRequest(java.lang.String name, int poolSize, int queueCapacity, boolean statisticsEnabled, @Nullable java.lang.String splitBrainProtectionName, @Nullable java.lang.String userCodeNamespace) {
        ClientMessage clientMessage = ClientMessage.createForEncode();
        clientMessage.setRetryable(false);
        clientMessage.setOperationName("DynamicConfig.AddExecutorConfig");
        ClientMessage.Frame initialFrame = new ClientMessage.Frame(new byte[REQUEST_INITIAL_FRAME_SIZE], UNFRAGMENTED_MESSAGE);
        encodeInt(initialFrame.content, TYPE_FIELD_OFFSET, REQUEST_MESSAGE_TYPE);
        encodeInt(initialFrame.content, PARTITION_ID_FIELD_OFFSET, -1);
        encodeInt(initialFrame.content, REQUEST_POOL_SIZE_FIELD_OFFSET, poolSize);
        encodeInt(initialFrame.content, REQUEST_QUEUE_CAPACITY_FIELD_OFFSET, queueCapacity);
        encodeBoolean(initialFrame.content, REQUEST_STATISTICS_ENABLED_FIELD_OFFSET, statisticsEnabled);
        clientMessage.add(initialFrame);
        StringCodec.encode(clientMessage, name);
        CodecUtil.encodeNullable(clientMessage, splitBrainProtectionName, StringCodec::encode);
        CodecUtil.encodeNullable(clientMessage, userCodeNamespace, StringCodec::encode);
        return clientMessage;
    }

    public static DynamicConfigAddExecutorConfigCodec.RequestParameters decodeRequest(ClientMessage clientMessage) {
        ClientMessage.ForwardFrameIterator iterator = clientMessage.frameIterator();
        RequestParameters request = new RequestParameters();
        ClientMessage.Frame initialFrame = iterator.next();
        request.poolSize = decodeInt(initialFrame.content, REQUEST_POOL_SIZE_FIELD_OFFSET);
        request.queueCapacity = decodeInt(initialFrame.content, REQUEST_QUEUE_CAPACITY_FIELD_OFFSET);
        request.statisticsEnabled = decodeBoolean(initialFrame.content, REQUEST_STATISTICS_ENABLED_FIELD_OFFSET);
        request.name = StringCodec.decode(iterator);
        request.splitBrainProtectionName = CodecUtil.decodeNullable(iterator, StringCodec::decode);
        if (iterator.hasNext()) {
            request.userCodeNamespace = CodecUtil.decodeNullable(iterator, StringCodec::decode);
            request.isUserCodeNamespaceExists = true;
        } else {
            request.isUserCodeNamespaceExists = false;
        }
        return request;
    }

    public static ClientMessage encodeResponse() {
        ClientMessage clientMessage = ClientMessage.createForEncode();
        ClientMessage.Frame initialFrame = new ClientMessage.Frame(new byte[RESPONSE_INITIAL_FRAME_SIZE], UNFRAGMENTED_MESSAGE);
        encodeInt(initialFrame.content, TYPE_FIELD_OFFSET, RESPONSE_MESSAGE_TYPE);
        clientMessage.add(initialFrame);

        return clientMessage;
    }
}
