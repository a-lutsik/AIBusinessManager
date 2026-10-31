package com.cadence.ai.internal.tools;

import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR 0002 §2.3, structural half: {@link ToolResults} is closed, flat and has no field that could carry a person. */
class ToolResultsLeakTest {

    /** Lower-cased fragments no component name of a tool result may contain. */
    private static final List<String> FORBIDDEN = List.of(
            "phone", "enteredname", "telegram", "displayname", "email", "clientname", "specialistname", "mastername",
            "firstname", "lastname", "payload", "uuid");
    private static final Set<Class<?>> ALLOWED_LEAVES = Set.of(String.class, int.class, boolean.class, Double.class);

    private static Set<Class<?>> records() {
        Set<Class<?>> out = new LinkedHashSet<>();
        for (Class<?> nested : ToolResults.class.getDeclaredClasses()) {
            if (nested.isRecord()) {
                out.add(nested);
            }
        }
        return out;
    }

    @Test
    void everyResultIsDeclaredInTheAllowList() {
        Set<Class<?>> declared = records();
        assertTrue(declared.size() >= 10, "scanned " + declared);
        Class<?>[] permitted = ToolResults.Result.class.getPermittedSubclasses();
        assertEquals(5, permitted.length);
        assertTrue(declared.containsAll(Arrays.asList(permitted)));
    }

    @Test
    void noComponentNameCouldCarryAPerson() {
        List<String> found = new ArrayList<>();
        for (Class<?> record : records()) {
            for (RecordComponent component : record.getRecordComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                if (FORBIDDEN.stream().anyMatch(name::contains) || name.equals("id") || name.endsWith("id")) {
                    found.add(record.getSimpleName() + "." + component.getName());
                }
            }
        }
        assertEquals(List.of(), found);
    }

    @Test
    void componentsAreOnlyPlainValuesOrOtherAllowListRecords() {
        Set<Class<?>> declared = records();
        List<String> found = new ArrayList<>();
        for (Class<?> record : declared) {
            for (RecordComponent component : record.getRecordComponents()) {
                if (!allowed(component.getGenericType(), declared)) {
                    found.add(record.getSimpleName() + "." + component.getName() + ": " + component.getGenericType());
                }
            }
        }
        assertEquals(List.of(), found, "a module view or a UUID must never be a component");
    }

    private static boolean allowed(Type type, Set<Class<?>> declared) {
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == List.class) {
            return allowed(parameterized.getActualTypeArguments()[0], declared);
        }
        return type instanceof Class<?> c && (ALLOWED_LEAVES.contains(c) || declared.contains(c));
    }
}
