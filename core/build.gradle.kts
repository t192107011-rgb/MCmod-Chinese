plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
}
tasks.withType<JavaCompile>().configureEach { options.release.set(8) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(kotlin("test-junit"))
}
tasks.register<JavaExec>("rewriteNbt") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("cn.mcworld.translator.core.NbtProbeKt")
    doFirst {
        args(listOf("inputFile", "outputFile", "nbtPath", "replacement").map {
            requireNotNull(project.findProperty(it)) { "Missing -P$it" }.toString()
        })
    }
}
tasks.register<JavaExec>("archiveProbe") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("cn.mcworld.translator.core.ArchiveProbe")
    doFirst {
        args(listOf("archiveAction", "inputFile", "outputFile").map {
            requireNotNull(project.findProperty(it)) { "Missing -P$it" }.toString()
        })
    }
}
