import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

group = "com.github.marcoral.versatia"
version = providers.gradleProperty("versatiaVersion").get()

base {
    archivesName = "VersatiaAPI"
}

// Exported to consumers' compile classpath; see docs/build.md
dependencies {
    compileOnlyApi(libs.paper.api)
    api(kotlin("stdlib"))
    api(kotlin("reflect"))

    testImplementation(kotlin("test"))
    testImplementation(libs.paper.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Paper 1.21.4 requires Java 21
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
            artifactId = "VersatiaAPI"
            from(components["java"])
        }
    }
}
