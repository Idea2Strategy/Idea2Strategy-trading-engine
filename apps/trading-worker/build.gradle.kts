plugins {
    java
    id("org.springframework.boot")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation(project(":modules:trading-application"))
    implementation(project(":modules:strategy-runtime"))
    implementation(project(":modules:trading-persistence"))
    implementation(project(":modules:trading-messaging"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
