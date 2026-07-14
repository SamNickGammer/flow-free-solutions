pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "flowassist"
include(":app")
include(":solver")
project(":solver").projectDir = file("../solver")
