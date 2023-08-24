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
package com.github.dtprj.dongting.raft.store;

import com.github.dtprj.dongting.common.DtUtil;
import com.github.dtprj.dongting.common.Pair;
import com.github.dtprj.dongting.common.Timestamp;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.raft.RaftException;
import com.github.dtprj.dongting.raft.impl.FileUtil;
import com.github.dtprj.dongting.raft.impl.RaftStatusImpl;
import com.github.dtprj.dongting.raft.impl.RaftUtil;
import com.github.dtprj.dongting.raft.impl.StatusUtil;
import com.github.dtprj.dongting.raft.server.LogItem;
import com.github.dtprj.dongting.raft.server.RaftGroupConfigEx;
import com.github.dtprj.dongting.raft.server.UnrecoverableException;

import java.io.File;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * @author huangli
 */
public class DefaultRaftLog implements RaftLog {

    private final RaftGroupConfigEx groupConfig;
    private final Timestamp ts;
    private final RaftStatusImpl raftStatus;
    private final ExecutorService ioExecutor;
    private LogFileQueue logFiles;
    private IdxFileQueue idxFiles;

    private long lastTaskNanos;
    private static final long TASK_INTERVAL_NANOS = 10 * 1000 * 1000 * 1000L;

    private static final String KEY_TRUNCATE = "truncate";

    public DefaultRaftLog(RaftGroupConfigEx groupConfig, ExecutorService ioExecutor) {
        this.groupConfig = groupConfig;
        this.ts = groupConfig.getTs();
        this.raftStatus = (RaftStatusImpl) groupConfig.getRaftStatus();
        this.ioExecutor = ioExecutor;

        this.lastTaskNanos = ts.getNanoTime();
    }

    @Override
    public Pair<Integer, Long> init(Supplier<Boolean> cancelInit) throws Exception {
        try {
            File dataDir = FileUtil.ensureDir(groupConfig.getDataDir());

            idxFiles = new IdxFileQueue(FileUtil.ensureDir(dataDir, "idx"), ioExecutor, groupConfig);
            logFiles = new LogFileQueue(FileUtil.ensureDir(dataDir, "log"), ioExecutor, groupConfig, idxFiles);
            logFiles.init();
            RaftUtil.checkInitCancel(cancelInit);
            idxFiles.init();
            RaftUtil.checkInitCancel(cancelInit);

            long restoreIndex = idxFiles.getNextIndex() - 1;
            long restoreIndexPos;
            if (restoreIndex > 0) {
                restoreIndexPos = idxFiles.syncLoadLogPos(restoreIndex);
            } else {
                restoreIndexPos = 0;
            }
            RaftUtil.checkInitCancel(cancelInit);

            String truncateStatus = raftStatus.getExtraPersistProps().getProperty(KEY_TRUNCATE);
            if (truncateStatus != null) {
                String[] parts = truncateStatus.split(",");
                if (parts.length == 2) {
                    long start = Long.parseLong(parts[0]);
                    long end = Long.parseLong(parts[1]);
                    logFiles.syncTruncateTail(start, end);
                    raftStatus.getExtraPersistProps().remove(KEY_TRUNCATE);
                    StatusUtil.persistUntilSuccess(raftStatus);
                }
            }
            RaftUtil.checkInitCancel(cancelInit);

            int lastTerm = logFiles.restore(restoreIndex, restoreIndexPos, cancelInit);
            RaftUtil.checkInitCancel(cancelInit);

            if (idxFiles.getNextIndex() == 1) {
                return new Pair<>(0, 0L);
            } else {
                long lastIndex = idxFiles.getNextIndex() - 1;
                return new Pair<>(lastTerm, lastIndex);
            }
        } catch (Throwable e) {
            close();
            throw e;
        }
    }

    @Override
    public void close() {
        DtUtil.close(idxFiles, logFiles);
    }

    @Override
    public void append(List<LogItem> logs) throws Exception {
        if (logs == null || logs.size() == 0) {
            BugLog.getLog().error("append log with empty logs");
            return;
        }
        long firstIndex = logs.get(0).getIndex();
        DtUtil.checkPositive(firstIndex, "firstIndex");
        if (firstIndex == idxFiles.getNextIndex()) {
            logFiles.append(logs);
        } else if (firstIndex < idxFiles.getNextIndex()) {
            if (firstIndex < idxFiles.queueStartPosition || firstIndex < logFiles.queueStartPosition) {
                throw new RaftException("bad index: " + firstIndex);
            }
            truncateTail(firstIndex);
            logFiles.append(logs);
        } else {
            throw new UnrecoverableException("bad index: " + firstIndex);
        }
    }

    private void truncateTail(long firstIndex) throws Exception {
        long dataPosition = idxFiles.truncateTail(firstIndex);
        Properties props = raftStatus.getExtraPersistProps();
        props.setProperty(KEY_TRUNCATE, dataPosition + "," + logFiles.getWritePos());
        StatusUtil.persistUntilSuccess(raftStatus);
        logFiles.syncTruncateTail(dataPosition, logFiles.getWritePos());
        props.remove(KEY_TRUNCATE);
        StatusUtil.persistUntilSuccess(raftStatus);
    }

    @Override
    public LogIterator openIterator(Supplier<Boolean> cancelIndicator) {
        return new DefaultLogIterator(idxFiles, logFiles, groupConfig, cancelIndicator);
    }

    @Override
    public CompletableFuture<Pair<Integer, Long>> tryFindMatchPos(int suggestTerm, long suggestIndex,
                                                                  Supplier<Boolean> cancelIndicator) {
        return logFiles.tryFindMatchPos(suggestTerm, suggestIndex, raftStatus.getLastLogIndex(), cancelIndicator);
    }

    @Override
    public void markTruncateByIndex(long index, long delayMillis) {
        long bound = Math.min(raftStatus.getLastApplied(), idxFiles.getNextPersistIndex() - 1);
        logFiles.markDeleteByIndex(bound, index, delayMillis);
    }

    @Override
    public void markTruncateByTimestamp(long timestampMillis, long delayMillis) {
        long bound = Math.min(raftStatus.getLastApplied(), idxFiles.getNextPersistIndex() - 1);
        logFiles.markDeleteByTimestamp(bound, timestampMillis, delayMillis);
    }

    @Override
    public void doDelete() {
        if (ts.getNanoTime() - lastTaskNanos > TASK_INTERVAL_NANOS) {
            logFiles.submitDeleteTask(ts.getWallClockMillis());
            idxFiles.submitDeleteTask(logFiles.getFirstIndex());
            lastTaskNanos = ts.getNanoTime();
        }
    }

}
