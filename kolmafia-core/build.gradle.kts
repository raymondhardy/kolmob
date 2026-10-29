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

dependencies {
    api(project(":compat"))

    implementation("net.sourceforge.htmlcleaner:htmlcleaner:2.29")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("com.alibaba.fastjson2:fastjson2:2.0.66")
    implementation("org.mozilla:rhino:1.9.2")
    implementation("org.slf4j:slf4j-api:2.0.20")
    implementation("org.slf4j:slf4j-nop:2.0.20")
    implementation("org.fusesource.jansi:jansi:2.4.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // SVN-over-WebDAV export (PROPFIND) on Android
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:none", "-Xmaxerrs", "2000"))
}
