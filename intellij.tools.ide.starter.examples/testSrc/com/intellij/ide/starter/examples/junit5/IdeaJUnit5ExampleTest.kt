package com.intellij.ide.starter.examples.junit5

import com.intellij.ide.starter.examples.data.TestCases
import com.intellij.ide.starter.junit5.JUnit5StarterAssistant
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.Starter
import com.intellij.ide.starter.runner.TestContainerImpl
import com.intellij.tools.ide.metrics.collector.starter.collector.getMetricsFromSpanAndChildren
import com.intellij.tools.ide.metrics.collector.telemetry.SpanFilter
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.exitApp
import com.intellij.tools.ide.performanceTesting.commands.inspectCode
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(JUnit5StarterAssistant::class)
class IdeaJUnit5ExampleTest {
  @Test
  fun openGradleJitPack() {

    val testContext = Starter
      .newContext(CurrentTestMethod.hyphenateWithClass(), TestCases.IC.GradleJitPackSimple)
      .prepareProjectCleanImport()
      .setSharedIndexesDownload(enable = true)

    val exitCommandChain = CommandChain().exitApp()

    testContext.runIDE(
      commands = exitCommandChain,
      launchName = "first run"
    )

    testContext.runIDE(
      commands = exitCommandChain,
      launchName = "second run"
    )
  }

  @Test
  fun openMavenProject() {

    val testContext = Starter
      .newContext(CurrentTestMethod.hyphenateWithClass(), TestCases.IC.MavenSimpleApp)
      .prepareProjectCleanImport()
      .setSharedIndexesDownload(enable = true)

    testContext.runIDE(commands = CommandChain().exitApp())
  }

  @Test
  @Disabled("Long running test (> 10 min)")
  fun inspectMavenProject() {
    val testContext = Starter
      .newContext(CurrentTestMethod.hyphenateWithClass(), TestCases.IC.MavenSimpleApp)
      .setSharedIndexesDownload(enable = true)

    val result = testContext.runIDE(commands = CommandChain().inspectCode().exitApp())
    getMetricsFromSpanAndChildren(result, SpanFilter.nameEquals("globalInspections")).forEach {
      println("Name: " + it.id.name)
      println("Value: " + it.value + "ms")
    }
  }
}