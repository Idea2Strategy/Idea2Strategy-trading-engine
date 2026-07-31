plugins {
    `java-library`
}

dependencies {
    implementation(project(":modules:trading-messaging"))

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(testFixtures(project(":modules:trading-messaging")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation(project(":modules:trading-application"))
}
