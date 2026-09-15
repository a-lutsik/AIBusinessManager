package com.aibusinessmanager.ai.internal;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class Pseudonymizer {

    private static final Pattern PHONE = Pattern.compile("\\+?[0-9][0-9\\-\\s()]{7,}");
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);

    private static final int MAX_TOKENS_PER_CONVERSATION = 500;
    private static final int MAX_CONVERSATIONS = 1_000;

    private final Map<UUID, Map<String, String>> reverse = new ConcurrentHashMap<>();

    public String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String masked = PHONE.matcher(text).replaceAll("⟦PHONE⟧");
        masked = EMAIL.matcher(masked).replaceAll("⟦EMAIL⟧");
        return masked;
    }

    public String tokenForName(UUID conversationId, String name) {
        if (conversationId == null) {
            return "⟦NAME⟧";
        }
        if (name == null || name.isBlank()) {
            return "⟦NAME⟧";
        }
        String token = "⟦NAME:" + Integer.toHexString(name.hashCode()) + "⟧";
        Map<String, String> mapping = reverse.get(conversationId);
        if (mapping == null) {
            Map<String, String> fresh = bounded();
            Map<String, String> existing = reverse.putIfAbsent(conversationId, fresh);
            if (existing == null) {
                evictIfNeeded();
                mapping = fresh;
            } else {
                mapping = existing;
            }
        }
        synchronized (mapping) {
            mapping.put(token, name);
        }
        return token;
    }

    public String restore(UUID conversationId, String text) {
        if (text == null) {
            return null;
        }
        Map<String, String> mapping = conversationId == null ? null : reverse.get(conversationId);
        if (mapping == null || mapping.isEmpty()) {
            return text;
        }
        String out = text;
        synchronized (mapping) {
            for (Map.Entry<String, String> e : mapping.entrySet()) {
                out = out.replace(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private void evictIfNeeded() {
        if (reverse.size() <= MAX_CONVERSATIONS) {
            return;
        }
        Iterator<UUID> it = reverse.keySet().iterator();
        if (it.hasNext()) {
            reverse.remove(it.next());
        }
    }

    private static Map<String, String> bounded() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > MAX_TOKENS_PER_CONVERSATION;
            }
        };
    }
}
