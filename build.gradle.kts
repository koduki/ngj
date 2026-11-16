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
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }
    
    repositories {
        mavenCentral()
    }
    
    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
    
    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
