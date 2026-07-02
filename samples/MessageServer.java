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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.openjdk.engine.javascript.V8ProgramRuntime;

public class MessageServer {
    // Grabbed this from samples/ConsoleExample.java
    public static class Console {
        private final PrintWriter out;
        private final PrintWriter err;

        private Console() {
            this.out = new PrintWriter(System.out, true);
            this.err = new PrintWriter(System.err, true);
        }

        public void log(String message) {
            out.println("[LOG] " + message);
        }

        public void warn(String message) {
            out.println("[WARN] " + message);
        }

        public void error(String message) {
            err.println("[ERROR] " + message);
        }

        public void info(String message) {
            out.println("[INFO] " + message);
        }

        public void log(Object... args) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                builder.append(args[i] != null ? args[i].toString() : "null");
                if (i < args.length - 1) {
                    builder.append(" ");
                }
            }
            log(builder.toString());
        }
    }

    public static final class ClientConnection {
        private final BufferedReader reader;
        private final OutputStream output;
        // Allow us to pretend that there is a delay, so that we're simulating 'real' network conditions
        private final long readDelayMillis;

        private ClientConnection(BufferedReader reader, OutputStream output) {
            this(reader, output, 0);
        }

        private ClientConnection(
                BufferedReader reader, OutputStream output, long readDelayMillis) {
            this.reader = reader;
            this.output = output;
            this.readDelayMillis = readDelayMillis;
        }

        public String readLine() throws IOException {
            if (readDelayMillis > 0) {
                try {
                    Thread.sleep(readDelayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for input", interrupted);
                }
            }
            return reader.readLine();
        }

        public void write(String message) throws IOException {
            output.write(message.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        public void writeLine(String message) throws IOException {
            write(message + "\n");
        }
    }

    private static final String COMMAND_PROCESSOR = """
            function processMessage(message) {
                const parts = message.trim().split(/\\s+/);
                const command = parts[0].toUpperCase();

                switch (command) {
                    case "SAYHELLO":
                        return "Hello!";

                    case "ADD": {
                        if (parts.length !== 3) {
                            return "Usage: ADD <number> <number>";
                        }

                        const first = Number(parts[1]);
                        const second = Number(parts[2]);

                        if (Number.isNaN(first) || Number.isNaN(second)) {
                            return "ERROR: Both arguments must be numbers";
                        }

                        return "RESULT " + (first + second);
                    }

                    case "QUIT":
                        return null;

                    default:
                        return "ERROR: Unknown command";
                }
            }
            """;

    private static final String ASYNC_USER_HANDLER = COMMAND_PROCESSOR + """
            (async function() {
                if (logConnections) {
                    console.log("User " + userId + " started on "
                        + connectionDescription);
                }
                const asyncConnection = Java.async(connection);
                await asyncConnection.writeLine(
                    "Ready. Commands: SAYHELLO, ADD <number> <number>, QUIT");
                while (true) {
                    try {
                        if (interactive) {
                            await asyncConnection.write("> ");
                        }
                        const message = await asyncConnection.readLine();
                        if (message === null) {
                            await asyncConnection.writeLine("Goodbye!");
                            break;
                        }

                        const response = processMessage(message);
                        if (response === null) {
                            const goodbye = "Goodbye!";
                            await asyncConnection.writeLine(goodbye);
                            console.log("User " + userId + " ran command '" + message
                                + "' with response '" + goodbye + "'");
                            break;
                        }

                        await asyncConnection.writeLine(response);
                        console.log("User " + userId + " ran command '" + message
                            + "' with response '" + response + "'");
                    } catch (error) {
                        console.error("Client handler failed: " + error);
                        break;
                    }
                }
            })();
            """;

    private static final String SYNC_USER_HANDLER = COMMAND_PROCESSOR + """
            (function() {
                if (logConnections) {
                    console.log("User " + userId + " started on "
                        + connectionDescription);
                }
                connection.writeLine(
                    "Ready. Commands: SAYHELLO, ADD <number> <number>, QUIT");
                while (true) {
                    try {
                        if (interactive) {
                            connection.write("> ");
                        }
                        const message = connection.readLine();
                        if (message === null) {
                            connection.writeLine("Goodbye!");
                            break;
                        }

                        const response = processMessage(message);
                        if (response === null) {
                            const goodbye = "Goodbye!";
                            connection.writeLine(goodbye);
                            console.log("User " + userId + " ran command '" + message
                                + "' with response '" + goodbye + "'");
                            break;
                        }

                        connection.writeLine(response);
                        console.log("User " + userId + " ran command '" + message
                            + "' with response '" + response + "'");
                    } catch (error) {
                        console.error("Client handler failed: " + error);
                        break;
                    }
                }
            })();
            """;

    private static final long STRESS_READ_DELAY_MILLIS = 5;

    private record Options(
            boolean synchronous, boolean stress, int userCount, int isolateCount) {
    }

    public static void main(String[] args) throws Exception {
        Options options = parseOptions(args);
        String userHandler = options.synchronous()
                ? SYNC_USER_HANDLER : ASYNC_USER_HANDLER;
        String handlerMode = options.synchronous() ? "sync" : "async";

        try (V8ProgramRuntime runtime = new V8ProgramRuntime(options.isolateCount())) {
            if (options.stress()) {
                runStressTest(runtime, userHandler, handlerMode,
                        options.userCount(), options.isolateCount());
                return;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
            System.out.println("Using " + handlerMode + " handler with "
                    + options.isolateCount() + " isolate(s)");
            runtime.run(userHandler, Map.of(
                    "connectionDescription", "interactive console",
                    "interactive", true,
                    "logConnections", true,
                    "userId", 1,
                    "console", new Console(),
                    "connection", new ClientConnection(reader, System.out)));
        }
    }

    private static Options parseOptions(String[] args) {
        boolean synchronous = false;
        boolean stress = false;
        int userCount = 100;
        int isolateCount = Runtime.getRuntime().availableProcessors();

        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            switch (argument) {
                case "sync" -> synchronous = true;
                case "--interactive" -> {
                    // Retained as an optional alias; interactive is the default mode.
                }
                case "--stress" -> {
                    stress = true;
                    if (index + 1 < args.length && isInteger(args[index + 1])) {
                        userCount = positiveInteger("user count", args[++index]);
                    }
                }
                case "--isolatecount" -> {
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException(
                                "--isolatecount requires a positive integer");
                    }
                    isolateCount = positiveInteger(
                            "isolate count", args[++index]);
                }
                default -> throw new IllegalArgumentException(
                        "Unknown argument: " + argument);
            }
        }

        return new Options(synchronous, stress, userCount, isolateCount);
    }

    private static boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException notAnInteger) {
            return false;
        }
    }

    private static int positiveInteger(String name, String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException notAnInteger) {
            throw new IllegalArgumentException(
                    name + " must be a positive integer: " + value,
                    notAnInteger);
        }
        if (parsed < 1) {
            throw new IllegalArgumentException(
                    name + " must be at least 1: " + value);
        }
        return parsed;
    }

    private static void runStressTest(V8ProgramRuntime runtime,
            String userHandler, String handlerMode,
            int userCount, int isolateCount) throws InterruptedException {
        if (userCount < 1) {
            throw new IllegalArgumentException("user count must be at least 1");
        }

        CountDownLatch ready = new CountDownLatch(userCount);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> users = new ArrayList<>(userCount);

        System.out.println("Preparing " + userCount + " concurrent users with the "
                + handlerMode + " handler, " + isolateCount + " isolate(s), and a "
                + STRESS_READ_DELAY_MILLIS + " ms blocking read delay...");
        for (int index = 1; index <= userCount; index++) {
            int userId = index;
            Thread user = Thread.ofVirtual()
                    .name("stress-user-" + userId)
                    .start(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            String commands = commandsForUser(userId);
                            BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(
                                            new ByteArrayInputStream(commands.getBytes(
                                                    StandardCharsets.UTF_8)),
                                            StandardCharsets.UTF_8));
                            runtime.run(userHandler, Map.of(
                                    "connectionDescription", "in-memory stress client",
                                    "interactive", false,
                                    "logConnections", false,
                                    "userId", userId,
                                    "console", new Console(),
                                    "connection", new ClientConnection(
                                            reader, OutputStream.nullOutputStream(),
                                            STRESS_READ_DELAY_MILLIS)));
                        } catch (Throwable failure) {
                            failures.add(failure);
                        }
                    });
            users.add(user);
        }

        ready.await();
        long startedAt = System.nanoTime();
        System.out.println("Starting all users at once...");
        start.countDown();
        for (Thread user : users) {
            user.join();
        }
        long elapsedNanos = System.nanoTime() - startedAt;
        long elapsedMillis = elapsedNanos / 1_000_000;

        if (!failures.isEmpty()) {
            RuntimeException summary = new RuntimeException(
                    failures.size() + " simulated users failed");
            failures.forEach(summary::addSuppressed);
            throw summary;
        }

        long commandCount = userCount * 4L;
        double throughput = commandCount * 1_000_000_000.0 / elapsedNanos;
        System.out.println();
        System.out.println("Stress-test summary");
        System.out.println("  Handler:       " + handlerMode);
        System.out.println("  Isolates:      " + isolateCount);
        System.out.println("  Virtual users: " + userCount);
        System.out.println("  Commands:      " + commandCount);
        System.out.println("  Read delay:    " + STRESS_READ_DELAY_MILLIS + " ms");
        System.out.println("  Failures:      0");
        System.out.println("  Elapsed:       " + elapsedMillis + " ms");
        System.out.printf("  Throughput:    %.0f commands/second%n", throughput);
    }

    // Same set of commands for each stress test user
    private static String commandsForUser(int userId) {
        return "SAYHELLO\n"
                + "ADD " + userId + " " + (userId + 1) + "\n"
                + "ADD nope " + userId + "\n"
                + "QUIT\n";
    }

}
