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
package com.github.dtprj.dongting.raft.server;

import com.github.dtprj.dongting.common.AbstractLifeCircle;
import com.github.dtprj.dongting.common.ObjUtil;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.net.Commands;
import com.github.dtprj.dongting.net.HostPort;
import com.github.dtprj.dongting.net.NioClient;
import com.github.dtprj.dongting.net.NioClientConfig;
import com.github.dtprj.dongting.net.NioServer;
import com.github.dtprj.dongting.net.NioServerConfig;
import com.github.dtprj.dongting.raft.impl.GroupConManager;
import com.github.dtprj.dongting.raft.impl.RaftStatus;
import com.github.dtprj.dongting.raft.impl.RaftTask;
import com.github.dtprj.dongting.raft.impl.RaftThread;
import com.github.dtprj.dongting.raft.rpc.AppendProcessor;
import com.github.dtprj.dongting.raft.rpc.VoteProcessor;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author huangli
 */
public class RaftServer extends AbstractLifeCircle {
    private static final DtLog log = DtLogs.getLogger(RaftServer.class);
    private final RaftServerConfig config;
    private final NioServer server;
    private final NioClient client;
    private final Set<HostPort> servers;
    private final GroupConManager groupConManager;
    private final RaftThread raftThread;
    private final RaftStatus raftStatus;

    public RaftServer(RaftServerConfig config) {
        this.config = config;
        Objects.requireNonNull(config.getServers());
        ObjUtil.checkPositive(config.getId(), "id");
        ObjUtil.checkPositive(config.getPort(), "port");

        servers = GroupConManager.parseServers(config.getServers());

        int electQuorum = servers.size() / 2 + 1;
        int rwQuorum = servers.size() % 2 == 0 ? servers.size() / 2 : electQuorum;
        raftStatus = new RaftStatus(electQuorum, rwQuorum);

        NioServerConfig nioServerConfig = new NioServerConfig();
        nioServerConfig.setPort(config.getPort());
        nioServerConfig.setName("RaftServer");
        nioServerConfig.setBizThreads(0);
        nioServerConfig.setIoThreads(1);
        server = new NioServer(nioServerConfig);

        NioClientConfig nioClientConfig = new NioClientConfig();
        nioClientConfig.setName("RaftClient");
        client = new NioClient(nioClientConfig);

        groupConManager = new GroupConManager(config.getId(), config.getServers(), client);
        server.register(Commands.RAFT_HANDSHAKE, this.groupConManager.getProcessor());
        LinkedBlockingQueue<RaftTask> queue = new LinkedBlockingQueue<>();
        server.register(Commands.RAFT_APPEND_ENTRIES, new AppendProcessor(queue));
        server.register(Commands.RAFT_REQUEST_VOTE, new VoteProcessor(queue));

        raftThread = new RaftThread(config, queue, raftStatus, client, groupConManager);
    }

    @Override
    protected void doStart() {
        server.start();
        client.start();
        client.waitStart();
        groupConManager.init(raftStatus.getElectQuorum(), servers, 1000);
        raftThread.start();
    }

    @Override
    protected void doStop() {
        server.stop();
        client.stop();
        raftThread.requestShutdown();
    }

}
