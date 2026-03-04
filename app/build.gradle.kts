plugins {
    id("org.springframework.boot") version "3.5.11"
    id("io.spring.dependency-management") version "1.1.7"
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
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")
    
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")

    kapt("org.springframework.boot:spring-boot-configuration-processor")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))
    implementation(kotlin("stdlib"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

application {
    mainClass.set("di.swallet.wpb.WpbApplicationKt")
}

tasks.withType<JavaExec> {
    jvmArgs(
        "--add-exports=jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNAMED",
        "--add-exports=java.base/sun.security.x509=ALL-UNNAMED"
    )
    // Environment variable for SoftHSM2 configuration file
    environment("SOFTHSM2_CONF", "${System.getProperty("user.home")}/.softhsm2.conf")
}

tasks.withType<Test> {
    useJUnitPlatform()
    
    // Activate the 'test' profile for clean logging
    systemProperty("spring.profiles.active", "test")

    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true 
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    
    environment("SOFTHSM2_CONF", "${System.getProperty("user.home")}/.softhsm2.conf")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "17"
    }
}