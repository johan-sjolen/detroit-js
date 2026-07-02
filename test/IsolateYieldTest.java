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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.SimpleScriptContext;
import org.testng.annotations.Test;

/**
 * @test
 * @run testng/othervm IsolateYieldTest
 */
public class IsolateYieldTest {

    @Test(timeOut = 10_000)
    public void javaCallYieldsIsolateToAnotherContext() throws Exception {
        System.out.println("Running the test!");
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("v8");
        ScriptContext blockedContext = newContext(engine);
        ScriptContext runnableContext = newContext(engine);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockedContext.setAttribute("started", started, ScriptContext.ENGINE_SCOPE);
        blockedContext.setAttribute("release", release, ScriptContext.ENGINE_SCOPE);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> blocked = executor.submit(() ->
                    engine.eval("started.countDown(); release.await(); 41", blockedContext));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            Future<Object> interleaved = executor.submit(() ->
                    engine.eval("const contextLocal = 21; contextLocal * 2", runnableContext));
            assertEquals(interleaved.get(5, TimeUnit.SECONDS), 42);

            release.countDown();
            assertEquals(blocked.get(5, TimeUnit.SECONDS), 41);
        } finally {
            release.countDown();
            executor.shutdownNow();
            if (engine instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private static ScriptContext newContext(ScriptEngine engine) {
        SimpleScriptContext context = new SimpleScriptContext();
        context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        return context;
    }
}
