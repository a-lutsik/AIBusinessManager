package com.cadence.app.architecture;

import com.cadence.platform.jooq.tables.AiConversation;
import com.cadence.platform.jooq.tables.AiDraft;
import com.cadence.platform.jooq.tables.AiMessage;
import com.cadence.platform.jooq.tables.AiPromptLog;
import com.cadence.platform.jooq.tables.AiPseudonym;
import com.cadence.platform.jooq.tables.AiTenantSettings;
import com.cadence.platform.jooq.tables.AiToolCall;
import com.cadence.platform.jooq.tables.AiUsageMonth;
import com.cadence.platform.jooq.tables.Appointment;
import com.cadence.platform.jooq.tables.AppointmentEvent;
import com.cadence.platform.jooq.tables.GrowthSignal;
import com.cadence.platform.jooq.tables.MetricSnapshot;
import com.cadence.platform.jooq.tables.records.AiConversationRecord;
import com.cadence.platform.jooq.tables.records.AiDraftRecord;
import com.cadence.platform.jooq.tables.records.AiMessageRecord;
import com.cadence.platform.jooq.tables.records.AiPromptLogRecord;
import com.cadence.platform.jooq.tables.records.AiPseudonymRecord;
import com.cadence.platform.jooq.tables.records.AiTenantSettingsRecord;
import com.cadence.platform.jooq.tables.records.AiToolCallRecord;
import com.cadence.platform.jooq.tables.records.AiUsageMonthRecord;
import com.cadence.platform.jooq.tables.records.AppointmentEventRecord;
import com.cadence.platform.jooq.tables.records.AppointmentRecord;
import com.cadence.platform.jooq.tables.records.GrowthSignalRecord;
import com.cadence.platform.jooq.tables.records.MetricSnapshotRecord;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.cadence",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ModuleBoundaryArchTest {

    @ArchTest
    static final ArchRule noJpa =
            noClasses()
                    .that().resideInAPackage("com.cadence..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "jakarta.persistence..",
                            "javax.persistence..",
                            "org.hibernate..",
                            "org.springframework.data.jpa.."
                    )
                    .because("Persistence is jOOQ + Flyway only — no JPA/Hibernate");

    @ArchTest
    static final ArchRule catalogInternalIsPrivate =
            noClasses()
                    .that().resideOutsideOfPackage("com.cadence.catalog.internal..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.cadence.catalog.internal..");

    @ArchTest
    static final ArchRule bookingInternalIsPrivate =
            noClasses()
                    .that().resideOutsideOfPackage("com.cadence.booking.internal..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.cadence.booking.internal..");

    @ArchTest
    static final ArchRule crmInternalIsPrivate =
            noClasses()
                    .that().resideOutsideOfPackage("com.cadence.crm.internal..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.cadence.crm.internal..");

    @ArchTest
    static final ArchRule retentionInternalIsPrivate =
            noClasses()
                    .that().resideOutsideOfPackage("com.cadence.retention.internal..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.cadence.retention.internal..");

    @ArchTest
    static final ArchRule growthInternalIsPrivate =
            noClasses()
                    .that().resideOutsideOfPackage("com.cadence.growth.internal..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.cadence.growth.internal..");

    @ArchTest
    static final ArchRule aiInternalIsPrivate =
            noClasses()
                    .that().resideOutsideOfPackage("com.cadence.ai.internal..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.cadence.ai.internal..");

    @ArchTest
    static final ArchRule notificationInternalIsPrivate =
            noClasses()
                    .that().resideOutsideOfPackage("com.cadence.notification.internal..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.cadence.notification.internal..");

    /**
     * ADR 0001: the booking journal and the visit rows are written and read by booking-engine only (single write path
     * {@code AppointmentRepository.record}, read port {@code BookingJournal}); metric snapshots and signals by growth
     * only. The generated jOOQ catalog itself ({@code Tables}, keys, indexes) is exempt.
     */
    @ArchTest
    static final ArchRule appointmentTablesBelongToBookingEngine =
            noClasses()
                    .that().resideOutsideOfPackages("com.cadence.booking.internal..", "com.cadence.platform.jooq..")
                    .should().dependOnClassesThat()
                    .belongToAnyOf(Appointment.class, AppointmentRecord.class, AppointmentEvent.class, AppointmentEventRecord.class)
                    .because("appointment and appointment_event are booking-engine tables (ADR 0001)");

    @ArchTest
    static final ArchRule growthTablesBelongToGrowth =
            noClasses()
                    .that().resideOutsideOfPackages("com.cadence.growth.internal..", "com.cadence.platform.jooq..")
                    .should().dependOnClassesThat()
                    .belongToAnyOf(MetricSnapshot.class, MetricSnapshotRecord.class, GrowthSignal.class, GrowthSignalRecord.class)
                    .because("metric_snapshot and growth_signal are growth tables (ADR 0001)");

    /**
     * ADR 0002 §9: the AI tables are read and written only by the platform AI stores ({@code com.cadence.platform.ai}),
     * which the {@code ai} module uses through their interfaces. The generated jOOQ catalog itself is exempt.
     */
    @ArchTest
    static final ArchRule aiTablesBelongToPlatformAiStores =
            noClasses()
                    .that().resideOutsideOfPackages("com.cadence.platform.ai..", "com.cadence.platform.jooq..")
                    .should().dependOnClassesThat()
                    .belongToAnyOf(
                            AiTenantSettings.class, AiTenantSettingsRecord.class,
                            AiConversation.class, AiConversationRecord.class,
                            AiMessage.class, AiMessageRecord.class,
                            AiPseudonym.class, AiPseudonymRecord.class,
                            AiUsageMonth.class, AiUsageMonthRecord.class,
                            AiDraft.class, AiDraftRecord.class,
                            AiPromptLog.class, AiPromptLogRecord.class,
                            AiToolCall.class, AiToolCallRecord.class)
                    .because("the ai_* tables belong to the platform AI stores (ADR 0002 §9)");

    /** Review D1: the lag-free recalculation is for the demo seed only, never for HTTP or the nightly job. */
    @ArchTest
    static final ArchRule seedRecalculationOnlyFromTheSeeder =
            noClasses()
                    .that().doNotHaveFullyQualifiedName("com.cadence.app.seed.DemoDataSeeder")
                    .and().resideOutsideOfPackage("com.cadence.growth..")
                    .should().callMethod(com.cadence.growth.api.GrowthService.class, "recalculateForSeed")
                    .because("only the single writer at startup may fold the journal without the as-of lag");

    /** Positive controls: the two rules above do see a table dependency where one is allowed. */
    @ArchTest
    static final ArchRule appointmentRuleSeesTheBookingRepository =
            classes()
                    .that().haveFullyQualifiedName("com.cadence.booking.internal.AppointmentRepository")
                    .should().dependOnClassesThat().belongToAnyOf(Appointment.class, AppointmentEvent.class);

    @ArchTest
    static final ArchRule growthRuleSeesTheSnapshotRepository =
            classes()
                    .that().haveFullyQualifiedName("com.cadence.growth.internal.MetricSnapshotRepository")
                    .should().dependOnClassesThat().belongToAnyOf(MetricSnapshot.class);

    @ArchTest
    static final ArchRule aiTableRuleSeesTheDraftStore =
            classes()
                    .that().haveFullyQualifiedName("com.cadence.platform.ai.JooqAiDraftStore")
                    .should().dependOnClassesThat().belongToAnyOf(AiDraft.class);

    @ArchTest
    static final ArchRule aiMustNotTouchJooqTables =
            noClasses()
                    .that().resideInAPackage("com.cadence.ai..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.cadence.platform.jooq..")
                    .because("AI may only call module service ports, never jOOQ tables");
}
