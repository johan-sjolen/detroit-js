/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.script.ScriptException;
import org.openjdk.engine.javascript.V8ProgramRuntime;

/**
 * @test
 * @run main/othervm AsyncProgramRuntimeTest
 */
public class AsyncProgramRuntimeTest {
    private static final int PROGRAM_COUNT = 100;

    public static void main(String[] args) throws Exception {
        AsyncProgramRuntimeTest test = new AsyncProgramRuntimeTest();
        test.manyVirtualThreadsShareOneIsolate();
        test.contextCanYieldAndResumeRepeatedly();
        test.javaFailureRejectsProgramPromise();
        // I had to re-implement some of the Java method resolution logic, so
        // I'm hoping it's correct!
        test.generatedDispatchHandlesJavaSignatures();
    }

    public void manyVirtualThreadsShareOneIsolate() throws Exception {
        CountDownLatch scriptsStarted = new CountDownLatch(PROGRAM_COUNT);
        Map<Thread, Throwable> failures = new ConcurrentHashMap<>();
        List<RecordingQueue<String>> queues = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        Object[] results = new Object[PROGRAM_COUNT];

        try (V8ProgramRuntime runtime = new V8ProgramRuntime(1)) {
            assertEquals(runtime.isolateCount(), 1);

            for (int index = 0; index < PROGRAM_COUNT; index++) {
                int programId = index;
                RecordingQueue<String> queue = new RecordingQueue<>();
                queues.add(queue);
                Thread thread = Thread.ofVirtual()
                        .name("js-program-" + programId)
                        .uncaughtExceptionHandler(failures::put)
                        .start(() -> {
                            try {
                                results[programId] = runtime.run("""
                                        (async function() {
                                            const aqueue = Java.async(queue);
                                            scriptsStarted.countDown();
                                            const message = await aqueue.take();
                                            return programId + ":" + message;
                                        })()
                                        """, Map.of(
                                                "scriptsStarted", scriptsStarted,
                                                "queue", queue,
                                                "programId", programId));
                            } catch (Throwable failure) {
                                throw new RuntimeException(failure);
                            }
                        });
                threads.add(thread);
            }

            assertTrue(scriptsStarted.await(15, TimeUnit.SECONDS),
                    "all Contexts should reach their asynchronous Java call");

            for (int index = PROGRAM_COUNT - 1; index >= 0; index--) {
                queues.get(index).put("message-" + index);
            }

            for (int index = 0; index < PROGRAM_COUNT; index++) {
                Thread thread = threads.get(index);
                thread.join(15_000);
                assertFalse(thread.isAlive(), "program did not finish: " + thread.getName());
                Throwable failure = failures.get(thread);
                if (failure != null) {
                    throw new AssertionError("program failed: " + thread.getName(), failure);
                }
                assertEquals(results[index], index + ":message-" + index);
                assertSame(queues.get(index).invocationThread, thread,
                        "blocking Java method should run on the owning virtual thread");
                assertTrue(queues.get(index).invocationThread.isVirtual());
            }
        } finally {
            for (Thread thread : threads) {
                if (thread.isAlive()) {
                    thread.interrupt();
                }
            }
        }
    }

    public void contextCanYieldAndResumeRepeatedly() throws Exception {
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        try (V8ProgramRuntime runtime = new V8ProgramRuntime(1)) {
            Object result = runtime.run("""
                    (async function() {
                        await Java.async(queue).put("first");
                        const first = await Java.async(queue).take();
                        await Java.async(queue).put("second");
                        const second = await Java.async(queue).take();
                        return first + ":" + second;
                    })()
                    """, Map.of("queue", queue));
            assertEquals(result, "first:second");
        }
    }

    public void javaFailureRejectsProgramPromise() throws Exception {
        try (V8ProgramRuntime runtime = new V8ProgramRuntime(1)) {
            try {
                runtime.run("""
                        (async function() {
                            await Java.async(failer).fail();
                        })()
                        """, Map.of("failer", new Failer()));
                throw new AssertionError("program should have failed");
            } catch (ScriptException expected) {
                assertTrue(expected.getCause() instanceof IOException);
                assertEquals(expected.getCause().getMessage(), "expected failure");
            }
        }
    }

    public void generatedDispatchHandlesJavaSignatures() throws Exception {
        DispatchTarget target = new DispatchTarget();
        try (V8ProgramRuntime runtime = new V8ProgramRuntime(1)) {
            Object result = runtime.run("""
                    (async function() {
                        const asyncTarget = Java.async(target);
                        const integer = await asyncTarget.overload(7);
                        const decimal = await asyncTarget.overload(7.5);
                        const primitives = await asyncTarget.primitives(
                                1, 2, 3, 4, 5.5, 6.5, "x", true);
                        const joined = await asyncTarget.join("prefix", "a", "b");
                        const nothing = await asyncTarget.nothing();
                        const staticResult = await Java.async(targetClass).staticCall(9);
                        return [integer, decimal, primitives, joined,
                                nothing === undefined, staticResult].join("|");
                    })()
                    """, Map.of(
                            "target", target,
                            "targetClass", DispatchTarget.class));
            assertEquals(result,
                    "int:7|double:7.5|1,2,3,4,5.5,6.5,x,true|prefix:a:b|true|static:9");
        }
    }

    public static final class RecordingQueue<E> extends LinkedBlockingQueue<E> {
        volatile Thread invocationThread;

        @Override
        public E take() throws InterruptedException {
            invocationThread = Thread.currentThread();
            return super.take();
        }
    }

    public static final class Failer {
        public void fail() throws IOException {
            throw new IOException("expected failure");
        }
    }

    public static final class DispatchTarget {
        public String overload(int value) {
            return "int:" + value;
        }

        public String overload(double value) {
            return "double:" + value;
        }

        public String primitives(byte byteValue, short shortValue, int intValue,
                long longValue, float floatValue, double doubleValue,
                char charValue, boolean booleanValue) {
            return byteValue + "," + shortValue + "," + intValue + "," + longValue
                    + "," + floatValue + "," + doubleValue + "," + charValue
                    + "," + booleanValue;
        }

        public String join(String prefix, String... values) {
            return prefix + ":" + String.join(":", values);
        }

        public void nothing() {
        }

        public static String staticCall(int value) {
            return "static:" + value;
        }
    }

    private static void assertEquals(Object actual, Object expected) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertSame(Object actual, Object expected, String message) {
        if (actual != expected) {
            throw new AssertionError(message + ": expected same object");
        }
    }

    private static void assertTrue(boolean condition) {
        assertTrue(condition, "expected condition to be true");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }
}
