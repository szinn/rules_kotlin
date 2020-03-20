/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Provides extensions for the JvmCompilationTask protocol buffer.
package io.bazel.kotlin.builder.tasks.jvm

import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.builder.toolchain.KotlinCompilerPluginArgsEncoder
import io.bazel.kotlin.builder.toolchain.KotlinToolchain
import io.bazel.kotlin.builder.utils.IS_JVM_SOURCE_FILE
import io.bazel.kotlin.builder.utils.bazelRuleKind
import io.bazel.kotlin.builder.utils.ensureDirectories
import io.bazel.kotlin.builder.utils.jars.JarCreator
import io.bazel.kotlin.builder.utils.jars.SourceJarCreator
import io.bazel.kotlin.builder.utils.jars.SourceJarExtractor
import io.bazel.kotlin.builder.utils.partitionJvmSources
import io.bazel.kotlin.model.JvmCompilationTask
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Return a list with the common arguments.
 */
internal fun JvmCompilationTask.commonArgs(): CompilationArgs = baseArgs()
    .absolutePaths(info.friendPathsList) {
      "-Xfriend-paths=${it.joinToString(X_FRIENDS_PATH_SEPARATOR)}"
    }
    .flag("-d", directories.classes)
    .givenNotEmpty(info.passthroughFlags) { it.split(" ") }

internal fun JvmCompilationTask.baseArgs(): CompilationArgs = CompilationArgs()
    .flag("-cp").absolutePaths(inputs.classpathList) {
      it.map(Path::toString).joinToString(File.pathSeparator)
    }
    .flag("-api-version", info.toolchainInfo.common.apiVersion)
    .flag("-language-version", info.toolchainInfo.common.languageVersion)
    .flag("-jvm-target", info.toolchainInfo.jvm.jvmTarget)
    .flag("-module-name", info.moduleName)

internal fun JvmCompilationTask.preProcessingSteps(context: CompilationTaskContext): JvmCompilationTask {
  ensureDirectories(
      directories.temp,
      directories.generatedSources,
      directories.generatedClasses,
      directories.classes
  )
  return context.execute("expand sources") { expandWithSourceJarSources() }
}

internal fun JvmCompilationTask.produceSourceJar() {
  Paths.get(outputs.srcjar).also { sourceJarPath ->
    Files.createFile(sourceJarPath)
    SourceJarCreator(
        sourceJarPath
    ).also { creator ->
      // This check asserts that source jars were unpacked if present.
      check(
          inputs.sourceJarsList.isEmpty() ||
              Files.exists(Paths.get(directories.temp).resolve("_srcjars"))
      )
      listOf(
          // Any (input) source jars should already have been expanded so do not add them here.
          inputs.javaSourcesList.stream(),
          inputs.kotlinSourcesList.stream()
      ).stream()
          .flatMap { it.map { p -> Paths.get(p) } }
          .also { creator.addSources(it) }
      creator.execute()
    }
  }
}

internal fun JvmCompilationTask.runAnnotationProcessors(
  context: CompilationTaskContext,
  pluginArgsEncoder: KotlinCompilerPluginArgsEncoder,
  compiler: KotlinToolchain.KotlincInvoker
): JvmCompilationTask {
  if (inputs.processorsList.isEmpty()) {
    return this
  } else {
    return context.execute("kapt (${inputs.processorsList.joinToString(", ")})")
    {
      commonArgs()
          .values(pluginArgsEncoder.encode(context, this))
          .values(inputs.kotlinSourcesList)
          .values(inputs.javaSourcesList).list().let { args ->
            context.executeCompilerTask(
                args,
                compiler::compile,
                printOnSuccess = context.whenTracing { false } ?: true)
          }.let { outputLines ->
            // if tracing is enabled the output should be formatted in a special way, if we aren't
            // tracing then any compiler output would make it's way to the console as is.
            context.whenTracing {
              printLines("kapt output", outputLines)
            }
            return@let expandWithGeneratedSources()
          }
    }
  }
}

/**
 * Produce the primary output jar.
 */
internal fun JvmCompilationTask.createOutputJar() =
    JarCreator(
        path = Paths.get(outputs.jar),
        normalize = true,
        verbose = false
    ).also {
      it.addDirectory(Paths.get(directories.classes))
      it.addDirectory(Paths.get(directories.generatedClasses))
      it.setJarOwner(info.label, info.bazelRuleKind)
      it.execute()
    }

/**
 * Compiles Kotlin sources to classes. Does not compile Java sources.
 */
internal fun JvmCompilationTask.compileKotlin(
  context: CompilationTaskContext,
  compiler: KotlinToolchain.KotlincInvoker,
  printOnFail: Boolean = true
) =
    commonArgs()
        .values(inputs.javaSourcesList)
        .values(inputs.kotlinSourcesList)
        .list().let { args ->
          return@let context.executeCompilerTask(args, compiler::compile, printOnFail = printOnFail)
        }

/**
 * If any srcjars were provided expand the jars sources and create a new [JvmCompilationTask] with the
 * Java and Kotlin sources merged in.
 */
internal fun JvmCompilationTask.expandWithSourceJarSources(): JvmCompilationTask =
    if (inputs.sourceJarsList.isEmpty())
      this
    else expandWithSources(
        SourceJarExtractor(
            destDir = Paths.get(directories.temp).resolve("_srcjars"),
            fileMatcher = IS_JVM_SOURCE_FILE
        ).also {
          it.jarFiles.addAll(inputs.sourceJarsList.map { p -> Paths.get(p) })
          it.execute()
        }.sourcesList.iterator()
    )

/**
 * Create a new [JvmCompilationTask] with sources found in the generatedSources directory. This should be run after
 * annotation processors have been run.
 */
internal fun JvmCompilationTask.expandWithGeneratedSources(): JvmCompilationTask =
    expandWithSources(
        File(directories.generatedSources).walkTopDown()
            .filter { it.isFile }
            .map { it.path }
            .iterator()
    )

private fun JvmCompilationTask.expandWithSources(sources: Iterator<String>): JvmCompilationTask =
    updateBuilder { builder ->
      sources.partitionJvmSources(
          { builder.inputsBuilder.addKotlinSources(it) },
          { builder.inputsBuilder.addJavaSources(it) })
    }

private fun JvmCompilationTask.updateBuilder(
  block: (JvmCompilationTask.Builder) -> Unit
): JvmCompilationTask =
    toBuilder().let {
      block(it)
      it.build()
    }
