plugins {
    `java-library`
    alias(libs.plugins.jooq.codegen)
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.spring.boot.starter)
    api(libs.spring.boot.starter.jdbc)
    api(libs.jooq)
    api(libs.libphonenumber)
    implementation(libs.jackson.databind)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    jooqCodegen(platform(libs.spring.boot.dependencies))
    jooqCodegen(libs.jooq.meta.extensions)
    jooqCodegen(libs.jooq.codegen)
    jooqCodegen(libs.jooq.meta)
}

sourceSets {
    main {
        java {
            srcDir(layout.buildDirectory.dir("generated-sources/jooq"))
        }
    }
}

jooq {
    configuration {
        generator {
            name = "org.jooq.codegen.JavaGenerator"
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property {
                        key = "scripts"
                        // V2 has Timescale extensions — parse only V1 for codegen
                        value = "src/main/resources/db/jooq/schema.sql"
                    }
                    property {
                        key = "sort"
                        value = "semantic"
                    }
                    property {
                        key = "unqualifiedSchema"
                        value = "public"
                    }
                    // Timescale / Postgres-specific bits that DDLDatabase may not parse
                    property {
                        key = "defaultNameCase"
                        value = "lower"
                    }
                }
                includes = ".*"
                excludes = "flyway_schema_history"
            }
            target {
                packageName = "com.cadence.platform.jooq"
                directory = layout.buildDirectory.dir("generated-sources/jooq").get().asFile.absolutePath
            }
        }
    }
}

tasks.named("compileJava") {
    dependsOn(tasks.named("jooqCodegen"))
}
