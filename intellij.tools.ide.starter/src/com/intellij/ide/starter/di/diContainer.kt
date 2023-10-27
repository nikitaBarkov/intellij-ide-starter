package com.intellij.ide.starter.di

import com.intellij.ide.starter.buildTool.BuildTool
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.community.PublicIdeDownloader
import com.intellij.ide.starter.config.ConfigurationStorage
import com.intellij.ide.starter.config.StarterConfigurationStorage
import com.intellij.ide.starter.frameworks.Framework
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.IdeDownloader
import com.intellij.ide.starter.ide.installer.IdeInstallerFactory
import com.intellij.ide.starter.models.IdeProduct
import com.intellij.ide.starter.models.IdeProductImp
import com.intellij.ide.starter.path.GlobalPaths
import com.intellij.ide.starter.path.InstallerGlobalPaths
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.report.FailureDetailsOnCI
import com.intellij.ide.starter.report.publisher.ReportPublisher
import com.intellij.ide.starter.report.publisher.impl.ConsoleTestResultPublisher
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.TestContainer
import com.intellij.ide.starter.runner.TestContainerImpl
import com.intellij.tools.ide.util.common.logOutput
import org.kodein.di.*
import java.net.URI

/**
 * Reinitialize / override bindings for this DI container in your module before executing tests
 * https://docs.kodein.org/kodein-di/7.9/core/bindings.html
 *
 * E.g:
 * ```
 * di = DI {
 *      extend(di)
 *      bindSingleton<GlobalPaths>(overrides = true) { YourImplementationOfPaths() }
 *    }
 * ```
 * */
var di = DI {
  bindSingleton<GlobalPaths> { InstallerGlobalPaths() }
  bindSingleton<CIServer> { NoCIServer }
  bindSingleton<FailureDetailsOnCI> { object : FailureDetailsOnCI {} }
  bindFactory<IDETestContext, PluginConfigurator> { testContext: IDETestContext -> PluginConfigurator(testContext) }
  bindSingleton<IdeDownloader> { PublicIdeDownloader }
  bindSingleton<IdeInstallerFactory> { IdeInstallerFactory() }

  // you can extend DI with frameworks, specific to IDE language stack
  bindArgSet<IDETestContext, Framework>()
  importAll(ideaFrameworksDI)

  // you can extend DI with build tools, specific to IDE language stack
  bindArgSet<IDETestContext, BuildTool>()
  importAll(ideaBuildToolsDI)

  bindSingleton<List<ReportPublisher>> { listOf(ConsoleTestResultPublisher) }
  bindSingleton<IdeProduct> { IdeProductImp }
  bindSingleton<CurrentTestMethod> { CurrentTestMethod }
  bindSingleton<ConfigurationStorage> { StarterConfigurationStorage() }
  bindSingleton(tag = "teamcity.uri") { URI("https://buildserver.labs.intellij.net").normalize() }

  bindProvider<TestContainer<*>> { TestContainer.newInstance<TestContainerImpl>() }
}.apply {
  logOutput("Starter DI was initialized")
}
