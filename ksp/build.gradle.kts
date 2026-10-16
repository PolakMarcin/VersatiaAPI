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

// Runs inside the plugin author's compiler, never on the server; see docs/build.md
dependencies {
    implementation(project(":api"))
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet.core)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(kotlin("test"))
    testImplementation(libs.kctfork.ksp)
    // Sample plugin sources compiled in tests extend JavaPlugin
    testImplementation(libs.paper.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // Framework code may use the plumbing marked @VersatiaInternalApi; see docs/api.md of VersatiaAPI
        optIn.add("com.github.marcoral.versatia.VersatiaInternalApi")
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
