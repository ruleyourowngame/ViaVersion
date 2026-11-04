dependencies {
    testImplementation(projects.viaversionCommon)
    testImplementation(testFixtures(projects.viaversionCommon))
    testImplementation("io.netty:netty-all:4.2.9.Final")
    testImplementation(rootProject.libs.bundles.junit)
    testRuntimeOnly(rootProject.libs.platformLauncher)
    implementation(projects.viaversionBukkitLegacy)
    compileOnlyApi(projects.viaversionCommon)
    compileOnly(libs.paper) {
        exclude("junit", "junit")
        exclude("com.google.code.gson", "gson")
        exclude("javax.persistence", "persistence-api")
    }
}
