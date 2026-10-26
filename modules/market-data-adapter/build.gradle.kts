plugins {
    `java-library`
}

dependencies {
    implementation(project(":modules:trading-messaging"))
    implementation(project(":modules:strategy-runtime"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("io.lettuce:lettuce-core")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-junit-jupiter")
    testImplementation(testFixtures(project(":modules:trading-messaging")))
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation(project(":modules:trading-application"))
}
