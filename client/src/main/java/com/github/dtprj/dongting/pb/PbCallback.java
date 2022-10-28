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

import java.nio.ByteBuffer;

/**
 * @author huangli
 */
public interface PbCallback {

    boolean readVarInt(int index, long value);

    boolean readFixedInt(int index, int value);

    boolean readFixedLong(int index, long value);

    boolean readBytes(int index, ByteBuffer buf, int len, boolean begin, boolean end);

    default void begin(int len) {
    }

    default void end() {
    }
}
