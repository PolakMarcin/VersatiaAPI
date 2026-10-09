rootProject.name = "VersatiaAPI"

include("api", "ksp")

// Shared by every module; see docs/build.md
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
