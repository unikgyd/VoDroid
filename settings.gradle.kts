pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    resolutionStrategy { eachPlugin {
        if (requested.id.id.startsWith("com.android.")) useModule("com.android.tools.build:gradle:${requested.version}")
        if (requested.id.id == "org.jetbrains.kotlin.android") useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
    } }
}
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "VoDroid"
include(":app", ":core", ":lpac", ":hidden-api", ":hook-api")
include(":hook-probe")
project(":hook-probe").projectDir = file("diagnostics/hook-probe")
