plugins {
    id("java")
}

allprojects {
    group = "dev.ngj"
    version = "0.1.0-SNAPSHOT"
}

/**
 * Valhalla JDK Configuration
 * 
 * NGJ targets JDK 28 Early-Access with JEP 401 (Value Objects) preview feature.
 * Pinned build: JDK 28 EA Build 24 (2026-11-12)
 * Download: https://jdk.java.net/28/
 * 
 * Value Objects (JEP 401) is a preview language and VM feature that must be
 * enabled with --enable-preview. This provides:
 * - Value classes declared with the `value` modifier
 * - Value objects that are immutable and lack object identity
 * - `==` operator compares value objects by field values
 * - Sealed hierarchies with PermittedSubclasses attribute
 * 
 * Class-file format for value classes:
 * - Identity classes have ACC_IDENTITY flag (0x0020, formerly ACC_SUPER)
 * - Value classes do NOT have ACC_IDENTITY flag
 * - LoadableDescriptors attribute for early value class loading
 * 
 * Build-time JDK: Use available JDK (21+) for compilation.
 * Runtime JDK: JDK 28 EA with --enable-preview is required.
 * CI/Docker: Uses JDK 28 EA Build 24 for full Valhalla support.
 */
val VALHALLA_JDK_VERSION = 28
val VALHALLA_JDK_BUILD = "24"
val VALHALLA_JDK_DATE = "2026-11-12"

// BUILD_JDK_VERSION for compiling (requires JDK 22+ for java.lang.classfile API)
// VALHALLA_JDK_VERSION (28) for runtime with JEP 401 Value Objects
// CI uses JDK 25 (toolchain) for compilation, JDK 28 EA for runtime tests
// Local builds can override with NGJ_BUILD_JDK_VERSION environment variable
val BUILD_JDK_VERSION = System.getenv("NGJ_BUILD_JDK_VERSION")?.toIntOrNull() ?: 25

subprojects {
    apply(plugin = "java")
    
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(BUILD_JDK_VERSION))
        }
        // Use the same version for source/target compatibility
        val javaVersion = JavaVersion.values().find { it.majorVersion.toInt() == BUILD_JDK_VERSION } 
            ?: JavaVersion.VERSION_21
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    
    repositories {
        mavenCentral()
    }
    
    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        // Enable preview features for JDK 21+ (sealed classes, pattern matching, etc.)
        options.compilerArgs.addAll(listOf(
            "--enable-preview"
        ))
    }
    
    tasks.withType<JavaExec> {
        // Enable preview features at runtime
        jvmArgs("--enable-preview")
    }
    
    tasks.withType<Test> {
        useJUnitPlatform()
        // Enable preview features for tests
        jvmArgs("--enable-preview")
        // Skip JDK version check for tests (build JDK may differ from runtime target)
        environment("NGJ_SKIP_JDK_CHECK", "true")
        // Set class file version for generated code
        // JDK 28 = class file version 72 (target runtime for Valhalla JEP 401)
        // The runtime tests verify loading on JDK 28 with --enable-preview
        val targetClassVersion = System.getenv("NGJ_RUNTIME_CLASS_VERSION")?.toIntOrNull()
            ?: 72  // Default to JDK 28 class file version
        environment("NGJ_RUNTIME_CLASS_VERSION", targetClassVersion.toString())
        
        // Allow setting test JVM version via property (for CI JDK 28 tests)
        val testJavaVersion = project.findProperty("testJavaVersion")?.toString()?.toIntOrNull()
        if (testJavaVersion != null && testJavaVersion >= 28) {
            val jdk28Home = System.getenv("JAVA_HOME_28")
            if (jdk28Home != null) {
                executable = "$jdk28Home/bin/java"
                jvmArgs("--enable-preview")
            }
        }
        
        // Detailed test output for AI loop diagnostics
        testLogging {
            events("passed", "skipped", "failed")
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }
}
