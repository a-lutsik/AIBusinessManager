plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.spring.boot.starter)
    api(project(":modules:platform"))
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
