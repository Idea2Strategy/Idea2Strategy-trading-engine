plugins {
    `java-library`
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation(project(":modules:trading-domain"))
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(project(":modules:market-data-adapter"))
    testImplementation(project(":modules:trading-messaging"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
