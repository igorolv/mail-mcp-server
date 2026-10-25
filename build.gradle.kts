plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "mail.mcp"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.ai.mcp.server)
    implementation(libs.httpclient5)
    implementation(libs.angus.mail)
    implementation(libs.jakarta.mail)
    implementation(libs.lucene.core)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.jar {
    enabled = false
}

tasks.bootJar {
    archiveBaseName.set("mail-mcp-server")
    archiveVersion.set("")
    archiveClassifier.set("")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

// EWS spike (docs/plan.md, stage 1): signs in to a real mailbox, so only the user runs it.
//   .\gradlew.bat ewsSpike --args="work"
// The runnable jar has the same mode: java -jar build\libs\mail-mcp-server.jar ews-spike work
tasks.register<JavaExec>("ewsSpike") {
    group = "application"
    description = "Runs the EWS spike against one connection from connections.json (run by the user only)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("mail.mcp.spike.EwsSpikeCommand")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) })
}

// Signs in to a real mailbox: run by the user only.
tasks.register<JavaExec>("imapSpike") {
    group = "application"
    description = "Runs the read-only IMAP spike (run by the user only)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("mail.mcp.spike.ImapSpikeCommand")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) })
}
