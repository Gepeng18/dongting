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

import com.github.dtprj.dongting.common.DtException;
import com.github.dtprj.dongting.common.DtUtil;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.raft.RaftException;
import com.github.dtprj.dongting.raft.server.ChecksumException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

/**
 * @author huangli
 */
public class StatusFile implements AutoCloseable {
    private static final DtLog log = DtLogs.getLogger(StatusManager.class);

    private static final int FILE_LENGTH = 4096;
    private static final int CRC_HEX_LENGTH = 8;
    private static final int CONTENT_START_POS = CRC_HEX_LENGTH + 2;
    private static final int CONTENT_LENGTH = FILE_LENGTH - CONTENT_START_POS;

    private final File file;
    private final ExecutorService ioExecutor;
    private FileLock lock;
    private AsynchronousFileChannel channel;
    private final byte[] data = new byte[FILE_LENGTH];
    private final ByteArrayOutputStream bos = new ByteArrayOutputStream(128);

    private final Properties properties = new Properties();

    public StatusFile(File file, ExecutorService ioExecutor) {
        this.file = file;
        this.ioExecutor = ioExecutor;
    }

    public Properties getProperties() {
        return properties;
    }

    public void init() {
        try {
            boolean needLoad = file.exists() && file.length() != 0;
            HashSet<OpenOption> options = new HashSet<>();
            options.add(StandardOpenOption.CREATE);
            options.add(StandardOpenOption.READ);
            options.add(StandardOpenOption.WRITE);
            channel = AsynchronousFileChannel.open(file.toPath(), options, ioExecutor);
            lock = channel.tryLock();
            if (needLoad) {
                log.info("loading status file: {}", file.getPath());
                if (file.length() != FILE_LENGTH) {
                    throw new RaftException("bad status file length: " + file.length());
                }
                ByteBuffer buf = ByteBuffer.wrap(data);
                AsyncIoTask task = new AsyncIoTask(channel, null, null);
                task.read(buf, 0).get(15, TimeUnit.SECONDS);

                CRC32C crc32c = new CRC32C();

                crc32c.update(data, CONTENT_START_POS, CONTENT_LENGTH);
                int expectCrc = (int) crc32c.getValue();

                int actualCrc = Integer.parseUnsignedInt(new String(data, 0, 8, StandardCharsets.UTF_8), 16);

                if (actualCrc != expectCrc) {
                    throw new ChecksumException("bad status file crc: " + actualCrc + ", expect: " + expectCrc);
                }

                properties.load(new StringReader(new String(data, CONTENT_START_POS, CONTENT_LENGTH, StandardCharsets.UTF_8)));

            }
        } catch (DtException e) {
            DtUtil.close(lock, channel);
            throw e;
        } catch (Exception e) {
            DtUtil.close(lock, channel);
            throw new RaftException(e);
        }
    }

    public CompletableFuture<Void> update(Properties props, boolean flush) {
        try {
            bos.reset();
            if (this.properties != props) {
                this.properties.putAll(props);
            }
            this.properties.store(bos, null);
            byte[] propertiesBytes = bos.toByteArray();
            Arrays.fill(data, (byte) ' ');
            System.arraycopy(propertiesBytes, 0, data, CONTENT_START_POS, propertiesBytes.length);
            data[CONTENT_START_POS - 2] = '\r';
            data[CONTENT_START_POS - 1] = '\n';

            CRC32C crc32c = new CRC32C();
            crc32c.update(data, CONTENT_START_POS, CONTENT_LENGTH);
            int crc = (int) crc32c.getValue();
            String crcHex = String.format("%08x", crc);
            byte[] crcBytes = crcHex.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(crcBytes, 0, data, 0, CRC_HEX_LENGTH);
            ByteBuffer buf = ByteBuffer.wrap(data);

            AsyncIoTask task = new AsyncIoTask(channel, null, null);
            return task.write(flush, false, buf, 0).whenComplete((v, ex) -> {
                if (ex != null) {
                    log.error("update status file failed. file={}", file.getPath(), ex);
                } else {
                    log.debug("saving status file success: {}", file.getPath());
                }
            });
        } catch (Exception e) {
            throw new RaftException("update status file failed. file=" + file.getPath(), e);
        }
    }

    @Override
    public void close() {
        DtUtil.close(lock, channel);
    }

}