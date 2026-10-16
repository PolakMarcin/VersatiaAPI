# Library API

VersatiaAPI is a public library, but the compiler's explicit API mode is deliberately *not*
enabled: it would demand a `public` modifier and an explicit return type on every exported
declaration, and the code base writes neither where the language does not need them (see the Best
practices page of the documentation site). Visibility therefore reads the Kotlin way: no modifier
means exported, and everything that is not part of the contract is marked `internal` or `private`.
A return type is written only where inference would do harm: where the body calls a Java method
and the inferred type would be a platform type (`getColorOrThrow`, `callSyncMethod`), where the
function is recursive, or where the compiler would otherwise expose an `internal` type.

## Packages

The `api` module is organised by feature, as if it were a slice of one monolith shared with
VersatiaCore: a feature package holds the feature's definitions here and its processors there.
The mechanism everything is built on sits in the root package; `engine` holds technical internals
that deliver no feature of their own but are needed for the framework to work.

| Package                  | Contents                                                                  |
|--------------------------|---------------------------------------------------------------------------|
| `com.github.marcoral.versatia` | the enhancement mechanism (`@VersatiaEnhancement`, `@VersatiaAttribute`, `@VersatiaSubclassEnhancement`, `EnhancementProcessor`, `EnhancementContext` with its `classLoader` extension and the processor helpers `targetAs`, `receiverOf`, `inject`, `failure`, `StateStore`, `StateKey`, `Phase`, `LifecycleEvent`, `EnhancementException`, `@VersatiaDisabler`, `@VersatiaInternalApi`), the plugin bootstrap (`VersatiaPlugin`, `VersatiaEngine`, `@DisableVersatiaFeatures`) and the attributes the runtime reads itself (`@ConditionalOnPlugin`, `@FriendlyName`, with `PluginRegistry` reading friendly names back); the static shortcuts on `VersatiaEngine` (`VersatiaEngineExtensions.kt`) and `ServiceNotRegisteredException` |
| `engine.descriptor`      | Model of the generated descriptor: `VersatiaPluginDescriptor`, `EnhancedElement`, `AnnotationOccurrence`, `ClassTarget`, `FunctionTarget`, `PropertyTarget`, `PluginTarget`, `MemberOwner`, `Parameter`, `TypeReference`, `Instantiator` with `DefaultArgument`, `Invoker`, `Setter`, `DescriptorVersion` |
| `di`                     | Bean scopes: `@Bean`, `@LocalBean`, `@ExposeSingleton`, `@ExposePrototype`, `@PluginBean`; `@Qualifier`, `@Primary`; `Reloadable`, beans reloaded with their plugin; `BeanResolver`, the injection contract for processors; `BeanProcessors`, `BeanNames` |
| `lifecycle`              | `@AutoInvoke`, functions called at a lifecycle phase; `LifecycleProcessors` |
| `config`                 | `@Value`, properties set from the plugin's YAML files at start and reload; `ValueTypes`, the types it reads by itself; `ValueMapper` and `NoMapper` for any other, `SectionMapper` for one written as a section; `PreviousValueTracking`, a cell keeping the value it replaced; `ConfigProcessors` |
| `resources`              | `@ExtractResources`, files shipped in the JAR extracted to the data folder; `ResourceProcessors` |
| `event`                  | `@RegisterListeners`, Bukkit listeners registered at start; `EventProcessors` |
| `util.*`                 | Independent helpers, described below                                      |

The framework packages are documented in [enhancements.md](enhancements.md). All helpers live
under `com.github.marcoral.versatia.util`, grouped into sub-packages by the domain they touch, not
by the kind of helper they are. Package names below are relative to `util`.

| Package        | Contents                                                                                 |
|----------------|------------------------------------------------------------------------------------------|
| `collection`   | `addOrThrow`/`putAllOrThrow`-style helpers for sets and maps, `DeepMergeMap`              |
| `config`       | Strict typed getters for `ConfigurationSection`, YAML file traversal                     |
| `inventory`    | `ItemStack`/`Inventory` helpers, `PlayerInventory` indices of the equipment slots         |
| `lang`         | Enum lookups ignoring case                                                                |
| `plugin`       | `Plugin.log`, `logWarning`, `logSevere` shorthands over the plugin logger                 |
| `reflect`      | Access to private members through `kotlin-reflect`                                        |
| `scheduler`    | `Plugin.runTask*` wrappers over `BukkitScheduler`                                         |
| `text`         | Subscript/superscript digits                                                             |
| `type`         | Small value type `Percent`                                                                |
| `validation`   | `validateAtLeast`-style guards that return the receiver                                   |
| `world`        | `Location` helpers                                                                        |

Files are named `<Subject>Extensions.kt` when they hold extension functions and after the type
when they hold a class. `util` holds helpers on objects a caller holds and pure functions; it never
imports the root package.

## Internal plumbing (`@VersatiaInternalApi`)

Some declarations are public only because the compile-time step, the generated descriptor and
VersatiaCore live in other modules than the API. They carry `@VersatiaInternalApi`, a
`@RequiresOptIn` marker of level `WARNING` in the root package, so a plugin that uses one gets a
compiler warning saying it is internal plumbing that may change without notice:

| Declaration                                                         | Used by                                   |
|---------------------------------------------------------------------|-------------------------------------------|
| `BeanProcessors`, `LifecycleProcessors`, `ConfigProcessors`, `ResourceProcessors`, `EventProcessors` | the annotations declaring the built-in enhancements, the `ksp` module, VersatiaCore |
| `ValueTypes`                                                        | the `@Value` checks in `ksp` and the value processor |
| `VersatiaPluginDescriptor`, `DescriptorVersion`                     | the generated descriptor, the descriptor loader |
| `Instantiator.Direct`/`Reflective`/`WithDefaults`/`ObjectInstance`, `Invoker.Direct`/`Reflective`, `Setter.Direct`/`Reflective` | the generated descriptor; processors call `create`, `invoke` and `set` on the interfaces |
| `BeanResolver.supply`, `onInstanceCreated`, `instancesOf`           | the `@Value` processor and the bean container |
| `VersatiaEngine.enhance`, `disable`                                 | `VersatiaPlugin.onEnable` and `onDisable`  |
| `Phase.appliesTo`                                                   | the runtime's dispatch of elements        |

What a custom `EnhancementProcessor` needs stays unmarked: `EnhancedElement` and its targets,
`AnnotationOccurrence`, `Parameter`, `TypeReference`, `MemberOwner`, `DefaultArgument`, the
`Instantiator`, `Invoker` and `Setter` interfaces, `BeanResolver.resolve` and `instanceOf`,
`StateStore`, and the helpers `targetAs`, `receiverOf`, `inject` and `failure` (see
[enhancements.md](enhancements.md#helpers-for-processors)). Mark a new declaration only when nothing outside the framework has a reason to touch
it, and never one a custom processor needs: the marker also covers every declaration whose
signature names a marked type.

The `api` and `ksp` modules, VersatiaCore and module implementations such as
VersatiaModule-MessagesImpl opt in for the whole module
(`optIn.add("com.github.marcoral.versatia.VersatiaInternalApi")` in `kotlin.compilerOptions`).
The generated descriptor opts in for its own file with `@file:OptIn(VersatiaInternalApi::class)`,
so plugins compile without warnings and without opting in themselves.

## `VersatiaEngine` shortcuts

Server-wide work that needs no object at hand is an extension on `VersatiaEngine`'s companion,
declared in `VersatiaEngineExtensions.kt`, so a plugin author remembers one class:
`VersatiaEngine.broadcast(message)`, `VersatiaEngine.playerOrNull("Notch")`,
`VersatiaEngine.loadService<Economy>()`, `VersatiaEngine.reload("shop")`. A member belongs there only
when it needs no receiver (scheduling needs the plugin, so it stays `plugin.runTaskLater`), is
routine, is at least as good as the Bukkit call it replaces, and delegates in one expression to
Bukkit or to `VersatiaEngine.instance`; logic goes into a type that runs without a server, such as
`PluginRegistry.getOrThrow`. The helper a shortcut replaces is moved, never kept as an alias.

Names use Bukkit's term without `get`; a lookup that can miss ends in `OrNull`, a bare name never
misses or throws an exception naming the fix; argument-less state that does not depend on the
calling thread is a property (`onlinePlayers`, `registry`), anything else a function
(`isPrimaryThread()`). The shortcuts are Kotlin-only: extensions on a companion compile to static
methods of `VersatiaEngineExtensionsKt` taking the companion, and Java keeps
`VersatiaEngine.getInstance()` plus Bukkit. `registry` and `reload` need VersatiaCore enabled; the
others call Bukkit and work in any plugin.

## Configuration getters (`util.config`)

Bukkit's own getters are lenient: `getInt` returns `0` for a missing key *and* for a value that is
not a number, so a typo in a config file turns into a silent default. The helpers in
`ConfigurationExtensions.kt` separate the two cases:

| Family                   | Key absent            | Value present but invalid           |
|--------------------------|-----------------------|-------------------------------------|
| `getXOrNull(key)`        | `null`                | throws `ConfigurationValueException` |
| `getX(key, default)`     | `default`             | throws                              |
| `getXOrThrow(key, msg?)` | throws                | throws                              |
| `isX(key)`               | `false`               | `false`                             |
| `ifXPresent(key) { }`    | action not run        | throws                              |
| `getXList(key)`          | empty list            | throws, naming the element index    |
| `getXListOrNull(key)`    | `null`                | throws, naming the element index    |
| `getXListOrThrow(key, msg?)` | throws            | throws, naming the element index    |

"Invalid" is type-specific and strict: `getIntOrNull` accepts only an `Int` (a YAML value such as
`3000000000` loads as `Long` and is rejected, use `getLongOrNull`), unsigned getters accept their
whole range (`UInt` up to 4294967295, `ULong` up to 18446744073709551615) and reject negative numbers
instead of wrapping them, float getters reject a finite number that overflows a float instead of
returning infinity, string getters accept any single value (`5` reads as `"5"`) but reject a section
or a list instead of printing it, `getPercent*` requires a trailing `%`, enum getters match the
constant name ignoring case and list up to twenty allowed names in the error. The enum getters exist
twice: reified for a type known at compile time and with a `Class<out Enum<*>>` parameter for the
value processor, which knows the enum only by name.

The list getters are just as strict: a single value or a section where a list is expected is an
error, not an empty list, and every element goes through the converter of the single-value getter,
so `[1, x, 2]` and `[1, 3000000000]` are errors naming element 1 and its value as written. Bukkit's
`getIntegerList` and its siblings skip elements they cannot convert, wrap numbers that do not fit
and read a single value as an empty list.

Not every family exists for every type. A member function always wins over an extension of the
same signature, so where Bukkit declares the name, the strict form is left out and Bukkit's lenient
member answers instead: `getBoolean`/`getInt`/`getLong`/`getDouble`/`getString(key, default)`,
`isBoolean`/`isInt`/`isLong`/`isDouble`/`isString`, `getBooleanList`, `getIntegerList`,
`getLongList`, `getFloatList`, `getDoubleList` and `getStringList`. For those types write
`getXOrNull(key) ?: default` for a default, and use `getXListOrNull` or `getXListOrThrow` for a
list.

`ConfigurationValueException` messages always contain the full dotted path of the key
(`ConfigurationSection.pathOf(key)`), so the user can find the line in the file. The optional
`exceptionMessage` parameter of the `OrThrow` family replaces the default message for both failure
modes.

To add support for another type, build on the generic entry point:

```kotlin
fun ConfigurationSection.getMaterialOrThrow(key: String) =
    getOrThrow(key, "a Material") { Material.matchMaterial(getStringOrThrow(it)) }
```

### YAML files

`loadYamlOrThrow(file)` propagates parse errors. Bukkit's `YamlConfiguration.loadConfiguration`
logs them and returns an empty configuration, which hides broken files. It also decodes strictly:
a file saved in another encoding, such as an ANSI code page on Windows, is an
`InvalidConfigurationException` (`pl.yml is not UTF-8 encoded; save it as UTF-8`) instead of text
with replacement characters, which Bukkit's own `load(File)` produces silently. A UTF-8 byte order
mark is accepted.

`File.yamlFiles()` lists the `.yml` and `.yaml` files under a directory, recursively, ordered by
the path relative to that directory with `/` separators, so the order is the same on Windows and
Linux; callers that report a file by name or parse it themselves start from it.
`Plugin.forEachYamlFile(relativePath) { path, config -> }` and `Plugin.mapYamlFiles` walk a
directory under the plugin's data folder in that order and load every file with
`loadYamlOrThrow`.

## `DeepMergeMap` (`util.collection`)

A `MutableMap<K, Any?>` that merges other maps into itself recursively. For every key of the
incoming map:

1. Key absent in the destination: the value is deep-copied in. Nested maps become nested
   `DeepMergeMap`s, nested collections become lists.
2. Both values are maps: they are merged recursively with the same rules.
3. Both values are collections: the result is their concatenation.
4. Anything else is a *conflict*, resolved by the `MergeConflictStrategy` given to the constructor:

| Strategy            | Result                                                                   |
|---------------------|--------------------------------------------------------------------------|
| `OVERRIDE` (default) | incoming value wins                                                     |
| `KEEP_EXISTING`     | existing value stays                                                    |
| `COMBINE_INTO_LIST` | `[existing, incoming]`; a collection on either side is flattened into it |
| `THROW`             | `IllegalStateException` naming the dotted key path                       |

`null` is an ordinary value, not "absent". Incoming maps are never mutated, and nothing nested is
shared with them: the constructor deep-copies its initial map by the same rules, `mergedWith(other)`
deep-copies the receiver, and concatenated or combined collections copy what they take in. Use
`merge(other)` or `map += other` to mutate in place and `mergedWith(other)` to get a copy. There is deliberately no
`plus` operator: defining both `plus` and `plusAssign` makes `+=` on a `var` ambiguous in Kotlin.

## `*OrThrow` collection helpers (`util.collection`)

`addAllOrThrow` and `putAllOrThrow` check the whole input before touching the receiver, so a
failed call leaves the collection unchanged. They also report duplicates *inside* the input, not only
clashes with what is already stored. A stored `null` value counts as present everywhere
(`containsKey`, never `get(key) != null`).

## Validation (`util.validation`)

`validate`, `validateAtLeast`, `validateBetween` and friends wrap `require` and return the receiver,
so they can be chained inline: `config.getIntOrThrow("size").validateAtLeast(1)`. They are generic
over `Comparable<T>` (or `Number` for the zero checks) rather than copied per numeric type. Messages
are lambdas and evaluated only on failure. They throw `IllegalArgumentException`, which knows
nothing about configuration keys, so a guard chained onto a configuration getter loses the key
path; pass a message naming the key (`ConfigurationSection.pathOf(key)`) when the value comes from a
file.

## Reflection (`util.reflect`)

`forceGetValue`, `forceSetValue` and `forceInvoke` make a member accessible and leave it so:
`kotlin-reflect` caches the underlying Java members, and restoring the flag would race with another
thread using the same member. `forceAccessible()` does the same for a Java member and returns it.
`forceSetValue` goes through the property's setter whenever there is one, so a private custom
setter runs; only a property without a setter is written through its field.
`Class.objectInstanceOrNull()` reads a Kotlin `object`'s `INSTANCE`, private or not, and
`Class.objectOrNewInstance()` falls back to the no-argument constructor, rethrowing what the
constructor throws unwrapped.

Properties of Kotlin `object`s are backed by static fields, which `kotlin-reflect` cannot set through
`KMutableProperty1.set` on the object instance; with an inline class type, such as `Percent`, it
cannot even describe the accessor (`Inconsistent number of parameters in the descriptor and Java
reflection object`). The helpers detect a static backing field and go through `java.lang.reflect`
alone: the setter, found by name (with the hash suffix the compiler adds for an inline class
parameter), or else the field. A boxed inline class value is unboxed through its `unbox-impl`
method when the member takes the underlying type, and boxed back through `box-impl` when it is
read. Background:
https://stackoverflow.com/questions/79443662

`isClassInClasspath(name, classLoader)` takes the loader explicitly because every plugin on a Paper
server has its own class loader; a lookup through this library's loader would not see the calling
plugin's optional dependencies. The class is loaded without initialization, so no static
initializers run as a side effect.

### Runtime requirement

The `util.reflect` package needs `kotlin-reflect` on the server. VersatiaCore lists it next to
`kotlin-stdlib` under `libraries:` in its `plugin.yml`, and Versatia plugins reach both through
`depend: [VersatiaCore]`. Plugins must not list them themselves; see
[enhancements.md](enhancements.md#setting-up-a-plugin-build).

## Tests

Pure-Kotlin code, the configuration getters and the YAML file helpers are covered by unit tests
under `api/src/test/kotlin` (`./gradlew test`). `paper-api` is on the test runtime classpath, which
is enough to run `YamlConfiguration` without a server; of the Bukkit types, `Color` and `Vector`
are read back in tests, while `ItemStack`, `Location` and `OfflinePlayer` are tested only for their
error messages, because reading them needs a server. Anything that needs a live `Bukkit.getServer()` (scheduler,
inventory, world, the `VersatiaEngine` shortcuts) is not unit-tested; the shortcuts hold no logic
by rule, and what they rely on lives in server-free types (`PluginRegistry.getOrThrow`).
