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

import com.github.dtprj.dongting.raft.client.RaftException;

/**
 * @author huangli
 */
public class NotLeaderException extends RaftException {
    private static final long serialVersionUID = 3514859721941247422L;

    // may be null
    private final RaftNode currentLeader;

    public NotLeaderException(RaftNode currentLeader) {
        this.currentLeader = currentLeader;
    }

    /**
     * get current known leader, may be null.
     */
    public RaftNode getCurrentLeader() {
        return currentLeader;
    }
}
