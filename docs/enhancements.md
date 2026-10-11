# Enhancements, the descriptor and dependency injection

This document describes the compile-time half of the framework: what a plugin author annotates,
what the `ksp` module generates from it, and the rules the generator enforces. The runtime half
lives in VersatiaCore and is documented there (`docs/runtime.md`).

## Vocabulary

- **Enhancement**: an annotation whose occurrences the framework acts on. It names the class that
  interprets it and the phase it runs at with `@VersatiaEnhancement` (root package); see
  [Phases](#phases). The annotation is repeatable: several processors can act on one occurrence,
  each producing its own element.
- **Processor**: an `EnhancementProcessor` (root package) that VersatiaCore loads by the class name
  the enhancement gives and calls with every recorded occurrence. Processors live in VersatiaCore,
  or in any plugin that the enhanced plugin depends on.
- **Attribute**: an annotation marked `@VersatiaAttribute` (root package). Wherever it appears on
  an enhanced class, its constructor parameters, an enhanced function or its parameters, the
  descriptor records it together with its arguments, so processors can read it. `@Qualifier` and
  `@Primary` are attributes.
- **Annotation inheritance**: an annotation class annotated with another annotation *inherits* it.
  The relation is transitive. When `@ExposePrototype` is annotated with `@Bean`, every class marked
  `@ExposePrototype` counts as marked `@Bean`. Plugin authors can define their own scopes this way:

  ```kotlin
  @Target(AnnotationTarget.CLASS)
  @Retention(AnnotationRetention.BINARY)
  @LocalBean
  annotation class Service
  ```

  `@Service` inherits `@LocalBean` and, through it, `@Bean` and `@VersatiaEnhancement`.
- **Descriptor**: the generated `VersatiaDescriptor` object (`engine.descriptor` package). It is placed in
  the package of the plugin's main class, the one named under `main:` in `plugin.yml`, so the
  runtime finds it by name without scanning anything.

Enhancements are the only extension mechanism of the framework: a feature is a set of
definitions (annotations, attributes, enums) that end up in the descriptor, plus a processor that
interprets them. VersatiaCore only reads the descriptor and hands each processor its slice; see
[architecture.md](architecture.md).

## `@VersatiaEnhancement`

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Bean
@VersatiaEnhancement(BeanProcessors.LOCAL, enhancementPhase = EventType.PLUGIN_START_ONLY)
annotation class LocalBean
```

- `processorClass` is the fully qualified name of the processor. A name, not a class: the plugin
  compiles against VersatiaAPI only, and the processor is in VersatiaCore or in another plugin.
  VersatiaAPI keeps the names of the built-in processors in `BeanProcessors` (`di`) and
  `LifecycleProcessors` (`lifecycle`). A mistyped name is found when the plugin enables, not at
  compile time.
- `enhancementPhase` or `enhancementPhaseParamRefName` say when the processor runs; see
  [Phases](#phases).

## Descriptor format

The descriptor is deliberately flat: a format version and a list of `EnhancedElement`s. Each
element carries

| Field            | Meaning                                                                   |
|------------------|---------------------------------------------------------------------------|
| `processorClass` | the processor that interprets it                                          |
| `phase`          | the `EventType` at which it runs; see [Phases](#phases)                   |
| `annotation`     | the enhancement annotation that produced it, with its arguments           |
| `target`         | what was annotated                                                        |
| `origin`         | the class the annotation is physically written on: the target's own class, a base class of the main class for inherited enhancements, the class carrying a `@VersatiaSubclassEnhancement` for the elements it produces |

The descriptor records facts about the annotated code, never what a feature makes of them. A
`ClassTarget` has the class name, its `assignableTypes` (itself plus every supertype except
`Any`), the attributes on the class, the constructor parameters (each a `Parameter`: type plus the
attributes on it) and an `Instantiator`. A `FunctionTarget` has the declaring class (Kotlin and JVM
names), the function name, who holds the instance (`FunctionOwner`: `PLUGIN`, `OBJECT` or
`INSTANCE` for a class whose instance the processor must obtain), the attributes on the function,
its parameters, an `Invoker` and the attributes on the declaring class (`classAnnotations`, for
what applies to a whole class). A `PluginTarget` describes the main class: name, assignable types
and attributes, nothing to construct. Bean names, `@Primary` and qualifiers are not fields: the DI
processor derives them from the recorded attributes.

An `AnnotationOccurrence` is the annotation's class name and its arguments, defaults included.
Argument values are strings, booleans, numbers, chars, enum entry names, class names and lists of
those; an attribute with an argument of another type (a nested annotation, say) is a compile error
at the place it is used.

No iteration order, grouping or phase structure is baked into the file; the runtime decides how to
walk it. Changing how VersatiaCore iterates therefore never requires regenerating plugins.

### Format version

The format has its own `major.minor` version (`VersatiaPluginDescriptor.FORMAT_VERSION`, currently
`1.0`), independent of the library version. A runtime accepts exactly the version it was built
with and rejects any other with a message naming the plugin, both versions and the fix. The
generator writes the two numbers as literal `Int` properties (`formatMajor`, `formatMinor`), so a
runtime can read them from any descriptor before deciding what to do with it; for the same reason
`elements` is a getter rather than an initialised field, so loading the descriptor class resolves
nothing the elements refer to.

### Direct access versus reflection

Encapsulation is preserved: beans, their constructors, functions and their parameter types may be
`private`. The generator picks per element:

- **`Instantiator.Direct` / `Invoker.Direct`** when the class, the member and every parameter type
  are `public` or `internal`. The generated code contains the call itself:
  `Instantiator.Direct { args -> Spy(args[0] as Secret) }`. No reflection, no `setAccessible`.
- **`Instantiator.Reflective` / `Invoker.Reflective`** otherwise. They store JVM binary names
  (`com.example.Outer$Hidden`) and at runtime load the class **without initialising it**
  (`Class.forName(name, false, loader)`), find the matching declared member and make it accessible.
- A Kotlin `object` enhanced through a subclass enhancement gets `Instantiator.Direct { _ -> Console }`
  when it is reachable and `Instantiator.ObjectInstance(binaryName)`, which reads its `INSTANCE`
  field, otherwise.

## Phases

`@VersatiaEnhancement` must say when its processor runs, in exactly one of two ways:

```kotlin
// Fixed for every occurrence
@VersatiaEnhancement(BeanProcessors.LOCAL, enhancementPhase = EventType.PLUGIN_START_ONLY)
annotation class LocalBean

// Chosen per occurrence through a property of the annotation
@VersatiaEnhancement(LifecycleProcessors.AUTO_INVOKE, enhancementPhaseParamRefName = "phase")
annotation class AutoInvoke(val phase: EventType)

@AutoInvoke(EventType.PLUGIN_RELOAD_OR_START)
fun start()
```

`EventType._NULL` and `""` are the defaults and mean "not given"; annotations cannot hold `null`.
With `enhancementPhaseParamRefName`, the named property must exist on the annotated annotation and
be of type `EventType`, and every occurrence must set it to something other than `_NULL`. When the
scope is inherited (`@Service` inherits `@LocalBean`), the value is read from the occurrence that
links the two, that is from the `@LocalBean(...)` written on the declaration of `Service`.

The processor stops the build when a `@VersatiaEnhancement` sets both or neither, names a property
the annotation does not declare, names one of another type, leaves `processorClass` empty, or an
occurrence leaves the phase at `_NULL`. Errors point at the annotation declaration when it is in
the sources, otherwise at the class using it.

| `EventType`              | Runs at                          |
|--------------------------|----------------------------------|
| `PLUGIN_START_ONLY`      | plugin enable                    |
| `PLUGIN_RELOAD_ONLY`     | `/versatia reload`, `VersatiaEngine.reload` |
| `PLUGIN_RELOAD_OR_START` | both of the above                |
| `PLUGIN_STOP`            | plugin disable                   |

How the runtime maps these to lifecycle events is described in VersatiaCore's `docs/runtime.md`.

## Processors

```kotlin
interface EnhancementProcessor {
    fun process(elements: List<EnhancedElement>, context: EnhancementContext)
}
```

The runtime loads the class named by `processorClass` through the enhanced plugin's class loader,
creates one instance per class (no-argument constructor, or the instance of a Kotlin `object`) and
keeps it. A processor receives, per plugin and per lifecycle event, the elements naming it whose
phase applies. The context carries the `Plugin`, the event, `onPluginEnhanced` for work that must
wait until every processor has seen the plugin, and two `StateStore`s: `state`, kept per plugin
across events and dropped when the plugin is disabled, and `runtimeState`, kept as long as
VersatiaCore runs, for what spans plugins. Entries implementing `AutoCloseable` are closed when
their store is dropped. These stores are the only way processors share anything: the DI processors
publish the plugin's beans in `state` under `BeanResolver.KEY` and keep the registry of exposed
beans in `runtimeState`; the auto-invoke processor, or any processor of any plugin, injects by
reading `context.state[BeanResolver.KEY]`.

## Dependency injection

Four annotations in `com.github.marcoral.versatia.di`:

| Annotation          | Target           | Meaning                                                              |
|---------------------|------------------|----------------------------------------------------------------------|
| `@Bean`             | annotation class | marks an annotation as a bean *scope*; never applied to a class       |
| `@LocalBean`        | class            | injectable only inside the plugin that declares it                   |
| `@ExposeSingleton`  | class            | one instance, shared with every plugin loaded afterwards             |
| `@ExposePrototype`  | class            | a fresh instance for each later plugin that depends on it; a plugin-typed parameter receives that plugin |

Each scope inherits `@Bean` and names its processor: `LocalBeanEnhancementProcessor`,
`ExposeSingletonEnhancementProcessor`, `ExposePrototypeEnhancementProcessor` in
`com.github.marcoral.versatia.di` of VersatiaCore (`BeanProcessors`). All three run at
`PLUGIN_START_ONLY`, and a scope that sends one of them anything else is a compile error: beans
can only be declared when the plugin starts.

`BeanResolver` (`di`) is the contract through which any processor injects: `resolve` turns a
function's `Parameter`s into arguments with the rules below, `instanceOf` returns the bean of a
class. The DI processors publish it in the plugin state under `BeanResolver.KEY`, and
`@AutoInvoke` is its first client; a processor shipped by another plugin uses it the same way.

Beans use constructor injection only. A dependency is matched against the *assignable types* of
the candidate beans, so a parameter may be typed by an interface the bean implements.

### The plugin itself

The plugin's main class is a local bean through `@PluginBean`, which `VersatiaPlugin` carries
(see [Enhancements of the main class](#enhancements-of-the-main-class)). Bukkit creates the
instance; the runtime registers it in the plugin's bean session before anything else is created,
so beans and auto-invoked functions can ask for it by its own type or by any supertype
(`VersatiaPlugin`, `JavaPlugin`, `Plugin`):

```kotlin
@LocalBean
class Storage(plugin: JavaPlugin) {
    val folder = plugin.dataFolder
}
```

Its bean name is always `plugin` (`BeanNames.PLUGIN`), whatever the class is called, so
`@Qualifier("plugin")` selects it and no other bean of the plugin may take that name
(`Bean name "plugin" is reserved for the plugin's main class; rename X with @Qualifier`).
`@Primary` on the main class works like on any bean; `@Qualifier` on it is a compile error, as is
a scope annotation (`MyPlugin is the plugin's main class and a local bean already; remove
@LocalBean`), since the runtime must never construct a second plugin instance. The descriptor
records the main class as a `PluginTarget` sent to `PluginBeanEnhancementProcessor`
(`BeanProcessors.PLUGIN`), which declares it in the same session as the scopes do. A main class
that extends `JavaPlugin` directly is a bean only if it carries `@PluginBean` itself; without it the
name `plugin` is free.

### Names, `@Qualifier` and `@Primary`

Every bean has a name, unique within its plugin. By default it is the simple class name with a
lower-case first letter (`FriendlyGreeter` becomes `friendlyGreeter`, `Settings.MessageFormat`
becomes `messageFormat`; `BeanNames.defaultFor`); `@Qualifier("grumpy")` on the class replaces it.
Nothing has to be declared as long as every requested type has a single candidate.

When several beans provide a requested type, two annotations decide:

- `@Qualifier("name")` on the constructor or `@AutoInvoke` parameter asks for the bean with that
  name;
- `@Primary` on one of the candidate classes makes it the default for parameters without a
  qualifier.

```kotlin
@LocalBean @Primary
class FriendlyGreeter : Greeter

@LocalBean @Qualifier("grumpy")
class UnfriendlyGreeter : Greeter

@LocalBean
class Banner(greeter: Greeter)                               // FriendlyGreeter, the @Primary

@LocalBean
class ComplaintDesk(@Qualifier("grumpy") greeter: Greeter)   // UnfriendlyGreeter, by name
```

Resolution of a parameter of type `T`, the same at compile time (local beans) and at runtime
(exposed beans of other plugins):

1. Take the local beans providing `T`; with a qualifier, only the one named so.
2. If that set is empty, take the exposed beans providing `T`, again filtered by the qualifier.
3. One candidate wins. Among several, a single `@Primary` wins. Otherwise it is an error listing
   the candidates by name and class.

Because the qualifier filters before the local-first preference applies, a `@Qualifier` can
deliberately pick an exposed bean even though a local bean of the same type exists. Without a
qualifier a local bean always wins. Names are not a global registry: two plugins may expose beans
with the same name, and that only matters when both provide the type somebody asks for, which is
then reported like any other ambiguity.

Example of a plugin:

```kotlin
class AlphaPlugin : VersatiaPlugin()

interface Greeter

@ExposeSingleton
class Clock

@ExposePrototype
class Ticket(val clock: Clock)

@LocalBean
internal class ConsoleGreeter(val clock: Clock) : Greeter

@LocalBean
private class HiddenAudit(@Qualifier("consoleGreeter") val greeter: Greeter, val ticket: Ticket)
```

Generated for it (shortened):

```kotlin
object VersatiaDescriptor : VersatiaPluginDescriptor {
  override val formatMajor: Int = 1
  override val formatMinor: Int = 0

  override val elements: List<EnhancedElement>
    get() = listOf(
      EnhancedElement(
        processorClass = "com.github.marcoral.versatia.di.PluginBeanEnhancementProcessor",
        phase = EventType.PLUGIN_START_ONLY,
        annotation = AnnotationOccurrence("com.github.marcoral.versatia.di.PluginBean"),
        target = PluginTarget(
          className = "com.example.alpha.AlphaPlugin",
          assignableTypes = listOf("com.example.alpha.AlphaPlugin", "com.github.marcoral.versatia.VersatiaPlugin", "org.bukkit.plugin.java.JavaPlugin", "org.bukkit.plugin.Plugin", /* ... */),
          annotations = emptyList(),
        ),
      ),
      EnhancedElement(
        processorClass = "com.github.marcoral.versatia.resources.ExtractResourcesEnhancementProcessor",
        phase = EventType.PLUGIN_START_ONLY,
        annotation = AnnotationOccurrence("com.github.marcoral.versatia.resources.ExtractResources"),
        target = PluginTarget(/* the same */),
      ),
      EnhancedElement(
        processorClass = "com.github.marcoral.versatia.di.LocalBeanEnhancementProcessor",
        phase = EventType.PLUGIN_START_ONLY,
        annotation = AnnotationOccurrence("com.github.marcoral.versatia.di.LocalBean"),
        target = ClassTarget(
          className = "com.example.alpha.ConsoleGreeter",
          assignableTypes = listOf("com.example.alpha.ConsoleGreeter", "com.example.alpha.Greeter"),
          annotations = emptyList(),
          constructorParameters = listOf(Parameter("com.example.alpha.Clock")),
          instantiator = Instantiator.Direct { args -> ConsoleGreeter(args[0] as Clock) },
        ),
      ),
      EnhancedElement(
        processorClass = "com.github.marcoral.versatia.di.LocalBeanEnhancementProcessor",
        phase = EventType.PLUGIN_START_ONLY,
        annotation = AnnotationOccurrence("com.github.marcoral.versatia.di.LocalBean"),
        target = ClassTarget(
          className = "com.example.alpha.HiddenAudit",
          assignableTypes = listOf("com.example.alpha.HiddenAudit"),
          annotations = emptyList(),
          constructorParameters = listOf(
            Parameter("com.example.alpha.Greeter", listOf(AnnotationOccurrence("com.github.marcoral.versatia.di.Qualifier", mapOf("value" to "consoleGreeter")))),
            Parameter("com.example.alpha.Ticket"),
          ),
          instantiator = Instantiator.Reflective("com.example.alpha.HiddenAudit", listOf("com.example.alpha.Greeter", "com.example.alpha.Ticket")),
        ),
      ),
      // Clock, Ticket ...
    )
}
```

### Compile-time checks

The processor reports these as compiler errors pointing at the offending declaration, and
generates nothing when any of them fires:

- a dependency cycle among the plugin's beans: `Dependency cycle: Alpha -> Beta -> Alpha`;
- a dependency type provided by more than one bean of the same plugin, unless a `@Qualifier` or a
  single `@Primary` decides;
- two beans of the plugin with the same name, a bean named `plugin` (reserved for the main class),
  a `@Qualifier` naming a bean that does not provide the requested type, or `@Primary`/`@Qualifier`
  on a class that is not a bean;
- a class with more than one scope annotation, or a scope annotation or `@Qualifier` on the
  plugin's main class;
- a bean that is abstract, an `object`, an interface, an inner class, or generic;
- a bean without exactly one constructor;
- a nullable, generic, vararg or type-parameter dependency;
- a prototype whose plugin parameter is not `VersatiaPlugin` or a supertype: it receives the
  consuming plugin, so the owner's own class would not fit every consumer;
- an attribute argument the descriptor cannot hold;
- a plugin enhancement or a `@DisableVersatiaFeatures` on a class that is not a `JavaPlugin`
  subclass, a switch that turns nothing off, or a class that both carries and switches off the
  same enhancement;
- a class matched by a subclass enhancement that is inner, generic or has more than one
  constructor, or whose constructor parameters are not injectable;
- a scope annotation that inherits `@Bean` but no `@VersatiaEnhancement`, or one that sends a
  bean processor anything but `PLUGIN_START_ONLY`;
- a `@VersatiaEnhancement` without `processorClass` or whose phase is not declared exactly once
  (see [Phases](#phases));
- `@FriendlyName` off the main class or not one word, and `@ConditionalOnPlugin` where it would
  have no effect (see [Conditional elements](#conditional-elements)).

A dependency that no local bean provides is **not** an error: it is expected to come from a bean
exposed by a plugin loaded earlier, and the runtime reports it if it does not.

## Auto-invoked functions

`@AutoInvoke(phase)` (`com.github.marcoral.versatia.lifecycle`) marks a function the runtime calls
at the given phase. It replaces overriding `onEnable`/`onDisable`; `VersatiaPlugin` leaves nothing
to override.

```kotlin
class MyPlugin : VersatiaPlugin() {
    @AutoInvoke(EventType.PLUGIN_RELOAD_OR_START)
    private fun start(clock: Clock) { ... }

    @AutoInvoke(EventType.PLUGIN_STOP)
    fun stop() { ... }
}
```

- The function may belong to the plugin's main class, to a Kotlin `object`, or to a bean. Anything
  else is a compile error: the runtime would have no instance to call it on.
- Parameters are injected exactly like constructor dependencies of a bean (non-nullable,
  non-generic class types; local beans first, then exposed ones; the plugin instance included).
  Ambiguity is a compile error, an unknown type is left to the runtime.
- Extension, suspend, abstract and generic functions are rejected. Return values are ignored.
- Visibility does not matter: a private function or class gets an `Invoker.Reflective`, a visible
  one a direct call such as `Invoker.Direct { instance, args -> (instance as MyPlugin).start(args[0] as Clock) }`.

The element's target is a `FunctionTarget` with the owner kind (`PLUGIN`, `OBJECT`, `INSTANCE`),
the parameters and the invoker; `INSTANCE` means a bean, and `AutoInvokeEnhancementProcessor`
fetches it through the plugin's `BeanResolver`. Functions run after every other processor of the event, in descriptor
order (class name, then function name), and a plugin's beans stay available for its whole
lifetime, so reload- and stop-phase functions can use them too.

## Enhancements of the main class

Class enhancements written on the main class, or on any of its supertypes, produce one
`PluginTarget` element each. `VersatiaPlugin` carries `@PluginBean` and `@ExtractResources`, which
is how every Versatia plugin gets its bean container and its resources without declaring anything;
an abstract base plugin in a library can add enhancements for the plugins extending it the same
way. The `PluginTarget` is the same for all of them: class name, assignable types and attributes,
nothing to construct. A processor that expects the main class accepts only that target and only
once per plugin.

`PluginCollector` walks the lineage nearest first (the main class, its superclasses, then the
interfaces) and records the first occurrence of each enhancement annotation, so a subclass
overrides a base class's arguments. `@DisableVersatiaFeatures` switches a feature off for the
classes below the one carrying it: each of its `Boolean` parameters is marked
`@VersatiaDisabler(Enhancement::class)`, and a parameter set to `true` leaves that enhancement
out, unless a class nearer to the main class declares it again. The collector knows no feature;
it reads the mapping from the parameter annotations, so a library can define a switch for its own
base-class enhancements the same way. Plugin enhancements and switches are accepted only on
`JavaPlugin` subclasses: elsewhere they would do nothing, so they are compile errors, as is a
switch that turns nothing off and a class that both carries and switches off an enhancement.

## Subclass enhancements

`@VersatiaSubclassEnhancement(processorClass, subclassOf, enhancementPhase)` is the second kind of
enhancement declaration, next to `@VersatiaEnhancement`. Written on an annotation class, it makes
every class in the plugin's sources that is assignable to `subclassOf` an element, as if the
annotation had been written on that class: concrete classes with one injectable constructor become
`ClassTarget`s like beans (validated the same way, dependencies checked by the bean graph
validator), objects become `ClassTarget`s with an object instantiator, the main class becomes its
`PluginTarget`; abstract classes and interfaces are skipped. The annotation carrying it is written
on the main class or on a base class of it, follows the lineage rules above (nearest declaration,
`@DisableVersatiaFeatures`), and is recorded as each element's `origin`:

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@VersatiaSubclassEnhancement("com.example.audit.AuditEnhancementProcessor", Auditable::class, EventType.PLUGIN_START_ONLY)
annotation class AuditSubclasses

@AuditSubclasses
abstract class AuditedPlugin : VersatiaPlugin()
```

The `ksp.SubclassCollector` applies the rules `PluginCollector` found; it knows no feature. A class
matched by a subclass enhancement that is also a bean produces both elements, so a processor that
needs the instance should ask the `BeanResolver` first and instantiate only when there is no bean.

## Event listeners

`@RegisterListeners` (`event`), declared on `VersatiaPlugin`, is a subclass enhancement for
Bukkit's `Listener`: every class of the plugin implementing it is recorded as an element for
`RegisterListenersEnhancementProcessor` (`EventProcessors.REGISTER_LISTENERS`) at
`PLUGIN_START_ONLY`, with `VersatiaPlugin` as the origin. A listener that is a bean is registered
with its bean instance; a plain listener class is created like a bean, with its constructor
parameters injected through the `BeanResolver`, but is not put into the container; objects and the
main class are registered as themselves. The generator lists subclass elements before function
elements, so listeners are registered before `@AutoInvoke` start functions run. Bukkit
unregisters the listeners when the plugin is disabled. `@DisableVersatiaFeatures(registerListeners = true)`
switches the feature off.

## Plugin resources

`@ExtractResources` (`resources`), declared on `VersatiaPlugin`, copies every file under the
`resources` directory of the plugin's JAR (`src/main/resources/resources/` in the build) into the
plugin's data folder at `PLUGIN_START_ONLY`, keeping the layout below that directory. A file that
already exists is never replaced, so edits made on the server survive an update; deleting a file
and restarting the plugin restores it. The processor runs immediately, before the beans are
created, so a bean may read the files in its constructor. The directory name is fixed
(`ExtractResources.DIRECTORY`); other files in `src/main/resources`, such as `plugin.yml`, stay in
the JAR. The processor is `ExtractResourcesEnhancementProcessor` (`ResourceProcessors.EXTRACT`) in
VersatiaCore.

## Friendly names

`@FriendlyName("shop")` (root package) is an attribute for the main class: the runtime registers
the plugin under that name instead of the `name:` of `plugin.yml`, and `/versatia reload <name>`
addresses it so. The descriptor records it on the `PluginTarget` like any attribute; the runtime
reads it from there when the plugin starts. `AttributePlacement` rejects it on any other class and
a name that is not one word. `VersatiaPlugin.versatiaReload()` runs the plugin's own reload event
through the engine, so plugin code never looks the service up itself; where the engine is needed
directly, `VersatiaEngine.instance` (`getInstance()` from Java) is the one lookup. `PluginRegistry` (root
package) is the read side: the running plugins
by friendly name, `friendlyNames`, `friendlyNameOf(plugin)` and `get(name)`; VersatiaCore
implements it, returns it from `VersatiaEngine.registry` and exposes it as a singleton bean.

## Conditional elements

`@ConditionalOnPlugin("Vault")` (root package) is an attribute on a bean or listener class, on an
`object` or class declaring enhanced functions, or on an enhanced function. The runtime dispatches
the element only while every named plugin is enabled; a function element carries the attributes of
its declaring class in `classAnnotations`, so the condition on a class or object covers its
functions. The class behind a skipped element is never loaded, so it may reference the optional
plugin's classes freely. `AttributePlacement` rejects the attribute on the main class, on a class
that is neither an element nor declares an enhanced function, on a function without an
enhancement, and with no plugin named. `VersatiaEngine.reload(plugin)` is the
third operation of the engine, next to `enhance` and `disable`.

## Setting up a plugin build

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    id("com.google.devtools.ksp") version "2.3.12"
}

dependencies {
    implementation("com.github.marcoral.versatia:VersatiaAPI:0.0.1")
    ksp("com.github.marcoral.versatia:VersatiaAPI-ksp:0.0.1")
}
```

That is all. The processor locates the main class by itself: the single concrete subclass of
`JavaPlugin` in the sources. KSP cannot read resources, so `plugin.yml` is not consulted; the
class the processor finds is the one `main:` must name anyway, otherwise Bukkit would not load the
plugin. Only when the sources contain no such class or several of them does the processor stop with
an error that asks for the explicit form:

```kotlin
ksp {
    arg("versatia.pluginYml", layout.projectDirectory.file("src/main/resources/plugin.yml").asFile.path)
}
```

With the option set, `main:` is read from that file and the sources are not searched; the named
class must still exist in the sources or on the classpath, because the descriptor describes it.

The plugin's main class extends `VersatiaPlugin` and `plugin.yml` lists `VersatiaCore` under
`depend:`, so the runtime is available when the plugin enables.

`plugin.yml` must **not** list `kotlin-stdlib` (or `kotlin-reflect`) under `libraries:`. Paper
loads each plugin's libraries into a separate class loader, so a plugin with its own copy of Kotlin
would exchange `kotlin.jvm.functions.Function1` instances with VersatiaCore across two different
`Function1` classes, and the JVM rejects that with a `LinkageError`. VersatiaCore is the single
provider of the Kotlin runtime; dependents reach it through `depend:`.

## How the `ksp` module works

The module mirrors the feature packages of the API: `ksp` holds what every feature needs,
`ksp.di` and `ksp.lifecycle` the collectors and checks of one feature each.

1. `VersatiaSymbolProcessor` runs once per compilation. `MainClassLocator` finds the plugin's main
   class (or reads it from `plugin.yml` when the option is set). `EnhancementResolver` resolves
   the enhancements an annotation inherits; `Attributes` records attribute occurrences and turns
   annotation arguments into descriptor values; `PluginCollector` gathers the enhancement
   annotations on the main class and its supertypes, applies the switches of
   `@DisableVersatiaFeatures` and checks where both are used; `SubclassCollector` turns the
   subclass enhancements among them into elements for the matching source classes.
2. `di.BeanCollector` walks every class declaration in the sources (compile-time scanning is fine,
   runtime scanning is not) and, for each annotation on a class, checks whether it inherits
   `@Bean`. Hits become `BeanDeclaration`s: type, name, scope occurrence, enhancements, attributes,
   parameters, assignable types, reachability. The main class becomes a `PluginDeclaration`
   carrying the enhancements `PluginCollector` found.
3. `lifecycle.FunctionCollector` collects functions annotated with an enhancement and checks their
   owner and parameters; the injection rules it applies come from `ksp.di`.
4. `di.BeanGraphValidator` is pure Kotlin: names, ambiguity and cycle detection over the
   declarations, the plugin included as a dependency-free local bean when it has `@PluginBean`.
5. `model.DescriptorGenerator` builds the file with KotlinPoet. It is also pure Kotlin, so its
   output is unit-tested as text.
6. The file is written through KSP's `CodeGenerator` as an aggregating output depending on all
   source files.

Tests in `ksp/src/test` cover the pure parts directly and the whole processor through
`kotlin-compile-testing` (`dev.zacsweers.kctfork`), which compiles sample plugin sources with KSP
in-process and lets the test load the generated descriptor.
