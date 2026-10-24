plugins {
    // Lets Gradle download the Java 25 toolchain itself when the machine has an older JDK,
    // instead of failing with "Cannot find a Java installation ... matching {languageVersion=25}".
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mail-mcp-server"
