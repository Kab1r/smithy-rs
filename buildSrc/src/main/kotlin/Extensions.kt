/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

// JReleaser publishes artifacts from a local staging repository, rather than maven local.
// https://jreleaser.org/guide/latest/examples/maven/staging-artifacts.html#_gradle
fun Project.stagingDir(): Provider<Directory> {
    return rootProject.layout.buildDirectory.dir("m2")
}

fun Project.configureSmithyBuildSeverity(rootProject: Project = this.rootProject) {
    val severity = PropertyRetriever(rootProject, this).get("smithy.severity") ?: return
    tasks.named("smithyBuild").configure {
        val severityGetter =
            javaClass.methods.firstOrNull { method ->
                method.name == "getSeverity" && method.parameterCount == 0
            } ?: error("Task $path does not expose a Smithy severity property")

        @Suppress("UNCHECKED_CAST")
        val severityProperty = severityGetter.invoke(this) as Property<String>
        severityProperty.set(severity)
    }
}
