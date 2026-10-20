plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Generated Swing/AWT shims (see engine/src/tool/java/GenShims.java); compiled without java.desktop.
val remappedCore = rootProject.file("engine/build/android/kolmafia-core-android.jar")

dependencies {
    api(project(":compat"))
    compileOnly(files(remappedCore).builtBy(":engine:genShims"))
}

tasks.withType<JavaCompile> {
    dependsOn(":engine:genShims")
    options.compilerArgs.addAll(listOf("--limit-modules", "java.base", "-Xmaxerrs", "1000"))
}
