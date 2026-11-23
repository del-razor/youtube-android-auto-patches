extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.delrazor.extension"
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
