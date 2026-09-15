plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.spring.boot.starter)
    implementation(libs.jackson.databind)
    implementation("org.springframework:spring-web")
    // ai must depend only on service ports — never on jOOQ-generated tables / repositories
    implementation(project(":modules:catalog"))
    implementation(project(":modules:booking-engine"))
    implementation(project(":modules:crm"))
    implementation(project(":modules:growth"))
    implementation(project(":modules:retention"))
    implementation(project(":modules:platform"))
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
