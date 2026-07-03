package eu.kanade.domain.manga.interactor

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger

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
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
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
        private val EBOOK_EXTENSIONS = setOf("azw", "azw3", "mobi", "pdf")
        private val CONTENT_EXTENSIONS = HTML_EXTENSIONS + TEXT_EXTENSIONS + EBOOK_EXTENSIONS
        // Max novels processed in parallel — file I/O + DB bound
        private const val CONCURRENCY = 6
    }

    data class NovelCandidate(
        val title: String,
        // Pairs of (chapterName, chapterDir/file) — UniFile refs kept minimal
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

        // Stream novels one top-level dir at a time — never hold all UniFile refs in memory
        val topLevel = root.listFiles().orEmpty().filter { it.isDirectory }
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val restored = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val semaphore = Semaphore(CONCURRENCY)

        // Count total novels up front for progress (cheap — just counts dirs, no recursion)
        val total = countNovels(root)
        val counter = AtomicInteger(0)

        coroutineScope {
            topLevel.map { topDir ->
                async {
                    semaphore.withPermit {
                        processTopDir(
                            context = context,
                            topDir = topDir,
                            localNovelsDir = localNovelsDir,
                            source = source,
                            categoryId = categoryId,
                            total = total,
                            counter = counter,
                            restored = restored,
                            skipped = skipped,
                            errors = errors,
                            onProgress = onProgress,
                        )
                    }
                }
            }.awaitAll()
        }

        if (restored.get() > 0) getLibraryManga.notifyChanged()

        Result(restored = restored.get(), skipped = skipped.get(), errors = errors)
    }

    private suspend fun processTopDir(
        context: Context,
        topDir: UniFile,
        localNovelsDir: UniFile,
        source: eu.kanade.tachiyomi.source.Source,
        categoryId: Long?,
        total: Int,
        counter: AtomicInteger,
        restored: AtomicInteger,
        skipped: AtomicInteger,
        errors: MutableList<String>,
        onProgress: (Int, Int, String) -> Unit,
    ) {
        val topChildren = topDir.listFiles().orEmpty()
        val chapterDirs = topChildren.filter { it.isDirectory && hasContentFiles(it) }
        val directContent = topChildren.filter { it.isFile && isContentFile(it) }

        when {
            chapterDirs.isNotEmpty() -> {
                // topDir is a novel, children are chapter dirs
                val idx = counter.incrementAndGet()
                onProgress(idx, total, topDir.name.orEmpty())
                val chapters = chapterDirs.map { chDir ->
                    ChapterCandidate(name = chDir.name.orEmpty(), files = contentFilesIn(chDir))
                }.sortedBy { it.name }
                restoreNovel(
                    context, NovelCandidate(topDir.name.orEmpty(), chapters),
                    localNovelsDir, source, categoryId, restored, skipped, errors,
                )
            }
            directContent.isNotEmpty() -> {
                // topDir is a novel, content files are the chapters
                val idx = counter.incrementAndGet()
                onProgress(idx, total, topDir.name.orEmpty())
                val chapters = directContent.map { f ->
                    ChapterCandidate(name = f.nameWithoutExtension.orEmpty(), files = listOf(f))
                }.sortedBy { it.name }
                restoreNovel(
                    context, NovelCandidate(topDir.name.orEmpty(), chapters),
                    localNovelsDir, source, categoryId, restored, skipped, errors,
                )
            }
            else -> {
                // topDir might be a source folder — go one level deeper
                topChildren.filter { it.isDirectory }.forEach { novelDir ->
                    val novelChildren = novelDir.listFiles().orEmpty()
                    val novelChapterDirs = novelChildren.filter { it.isDirectory && hasContentFiles(it) }
                    val novelDirectContent = novelChildren.filter { it.isFile && isContentFile(it) }

                    val idx = counter.incrementAndGet()
                    onProgress(idx, total, novelDir.name.orEmpty())

                    when {
                        novelChapterDirs.isNotEmpty() -> {
                            val chapters = novelChapterDirs.map { chDir ->
                                ChapterCandidate(name = chDir.name.orEmpty(), files = contentFilesIn(chDir))
                            }.sortedBy { it.name }
                            restoreNovel(
                                context, NovelCandidate(novelDir.name.orEmpty(), chapters),
                                localNovelsDir, source, categoryId, restored, skipped, errors,
                            )
                        }
                        novelDirectContent.isNotEmpty() -> {
                            val chapters = novelDirectContent.map { f ->
                                ChapterCandidate(name = f.nameWithoutExtension.orEmpty(), files = listOf(f))
                            }.sortedBy { it.name }
                            restoreNovel(
                                context, NovelCandidate(novelDir.name.orEmpty(), chapters),
                                localNovelsDir, source, categoryId, restored, skipped, errors,
                            )
                        }
                        else -> skipped.incrementAndGet()
                    }
                }
            }
        }
    }

    private suspend fun restoreNovel(
        context: Context,
        novel: NovelCandidate,
        localNovelsDir: UniFile,
        source: eu.kanade.tachiyomi.source.Source,
        categoryId: Long?,
        restored: AtomicInteger,
        skipped: AtomicInteger,
        errors: MutableList<String>,
    ) {
        if (novel.chapters.isEmpty()) {
            skipped.incrementAndGet()
            return
        }
        try {
            val sanitized = sanitize(novel.title)

            // Skip if already in library
            val existing = getMangaByUrlAndSourceId.await(sanitized, LOCAL_NOVEL_SOURCE_ID)
            if (existing?.favorite == true) {
                skipped.incrementAndGet()
                return
            }

            val novelDir = localNovelsDir.findFile(sanitized)
                ?: localNovelsDir.createDirectory(sanitized)
            if (novelDir == null) {
                errors.add("${novel.title}: could not create directory")
                return
            }

            novel.chapters.forEach { chapter ->
                val chapterFileName = "${sanitize(chapter.name)}.html"
                if (novelDir.findFile(chapterFileName) != null) return@forEach
                val destFile = novelDir.createFile(chapterFileName) ?: return@forEach
                streamChapterFiles(context, chapter.files, destFile)
            }

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

            restored.incrementAndGet()
        } catch (e: Exception) {
            errors.add("${novel.title}: ${e.message}")
        }
    }

    // ── Discovery (used by scan() preview only) ──────────────────────────────

    private fun discoverNovels(root: UniFile): List<NovelCandidate> {
        val novels = mutableListOf<NovelCandidate>()
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { topDir ->
            val topChildren = topDir.listFiles().orEmpty()
            val chapterDirs = topChildren.filter { it.isDirectory && hasContentFiles(it) }
            val directContent = topChildren.filter { it.isFile && isContentFile(it) }
            when {
                chapterDirs.isNotEmpty() -> novels.add(
                    NovelCandidate(topDir.name.orEmpty(), chapterDirs.map {
                        ChapterCandidate(it.name.orEmpty(), contentFilesIn(it))
                    }.sortedBy { it.name }),
                )
                directContent.isNotEmpty() -> novels.add(
                    NovelCandidate(topDir.name.orEmpty(), directContent.map {
                        ChapterCandidate(it.nameWithoutExtension.orEmpty(), listOf(it))
                    }.sortedBy { it.name }),
                )
                else -> topChildren.filter { it.isDirectory }.forEach { novelDir ->
                    val nc = novelDir.listFiles().orEmpty()
                    val ncd = nc.filter { it.isDirectory && hasContentFiles(it) }
                    val ndf = nc.filter { it.isFile && isContentFile(it) }
                    when {
                        ncd.isNotEmpty() -> novels.add(NovelCandidate(novelDir.name.orEmpty(), ncd.map {
                            ChapterCandidate(it.name.orEmpty(), contentFilesIn(it))
                        }.sortedBy { it.name }))
                        ndf.isNotEmpty() -> novels.add(NovelCandidate(novelDir.name.orEmpty(), ndf.map {
                            ChapterCandidate(it.nameWithoutExtension.orEmpty(), listOf(it))
                        }.sortedBy { it.name }))
                    }
                }
            }
        }
        return novels.distinctBy { it.title }
    }

    /** Quick novel count without materializing chapter/file lists — for progress denominator. */
    private fun countNovels(root: UniFile): Int {
        var count = 0
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { topDir ->
            val topChildren = topDir.listFiles().orEmpty()
            val hasChapterDirs = topChildren.any { it.isDirectory && hasContentFiles(it) }
            val hasDirectContent = topChildren.any { it.isFile && isContentFile(it) }
            when {
                hasChapterDirs || hasDirectContent -> count++
                else -> count += topChildren.count { it.isDirectory }
            }
        }
        return count
    }

    private fun hasContentFiles(dir: UniFile): Boolean {
        // listFiles() once, reuse result — caller must not call contentFilesIn() on same dir
        return dir.listFiles().orEmpty().any { it.isFile && isContentFile(it) }
    }

    private fun isContentFile(file: UniFile): Boolean {
        val ext = file.name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in CONTENT_EXTENSIONS
    }

    private fun contentFilesIn(dir: UniFile): List<UniFile> =
        dir.listFiles().orEmpty().filter { it.isFile && isContentFile(it) }.sortedBy { it.name }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Stream chapter files directly into [dest] without loading into memory. */
    private fun streamChapterFiles(context: Context, files: List<UniFile>, dest: UniFile) {
        if (files.isEmpty()) return
        val separator = "\n\n".toByteArray()
        dest.openOutputStream().buffered().use { out ->
            files.forEachIndexed { i, file ->
                context.contentResolver.openInputStream(file.uri)?.buffered()?.use { it.copyTo(out) }
                if (i < files.size - 1) out.write(separator)
            }
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(200).ifBlank { "Unknown" }

    private val UniFile.nameWithoutExtension: String
        get() = name?.substringBeforeLast('.') ?: name.orEmpty()
}
