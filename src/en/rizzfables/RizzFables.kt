package eu.kanade.tachiyomi.extension.en.rizzfables

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import org.jsoup.nodes.Element

@Source
abstract class RizzFables : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/manga-list").asJsoup()

        val mangas = document
            .select("a[href*='/series/']")
            .mapNotNull { parseManga(it) }
            .distinctBy { it.url }

        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get(baseUrl).asJsoup()

        val mangas = document
            .select("a[href*='/series/']")
            .mapNotNull { parseManga(it) }
            .distinctBy { it.url }

        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val searchQuery = query.trim().replace(" ", "+")

        val url = "$baseUrl/Index/live_search?search_value=$searchQuery"

        val document = client.get(url).asJsoup()

        val mangas = document
            .select("a[href*='/series/']")
            .mapNotNull { parseManga(it) }
            .distinctBy { it.url }

        return MangasPage(mangas, false)
    }

    private fun parseManga(element: Element): SManga? {
        val href = element.absUrl("href")

        if (href.isBlank()) {
            return null
        }

        val title = element.selectFirst(".autotitle")?.text()
            ?: element.selectFirst("img")?.attr("alt")
            ?: element.text()

        if (title.isBlank()) {
            return null
        }

        return SManga.create().apply {
            url = href.removePrefix(baseUrl)
            this.title = title
            thumbnail_url = element.selectFirst("img")?.absUrl("src")
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val updatedManga = manga.apply {
            title = document
                .selectFirst("h1.entry-title, .entry-title")
                ?.text()
                ?: title

            thumbnail_url = document
                .selectFirst(".summary_image img, .thumb img")
                ?.absUrl("src")
                ?: thumbnail_url

            description = document
                .selectFirst(".description, .summary_content")
                ?.text()
                ?: ""

            author = document
                .selectFirst(".author-content")
                ?.text()

            artist = document
                .selectFirst(".artist-content")
                ?.text()

            genre = document
                .select(".genres-content a")
                .joinToString { it.text() }

            val pageText = document.text().lowercase()

            status = when {
                "completed" in pageText -> SManga.COMPLETED
                "ongoing" in pageText -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }

        val chapterList = document
            .select("a[href*='/chapter/']")
            .mapNotNull { parseChapter(it) }
            .distinctBy { it.url }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = chapterList,
        )
    }

    private fun parseChapter(element: Element): SChapter? {
        val href = element.absUrl("href")

        if (href.isBlank()) {
            return null
        }

        val title = element.text().trim()

        if (title.isBlank()) {
            return null
        }

        val number = Regex(
            """chapter\s*([0-9]+(?:\.[0-9]+)?)""",
            RegexOption.IGNORE_CASE,
        )
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?: 0f

        return SChapter.create().apply {
            url = href.removePrefix(baseUrl)
            name = title
            chapter_number = number
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        return document
            .select("#readerarea img")
            .mapIndexedNotNull { index, image ->
                val url = image.absUrl("src")

                if (url.isBlank()) {
                    null
                } else {
                    Page(
                        index = index,
                        imageUrl = url,
                    )
                }
            }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.equals("rizzfables.com", ignoreCase = true)) {
            return null
        }

        if (!url.encodedPath.startsWith("/series/")) {
            return null
        }

        val manga = SManga.create().apply {
            this.url = url.encodedPath

            title = url.pathSegments.last()
                .removePrefix("r2311170-")
                .replace("-", " ")
                .replaceFirstChar { it.uppercase() }
        }

        return fetchMangaUpdate(
            manga = manga,
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = true,
        ).manga
    }

    override fun getMangaUrl(manga: SManga): String {
        return if (manga.url.startsWith("http")) {
            manga.url
        } else {
            "$baseUrl${manga.url}"
        }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        return if (chapter.url.startsWith("http")) {
            chapter.url
        } else {
            "$baseUrl${chapter.url}"
        }
    }
}
