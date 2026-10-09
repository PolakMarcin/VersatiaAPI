import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

group = "com.github.marcoral.versatia"
version = providers.gradleProperty("versatiaVersion").get()

base {
    archivesName = "VersatiaAPI-ksp"
}

val kspVersion = providers.gradleProperty("kspVersion").get()
val kotlinPoetVersion = providers.gradleProperty("kotlinPoetVersion").get()
val kctforkVersion = providers.gradleProperty("kctforkVersion").get()

// Runs inside the plugin author's compiler, never on the server; see docs/build.md
dependencies {
    implementation(project(":api"))
    implementation("com.google.devtools.ksp:symbol-processing-api:$kspVersion")
    implementation("com.squareup:kotlinpoet:$kotlinPoetVersion")
    implementation("com.squareup:kotlinpoet-ksp:$kotlinPoetVersion")

    testImplementation(kotlin("test"))
    testImplementation("dev.zacsweers.kctfork:ksp:$kctforkVersion")
    // Sample plugin sources compiled in tests extend JavaPlugin
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "VersatiaAPI-ksp"
            from(components["java"])
        }
    }
}
