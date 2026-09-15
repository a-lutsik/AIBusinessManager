plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.spring.boot.starter)
    api(project(":modules:platform"))
    api(project(":modules:catalog"))
    api(project(":modules:crm"))
    implementation(project(":modules:retention"))
    implementation(project(":modules:notification"))
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
