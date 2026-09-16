package com.cadence.growth.api;

public record GrowthSignalView(
        String key,
        String title,
        String evidence,
        String suggestedAction
) {
}
