import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.jadx.core)
    implementation(libs.jadx.dex.input)
    api(libs.arsclib)
    implementation(libs.smali)
    implementation(libs.baksmali)
    api(libs.apksig)
    implementation(libs.slf4j.api)
    testImplementation(libs.junit)
}

// `./gradlew :engine:run --args="decompile foo.apk -o out"` runs the engine on the host JVM.
application {
    mainClass.set("com.gt.apkforge.engine.cli.EngineCliKt")
}
