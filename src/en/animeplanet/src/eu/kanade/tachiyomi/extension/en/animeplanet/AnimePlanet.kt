package eu.kanade.tachiyomi.extension.en.animeplanet

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
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

        if (trimmed.isEmpty()) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        if (trimmed.startsWith(RECOMMENDATION_SCHEMA)) {
            val slug = trimmed.removePrefix(RECOMMENDATION_SCHEMA)
            if (slug.isBlank()) {
                return MangasPage(emptyList(), hasNextPage = false)
            }

            return fetchRecommendations(slug)
        }

        return fetchMangaListing(page = page, query = trimmed)
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

    private fun parseMangaCards(document: Document): List<SManga> {
        val cards = document.select("li.card, .cardDeck .card, .cardGrid .card")
            .mapNotNull(::mangaFromElement)
            .distinctBy { it.url }

        if (cards.isNotEmpty()) return cards

        return parseMangaLinks(document)
    }

    private fun parseMangaLinks(document: Document): List<SManga> = document.select("a[href^=/manga/]")
        .mapNotNull(::mangaFromElement)
        .distinctBy { it.url }

    private fun mangaFromElement(element: Element): SManga? {
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

    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()

    private companion object {
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
