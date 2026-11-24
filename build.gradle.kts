plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "com.codereview"
version = "0.1.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.3")
        bundledPlugin("Git4Idea")
        bundledPlugin("com.intellij.platform.vcs")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("com.intellij.mcpServer")
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(25)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.codereview.annotator"
        name = "Code Review Annotator"
        version = project.version.toString()
        description = """
            Add review comments on diffs right from your IDE on a line, a file, or the whole review.
            When you're done, export everything as markdown you can paste into a PR, a chat, or an AI agent.
            Comments show up inline in the editor and persist across restarts.
            <br/><br/>
            Inspired by <a href="https://github.com/agavra/tuicr">tuicr</a>.
        """.trimIndent()
        changeNotes = """
            Initial release.
        """.trimIndent()
        ideaVersion {
            sinceBuild = "263"
            untilBuild = provider { null }
        }
        vendor {
            name = "Code Review"
        }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    test {
        useJUnit()
    }
}
