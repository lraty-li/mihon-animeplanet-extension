import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "AnimePlanet"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "Anime-Planet"
        baseUrl = "https://www.anime-planet.com"
        lang = "en"
    }

    deeplink {
        path("/..*")
    }
}
