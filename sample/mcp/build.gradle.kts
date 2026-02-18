/** Sample MCP server — wraps the compose-driver MCP module for the sample composable. */
plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
    application
}

application {
    mainClass = "io.github.jdemeulenaere.compose.driver.mcp.MainKt"
}

dependencies {
    // driver-mcp is not published to Maven Central yet; it is always sourced from the composite
    // build (../), which the sample includes when compose.driver.version ends in "-SNAPSHOT".
    implementation(project(":compose-driver:driver-mcp"))
    implementation(projects.desktop)
}

tasks.named<JavaExec>("run") {
    val prop = "compose.driver.composable"
    System.getProperty(prop)?.let { systemProperty(prop, it) }
}
