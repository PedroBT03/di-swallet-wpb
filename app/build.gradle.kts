plugins {
    // Core Spring Boot and Dependency Management
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    
    // Kotlin plugins for JVM, Spring, JPA, and Annotation Processing (kapt)
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    kotlin("plugin.jpa") version "2.3.0"
    kotlin("kapt") version "2.3.0"
    
    application
    jacoco
}

group = "di.swallet.wpb"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // --- Web & Core Infrastructure ---
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-jackson2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))
    implementation(kotlin("stdlib"))

    // --- OpenID4VP / EUDI SDK ---
    implementation("eu.europa.ec.eudi:eudi-lib-jvm-openid4vp-kt:0.13.0")
    implementation("eu.europa.ec.eudi:eudi-lib-jvm-openid4vci-kt:0.7.0")

    // --- Ktor client used by the EUDI SDK wrapper ---
    implementation("io.ktor:ktor-client-cio:3.3.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.3.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")

    // --- Persistence Layer ---
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.postgresql:postgresql") // Production database
    runtimeOnly("com.h2database:h2") // Allow local H2 runtime for dev/emulator
    testImplementation("com.h2database:h2")  // In-memory database for isolated testing

    // --- Identity & Credential Formats (Format Engine) ---
    // Used for JWS, JWT, and SD-JWT operations
    implementation("com.nimbusds:nimbus-jose-jwt:9.37.3")
    // CBOR/COSE/mdoc tooling for ISO 18013-5 interoperable artifacts.
    implementation("com.authlete:cbor:1.19")

    // Reactive streams (coroutines interop) required for suspending controller support
    implementation("org.reactivestreams:reactive-streams:1.0.4")
    implementation("io.projectreactor:reactor-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // --- Cryptography & Security ---
    // Standard security provider for X.509 and certificate utilities
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")
    // FIDO2 / WebAuthn support for Strong User Authentication (SUA)
    implementation("com.yubico:webauthn-server-core:2.5.4")
    // FIDO2 Metadata Service (MDS) for hardware attestation verification
    implementation("com.yubico:webauthn-server-attestation:2.5.4")
    
    // --- API Documentation ---
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    // --- Operations ---
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // --- Configuration Metadata Generation ---
    // Enables IDE auto-completion for custom application properties
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    
    // --- Testing Framework ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.wiremock:wiremock-standalone:3.5.4")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.20.1")
    testImplementation("com.augustcellars.cose:cose-java:1.1.0")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.1")
}

kapt {
    correctErrorTypes = true
}

sourceSets {
    named("main") {
        java.srcDir(layout.buildDirectory.dir("tmp/kapt3/classes/main"))
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(tasks.named("kaptKotlin"))
    from(layout.buildDirectory.dir("tmp/kapt3/classes/main")) {
        include("META-INF/spring-configuration-metadata.json")
    }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

application {
    mainClass.set("di.swallet.wpb.WpbApplicationKt")
}

springBoot {
    buildInfo {
        properties {
            val gitCommit = runCatching {
                ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .directory(rootProject.projectDir)
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            }.getOrDefault("unknown").ifBlank { "unknown" }
            additional.set(mapOf("git.commit.id.abbrev" to gitCommit))
        }
    }
}

/** Parses KEY=VALUE lines from the repo-root `.env` (comments and blanks ignored). */
fun parseDotEnv(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    return file.readLines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
        val separator = trimmed.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        trimmed.substring(0, separator).trim() to trimmed.substring(separator + 1).trim()
    }.toMap()
}

val localDotEnv = parseDotEnv(rootProject.file(".env"))

tasks.withType<JavaExec> {
    // Access internal JDK modules required for HSM (PKCS#11) and Certificate operations
    jvmArgs(
        "--add-exports=jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNAMED",
        "--add-exports=java.base/sun.security.x509=ALL-UNNAMED"
    )
    // Automated environment variable for SoftHSM2 configuration
    environment(
        "SOFTHSM2_CONF",
        System.getenv("SOFTHSM2_CONF") ?: "${System.getProperty("user.home")}/.softhsm2.conf",
    )
    if (System.getenv("SPRING_DATASOURCE_USERNAME").isNullOrBlank()) {
        environment(
            "SPRING_DATASOURCE_USERNAME",
            localDotEnv["POSTGRES_USER"] ?: "pedro",
        )
    }
    if (System.getenv("SPRING_DATASOURCE_PASSWORD").isNullOrBlank()) {
        environment(
            "SPRING_DATASOURCE_PASSWORD",
            localDotEnv["POSTGRES_PASSWORD"] ?: "tese2026",
        )
    }
}

jacoco {
    toolVersion = "0.8.12"
}

val jacocoCoverageExcludes = listOf(
    "**/WpbApplication*",
)

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    classDirectories.setFrom(
        sourceSets.main.get().output.classesDirs.files.map { classesDir ->
            fileTree(classesDir) {
                exclude(jacocoCoverageExcludes)
            }
        },
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = 0.30.toBigDecimal()
            }
        }
    }
    classDirectories.setFrom(tasks.jacocoTestReport.get().classDirectories)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("performance", "external")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)

    jvmArgs(
        "--add-exports=jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNAMED",
        "--add-exports=java.base/sun.security.x509=ALL-UNNAMED",
    )
    
    // Activate 'test' profile to enable clean, human-readable reporting logs
    systemProperty("spring.profiles.active", "test")

    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true 
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    
    // Automated environment variable for SoftHSM2 during test execution
    environment(
        "SOFTHSM2_CONF",
        System.getenv("SOFTHSM2_CONF") ?: "${System.getProperty("user.home")}/.softhsm2.conf",
    )
}

val conformanceReportDir = layout.buildDirectory.dir("reports/conformance")

tasks.register<Test>("conformanceTest") {
    description = "Runs @Tag(conformance) tests and writes build/reports/conformance/summary.md"
    group = "verification"
    useJUnitPlatform {
        includeTags("conformance")
    }
    systemProperty("spring.profiles.active", "test")
    systemProperty("conformance.report.dir", conformanceReportDir.get().asFile.absolutePath)
    environment(
        "SOFTHSM2_CONF",
        System.getenv("SOFTHSM2_CONF") ?: "${System.getProperty("user.home")}/.softhsm2.conf",
    )
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}

val performanceReportDir = layout.buildDirectory.dir("reports/performance")

tasks.register<Test>("performanceTest") {
    description = "Runs @Tag(performance) smoke tests (manual / optional CI)"
    group = "verification"
    useJUnitPlatform {
        includeTags("performance")
    }
    systemProperty("spring.profiles.active", "test")
    systemProperty("performance.report.dir", performanceReportDir.get().asFile.absolutePath)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.register<Test>("externalInteropTest") {
    description = "Runs optional external interop tests (env-gated, Tier 3)"
    group = "verification"
    useJUnitPlatform {
        includeTags("external")
    }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<org.springframework.boot.gradle.tasks.bundling.BootJar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}