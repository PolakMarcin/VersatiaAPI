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
    compileOnlyApi("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    api(kotlin("stdlib"))
    api(kotlin("reflect"))

    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
