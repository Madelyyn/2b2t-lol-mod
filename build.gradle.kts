import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec
import java.util.Properties

plugins {
    alias(libs.plugins.fabric.loom)
}

val mcVersionMatrixFile = rootProject.file("gradle/mc-versions.properties")
val mcVersionMatrix = Properties().apply {
    mcVersionMatrixFile.inputStream().use { load(it) }
}

fun matrixValue(key: String): String =
    mcVersionMatrix.getProperty(key)
        ?: throw GradleException("Missing '$key' in ${mcVersionMatrixFile.path}.")

val supportedMcVersions = matrixValue("supported")
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

if (supportedMcVersions.isEmpty()) {
    throw GradleException("No supported Minecraft versions were found in ${mcVersionMatrixFile.path}.")
}

val latestMcVersion = matrixValue("latest")
if (latestMcVersion !in supportedMcVersions) {
    throw GradleException("latest=$latestMcVersion must be listed in supported versions.")
}

val targetMcVersion = ((findProperty("targetMcVersion") as String?)?.trim())
    ?.takeIf { it.isNotEmpty() }
    ?: latestMcVersion

if (targetMcVersion !in supportedMcVersions) {
    throw GradleException(
        "Unsupported targetMcVersion=$targetMcVersion. Supported versions: ${supportedMcVersions.joinToString(", ")}."
    )
}

val yarnMappingsVersion = matrixValue("mc.$targetMcVersion.yarn")
val fabricLoaderVersion = matrixValue("mc.$targetMcVersion.loader")
val meteorVersion = matrixValue("mc.$targetMcVersion.meteor")

base {
    archivesName = properties["archives_base_name"] as String
    version = "${libs.versions.mod.version.get()}-mc$targetMcVersion"
    group = properties["maven_group"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    // Fabric
    minecraft("com.mojang:minecraft:$targetMcVersion")
    mappings("net.fabricmc:yarn:$yarnMappingsVersion:v2")
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")

    // Meteor
    modImplementation("meteordevelopment:meteor-client:$meteorVersion")
}

val buildPerVersionTasks = supportedMcVersions.map { mcVersion ->
    val taskName = "buildMc${mcVersion.replace(".", "_")}"
    tasks.register<Exec>(taskName) {
        group = "build"
        description = "Builds the mod for Minecraft $mcVersion."
        workingDir = rootDir
        val gradlew = if (System.getProperty("os.name").lowercase().contains("windows")) {
            "gradlew.bat"
        } else {
            "./gradlew"
        }
        commandLine(gradlew, "--no-daemon", "build", "-PtargetMcVersion=$mcVersion")
    }
}

buildPerVersionTasks.zipWithNext().forEach { (previous, next) ->
    next.configure { mustRunAfter(previous) }
}

tasks {
    register("buildAll") {
        group = "build"
        description = "Builds all versions"
        dependsOn(buildPerVersionTasks)
    }

    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to targetMcVersion
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}
