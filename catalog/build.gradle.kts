plugins {
    `version-catalog`
    `maven-publish`
}

group = "com.github.marcoral.versatia"
val versatiaVersion = providers.gradleProperty("versatiaVersion").get()
version = versatiaVersion

// The shared third-party versions plus the Versatia artifacts at this build's version; see docs/build.md
catalog {
    versionCatalog {
        from(files("../gradle/libs.versions.toml"))
        version("versatia", versatiaVersion)
        library("versatia-api", "com.github.marcoral.versatia", "VersatiaAPI").versionRef("versatia")
        library("versatia-ksp", "com.github.marcoral.versatia", "VersatiaAPI-ksp").versionRef("versatia")
        library("versatia-starter", "com.github.marcoral.versatia", "VersatiaStarter").versionRef("versatia")
        library("versatia-messages", "com.github.marcoral.versatia", "VersatiaModule-Messages").versionRef("versatia")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "VersatiaCatalog"
            from(components["versionCatalog"])
        }
    }
}
