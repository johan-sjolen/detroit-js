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

package org.openjdk.engine.javascript.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.openjdk.engine.javascript.V8Context;
import org.openjdk.engine.javascript.V8Undefined;

public final class V8AsyncCallSite {
    private record Key(String name, String descriptor, boolean staticCall) {
    }

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandles.Lookup PUBLIC_LOOKUP =
            MethodHandles.publicLookup();
    private static final MethodType INVOKER_TYPE = MethodType.methodType(
            Object.class, Object.class, Object[].class);


    private static MethodHandle converter(String name, Class<?> returnType) {
        try {
            return LOOKUP.findStatic(V8AsyncCallSite.class, name,
                MethodType.methodType(returnType, Object.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
    private static final MethodHandle TO_BOOLEAN = converter("toBoolean", boolean.class);
    private static final MethodHandle TO_BYTE = converter("toByte", byte.class);
    private static final MethodHandle TO_CHAR = converter("toChar", char.class);
    private static final MethodHandle TO_SHORT = converter("toShort", short.class);
    private static final MethodHandle TO_INT = converter("toInt", int.class);
    private static final MethodHandle TO_LONG = converter("toLong", long.class);
    private static final MethodHandle TO_FLOAT = converter("toFloat", float.class);
    private static final MethodHandle TO_DOUBLE = converter("toDouble", double.class);
    private static MethodHandle primitiveConverter(Class<?> type) {
        if (type == boolean.class) return TO_BOOLEAN;
        if (type == byte.class) return TO_BYTE;
        if (type == char.class) return TO_CHAR;
        if (type == short.class) return TO_SHORT;
        if (type == int.class) return TO_INT;
        if (type == long.class) return TO_LONG;
        if (type == float.class) return TO_FLOAT;
        if (type == double.class) return TO_DOUBLE;
        return null;
    }

    // Cache our callsites
    private static final ClassValue<CallSiteTable> CALL_SITES =
            new ClassValue<>() {
                @Override
                protected CallSiteTable computeValue(Class<?> type) {
                    Map<Key, Method> methods = new HashMap<>();
                    addMethods(methods, type, false);
                    addMethods(methods, type, true);
                    return new CallSiteTable(Map.copyOf(methods));
                }
            };

    private final MethodHandle invoker;

    private V8AsyncCallSite(Method method) throws IllegalAccessException {
        MethodHandle target = PUBLIC_LOOKUP.unreflect(method).asFixedArity();
        int receiverCount = Modifier.isStatic(method.getModifiers()) ? 0 : 1;
        Class<?>[] parameterTypes = method.getParameterTypes();

        for (int index = 0; index < parameterTypes.length; index++) {
            MethodHandle filter = primitiveConverter(parameterTypes[index]);
            if (filter != null) {
                target = MethodHandles.filterArguments(target,
                        receiverCount + index, filter);
            }
        }

        if (method.getReturnType() == void.class) {
            MethodHandle undefined = MethodHandles.constant(
                    Object.class, V8Undefined.INSTANCE);
            target = MethodHandles.filterReturnValue(target, undefined);
        }

        Class<?>[] genericParameters = new Class<?>[
                receiverCount + parameterTypes.length];
        Arrays.fill(genericParameters, Object.class);
        target = target.asType(MethodType.methodType(Object.class, genericParameters));
        target = target.asSpreader(Object[].class, parameterTypes.length);
        if (receiverCount == 0) {
            target = MethodHandles.dropArguments(target, 0, Object.class);
        }
        invoker = target.asType(INVOKER_TYPE);
    }

    public static V8AsyncCallSite resolve(Object receiver, String name,
            String descriptor, boolean staticCall) throws NoSuchMethodException {
        Class<?> type;
        if (staticCall) {
            if (receiver instanceof Class<?> receiverClass) {
                type = receiverClass;
            } else {
                throw new NoSuchMethodException("static receiver is not a Class");
            }
        } else {
            if (receiver == null) {
                throw new NullPointerException("receiver");
            }
            type = receiver.getClass();
        }

        V8AsyncCallSite callSite = CALL_SITES.get(type).resolve(
                new Key(name, descriptor, staticCall));
        if (callSite == null) {
            throw new NoSuchMethodException(type.getName() + "." + name + descriptor);
        }
        return callSite;
    }

    public Object invoke(Object receiver, Object[] arguments) throws Throwable {
        return (Object)invoker.invokeExact(receiver, arguments);
    }

    private static void addMethods(Map<Key, Method> methods,
            Class<?> type, boolean staticCall) {
        for (Method method : new AccessibleMembersLookup(type, !staticCall).getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) != staticCall
                    || isFiltered(method)) {
                continue;
            }
            methods.put(new Key(method.getName(),
                    V8.executableSignature(method), staticCall), method);
        }
    }

    // Basically copied from V8ClassGenerator.java:894
    private static boolean isFiltered(Method method) {
        // Don't allow arbitrary eval!
        if (method.getName().equals("allowCodeGenerationFromStrings")
                && V8Context.class.isAssignableFrom(method.getDeclaringClass())) {
            return true;
        }
        try {
            PUBLIC_LOOKUP.unreflect(method);
            return false;
        } catch (IllegalAccessException ignored) {
            return true;
        }
    }

    private static boolean toBoolean(Object value) {
        return (Boolean)value;
    }

    private static byte toByte(Object value) {
        return ((Number)value).byteValue();
    }

    private static char toChar(Object value) {
        if (value instanceof Character character) {
            return character;
        }
        return ((String)value).charAt(0);
    }

    private static short toShort(Object value) {
        return ((Number)value).shortValue();
    }

    private static int toInt(Object value) {
        return ((Number)value).intValue();
    }

    private static long toLong(Object value) {
        return ((Number)value).longValue();
    }

    private static float toFloat(Object value) {
        return ((Number)value).floatValue();
    }

    private static double toDouble(Object value) {
        return ((Number)value).doubleValue();
    }

    private static final class CallSiteTable {
        private final Map<Key, Method> methods;
        private final ConcurrentHashMap<Key, V8AsyncCallSite> callSites =
                new ConcurrentHashMap<>();

        private CallSiteTable(Map<Key, Method> methods) {
            this.methods = methods;
        }

        private V8AsyncCallSite resolve(Key key) {
            Method method = methods.get(key);
            if (method == null) {
                return null;
            }
            return callSites.computeIfAbsent(key, ignored -> {
                try {
                    return new V8AsyncCallSite(method);
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
    }
}
