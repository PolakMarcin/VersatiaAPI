package com.github.marcoral.versatia.resources

import com.github.marcoral.versatia.EventType
import com.github.marcoral.versatia.VersatiaEnhancement

/**
 * Copies every file under the [DIRECTORY] directory of the plugin's JAR (`src/main/resources/resources/`
 * in the build) into the plugin's data folder when the plugin starts, keeping the directory
 * layout. A file that already exists in the data folder is left untouched, so edits made on the
 * server survive updates of the plugin. Declared on `VersatiaPlugin`, so every Versatia plugin has
 * it; see docs/enhancements.md.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@VersatiaEnhancement(ResourceProcessors.EXTRACT, enhancementPhase = EventType.PLUGIN_START_ONLY)
annotation class ExtractResources {
    companion object {
        /** The classpath directory whose files are extracted. */
        const val DIRECTORY = "resources"
    }
}

/** The VersatiaCore processors of this package, by name: the API does not see the runtime. */
object ResourceProcessors {
    const val EXTRACT = "com.github.marcoral.versatia.resources.ExtractResourcesEnhancementProcessor"
}
