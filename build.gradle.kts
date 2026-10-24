import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

// The GitHub dependency graph (what Dependabot scans) also captures the Gradle plugin/buildscript
// classpath, where AGP's own transitives pull vulnerable bouncycastle/httpclient/commons-lang3 (e.g.
// apkzlib -> bcprov 1.79). These run only inside Gradle at build time and never ship, but Dependabot
// still flags them, and the `allprojects` project-configuration forces below cannot reach the
// buildscript classpath. Force them to their patched releases here. (netty 4.1.x is not on this
// classpath — it is a project-configuration transitive only.)
buildscript {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val group = requested.group
            val name = requested.name
            when {
                group == "org.bouncycastle" &&
                    name in setOf("bcprov-jdk18on", "bcutil-jdk18on", "bcpkix-jdk18on") ->
                    useVersion("1.85")
                group == "org.apache.httpcomponents" && name in setOf("httpclient", "httpmime") ->
                    useVersion("4.5.14")
                group == "org.apache.commons" && name == "commons-lang3" ->
                    useVersion("3.18.0")
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.gradle.versions)
    alias(libs.plugins.version.catalog.update)
}

tasks.withType<DependencyUpdatesTask> {
    rejectVersionIf {
        val version = candidate.version.uppercase()
        listOf("ALPHA", "BETA", "RC", "DEV", "SNAPSHOT", "M1", "M2", "M3").any { version.contains(it) }
    }
}

// Every Dependabot-flagged CVE in this repo originates from BUILD/TEST-TOOLING transitives that never
// ship in the APK and never run at app runtime: Android Lint's `androidLintTool` configuration and
// AGP's Unified Test Platform (UTP) configurations pull vulnerable bouncycastle/httpclient/commons-lang3,
// and UTP's grpc-netty drags a vulnerable netty 4.1.x. Scoped `constraints {}` cannot reach these
// AGP-internal tooling configurations, and the forces must apply to :app, :compose-test-app and
// :e2e-tests alike — so force every affected transitive up to its first patched release across ALL
// projects and ALL configurations. The shipping runtime is unaffected: Ktor's server engine keeps its
// netty 4.2.x line (pinned to 4.2.19.Final by :app constraints, which this 4.1.x rule never matches),
// and the app's direct bouncycastle is 1.86, the version forced here.
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val group = requested.group
            val name = requested.name
            when {
                group == "io.netty" && requested.version?.startsWith("4.1.") == true -> {
                    useVersion("4.1.137.Final")
                    because(
                        "CVE-2026-59903 (and earlier); UTP/grpc-netty pulls a vulnerable 4.1.x tooling transitive",
                    )
                }
                group == "org.bouncycastle" &&
                    name in setOf("bcprov-jdk18on", "bcutil-jdk18on", "bcpkix-jdk18on") -> {
                    useVersion("1.86")
                    because("CVE-2025-14813; androidLintTool/UTP pull vulnerable bouncycastle 1.79 (tooling only)")
                }
                group == "org.apache.httpcomponents" && name in setOf("httpclient", "httpmime") -> {
                    useVersion("4.5.14")
                    because("CVE-2020-13956; androidLintTool/UTP pull vulnerable httpclient 4.5.6 (tooling only)")
                }
                group == "org.apache.httpcomponents.core5" && name in setOf("httpcore5", "httpcore5-h2") -> {
                    useVersion("5.4.3")
                    because("CVE-2026-54399; ktor-server-test-host -> ktor-client-apache5 -> httpclient5 pulls vulnerable httpcore5 5.3.6 (test only)")
                }
                group == "org.apache.httpcomponents.client5" && name == "httpclient5" -> {
                    useVersion("5.6.3")
                    because("CVE-2026-64607; ktor-server-test-host -> ktor-client-apache5 pulls vulnerable httpclient5 < 5.6.3 (test only; 5.6.3 depends on the already-forced httpcore5 5.4.3)")
                }
                group == "org.apache.commons" && name == "commons-lang3" -> {
                    useVersion("3.18.0")
                    because("CVE-2025-48924; androidLintTool/UTP pull vulnerable commons-lang3 3.16.0 (tooling only)")
                }
            }
        }
    }
}
