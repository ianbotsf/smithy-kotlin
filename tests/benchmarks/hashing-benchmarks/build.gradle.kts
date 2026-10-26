/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
import aws.sdk.kotlin.gradle.publishing.skipPublishing
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlinx.benchmark)
    alias(libs.plugins.aws.kotlin.repo.tools.kmp)
}

skipPublishing()

val optinAnnotations = listOf("kotlin.RequiresOptIn", "aws.smithy.kotlin.runtime.InternalApi")

kotlin {
    sourceSets {
        all {
            optinAnnotations.forEach { languageSettings.optIn(it) }
        }

        commonMain {
            dependencies {
                implementation(libs.kotlinx.benchmark.runtime)
                implementation(project(":runtime:runtime-core"))
            }
        }
    }
}

benchmark {
    targets {
        register("jvm")

        // Native benchmarks are executables, so only the host's own native target can run them
        kotlin.targets
            .withType<KotlinNativeTarget>()
            .filter { it.konanTarget == HostManager.host }
            .forEach { register(it.name) }
    }

    configurations {
        getByName("main") {
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            warmups = 3
            outputTimeUnit = "us"
            reportFormat = "text"
        }
    }
}

// Workaround for https://github.com/Kotlin/kotlinx-benchmark/issues/39
afterEvaluate {
    tasks.named<org.gradle.jvm.tasks.Jar>("jvmBenchmarkJar") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

// The dudect timing tests are slow and sensitive to host load, so they only run with -Pdudect, and then alone
if (project.hasProperty("dudect")) {
    // Native test binaries are debug builds by default, whose timing says little about optimized code. This adds an
    // optimized one: build with linkDudectReleaseTest<Target> and run its test.kexe directly.
    kotlin.targets.withType<KotlinNativeTarget>().configureEach {
        binaries.test("dudect", listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE))
    }
}

tasks.withType<AbstractTestTask>().configureEach {
    val dudect = "*DudectTimingTest"
    if (project.hasProperty("dudect")) {
        filter.includeTestsMatching(dudect)
        outputs.upToDateWhen { false }
    } else {
        filter.excludeTestsMatching(dudect)
    }
}
