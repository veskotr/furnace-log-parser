plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.beryx.jlink") version "3.1.1"
}

group = "com.vesodev"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

javafx {
    version = "21.0.2"
    modules = listOf(
        "javafx.controls",
        "javafx.graphics",
        "javafx.base"
    )
}

dependencies {
    implementation("com.fazecast:jSerialComm:2.11.0")
    implementation("org.apache.poi:poi-ooxml:5.2.3")
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("io.fair-acc:chartfx:11.3.1")
    implementation("io.fair-acc:dataset:11.3.1")
    implementation("org.kordamp.ikonli:ikonli-fontawesome-pack:12.3.1")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.vesodev.fx.FxMain")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.vesodev.fx.FxMain")
    jvmArgs("--add-modules", "javafx.controls,javafx.graphics,javafx.base")
}

jlink {
    moduleName.set("com.vesodev.furnacemonitor")

    imageZip.set(project.file("${layout.buildDirectory.get()}/distributions/app-${javafx.platform.classifier}.zip"))

    options.set(listOf(
        "--strip-debug",
        "--compress", "2",
        "--no-header-files",
        "--no-man-pages"
    ))

    launcher {
        name = "FurnaceMonitor"
    }

    jpackage {
        installerType = "exe"
        icon = "src/main/resources/furnace.ico"

        imageName = "FurnaceMonitor"

        installerName = "FurnaceMonitorInstaller"

        appVersion = "1.0.0"

        vendor = "VesoDev"

        installerOptions = listOf(
            "--win-per-user-install",
            "--win-dir-chooser",
            "--win-shortcut",
            "--win-menu"
        )

    }
}