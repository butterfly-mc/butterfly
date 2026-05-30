plugins { `java-library` }

group = "mc.butterfly"
version = providers.gradleProperty("butterflyVersion").orElse("0.0.0-SNAPSHOT").get()

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<Test> { useJUnitPlatform() }
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
