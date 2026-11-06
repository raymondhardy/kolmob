plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

// Kolmob's own JUnit tests for its engine changes, run against our synced core on the JVM.
// Upstream's test helpers come from tools/sync-core-tests.py (gitignored).
dependencies {
    testImplementation(project(":kolmafia-core"))
    testImplementation("net.sourceforge.htmlcleaner:htmlcleaner:2.29")
    testImplementation("org.jsoup:jsoup:1.23.2")
    testImplementation("com.alibaba.fastjson2:fastjson2:2.0.66")
    testImplementation("org.mozilla:rhino:1.9.2")
    testImplementation("org.slf4j:slf4j-api:2.0.20")
    testImplementation("org.slf4j:slf4j-nop:2.0.20")
    testImplementation("org.fusesource.jansi:jansi:2.4.3")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("org.hamcrest:hamcrest:3.0")
    testImplementation("com.spotify:hamcrest-optional:1.3.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:6.1.3")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation("org.junit-pioneer:junit-pioneer:2.3.0")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("line.separator", "\n")
    systemProperty("junit.jupiter.extensions.autodetection.enabled", true)
    systemProperty("useCWDasROOT", true)
    systemProperty("file.encoding", "UTF-8")
    systemProperty("buildDir", layout.buildDirectory.get().asFile)
    workingDir("root")
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    testLogging.showStandardStreams = false
    testLogging {
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED, org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED)
    }
    maxHeapSize = "2g"
}

// Kolmob's own engine tests (tracked in git), built on upstream's test helpers. Upstream's own tests are not copied
// here: they run upstream. tools/sync-core-tests.py copies the helpers unchanged.
