package com.cadence.app.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

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

    @ArchTest
    static final ArchRule aiMustNotTouchJooqTables =
            noClasses()
                    .that().resideInAPackage("com.cadence.ai..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.cadence.platform.jooq..")
                    .because("AI may only call module service ports, never jOOQ tables");
}
