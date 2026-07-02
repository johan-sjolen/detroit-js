/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package org.openjdk.engine.javascript;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import org.openjdk.engine.javascript.internal.V8AsyncCallSite;

/**
 * Proof-of-concept runtime for running asynchronous JavaScript programs on a
 * bounded number of V8 isolates. Each caller owns a program session. Calls
 * made through Java.async(target).method(...) are executed on that
 * caller's thread and represented in JavaScript by a Promise.
 *
 * This class intentionally has a narrow contract. Source passed to
 * #run(String, Map) should evaluate to either a Promise or an immediate
 * result. Regular Java calls still execute synchronously on its isolate.
 *
 * A V8ProgramRuntime schedules programs to isolates in a round-robin scheme.
 * PLEASE NOTE: Scheduling is ENTIRELY cooperative. If a Javascript program does not reach a yield-point
 * (effectively: awaits a Promise), we will not be able to progress.
 */
public final class V8ProgramRuntime implements AutoCloseable {
    private static final String BRIDGE_BINDING = "__detroitAsyncBridge";

    private final Lane[] lanes;
    private final AtomicInteger nextLane = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a runtime with at most one isolate per available processor.
     */
    public V8ProgramRuntime() {
        this(Runtime.getRuntime().availableProcessors());
    }

    /**
     * Creates a runtime with the requested number of isolates.
     *
     * @param isolateCount number of isolates and isolate runners
     */
    public V8ProgramRuntime(int isolateCount) {
        if (isolateCount < 1) {
            throw new IllegalArgumentException(
                    "has to have at least one isolate");
        }

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        lanes = new Lane[isolateCount];
        int initialized = 0;
        try {
            for (; initialized < isolateCount; initialized++) {
                lanes[initialized] = new Lane(initialized, loader);
            }
        } catch (Throwable failure) {
            for (int index = 0; index < initialized; index++) {
                lanes[index].close();
            }
            throw failure;
        }
    }

    public int isolateCount() {
        return lanes.length;
    }

    /**
     * Runs source without additional bindings.
     */
    public Object run(String source) throws ScriptException, InterruptedException {
        return run(source, Map.of());
    }

    /**
     * Runs source in a fresh V8 Context assigned to one of this runtime's
     * lanes. The calling thread services asynchronous Java invocations for
     * the lifetime of the program.
     *
     * @param source source whose final expression is the program result or Promise
     * @param initialBindings values installed in the program's engine scope
     * @return fulfilled program result
     * @throws ScriptException if evaluation fails or its Promise is rejected
     * @throws InterruptedException if the owning thread is interrupted
     */
    public Object run(String source, Map<String, ?> initialBindings)
            throws ScriptException, InterruptedException {
        Objects.requireNonNull(source);
        Objects.requireNonNull(initialBindings);
        if (closed.get()) {
            throw new IllegalStateException("V8 program runtime is closed");
        }

        int laneIndex = Math.floorMod(nextLane.getAndIncrement(), lanes.length);
        ProgramSession session = new ProgramSession(lanes[laneIndex]);
        // This is the magic: We dispatch the JS program to the Isolate
        // and we run our ProgramSession on a Virtual thread, awaiting async call requests.
        // THis allows the Virtual thread to yield without pinning.
        session.lane.execute(() -> startProgram(session, source, initialBindings));
        return session.runOnOwnerThread();
    }

    private static void startProgram(ProgramSession session, String source,
            Map<String, ?> initialBindings) {
        try {
            Lane lane = session.lane;
            SimpleScriptContext context = new SimpleScriptContext();
            Bindings bindings = lane.engine.createBindings();
            context.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
            bindings.putAll(initialBindings);

            AsyncBridge bridge = new AsyncBridge(session, (JSFactory)bindings);
            bindings.put(BRIDGE_BINDING, bridge);
            session.retain(context, bridge);

            Object result = lane.engine.eval(source, context);
            if (result instanceof JSPromise promise) {
                session.retainPromise(promise);
                promise.then(JSFunction.consumer(session::programCompleted))
                        ._catch(JSFunction.consumer(session::programFailed));
                session.performCheckpoint();
            } else {
                session.programCompleted(result);
            }
        } catch (Throwable failure) {
            session.programFailed(failure);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (Lane lane : lanes) {
                lane.close();
            }
        }
    }

    /**
     * Per-context bridge exposed to the generated Java wrappers. This class is
     * public so the existing Java bridge can discover its invoke method.
     */
    public static final class AsyncBridge {
        private final ProgramSession session;
        private final JSFactory factory;

        private AsyncBridge(ProgramSession session, JSFactory factory) {
            this.session = session;
            this.factory = factory;
        }

        /**
         * Resolves an exact method selected by Detroit's generated JavaScript
         * wrapper. Generated code caches the returned call site.
         */
        public Object resolve(Object receiver, String methodName,
                String descriptor, boolean staticCall) throws NoSuchMethodException {
            Objects.requireNonNull(receiver);
            Objects.requireNonNull(methodName);
            Objects.requireNonNull(descriptor);
            return V8AsyncCallSite.resolve(
                    receiver, methodName, descriptor, staticCall);
        }

        /**
         * Creates a Promise and asks the program-owning thread to invoke a
         * previously resolved Java call site.
         */
        public JSPromise invokeResolved(Object callSite, Object receiver,
                Object[] arguments) {
            Objects.requireNonNull(callSite);
            Objects.requireNonNull(receiver);
            if (callSite instanceof V8AsyncCallSite asyncCallSite) {
                JSResolver resolver = factory.newResolver();
                session.postInvocation(new Invocation(asyncCallSite, receiver,
                    arguments == null ? new Object[0] : arguments, resolver));
                return resolver.getPromise();
            } else {
                throw new IllegalArgumentException("invalid asynchronous Java call site");
            }
        }
    }

    private static final class ProgramSession {
        private final Lane lane;
        private final BlockingQueue<Message> mailbox = new LinkedBlockingQueue<>();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile ScriptContext retainedContext;
        private volatile AsyncBridge retainedBridge;
        private volatile JSPromise retainedPromise;

        private ProgramSession(Lane lane) {
            this.lane = lane;
        }

        private void retain(ScriptContext context, AsyncBridge bridge) {
            retainedContext = context;
            retainedBridge = bridge;
        }

        private void retainPromise(JSPromise promise) {
            retainedPromise = promise;
        }

        private void performCheckpoint() throws ScriptException {
            // V8's automatic checkpoint runs as the eval call depth returns to
            // zero. Using the program's ScriptContext also restores the correct
            // context wrapper while its Promise continuation is executing.
            lane.engine.eval("void 0", retainedContext);
        }

        private void postInvocation(Invocation invocation) {
            if (!terminal.get()) {
                mailbox.offer(invocation);
            }
        }

        private Object runOnOwnerThread() throws ScriptException, InterruptedException {
            try {
                for (;;) {
                    Message message = mailbox.take();
                    if (message instanceof Invocation invocation) {
                        executeInvocation(invocation);
                    } else if (message instanceof ProgramCompleted completed) {
                        return completed.value;
                    } else if (message instanceof ProgramFailed failed) {
                        throw asScriptException(failed.cause);
                    }
                }
            } finally {
                terminal.set(true);
                retainedPromise = null;
                retainedBridge = null;
                retainedContext = null;
            }
        }

        private void executeInvocation(Invocation invocation) {
            Object result = null;
            Throwable failure = null;
            try {
                result = invocation.callSite.invoke(
                        invocation.receiver, invocation.arguments);
            } catch (Throwable exception) {
                failure = exception;
            }

            Object value = result;
            Throwable throwable = failure;
            lane.execute(() -> completeInvocation(invocation.resolver, value, throwable));
        }

        private void completeInvocation(JSResolver resolver, Object value, Throwable failure) {
            if (terminal.get()) {
                return;
            }
            try {
                if (failure == null) {
                    resolver.resolve(value);
                } else {
                    resolver.reject(failure);
                }
                performCheckpoint();
            } catch (Throwable completionFailure) {
                programFailed(completionFailure);
            }
        }

        private void programCompleted(Object value) {
            if (terminal.compareAndSet(false, true)) {
                mailbox.offer(new ProgramCompleted(value));
            }
        }

        private void programFailed(Object reason) {
            Throwable failure = reason instanceof Throwable throwable
                    ? throwable
                    : new RuntimeException(String.valueOf(reason));
            programFailed(failure);
        }

        private void programFailed(Throwable failure) {
            if (terminal.compareAndSet(false, true)) {
                mailbox.offer(new ProgramFailed(failure));
            }
        }
    }

    private interface Message {
    }

    private static final class Invocation implements Message {
        private final V8AsyncCallSite callSite;
        private final Object receiver;
        private final Object[] arguments;
        private final JSResolver resolver;

        private Invocation(V8AsyncCallSite callSite, Object receiver,
                Object[] arguments, JSResolver resolver) {
            this.callSite = callSite;
            this.receiver = receiver;
            this.arguments = arguments;
            this.resolver = resolver;
        }
    }

    private static final class ProgramCompleted implements Message {
        private final Object value;

        private ProgramCompleted(Object value) {
            this.value = value;
        }
    }

    private static final class ProgramFailed implements Message {
        private final Throwable cause;

        private ProgramFailed(Throwable cause) {
            this.cause = cause;
        }
    }

    private static ScriptException asScriptException(Throwable failure) {
        if (failure instanceof ScriptException scriptException) {
            return scriptException;
        }
        ScriptException scriptException = new ScriptException(String.valueOf(failure));
        scriptException.initCause(failure);
        return scriptException;
    }

    /**
     * A Lane is a thread which has a V8 Isolate running inside of it.
     */
    private static final class Lane implements AutoCloseable {
        private final ExecutorService executor;
        private final V8ScriptEngine engine;

        private Lane(int index, ClassLoader loader) {
            executor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "V8 isolate runner-" + index);
                thread.setDaemon(true);
                return thread;
            });
            try {
                engine = new V8ScriptEngineFactory().getScriptEngine(loader);
            } catch (RuntimeException | Error failure) {
                executor.shutdownNow();
                throw failure;
            }
        }

        private void execute(Runnable task) {
            try {
                executor.execute(task);
            } catch (RejectedExecutionException failure) {
                throw new IllegalStateException("V8 isolate runner is closed", failure);
            }
        }

        private <T> T call(java.util.concurrent.Callable<T> task) {
            Future<T> future = executor.submit(task);
            try {
                return future.get();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while starting V8 isolate", failure);
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(cause);
            }
        }

        @Override
        public void close() {
            try {
                call(() -> {
                    engine.close();
                    return null;
                });
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
