buildscript {
  repositories {
    google()
    mavenCentral()
  }
  dependencies {
    // AGP 9 compiles Kotlin itself; this pins the Kotlin version it uses
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
  }
}
plugins {
  id("com.android.application") version "9.5.0" apply false
}