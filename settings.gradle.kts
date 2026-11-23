pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "WeChatShadeReply"
includeBuild(file(providers.gradleProperty("couiSource").orNull?.takeIf(String::isNotBlank) ?: "../coui17-main")) {
    dependencySubstitution {
        substitute(module("io.github.suqi8.coui.kmp:coui-ui")).using(project(":coui-ui"))
        substitute(module("io.github.suqi8.coui.kmp:coui-preference")).using(project(":coui-preference"))
    }
}
