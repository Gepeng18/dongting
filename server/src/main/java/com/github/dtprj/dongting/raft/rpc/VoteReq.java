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
package com.github.dtprj.dongting.raft.rpc;

import com.github.dtprj.dongting.buf.ByteBufferPool;
import com.github.dtprj.dongting.pb.PbCallback;
import com.github.dtprj.dongting.pb.PbUtil;

import java.nio.ByteBuffer;

/**
 * @author huangli
 */
//  uint32 term = 1;
//  uint32 candidate_id = 2;
//  fixed64 last_log_index = 3;
//  uint32 last_log_term = 4;
//  unit32 pre_vote = 5;
public class VoteReq {
    private int term;
    private int candidateId;
    private long lastLogIndex;
    private int lastLogTerm;
    private boolean preVote;

    public static class Callback extends PbCallback {
        private final VoteReq result = new VoteReq();

        @Override
        public boolean readVarNumber(int index, long value) {
            switch (index) {
                case 1:
                    result.term = (int) value;
                    break;
                case 2:
                    result.candidateId = (int) value;
                    break;
                case 4:
                    result.lastLogTerm = (int) value;
                    break;
                case 5:
                    result.preVote = value == 1;
                    break;
            }
            return true;
        }

        @Override
        public boolean readFix64(int index, long value) {
            if (index == 3) {
                result.lastLogIndex = value;
            }
            return true;
        }

        @Override
        public VoteReq getResult() {
            return result;
        }
    }

    public static class WriteFrame extends com.github.dtprj.dongting.net.WriteFrame {

        private final VoteReq data;

        public WriteFrame(VoteReq data) {
            this.data = data;
        }

        @Override
        protected int calcEstimateBodySize() {
            return PbUtil.accurateUnsignedLongSize(1, data.term)
                    + PbUtil.accurateUnsignedIntSize(2, data.candidateId)
                    + PbUtil.accurateFix64Size(3, data.lastLogIndex)
                    + PbUtil.accurateUnsignedIntSize(4, data.lastLogTerm)
                    + PbUtil.accurateUnsignedIntSize(5, data.preVote ? 1 : 0);
        }

        @Override
        protected void encodeBody(ByteBuffer buf, ByteBufferPool pool) {
            super.writeBodySize(buf, estimateBodySize());
            PbUtil.writeUnsignedInt32(buf, 1, data.term);
            PbUtil.writeUnsignedInt32(buf, 2, data.candidateId);
            PbUtil.writeFix64(buf, 3, data.lastLogIndex);
            PbUtil.writeUnsignedInt32(buf, 4, data.lastLogTerm);
            PbUtil.writeUnsignedInt32(buf, 5, data.preVote ? 1 : 0);
        }
    }

    public int getTerm() {
        return term;
    }

    public void setTerm(int term) {
        this.term = term;
    }

    public int getCandidateId() {
        return candidateId;
    }

    public void setCandidateId(int candidateId) {
        this.candidateId = candidateId;
    }

    public long getLastLogIndex() {
        return lastLogIndex;
    }

    public void setLastLogIndex(long lastLogIndex) {
        this.lastLogIndex = lastLogIndex;
    }

    public int getLastLogTerm() {
        return lastLogTerm;
    }

    public void setLastLogTerm(int lastLogTerm) {
        this.lastLogTerm = lastLogTerm;
    }

    public boolean isPreVote() {
        return preVote;
    }

    public void setPreVote(boolean preVote) {
        this.preVote = preVote;
    }
}
