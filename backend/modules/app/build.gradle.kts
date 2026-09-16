plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    implementation(project(":modules:platform"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:booking-engine"))
    implementation(project(":modules:crm"))
    implementation(project(":modules:retention"))
    implementation(project(":modules:growth"))
    implementation(project(":modules:ai"))
    implementation(project(":modules:notification"))

    implementation(libs.spring.boot.starter.batch)
    implementation(libs.spring.boot.starter.integration)
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveBaseName.set("cadence")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    val localYaml = layout.projectDirectory.file("application-local.yml").asFile
    if (localYaml.exists()) {
        args(
            "--spring.profiles.include=local",
            "--spring.config.additional-location=optional:file:${localYaml.absolutePath}"
        )
    }
}
