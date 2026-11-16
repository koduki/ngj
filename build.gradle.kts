plugins {
    id("java")
}

allprojects {
    group = "dev.ngj"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")
    
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }
    
    repositories {
        mavenCentral()
    }
    
    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("--enable-preview"))
    }
    
    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("--enable-preview")
    }
    
    tasks.withType<JavaExec> {
        jvmArgs("--enable-preview")
    }
}
