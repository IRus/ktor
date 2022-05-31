/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*

val FAST_MODE = true

fun fastOr(block: () -> List<String>): List<String> {
    if (FAST_MODE) return emptyList()
    return block()
}

fun KotlinMultiplatformExtension.posixTargets(): List<String> = fastOr { nixTargets() + mingwX64().name }

fun KotlinMultiplatformExtension.nixTargets(): List<String> = fastOr {
    darwinTargets() + linuxX64().name
}

fun KotlinMultiplatformExtension.darwinTargets(): List<String> = fastOr {
    macosTargets() + iosTargets() + watchosTargets() + tvosTargets()
}

fun KotlinMultiplatformExtension.macosTargets(): List<String> = fastOr {
    listOf(
        macosX64(),
        macosArm64()
    ).map { it.name }
}

fun KotlinMultiplatformExtension.iosTargets(): List<String> = fastOr {
    listOf(
        iosX64(),
        iosArm64(),
        iosArm32(),
        iosSimulatorArm64(),
    ).map { it.name }
}


fun KotlinMultiplatformExtension.watchosTargets(): List<String> = fastOr {
    listOf(
        watchosX86(),
        watchosX64(),
        watchosArm32(),
        watchosArm64(),
        watchosSimulatorArm64(),
    ).map { it.name }
}

fun KotlinMultiplatformExtension.tvosTargets(): List<String> = fastOr {
    listOf(
        tvosX64(),
        tvosArm64(),
        tvosSimulatorArm64(),
    ).map { it.name }
}

fun KotlinMultiplatformExtension.desktopTargets(): List<String> = fastOr {
    listOf(
        macosX64(),
        macosArm64(),
        linuxX64(),
        mingwX64()
    ).map { it.name }
}
