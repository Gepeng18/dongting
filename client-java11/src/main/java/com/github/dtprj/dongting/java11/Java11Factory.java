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
package com.github.dtprj.dongting.java11;

import com.github.dtprj.dongting.common.AbstractRefCountUpdater;
import com.github.dtprj.dongting.common.VersionFactory;
import com.github.dtprj.dongting.queue.MpscLinkedQueue;

import java.lang.invoke.VarHandle;

/**
 * @author huangli
 */
@SuppressWarnings({"unused"})
public class Java11Factory extends VersionFactory {

    @Override
    public AbstractRefCountUpdater newRefCountUpdater(boolean plain) {
        return plain ? PlainRefCountUpdater.getInstance() : VarHandleRefCount.getInstance();
    }

    @Override
    public <E> MpscLinkedQueue<E> newMpscLinkedQueue() {
        return new Java11MpscLinkedQueue<>();
    }

    @Override
    public void releaseFence() {
        VarHandle.releaseFence();
    }

    @Override
    public void acquireFence() {
        VarHandle.acquireFence();
    }

    @Override
    public void fullFence() {
        VarHandle.fullFence();
    }

}
