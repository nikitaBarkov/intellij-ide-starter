package com.intellij.ide.starter.path

import com.intellij.ide.starter.utils.FileSystem.getFileOrDirectoryPresentableSize
import com.intellij.ide.starter.utils.createInMemoryDirectory
import com.intellij.ide.starter.utils.executeWithRetry
import com.intellij.ide.starter.utils.logOutput
import com.intellij.openapi.util.SystemInfoRt
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

class IDEDataPaths(
  val testHome: Path,
  private val inMemoryRoot: Path?
) : Closeable {

  companion object {
    fun createPaths(testName: String, testHome: Path, useInMemoryFs: Boolean): IDEDataPaths {
      testHome.toFile().walkBottomUp().fold(true) { res, it ->
        (it.absolutePath.startsWith((testHome / "system").toFile().absolutePath) || it.delete() || !it.exists()) && res
      }
      testHome.createDirectories()
      val inMemoryRoot = if (useInMemoryFs) {
        createInMemoryDirectory("ide-integration-test-$testName")
      }
      else {
        null
      }
      //workaround for https://bugs.openjdk.org/browse/JDK-8024496
      if (SystemInfoRt.isWindows) {
        Thread.sleep(5.seconds.inWholeMilliseconds)
      }
      return executeWithRetry(retries = 5, exception = java.nio.file.AccessDeniedException::class.java, delay = 2.seconds) {
        return@executeWithRetry IDEDataPaths(testHome = testHome, inMemoryRoot = inMemoryRoot)
      }
    }
  }

  val logsDir = (testHome / "log").createDirectories()
  val snapshotsDir = (testHome / "snapshots").createDirectories()
  val tempDir = (testHome / "temp").createDirectories()

  /**
   * Directory used to store TeamCity artifacts. To make sure the TeamCity publishes all artifacts
   * files added to this directory must not be removed until the end of the tests execution .
   */
  private val teamCityArtifacts = (testHome / "team-city-artifacts").createDirectories()

  val configDir = ((inMemoryRoot ?: testHome) / "config").createDirectories()
  val systemDir = ((inMemoryRoot ?: testHome) / "system").createDirectories()
  val pluginsDir = (testHome / "plugins").createDirectories()
  val jbrDiagnostic = (testHome / "jbrDiagnostic").createDirectories()

  override fun close() {
    if (inMemoryRoot != null) {
      try {
        inMemoryRoot.toFile().deleteRecursively()
      }
      catch (e: Exception) {
        logOutput("! Failed to unmount in-memory FS at $inMemoryRoot")
        e.stackTraceToString().lines().forEach { logOutput("    $it") }
      }
    }

    // [deleteDirectories] is disabled, because sometimes we need to collect some artifacts from those directories
    // anyway, they will be cleaned up by CI
  }

  private fun deleteDirectories() {
    val toDelete = getDirectoriesToDeleteAfterTest().filter { it.exists() }

    if (toDelete.isNotEmpty()) {
      logOutput(buildString {
        appendLine("Removing directories of $testHome")
        toDelete.forEach { path ->
          appendLine("  $path: ${path.getFileOrDirectoryPresentableSize()}")
        }
      })
    }

    toDelete.forEach { runCatching { it.toFile().deleteRecursively() } }
  }

  private fun getDirectoriesToDeleteAfterTest() = if (testHome.exists()) {
    Files.list(testHome).use { it.toList() } - setOf(teamCityArtifacts)
  }
  else {
    emptyList()
  }

  override fun toString(): String = "IDE Test Paths at $testHome"
}