package com.aibusinessmanager.catalog.api;

public record BookingRulesView(
        int slotStepMinutes,
        int minNoticeMinutes,
        int horizonDays,
        boolean clientRescheduleAllowed,
        int lateCancellationHours,
        boolean newClientRequiresConfirmation,
        boolean deductPackageOnNoShow,
        int weekStartsOn,
        boolean timeFormat24h
) {
}
