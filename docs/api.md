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
| `com.github.marcoral.versatia` | the enhancement mechanism (`@VersatiaEnhancement`, `@VersatiaAttribute`, `@VersatiaSubclassEnhancement`, `EnhancementProcessor`, `EnhancementContext`, `StateStore`, `EventType`, `LifecycleEvent`, `EnhancementException`, `@VersatiaDisabler`) and the plugin bootstrap (`VersatiaPlugin`, `VersatiaEngine`, `@DisableVersatiaFeatures`) |
| `engine.descriptor`      | Model of the generated descriptor: `VersatiaPluginDescriptor`, `EnhancedElement`, `AnnotationOccurrence`, `ClassTarget`, `FunctionTarget`, `PropertyTarget`, `PluginTarget`, `MemberOwner`, `Parameter`, `TypeReference`, `Instantiator`, `Invoker`, `Setter`, `DescriptorVersion` |
| `di`                     | Bean scopes: `@Bean`, `@LocalBean`, `@ExposeSingleton`, `@ExposePrototype`, `@PluginBean`; `@Qualifier`, `@Primary`; `Reloadable`, beans reloaded with their plugin; `BeanResolver`, the injection contract for processors; `BeanProcessors`, `BeanNames` |
| `lifecycle`              | `@AutoInvoke`, functions called at a lifecycle phase; `LifecycleProcessors` |
| `config`                 | `@Value`, properties set from the plugin's YAML files at start and reload; `ValueTypes`, the types it reads by itself; `ValueMapper` and `NoMapper` for any other; `PreviousValueTracking`, a cell keeping the value it replaced; `ConfigProcessors` |
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
| `inventory`    | `ItemStack`/`Inventory` helpers, raw player-inventory slot indices                        |
| `lang`         | Enum lookups ignoring case                                                                |
| `plugin`       | `Plugin.log`, `logWarning`, `logSevere` shorthands over the plugin logger                 |
| `reflect`      | Access to private members through `kotlin-reflect`                                        |
| `scheduler`    | `Plugin.runTask*` wrappers over `BukkitScheduler`                                         |
| `server`       | Primary-thread checks, console/broadcast shorthands, `loadService<T>()` over the `ServicesManager` |
| `text`         | Subscript/superscript digits                                                             |
| `type`         | Small value types: `Percent`, `LevelWithExp`                                              |
| `validation`   | `validateAtLeast`-style guards that return the receiver                                   |
| `world`        | `Location` helpers                                                                        |

Files are named `<Subject>Extensions.kt` when they hold extension functions and after the type
when they hold a class.

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

"Invalid" is type-specific and strict: `getIntOrNull` accepts only an `Int` (a YAML value such as
`3000000000` loads as `Long` and is rejected, use `getLongOrNull`), unsigned getters reject negative
numbers instead of wrapping them, `getPercent*` requires a trailing `%`, enum getters match the
constant name ignoring case and list the allowed names in the error. The enum getters exist twice:
reified for a type known at compile time and with a `Class<out Enum<*>>` parameter for the
value processor, which knows the enum only by name.

`ConfigurationValueException` messages always contain the full dotted path of the key
(`ConfigurationSection.pathOf(key)`), so the user can find the line in the file. The optional
`exceptionMessage` parameter of the `OrThrow` family replaces the default message for both failure
modes.

To add support for another type, build on the generic entry point:

```kotlin
fun ConfigurationSection.getMaterialOrThrow(key: String) =
    getOrThrow(key, "a Material") { Material.matchMaterial(getString(it)!!) }
```

### YAML files

`loadYamlOrThrow(file)` propagates parse errors. Bukkit's `YamlConfiguration.loadConfiguration`
logs them and returns an empty configuration, which hides broken files.

`Plugin.forEachYamlFile(relativePath) { path, config -> }` and `Plugin.mapYamlFiles` walk a
directory under the plugin's data folder recursively, in path order, and visit only files with a
`.yml` or `.yaml` extension.

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

`null` is an ordinary value, not "absent". Incoming maps are never mutated. Use `merge(other)` or
`map += other` to mutate in place and `mergedWith(other)` to get a copy. There is deliberately no
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
are lambdas and evaluated only on failure.

## Reflection (`util.reflect`)

`forceGetValue`, `forceSetValue` and `forceInvoke` temporarily make a member accessible and restore
the previous accessibility afterward (they do not blindly reset it to `false`).

Properties of Kotlin `object`s are backed by static fields, which `kotlin-reflect` cannot set through
`KMutableProperty1.set` on the object instance; with an inline class type, such as `Percent`, it
cannot even describe the accessor (`Inconsistent number of parameters in the descriptor and Java
reflection object`). The helpers detect a static backing field and go through
`java.lang.reflect.Field` alone, making only the field accessible, and unbox a boxed inline class
value through its `unbox-impl` method when the field holds the underlying type. Background:
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

Pure-Kotlin code and the configuration getters are covered by unit tests under
`api/src/test/kotlin` (`./gradlew test`). `paper-api` is on the test runtime classpath, which is enough to run
`YamlConfiguration` without a server. Anything that needs a live `Bukkit.getServer()` (scheduler,
inventory, world) is not unit-tested.
