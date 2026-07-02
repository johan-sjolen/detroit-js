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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.SimpleScriptContext;
import org.testng.annotations.Test;

/**
 * @test
 * @run testng/othervm SharedArrayListYieldTest
 */
public class SharedArrayListYieldTest {

    @Test(timeOut = 10_000)
    public void contextsShareJavaArrayList() throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("v8");
        ScriptContext heroContext = newContext(engine);
        ScriptContext villainContext = newContext(engine);
        ArrayList<String> characters = new ArrayList<>();
        Semaphore heroTurn = new Semaphore(1);
        Semaphore villainTurn = new Semaphore(0);

        for (ScriptContext context : List.of(heroContext, villainContext)) {
            context.setAttribute("characters", characters, ScriptContext.ENGINE_SCOPE);
            context.setAttribute("heroTurn", heroTurn, ScriptContext.ENGINE_SCOPE);
            context.setAttribute("villainTurn", villainTurn, ScriptContext.ENGINE_SCOPE);
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> villain = executor.submit(() -> engine.eval("""
                    for (let i = 0; i < 3; i++) {
                        villainTurn.acquire();
                        characters.add("ContextVillain");
                        heroTurn.release();
                    }
                    "villain done";
                    """, villainContext));

            Future<Object> hero = executor.submit(() -> engine.eval("""
                    for (let i = 0; i < 3; i++) {
                        heroTurn.acquire();
                        characters.add("ContextHero");
                        villainTurn.release();
                    }
                    "hero done";
                    """, heroContext));

            assertEquals(hero.get(5, TimeUnit.SECONDS), "hero done");
            assertEquals(villain.get(5, TimeUnit.SECONDS), "villain done");

            // The scripts only mutate the shared Java object. Java alone observes
            // and prints the final result after both Contexts have completed.
            System.out.println("Shared ArrayList from Java: " + characters);
            assertEquals(characters, List.of(
                    "ContextHero", "ContextVillain",
                    "ContextHero", "ContextVillain",
                    "ContextHero", "ContextVillain"));
        } finally {
            // Ensure either script can escape a failed peer before interruption.
            heroTurn.release(3);
            villainTurn.release(3);
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
