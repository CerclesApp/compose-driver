plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
    application
}

application {
    mainClass = "io.github.jdemeulenaere.compose.driver.mcp.MainKt"
}

dependencies {
    implementation(projects.driverCore)
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.slf4j.simple)
}
