/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import ktorbuild.createCInterop
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager

description = ""

plugins {
    id("ktorbuild.project.library")
    id("kotlinx-serialization")
}

kotlin {
    createCInterop("host_common", sourceSet = "nix")

    targets.withType<KotlinNativeTarget>().matching {
        it.konanTarget == HostManager.host && it.konanTarget.family in listOf(Family.LINUX, Family.OSX)
    }.configureEach {
        compilations.named("test") {
            // Host process tests use only native test outputs, without compiling the JVM test suite.
            defaultSourceSet.kotlin.srcDir("nix/test-process")
            cinterops.create("shutdown_test") {
                definitionFile = project.layout.projectDirectory.file("nix/interop/shutdown_test.def")
            }
        }
    }

    sourceSets {
        named("nixTest") {
            // Exercise internal hook registration and completion with a real server engine.
            dependencies { implementation(projects.ktorServerCio) }
        }

        commonMain.dependencies {
            api(projects.ktorUtils)
            api(projects.ktorHttp)
            api(projects.ktorSerialization)
            api(projects.ktorEvents)
            api(projects.ktorHttpCio)
            api(projects.ktorWebsockets)

            api(libs.kotlin.reflect)
        }

        jvmMain.dependencies {
            api(libs.typesafe.config)
        }

        commonTest.dependencies {
            implementation(projects.ktorServerTestBase)
        }

        jvmTest.dependencies {
            implementation(projects.ktorServerConfigYaml)
            implementation(projects.ktorServerTestSuites)

            implementation(libs.mockk)
        }
    }
}
