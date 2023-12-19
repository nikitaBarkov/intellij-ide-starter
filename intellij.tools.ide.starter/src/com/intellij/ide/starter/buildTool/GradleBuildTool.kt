package com.intellij.ide.starter.buildTool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.ide.starter.bus.EventState
import com.intellij.ide.starter.bus.StarterBus
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.process.destroyProcessIfExists
import com.intellij.ide.starter.process.exec.ExecOutputRedirect
import com.intellij.ide.starter.process.exec.ProcessExecutor
import com.intellij.ide.starter.runner.IdeLaunchEvent
import com.intellij.ide.starter.utils.HttpClient
import com.intellij.ide.starter.utils.XmlBuilder
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.util.SystemInfo
import com.intellij.tools.ide.util.common.logError
import com.intellij.tools.ide.util.common.logOutput
import org.apache.http.client.methods.HttpGet
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.File
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

open class GradleBuildTool(testContext: IDETestContext) : BuildTool(BuildToolType.GRADLE, testContext) {

  companion object {
    private fun destroyGradleDaemonProcessIfExists() {
      val mavenDaemonName = "gradleDaemon"
      destroyProcessIfExists(mavenDaemonName)
    }
  }

  init {
    StarterBus.subscribeOnlyOnce(GradleBuildTool::javaClass, eventState = EventState.AFTER) { event: IdeLaunchEvent ->
      if (event.data.runContext.testContext === testContext) {
        destroyGradleDaemonProcessIfExists()
      }
    }
  }

  private val localGradleRepoPath: Path
    get() = testContext.paths.tempDir.resolve("gradle")

  private val gradleXmlPath: Path
    get() = testContext.resolvedProjectHome.resolve(".idea").resolve("gradle.xml")

  private fun parseGradleXmlConfig(): Document = XmlBuilder.parse(gradleXmlPath)

  private fun getGradleVersionFromWrapperProperties(): String {
    val propFile = testContext.resolvedProjectHome.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties")
    if (propFile.notExists()) return ""
    val distributionUrl = propFile.readLines(Charsets.ISO_8859_1).first { it.startsWith("distributionUrl") }
    val version = "\\d.{1,4}\\d".toRegex().find(distributionUrl)?.value ?: ""
    return version
  }

  fun getGradleDaemonLog(): Path {
    return getGradleDaemonLogs().first()
  }

  fun getGradleDaemonLogs(): List<Path> {
    return localGradleRepoPath.resolve("daemon").resolve(getGradleVersionFromWrapperProperties())
      .listDirectoryEntries()
      .filter { it.last().extension == "log" }
  }

  fun useNewGradleLocalCache(): GradleBuildTool {
    localGradleRepoPath.toFile().mkdirs()
    testContext.applyVMOptionsPatch { addSystemProperty("gradle.user.home", localGradleRepoPath.toString()) }
    return this
  }

  fun removeGradleConfigFiles(): GradleBuildTool {
    logOutput("Removing Gradle config files in ${testContext.resolvedProjectHome} ...")

    testContext.resolvedProjectHome.toFile().walkTopDown()
      .forEach {
        if (it.isFile && (it.extension in listOf("gradle", "kts") || (it.name in listOf("gradlew", "gradlew.bat", "gradle.properties")))) {
          it.delete()
          logOutput("File ${it.path} is deleted")
        }
      }

    return this
  }

  fun enableParallelImport(enable: Boolean): GradleBuildTool {
    addPropertyToGradleProperties("org.gradle.parallel", enable.toString(), true)
    return this
  }

  fun addPropertyToGradleProperties(property: String, value: String, replacePrevValue: Boolean = false): GradleBuildTool {
    val projectDir = testContext.resolvedProjectHome
    val gradleProperties = projectDir.resolve("gradle.properties")
    val lineWithTheSameProperty = gradleProperties.readLines().singleOrNull { it.contains(property) }

    if (lineWithTheSameProperty != null) {
      if (lineWithTheSameProperty.contains(value)) {
        return this
      }

      val prevValue = lineWithTheSameProperty.substringAfter("$property=")
      val newValue = (if (!replacePrevValue) prevValue else "") + " $value"
      val tempFile = File.createTempFile("newContent", ".txt").toPath()
      gradleProperties.forEachLine { line ->
        tempFile.appendText(when {
                              line.contains(property) -> "$property=${newValue.trim()}" + System.getProperty("line.separator")
                              else -> line + System.getProperty("line.separator")
                            })
      }
      gradleProperties.writeText(tempFile.readText())
    }
    else {
      gradleProperties.appendLines(listOf("$property=$value"))
    }

    return this
  }

  fun setGradleJvmInProject(useJavaHomeAsGradleJvm: Boolean = true): GradleBuildTool {
    try {
      if (gradleXmlPath.notExists()) return this
      val xmlDoc = parseGradleXmlConfig()

      val gradleSettings = xmlDoc.getElementsByTagName("GradleProjectSettings")
      if (gradleSettings.length != 1) return this

      val options = (gradleSettings.item(0) as Element).getElementsByTagName("option")

      XmlBuilder.findNode(options) { it.getAttribute("name") == "gradleJvm" }
        .ifPresent { node -> gradleSettings.item(0).removeChild(node) }

      if (useJavaHomeAsGradleJvm) {
        val option = xmlDoc.createElement("option")
        option.setAttribute("name", "gradleJvm")
        option.setAttribute("value", "#JAVA_HOME")
        gradleSettings.item(0).appendChild(option)
      }

      XmlBuilder.writeDocument(xmlDoc, gradleXmlPath)
    }
    catch (e: Exception) {
      logError(e)
    }

    return this
  }

  fun runBuildBy(useGradleBuildSystem: Boolean): GradleBuildTool {
    if (gradleXmlPath.notExists()) return this
    if (gradleXmlPath.toFile().readText().contains("<option name=\"delegatedBuild\" value=\"$useGradleBuildSystem\"/>")) return this

    val xmlDoc = parseGradleXmlConfig()

    val gradleProjectSettingsElements: NodeList = xmlDoc.getElementsByTagName("GradleProjectSettings")
    if (gradleProjectSettingsElements.length != 1) return this

    val options = (gradleProjectSettingsElements.item(0) as Element).getElementsByTagName("option")

    XmlBuilder.findNode(options) { it.getAttribute("name") == "delegatedBuild" }
      .ifPresent { node -> gradleProjectSettingsElements.item(0).removeChild(node) }

    for (i in 0 until gradleProjectSettingsElements.length) {
      val component: Node = gradleProjectSettingsElements.item(i)

      if (component.nodeType == Node.ELEMENT_NODE) {
        val optionElement = xmlDoc.createElement("option")
        optionElement.setAttribute("name", "delegatedBuild")
        optionElement.setAttribute("value", "$useGradleBuildSystem")
        component.appendChild(optionElement)
      }
    }

    XmlBuilder.writeDocument(xmlDoc, gradleXmlPath)

    return this
  }

  fun setLogLevel(logLevel: LogLevel): GradleBuildTool {
    testContext.applyVMOptionsPatch {
      configureLoggers(logLevel, "org.jetbrains.plugins.gradle")
    }
    return this
  }

  fun execGradlew(args: List<String>, timeout: Duration = 1.minutes): GradleBuildTool {
    val stdout = ExecOutputRedirect.ToString()
    val stderr = ExecOutputRedirect.ToString()

    val command = when (SystemInfo.isWindows) {
      true -> (testContext.resolvedProjectHome / "gradlew.bat").toString()
      false -> "./gradlew"
    }

    if (!SystemInfo.isWindows) {
      ProcessExecutor(
        presentableName = "chmod gradlew",
        workDir = testContext.resolvedProjectHome,
        timeout = 1.minutes,
        args = listOf("chmod", "+x", "gradlew"),
        stdoutRedirect = stdout,
        stderrRedirect = stderr
      ).start()
    }

    ProcessExecutor(
      presentableName = "Calling gradlew with parameters: $args",
      workDir = testContext.resolvedProjectHome,
      timeout = timeout,
      args = listOf(command) + args,
      stdoutRedirect = stdout,
      stderrRedirect = stderr
    ).start()
    return this
  }

  fun setGradleVersionInWrapperProperties(newVersion: String): GradleBuildTool {
    val propFile = testContext.resolvedProjectHome.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties")
    if (propFile.exists()) {
      val lineToReplace = propFile.readLines(Charsets.ISO_8859_1).filter { it.startsWith("distributionUrl") }[0]
      val newLine = lineToReplace.replace("\\d.*\\d".toRegex(), newVersion)
      propFile.writeText(propFile.readText().replace(lineToReplace, newLine))
    }
    return this
  }

  fun updateKotlinVersionInGradleProperties(kotlinVersion: String): GradleBuildTool {
    val gradlePropertiesFile = testContext.resolvedProjectHome.resolve("gradle.properties")
    val textLines = gradlePropertiesFile.readLines()
    var text = gradlePropertiesFile.readText()
    textLines.forEach { line ->
      if (line.contains("kotlinVersion=")) {
        text = text.replace(line, "kotlinVersion=$kotlinVersion")
      }
    }
    gradlePropertiesFile.writeText(text)
    return this
  }


  fun getLastGradleReleaseVersion(): String {
    return HttpClient.sendRequest(
      HttpGet("https://services.gradle.org/versions/current").apply {
        addHeader("Content-Type", "application/json")
      }) {
      jacksonObjectMapper()
        .readValue(it.entity.content, JsonNode::class.java)
    }
      .get("version")
      .toString()
      .replace("\"", "")
      .also { logOutput("Last gradle release version $it") }
  }
}
