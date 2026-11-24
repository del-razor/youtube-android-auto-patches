extension {
    name = "extensions/gasbuddy.mpe"
}

android {
    namespace = "app.delrazor.extension.gasbuddy"

    buildTypes {
        named("release") {
            optimization {
                keepRules {
                    // Same as the YouTube extension: the bundled androidx classes are moved to a
                    // private package (see proguard-rules.pro), so their own keep rules are ignored.
                    ignoreFromAllExternalDependencies(true)
                }
            }
        }
    }
}

configurations.configureEach {
    exclude(group = "androidx.profileinstaller", module = "profileinstaller")
    exclude(group = "androidx.startup", module = "startup-runtime")
}

dependencies {
    // Android for Cars App Library: the official way to draw template screens (map, lists,
    // details) on the Android Auto car screen. GasBuddy uses the "point of interest" category.
    implementation(libs.androidx.car.app)
}
