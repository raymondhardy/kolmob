plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Compile without java.desktop visible: these classes replace parts of Swing/AWT that Android lacks,
// so they must not clash with the JDK's own javax.swing / java.awt packages at compile time.
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--limit-modules", "java.base"))
}
