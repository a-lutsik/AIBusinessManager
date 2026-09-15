package com.aibusinessmanager.ai.internal;

import org.springframework.stereotype.Component;

@Component
public class StubLlmProvider implements LlmProvider {

    @Override
    public String provider() {
        return "stub";
    }

    @Override
    public String model() {
        return "stub";
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return "I reviewed the latest metrics and schedule (stub model). "
                + "Confirm a draft below if you want me to change anything. Prompt locale is honored. "
                + "User said: " + userPrompt;
    }
}
