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

package com.hazelcast.client.alto;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.impl.clientside.HazelcastClientInstanceImpl;
import com.hazelcast.client.impl.connection.ClientConnection;
import com.hazelcast.client.impl.connection.ClientConnectionManager;
import com.hazelcast.client.impl.connection.tcp.AltoChannelClientConnectionAdapter;
import com.hazelcast.client.impl.connection.tcp.TcpClientConnection;
import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.codec.MapPutCodec;
import com.hazelcast.client.impl.spi.impl.ClientInvocation;
import com.hazelcast.client.impl.spi.impl.ClientInvocationFuture;
import com.hazelcast.client.test.ClientTestSupport;
import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MapStoreConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.internal.networking.Channel;
import com.hazelcast.internal.networking.OutboundFrame;
import com.hazelcast.internal.serialization.Data;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.util.ThreadUtil;
import com.hazelcast.map.IMap;
import com.hazelcast.map.MapStoreAdapter;
import com.hazelcast.partition.PartitionService;
import com.hazelcast.spi.exception.TargetDisconnectedException;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.annotation.SlowTest;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.hazelcast.client.properties.ClientProperty.HEARTBEAT_INTERVAL;
import static com.hazelcast.client.properties.ClientProperty.HEARTBEAT_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastSerialClassRunner.class)
@Category({SlowTest.class})
public class ClientAltoTest extends ClientTestSupport {

    @After
    public void cleanup() {
        HazelcastClient.shutdownAll();
        Hazelcast.shutdownAll();
    }

    @Test
    public void testClientConnectsAllAltoPorts() {
        Config config = getMemberConfig();
        Hazelcast.newHazelcastInstance(config);
        Hazelcast.newHazelcastInstance(config);

        HazelcastInstance client = HazelcastClient.newHazelcastClient(getClientConfig());

        Collection<ClientConnection> connections = getConnectionManager(client).getActiveConnections();
        assertTrueEventually(() -> assertEquals(2, connections.size()));

        assertClientConnectsAllAltoPortsEventually(connections, config.getAltoConfig().getEventloopCount());
    }

    @Test
    public void testClientConnectsAllAltoPorts_whenNewMemberJoins() {
        Config config = getMemberConfig();
        Hazelcast.newHazelcastInstance(config);

        HazelcastInstance client = HazelcastClient.newHazelcastClient(getClientConfig());

        Hazelcast.newHazelcastInstance(config);

        Collection<ClientConnection> connections = getConnectionManager(client).getActiveConnections();
        assertTrueEventually(() -> assertEquals(2, connections.size()));

        assertClientConnectsAllAltoPortsEventually(connections, config.getAltoConfig().getEventloopCount());
    }

    @Test
    public void testClientConnectsAllAltoPorts_afterRestart() {
        Config config = getMemberConfig();
        HazelcastInstance instance = Hazelcast.newHazelcastInstance(config);

        HazelcastInstance client = HazelcastClient.newHazelcastClient(getClientConfig());

        CountDownLatch disconnected = new CountDownLatch(1);
        client.getLifecycleService().addLifecycleListener(state -> {
            if (state.getState() == LifecycleEvent.LifecycleState.CLIENT_DISCONNECTED) {
                disconnected.countDown();
            }
        });

        Collection<ClientConnection> connections = getConnectionManager(client).getActiveConnections();
        assertClientConnectsAllAltoPortsEventually(connections, config.getAltoConfig().getEventloopCount());

        instance.shutdown();
        assertOpenEventually(disconnected);
        assertTrueEventually(() -> assertEquals(0, connections.size()));

        Hazelcast.newHazelcastInstance(config);

        assertTrueEventually(() -> assertEquals(1, connections.size()));
        assertClientConnectsAllAltoPortsEventually(connections, config.getAltoConfig().getEventloopCount());
    }

    @Test
    public void testClientRoutesPartitionBoundRequestsToAltoConnections() {
        Config config = getMemberConfig();
        HazelcastInstance instance1 = Hazelcast.newHazelcastInstance(config);
        HazelcastInstance instance2 = Hazelcast.newHazelcastInstance(config);
        warmUpPartitions(instance1, instance2);

        HazelcastInstance client = HazelcastClient.newHazelcastClient(getClientConfig());
        IMap<String, Integer> map = client.getMap(randomMapName());

        ClientConnectionManager connectionManager = getConnectionManager(client);
        Collection<ClientConnection> connections = connectionManager.getActiveConnections();
        assertTrueEventually(() -> assertEquals(2, connections.size()));

        assertClientConnectsAllAltoPortsEventually(connections, config.getAltoConfig().getEventloopCount());

        // Wait until the partition table is received on the client-side
        PartitionService partitionService = client.getPartitionService();
        assertTrueEventually(() -> assertFalse(partitionService.getPartitions().isEmpty()));

        int partitionCount = partitionService.getPartitions().size();
        for (int i = 0; i < partitionCount; i++) {
            String key = generateKeyForPartition(instance1, i);
            long currentTimeMillis = System.currentTimeMillis();
            map.put(key, i);

            UUID ownerUuid = partitionService.getPartition(key).getOwner().getUuid();
            TcpClientConnection partitionOwner = (TcpClientConnection) connectionManager.getConnection(ownerUuid);
            assertNotNull(partitionOwner);

            Channel[] altoChannels = partitionOwner.getAltoChannels();
            assertNotNull(altoChannels);

            Channel altoChannel = altoChannels[i % altoChannels.length];
            assertFalse(altoChannel.isClosed());
            assertTrue(altoChannel.lastWriteTimeMillis() >= currentTimeMillis);
        }
    }

    @Test
    public void testClientRoutesNonPartitionBoundRequestsToClassicConnections() {
        Config config = getMemberConfig();
        Hazelcast.newHazelcastInstance(config);

        HazelcastInstance client = HazelcastClient.newHazelcastClient(getClientConfig());
        IMap<String, Integer> map = client.getMap(randomMapName());

        ClientConnectionManager connectionManager = getConnectionManager(client);
        Collection<ClientConnection> connections = connectionManager.getActiveConnections();

        assertClientConnectsAllAltoPortsEventually(connections, config.getAltoConfig().getEventloopCount());

        long currentTimeMillis = System.currentTimeMillis();
        map.size();
        ClientConnection connection = connectionManager.getRandomConnection();
        assertTrue(connection.lastWriteTimeMillis() >= currentTimeMillis);
    }

    @Test
    public void testConnectionCloses_whenAltoChannelsClose() {
        Config config = getMemberConfig();
        Hazelcast.newHazelcastInstance(config);

        HazelcastInstance client = HazelcastClient.newHazelcastClient(getClientConfig());
        ClientConnectionManager connectionManager = getConnectionManager(client);
        Collection<ClientConnection> connections = connectionManager.getActiveConnections();

        assertClientConnectsAllAltoPortsEventually(connections, config.getAltoConfig().getEventloopCount());

        CountDownLatch disconnected = new CountDownLatch(1);
        CountDownLatch reconnected = new CountDownLatch(1);
        client.getLifecycleService().addLifecycleListener(state -> {
            if (state.getState() == LifecycleEvent.LifecycleState.CLIENT_DISCONNECTED) {
                disconnected.countDown();
            } else if (state.getState() == LifecycleEvent.LifecycleState.CLIENT_CONNECTED) {
                reconnected.countDown();
            }
        });

        assertEquals(1, connections.size());
        TcpClientConnection connection = (TcpClientConnection) connections.iterator().next();
        Channel[] channels = connection.getAltoChannels();

        // Write an unexpected frame to cause problem in the pipeline
        // and close the channel
        channels[0].write(new OutboundFrame() {
            @Override
            public boolean isUrgent() {
                return false;
            }

            @Override
            public int getFrameLength() {
                return 0;
            }
        });

        assertOpenEventually(disconnected);

        assertFalse(connection.isAlive());
        for (Channel channel : channels) {
            // All the channels must be closed as well
            assertTrue(channel.isClosed());
        }

        assertOpenEventually(reconnected);

        assertClientConnectsAllAltoPortsEventually(connections, config.getAltoConfig().getEventloopCount());
    }

    @Test
    public void testAltoChannelsClose_whenConnectionCloses() {
        Config config = getMemberConfig();
        Hazelcast.newHazelcastInstance(config);

        HazelcastInstance client = HazelcastClient.newHazelcastClient(getClientConfig());
        ClientConnectionManager connectionManager = getConnectionManager(client);
        Collection<ClientConnection> connections = connectionManager.getActiveConnections();

        assertClientConnectsAllAltoPortsEventually(connections, config.getAltoConfig().getEventloopCount());

        CountDownLatch disconnected = new CountDownLatch(1);
        CountDownLatch reconnected = new CountDownLatch(1);
        client.getLifecycleService().addLifecycleListener(state -> {
            if (state.getState() == LifecycleEvent.LifecycleState.CLIENT_DISCONNECTED) {
                disconnected.countDown();
            } else if (state.getState() == LifecycleEvent.LifecycleState.CLIENT_CONNECTED) {
                reconnected.countDown();
            }
        });

        assertEquals(1, connections.size());
        TcpClientConnection connection = (TcpClientConnection) connections.iterator().next();
        Channel[] channels = connection.getAltoChannels();

        connection.close("Expected", null);

        assertOpenEventually(disconnected);

        assertFalse(connection.isAlive());
        for (Channel channel : channels) {
            // All the channels must be closed as well
            assertTrue(channel.isClosed());
        }

        assertOpenEventually(reconnected);

        assertClientConnectsAllAltoPortsEventually(connections, config.getAltoConfig().getEventloopCount());
    }

    @Test
    public void testPartitionBoundPendingInvocations_whenConnectionCloses() {
        String mapName = randomMapName();
        Config config = getMemberWithMapStoreConfig(mapName);
        Hazelcast.newHazelcastInstance(config);

        HazelcastInstance client = HazelcastClient.newHazelcastClient(getClientConfig());
        ClientConnectionManager connectionManager = getConnectionManager(client);
        Collection<ClientConnection> connections = connectionManager.getActiveConnections();

        assertClientConnectsAllAltoPortsEventually(connections, config.getAltoConfig().getEventloopCount());

        IMap<Integer, Integer> map = client.getMap(mapName);
        CompletableFuture<Integer> future = map.putAsync(1, 1).toCompletableFuture();
        connections.iterator().next().close("Expected", null);

        // Should get TargetDisconnectedException and retried based on our rules,
        // which bubbles the exception to the user for non-retryable messages
        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(TargetDisconnectedException.class);
    }

    @Test
    public void testAltoChannelTargetedPendingInvocations_whenConnectionCloses() {
        // We don't send invocations to Alto channels this way, but this is just
        // to make sure that invocation directly to the Alto channels work, and
        // closing the connection (hence the channel) cleanups the pending invocations
        String mapName = randomMapName();
        Config config = getMemberWithMapStoreConfig(mapName);
        Hazelcast.newHazelcastInstance(config);

        HazelcastClientInstanceImpl client
                = getHazelcastClientInstanceImpl(HazelcastClient.newHazelcastClient(getClientConfig()));
        ClientConnectionManager connectionManager = client.getConnectionManager();
        Collection<ClientConnection> connections = connectionManager.getActiveConnections();

        assertClientConnectsAllAltoPortsEventually(connections, config.getAltoConfig().getEventloopCount());

        ClientConnection connection = connections.iterator().next();
        Channel[] altoChannels = connection.getAltoChannels();

        int key = 1;
        int value = 1;

        int partitionId = client.getPartitionService().getPartition(key).getPartitionId();
        Channel targetChannel = altoChannels[partitionId % altoChannels.length];
        ClientConnection adapter
                = (ClientConnection) targetChannel.attributeMap().get(AltoChannelClientConnectionAdapter.class);

        InternalSerializationService serializationService = client.getSerializationService();

        Data keyData = serializationService.toData(key);
        Data valueData = serializationService.toData(value);

        ClientMessage request = MapPutCodec.encodeRequest(mapName, keyData, valueData, ThreadUtil.getThreadId(), -1);
        ClientInvocationFuture future = new ClientInvocation(client, request, mapName, adapter).invoke();

        connection.close("Expected", null);

        // Should get TargetDisconnectedException and retried based on our rules,
        // which bubbles the exception to the user for non-retryable messages
        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(TargetDisconnectedException.class);
    }

    @Test
    public void testAltoEnabledClient_inAltoDisabledCluster() {
        Hazelcast.newHazelcastInstance();
        Hazelcast.newHazelcastInstance();

        HazelcastInstance client = HazelcastClient.newHazelcastClient(getClientConfig());
        IMap<String, String> map = client.getMap(randomMapName());

        ClientConnectionManager connectionManager = getConnectionManager(client);
        Collection<ClientConnection> connections = connectionManager.getActiveConnections();
        assertTrueEventually(() -> assertEquals(2, connections.size()));

        assertNoConnectionToAltoPortsAllTheTime(connections);

        map.put("42", "42");
        assertEquals("42", map.get("42"));
    }

    @Test
    public void testAltoDisabledClient_inAltoEnabledCluster() {
        Config config = getMemberConfig();
        Hazelcast.newHazelcastInstance(config);
        Hazelcast.newHazelcastInstance(config);

        HazelcastInstance client = HazelcastClient.newHazelcastClient();
        IMap<String, String> map = client.getMap(randomMapName());

        ClientConnectionManager connectionManager = getConnectionManager(client);
        Collection<ClientConnection> connections = connectionManager.getActiveConnections();
        assertTrueEventually(() -> assertEquals(2, connections.size()));

        assertNoConnectionToAltoPortsAllTheTime(connections);

        map.put("42", "42");
        assertEquals("42", map.get("42"));
    }

    @Test
    public void testAltoClient_heartbeatsToIdleAltoChannels() {
        Config config = getMemberConfig();
        Hazelcast.newHazelcastInstance(config);

        ClientConfig clientConfig = getClientConfig();
        clientConfig.setProperty(HEARTBEAT_INTERVAL.getName(), "1000");

        HazelcastInstance client = HazelcastClient.newHazelcastClient(clientConfig);

        ClientConnectionManager connectionManager = getConnectionManager(client);
        Collection<ClientConnection> connections = connectionManager.getActiveConnections();
        assertClientConnectsAllAltoPortsEventually(connections, config.getAltoConfig().getEventloopCount());

        ClientConnection connection = connectionManager.getRandomConnection();
        assertTrue(connection.isAlive());

        Channel[] altoChannels = connection.getAltoChannels();
        assertNotNull(altoChannels);

        long now = System.currentTimeMillis();
        assertTrueEventually(() -> {
            for (Channel channel : altoChannels) {
                assertTrue(channel.lastWriteTimeMillis() > now);
            }
        });
    }

    @Test
    public void testAltoClient_heartbeatsToNotRespondingAltoChannelsTimeouts() {
        Config config = getMemberConfig();
        Hazelcast.newHazelcastInstance(config);

        ClientConfig clientConfig = getClientConfig();
        clientConfig.setProperty(HEARTBEAT_INTERVAL.getName(), "1000");
        clientConfig.setProperty(HEARTBEAT_TIMEOUT.getName(), "3000");

        HazelcastInstance client = HazelcastClient.newHazelcastClient(clientConfig);
        ClientConnectionManager connectionManager = getConnectionManager(client);
        Collection<ClientConnection> connections = connectionManager.getActiveConnections();

        assertClientConnectsAllAltoPortsEventually(connections, config.getAltoConfig().getEventloopCount());

        ClientConnection connection = connections.iterator().next();
        assertTrue(connection.isAlive());

        // This is a long-running task that will block the Alto thread, and it
        // should not be able to respond to ping requests
        spawn(() -> {
            String mapName = randomMapName();
            IMap<Integer, Integer> map = client.getMap(mapName);
            map.put(1, 1);
            map.executeOnKey(1, entry -> {
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(1_000));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
        });

        assertTrueEventually(() -> assertFalse(connection.isAlive()));
    }

    private void assertNoConnectionToAltoPortsAllTheTime(Collection<ClientConnection> connections) {
        assertTrueAllTheTime(() -> {
            for (ClientConnection connection : connections) {
                TcpClientConnection clientConnection = (TcpClientConnection) connection;
                assertTrue(clientConnection.isAlive());
                assertNull(clientConnection.getAltoChannels());
            }
        }, 3);
    }

    private void assertClientConnectsAllAltoPortsEventually(Collection<ClientConnection> connections, int expectedPortCount) {
        assertTrueEventually(() -> {
            for (ClientConnection connection : connections) {
                TcpClientConnection clientConnection = (TcpClientConnection) connection;

                Channel[] altoChannels = clientConnection.getAltoChannels();
                assertNotNull(altoChannels);
                assertEquals(expectedPortCount, altoChannels.length);

                for (Channel channel : altoChannels) {
                    assertNotNull(channel);
                    assertFalse(channel.isClosed());
                }
            }
        });
    }

    private ClientConnectionManager getConnectionManager(HazelcastInstance client) {
        return getHazelcastClientInstanceImpl(client).getConnectionManager();
    }

    private ClientConfig getClientConfig() {
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.getAltoConfig().setEnabled(true);
        return clientConfig;
    }

    private Config getMemberConfig() {
        Config config = new Config();
        // Jet prints too many logs
        config.getJetConfig().setEnabled(false);

        int loopCount = Math.min(Runtime.getRuntime().availableProcessors(), 3);
        config.getAltoConfig()
                .setEnabled(true)
                .setEventloopCount(loopCount);
        return config;
    }

    private Config getMemberWithMapStoreConfig(String mapName) {
        Config config = getMemberConfig();
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setEnabled(true).setImplementation(new MapStoreAdapter<Integer, Integer>() {
            @Override
            public Integer load(Integer key) {
                // Simulate a long-running operation
                sleepSeconds(1000);
                return super.load(key);
            }
        });
        config.addMapConfig(new MapConfig(mapName).setMapStoreConfig(mapStoreConfig));
        return config;
    }
}