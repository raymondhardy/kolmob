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

// Upstream KoLmafia's own JUnit suite, run against our synced core (stubs + patches) on the JVM.
// Sources come from tools/sync-upstream.sh (gitignored).
dependencies {
    testImplementation(project(":kolmafia-core"))
    testImplementation("net.sourceforge.htmlcleaner:htmlcleaner:2.29")
    testImplementation("org.jsoup:jsoup:1.23.1")
    testImplementation("com.alibaba.fastjson2:fastjson2:2.0.59")
    testImplementation("org.mozilla:rhino:1.9.1")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation("org.slf4j:slf4j-nop:2.0.17")
    testImplementation("org.fusesource.jansi:jansi:2.4.2")
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

// Two upstream tests need Swing panels / jgit that the Android core does not ship.
sourceSets.test {
    java.exclude("**/YouRobotManagerTest.java", "**/QuestManagerTest.java")
}
