plugins {
    // Core Spring Boot and Dependency Management
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.7"
    
    // Kotlin plugins for JVM, Spring, JPA, and Annotation Processing (kapt)
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.spring") version "2.2.0"
    kotlin("plugin.jpa") version "2.2.0"
    kotlin("kapt") version "2.2.0"
    
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
    implementation("org.springframework.boot:spring-boot-starter-web")
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
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
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
    implementation("io.projectreactor:reactor-core:3.5.15")
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
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")

    // --- Configuration Metadata Generation ---
    // Enables IDE auto-completion for custom application properties
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    
    // --- Testing Framework ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.wiremock:wiremock-standalone:3.5.4")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.20.1")
    testImplementation("com.augustcellars.cose:cose-java:1.1.0")
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

tasks.withType<JavaExec> {
    // Access internal JDK modules required for HSM (PKCS#11) and Certificate operations
    jvmArgs(
        "--add-exports=jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNAMED",
        "--add-exports=java.base/sun.security.x509=ALL-UNNAMED"
    )
    // Automated environment variable for SoftHSM2 configuration
    environment("SOFTHSM2_CONF", "${System.getProperty("user.home")}/.softhsm2.conf")
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

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    
    // Activate 'test' profile to enable clean, human-readable reporting logs
    systemProperty("spring.profiles.active", "test")

    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true 
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    
    // Automated environment variable for SoftHSM2 during test execution
    environment("SOFTHSM2_CONF", "${System.getProperty("user.home")}/.softhsm2.conf")
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