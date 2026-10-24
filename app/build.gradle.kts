import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.kolmafiaandroid"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.kolmafiaandroid"
        minSdk = 33
        targetSdk = 34
        // The release tag (vX.Y.Z, set by CI as GITHUB_REF_NAME) is the version: shown in the app and used for the version code,
        // so each release is a real update. Local and untagged builds are "dev".
        val tagVersion = System.getenv("GITHUB_REF_NAME")?.removePrefix("v")?.takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) }
        versionName = tagVersion ?: "dev"
        versionCode = tagVersion?.split(".")?.map { it.toInt() }?.let { (a, b, c) -> a * 1_000_000 + b * 1_000 + c } ?: 1

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing: put a keystore.properties next to the root build.gradle (it is git-ignored):
    //   storeFile=/path/to/release.jks
    //   storePassword=...
    //   keyAlias=...
    //   keyPassword=...
    // Without it, assembleRelease still works and produces an unsigned APK.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["kolmobHooks"] = "true"
        }
        // Optimised, non-debuggable build signed with the debug key: ART can compile it ahead of time
        // (debuggable apps are limited to the slow 'verify' filter). Keeps the adb test hooks.
        create("fast") {
            initWith(getByName("debug"))
            isDebuggable = false
            manifestPlaceholders["kolmobHooks"] = "true"
            matchingFallbacks += listOf("debug")
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            manifestPlaceholders["kolmobHooks"] = "false"
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes.add("OSGI-INF/l10n/plugin.properties")
            excludes.add("META-INF/AL2.0")
            excludes.add("META-INF/LGPL2.1")
            excludes.add("META-INF/DEPENDENCIES")
            excludes.add("META-INF/LICENSE*")
            excludes.add("META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.7.1")

    // Material 3
    implementation("com.google.android.material:material:1.11.0")

    // Splash screen support
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Sliding tabs via ViewPager2
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Real KoLmafia core (headless, with Swing/AWT/java.net.http replaced by Android shims).
    // The core jar is remapped by :engine:remapAwt (java.awt -> com.kolmob.awt, java.net.http -> com.kolmob.http).
    implementation(project(":compat"))
    implementation(project(":compat-gen"))
    implementation(project(":engine")) { exclude(module = "kolmafia-core") }
    implementation(files(rootProject.file("engine/build/android/kolmafia-core-android.jar")).builtBy(":engine:remapAwt"))
    implementation("net.sourceforge.htmlcleaner:htmlcleaner:2.29")
    implementation("org.jsoup:jsoup:1.23.1")
    implementation("com.alibaba.fastjson2:fastjson2:2.0.59")
    implementation("org.mozilla:rhino:1.9.1")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    implementation("org.fusesource.jansi:jansi:2.4.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.robolectric:robolectric:4.12")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test:core:1.6.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}