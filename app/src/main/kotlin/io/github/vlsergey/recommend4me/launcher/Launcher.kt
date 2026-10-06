package io.github.vlsergey.recommend4me.launcher

import io.github.vlsergey.recommend4me.Application
import io.github.vlsergey.recommend4me.backup.StartupBackup
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.core.io.DefaultResourceLoader
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val log = LoggerFactory.getLogger("io.github.vlsergey.recommend4me.launcher.Launcher")

/**
 * Starts the application with its plugins.
 *
 * A PLUGIN IS A FOLDER OF JARS in a plugins folder: the plugin's own jar and the libraries it needs
 * beyond the application's. Every jar of every plugin goes into ONE class loader above the
 * application's — one, so that two plugins sharing a library with native code (ONNX Runtime) load
 * it once; a jar of a name met before (the same library in two plugins, or one the application has
 * already) is left out. Spring is started with that loader, and finds the plugins' auto-configurations.
 *
 * The plugins folders: `recommend4me.plugin-dirs` (separated as a class path), or `plugins` beside
 * the application's `lib`.
 */
fun main(args: Array<String>) {
    val dataDir = System.getProperty("recommend4me.data-dir") ?: defaultDataDir().toString()
    System.setProperty("recommend4me.data-dir", dataDir)
    // The personal configuration: what this machine is opened to, and anything else
    if (System.getProperty("spring.config.additional-location") == null) {
        System.setProperty("spring.config.additional-location", "optional:file:${Path.of(dataDir).resolve("application.yaml")}")
    }

    // The Firefox extension, loaded by the browser from its manifest.json on this disk
    if (System.getProperty("recommend4me.extension-dir") == null) {
        installed("extension")?.let { System.setProperty("recommend4me.extension-dir", it.toString()) }
    }

    val parent = Thread.currentThread().contextClassLoader
    val loader = URLClassLoader("plugins", pluginJars().map { it.toUri().toURL() }.toTypedArray(), parent)
    Thread.currentThread().contextClassLoader = loader
    SpringApplication(Application::class.java).apply {
        setResourceLoader(DefaultResourceLoader(loader))
        // Before the databases are opened
        addListeners(StartupBackup())
    }.run(*args)
}

/** %LOCALAPPDATA%\recommend4me on Windows, ~/.local/share/recommend4me elsewhere. */
fun defaultDataDir(): Path =
    Path.of(System.getenv("LOCALAPPDATA") ?: (System.getProperty("user.home") + "/.local/share"), "recommend4me")

private fun pluginJars(): List<Path> {
    val dirs = System.getProperty("recommend4me.plugin-dirs")?.split(File.pathSeparator)?.filter { it.isNotBlank() }?.map { Path.of(it) }
        ?: listOfNotNull(installedPlugins())
    // The jars the application has already, by name
    val taken = System.getProperty("java.class.path").split(File.pathSeparator).map { Path.of(it).name }.toMutableSet()
    val jars = ArrayList<Path>()
    dirs.filter { it.isDirectory() }.forEach { dir ->
        dir.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }.forEach { plugin ->
            val own = plugin.listDirectoryEntries().filter { it.extension == "jar" }.sortedBy { it.name }
            log.info("plugin {} from {}: {} jars", plugin.name, dir, own.size)
            own.forEach { if (taken.add(it.name)) jars.add(it) }
        }
    }
    return jars
}

/** `plugins` beside the folder of the application's own jar (`lib`); null when run from a build. */
private fun installedPlugins(): Path? = installed("plugins")

/** A folder of the installed application, beside `lib`; null when run from a build or when it is missing. */
private fun installed(name: String): Path? {
    val location = Path.of(Application::class.java.protectionDomain.codeSource.location.toURI())
    if (!Files.isRegularFile(location)) return null
    return location.parent?.parent?.resolve(name)?.takeIf { it.isDirectory() }
}
