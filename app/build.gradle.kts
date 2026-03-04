plugins {
    id("org.springframework.boot") version "3.5.11"
    id("io.spring.dependency-management") version "1.1.7"
    
    // Kotlin versions
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24"
    
    application
}

group = "di.swallet.wpb"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot Starters (Automatically uses the version from the plugin)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))
    implementation(kotlin("stdlib"))
}

application {
    mainClass.set("di.swallet.wpb.WpbApplicationKt")
}

tasks.withType<JavaExec> {
    // Mandatory for SunPKCS11 access
    jvmArgs("--add-exports=jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNAMED")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "17" // Matches your VS Code setting
    }
}