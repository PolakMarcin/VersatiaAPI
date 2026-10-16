rootProject.name = "VersatiaAPI"

include("api", "ksp", "catalog")

// Shared by every module; see docs/build.md
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
