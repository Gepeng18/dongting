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
package com.github.dtprj.dongting.bench;

import java.text.DecimalFormat;
import java.util.concurrent.atomic.LongAdder;

/**
 * @author huangli
 */
public abstract class BenchBase {

    protected final int threadCount;
    private final long testTime;
    private final long warmupTime;
    protected volatile boolean stop = false;
    protected LongAdder successCount = new LongAdder();
    protected LongAdder failCount = new LongAdder();

    private static final boolean LOG_RT = false;
    private long totalNanos;
    private long maxNanos;

    public BenchBase(int threadCount, long testTime) {
        this(threadCount, testTime, 5000);
    }

    public BenchBase(int threadCount, long testTime, long warmupTime) {
        this.threadCount = threadCount;
        this.testTime = testTime;
        this.warmupTime = warmupTime;
    }

    public void init() throws Exception {
    }

    public void shutdown() throws Exception {
    }

    public void start() throws Exception {
        init();
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            int threadIndex = i;
            threads[i] = new Thread(() -> run(threadIndex));
            threads[i].setName("BenchThread" + i);
            threads[i].start();
        }
        Thread.sleep(warmupTime);
        long warmupCount = successCount.sum();
        long warmupFailCount = failCount.sum();
        Thread.sleep(testTime);
        stop = true;
        long sc = successCount.sum() - warmupCount;
        long fc = failCount.sum() - warmupFailCount;
        for (Thread t : threads) {
            t.join();
        }
        shutdown();

        double ops = sc * 1.0 / testTime * 1000;
        System.out.println("success sc:" + sc + ", ops=" + new DecimalFormat(",###").format(ops));

        ops = fc * 1.0 / testTime * 1000;
        System.out.println("fail sc:" + fc + ", ops=" + new DecimalFormat(",###").format(ops));

        if (LOG_RT) {
            System.out.printf("Max time: %,d ns%n", maxNanos);
            System.out.printf("Avg time: %,d ns%n", totalNanos / (sc + fc));
        }

    }

    public void run(int threadIndex) {
        while (!stop) {
            long startTime = 0;
            if (LOG_RT) {
                startTime = System.nanoTime();
            }
            try {
                test(threadIndex, startTime);
            } catch (Throwable e) {
                failCount.increment();
            }
        }
    }

    public abstract void test(int threadIndex, long startTime);

    protected void logRt(long startTime) {
        if (LOG_RT) {
            long x = System.nanoTime() - startTime;
            maxNanos = Math.max(x, maxNanos);
            totalNanos += x;
        }
    }
}
