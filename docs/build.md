# Build configuration

VersatiaAPI is a multi-module Gradle build. This document explains the non-obvious parts of the
build scripts and what each module exports to projects that depend on it.

## Modules

| Module | Artifact                                             | Purpose                                              |
|--------|------------------------------------------------------|------------------------------------------------------|
| `api`  | `com.github.marcoral.versatia:VersatiaAPI`       | public API, helpers, annotations; on the server      |
| `ksp`  | `com.github.marcoral.versatia:VersatiaAPI-ksp`   | KSP processor generating descriptors; compile time   |

The root project only declares the Kotlin plugin version (`apply false`) and the shared
repositories (`dependencyResolutionManagement` in `settings.gradle.kts`). Versions shared by the
modules live in `gradle.properties` (`versatiaVersion`, `kspVersion`, `kotlinPoetVersion`,
`kctforkVersion`).

`archivesName` and the publication `artifactId` are set explicitly, so the artifacts keep their
names even though the Gradle project names are `api` and `ksp`. Gradle derives the coordinates of
the `ksp -> api` project dependency from `api`'s publication, so the published `ksp` POM points at
`VersatiaAPI`.

## Toolchain

- Paper 1.21.4 requires Java 21, so both modules target JVM 21. Any JDK 21 or newer can compile
  the project; no Gradle toolchain download is configured.
- Gradle comes from the wrapper (`gradlew`), so nothing has to be installed globally.
- `withSourcesJar()` publishes a sources JAR next to each binary.
- Neither module runs in explicit API mode: declarations rely on the default visibility and on
  inferred return types, see [api.md](api.md). Almost everything in `ksp` is `internal`; it is a
  tool, not an API.

## `api`: exported dependencies

The `java-library` plugin provides dependency scopes that control what consumers see:

| Scope            | Own compile classpath | Consumer compile classpath | Consumer runtime classpath |
|------------------|-----------------------|----------------------------|----------------------------|
| `compileOnlyApi` | yes                   | yes                        | no                         |
| `api`            | yes                   | yes                        | yes                        |
| `implementation` | yes                   | no                         | yes                        |
| `compileOnly`    | yes                   | no                         | no                         |

- `paper-api` is `compileOnlyApi`. Consumers compile against it, but it is never part of a
  runtime classpath, because the server provides it.
- `kotlin-stdlib` and `kotlin-reflect` are `api`. Consumers get them on both classpaths; the
  versions are aligned with the Kotlin Gradle plugin version through the Kotlin BOM constraints
  that the plugin adds. `kotlin-reflect` is required by the `util.reflect` package. On the server
  both come from VersatiaCore, which lists them under `libraries:`; a plugin must not list them
  itself, or it exchanges Kotlin classes with VersatiaCore across two copies; see
  [api.md](api.md#runtime-requirement).

Anything the public API exposes in signatures must be `api` or `compileOnlyApi`. Internal
dependencies stay `implementation`.

Gradle does not export repositories. A consumer must still declare the Paper repository to resolve
the exported `paper-api`.

## `ksp`: processor dependencies

`symbol-processing-api`, `kotlinpoet` and `kotlinpoet-ksp` are `implementation` dependencies.
A plugin adds the module with the `ksp(...)` configuration, so these libraries end up on the
plugin's annotation-processing classpath only and never near the server. `ksp` depends on `api`
for the annotation names and the descriptor model it generates code against.

KSP has its own version line (`2.3.x`) that is decoupled from the Kotlin version. The processor is
compiled against `symbol-processing-api` only; the Kotlin compiler the plugin author uses provides
the implementation.

## Tests

Both modules use `kotlin("test")`, which resolves to `kotlin-test-junit5` because `tasks.test`
uses `useJUnitPlatform()`. `junit-platform-launcher` is declared as `testRuntimeOnly`; Gradle 9 no
longer adds it automatically.

- `api`: `paper-api` is added as `testImplementation` so tests can use `YamlConfiguration` and
  other server-independent Bukkit classes at runtime. It stays `compileOnlyApi` for consumers, so a
  plugin's own unit tests compile against it but fail with `NoClassDefFoundError` until the plugin
  build adds `testImplementation("io.papermc.paper:paper-api:...")` as well.
- `ksp`: `dev.zacsweers.kctfork:ksp` (a maintained fork of `kotlin-compile-testing`) compiles
  sample plugin sources with the processor in-process and exposes the generated files and the
  compiled classes. Its API is marked `@ExperimentalCompilerApi`, hence the `@OptIn` on the test
  class. kctfork is built against exact Kotlin and KSP versions; when bumping Kotlin, pick the
  kctfork release that matches (its POM lists `kotlin-compiler-embeddable` and
  `symbol-processing-api`).

Run them with `./gradlew test`; `./gradlew build` runs them as well.

## Publishing

Each module has a single `maven` publication from its `java` component: POM, Gradle Module
Metadata (`.module`), binary JAR and sources JAR.

Gradle consumers read the `.module` file, which keeps the `compileOnlyApi` / `api` distinction
exactly. Maven consumers read the POM, where both appear with `compile` scope.

For now the only target is the local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Artifacts land in `~/.m2/repository/com/github/marcoral/versatia/VersatiaAPI/<version>/` and
`.../VersatiaAPI-ksp/<version>/`. A remote repository can be added later under
`publishing.repositories` without changing the publications.

## Configuration cache

`org.gradle.configuration-cache=true` is enabled in `gradle.properties`. The build scripts contain
no custom tasks, so there are no special rules to follow today. If tasks are added, read values
through providers and never capture script-level fields inside task lambdas.
