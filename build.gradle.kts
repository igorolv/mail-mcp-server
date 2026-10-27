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
    implementation(libs.lucene.analysis.common)
    implementation(libs.lucene.queryparser)
    implementation(libs.lucene.highlighter)
    implementation(libs.pdfbox)
    implementation(libs.poi.ooxml)
    implementation(libs.poi.scratchpad)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.jar {
    enabled = false
}

tasks.bootJar {
    archiveBaseName.set("mail-mcp-server")
    archiveVersion.set("")
    archiveClassifier.set("")
    // WindowsCredential calls CredReadW through the foreign function API.
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("mail.mime.decodefilename", "true")
    systemProperty("mail.mime.parameters.strict", "false")
    systemProperty("mail.mime.base64.ignoreerrors", "true")
}

// Offline conversion of messages already on disk; never signs in.
tasks.register<JavaExec>("mailReprocess") {
    group = "application"
    description = "Reprocesses one connection's local MIME files without network access"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("mail.mcp.cli.MailReprocessCommand")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) })
}

// Offline rebuild of the derived search index.
tasks.register<JavaExec>("mailReindex") {
    group = "application"
    description = "Rebuilds one connection's local search index without network access"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("mail.mcp.cli.MailReindexCommand")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) })
}

// Signs in to a real mailbox: run by the user only.
tasks.register<JavaExec>("mailSync") {
    group = "application"
    description = "Synchronizes one mailbox into local storage (run by the user only)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("mail.mcp.cli.MailSyncCommand")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) })
}
