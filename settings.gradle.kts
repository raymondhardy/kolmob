pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Kolmob"
include(":app", ":ash-engine", ":kolmafia-core", ":compat", ":compat-gen", ":engine")
// Upstream JUnit suite on the JVM (opt in: ./gradlew -PcoreTests :core-tests:test)
if (providers.gradleProperty("coreTests").isPresent) include(":core-tests")
