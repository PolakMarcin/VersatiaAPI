# Architecture

Versatia is split into two projects with independent builds and repositories:

| Project          | Kind            | Role                                                                 |
|------------------|-----------------|----------------------------------------------------------------------|
| VersatiaAPI  | library         | Public API, syntax sugar and the compile-time code generation step   |
| VersatiaCore     | server plugin   | Framework runtime; interprets the generated output on the server     |

Plugins built on Versatia compile against VersatiaAPI only. VersatiaCore runs on the server next
to them and provides the behaviour behind the API.

## Mission

Remove as much Bukkit boilerplate from plugins as possible: registering listeners, registering
scheduled tasks, unpacking bundled resources, wiring singletons and passing dependencies around by
hand. A plugin should declare *what* it has; the framework takes care of *when* and *how* it is
hooked into the server.

## Compile time versus runtime

The central design rule is that **nothing is discovered at runtime**. There is no classpath
scanning and no reflection-based lookup of annotated classes while the server is running.

1. **Compile time (VersatiaAPI).** A code generation step runs as part of the plugin's build.
   It inspects the plugin's sources and emits a descriptor, a file with "hints" bundled into the
   plugin JAR, describing everything the framework has to act on: listeners to register, tasks to
   start, components to construct and inject, resources to unpack, and so on. It may also emit
   plain Kotlin code (factories, wiring) so the runtime has nothing to figure out on its own.
2. **Runtime (VersatiaCore).** At a few well-defined lifecycle points the runtime reads the
   descriptor of each Versatia plugin and executes it: plugin load and enable, reload, plugin
   disable and server shutdown. Between those points the framework does not need the descriptor.

The descriptor format is the compatibility contract between the two projects. VersatiaAPI
defines what is generated, VersatiaCore must be able to read it.

## Features built on this mechanism

- Automatic listener registration.
- Automatic scheduling of recurring and delayed tasks.
- Dependency injection driven entirely by the generated descriptor.
- Resource unpacking and other setup that today is repeated in every plugin's `onEnable`.

## Enhancements and enhancement processors

The framework does not hardcode what any annotation means. An annotation such as `@LocalBean` is
an *enhancement*: the compile-time step only records the context in which it occurs (the annotated
element and the annotation) in the descriptor. The behaviour lives in an **EnhancementProcessor**,
a class that the runtime invokes with every recorded occurrence carrying its id.

The annotation itself points at its processor and says when it is processed:

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Bean
@VersatiaEnhancement(BeanProcessors.LOCAL, enhancementPhase = Phase.PLUGIN_START_ONLY)
annotation class LocalBean
```

- `processorClass` is the fully qualified name of the processor. The runtime loads it by that
  name through the enhanced plugin's class loader, so the class may live in VersatiaCore or in any
  plugin the enhanced one depends on; there is no list of processors anywhere.
- `enhancementPhase`, or a property of the annotation named by `enhancementPhaseParamRefName`,
  selects the lifecycle event(s) at which the processor runs.
- Annotations marked `@VersatiaAttribute` (`@Qualifier`, `@Primary`) are recorded with their
  arguments wherever they decorate an enhanced element, so a processor gets its facts from the
  descriptor and the descriptor itself stays feature-neutral.
- Processors share per-plugin state only through `EnhancementContext.state`, which the runtime
  drops (closing `AutoCloseable` entries) when the plugin is disabled.

### Enhancements are the only extension mechanism

Enhancements are the most powerful mechanism of the framework and the starting point of every
other feature: a feature is a set of definitions that end up in the descriptor and a processor that
interprets them. Dependency injection and auto-invoked functions are built this way and nothing
else is special about them. This fixes the division of labour:

- **VersatiaCore is deliberately simple.** It reads a plugin's descriptor, hands every processor
  the slice of elements carrying its id, and lets the processor analyse that slice its own way and
  do all the work. The pipeline knows nothing about any particular feature.
- **A new feature adds only definitions to VersatiaAPI** (annotations, enums, processor ids) **and
  only processors to VersatiaCore.** Nothing else changes: not the loader, not the dispatcher,
  not the main class.
- **Packages are organised by feature**, as if API, Core and the compile-time step were slices of
  one monolith: a feature package holds the feature's definitions in VersatiaAPI and its processors
  in VersatiaCore. The enhancement mechanism and the plugin bootstrap (`VersatiaPlugin`,
  `VersatiaEngine`) are elementary and belong to the root package `com.github.marcoral.versatia`;
  there are no packages named after layers. The descriptor is a technical detail of the
  mechanism, not a feature.

## Decisions taken

- Code generation uses **KSP** (Kotlin Symbol Processing) with **KotlinPoet** for emitting the
  Kotlin source. The processor is the `ksp` module of VersatiaAPI.
- The descriptor is a generated `object VersatiaDescriptor` placed in the package of the plugin's
  main class (the `main:` entry of `plugin.yml`), so the runtime loads it by name.
- The descriptor is a flat list of elements with no iteration strategy baked in; the runtime
  groups and orders them.
- Encapsulation is preserved: generated code calls visible constructors directly and falls back to
  reflection for private ones, never initialising classes as a side effect.
- The first enhancement built on this is dependency injection (`@LocalBean`, `@ExposeSingleton`,
  `@ExposePrototype`); bean graphs are validated at compile time, including cycle detection.

Details: [enhancements.md](enhancements.md). The helper packages are described in [api.md](api.md).
