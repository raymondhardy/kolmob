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

// Build-time tool (bytecode remapper), not part of the app.
sourceSets.create("tool")

dependencies {
    api(project(":kolmafia-core"))
    compileOnly(project(":compat"))
    implementation("com.alibaba.fastjson2:fastjson2:2.0.59")
    "toolImplementation"("org.ow2.asm:asm:9.7")
    "toolImplementation"("org.ow2.asm:asm-commons:9.7")
}

tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Boots the headless KoLmafia core and runs a few ASH/CLI commands on the full JVM."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.sourceforge.kolmafia.SmokeMain")
    systemProperty("java.awt.headless", "true")
}

// Remap java.awt -> com.kolmob.awt in the core jar (Android cannot define java.* classes).
val coreJarFile = project(":kolmafia-core").layout.buildDirectory.file("libs/kolmafia-core.jar")
val remappedJar = layout.buildDirectory.file("android/kolmafia-core-android.jar")

val remapAwt = tasks.register<JavaExec>("remapAwt") {
    group = "build"
    dependsOn(":kolmafia-core:jar")
    inputs.file(coreJarFile)
    outputs.file(remappedJar)
    classpath = sourceSets["tool"].runtimeClasspath
    mainClass.set("RemapAwt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(coreJarFile.get().asFile.absolutePath, remappedJar.get().asFile.absolutePath)
    })
}

// Generate shims for exactly the Swing/AWT members the (remapped) core references; see GenShims.java.
val genShims = tasks.register<JavaExec>("genShims") {
    group = "build"
    dependsOn(remapAwt)
    val outDir = rootProject.layout.projectDirectory.dir("compat-gen/src/main/java")
    inputs.file(remappedJar)
    outputs.dir(outDir)
    classpath = sourceSets["tool"].runtimeClasspath
    mainClass.set("GenShims")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(remappedJar.get().asFile.absolutePath, outDir.asFile.absolutePath, rootProject.file("compat/src/main/java").absolutePath)
    })
    doFirst { delete(outDir) }
}

// Same smoke test, but on a JVM without java.desktop, using the remapped core + compat shims:
// the closest cheap approximation of the Android runtime.
tasks.register<JavaExec>("smokeAndroidLike") {
    group = "verification"
    description = "Smoke test on the remapped core with java.desktop removed (emulates Android)."
    dependsOn(remapAwt)
    val deps = configurations.runtimeClasspath.get().filter { !it.name.startsWith("kolmafia-core") }
    classpath = files(sourceSets["main"].output) + files(remappedJar) + deps
    mainClass.set("net.sourceforge.kolmafia.SmokeMain")
    systemProperty("java.awt.headless", "true")
    // ART verifies lazily and tolerates missing classes in unexecuted code; HotSpot is stricter by default.
    jvmArgs(
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:-BytecodeVerificationRemote",
        "--limit-modules",
        "java.base,java.logging,java.xml,java.sql,java.naming,java.net.http,java.security.sasl,java.security.jgss,java.xml.crypto,jdk.unsupported,jdk.crypto.ec,jdk.localedata"
    )
}

tasks.register("printAndroidLikeClasspath") {
    doLast {
        val deps = configurations.runtimeClasspath.get().filter { !it.name.startsWith("kolmafia-core") && !it.name.startsWith("compat") }
        println("CP=" + (listOf(sourceSets["main"].output.classesDirs.asPath, remappedJar.get().asFile.absolutePath) + deps.map { it.absolutePath }).joinToString(":"))
    }
}

// Which java.* calls in the core do not exist on Android? (NoSuchMethodError audit against android.jar)
tasks.register<JavaExec>("apiAudit") {
    group = "verification"
    dependsOn(remapAwt)
    classpath = sourceSets["tool"].runtimeClasspath
    mainClass.set("ApiAudit")
    val androidJar = System.getProperty("user.home") + "/Android/Sdk/platforms/android-36/android.jar"
    argumentProviders.add(CommandLineArgumentProvider { listOf(remappedJar.get().asFile.absolutePath, androidJar) })
}
