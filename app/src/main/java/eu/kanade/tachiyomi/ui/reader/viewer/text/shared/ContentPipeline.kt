package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import androidx.annotation.WorkerThread
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences

/**
 * Order (kept stable — changing it changes user-visible output for some chapters):
 * stripChapterTitle → normalize → regex replacements → forceLowercase → sanitizeForRender.
 */
class ContentPipeline(private val preferences: ReaderPreferences) {

    suspend fun process(
        raw: String,
        config: ContentConfig,
    ): ProcessedContent = finalize(preProcess(raw, config), config)

    @WorkerThread
    fun preProcess(raw: String, config: ContentConfig): PreProcessed {
        var content = raw
        val plainTextMode = HtmlUtils.isPlainTextChapter(config.chapterUrl)

        if (config.hideTitle) {
            content = HtmlUtils.stripChapterTitle(content, config.chapterName)
        }

        content = if (plainTextMode) {
            HtmlUtils.normalizePlainTextContent(content)
        } else {
            HtmlUtils.normalizeContentForHtml(content, config.chapterUrl)
        }

        content = RegexReplacementsProcessor.apply(content, preferences)

        if (config.forceLowercase) content = content.lowercase()

        return PreProcessed(content, plainTextMode)
    }

    suspend fun finalize(
        pre: PreProcessed,
        config: ContentConfig,
    ): ProcessedContent {
        var content = pre.text

        if (!pre.isPlainText) {
            content = HtmlUtils.sanitizeForRender(
                content,
                target = config.target,
                keepEmbeddedCss = config.keepEmbeddedCss,
                keepEmbeddedJs = config.keepEmbeddedJs,
                blockMedia = config.blockMedia,
            )
        }

        return ProcessedContent(
            text = content,
            isPlainText = pre.isPlainText,
            chapterUrl = config.chapterUrl,
        )
    }

    data class PreProcessed(
        val text: String,
        val isPlainText: Boolean,
    )
}
