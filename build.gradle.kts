plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.0.1"
}

group = "com.aibridge"
version = "0.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
        intellijDependencies()
    }
}

val ktorVersion = "3.0.3"

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2024.3.1")
        
        // Plugin dependencies
        plugin("com.github.copilot:1.7.1-243")
        bundledPlugin("com.intellij.java")
        
        // Instrumentation tools
        instrumentationTools()
    }

    // Ktor Server
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-jackson-jvm:$ktorVersion")
    
    // Provided by IDE or Copilot plugin
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    compileOnly("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")

    // Tests
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

// Exclude overlapping libraries from the bundled output (runtime only)
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-datetime")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-datetime-jvm")
    exclude(group = "org.slf4j", module = "slf4j-api")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false
    
    pluginConfiguration {
        id = "com.aibridge"
        name = "AiBridge"
        vendor {
            name = "AiBridge"
        }

        ideaVersion {
            sinceBuild = "243"
            untilBuild = "261.*"
        }
    }

    publishing {
        token = System.getenv("PUBLISH_TOKEN")
    }
}

tasks {
    patchPluginXml {
        sinceBuild = "243"
        untilBuild = "261.*"
    }

    withType<Test> {
        useJUnitPlatform()
    }
}
