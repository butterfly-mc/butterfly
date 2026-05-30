plugins {
    id("butterfly.java-conventions")
    id("butterfly.publish-api")
}

dependencies {
    api(libs.slf4j.api)
    implementation(libs.snakeyaml)
    testImplementation(libs.junit.jupiter)
}
