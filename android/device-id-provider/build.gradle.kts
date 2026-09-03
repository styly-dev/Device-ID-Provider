import com.android.build.gradle.LibraryExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library")
    id("maven-publish")
}

group = "com.styly"
version = providers.gradleProperty("VERSION_NAME").get()

extensions.configure<LibraryExtension> {
    namespace = "com.styly.deviceid"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "device-id-provider-android"
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

tasks.register<Copy>("syncUnityAar") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/aar/device-id-provider-release.aar"))
    into(rootProject.projectDir.resolve("../Packages/com.styly.device-id-provider/Plugins/Android"))
    rename { "styly-device-id-provider.aar" }
}
