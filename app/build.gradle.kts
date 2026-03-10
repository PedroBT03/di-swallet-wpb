plugins {
    // Core Spring Boot and Dependency Management
    id("org.springframework.boot") version "3.5.11"
    id("io.spring.dependency-management") version "1.1.7"
    
    // Kotlin plugins for JVM, Spring, JPA, and Annotation Processing (kapt)
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24"
    kotlin("plugin.jpa") version "1.9.24"
    kotlin("kapt") version "1.9.24"
    
    application
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

    // --- Persistence Layer ---
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql") // Production database
    testImplementation("com.h2database:h2")  // In-memory database for isolated testing

    // --- Identity & Credential Formats (Format Engine) ---
    // Used for JWS, JWT, and SD-JWT operations
    implementation("com.nimbusds:nimbus-jose-jwt:9.37.3")

    // --- Cryptography & Security ---
    // Standard security provider for X.509 and certificate utilities
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")
    // FIDO2 / WebAuthn support for Strong User Authentication (SUA)
    implementation("com.yubico:webauthn-server-core:2.5.4")
    
    // --- API Documentation ---
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")

    // --- Configuration Metadata Generation ---
    // Enables IDE auto-completion for custom application properties
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    
    // --- Testing Framework ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
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

tasks.withType<Test> {
    useJUnitPlatform()
    
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
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "17"
    }
}