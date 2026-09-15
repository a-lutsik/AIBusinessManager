package com.aibusinessmanager.app.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.aibusinessmanager",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ModuleBoundaryArchTest {

    @ArchTest
    static final ArchRule noJpa =
            noClasses()
                    .that().resideInAPackage("com.aibusinessmanager..")
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
                    .that().resideOutsideOfPackage("com.aibusinessmanager.catalog.internal..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.aibusinessmanager.catalog.internal..");

    @ArchTest
    static final ArchRule bookingInternalIsPrivate =
            noClasses()
                    .that().resideOutsideOfPackage("com.aibusinessmanager.booking.internal..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.aibusinessmanager.booking.internal..");

    @ArchTest
    static final ArchRule crmInternalIsPrivate =
            noClasses()
                    .that().resideOutsideOfPackage("com.aibusinessmanager.crm.internal..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.aibusinessmanager.crm.internal..");

    @ArchTest
    static final ArchRule retentionInternalIsPrivate =
            noClasses()
                    .that().resideOutsideOfPackage("com.aibusinessmanager.retention.internal..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.aibusinessmanager.retention.internal..");

    @ArchTest
    static final ArchRule growthInternalIsPrivate =
            noClasses()
                    .that().resideOutsideOfPackage("com.aibusinessmanager.growth.internal..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.aibusinessmanager.growth.internal..");

    @ArchTest
    static final ArchRule aiInternalIsPrivate =
            noClasses()
                    .that().resideOutsideOfPackage("com.aibusinessmanager.ai.internal..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.aibusinessmanager.ai.internal..");

    @ArchTest
    static final ArchRule notificationInternalIsPrivate =
            noClasses()
                    .that().resideOutsideOfPackage("com.aibusinessmanager.notification.internal..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.aibusinessmanager.notification.internal..");

    @ArchTest
    static final ArchRule aiMustNotTouchJooqTables =
            noClasses()
                    .that().resideInAPackage("com.aibusinessmanager.ai..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.aibusinessmanager.platform.jooq..")
                    .because("AI may only call module service ports, never jOOQ tables");
}
