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
package com.github.dtprj.dongting.pb;

import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;

import java.nio.ByteBuffer;

/**
 * @author huangli
 */
public class PbParser {

    private static final DtLog log = DtLogs.getLogger(PbParser.class);

    private static final int STATUS_PARSE_PB_LEN = 1;
    private static final int STATUS_PARSE_TAG = 2;
    private static final int STATUS_PARSE_FILED_LEN = 3;
    private static final int STATUS_PARSE_FILED_BODY = 4;
    private static final int STATUS_SKIP_REST = 5;
    private final int maxFrame;

    private int status = STATUS_PARSE_PB_LEN;

    private int frameLen;
    // not include first 4 bytes of protobuf len
    private int parsedBytes;

    private int pendingBytes;
    private int fieldType;
    private int fieldIndex;
    private int fieldLen;
    private long tempValue;

    public PbParser(int maxFrame) {
        this.maxFrame = maxFrame;
    }

    public void parse(ByteBuffer buf, PbCallback callback) {
        int remain = buf.remaining();
        while (remain > 0) {
            switch (status) {
                case STATUS_PARSE_PB_LEN:
                    remain = onStatusParsePbLen(buf, callback, remain);
                    break;
                case STATUS_PARSE_TAG:
                case STATUS_PARSE_FILED_LEN:
                    remain = parseVarInt(buf, remain);
                    break;
                case STATUS_PARSE_FILED_BODY:
                    remain = onStatusParseFieldBody(buf, callback, remain);
                    break;
                case STATUS_SKIP_REST:
                    int skipCount = Math.min(frameLen - parsedBytes, buf.remaining());
                    assert skipCount >= 0;
                    buf.position(buf.position() + skipCount);
                    if (parsedBytes + skipCount == frameLen) {
                        status = STATUS_PARSE_PB_LEN;
                    }
                    remain -= skipCount;
                    break;
            }
        }
    }

    private int onStatusParsePbLen(ByteBuffer buf, PbCallback callback, int remain) {
        if (pendingBytes == 0) {
            if (remain >= 4) {
                int len = buf.getInt();
                if (len <= 0 || len > maxFrame) {
                    throw new PbException("maxFrameSize exceed: max=" + maxFrame + ", actual=" + len);
                }
                callback.begin(len);
                status = STATUS_PARSE_TAG;
                frameLen = len;
                pendingBytes = 0;
                parsedBytes = 0;
                return remain - 4;
            } else {
                parsedBytes = 0;
                frameLen = 0;
            }
        }
        int restLen = 4 - pendingBytes;
        if (remain >= restLen) {
            for (int i = 0; i < restLen; i++) {
                frameLen = (frameLen << 8) | (0xFF & buf.get());
            }
            if (frameLen <= 0 || frameLen > maxFrame) {
                throw new PbException("maxFrameSize exceed: max=" + maxFrame + ", actual=" + frameLen);
            }
            callback.begin(frameLen);
            pendingBytes = 0;
            status = STATUS_PARSE_TAG;
            return remain - restLen;
        } else {
            for (int i = 0; i < remain; i++) {
                frameLen = (frameLen << 8) | (0xFF & buf.get());
            }
            pendingBytes += remain;
            return 0;
        }
    }

    private int parseVarInt(ByteBuffer buf, int remain) {
        int value = 0;
        int bitIndex = 0;
        int pendingBytes = this.pendingBytes;
        if (pendingBytes > 0) {
            value = (int) this.tempValue;
            bitIndex = pendingBytes * 7;
        }

        // max 5 bytes for 32bit number in proto buffer
        final int MAX_BYTES = 5;
        int i = 1;
        int frameLen = this.frameLen;
        int parsedBytes = this.parsedBytes;
        for (; i <= remain; i++) {
            int x = buf.get();
            value |= (x & 0x7F) << bitIndex;
            if (x >= 0) {
                // first bit is 0, read complete
                if (pendingBytes + i > MAX_BYTES) {
                    throw new PbException("var int too long: " + (pendingBytes + i + 1));
                }
                parsedBytes += i;
                if (parsedBytes > frameLen) {
                    throw new PbException("frame exceed " + frameLen);
                }

                /////////////////////////////////////
                switch (status) {
                    case STATUS_PARSE_TAG:
                        afterTagParsed(value);
                        break;
                    case STATUS_PARSE_FILED_LEN:
                        if (value <= 0) {
                            throw new PbException("bad field len: " + fieldLen);
                        }
                        if (parsedBytes + value > frameLen) {
                            throw new PbException("field length overflow frame length:" + value);
                        }
                        this.fieldLen = value;
                        this.status = STATUS_PARSE_FILED_BODY;
                        break;
                    default:
                        throw new PbException("invalid status: " + status);
                }
                this.parsedBytes = parsedBytes;
                this.pendingBytes = 0;
                /////////////////////////////////////
                return remain - i;
            } else {
                bitIndex += 7;
            }
        }
        pendingBytes += remain;
        parsedBytes += remain;
        if (pendingBytes >= MAX_BYTES) {
            throw new PbException("var int too long, at least " + pendingBytes);
        }
        if (parsedBytes >= frameLen) {
            throw new PbException("frame exceed, at least " + frameLen);
        }
        this.tempValue = value;
        this.pendingBytes = pendingBytes;
        this.parsedBytes = parsedBytes;
        return 0;
    }

    private void afterTagParsed(int value) {
        int type = value & 0x07;
        this.fieldType = type;
        value = value >>> 3;
        if (value == 0) {
            throw new PbException("bad index:" + fieldIndex);
        }
        this.fieldIndex = value;

        switch (type) {
            case PbUtil.TYPE_VAR_INT:
            case PbUtil.TYPE_FIX64:
            case PbUtil.TYPE_FIX32:
                this.status = STATUS_PARSE_FILED_BODY;
                break;
            case PbUtil.TYPE_LENGTH_DELIMITED:
                this.status = STATUS_PARSE_FILED_LEN;
                break;
            default:
                throw new PbException("type not support:" + type);
        }
    }

    private int parseVarLong(ByteBuffer buf, PbCallback callback, int remain) {
        long value = 0;
        int bitIndex = 0;
        int pendingBytes = this.pendingBytes;
        if (pendingBytes > 0) {
            value = this.tempValue;
            bitIndex = pendingBytes * 7;
        }

        // max 10 bytes for 64bit number in proto buffer
        final int MAX_BYTES = 10;
        int i = 1;
        int frameLen = this.frameLen;
        int parsedBytes = this.parsedBytes;
        for (; i <= remain; i++) {
            int x = buf.get();
            value |= (x & 0x7FL) << bitIndex;
            if (x >= 0) {
                // first bit is 0, read complete
                if (pendingBytes + i > MAX_BYTES) {
                    throw new PbException("var long too long: " + (pendingBytes + i + 1));
                }
                parsedBytes += i;
                if (parsedBytes > frameLen) {
                    throw new PbException("frame exceed " + frameLen);
                }

                try {
                    if (callback.readVarInt(this.fieldIndex, value)) {
                        this.status = STATUS_PARSE_TAG;
                    } else {
                        this.status = STATUS_SKIP_REST;
                    }
                } catch (Throwable e) {
                    log.warn("proto buffer parse callback fail: {}", e.toString());
                    this.status = STATUS_SKIP_REST;
                }

                this.pendingBytes = 0;
                this.parsedBytes = parsedBytes;
                return remain - i;
            } else {
                bitIndex += 7;
            }
        }
        pendingBytes += remain;
        parsedBytes += remain;
        if (pendingBytes >= MAX_BYTES) {
            throw new PbException("var long too long, at least " + pendingBytes);
        }
        if (parsedBytes >= frameLen) {
            throw new PbException("frame exceed, at least " + frameLen);
        }
        this.tempValue = value;
        this.pendingBytes = pendingBytes;
        this.parsedBytes = parsedBytes;
        return 0;
    }

    private int onStatusParseFieldBody(ByteBuffer buf, PbCallback callback, int remain) {
        switch (this.fieldType) {
            case PbUtil.TYPE_VAR_INT:
                remain = parseVarLong(buf, callback, remain);
                break;
            case PbUtil.TYPE_FIX64:
                // TODO finish it
                break;
            case PbUtil.TYPE_FIX32:
                // TODO finish it
                break;
            case PbUtil.TYPE_LENGTH_DELIMITED:
                int fieldLen = this.fieldLen;
                int pendingBytes = this.pendingBytes;
                int needRead = fieldLen - pendingBytes;
                int actualRead = Math.min(needRead, remain);
                int start = buf.position();
                int end = start + actualRead;
                int limit = buf.limit();
                buf.limit(end);
                boolean result = false;
                try {
                    result = callback.readBytes(this.fieldIndex, buf, fieldLen,
                            pendingBytes == 0, needRead == actualRead);
                } catch (Throwable e) {
                    log.warn("proto buffer parse callback fail: {}", e.toString());
                } finally {
                    buf.limit(limit);
                    buf.position(end);
                    parsedBytes += actualRead;
                    remain -= actualRead;
                    if (result) {
                        if (needRead == actualRead) {
                            pendingBytes = 0;
                            status = STATUS_PARSE_TAG;
                        } else {
                            pendingBytes += actualRead;
                        }
                    } else {
                        pendingBytes = 0;
                        status = STATUS_SKIP_REST;
                    }
                    this.pendingBytes = pendingBytes;
                }
                break;
            default:
                throw new PbException("type not support:" + this.fieldType);
        }
        if (frameLen == parsedBytes && status == STATUS_PARSE_TAG) {
            callback.end();
            status = STATUS_PARSE_PB_LEN;
        }
        return remain;
    }

}
