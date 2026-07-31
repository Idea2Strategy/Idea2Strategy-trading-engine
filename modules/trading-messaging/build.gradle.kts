plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    implementation(project(":modules:trading-application"))

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
