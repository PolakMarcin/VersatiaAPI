# VersatiaAPI

Foundation library of the **Versatia framework** for Paper **1.21.4**, written in Kotlin and built
with Gradle.

VersatiaAPI is not a server plugin. It is what Versatia-based plugins compile against, in two
modules:

- `api` – the public API that the framework runtime (VersatiaCore, a separate repository)
  implements, syntax sugar over Paper, and the annotations plugins use to declare enhancements;
  today dependency injection (`@LocalBean`, `@ExposeSingleton`, `@ExposePrototype`, `@Qualifier`,
  `@Primary`, `Reloadable` beans), lifecycle hooks (`@AutoInvoke`), configuration values (`@Value`,
  `ValueMapper`, `PreviousValueTracking`), resource extraction (`@ExtractResources`), listener
  registration (`@RegisterListeners`), optional integrations (`@ConditionalOnPlugin`), friendly
  plugin names (`@FriendlyName`, read back through `PluginRegistry`) and switches that turn
  inherited features off (`@DisableVersatiaFeatures`),
- `ksp` – the compile-time step: a Kotlin Symbol Processing processor that analyses a plugin's
  sources, validates them and generates the descriptor the runtime interprets.

How the two projects cooperate and why nothing is discovered at runtime is described in
[docs/architecture.md](docs/architecture.md). The descriptor, the KSP setup and dependency
injection are in [docs/enhancements.md](docs/enhancements.md); the helper packages in
[docs/api.md](docs/api.md).

## Requirements

- JDK 21 or newer
- Nothing else. Gradle is downloaded automatically by the wrapper (`gradlew`).

## Building

```bash
./gradlew build
```

Builds both modules and runs their tests. The JARs are `api/build/libs/VersatiaAPI-<version>.jar`
and `ksp/build/libs/VersatiaAPI-ksp-<version>.jar`.

## Publishing for local development

Dependent projects consume VersatiaAPI from the local Maven repository (`~/.m2`):

```bash
./gradlew publishToMavenLocal
```

Run this after every change that a dependent project should see. It publishes both
`VersatiaAPI` and `VersatiaAPI-ksp`.

## Using it in a plugin

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenLocal { content { includeGroup("com.github.marcoral.versatia") } }
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.20"
    id("com.google.devtools.ksp") version "2.3.12"
}

dependencies {
    implementation("com.github.marcoral.versatia:VersatiaAPI:0.0.1")
    ksp("com.github.marcoral.versatia:VersatiaAPI-ksp:0.0.1")
}
```

No further configuration: the processor finds the plugin's main class in the sources and places the
generated descriptor next to it.

The plugin's main class extends `com.github.marcoral.versatia.VersatiaPlugin` and its
`plugin.yml` lists `VersatiaCore` under `depend:` and does not list Kotlin under `libraries:`
(VersatiaCore provides it; see [docs/enhancements.md](docs/enhancements.md#setting-up-a-plugin-build)).

Projects that depend on VersatiaAPI inherit the Paper API, the Kotlin standard library and
`kotlin-reflect` on their compile classpath, so they do not declare those themselves. The Paper
repository is still required, because Gradle does not inherit repositories from dependencies, and
so is `testImplementation` of `paper-api` for unit tests that touch Bukkit classes such as
`YamlConfiguration`: the Paper API is inherited for compilation only.
See [docs/build.md](docs/build.md) for how this is set up.

## Project layout

- `api/src/main/kotlin/` – library sources under `com.github.marcoral.versatia`, organised by
  feature: the root package (enhancement mechanism, `VersatiaPlugin`, `VersatiaEngine` and its
  static shortcuts),
  `engine.descriptor` (model of the generated descriptor), `di` (bean scopes), `lifecycle`
  (`@AutoInvoke`), `config` (`@Value`), `resources` (`@ExtractResources`), `event`
  (`@RegisterListeners`), `util.*` (helpers, see [docs/api.md](docs/api.md))
- `api/src/test/kotlin/` – unit tests of the helpers
- `ksp/src/main/kotlin/` – the KSP processor, with `ksp.di`, `ksp.lifecycle` and `ksp.config` for
  the feature-specific collectors and checks and `ksp.model` for the descriptor model and generator
- `ksp/src/test/kotlin/` – unit tests plus compile-testing of the processor
- `docs/architecture.md` – how VersatiaAPI and VersatiaCore cooperate
- `docs/enhancements.md` – descriptor format, KSP setup, dependency injection rules
- `docs/api.md` – package overview and API conventions
- `docs/build.md` – modules, build and publishing details
