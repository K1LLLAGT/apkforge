import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ciBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

android {
    namespace = "com.gt.apkforge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gt.apkforge"
        minSdk = 26
        targetSdk = 34
        versionCode = ciBuild ?: 1
        versionName = "1.0.0" + (ciBuild?.let { "-ci$it" } ?: "")
    }

    signingConfigs {
        // Committed key so every CI build installs over the previous one. Release builds use
        // APKFORGE_KEYSTORE(_PASSWORD/_ALIAS/_KEY_PASSWORD) when set (e.g. from repo secrets).
        getByName("debug") {
            storeFile = file("apkforge-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val ks = System.getenv("APKFORGE_KEYSTORE")?.let(::file)
            storeFile = ks ?: file("apkforge-debug.keystore")
            storePassword = if (ks != null) System.getenv("APKFORGE_KEYSTORE_PASSWORD") else "android"
            keyAlias = if (ks != null) System.getenv("APKFORGE_KEY_ALIAS") else "androiddebugkey"
            keyPassword = if (ks != null) System.getenv("APKFORGE_KEY_PASSWORD") else "android"
        }
    }

    buildTypes {
        release {
            // jadx/ARSCLib/smali rely on reflection + ServiceLoader; no shrinking.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += listOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/**")
    }
}

// jadx/ARSCLib/apksig target desktop Java 11+; redirect the few APIs Android < 33 lacks.
androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(ApiCompatTransform::class.java, InstrumentationScope.ALL) {}
        variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
    }
}

dependencies {
    implementation(project(":engine"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
