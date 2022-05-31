description = "Ktor network utilities"

kotlin {
//    nixTargets().forEach {
//        it.compilations.getByName("main").cinterops {
//            create("network") {
//                defFile = projectDir.resolve("nix/interop/network.def")
//            }
//        }
//    }

    sourceSets {
        jvmAndNixMain {
            dependencies {
                api(project(":ktor-utils"))
            }
        }

        jvmAndNixTest {
            dependencies {
                api(project(":ktor-test-dispatcher"))
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.mockk)
            }
        }
    }
}
