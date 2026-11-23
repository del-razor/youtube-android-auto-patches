extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.delrazor.extension"

    buildTypes {
        named("release") {
            optimization {
                keepRules {
                    // The keep rules that androidx ships would keep some androidx classes under
                    // their real names, and those would merge with YouTube's copies.
                    // Ignore them so R8 can move every bundled class (see proguard-rules.pro).
                    ignoreFromAllExternalDependencies(true)
                }
            }
        }
    }
}

// Not needed in the car screen. Their classes are kept by name because the extension's own
// manifest references them, and YouTube has classes with the same names.
configurations.configureEach {
    exclude(group = "androidx.profileinstaller", module = "profileinstaller")
    exclude(group = "androidx.startup", module = "startup-runtime")
}

dependencies {
    // Unofficial Android Auto SDK (2019), taken from Fermata Auto
    // (https://github.com/AndreyPavlenko/Fermata, fermata/lib/auto/aauto.aar).
    // It lets an app draw its own screen on the car display (CarActivity) and read
    // the car's sensors, including the driving status "no video" flag.
    implementation(files("libs/aauto.aar"))

    // CarActivity uses androidx.fragment.
    implementation(libs.androidx.fragment)
}
