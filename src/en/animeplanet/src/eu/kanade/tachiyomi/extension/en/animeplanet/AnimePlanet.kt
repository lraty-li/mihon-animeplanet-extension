package eu.kanade.tachiyomi.extension.en.animeplanet

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import kotlin.time.Duration.Companion.seconds

@Source
abstract class AnimePlanet : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(1, 2.seconds)
    }

    override val supportsLatest = false

    override suspend fun getPopularManga(page: Int): MangasPage = fetchMangaListing(page = page, query = null)

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), hasNextPage = false)

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val trimmed = query.trim()

        if (trimmed.startsWith(RECOMMENDATION_SCHEMA)) {
            val slug = trimmed.removePrefix(RECOMMENDATION_SCHEMA)
            if (slug.isBlank()) {
                return MangasPage(emptyList(), hasNextPage = false)
            }

            return fetchRecommendations(slug)
        }

        val direct = fetchMangaListing(page = page, query = trimmed)
        if (direct.mangas.isNotEmpty() || !trimmed.hasCjk()) {
            return direct
        }

        return resolveAndSearch(page, trimmed) ?: direct
    }

    private suspend fun resolveAndSearch(page: Int, query: String): MangasPage? {
        val resolvers: List<suspend (String) -> String?> = listOf(
            ::resolveMangaDexAlias,
            ::resolveBangumiAlias,
        )

        for (resolver in resolvers) {
            val alias = resolver(query) ?: continue
            val result = fetchMangaListing(page = page, query = alias)
            if (result.mangas.isNotEmpty()) return result
        }

        return null
    }

    private suspend fun resolveMangaDexAlias(query: String): String? {
        val url = MANGADEX_API.toHttpUrl().newBuilder()
            .addQueryParameter("title", query)
            .addQueryParameter("limit", "1")
            .build()

        val data = client.get(url)
            .parseAs<JsonObject>()["data"]
            ?.jsonArray
            ?: return null

        val attributes = data.firstOrNull()
            ?.jsonObject
            ?.get("attributes")
            ?.jsonObject
            ?: return null

        val titles = buildList {
            attributes["title"]?.jsonObject?.let(::add)
            attributes["altTitles"]?.jsonArray?.forEach { add(it.jsonObject) }
        }

        return listOf("en", "ja-ro", "ja")
            .firstNotNullOfOrNull { lang ->
                titles.firstNotNullOfOrNull { title ->
                    title[lang]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() && it != query }
                }
            }
            ?: titles.firstNotNullOfOrNull { title ->
                title.values.firstNotNullOfOrNull { value ->
                    value.jsonPrimitive.contentOrNull
                        ?.takeIf { it.isNotBlank() && it != query }
                }
            }
    }

    private suspend fun resolveBangumiAlias(query: String): String? {
        val body = buildJsonObject {
            put("keyword", query)
            put("sort", "match")
            putJsonObject("filter") {
                putJsonArray("type") { add(1) }
            }
        }.toJsonRequestBody()

        val results = client.post("$BANGUMI_API/search/subjects?limit=1", body)
            .parseAs<JsonObject>()["data"]
            ?.jsonArray
            ?: return null

        val subject = results.firstOrNull() ?: return null

        val id = subject.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val details = client.get("$BANGUMI_API/subjects/$id").parseAs<JsonObject>()
        val infobox = details["infobox"]?.jsonArray ?: return null

        return infobox.asSequence().flatMap { item ->
            val entry = item.jsonObject
            val key = entry["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (key != "别名" && key != "別名") return@flatMap emptySequence()

            when (val value = entry["value"]) {
                is JsonPrimitive -> value.contentOrNull?.let { sequenceOf(it) } ?: emptySequence()
                is JsonArray -> value.asSequence().mapNotNull { alias ->
                    when (alias) {
                        is JsonPrimitive -> alias.contentOrNull
                        is JsonObject -> alias["v"]?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                }
                else -> emptySequence()
            }
        }
            .firstOrNull { it.isNotBlank() && it != query }
    }

    private fun String.hasCjk() = any { char ->
        char.code in 0x3400..0x9FFF ||
            char.code in 0xF900..0xFAFF ||
            char.code in 0x3040..0x30FF ||
            char.code in 0xAC00..0xD7AF
    }

    private suspend fun fetchMangaListing(page: Int, query: String?): MangasPage {
        val url = "$baseUrl/manga/all".toHttpUrl().newBuilder().apply {
            query
                ?.takeIf { it.isNotBlank() }
                ?.let { addQueryParameter("name", it) }

            if (page > 1) {
                addQueryParameter("page", page.toString())
            }
        }.build()

        val document = client.get(url).asJsoup()
        val mangas = parseMangaCards(document)

        return MangasPage(
            mangas = mangas,
            hasNextPage = document.selectFirst(
                "a.next, a[rel=next], .pagination a:matchesOwn((?i)next|›|»)",
            ) != null,
        )
    }

    private suspend fun fetchRecommendations(slug: String): MangasPage {
        val url = "$baseUrl/manga/$slug/recommendations"
        val document = client.get(url).asJsoup()

        val mangas = parseMangaLinks(document)
            .filterNot { it.url == "/manga/$slug" }

        return MangasPage(
            mangas = mangas,
            hasNextPage = false,
        )
    }

    private fun parseMangaCards(document: Document): List<SManga> = document.select("li.card, .cardDeck .card, .cardGrid .card")
        .mapNotNull(::mangaFromElement)
        .distinctBy { it.url }

    private fun parseMangaLinks(document: Document): List<SManga> = document.select("a[href^=/manga/]")
        .mapNotNull { mangaFromElement(it, initialized = false) }
        .distinctBy { it.url }

    private fun mangaFromElement(element: Element, initialized: Boolean = true): SManga? {
        val link = when {
            element.tagName() == "a" -> element
            else -> element.selectFirst("a[href^=/manga/]") ?: return null
        }

        val path = link.attr("href")
            .substringBefore('?')
            .removeSuffix("/")

        if (!MANGA_PATH_REGEX.matches(path)) return null
        if (path in RESERVED_MANGA_PATHS) return null

        val title = element.selectFirst("h3.cardName, h2, h3")
            ?.text()
            ?.takeIf { it.isNotBlank() }
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: link.text().takeIf { it.isNotBlank() }
            ?: return null

        val image = element.selectFirst("img")

        return SManga.create().apply {
            setUrlWithoutDomain(path)
            this.title = title
            genre = "$RECOMMENDATION_SCHEMA${path.substringAfter("/manga/")}"
            this.initialized = initialized
            thumbnail_url = image?.let {
                it.attr("data-src").takeIf(String::isNotBlank)
                    ?: it.attr("data-original").takeIf(String::isNotBlank)
                    ?: it.absUrl("src").takeIf(String::isNotBlank)
                    ?: it.attr("src").takeIf(String::isNotBlank)
            }
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val path = url.encodedPath.removeSuffix("/")
        if (!MANGA_PATH_REGEX.matches(path)) return null

        val manga = SManga.create().apply {
            setUrlWithoutDomain(path)
        }

        return fetchMangaUpdate(
            manga = manga,
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        ).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails) {
            return SMangaUpdate(manga, emptyList())
        }

        if (manga.title.isNotBlank() && manga.genre.orEmpty().contains(RECOMMENDATION_SCHEMA)) {
            val metadata = resolveRecommendationMetadata(manga.title)
            metadata.title?.let { manga.title = it }
            metadata.author?.let { manga.author = it }
            metadata.thumbnailUrl?.let { manga.thumbnail_url = it }
            if (metadata.author != null) {
                manga.artist = metadata.artist.orEmpty()
            }
            return SMangaUpdate(manga, emptyList())
        }

        val document = client.get(getMangaUrl(manga)).asJsoup()
        val slug = manga.url
            .substringAfter("/manga/")
            .substringBefore('/')

        val parsedGenres = document
            .select(
                "a[href^=/manga/tags/], .tags a[href*=/manga/tags/], " +
                    ".entryBar a[href*=/manga/tags/]",
            )
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val people = document.select("a[href^=/people/], a[href*=/people/]")
            .map {
                it.text()
                    .trim()
                    .replace(PERSON_ROLE_SUFFIX_REGEX, "")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendationTag = "$RECOMMENDATION_SCHEMA$slug"

        val updated = manga.apply {
            title = document.selectFirst("h1")
                ?.text()
                ?.substringBefore(" - Recommendations")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: title

            thumbnail_url = document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.takeIf(String::isNotBlank)
                ?: document.selectFirst("#entry img, .entryBar img, .mainEntry img, img[itemprop=image]")?.let {
                    it.attr("data-src").takeIf(String::isNotBlank)
                        ?: it.absUrl("src").takeIf(String::isNotBlank)
                        ?: it.attr("src").takeIf(String::isNotBlank)
                }
                ?: thumbnail_url

            author = people.joinToString(", ").takeIf { it.isNotBlank() } ?: author

            description = (
                document.selectFirst("meta[property=og:description]")
                    ?.attr("content")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: document.selectFirst("meta[name=description]")
                        ?.attr("content")
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                    ?: document.selectFirst(".entrySynopsis, .synopsis")
                        ?.text()
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                    ?: description
                )?.let { Parser.unescapeEntities(it, false) }

            genre = (parsedGenres + recommendationTag)
                .distinct()
                .joinToString(", ")
        }

        return SMangaUpdate(updated, emptyList())
    }

    private suspend fun resolveRecommendationMetadata(title: String): RecommendationMetadata = resolveBangumiMetadata(title)
        ?: resolveMangaDexMetadata(title)
        ?: RecommendationMetadata()

    private suspend fun resolveBangumiMetadata(title: String): RecommendationMetadata? {
        val body = buildJsonObject {
            put("keyword", title)
            put("sort", "match")
            putJsonObject("filter") {
                putJsonArray("type") { add(1) }
            }
        }.toJsonRequestBody()

        val subjects = client.post("$BANGUMI_API/search/subjects?limit=5", body)
            .parseAs<JsonObject>()["data"]
            ?.jsonArray
            ?: return null

        for (candidate in subjects) {
            val subject = candidate.jsonObject
            val infobox = subject["infobox"]?.jsonArray ?: JsonArray(emptyList())
            val aliases = infobox
                .map { it.jsonObject }
                .filter { entry ->
                    entry["key"]?.jsonPrimitive?.contentOrNull in setOf("别名", "別名")
                }
                .flatMap(::bangumiValues)

            val exactMatch = buildList {
                subject["name"]?.jsonPrimitive?.contentOrNull?.let(::add)
                subject["name_cn"]?.jsonPrimitive?.contentOrNull?.let(::add)
                addAll(aliases)
            }.any { it.equals(title, ignoreCase = true) }
            if (!exactMatch) continue

            val chineseTitle = subject["name_cn"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() && it != title }
            val thumbnailUrl = (subject["images"] as? JsonObject)?.let { images ->
                listOf("common", "large", "medium", "small")
                    .firstNotNullOfOrNull { size ->
                        images[size]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    }
            }

            val authors = mutableListOf<String>()
            val artists = mutableListOf<String>()
            infobox.forEach { item ->
                val entry = item.jsonObject
                when (entry["key"]?.jsonPrimitive?.contentOrNull.orEmpty()) {
                    "作者", "原作" -> authors += bangumiValues(entry)
                    "作画", "作畫" -> artists += bangumiValues(entry)
                }
            }

            return RecommendationMetadata(
                title = chineseTitle,
                author = authors.distinct().joinToString(", ").takeIf(String::isNotBlank),
                artist = artists.distinct().joinToString(", ").takeIf(String::isNotBlank),
                thumbnailUrl = thumbnailUrl,
            )
        }

        return null
    }

    private fun bangumiValues(entry: JsonObject): List<String> = when (val value = entry["value"]) {
        is JsonPrimitive -> listOfNotNull(value.contentOrNull)
        is JsonArray -> value.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> item["v"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }
        else -> emptyList()
    }.filter(String::isNotBlank)

    private suspend fun resolveMangaDexMetadata(title: String): RecommendationMetadata? {
        val url = MANGADEX_API.toHttpUrl().newBuilder()
            .addQueryParameter("title", title)
            .addQueryParameter("limit", "5")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("includes[]", "artist")
            .addQueryParameter("includes[]", "cover_art")
            .build()

        val candidates = client.get(url)
            .parseAs<JsonObject>()["data"]
            ?.jsonArray
            ?: return null

        for (candidate in candidates) {
            val manga = candidate.jsonObject
            val attributes = manga["attributes"]?.jsonObject ?: continue
            val titles = buildList {
                attributes["title"]?.jsonObject?.let(::add)
                attributes["altTitles"]?.jsonArray?.forEach { add(it.jsonObject) }
            }

            val exactMatch = titles.any { localizedTitles ->
                localizedTitles.values.any { value ->
                    value.jsonPrimitive.contentOrNull?.equals(title, ignoreCase = true) == true
                }
            }
            if (!exactMatch) continue

            val chineseTitle = titles.firstNotNullOfOrNull { localizedTitles ->
                localizedTitles.entries.firstNotNullOfOrNull { (language, value) ->
                    value.jsonPrimitive.contentOrNull
                        ?.takeIf { language.startsWith("zh") && it.isNotBlank() }
                }
            }

            val relationships = manga["relationships"]?.jsonArray ?: JsonArray(emptyList())
            fun creatorNames(type: String) = relationships
                .filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == type }
                .mapNotNull { it.jsonObject["attributes"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull }
                .filter(String::isNotBlank)
                .distinct()

            val mangaId = manga["id"]?.jsonPrimitive?.contentOrNull
            val coverFile = relationships
                .firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "cover_art" }
                ?.jsonObject
                ?.get("attributes")
                ?.jsonObject
                ?.get("fileName")
                ?.jsonPrimitive
                ?.contentOrNull
            val thumbnailUrl = if (mangaId != null && coverFile != null) {
                "$MANGADEX_UPLOADS/covers/$mangaId/$coverFile"
            } else {
                null
            }

            return RecommendationMetadata(
                title = chineseTitle,
                author = creatorNames("author").joinToString(", ").takeIf(String::isNotBlank),
                artist = creatorNames("artist").joinToString(", ").takeIf(String::isNotBlank),
                thumbnailUrl = thumbnailUrl,
            )
        }

        return null
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()

    private data class RecommendationMetadata(
        val title: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val thumbnailUrl: String? = null,
    )

    private companion object {
        const val BANGUMI_API = "https://api.bgm.tv/v0"
        const val MANGADEX_API = "https://api.mangadex.org/manga"
        const val MANGADEX_UPLOADS = "https://uploads.mangadex.org"
        const val RECOMMENDATION_SCHEMA = "ap:recommend:"
        val MANGA_PATH_REGEX = Regex("^/manga/[a-z0-9][a-z0-9-]*$", RegexOption.IGNORE_CASE)
        val PERSON_ROLE_SUFFIX_REGEX = Regex("\\s+(?:Author & Artist|Author|Artist)$", RegexOption.IGNORE_CASE)
        val RESERVED_MANGA_PATHS = setOf(
            "/manga/all",
            "/manga/recommendations",
            "/manga/read-online",
            "/manga/read-manga-online",
            "/manga/webtoons",
            "/manga/light-novels",
            "/manga/top-manga",
        )
    }
}
