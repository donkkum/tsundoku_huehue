package eu.kanade.domain.manga.interactor

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Scans an existing downloads folder for previously downloaded novels (HTML chapters)
 * and rebuilds the library without requiring any network source.
 *
 * Expected folder structure (one or two levels of nesting):
 *   root/Novel Title/Chapter Name/content.html        (source-less or already flat source)
 *   root/Source Name/Novel Title/Chapter/content.html  (old multi-source downloads)
 *
 * Each chapter's HTML files are merged into a single .html file placed in the
 * LocalNovelSource directory, then registered as a library entry.
 */
class RestoreFromDownloads(
    private val storageManager: StorageManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
) {
    companion object {
        private const val LOCAL_NOVEL_SOURCE_ID = 1L
        private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")
        private val TEXT_EXTENSIONS = setOf("txt", "text")
        private val CONTENT_EXTENSIONS = HTML_EXTENSIONS + TEXT_EXTENSIONS
    }

    data class NovelCandidate(
        val title: String,
        val chapters: List<ChapterCandidate>,
    )

    data class ChapterCandidate(
        val name: String,
        val files: List<UniFile>,
    )

    data class Result(
        val restored: Int,
        val skipped: Int,
        val errors: List<String>,
    )

    /** Scan [rootUri] and return novels found without modifying anything. */
    fun scan(context: Context, rootUri: Uri): List<NovelCandidate> {
        val root = UniFile.fromUri(context, rootUri) ?: return emptyList()
        return discoverNovels(root)
    }

    /** Restore all discovered novels into the local library. */
    suspend fun restore(
        context: Context,
        rootUri: Uri,
        categoryId: Long?,
        onProgress: (current: Int, total: Int, title: String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val root = UniFile.fromUri(context, rootUri) ?: return@withContext Result(0, 0, listOf("Could not open folder"))
        val localNovelsDir = storageManager.getLocalNovelSourceDirectory()
            ?: return@withContext Result(0, 0, listOf("Local novels directory not found"))
        val source = sourceManager.get(LOCAL_NOVEL_SOURCE_ID)
            ?: return@withContext Result(0, 0, listOf("Local novel source not found"))

        val novels = discoverNovels(root)
        val errors = mutableListOf<String>()
        var restored = 0
        var skipped = 0

        novels.forEachIndexed { index, novel ->
            onProgress(index + 1, novels.size, novel.title)

            if (novel.chapters.isEmpty()) {
                skipped++
                return@forEachIndexed
            }

            try {
                val sanitized = sanitize(novel.title)
                val novelDir = localNovelsDir.findFile(sanitized) ?: localNovelsDir.createDirectory(sanitized)
                if (novelDir == null) {
                    errors.add("${novel.title}: could not create directory")
                    return@forEachIndexed
                }

                var wroteAny = false
                novel.chapters.forEach { chapter ->
                    val chapterFileName = "${sanitize(chapter.name)}.html"
                    // Skip if already exists (idempotent)
                    if (novelDir.findFile(chapterFileName) != null) return@forEach

                    val content = mergeChapterFiles(context, chapter.files)
                    if (content.isNullOrBlank()) return@forEach

                    val destFile = novelDir.createFile(chapterFileName) ?: return@forEach
                    destFile.openOutputStream().use { it.write(content.toByteArray()) }
                    wroteAny = true
                }

                if (!wroteAny && novel.chapters.all { ch ->
                    novelDir.findFile("${sanitize(ch.name)}.html") != null
                }) {
                    // All chapters already present — still register in DB
                }

                // Register in DB
                val existing = getMangaByUrlAndSourceId.await(sanitized, LOCAL_NOVEL_SOURCE_ID)
                val manga = existing ?: run {
                    val placeholder = eu.kanade.tachiyomi.source.model.SManga.create().apply {
                        title = novel.title
                        url = sanitized
                    }
                    networkToLocalManga(placeholder.toDomainManga(LOCAL_NOVEL_SOURCE_ID, isNovel = true))
                }

                mangaRepository.update(MangaUpdate(id = manga.id, favorite = true, dateAdded = System.currentTimeMillis(), isNovel = true))

                val networkManga = source.getMangaDetails(manga.toSManga())
                updateManga.awaitUpdateFromSource(manga, networkManga, manualFetch = true)

                val chapters = source.getChapterList(manga.toSManga())
                syncChaptersWithSource.await(chapters, manga, source, manualFetch = true)

                if (categoryId != null && categoryId != 0L) {
                    setMangaCategories.await(manga.id, listOf(categoryId))
                }

                restored++
            } catch (e: Exception) {
                errors.add("${novel.title}: ${e.message}")
            }
        }

        Result(restored = restored, skipped = skipped, errors = errors)
    }

    // ── Discovery ────────────────────────────────────────────────────────────

    private fun discoverNovels(root: UniFile): List<NovelCandidate> {
        val topLevel = root.listFiles().orEmpty().filter { it.isDirectory }
        if (topLevel.isEmpty()) return emptyList()

        val novels = mutableListOf<NovelCandidate>()

        topLevel.forEach { topDir ->
            val topChildren = topDir.listFiles().orEmpty()

            // Check if topDir itself is a novel (its children are chapter dirs or html files)
            val chapterDirs = topChildren.filter { it.isDirectory && hasContentFiles(it) }
            val directHtml = topChildren.filter { it.isFile && isContentFile(it) }

            when {
                chapterDirs.isNotEmpty() -> {
                    // topDir = novel title, children = chapter dirs
                    val chapters = chapterDirs.map { chDir ->
                        ChapterCandidate(
                            name = chDir.name.orEmpty(),
                            files = contentFilesIn(chDir),
                        )
                    }.sortedBy { it.name }
                    novels.add(NovelCandidate(title = topDir.name.orEmpty(), chapters = chapters))
                }
                directHtml.isNotEmpty() -> {
                    // topDir = novel, html files are the chapters themselves
                    val chapters = directHtml.map { f ->
                        ChapterCandidate(name = f.nameWithoutExtension.orEmpty(), files = listOf(f))
                    }.sortedBy { it.name }
                    novels.add(NovelCandidate(title = topDir.name.orEmpty(), chapters = chapters))
                }
                else -> {
                    // topDir might be a source folder — go one level deeper
                    topChildren.filter { it.isDirectory }.forEach { novelDir ->
                        val novelChildren = novelDir.listFiles().orEmpty()
                        val novelChapterDirs = novelChildren.filter { it.isDirectory && hasContentFiles(it) }
                        val novelDirectHtml = novelChildren.filter { it.isFile && isContentFile(it) }

                        when {
                            novelChapterDirs.isNotEmpty() -> {
                                val chapters = novelChapterDirs.map { chDir ->
                                    ChapterCandidate(name = chDir.name.orEmpty(), files = contentFilesIn(chDir))
                                }.sortedBy { it.name }
                                novels.add(NovelCandidate(title = novelDir.name.orEmpty(), chapters = chapters))
                            }
                            novelDirectHtml.isNotEmpty() -> {
                                val chapters = novelDirectHtml.map { f ->
                                    ChapterCandidate(name = f.nameWithoutExtension.orEmpty(), files = listOf(f))
                                }.sortedBy { it.name }
                                novels.add(NovelCandidate(title = novelDir.name.orEmpty(), chapters = chapters))
                            }
                        }
                    }
                }
            }
        }

        return novels.distinctBy { it.title }
    }

    private fun hasContentFiles(dir: UniFile): Boolean =
        dir.listFiles().orEmpty().any { it.isFile && isContentFile(it) }

    private fun isContentFile(file: UniFile): Boolean {
        val ext = file.name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in CONTENT_EXTENSIONS
    }

    private fun contentFilesIn(dir: UniFile): List<UniFile> =
        dir.listFiles().orEmpty().filter { it.isFile && isContentFile(it) }.sortedBy { it.name }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun mergeChapterFiles(context: Context, files: List<UniFile>): String? {
        if (files.isEmpty()) return null
        return files.mapNotNull { file ->
            context.contentResolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }
        }.joinToString("\n\n").ifBlank { null }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(200).ifBlank { "Unknown" }

    private val UniFile.nameWithoutExtension: String
        get() = name?.substringBeforeLast('.') ?: name.orEmpty()
}
