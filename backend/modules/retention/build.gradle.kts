plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.spring.boot.starter)
    api(project(":modules:platform"))
    api(project(":modules:crm"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:notification"))
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
