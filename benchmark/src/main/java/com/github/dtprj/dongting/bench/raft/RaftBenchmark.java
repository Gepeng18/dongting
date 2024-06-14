/*
 * Copyright The Dongting Project
 *
 * The Dongting Project licenses this file to you under the Apache License,
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
package com.github.dtprj.dongting.bench.raft;

import com.github.dtprj.dongting.bench.common.BenchBase;
import com.github.dtprj.dongting.bench.common.SimplePerfCallback;
import com.github.dtprj.dongting.common.DtTime;
import com.github.dtprj.dongting.common.DtUtil;
import com.github.dtprj.dongting.common.PerfCallback;
import com.github.dtprj.dongting.dtkv.KvClient;
import com.github.dtprj.dongting.dtkv.server.DtKV;
import com.github.dtprj.dongting.dtkv.server.KvServerUtil;
import com.github.dtprj.dongting.fiber.Dispatcher;
import com.github.dtprj.dongting.fiber.FiberGroup;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.net.HostPort;
import com.github.dtprj.dongting.net.NioClientConfig;
import com.github.dtprj.dongting.raft.RaftNode;
import com.github.dtprj.dongting.raft.server.DefaultRaftFactory;
import com.github.dtprj.dongting.raft.server.RaftGroupConfig;
import com.github.dtprj.dongting.raft.server.RaftGroupConfigEx;
import com.github.dtprj.dongting.raft.server.RaftServer;
import com.github.dtprj.dongting.raft.server.RaftServerConfig;
import com.github.dtprj.dongting.raft.sm.StateMachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author huangli
 */
public class RaftBenchmark extends BenchBase {
    private static final DtLog log = DtLogs.getLogger(RaftBenchmark.class);
    private static final String DATA_DIR = "target/raftlog";
    private static final int GROUP_ID = 0;

    private static final int NODE_COUNT = 3; // change this should delete dongting/target dir
    private static final int CLIENT_COUNT = 1; // also it is client thread count and tcp connections count
    private static final boolean SYNC = false; // client send requests sync or async
    private static final int DATA_LEN = 256;
    // should less than RaftGroupConfig.maxPendingWrites.
    // larger numbers significantly increase throughput and latency.
    private static final int CLIENT_MAX_OUT_REQUESTS = 2000;
    private static final boolean PERF = false; // significant performance impact if change to true
    private static final boolean SYNC_FORCE = true; // not wait for FileChannel.force after write
    // significant performance impact if too large.
    // if we want to test the performance of the raft framework except the state machine, change to 1.
    private static final int KEYS = 100_000;

    private static final byte[] DATA = new byte[DATA_LEN];
    private final List<RaftServer> raftServers = new ArrayList<>();
    private final List<RaftGroupConfig> groupConfigs = new ArrayList<>();
    private final List<DefaultRaftFactory> raftFactories = new ArrayList<>();
    private KvClient[] clients;

    public static void main(String[] args) throws Exception {
        RaftBenchmark benchmark = new RaftBenchmark(CLIENT_COUNT, 5000, 200);
        benchmark.setLogRt(true);
        benchmark.start();
    }

    public RaftBenchmark(int threadCount, long testTime, long warmupTime) {
        super(threadCount, testTime, warmupTime);
    }

    private void createServer(int nodeId, int replicatePort, int servicePort, String servers, String nodeIdOfMembers) {
        RaftServerConfig serverConfig = new RaftServerConfig();
        serverConfig.setServers(servers);
        serverConfig.setNodeId(nodeId);
        serverConfig.setReplicatePort(replicatePort);
        serverConfig.setServicePort(servicePort);

        RaftGroupConfig groupConfig = RaftGroupConfig.newInstance(GROUP_ID, nodeIdOfMembers, "");
        groupConfig.setDataDir(DATA_DIR + "-" + nodeId);
        groupConfig.setSyncForce(SYNC_FORCE);
        groupConfig.setSaveSnapshotMillis(Long.MAX_VALUE);

        if (PERF) {
            groupConfig.setPerfCallback(new RaftPerfCallback(true, "node" + nodeId + "_"));
        }

        DefaultRaftFactory raftFactory = createRaftFactory(nodeId, serverConfig);

        RaftServer raftServer = new RaftServer(serverConfig, Collections.singletonList(groupConfig), raftFactory);
        KvServerUtil.initKvServer(raftServer);
        raftServer.start();

        groupConfigs.add(groupConfig);
        raftServers.add(raftServer);
        raftFactories.add(raftFactory);
    }

    private DefaultRaftFactory createRaftFactory(int nodeId, RaftServerConfig serverConfig) {
        DefaultRaftFactory raftFactory = new DefaultRaftFactory(serverConfig) {
            @Override
            public StateMachine createStateMachine(RaftGroupConfigEx groupConfig) {
                return new DtKV();
            }

            @Override
            public FiberGroup createFiberGroup(RaftGroupConfig groupConfig) {
                // we start multi nodes in same jvm, so use node id as part of dispatcher name
                Dispatcher dispatcher = new Dispatcher("node-" + nodeId, groupConfig.getPerfCallback());
                return new FiberGroup("group-" + GROUP_ID + "-node-" + nodeId, dispatcher);
            }
        };
        raftFactory.start();
        return raftFactory;
    }

    @Override
    public void init() throws Exception {
        new Random().nextBytes(DATA);
        StringBuilder serversStr = new StringBuilder();
        StringBuilder memberIds = new StringBuilder();
        List<RaftNode> serverNodes = new ArrayList<>();
        for (int i = 1; i <= NODE_COUNT; i++) {
            serversStr.append(i).append(",127.0.0.1:").append(4000 + i).append(";");
            memberIds.append(i).append(",");
            serverNodes.add(new RaftNode(i, new HostPort("127.0.0.1", 5000 + i)));
        }
        serversStr.deleteCharAt(serversStr.length() - 1);
        memberIds.deleteCharAt(memberIds.length() - 1);

        for (int i = 1; i <= NODE_COUNT; i++) {
            createServer(i, 4000 + i, 5000 + i, serversStr.toString(), memberIds.toString());
        }

        for (RaftServer s : raftServers) {
            s.getAllGroupReadyFuture().get(60, TimeUnit.SECONDS);
        }
        log.info("raft servers started");

        clients = new KvClient[threadCount];
        for (int i = 0; i < threadCount; i++) {
            NioClientConfig nioClientConfig = new NioClientConfig();
            nioClientConfig.setMaxOutRequests(CLIENT_MAX_OUT_REQUESTS / threadCount);
            KvClient c = new KvClient(nioClientConfig);
            c.start();
            c.getRaftClient().addOrUpdateGroup(GROUP_ID, serverNodes);
            clients[i] = c;
        }

        //noinspection rawtypes
        CompletableFuture[] futures = new CompletableFuture[threadCount];
        for (int i = 0; i < threadCount; i++) {
            // make c find the leader
            futures[i] = clients[i].get(GROUP_ID, "kkk", new DtTime(5, TimeUnit.SECONDS));
        }
        CompletableFuture.allOf(futures).get();
    }

    @Override
    protected void afterWarmup() {
        for (RaftGroupConfig groupConfig : groupConfigs) {
            PerfCallback c = groupConfig.getPerfCallback();
            if (c instanceof SimplePerfCallback) {
                ((SimplePerfCallback) c).start();
            }
        }
    }

    @Override
    public void shutdown() throws Exception {
        //noinspection unchecked
        CompletableFuture<Long>[] saveFutures = new CompletableFuture[NODE_COUNT];
        for (int i = 0; i < raftServers.size(); i++) {
            saveFutures[i] = raftServers.get(i).getRaftGroup(GROUP_ID).fireSaveSnapshot();
        }
        CompletableFuture.allOf(saveFutures).get(10, TimeUnit.SECONDS);
        DtTime timeout = new DtTime(10, TimeUnit.SECONDS);
        DtUtil.stop(timeout, clients);
        DtUtil.stop(timeout, raftServers.toArray(new RaftServer[0]));
        DtUtil.stop(timeout, raftFactories.toArray(new DefaultRaftFactory[0]));

        for (RaftGroupConfig config : groupConfigs) {
            if (config.getPerfCallback() instanceof RaftPerfCallback) {
                System.out.println("----------------------- raft perf stats----------------------");
                ((RaftPerfCallback) config.getPerfCallback()).printStats();
                System.out.println("-------------------------------------------------------------");
            }
        }
    }

    @Override
    public void test(int threadIndex, long startTime, int state) {
        try {
            int k = Integer.reverse((int) startTime);
            k = Math.abs(k % KEYS);
            final DtTime timeout = new DtTime(800, TimeUnit.MILLISECONDS);
            CompletableFuture<Void> f = clients[threadIndex].put(GROUP_ID, String.valueOf(k), DATA, timeout);

            if (SYNC) {
                f.get();
                success(state);
            } else {
                f.whenComplete((result, ex) -> {
                    logRt(startTime, state);
                    if (ex != null) {
                        fail(state);
                    } else {
                        success(state);
                    }
                });
            }
        } catch (Exception e) {
            fail(state);
        } finally {
            if (SYNC) {
                logRt(startTime, state);
            }
        }
    }


}
