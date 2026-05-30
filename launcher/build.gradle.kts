plugins {
    id("butterfly.java-conventions")
    application
    id("com.gradleup.shadow") version "8.3.5"
}

dependencies {
    implementation(project(":core"))
    implementation(libs.jline)
    implementation(libs.log4j.core)
    implementation(libs.log4j.slf4j)
}

application {
    mainClass.set("net.butterfly.launcher.Main")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("butterfly-server")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
}
