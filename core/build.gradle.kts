plugins { id("butterfly.java-conventions") }

dependencies {
    api(project(":api"))

    api(libs.proto.nbt)
    api(libs.proto.raknet)
    api(libs.proto.crypto)
    api(libs.proto.codec)

    implementation(libs.netty.transport)
    implementation(libs.netty.epoll)
    implementation(libs.jose4j)
    implementation(libs.fastutil)
    implementation(libs.gson)
    implementation(libs.snakeyaml)
    implementation(libs.disruptor)
    implementation(libs.hivemc.leveldb)
    implementation(libs.hivemc.leveldb.api)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
}
