import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.github")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}

tasks.named("buildPlugin") {
    dependsOn(tasks.test)
}

// Sem isto, a compilação incremental dos testes não percebe parâmetros novos com valor padrão no código principal,
// e os testes quebram com NoSuchMethodError.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    inputs.files(sourceSets.main.get().output.classesDirs)
        .withNormalizer(ClasspathNormalizer::class.java)
        .withPropertyName("mainClassesContent")
    incremental = false
}
