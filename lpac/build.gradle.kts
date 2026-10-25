plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "net.typeblog.lpac_jni"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig { minSdk = 33; ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
    externalNativeBuild { ndkBuild { path("src/main/jni/lpac-jni.mk") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4") }
