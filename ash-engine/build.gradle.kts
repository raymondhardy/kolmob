plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // HTML parsing
    implementation("net.sourceforge.htmlcleaner:htmlcleaner:2.29")
    implementation("org.jsoup:jsoup:1.23.1")

    // JSON - using Gson instead of fastjson2 (compatible with minSdk 21)
    implementation("com.google.code.gson:gson:2.10.1")

    // JavaScript engine for ASH - using Rhino with minSdk 26 requirement
    // Using a simpler approach without Rhino for now

    // ANSI colors
    implementation("org.fusesource.jansi:jansi:2.4.2")

    // SVN support
    implementation("org.tmatesoft.svnkit:svnkit:1.10.11") {
        exclude(group = "org.apache.sshd")
        exclude(group = "com.jcraft", module = "jsch.agentproxy.core")
        exclude(group = "com.jcraft", module = "jsch.agentproxy.connector-factory")
    }

    // JGit for Git support
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.4.0.202509020913-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.4.0.202509020913-r")

    // JGoodies binding
    implementation("com.jgoodies:jgoodies-binding:2.13.0")

    // JNA for native access
    implementation("net.java.dev.jna:jna:5.18.1")
    implementation("net.java.dev.jna:jna-platform:5.18.1")

    // Trilead SSH2 for SVN
    implementation("com.trilead:trilead-ssh2:1.0.0-build222")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("org.slf4j:slf4j-nop:2.0.17")

    // JGoodies binding
    implementation("com.jgoodies:jgoodies-binding:2.13.0")

    // JGit
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.4.0.202509020913-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.4.0.202509020913-r")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.hamcrest:hamcrest:2.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
}