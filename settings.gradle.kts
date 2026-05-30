rootProject.name = "butterfly"

pluginManagement {
    includeBuild("build-logic")
    repositories { gradlePluginPortal(); mavenCentral() }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            name = "ButterflyProtocol"
            url = uri("https://maven.pkg.github.com/butterfly-mc/butterfly-protocol")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.token").orNull
            }
        }
    }
}

include(
    "api",
    "core",
    "launcher",
)
