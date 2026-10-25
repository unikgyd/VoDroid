plugins { id("com.android.application") }
android {
    namespace = "io.vodroid.hookprobe"
    compileSdk = 35
    defaultConfig { applicationId = "io.vodroid.hookprobe"; minSdk = 33; targetSdk = 35; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
