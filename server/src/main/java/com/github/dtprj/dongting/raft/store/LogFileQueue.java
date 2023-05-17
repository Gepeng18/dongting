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

import com.github.dtprj.dongting.codec.Encoder;
import com.github.dtprj.dongting.common.BitUtil;
import com.github.dtprj.dongting.common.DtUtil;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.raft.client.RaftException;
import com.github.dtprj.dongting.raft.impl.FileUtil;
import com.github.dtprj.dongting.raft.server.ChecksumException;
import com.github.dtprj.dongting.raft.server.LogItem;
import com.github.dtprj.dongting.raft.server.RaftGroupConfigEx;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.zip.CRC32C;

/**
 * @author huangli
 */
class LogFileQueue extends FileQueue implements FileOps {
    private static final DtLog log = DtLogs.getLogger(LogFileQueue.class);

    private static final long LOG_FILE_SIZE = 1024 * 1024 * 1024;
    private static final long FILE_LEN_MASK = LOG_FILE_SIZE - 1;
    private static final int FILE_LEN_SHIFT_BITS = BitUtil.zeroCountOfBinary(LOG_FILE_SIZE);

    private final IdxOps idxOps;

    private final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(128 * 1024);
    private final CRC32C crc32c = new CRC32C();
    private long writePos;

    public LogFileQueue(File dir, ExecutorService ioExecutor, RaftGroupConfigEx groupConfig, IdxOps idxOps) {
        super(dir, ioExecutor, groupConfig);
        this.idxOps = idxOps;
    }

    @Override
    protected long getFileSize() {
        return LOG_FILE_SIZE;
    }

    @Override
    public int getFileLenShiftBits() {
        return FILE_LEN_SHIFT_BITS;
    }

    @Override
    protected long getWritePos() {
        return writePos;
    }

    public int restore(long commitIndex, long commitIndexPos) throws IOException {
        log.info("restore from {}, {}", commitIndex, commitIndexPos);
        Restorer restorer = new Restorer(idxOps, this, commitIndex, commitIndexPos);
        for (int i = 0; i < queue.size(); i++) {
            LogFile lf = queue.get(i);
            writePos = restorer.restoreFile(this.writeBuffer, lf);
        }
        if (queue.size() > 0) {
            if (commitIndexPos >= queue.get(queue.size() - 1).endPos) {
                throw new RaftException("commitIndexPos is illegal. " + commitIndexPos);
            }
            log.info("restore finished. lastTerm={}, lastIndex={}, lastPos={}, lastFile={}",
                    restorer.previousTerm, restorer.previousIndex, writePos, queue.get(queue.size() - 1).file.getPath());
        }
        return restorer.previousTerm;
    }

    static void updateCrc(CRC32C crc32c, ByteBuffer buf, int startPos, int len) {
        int oldPos = buf.position();
        int oldLimit = buf.limit();
        buf.limit(startPos + len);
        buf.position(startPos);
        crc32c.update(buf);
        buf.limit(oldLimit);
        buf.position(oldPos);
    }

    public void append(List<LogItem> logs) throws IOException {
        ensureWritePosReady();
        ByteBuffer writeBuffer = this.writeBuffer;
        writeBuffer.clear();
        long pos = writePos;
        LogFile file = getLogFile(pos);
        for (int i = 0; i < logs.size(); i++) {
            LogItem log = logs.get(i);
            int dataSize = log.getDataSize();
            long posOfFile = (pos + writeBuffer.position()) & FILE_LEN_MASK;
            int totalLen = LogHeader.computeTotalLen(0, 0, dataSize);
            if (posOfFile == 0) {
                if (i != 0) {
                    // last item exactly fill the file
                    pos = writeAndClearBuffer(writeBuffer, file, pos);
                    ensureWritePosReady(pos);
                    file = getLogFile(pos);
                }
                file.firstTimestamp = log.getTimestamp();
                file.firstIndex = log.getIndex();
                file.firstTerm = log.getTerm();
            } else if (LOG_FILE_SIZE - posOfFile < totalLen) {
                // file rest space is not enough
                pos = writeAndClearBuffer(writeBuffer, file, pos);
                // roll to next file
                pos = ((pos >>> FILE_LEN_SHIFT_BITS) + 1) << FILE_LEN_SHIFT_BITS;
                ensureWritePosReady(pos);
                file = getLogFile(pos);
            }

            if (writeBuffer.remaining() < LogHeader.ITEM_HEADER_SIZE) {
                pos = writeAndClearBuffer(writeBuffer, file, pos);
            }

            long itemStartPos = pos;
            LogHeader.writeHeader(crc32c, writeBuffer, log, 0, 0, dataSize);
            pos += LogHeader.ITEM_HEADER_SIZE;

            Encoder encoder;
            if (log.getType() == LogItem.TYPE_NORMAL) {
                encoder = groupConfig.getEncoder().get();
            } else {
                // TODO byte buffer encoder
                encoder = null;
            }
            int lastPos = writeBuffer.position();
            while (!encoder.encode(writeBuffer, log.getData())) {
                if (writeBuffer.position() > lastPos) {
                    updateCrc(crc32c, writeBuffer, lastPos, writeBuffer.position() - lastPos);
                }
                pos = writeAndClearBuffer(writeBuffer, file, pos);
                lastPos = 0;
            }

            if (writeBuffer.position() > lastPos) {
                updateCrc(crc32c, writeBuffer, lastPos, writeBuffer.position() - lastPos);
            }
            if (writeBuffer.remaining() < 4) {
                pos = writeAndClearBuffer(writeBuffer, file, pos);
            }
            writeBuffer.putInt((int) crc32c.getValue());

            idxOps.put(log.getIndex(), itemStartPos, totalLen);
        }
        pos = writeAndClearBuffer(writeBuffer, file, pos);
        file.channel.force(false);
        this.writePos = pos;
    }

    private long writeAndClearBuffer(ByteBuffer buffer, LogFile file, long pos) throws IOException {
        if (buffer.position() == 0) {
            return pos;
        }
        long posOfFile = pos & FILE_LEN_MASK;
        buffer.flip();
        int count = buffer.remaining();
        FileUtil.syncWriteFull(file.channel, buffer, posOfFile);
        buffer.clear();
        return pos + count;
    }

    public void truncateTail(long dataPosition) {
        DtUtil.checkNotNegative(dataPosition, "dataPosition");
        writePos = dataPosition;
    }

    public void checkPos(long pos) {
        if (pos < queueStartPosition) {
            throw new RaftException("pos too small: " + pos);
        }
        if (pos >= writePos) {
            throw new RaftException("pos too large: " + pos);
        }
    }

    static void prepareNextRead(ByteBuffer buf) {
        if (buf.hasRemaining()) {
            ByteBuffer temp = buf.slice();
            buf.clear();
            buf.put(temp);
        } else {
            buf.clear();
        }
    }

    public void markDeleteByIndex(long index, long deleteTimestamp) {
        markDelete(deleteTimestamp, nextFile -> index >= nextFile.firstIndex);
    }

    public void markDeleteByTimestamp(long lastApplied, long timestampMillis, long deleteTimestamp) {
        markDelete(deleteTimestamp, nextFile -> timestampMillis >= nextFile.firstTimestamp
                && lastApplied >= nextFile.firstIndex);
    }

    private void markDelete(long deleteTimestamp, Predicate<LogFile> predicate) {
        int queueSize = queue.size();
        for (int i = 0; i < queueSize - 1; i++) {
            LogFile logFile = queue.get(i);
            LogFile nextFile = queue.get(i + 1);

            if (nextFile.firstTimestamp == 0) {
                return;
            }
            if (predicate.test(nextFile)) {
                if (logFile.deleteTimestamp != 0) {
                    logFile.deleteTimestamp = Math.min(deleteTimestamp, logFile.deleteTimestamp);
                } else {
                    logFile.deleteTimestamp = deleteTimestamp;
                }
            } else {
                return;
            }
        }
    }

    public void submitDeleteTask(long startTimestamp) {
        submitDeleteTask(logFile -> {
            long deleteTimestamp = logFile.deleteTimestamp;
            return deleteTimestamp > 0 && deleteTimestamp < startTimestamp && logFile.use <= 0;
        });
    }

    public long getFirstIndex() {
        if (queue.size() > 0) {
            return queue.get(0).firstIndex;
        }
        return 0;
    }

    public CompletableFuture<Long> nextIndexToReplicate(int remoteMaxTerm, long remoteMaxIndex, long nextIndex,
                                                        Supplier<Boolean> fullIndicator) {
        if (queue.size() == 0) {
            return CompletableFuture.completedFuture(1L);
        }
        LogFile logFile = findLogFileToReplicate(remoteMaxTerm, remoteMaxIndex, nextIndex);
        if (logFile == null) {
            return CompletableFuture.completedFuture(-1L);
        }
        if (compare(logFile.firstTerm, logFile.firstIndex, remoteMaxTerm, remoteMaxIndex) == 0) {
            return CompletableFuture.completedFuture(logFile.firstIndex);
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        ioExecutor.execute(() -> nextIndexToReplicate(fullIndicator, logFile, nextIndex,
                remoteMaxTerm, remoteMaxIndex, future));
        return future;
    }

    // in io thread
    private void nextIndexToReplicate(Supplier<Boolean> fullIndicator, LogFile logFile, long nextIndex,
                                      int remoteMaxTerm, long remoteMaxIndex, CompletableFuture<Long> future) {
        try {
            if (fullIndicator.get()) {
                future.cancel(false);
                return;
            }
            long leftIndex = logFile.firstIndex;
            // findLogFileToReplicate ensures nextIndex>logFile.firstIndex
            long rightIndex = nextIndex - 1;
            LogHeader header = new LogHeader();
            loadHeaderInIoThread(logFile, rightIndex, header);
            if (fullIndicator.get()) {
                future.cancel(false);
                return;
            }
            while (leftIndex < rightIndex) {
                long midIndex = (leftIndex + rightIndex) >>> 1;
                loadHeaderInIoThread(logFile, midIndex, header);
                if (fullIndicator.get()) {
                    future.cancel(false);
                    return;
                }
                int c = compare(header.term, midIndex, remoteMaxTerm, remoteMaxIndex);
                if (c == 0) {
                    future.complete(midIndex);
                    return;
                } else if (c > 0) {
                    rightIndex = midIndex - 1;
                } else {
                    if (rightIndex == leftIndex + 1) {
                        loadHeaderInIoThread(logFile, rightIndex, header);
                        if (fullIndicator.get()) {
                            future.cancel(false);
                            return;
                        }
                        c = compare(header.term, rightIndex, remoteMaxTerm, remoteMaxIndex);
                        if (c > 0) {
                            future.complete(leftIndex);
                        } else {
                            future.complete(rightIndex);
                        }
                        return;
                    } else {
                        leftIndex = midIndex;
                    }
                }
            }
            future.complete(leftIndex);
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
    }

    private void loadHeaderInIoThread(LogFile logFile, long index, LogHeader header) throws Exception {
        CompletableFuture<Long> posFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return idxOps.syncLoadLogPos(index);
            } catch (Throwable e) {
                throw new CompletionException(e);
            }
        }, raftExecutor);
        long pos = posFuture.get();
        ByteBuffer buf = ByteBuffer.allocate(LogHeader.ITEM_HEADER_SIZE);
        FileUtil.syncReadFull(logFile.channel, buf, pos & FILE_LEN_MASK);
        buf.flip();
        header.read(buf);
        if (!header.crcMatch()) {
            throw new ChecksumException();
        }
        if (header.index != index) {
            throw new RaftException("index not match");
        }
    }

    private LogFile findLogFileToReplicate(int remoteMaxTerm, long remoteMaxIndex, long nextIndex) {
        int left = 0;
        int right = queue.size() - 1;
        while (left <= right) {
            int mid = (left + right) >>> 1;
            LogFile logFile = queue.get(mid);
            if (logFile.deleteTimestamp > 0) {
                left = mid + 1;
                continue;
            }
            if (logFile.firstIndex >= nextIndex) {
                right = mid - 1;
                continue;
            }
            int c = compare(logFile.firstTerm, logFile.firstIndex, remoteMaxTerm, remoteMaxIndex);
            if (left == right) {
                return c <= 0 ? logFile : null;
            } else if (c > 0) {
                right = mid - 1;
            } else if (c < 0) {
                if (right == left + 1) {
                    // assert mid == left
                    LogFile nextLogFile = queue.get(right);
                    c = compare(nextLogFile.firstTerm, nextLogFile.firstIndex, remoteMaxTerm, remoteMaxIndex);
                    return c <= 0 ? nextLogFile : logFile;
                } else {
                    left = mid;
                }
            } else {
                return logFile;
            }
        }
        return null;
    }

    private static int compare(int term1, long index1, int term2, long index2) {
        if (term1 < term2) {
            return -1;
        } else if (term1 > term2) {
            return 1;
        } else {
            return Long.compare(index1, index2);
        }
    }

    @Override
    public long filePos(long absolutePos) {
        return (int) (absolutePos & LogFileQueue.FILE_LEN_MASK);
    }

    public long restInCurrentFile(long absolutePos) {
        long totalRest = writePos - absolutePos;
        long fileRest = LOG_FILE_SIZE - filePos(absolutePos);
        return Math.min(totalRest, fileRest);
    }

    @Override
    public long fileLength() {
        return LOG_FILE_SIZE;
    }

}
