package com.cadence.notification.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramPropertiesTest {

    private static final String TOKEN = "123456789:STUB_token_for_the_stub_server_only";

    private static TelegramProperties props(boolean enabled, String token, String username, String secret) {
        return new TelegramProperties(enabled, token, username, secret, "https://api.telegram.org",
                Duration.ofSeconds(3), Duration.ofSeconds(5), 3, Duration.ofHours(72), 60_000);
    }

    @Test
    void aDisabledChannelNeedsNoSettings() {
        assertThatCode(() -> props(false, null, null, null)).doesNotThrowAnyException();
    }

    @Test
    void anEnabledChannelRefusesToStartWithoutTokenUsernameOrSecret() {
        assertThatThrownBy(() -> props(true, "", "cadence_bot", "secret")).hasMessageContaining("bot-token");
        assertThatThrownBy(() -> props(true, TOKEN, null, "secret")).hasMessageContaining("bot-username");
        assertThatThrownBy(() -> props(true, TOKEN, "cadence_bot", " ")).hasMessageContaining("webhook-secret");
    }

    @Test
    void aPlaceholderTokenAndABadSecretAreRefusedWithoutEchoingThem() {
        assertThatThrownBy(() -> props(true, "CHANGE_ME_bot_token", "cadence_bot", "secret"))
                .hasMessageContaining("BotFather").hasMessageNotContaining("CHANGE_ME");
        assertThatThrownBy(() -> props(true, TOKEN, "cadence_bot", "bad secret!")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTokenNeverAppearsInTheStringForm() {
        assertThat(props(true, TOKEN, "cadence_bot", "secret").toString()).doesNotContain("STUB_token").doesNotContain("secret");
    }
}
