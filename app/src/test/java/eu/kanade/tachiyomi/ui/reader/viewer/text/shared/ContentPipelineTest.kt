@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.reader.viewer.text.shared

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class ContentPipelineTest {

    private fun fakePref(value: String): Preference<String> = mockk {
        every { get() } returns value
    }

    private fun pipelineWith(regexRulesJson: String = "[]"): ContentPipeline {
        val prefs = mockk<ReaderPreferences>()
        every { prefs.novelRegexReplacements } returns fakePref(regexRulesJson)
        return ContentPipeline(prefs)
    }

    private fun cfg(
        url: String? = "chapter.html",
        target: RenderTarget = RenderTarget.TEXT_VIEW,
        hideTitle: Boolean = false,
        forceLowercase: Boolean = false,
        blockMedia: Boolean = false,
        keepEmbeddedCss: Boolean = true,
        keepEmbeddedJs: Boolean = false,
    ) = ContentConfig(
        chapterUrl = url,
        chapterName = "Chapter 1",
        target = target,
        hideTitle = hideTitle,
        forceLowercase = forceLowercase,
        blockMedia = blockMedia,
        keepEmbeddedCss = keepEmbeddedCss,
        keepEmbeddedJs = keepEmbeddedJs,
    )

    // ── Regex replacements always apply (regression: skipped in pre-fetch path) ─────

    @Test
    fun `regex rules apply to TEXT_VIEW target`() = runBlocking {
        @Suppress("ktlint:standard:max-line-length")
        val rules = """[{"title":"r","pattern":"foo","replacement":"bar","isRegex":false,"enabled":true,"caseSensitive":false,"matchWholeWord":false}]"""
        val pipeline = pipelineWith(rules)
        val result = pipeline.process("<p>foo</p>", cfg(url = "x.html", target = RenderTarget.TEXT_VIEW))
        assertTrue(result.text.contains("bar"))
        assertFalse(result.text.contains("foo"))
    }

    @Test
    fun `regex rules apply to WEB_VIEW target`() = runBlocking {
        @Suppress("ktlint:standard:max-line-length")
        val rules = """[{"title":"r","pattern":"foo","replacement":"bar","isRegex":false,"enabled":true,"caseSensitive":false,"matchWholeWord":false}]"""
        val pipeline = pipelineWith(rules)
        val result = pipeline.process("<p>foo</p>", cfg(url = "x.html", target = RenderTarget.WEB_VIEW))
        assertTrue(result.text.contains("bar"))
        assertFalse(result.text.contains("foo"))
    }

    @Test
    fun `regex rules apply to plain-text chapters`() = runBlocking {
        @Suppress("ktlint:standard:max-line-length")
        val rules = """[{"title":"r","pattern":"foo","replacement":"bar","isRegex":false,"enabled":true,"caseSensitive":false,"matchWholeWord":false}]"""
        val pipeline = pipelineWith(rules)
        val result = pipeline.process("foo bar baz", cfg(url = "/path/x.txt"))
        assertTrue(result.isPlainText)
        assertTrue(result.text.contains("bar"))
    }

    // ── Pipeline ordering: title stripped before sanitize, regex after normalize ─────

    @Test
    fun `hideTitle removes leading heading`() = runBlocking {
        val pipeline = pipelineWith()
        val result = pipeline.process(
            "<h1>Chapter 1</h1><p>Body</p>",
            cfg(hideTitle = true),
        )
        assertFalse(result.text.contains("<h1>"))
        assertTrue(result.text.contains("Body"))
    }

    @Test
    fun `forceLowercase applies after normalize`() = runBlocking {
        val pipeline = pipelineWith()
        val result = pipeline.process("<p>HELLO</p>", cfg(forceLowercase = true))
        assertTrue(result.text.contains("hello"))
        assertFalse(result.text.contains("HELLO"))
    }

    // ── Target-specific sanitize behavior ──────────────────────────────────────────

    @Test
    fun `TEXT_VIEW always strips scripts and styles even when keepEmbedded flags are true`() = runBlocking {
        val pipeline = pipelineWith()
        val raw = "<p>Body</p><script>alert(1)</script><style>p{color:red}</style>"
        val result = pipeline.process(
            raw,
            cfg(target = RenderTarget.TEXT_VIEW, keepEmbeddedCss = true, keepEmbeddedJs = true),
        )
        assertFalse(result.text.contains("<script"))
        assertFalse(result.text.contains("<style"))
    }

    @Test
    fun `WEB_VIEW preserves embedded CSS and JS when flags are true`() = runBlocking {
        val pipeline = pipelineWith()
        val raw = "<p>Body</p><script>alert(1)</script><style>p{color:red}</style>"
        val result = pipeline.process(
            raw,
            cfg(target = RenderTarget.WEB_VIEW, keepEmbeddedCss = true, keepEmbeddedJs = true),
        )
        assertTrue(result.text.contains("<script"))
        assertTrue(result.text.contains("<style"))
    }

    @Test
    fun `WEB_VIEW strips embedded CSS and JS when flags are false`() = runBlocking {
        val pipeline = pipelineWith()
        val raw = "<p>Body</p><script>alert(1)</script><style>p{color:red}</style>"
        val result = pipeline.process(
            raw,
            cfg(target = RenderTarget.WEB_VIEW, keepEmbeddedCss = false, keepEmbeddedJs = false),
        )
        assertFalse(result.text.contains("<script"))
        assertFalse(result.text.contains("<style"))
    }

    @Test
    fun `blockMedia strips img and video for WEB_VIEW`() = runBlocking {
        val pipeline = pipelineWith()
        val result = pipeline.process(
            "<p>Body</p><img src=\"x.jpg\"><video src=\"x.mp4\"></video>",
            cfg(target = RenderTarget.WEB_VIEW, blockMedia = true),
        )
        assertFalse(result.text.contains("<img"))
        assertFalse(result.text.contains("<video"))
    }

    @Test
    fun `comments and entity-encoded ad markers stripped`() = runBlocking {
        val pipeline = pipelineWith()
        val result = pipeline.process(
            "<p>Body <!-- ad here --> end</p>",
            cfg(),
        )
        assertFalse(result.text.contains("<!--"))
    }

    // ── Plain-text mode propagation ────────────────────────────────────────────────

    @Test
    fun `isPlainText is true for txt urls`() = runBlocking {
        val pipeline = pipelineWith()
        val result = pipeline.process("hi", cfg(url = "/path/x.txt"))
        assertTrue(result.isPlainText)
    }

    @Test
    fun `isPlainText is false for html urls`() = runBlocking {
        val pipeline = pipelineWith()
        val result = pipeline.process("<p>hi</p>", cfg(url = "x.html"))
        assertFalse(result.isPlainText)
    }

    @Test
    fun `chapterUrl is carried through unchanged`() = runBlocking {
        val pipeline = pipelineWith()
        val url = "https://example.com/chapter-1.html"
        val result = pipeline.process("<p>hi</p>", cfg(url = url))
        assertEquals(url, result.chapterUrl)
    }

    // ── Pipeline ordering: regex sees post-normalize content ──────────────────────

    @Test
    fun `regex pattern targeting HTML tag matches post-normalize html chapter`() = runBlocking {
        @Suppress("ktlint:standard:max-line-length")
        val rules = """[{"title":"r","pattern":"<p>","replacement":"<P>","isRegex":false,"enabled":true,"caseSensitive":true,"matchWholeWord":false}]"""
        val pipeline = pipelineWith(rules)
        val result = pipeline.process("<p>foo</p>", cfg(url = "x.html"))
        assertTrue(result.text.contains("<P>"))
    }

    @Test
    fun `regex still applies to plain-text chapters where normalize is near-no-op`() = runBlocking {
        // Plain-text mode uses HtmlUtils.normalizePlainTextContent which only strips
        // NUL bytes and normalizes line endings. Regex sees content equivalent to
        // raw input, so a literal rule still matches.
        @Suppress("ktlint:standard:max-line-length")
        val rules = """[{"title":"r","pattern":"foo","replacement":"bar","isRegex":false,"enabled":true,"caseSensitive":true,"matchWholeWord":false}]"""
        val pipeline = pipelineWith(rules)
        val result = pipeline.process("foo\r\nbaz", cfg(url = "/path/foo.txt"))
        assertTrue(result.isPlainText)
        assertTrue(result.text.contains("bar"))
        // CRLF was normalized to LF, then regex matched.
        assertEquals("bar\nbaz", result.text)
    }

    // ── preProcess + finalize: shared cache for two-pass renders ─────────────────

    @Test
    fun `preProcess plus finalize equals process`() = runBlocking {
        val pipeline = pipelineWith()
        val cfg = cfg()
        val direct = pipeline.process("<p>foo</p>", cfg)
        val pre = pipeline.preProcess("<p>foo</p>", cfg)
        val staged = pipeline.finalize(pre, cfg)
        assertEquals(direct.text, staged.text)
        assertEquals(direct.isPlainText, staged.isPlainText)
        assertEquals(direct.chapterUrl, staged.chapterUrl)
    }

    @Test
    fun `pipeline is idempotent on pre-process stage`() {
        val pipeline = pipelineWith()
        val pre1 = pipeline.preProcess("<p>foo</p>", cfg())
        val pre2 = pipeline.preProcess(pre1.text, cfg())
        // Running preProcess again on its own output should not introduce changes
        // for HTML chapters (normalize is passthrough on HTML).
        assertEquals(pre1.text, pre2.text)
    }
}
