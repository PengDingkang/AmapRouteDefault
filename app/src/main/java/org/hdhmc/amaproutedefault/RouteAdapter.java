package org.hdhmc.amaproutedefault;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Validates the contract independently of obfuscated class, method and field names. */
final class RouteAdapter {
    final Method initialize;
    final Method callback;
    final Constructor<?> resultConstructor;
    final Field routeField;
    private final Constructor<?> requestConstructor;
    private final List<Field> requestFields;
    private final Map<String, Object> modes;
    private final Object automatic;

    private RouteAdapter(Method initialize, Method callback, Constructor<?> resultConstructor,
                         Field routeField, Constructor<?> requestConstructor,
                         List<Field> requestFields, Map<String, Object> modes) {
        this.initialize = initialize;
        this.callback = callback;
        this.resultConstructor = resultConstructor;
        this.routeField = routeField;
        this.requestConstructor = requestConstructor;
        this.requestFields = requestFields;
        this.modes = modes;
        automatic = modes.get("DEFAULT");
    }

    static RouteAdapter validate(Method initialize) throws ReflectiveOperationException {
        int modifiers = initialize.getModifiers();
        Class<?>[] params = initialize.getParameterTypes();
        require(Modifier.isStatic(modifiers) && !Modifier.isNative(modifiers)
                && initialize.getReturnType() == void.class && params.length == 2,
                "Expected static void initializer(request, callback)");
        Class<?> request = params[0];
        require(!request.isInterface() && !Modifier.isAbstract(request.getModifiers())
                && request.getSuperclass() == Object.class, "Unsupported request inheritance");
        require(params[1].isInterface(), "Callback must be an interface");
        List<Method> callbacks = new ArrayList<>();
        for (Method method : params[1].getMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) && !Modifier.isStatic(method.getModifiers()))
                callbacks.add(method);
        }
        require(callbacks.size() == 1, "Ambiguous callback contract");
        Method callback = callbacks.get(0);
        require(callback.getReturnType() == void.class && callback.getParameterCount() == 1,
                "Unexpected callback result");

        List<Field> fields = new ArrayList<>();
        List<Field> enumFields = new ArrayList<>();
        int poiFields = 0;
        for (Field field : request.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            require(!Modifier.isFinal(field.getModifiers()), "Immutable request field");
            field.setAccessible(true);
            fields.add(field);
            if (field.getType().isEnum()) enumFields.add(field);
            if (isPoi(field.getType())) poiFields++;
        }
        require(enumFields.size() == 1 && poiFields >= 2, "Ambiguous route field or missing POIs");
        Field selected = enumFields.get(0);
        Class<?> routeType = selected.getType();
        Map<String, Object> modes = routeModes(routeType);

        Class<?> resultType = callback.getParameterTypes()[0];
        Constructor<?> resultConstructor = resultType.getDeclaredConstructor(routeType);
        int resultRouteFields = 0;
        for (Field field : resultType.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == routeType) resultRouteFields++;
        }
        require(resultRouteFields == 1, "Unexpected result route field");
        Constructor<?> requestConstructor = request.getDeclaredConstructor();
        requestConstructor.setAccessible(true);
        resultConstructor.setAccessible(true);
        initialize.setAccessible(true);
        return new RouteAdapter(initialize, callback, resultConstructor, selected,
                requestConstructor, fields, modes);
    }

    private static boolean isPoi(Class<?> type) {
        try {
            return type.getMethod("getName").getReturnType() == String.class
                    && !type.getMethod("getPoint").getReturnType().isPrimitive();
        } catch (NoSuchMethodException ignored) { return false; }
    }

    private static Map<String, Object> routeModes(Class<?> type) throws ReflectiveOperationException {
        Map<String, Object> modes = new HashMap<>();
        for (Object value : type.getEnumConstants()) modes.put(((Enum<?>) value).name(), value);
        String[] names = {"DEFAULT", "CAR", "BUS", "ONFOOT", "RIDE"};
        int[] values = {-1, 0, 1, 2, 3};
        for (String name : names) require(modes.containsKey(name), "Missing route constant: " + name);
        // Verify numeric semantics too: names alone are insufficient evidence across versions.
        boolean verified = false;
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                    || method.getReturnType() != int.class) continue;
            method.setAccessible(true);
            boolean matches = true;
            for (int i = 0; i < names.length; i++) {
                if (!Integer.valueOf(values[i]).equals(method.invoke(modes.get(names[i])))) matches = false;
            }
            if (matches) verified = true;
        }
        require(verified, "Route constant values changed");
        return modes;
    }

    /** null means no override. Never changes a request owned by the host application. */
    Object replacement(Object input, String mode) throws ReflectiveOperationException {
        Object desired = modes.get(mode);
        if (desired == null || desired == automatic) return null;
        Object explicit = input == null ? null : routeField.get(input);
        if (explicit != null && explicit != automatic) return null;
        Object copy = requestConstructor.newInstance();
        if (input != null) for (Field field : requestFields) field.set(copy, field.get(input));
        routeField.set(copy, desired);
        return copy;
    }

    private static void require(boolean condition, String reason) throws NoSuchMethodException {
        if (!condition) throw new NoSuchMethodException(reason);
    }
}
