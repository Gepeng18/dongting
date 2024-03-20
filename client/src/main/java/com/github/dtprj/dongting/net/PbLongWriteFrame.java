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
package com.github.dtprj.dongting.net;

import com.github.dtprj.dongting.codec.PbUtil;

import java.nio.ByteBuffer;

/**
 * @author huangli
 */
public class PbLongWriteFrame extends SmallNoCopyWriteFrame {

    private final long value;

    public PbLongWriteFrame(int command, long value) {
        setCommand(command);
        this.value = value;
    }

    @Override
    protected void encodeBody(ByteBuffer buf) {
        PbUtil.writeFix64(buf, 1, value);
    }

    @Override
    protected int calcActualBodySize() {
        return value == 0 ? 0 : 9;
    }
}
