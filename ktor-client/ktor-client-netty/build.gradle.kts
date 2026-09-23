/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

description = "Netty based client engine"

plugins {
    id("ktorbuild.project.library")
    id("test-server")
}

kotlin {
    sourceSets {
        jvmMain.dependencies {
            api(projects.ktorClientCore)
            api(libs.netty.codec.http)
            api(libs.netty.handler)
            api(libs.netty.handler.proxy)
        }
        jvmTest.dependencies {
            implementation(projects.ktorClientTests)
        }
    }
}
