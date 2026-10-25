import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val releaseVersion=Properties().apply{rootProject.file("version.properties").inputStream().use{load(it)}}
val appVersionName=providers.gradleProperty("vodroidVersionName").getOrElse(releaseVersion.getProperty("versionName"))
val appVersionCode=releaseVersion.getProperty("versionCode").toInt()
val signingFile=providers.environmentVariable("VODROID_KEYSTORE_FILE").orNull
val signingStorePassword=providers.environmentVariable("VODROID_STORE_PASSWORD").orNull
val signingAlias=providers.environmentVariable("VODROID_KEY_ALIAS").orNull
val signingKeyPassword=providers.environmentVariable("VODROID_KEY_PASSWORD").orNull
val hasReleaseSigning=listOf(signingFile,signingStorePassword,signingAlias,signingKeyPassword).all{!it.isNullOrBlank()}

android {
    namespace = "io.vodroid"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "io.vodroid"; minSdk = 33; targetSdk = 35; versionCode = appVersionCode; versionName = appVersionName; testInstrumentationRunner = "io.vodroid.SmokeInstrumentation"; ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs { if(hasReleaseSigning)create("publication") { storeFile=file(signingFile!!);storePassword=signingStorePassword;keyAlias=signingAlias;keyPassword=signingKeyPassword;storeType="PKCS12" } }
    buildTypes { release { isMinifyEnabled = false;signingConfig=signingConfigs.findByName("publication") } }
}
val checkReleaseSigning=tasks.register("checkReleaseSigning") { doLast { check(hasReleaseSigning&&file(signingFile!!).isFile){"Release signing is missing. Run python scripts/signing.py init and use scripts/release.py; see docs/RELEASING.md."} } }
tasks.matching{it.name=="preReleaseBuild"}.configureEach{dependsOn(checkReleaseSigning)}
dependencies { implementation(project(":core")); implementation(project(":lpac")); compileOnly(project(":hidden-api")); compileOnly(project(":hook-api")); androidTestCompileOnly(project(":hidden-api")) }
