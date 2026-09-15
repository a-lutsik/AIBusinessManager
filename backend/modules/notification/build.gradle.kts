plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.spring.boot.starter)
    api(project(":modules:platform"))
    implementation(libs.jackson.databind)
}
