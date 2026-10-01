plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
}