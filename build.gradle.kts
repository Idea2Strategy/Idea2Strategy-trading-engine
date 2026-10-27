plugins {
    base
    id("org.springframework.boot") version "4.1.0" apply false
}

allprojects {
    group = "com.idea2strategy"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

/**
 * Whether a Docker engine accepts connections on an endpoint Testcontainers would try.
 *
 * Container test classes use `disabledWithoutDocker = true`, so a run without Docker passes with
 * them skipped. Such a skip-only result is never stored in the build cache, and as a test input
 * this keeps a later run that has Docker from treating it as up-to-date.
 */
abstract class DockerAvailability : ValueSource<Boolean, ValueSourceParameters.None> {
    override fun obtain(): Boolean = candidateEndpoints().any(::accepts)

    private fun candidateEndpoints(): List<String> {
        val home = System.getProperty("user.home")
        val userProperties = java.util.Properties()
        val userPropertiesFile = java.io.File(home, ".testcontainers.properties")
        if (userPropertiesFile.isFile) {
            userPropertiesFile.inputStream().use(userProperties::load)
        }
        return listOfNotNull(
            System.getenv("DOCKER_HOST") ?: userProperties.getProperty("docker.host"),
            userProperties.getProperty("tc.host"),
            "unix:///var/run/docker.sock",
            "unix://$home/.docker/run/docker.sock",
            "unix://$home/.docker/desktop/docker.sock",
            System.getenv("XDG_RUNTIME_DIR")?.let { "unix://$it/docker.sock" },
            "npipe:////./pipe/docker_engine",
        ).filter(String::isNotBlank)
    }

    private fun accepts(endpoint: String): Boolean = try {
        val uri = java.net.URI(endpoint)
        when (uri.scheme) {
            "unix" -> java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use {
                it.connect(java.net.UnixDomainSocketAddress.of(uri.path))
            }
            "tcp", "http", "https" -> java.net.Socket().use {
                it.connect(java.net.InetSocketAddress(uri.host, uri.port.takeIf { port -> port > 0 } ?: 2375), 1_000)
                true
            }
            "npipe" -> java.io.File(endpoint.removePrefix("npipe://").replace('/', '\\')).exists()
            else -> false
        }
    } catch (unreachable: Exception) {
        false
    }
}

val dockerAvailable = providers.of(DockerAvailability::class.java) {}

/**
 * CI passes `-PrequireContainerTests=true`: a test task that skipped any test then fails, so a run
 * without Docker cannot pass with its container tests skipped. Local runs leave it unset.
 */
val requireContainerTests = providers.gradleProperty("requireContainerTests")
    .map(String::toBoolean)
    .getOrElse(false)

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(21)
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            val docker = dockerAvailable
            inputs.property("dockerAvailable", docker)
            outputs.cacheIf("Docker is available, so container tests ran") { docker.get() }
            if (requireContainerTests) {
                val junitXml = reports.junitXml.outputLocation
                doLast("rejectSkippedTests") {
                    val skipped = junitXml.get().asFile
                        .listFiles { file -> file.name.startsWith("TEST-") && file.name.endsWith(".xml") }
                        .orEmpty()
                        .map { report ->
                            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                                .parse(report).documentElement
                        }
                        .filter { suite -> (suite.getAttribute("skipped").toIntOrNull() ?: 0) > 0 }
                        .map { suite -> "${suite.getAttribute("name")} (${suite.getAttribute("skipped")})" }
                        .sorted()
                    if (skipped.isNotEmpty()) {
                        throw GradleException(
                            "$path skipped tests with -PrequireContainerTests=true. Container tests " +
                                "need a Docker engine that Testcontainers can reach. Skipped: " +
                                skipped.joinToString(", "),
                        )
                    }
                }
            }
        }
    }
}

abstract class VerifyContainerBuildContracts : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dockerfiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dockerignore: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val environmentExamples: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val requiredApplications = mapOf(
            "market-gateway" to "apps/market-gateway/build/libs/market-gateway-0.1.0-SNAPSHOT.jar",
            "trading-worker" to "apps/trading-worker/build/libs/trading-worker-0.1.0-SNAPSHOT.jar",
        )

        requiredApplications.forEach { (application, jarPath) ->
            val dockerfile = dockerfiles.files.singleOrNull { it.invariantSeparatorsPath.endsWith("apps/$application/Dockerfile") }
                ?: error("Missing apps/$application/Dockerfile")
            val content = dockerfile.readText()
            check(Regex("""(?m)^# syntax=docker/dockerfile:1\.18@sha256:[0-9a-f]{64}${'$'}""").containsMatchIn(content)) {
                "$application must pin the Dockerfile frontend by digest"
            }
            check(Regex("""(?m)^FROM --platform=\${'$'}BUILDPLATFORM eclipse-temurin:21-jdk-jammy@sha256:[0-9a-f]{64} AS build${'$'}""").containsMatchIn(content)) {
                "$application builder must pin a multi-platform Java 21 image by digest"
            }
            check(Regex("""(?m)^FROM eclipse-temurin:21-jre-jammy@sha256:[0-9a-f]{64}${'$'}""").containsMatchIn(content)) {
                "$application runtime must pin a multi-platform Java 21 image by digest"
            }
            check(content.contains("./gradlew :apps:$application:bootJar --no-daemon")) {
                "$application must build its own Spring Boot executable jar"
            }
            check(content.contains("sed -i 's/\\r$//' gradlew")) {
                "$application build must tolerate a CRLF Gradle wrapper from Windows checkouts"
            }
            check(content.contains("COPY --from=build --chown=10001:10001 /workspace/$jarPath /opt/idea2strategy/application.jar")) {
                "$application must copy the expected executable jar"
            }
            check(Regex("""(?m)^USER [1-9][0-9]*:[1-9][0-9]*${'$'}""").containsMatchIn(content)) {
                "$application runtime must use an explicit non-root uid and gid"
            }
            check(content.contains("""ENTRYPOINT ["java","-jar","/opt/idea2strategy/application.jar"]""")) {
                "$application must launch the executable jar without a shell"
            }
            check(content.contains("ENV I2S_READINESS_FILE=/tmp/idea2strategy-ready")) {
                "$application must declare the readiness marker consumed by orchestration"
            }
            check(content.contains("STOPSIGNAL SIGTERM")) {
                "$application must deliver a graceful SIGTERM to the Java PID 1 process"
            }
            check(content.contains(
                    """HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 CMD test -s "${'$'}I2S_READINESS_FILE" && kill -0 1""")) {
                "$application must check both semantic readiness and Java process liveness"
            }
        }

        val dockerignore = dockerignore.get().asFile
        check(dockerignore.isFile) { "Missing .dockerignore" }
        val ignored = dockerignore.readLines().map(String::trim).toSet()
        check(setOf(".git", ".gradle", "build", "**/build").all(ignored::contains)) {
            ".dockerignore must exclude Git and Gradle build state"
        }

        val requiredEnvironment = mapOf(
            "market-gateway" to setOf(
                "ALPACA_API_KEY",
                "ALPACA_API_SECRET",
                "MARKET_GATEWAY_REDIS_URI",
                "MARKET_GATEWAY_REDIS_KEY_PREFIX",
                "MARKET_GATEWAY_INSTRUMENT_MAPPING_PATH",
                "MARKET_GATEWAY_RIGHTS_EVIDENCE_PATH",
                "I2S_READINESS_FILE",
                "JAVA_TOOL_OPTIONS",
            ),
            "trading-worker" to setOf(
                "SPRING_DATASOURCE_URL",
                "SPRING_DATASOURCE_USERNAME",
                "SPRING_DATASOURCE_PASSWORD",
                "SPRING_FLYWAY_ENABLED",
                "TRADING_MARKET_EVENTS_REDIS_URI",
                "TRADING_MARKET_EVENTS_REDIS_KEY_PREFIX",
                "TRADING_MARKET_EVENTS_CONSUMER_NAME",
                "TRADING_WARMUP_BUNDLE_ROOT",
                "TRADING_BOT_CONTROL_WORKER_ID",
                "TRADING_RUNTIME_SHUTDOWN_DRAIN_TIMEOUT",
                "I2S_READINESS_FILE",
                "JAVA_TOOL_OPTIONS",
            ),
        )
        requiredEnvironment.forEach { (application, requiredKeys) ->
            val example = environmentExamples.files.singleOrNull {
                it.name == "$application.env.example"
            } ?: error("Missing deploy/$application.env.example")
            val entries = example.readLines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .associate { line ->
                    val separator = line.indexOf('=')
                    check(separator > 0) { "Malformed line in ${example.path}: $line" }
                    line.substring(0, separator) to line.substring(separator + 1)
                }
            check(entries.keys.containsAll(requiredKeys)) {
                "$application environment example is missing ${requiredKeys - entries.keys}"
            }
            val secretKeys = entries.keys.filter {
                it.endsWith("_PASSWORD") || it == "ALPACA_API_KEY" || it == "ALPACA_API_SECRET"
            }
            check(secretKeys.all { entries.getValue(it).isEmpty() }) {
                "$application example secret values must stay blank"
            }
            check(entries.getValue("JAVA_TOOL_OPTIONS").contains("-XX:+ExitOnOutOfMemoryError")) {
                "$application must fail the container on an unrecoverable Java heap exhaustion"
            }
        }
    }
}

val verifyContainerBuildContracts by tasks.registering(VerifyContainerBuildContracts::class) {
    group = "verification"
    description = "Verifies pinned, multi-platform, non-root runtime image contracts."
    dockerfiles.from(
        layout.projectDirectory.file("apps/market-gateway/Dockerfile"),
        layout.projectDirectory.file("apps/trading-worker/Dockerfile"),
    )
    dockerignore.set(layout.projectDirectory.file(".dockerignore"))
    environmentExamples.from(
        layout.projectDirectory.file("deploy/market-gateway.env.example"),
        layout.projectDirectory.file("deploy/trading-worker.env.example"),
    )
}

tasks.named("check") {
    dependsOn(verifyContainerBuildContracts)
}
