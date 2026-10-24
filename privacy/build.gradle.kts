plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    jacoco
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ktlint {
    version.set("1.8.0")
}

// Same rationale as :app — ktlint-cli 1.8.0 bundles a CVE-flagged logback used only at build time.
configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy {
        force(
            "ch.qos.logback:logback-core:1.5.34",
            "ch.qos.logback:logback-classic:1.5.34",
        )
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.javax.inject)
    implementation(libs.libphonenumber)
    // API-identical to onnxruntime-android; :app supplies the Android AAR at runtime and
    // :privacy-benchmark / tests supply the JVM artifact.
    compileOnly(libs.onnxruntime.jvm)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.onnxruntime.jvm)
}

tasks.test {
    useJUnitPlatform()
}

jacoco {
    toolVersion = "0.8.15"
}

// OrtPiiModelRunner is exercised only by the PRIVACY_MODEL_DIR-gated real-model test (CI has no
// 151 MB model), so it is excluded from the coverage calculation — the same pattern :app uses for
// device-only classes. User-approved (review finding P59-004).
val privacyJacocoClassDirs =
    fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
        exclude("**/OrtPiiModelRunner*")
    }

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(privacyJacocoClassDirs)
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}
