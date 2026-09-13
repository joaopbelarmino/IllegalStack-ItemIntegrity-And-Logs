plugins {
    java
    `java-library`
    id("com.gradleup.shadow") version "8.3.5"

    idea
    eclipse
}

repositories {
    mavenCentral()
    maven {
        name = "OSS Sonatype"
        url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
    }
    maven {
        name = "PaperMC"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "PaperMC"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "ProtocolLib"
        url = uri("https://repo.dmulloy2.net/nexus/repository/public/")
    }
    maven {
        name = "Magic"
        url = uri("https://maven.elmakers.com/repository/")
    }
    maven {
        name = "LeafMC"
        url = uri("https://maven.leafmc.one/snapshots/")
    }
    maven {
        name = "CodeMC"
        url = uri("https://repo.codemc.org/repository/maven-public/")
    }
    maven {
        name = "JitPack"
        url = uri("https://jitpack.io")
    }
}

dependencies {
    compileOnly("cn.dreeam.leaf:leaf-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.dmulloy2:protocollib:5.0.0")
    compileOnly("com.elmakers.mine.bukkit:MagicAPI:10.2")
    compileOnly("de.tr7zw:item-nbt-api-plugin:2.8.0")
    compileOnly("com.github.TheBusyBiscuit:Slimefun4:RC-30") { isTransitive = false }
    compileOnly("io.netty:netty-all:4.1.110.Final") {
        because("The version aligns with the version used by Minecraft itself." +
                "The minecraft server ships netty as well, so we don't need to include it in the jar.")
    }
    compileOnly("com.gmail.nossr50.mcMMO:mcMMO:2.1.217") { isTransitive = false }
    compileOnly("fr.minuskube.inv:smart-invs:1.2.7") { isTransitive = false }
    //compileOnly("com.github.CraftingStore.MinecraftPlugin:core:master-e366d322f8-1")
    compileOnly("com.github.brcdev-minecraft:shopgui-api:3.0.0") { isTransitive = false }

    // SQLite uses JNI class names; retain org.sqlite when embedding the driver.
    implementation("org.xerial:sqlite-jdbc:3.51.3.0")
    // Small Apache-2.0 parser used only for compressed offline playerdata NBT.
    implementation("io.github.canary-prism:querz-nbt:6.2.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("cn.dreeam.leaf:leaf-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testRuntimeOnly("org.apache.logging.log4j:log4j-api:2.24.1")
    testRuntimeOnly("org.apache.logging.log4j:log4j-core:2.24.1")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("Illegalstack-zetramc")
    archiveClassifier.set("")
    mergeServiceFiles()
    relocate("net.querz", "main.java.me.dniym.libs.querz")
    // Nao precisamos de mais nada de sqlite-jdbc alem da classe JDBC em si -
    // isso mantem o jar final pequeno.
    minimize {
        exclude(dependency("org.xerial:sqlite-jdbc:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

val verifySqliteArtifact by tasks.registering(JavaExec::class) {
    dependsOn(tasks.shadowJar, tasks.compileTestJava)
    classpath = files(sourceSets["test"].output.classesDirs, tasks.shadowJar.flatMap { it.archiveFile })
    mainClass.set("main.java.me.dniym.identity.audit.SqliteArtifactProbe")
}

tasks.check {
    dependsOn(verifySqliteArtifact)
}

the<JavaPluginExtension>().toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
}

configurations.all {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}

tasks.compileJava.configure {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.compileTestJava.configure {
    options.encoding = "UTF-8"
}

version = "3.0"

tasks.named<Copy>("processResources") {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
