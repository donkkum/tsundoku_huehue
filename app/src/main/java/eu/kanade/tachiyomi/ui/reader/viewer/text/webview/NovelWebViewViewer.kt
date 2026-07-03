@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import android.annotation.SuppressLint
import android.content.Context
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.Keep
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.text.NovelConfig
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ChapterQueue
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ContentConfig
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ContentPipeline
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ErrorFormatter
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.HtmlUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.NovelPageLoader
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ProcessedContent
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.RenderTarget
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.ThemeUtils
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.handleNovelFlingGesture
import eu.kanade.tachiyomi.ui.reader.viewer.text.shared.localized
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_DIVIDER_CLASS
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_ID_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_NUMBER_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_PATH_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TAG_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_TITLE_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.CHAPTER_URL_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_CHAPTERS_CONTAINER_ID
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_CHAPTER_ATTR
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.TSUNDOKU_OBJECT_NAME
import eu.kanade.tachiyomi.ui.reader.viewer.text.webview.NovelWebViewChapterMeta.quoteForJson
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.novel.TDMR
import uy.kohesive.injekt.injectLazy

class NovelWebViewViewer(val activity: ReaderActivity) : Viewer {

    private companion object {
        const val REMEMBER_MENU_ITEM_ID = 0xBEEF // arbitrary unique ID
        const val ATTR_DATA_EDITABLE = "data-tsundoku-editable"
        const val ID_EDIT_MODE_STYLE = "edit-mode-style"

        fun unescapeJsResult(result: String): String =
            if (result.startsWith("\"") && result.endsWith("\"")) {
                // \\ must come first so \\n stays as backslash+n rather than becoming a newline.
                result.substring(1, result.length - 1)
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
            } else {
                result
            }
    }

    private val container = FrameLayout(activity)
    private lateinit var webView: WebView
    private var loadingIndicator: ReaderProgressIndicator? = null
    private val preferences: ReaderPreferences by injectLazy()
    private val libraryPreferences: tachiyomi.domain.library.service.LibraryPreferences by injectLazy()
    private val contentPipeline = ContentPipeline(preferences)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private var currentPage: ReaderPage? = null
    private var currentChapters: ViewerChapters? = null
    private val imageCache = NovelWebViewImageCache(activity.cacheDir, scope)

    private var lastSavedProgress = 0f

    private var isInfiniteScrollNavigation = false
    private var isInfiniteScrollPrepend = false
    private val chapterQueue = ChapterQueue<ReaderChapter> { it.chapter.id }

    // Lightweight property accessors so existing call sites keep working.
    // Mutations should go through chapterQueue's methods (append / prepend /
    // removeFirst / clear) — they keep the cursor and id-set in sync.
    private val loadedChapters: List<ReaderChapter> get() = chapterQueue.all
    private val loadedChapterIds: Set<Long> get() = chapterQueue.loadedIds
    private var currentChapterIndex: Int
        get() = chapterQueue.currentIndex
        set(value) {
            chapterQueue.currentIndex = value
        }
    private var isLoadingNext: Boolean
        get() = chapterQueue.isLoadingNext
        set(value) {
            chapterQueue.isLoadingNext = value
        }
    private var isDestroyed = false
    private var isEditingMode = false

    private var isAutoScrolling = false
    private var autoScrollJob: Job? = null

    private val config = NovelConfig(scope)
    private val navigator get() = config.navigator

    // True only while loadHtmlContent() has called loadDataWithBaseURL for real chapter content
    // (not the loading-indicator page). Lets onPageFinished distinguish real vs loading loads.
    private var isLoadingRealChapter = false

    // Initialized in [initWebView] after the WebView lateinit is assigned.
    // Was previously `by lazy { ... }` but the lazy initializer ran from
    // inside the WebView's `.apply { }` block (before `webView = …` had
    // completed assignment), causing "lateinit property webView has not been
    // initialized" when toggling rendering mode mid-session.
    private lateinit var styler: NovelWebViewStyler

    private val inlineFeedback by lazy {
        NovelWebViewInlineFeedback(
            scope = scope,
            evaluateJs = { js -> evaluateJavascriptSafe(js, null) },
        )
    }

    var pendingSelectedText: String? = null

    private val gestureDetector = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean = false

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (isEditingMode) return false
                if (!preferences.novelSwipeNavigation.get()) return false
                return handleNovelFlingGesture(
                    e1,
                    e2,
                    velocityX,
                    velocityY,
                    onPrevious = { activity.loadPreviousChapter() },
                    onNext = { activity.loadNextChapter() },
                )
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isEditingMode) return false
                if (e.eventTime - e.downTime >= android.view.ViewConfiguration.getLongPressTimeout()) return true

                val pos = android.graphics.PointF(
                    e.x / container.width.toFloat(),
                    e.y / container.height.toFloat(),
                )

                when (navigator.getAction(pos)) {
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.MENU -> {
                        activity.toggleMenu()
                    }
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.NEXT,
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.RIGHT,
                    -> {
                        webView.evaluateJavascript("window.scrollBy(0, ${(container.height * 0.8).toInt()});", null)
                    }
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.PREV,
                    eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion.LEFT,
                    -> {
                        webView.evaluateJavascript("window.scrollBy(0, -${(container.height * 0.8).toInt()});", null)
                    }
                }

                return true
            }
        },
    ).apply {
        // Disable long press handling so WebView can handle text selection
        setIsLongpressEnabled(false)
    }

    init {
        initWebView()
        observePreferences()

        // NovelConfig swallows the initial navigationMode emit, so this
        // listener now fires only when the user actually changes the nav-mode
        // preference. Always show the preview in that case — opening the
        // reader plainly should NOT re-pop the overlay.
        config.navigationModeChangedListener = {
            activity.binding.navigationOverlay.setNavigation(config.navigator, true)
        }
        // Initial publish so overlay reflects the configured navigator from the
        // start instead of staying on whatever the previous viewer set, but
        // without the show-on-start preview.
        activity.binding.navigationOverlay.setNavigation(config.navigator, false)
        // Brand-new-user one-shot: surface the nav layout on first reader open.
        if (config.forceNavigationOverlay && !activity.tapZonesShownInSession) {
            activity.tapZonesShownInSession = true
            activity.binding.navigationOverlay.setNavigation(config.navigator, true)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun initWebView() {
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // Remove blocksDescendants from reader_activity.xml's viewer_container parent
                // so the WebView can actually receive text input focus.
                (container.parent as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })

        webView = object : WebView(activity) {
            override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
                if (!preferences.novelTextSelectable.get() || callback == null) {
                    return super.startActionMode(callback, type)
                }
                // Preserve Callback2 so the floating toolbar anchors correctly to the selection
                val wrapped = if (callback is ActionMode.Callback2) {
                    object : ActionMode.Callback2() {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            val result = callback.onCreateActionMode(mode, menu)
                            menu.add(
                                Menu.NONE,
                                REMEMBER_MENU_ITEM_ID,
                                Menu.NONE,
                                activity.stringResource(TDMR.strings.action_remember),
                            )
                                .setIcon(android.R.drawable.ic_menu_save)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                            return result
                        }
                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
                            callback.onPrepareActionMode(mode, menu)
                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                            if (item.itemId == REMEMBER_MENU_ITEM_ID) {
                                onRememberSelectedText(mode) // pass mode in
                                return true
                            }
                            return callback.onActionItemClicked(mode, item)
                        }
                        override fun onDestroyActionMode(mode: ActionMode) =
                            callback.onDestroyActionMode(mode)

                        // Forward the content rect so the toolbar floats near the selection
                        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) =
                            callback.onGetContentRect(mode, view, outRect)
                    }
                } else {
                    object : ActionMode.Callback {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            val result = callback.onCreateActionMode(mode, menu)
                            menu.add(
                                Menu.NONE,
                                REMEMBER_MENU_ITEM_ID,
                                Menu.NONE,
                                activity.stringResource(TDMR.strings.action_remember),
                            )
                                .setIcon(android.R.drawable.ic_menu_save)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                            return result
                        }
                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
                            callback.onPrepareActionMode(mode, menu)
                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                            if (item.itemId == REMEMBER_MENU_ITEM_ID) {
                                onRememberSelectedText()
                                mode.finish()
                                return true
                            }
                            return callback.onActionItemClicked(mode, item)
                        }
                        override fun onDestroyActionMode(mode: ActionMode) =
                            callback.onDestroyActionMode(mode)
                    }
                }
                return super.startActionMode(wrapped, type)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                val shouldBlock = preferences.novelBlockMedia.get()
                blockNetworkImage = shouldBlock
                loadsImagesAutomatically = !shouldBlock
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): android.webkit.WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    val fallbackChapterId =
                        currentPage?.chapter?.chapter?.id ?: currentChapters?.currChapter?.chapter?.id
                    val fallbackLoader = activity.viewModel.state.value.viewerChapters?.currChapter?.pageLoader
                    imageCache.intercept(url, fallbackChapterId, fallbackLoader)?.let { return it }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    hideLoadingIndicator()
                    styler.injectScript { buildTsundokuScript() }
                    styler.injectScrollTracking()
                    restoreScrollPosition()
                    syncShortChapterProgressIfNeeded()
                    if (!preferences.novelInfiniteScroll.get()) {
                        styler.injectNextChapterButton(currentChapters?.nextChapter != null)
                    }
                    if (isEditingMode) {
                        toggleEditMode(true)
                    }
                    // isLoadingRealChapter distinguishes real chapter loads from
                    // showLoadingIndicator() loads — both fire onPageFinished(about:blank)
                    // when the chapter URL is relative, making the URL useless as a guard.
                    if (isLoadingRealChapter) {
                        isLoadingRealChapter = false
                    }
                }
            }

            addJavascriptInterface(this@NovelWebViewViewer.WebViewInterface(), "Android")

            isLongClickable = true

            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
        }

        // Construct the styler now that `webView` has been assigned. Doing this
        // here (instead of as a `by lazy { … }` initializer that referenced
        // `webView`) avoids the "lateinit property webView has not been
        // initialized" crash that fired when the lazy initializer ran from
        // inside the WebView's `.apply { }` block during construction.
        styler = NovelWebViewStyler(
            activity = activity,
            preferences = preferences,
            webView = webView,
            container = container,
            evaluateJs = { js -> evaluateJavascriptSafe(js, null) },
        )
        styler.applyScrollbarSettings()

        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val (themeBgColor, _) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        webView.setBackgroundColor(finalBgColor)
        container.setBackgroundColor(finalBgColor)

        container.addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    private fun observePreferences() {
        NovelWebViewPreferenceObserver(
            preferences = preferences,
            scope = scope,
            onStyleChanged = { styler.injectStyles() },
            onScriptChanged = { styler.injectScript { buildTsundokuScript() } },
            onChapterReloadRequested = {
                // Force a full pipeline re-run so the new prefs take effect.
                // Plain setChapters() would no-op on an already-loaded chapter.
                reloadChapter()
            },
            onBlockMediaChanged = { blockMedia ->
                webView.settings.apply {
                    blockNetworkImage = blockMedia
                    loadsImagesAutomatically = !blockMedia
                }
                webView.reload()
            },
        ).observe()
    }

    private fun restoreScrollPosition() {
        currentPage?.let { page ->
            val savedProgress = page.chapter.chapter.last_page_read
            val isRead = page.chapter.chapter.read

            logcat(LogPriority.DEBUG) {
                "NovelWebViewViewer: Restoring progress, savedProgress=$savedProgress, isRead=$isRead for ${page.chapter.chapter.name}"
            }

            val shouldRestore = if (!isRead) {
                savedProgress > 0 && savedProgress <= 100
            } else {
                libraryPreferences.novelReadProgress100.get() && savedProgress > 0 && savedProgress <= 100
            }
            if (shouldRestore) {
                val progress = savedProgress / 100f
                lastSavedProgress = progress

                webView.postDelayed({
                    val js = """
                        (function() {
                            function scrollable() {
                                var docHeight = Math.max(
                                    document.documentElement.scrollHeight,
                                    document.body ? document.body.scrollHeight : 0
                                );
                                var viewport = window.innerHeight || document.documentElement.clientHeight;
                                return docHeight - viewport;
                            }
                            var range = scrollable();
                            if (range > 0) {
                                window.scrollTo(0, range * $progress);
                            } else {
                                setTimeout(function() {
                                    window.scrollTo(0, scrollable() * $progress);
                                }, 200);
                            }
                        })();
                    """
                    webView.evaluateJavascript(js, null)
                }, 100)
            } else {
                webView.scrollTo(0, 0)
                lastSavedProgress = 0f
            }
        }
    }

    private fun getThemeColors(theme: String): Pair<Int, Int> =
        ThemeUtils.getThemeColors(activity, preferences, theme)

    override fun destroy() {
        // Only persist if real progress exists. lastSavedProgress starts at 0 and stays 0
        // until onPageFinished restores or the user scrolls. Saving 0 here on an early
        // teardown (orientation lock recreates the activity before restore runs) would
        // wipe the chapter's saved progress.
        if (lastSavedProgress > 0f) saveProgress()

        imageCache.clear()

        isDestroyed = true

        scope.cancel()
        webView.destroy()
    }

    private fun evaluateJavascriptSafe(js: String, callback: ((String) -> Unit)? = null) {
        if (isDestroyed) return
        activity.runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            try {
                webView.evaluateJavascript(js, callback)
            } catch (t: Throwable) {
                // WebView may already be destroyed; avoid crashing.
                logcat(LogPriority.WARN) { "NovelWebViewViewer: evaluateJavascript ignored (${t.message})" }
            }
        }
    }

    private fun saveProgress() {
        currentPage?.let { page ->
            val progressValue = (lastSavedProgress * 100).toInt().coerceIn(0, 100)
            activity.saveNovelProgress(page, progressValue)
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Saving progress $progressValue%" }
        }
    }

    private fun shouldAutoMarkShortChapter(page: ReaderPage?): Boolean {
        if (!preferences.novelMarkShortChapterAsRead.get()) return false
        val chapter = page?.chapter?.chapter ?: return false
        return !chapter.read && chapter.last_page_read <= 0
    }

    private fun syncShortChapterProgressIfNeeded() {
        val page = currentPage ?: return
        if (!shouldAutoMarkShortChapter(page)) return
        if (page.status != Page.State.Ready || page.text.isNullOrBlank()) return

        evaluateJavascriptSafe(
            """
            (function() {
                function checkIfShortChapter() {
                    var docHeight = Math.max(
                        document.documentElement.scrollHeight,
                        document.body ? document.body.scrollHeight : 0
                    );
                    var viewport = window.innerHeight || document.documentElement.clientHeight;
                    return docHeight - viewport <= 0;
                }
                var called = false;
                function tryMarkShort() {
                    if (!called && checkIfShortChapter()) {
                        called = true;
                        Android.markChapterAsShort();
                    }
                }
                var resizeObserver = new ResizeObserver(function() {
                    tryMarkShort();
                    if (called) resizeObserver.disconnect();
                });
                resizeObserver.observe(document.body);
                setTimeout(function() {
                    tryMarkShort();
                    resizeObserver.disconnect();
                }, 500);
            })();
            """.trimIndent(),
            null,
        )
    }

    override fun getView(): View = container

    fun reloadContent() {
        val page = currentPage ?: return
        val chapter = currentChapters?.currChapter ?: return
        val content = page.text ?: return
        val cfg = ContentConfig.from(
            preferences,
            RenderTarget.WEB_VIEW,
            chapter.chapter.url,
            chapter.chapter.name,
        )

        scope.launch {
            val processed = withContext(Dispatchers.Default) { contentPipeline.process(content, cfg) }
            loadHtmlContent(processed, chapter)
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        val page = chapters.currChapter.pages?.firstOrNull() ?: return
        val chapterId = chapters.currChapter.chapter.id ?: return

        loadJob?.cancel()

        currentPage = page
        currentChapters = chapters

        val isPrepend = isInfiniteScrollPrepend
        isInfiniteScrollPrepend = false
        isInfiniteScrollNavigation = false

        if (loadedChapterIds.contains(chapterId)) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Chapter $chapterId already loaded, skipping" }
            val index = chapterQueue.indexOf(chapterId)
            if (index >= 0) {
                currentChapterIndex = index
            }
            return
        }

        if (!preferences.novelInfiniteScroll.get() || loadedChapterIds.isEmpty()) {
            chapterQueue.clear()
            currentChapterIndex = 0
        }

        if (page.status == Page.State.Ready && !page.text.isNullOrEmpty()) {
            if (!isPrepend) hideLoadingIndicator()
            displayContent(chapters.currChapter, page, isPrepend, isPrepend)
            if (!isPrepend) activity.viewModel.setNovelVisibleChapter(page.chapter.chapter)
            return
        }

        if (!isPrepend) showLoadingIndicator()

        loadJob = scope.launch {
            val loader = page.chapter.pageLoader
            if (loader == null) {
                logcat(LogPriority.ERROR) { "NovelWebViewViewer: No page loader available" }
                if (!isPrepend) hideLoadingIndicator()
                return@launch
            }

            launch(Dispatchers.IO) {
                loader.loadPage(page)
            }

            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue, Page.State.LoadPage -> {
                        if (!isPrepend) showLoadingIndicator()
                    }
                    Page.State.Ready -> {
                        if (!isPrepend) hideLoadingIndicator()
                        displayContent(chapters.currChapter, page, isPrepend, isPrepend)
                        if (!isPrepend) activity.viewModel.setNovelVisibleChapter(page.chapter.chapter)
                    }
                    is Page.State.Error -> {
                        if (!isPrepend) hideLoadingIndicator()
                        displayError(state.error)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun displayContent(
        chapter: ReaderChapter,
        page: ReaderPage,
        isAppendOrPrepend: Boolean = false,
        isPrepend: Boolean = false,
    ) {
        val rawContent = page.text
        if (rawContent.isNullOrBlank()) {
            displayError(Exception(activity.stringResource(TDMR.strings.novel_error_empty_chapter)))
            return
        }

        val cfg = ContentConfig.from(
            preferences,
            RenderTarget.WEB_VIEW,
            chapter.chapter.url,
            chapter.chapter.name,
        )

        scope.launch {
            val processed = withContext(Dispatchers.Default) {
                contentPipeline.process(rawContent, cfg)
            }
            val chapterId = chapter.chapter.id ?: -1L

            // For infinite-scroll appends/prepends, prefix every tsundoku-novel-image://
            // URL with the chapter ID so that shouldInterceptRequest can resolve the
            // correct loader even when multiple chapters share identical image filenames
            // (e.g. image_0.jpg in both chapter 3 and chapter 4).
            val finalProcessed = if (isAppendOrPrepend &&
                processed.text.contains(NovelWebViewImageCache.URL_SCHEME_NOVEL_IMAGE)
            ) {
                processed.copy(
                    text = processed.text.replace(
                        NovelWebViewImageCache.URL_SCHEME_NOVEL_IMAGE,
                        "${NovelWebViewImageCache.URL_SCHEME_NOVEL_IMAGE}$chapterId/",
                    ),
                )
            } else {
                processed
            }
            imageCache.schedulePrefetch(finalProcessed.text, chapter.chapter.id, page.chapter.pageLoader)

            withContext(Dispatchers.Main) {
                if (isAppendOrPrepend && preferences.novelInfiniteScroll.get()) {
                    if (!loadedChapterIds.contains(chapterId)) {
                        if (isPrepend) {
                            chapterQueue.prepend(chapter)
                        } else {
                            chapterQueue.append(chapter)
                        }
                    }
                    if (isPrepend) {
                        prependHtmlContent(
                            finalProcessed,
                            chapterId,
                            chapter.chapter.name,
                            chapter.chapter.chapter_number,
                            chapter.chapter.url,
                        )
                    } else {
                        appendHtmlContent(
                            finalProcessed,
                            chapterId,
                            chapter.chapter.name,
                            chapter.chapter.chapter_number,
                            chapter.chapter.url,
                        )
                    }
                } else {
                    loadHtmlContent(finalProcessed, chapter)

                    chapterQueue.clear()
                    chapterQueue.append(chapter)
                    currentChapterIndex = 0
                }
            }
        }
    }

    /**
     * Prepend [processed] (already through [ContentPipeline]) to the WebView DOM.
     * No preprocessing is performed here; the content is injected as-is.
     */
    private fun prependHtmlContent(
        processed: ProcessedContent,
        chapterId: Long,
        chapterName: String,
        chapterNumber: Float,
        chapterUrl: String?,
    ) {
        val plainTextMode = processed.isPlainText
        val escapedContent = quoteForJson(processed.text)

        val js = """
            (function() {
                var oldHeight = document.body.scrollHeight;
                var oldScrollY = window.scrollY || window.pageYOffset;

                var chapterElement = document.createElement('${CHAPTER_TAG_NAME}');
                chapterElement.setAttribute('${CHAPTER_ID_ATTR}', '$chapterId');
                chapterElement.setAttribute('${TSUNDOKU_CHAPTER_ATTR}', '1');
                chapterElement.setAttribute('$CHAPTER_TITLE_ATTR', ${quoteForJson(chapterName)});
                chapterElement.setAttribute('$CHAPTER_NUMBER_ATTR', '$chapterNumber');
                chapterElement.setAttribute('$CHAPTER_PATH_ATTR', ${quoteForJson(chapterUrl.orEmpty())});
                chapterElement.setAttribute('$CHAPTER_URL_ATTR', ${quoteForJson(toAbsoluteChapterUrl(chapterUrl))});
                ${if (plainTextMode) "chapterElement.textContent = $escapedContent;" else "chapterElement.innerHTML = $escapedContent;"}

                var divider = document.createElement('div');
                divider.className = '$CHAPTER_DIVIDER_CLASS';
                divider.setAttribute('${CHAPTER_ID_ATTR}', '$chapterId');
                divider.setAttribute('${TSUNDOKU_CHAPTER_ATTR}', '1');
                divider.setAttribute('$CHAPTER_TITLE_ATTR', ${quoteForJson(chapterName)});
                divider.setAttribute('$CHAPTER_NUMBER_ATTR', '$chapterNumber');
                divider.setAttribute('$CHAPTER_PATH_ATTR', ${quoteForJson(chapterUrl.orEmpty())});
                divider.setAttribute('$CHAPTER_URL_ATTR', ${quoteForJson(toAbsoluteChapterUrl(chapterUrl))});

                var firstChild = document.body.firstChild;
                document.body.insertBefore(chapterElement, firstChild);
                document.body.insertBefore(divider, chapterElement);

                setTimeout(function() {
                    var newHeight = document.body.scrollHeight;
                    var diff = newHeight - oldHeight;
                    if (diff > 0) {
                        window.scrollTo(0, oldScrollY + diff);
                    }

                    if (typeof window.updateChapterBoundaries === 'function') {
                        window.updateChapterBoundaries();
                    }
                }, 10);
            })();
        """.trimIndent()

        evaluateJavascriptSafe(js) {
            styler.injectScript { buildTsundokuScript() }
        }

        logcat(LogPriority.DEBUG) {
            "NovelWebViewViewer: Prepended chapter $chapterId (${loadedChapterIds.size} total)"
        }
    }

    private fun appendHtmlContent(processed: ProcessedContent, chapterId: Long, chapterName: String, chapterNumber: Float, chapterUrl: String?) {
        val plainTextMode = processed.isPlainText
        val escapedContent = quoteForJson(processed.text)

        val js = """
            (function() {
                var chaptersContainer = document.getElementById('$TSUNDOKU_CHAPTERS_CONTAINER_ID');
                if (!chaptersContainer) {
                    chaptersContainer = document.createElement('div');
                    chaptersContainer.id = '$TSUNDOKU_CHAPTERS_CONTAINER_ID';
                    while (document.body.firstChild) {
                        chaptersContainer.appendChild(document.body.firstChild);
                    }
                    document.body.appendChild(chaptersContainer);
                }

                var divider = document.createElement('div');
                divider.className = '$CHAPTER_DIVIDER_CLASS';
                divider.setAttribute('$CHAPTER_ID_ATTR', '$chapterId');
                divider.setAttribute('$CHAPTER_TITLE_ATTR', ${quoteForJson(chapterName)});
                divider.setAttribute('$CHAPTER_NUMBER_ATTR', '$chapterNumber');
                divider.setAttribute('$CHAPTER_PATH_ATTR', ${quoteForJson(chapterUrl.orEmpty())});
                divider.setAttribute('$CHAPTER_URL_ATTR', ${quoteForJson(toAbsoluteChapterUrl(chapterUrl))});
                chaptersContainer.appendChild(divider);

                var chapterElement = document.createElement('${CHAPTER_TAG_NAME}');
                chapterElement.setAttribute('${CHAPTER_ID_ATTR}', '$chapterId');
                chapterElement.setAttribute('$CHAPTER_TITLE_ATTR', ${quoteForJson(chapterName)});
                chapterElement.setAttribute('$CHAPTER_NUMBER_ATTR', '$chapterNumber');
                chapterElement.setAttribute('$CHAPTER_PATH_ATTR', ${quoteForJson(chapterUrl.orEmpty())});
                chapterElement.setAttribute('$CHAPTER_URL_ATTR', ${quoteForJson(toAbsoluteChapterUrl(chapterUrl))});
                chapterElement.setAttribute('${TSUNDOKU_CHAPTER_ATTR}', '1');
                ${if (plainTextMode) "chapterElement.textContent = $escapedContent;" else "chapterElement.innerHTML = $escapedContent;"}
                chaptersContainer.appendChild(chapterElement);

                requestAnimationFrame(function() {
                    if (typeof window.updateChapterBoundaries === 'function') {
                        window.updateChapterBoundaries();
                    }
                    if (window.Android && window.Android.onInfiniteScrollAppendComplete) {
                        window.Android.onInfiniteScrollAppendComplete($chapterId);
                    }
                });
            })();
        """.trimIndent()

        evaluateJavascriptSafe(js) {
            styler.injectScript { buildTsundokuScript() }
        }

        logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Appended chapter $chapterId (${loadedChapterIds.size} total)" }
    }

    private suspend fun loadHtmlContent(
        processed: ProcessedContent,
        chapter: ReaderChapter? = null,
    ) {
        val chapterModel = chapter?.chapter
        val chapterId = chapterModel?.id ?: -1L
        val chapterPath = chapterModel?.url.orEmpty()

        val stylePayload = styler.buildPayload()
        webView.setBackgroundColor(stylePayload.backgroundColor)
        container.setBackgroundColor(stylePayload.backgroundColor)

        chapterQueue.clear()
        currentChapterIndex = 0

        // Inputs are gathered on Main (touch viewer state), but the heavy work — the image-URL
        // regex scan and the Jsoup parse + full-document string build — runs off the main thread.
        // For large chapters this was a multi-MB alloc + DOM parse on the UI thread (frame skips).
        val input = NovelWebViewDocumentBuilder.DocumentInput(
            processed = processed,
            chapter = chapter,
            style = stylePayload,
            themeTokens = ThemeUtils.getThemeTokens(activity, preferences, preferences.novelTheme.get()),
            tsundokuScript = buildTsundokuScript(),
            infiniteScrollEnabled = preferences.novelInfiniteScroll.get(),
            blockMedia = preferences.novelBlockMedia.get(),
        )
        val pageLoader = currentPage?.chapter?.pageLoader
        val html = withContext(Dispatchers.Default) {
            imageCache.schedulePrefetch(processed.text, chapterId.takeIf { it != -1L }, pageLoader)
            NovelWebViewDocumentBuilder.assemble(input)
        }

        // Signal to onPageFinished that the next callback is for real chapter content, not
        // the loading-indicator page (which also fires onPageFinished with url="about:blank").
        isLoadingRealChapter = true
        webView.loadDataWithBaseURL(resolveWebViewBaseUrl(chapterPath), html, "text/html", "UTF-8", null)
    }

    private fun resolveWebViewBaseUrl(chapterUrl: String?): String? =
        NovelWebViewChapterMeta.resolveWebViewBaseUrl(chapterUrl, activity.viewModel.manga?.url)

    private fun toAbsoluteChapterUrl(chapterPath: String?): String =
        NovelWebViewChapterMeta.toAbsoluteChapterUrl(chapterPath, activity.viewModel.manga?.url)

    private fun buildTsundokuScript(): String {
        val context = NovelWebViewChapterMeta.TsundokuScriptContext(
            novelUrl = activity.viewModel.manga?.url,
            currentChapter = getCurrentTsundokuChapter(),
            chaptersInOrder = if (loadedChapters.isNotEmpty()) {
                loadedChapters
            } else {
                currentChapters?.currChapter?.let { listOf(it) }.orEmpty()
            },
            isEditingMode = isEditingMode,
            isInfiniteScroll = preferences.novelInfiniteScroll.get(),
            textSelectionBlocked = !preferences.novelTextSelectable.get(),
            forcedLowercase = preferences.novelForceTextLowercase.get(),
        )
        return NovelWebViewChapterMeta.buildTsundokuScript(context)
    }

    private fun getCurrentTsundokuChapter(): ReaderChapter? =
        loadedChapters.getOrNull(currentChapterIndex) ?: currentChapters?.currChapter

    private fun updateChapterMetaJs() {
        val js = buildTsundokuScript()
        evaluateJavascriptSafe("(function(){$js})();", null)
    }

    private fun showLoadingIndicator(message: String = "Loading...") {
        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val fontColor = preferences.novelFontColor.get()
        val (themeBgColor, themeTextColor) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        val finalTextColor = if (fontColor != 0) fontColor else themeTextColor

        val bgColorHex = ThemeUtils.colorToHex(finalBgColor)
        val textColorHex = ThemeUtils.colorToHex(finalTextColor)

        val loadingHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        margin: 0;
                        padding: 16px;
                        background-color: $bgColorHex;
                        color: $textColorHex;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        height: 100vh;
                        font-family: sans-serif;
                    }
                </style>
            </head>
            <body>
                <div>${message.replace("<", "&lt;").replace(">", "&gt;")}</div>
            </body>
            </html>
        """.trimIndent()

        isLoadingRealChapter = false
        webView.loadDataWithBaseURL(null, loadingHtml, "text/html", "UTF-8", null)
    }

    private fun hideLoadingIndicator() {
    }

    private fun displayError(error: Throwable) {
        val fmt = ErrorFormatter.format(error)
        logcat(LogPriority.ERROR) { "NovelWebViewViewer: Chapter load failed\n${fmt.stackTrace}" }

        val theme = preferences.novelTheme.get()
        val backgroundColor = preferences.novelBackgroundColor.get()
        val fontColor = preferences.novelFontColor.get()
        val (themeBgColor, themeTextColor) = getThemeColors(theme)
        val finalBgColor = if (theme == "custom" && backgroundColor != 0) backgroundColor else themeBgColor
        val finalTextColor = if (fontColor != 0) fontColor else themeTextColor
        val bgColorHex = ThemeUtils.colorToHex(finalBgColor)
        val textColorHex = ThemeUtils.colorToHex(finalTextColor)

        val escapedCategory = HtmlUtils.escapeHtml(fmt.category.localized(activity))
        val escapedSummary = HtmlUtils.escapeHtml(fmt.summary)
        val escapedTrace = HtmlUtils.escapeHtml(fmt.stackTrace)
        // Base64-encode the trace so it can be safely passed to the Android JS bridge
        // without worrying about special characters breaking the JS string literal.
        val base64Trace = android.util.Base64.encodeToString(
            fmt.stackTrace.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )

        val errorHtml = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { margin: 0; padding: 24px 16px; background: $bgColorHex; color: $textColorHex; font-family: sans-serif; }
              .err { max-width: 600px; margin: 0 auto; text-align: center; padding-top: 10vh; }
              .category { color: #ff5555; font-size: 18px; font-weight: bold; margin-bottom: 12px; }
              .summary { color: #888; font-size: 14px; margin-bottom: 24px; word-break: break-word; }
              .copy-btn { background: transparent; color: $textColorHex; border: 1px solid #555; border-radius: 8px; padding: 10px 20px; font-size: 14px; cursor: pointer; margin-bottom: 20px; }
              details { text-align: left; margin-top: 4px; }
              summary { cursor: pointer; color: #777; font-size: 13px; padding: 8px 0; user-select: none; }
              pre { background: rgba(0,0,0,0.25); color: #bbb; padding: 12px; border-radius: 6px; font-size: 11px; white-space: pre-wrap; word-break: break-all; max-height: 280px; overflow-y: auto; margin: 0; }
            </style>
            </head>
            <body>
            <div class="err">
              <div class="category">$escapedCategory</div>
              <div class="summary">$escapedSummary</div>
              <button class="copy-btn" onclick="copyErr()">Copy error details</button>
              <details>
                <summary>Technical details</summary>
                <pre>$escapedTrace</pre>
              </details>
            </div>
            <script>
            function copyErr() {
              if (window.Android && window.Android.copyToClipboard) {
                window.Android.copyToClipboard('$base64Trace');
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
    }

    override fun moveToPage(page: ReaderPage) {
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        val scrollAmount = (container.height * 0.30).toInt()

        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (isUp) activity.toggleMenu()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (preferences.novelVolumeKeysScroll.get()) {
                    if (!isUp) webView.evaluateJavascript("window.scrollBy(0, $scrollAmount);", null)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (preferences.novelVolumeKeysScroll.get()) {
                    if (!isUp) webView.evaluateJavascript("window.scrollBy(0, -$scrollAmount);", null)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_SPACE -> {
                if (!isUp) {
                    if (event.isShiftPressed) {
                        webView.pageUp(false)
                    } else {
                        webView.pageDown(false)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (!isUp) webView.pageUp(false)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (!isUp) webView.pageDown(false)
                return true
            }
        }
        return false
    }

    fun toggleEditMode(isEditing: Boolean, save: Boolean = true) {
        if (!isEditing && !save) {
            this.isEditingMode = false
            webView.evaluateJavascript(
                "(function() { window.getSelection().removeAllRanges(); document.activeElement.blur(); })();",
                null,
            )
            webView.clearFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(webView.windowToken, 0)

            // Reload chapter to discard edits
            chapterQueue.clear()
            activity.viewModel.reloadChapter(fromSource = false)
            return
        }

        this.isEditingMode = isEditing
        styler.injectScript { buildTsundokuScript() }
        updateChapterMetaJs()

        if (isEditing) {
            webView.post {
                activity.window.decorView.clearFocus()
                webView.requestFocus()
                webView.requestFocusFromTouch()
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(webView, 0)
                webView.postDelayed({
                    imm?.showSoftInput(webView, 0)
                }, 120)
            }
        } else {
            webView.evaluateJavascript(
                "(function() { window.getSelection().removeAllRanges(); document.activeElement.blur(); })();",
                null,
            )
            webView.clearFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(webView.windowToken, 0)
        }

        val script = """
            (function() {
                function enableEdit() {
                    document.designMode = 'off';
                    var styleId = '${ID_EDIT_MODE_STYLE}';
                    if ('$isEditing' === 'true') {
                        if (!document.getElementById(styleId)) {
                            var style = document.createElement('style');
                            style.id = styleId;
                            style.innerHTML = '${CHAPTER_TAG_NAME}, [${ATTR_DATA_EDITABLE}="1"], body { -webkit-user-select: text !important; user-select: text !important; pointer-events: auto !important; -webkit-tap-highlight-color: transparent; outline: none; } ' +
                                'body { padding-bottom: max(220px, 38vh) !important; }';
                            document.head.appendChild(style);
                        }

                        var editTargets = document.querySelectorAll('${CHAPTER_TAG_NAME}');
                        if (editTargets.length === 0 && document.body) {
                            document.body.setAttribute('contenteditable', 'true');
                            document.body.setAttribute('${ATTR_DATA_EDITABLE}', '1');
                            document.body.setAttribute('tabindex', '0');
                        } else {
                            for (var i = 0; i < editTargets.length; i++) {
                                editTargets[i].setAttribute('contenteditable', 'true');
                                editTargets[i].setAttribute('${ATTR_DATA_EDITABLE}', '1');
                                editTargets[i].setAttribute('tabindex', '0');
                            }
                        }

                        window.$TSUNDOKU_OBJECT_NAME = window.$TSUNDOKU_OBJECT_NAME || {};
                        window.$TSUNDOKU_OBJECT_NAME.runtime = window.$TSUNDOKU_OBJECT_NAME.runtime || {};
                        if (!window.$TSUNDOKU_OBJECT_NAME.runtime.editInputBound) {
                            window.$TSUNDOKU_OBJECT_NAME.runtime.editInputBound = true;
                            var existingListener = window.$TSUNDOKU_OBJECT_NAME.runtime.inputListener;
                            if (existingListener) {
                                document.removeEventListener('input', existingListener);
                            }
                            var inputListener = function(e) {
                                if (window.Android && window.Android.onContentEdited) {
                                    window.Android.onContentEdited();
                                }
                            };
                            document.addEventListener('input', inputListener);
                            window.$TSUNDOKU_OBJECT_NAME.runtime.inputListener = inputListener;
                        }
                    } else {
                        var style = document.getElementById(styleId);
                        if (style) {
                            style.parentNode.removeChild(style);
                        }

                        var editableNodes = document.querySelectorAll('[data-tsundoku-editable="1"]');
                        for (var j = 0; j < editableNodes.length; j++) {
                            editableNodes[j].removeAttribute('contenteditable');
                            editableNodes[j].removeAttribute('${ATTR_DATA_EDITABLE}');
                            editableNodes[j].removeAttribute('tabindex');
                        }

                        var contents = [];
                        var nodes = document.querySelectorAll('${CHAPTER_TAG_NAME}');
                        if (nodes.length > 0) {
                            for (var i = 0; i < nodes.length; i++) {
                                var html = nodes[i].innerHTML;
                                var chapterId = nodes[i].getAttribute('${CHAPTER_ID_ATTR}');
                                contents.push({id: chapterId, content: html});
                            }
                        } else if (document.body) {
                            var currentId = '${currentChapters?.currChapter?.chapter?.id ?: -1}';
                            contents.push({id: currentId, content: document.body.innerHTML});
                        }
                        if (window.Android && window.Android.onSaveEditedContent) {
                            window.Android.onSaveEditedContent(JSON.stringify(contents));
                        }
                    }
                }

                if (document.readyState === 'complete') {
                    enableEdit();
                } else {
                    window.addEventListener('load', enableEdit);
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    @Keep
    @Suppress("unused")
    inner class WebViewInterface {
        @JavascriptInterface
        fun onContentEdited() {
            activity.runOnUiThread {
                activity.viewModel.setHasUnsavedChanges(true)
            }
        }

        @JavascriptInterface
        fun onSaveEditedContent(json: String) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: onSaveEditedContent(length=${json.length})" }
            activity.runOnUiThread {
                activity.viewModel.saveEditedChapterContent(json)
            }
        }

        @JavascriptInterface
        fun onScrollProgress(progress: Float) {
            activity.runOnUiThread {
                lastSavedProgress = progress
                saveProgress()
            }
        }

        @JavascriptInterface
        fun onScrollUpdate(progress: Float) {
            activity.runOnUiThread {
                lastSavedProgress = progress
                activity.onNovelProgressChanged(progress)
            }
        }

        @JavascriptInterface
        fun onChapterScrollUpdate(chapterIndex: Int, progress: Float) {
            activity.runOnUiThread {
                if (chapterIndex != currentChapterIndex && chapterIndex >= 0 && chapterIndex < loadedChapters.size) {
                    val oldIndex = currentChapterIndex
                    currentChapterIndex = chapterIndex
                    logcat(LogPriority.DEBUG) {
                        "NovelWebViewViewer: onChapterScrollUpdate chapterIndex=$chapterIndex progress=$progress (changed from $oldIndex)"
                    }

                    activity.viewModel.setNovelVisibleChapter(loadedChapters.getOrNull(chapterIndex)?.chapter)

                    loadedChapters.getOrNull(chapterIndex)?.pages?.firstOrNull()?.let { page ->
                        currentPage = page
                        activity.onPageSelected(page)
                    }

                    lastSavedProgress = progress
                    activity.onNovelProgressChanged(progress)

                    updateChapterMetaJs()
                }
            }
        }

        @JavascriptInterface
        fun onInfiniteScrollAppendComplete(@Suppress("UNUSED_PARAMETER") chapterId: Long) {
            // No-op: append completion is handled by the viewer directly.
        }

        @JavascriptInterface
        fun loadNextChapter() {
            activity.runOnUiThread {
                logcat(LogPriority.DEBUG) {
                    "NovelWebViewViewer: loadNextChapter triggered, infiniteScroll=${preferences.novelInfiniteScroll.get()}, isLoadingNext=$isLoadingNext, loadedCount=${loadedChapterIds.size}"
                }
                if (!preferences.novelInfiniteScroll.get()) {
                    activity.loadNextChapter()
                } else if (!isLoadingNext) {
                    isLoadingNext = true
                    scope.launch {
                        try {
                            appendNextChapterIfAvailable()
                        } finally {
                            isLoadingNext = false
                            setJsLoadingNext()
                        }
                    }
                } else {
                    logcat(LogPriority.WARN) {
                        "NovelWebViewViewer: loadNextChapter ignored (infiniteScroll=${preferences.novelInfiniteScroll.get()}, isLoadingNext=$isLoadingNext)"
                    }
                }
            }
        }

        @JavascriptInterface
        fun markChapterAsShort() {
            activity.runOnUiThread {
                lastSavedProgress = 1f
                saveProgress()
                activity.onNovelProgressChanged(1f)
                logcat(LogPriority.DEBUG) { "NovelWebViewViewer: Chapter marked as short (fits in viewport)" }

                // Chapter fits in viewport → no scroll events fire → threshold never reached.
                // Trigger infinite scroll append manually.
                if (preferences.novelInfiniteScroll.get() && !isLoadingNext) {
                    isLoadingNext = true
                    scope.launch {
                        try {
                            appendNextChapterIfAvailable()
                        } finally {
                            isLoadingNext = false
                            setJsLoadingNext()
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun copyToClipboard(base64Text: String) {
            activity.runOnUiThread {
                val text = try {
                    android.util.Base64.decode(base64Text, android.util.Base64.DEFAULT)
                        .toString(Charsets.UTF_8)
                } catch (_: Exception) {
                    base64Text
                }
                val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("error", text))
                activity.toast(activity.stringResource(TDMR.strings.novel_error_copied))
            }
        }
    }

    private fun setJsLoadingNext() {
        evaluateJavascriptSafe(
            "(function(){ if (window.$TSUNDOKU_OBJECT_NAME && window.$TSUNDOKU_OBJECT_NAME.runtime && window.$TSUNDOKU_OBJECT_NAME.runtime.setLoadingNext) window.$TSUNDOKU_OBJECT_NAME.runtime.setLoadingNext(false); })();",
            null,
        )
    }

    private suspend fun awaitPageText(page: ReaderPage, loader: PageLoader, timeoutMs: Long): Boolean =
        NovelPageLoader.awaitPageText("NovelWebViewViewer", page, loader, timeoutMs, scope)

    private suspend fun displayContentImmediate(
        chapter: ReaderChapter,
        page: ReaderPage,
        isAppendOrPrepend: Boolean,
        isPrepend: Boolean,
    ) {
        if (isDestroyed) return

        val rawContent = page.text
        if (rawContent.isNullOrBlank()) {
            displayError(Exception(activity.stringResource(TDMR.strings.novel_error_empty_chapter)))
            return
        }

        val chapterId = chapter.chapter.id ?: return

        val cfg = ContentConfig.from(
            preferences,
            RenderTarget.WEB_VIEW,
            chapter.chapter.url,
            chapter.chapter.name,
        )
        val processed = withContext(Dispatchers.Default) {
            contentPipeline.process(rawContent, cfg)
        }
        imageCache.schedulePrefetch(processed.text, chapterId, page.chapter.pageLoader)

        withContext(Dispatchers.Main) {
            if (isDestroyed) return@withContext

            if (isAppendOrPrepend && preferences.novelInfiniteScroll.get()) {
                if (!loadedChapterIds.contains(chapterId)) {
                    if (isPrepend) {
                        return@withContext
                    }
                    chapterQueue.append(chapter)
                }
                appendHtmlContent(
                    processed,
                    chapterId,
                    chapter.chapter.name,
                    chapter.chapter.chapter_number,
                    chapter.chapter.url,
                )
            } else {
                loadHtmlContent(processed, chapter)
                chapterQueue.reset(chapter)
            }
        }
    }

    private suspend fun appendNextChapterIfAvailable(): Boolean {
        val anchor = loadedChapters.lastOrNull() ?: currentChapters?.currChapter ?: run {
            logcat(LogPriority.ERROR) {
                "NovelWebViewViewer: appendNext failed, no anchor chapter (loadedCount=${loadedChapters.size})"
            }
            inlineFeedback.showInlineError("No anchor chapter for infinite scroll", isPrepend = false)
            return false
        }
        logcat(LogPriority.DEBUG) {
            "NovelWebViewViewer: appendNext starting from anchor=${anchor.chapter.id}/${anchor.chapter.name}"
        }

        val preparedChapter = activity.viewModel.prepareNextChapterForInfiniteScroll(anchor) ?: run {
            logcat(LogPriority.WARN) { "NovelWebViewViewer: No next chapter available after ${anchor.chapter.name}" }
            inlineFeedback.showInlineError("No next chapter available", isPrepend = false)
            return false
        }
        val nextId = preparedChapter.chapter.id ?: run {
            logcat(LogPriority.ERROR) { "NovelWebViewViewer: prepared next chapter has null id" }
            inlineFeedback.showInlineError("Chapter has no id", isPrepend = false)
            return false
        }
        logcat(LogPriority.DEBUG) { "NovelWebViewViewer: prepared next=$nextId/${preparedChapter.chapter.name}" }

        if (loadedChapterIds.contains(nextId)) {
            logcat(LogPriority.DEBUG) { "NovelWebViewViewer: next chapter $nextId already loaded, skipping" }
            return true
        }

        val page = preparedChapter.pages?.firstOrNull() ?: run {
            logcat(LogPriority.ERROR) { "NovelWebViewViewer: No page in prepared next chapter" }
            inlineFeedback.showInlineError("No page in next chapter", isPrepend = false)
            return false
        }
        val loader = page.chapter.pageLoader ?: run {
            logcat(LogPriority.ERROR) { "NovelWebViewViewer: No page loader for next chapter" }
            inlineFeedback.showInlineError("No loader for next chapter", isPrepend = false)
            return false
        }

        inlineFeedback.showInlineLoading(isPrepend = false)
        try {
            logcat(LogPriority.DEBUG) {
                "NovelWebViewViewer: loading page for next chapter $nextId, state=${page.status}"
            }
            val loaded = try {
                awaitPageText(page = page, loader = loader, timeoutMs = 30_000)
            } catch (_: TimeoutCancellationException) {
                logcat(LogPriority.ERROR) { "NovelWebViewViewer: Timed out loading next chapter page after 30s" }
                inlineFeedback.showInlineError("Timeout loading next chapter", isPrepend = false)
                false
            } catch (_: CancellationException) {
                logcat(LogPriority.DEBUG) { "NovelWebViewViewer: appendNext cancelled" }
                false
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "NovelWebViewViewer: Error loading next chapter page: ${e.message}" }
                inlineFeedback.showInlineError("Error: ${e.message ?: "Unknown error"}", isPrepend = false)
                false
            }

            if (!loaded) return false

            logcat(LogPriority.DEBUG) {
                "NovelWebViewViewer: appending content for chapter $nextId"
            }
            displayContentImmediate(preparedChapter, page, isAppendOrPrepend = true, isPrepend = false)
            logcat(LogPriority.INFO) {
                "NovelWebViewViewer: Successfully appended next chapter ${preparedChapter.chapter.name}"
            }
            return true
        } finally {
            inlineFeedback.hideInlineLoading(isPrepend = false)
            setJsLoadingNext()
        }
    }

    /**
     * Scroll to the top of the content
     */
    fun scrollToTop() {
        webView.scrollTo(0, 0)
    }

    fun toggleAutoScroll() {
        isAutoScrolling = !isAutoScrolling

        if (isAutoScrolling) {
            startAutoScroll()
        } else {
            stopAutoScroll()
        }
    }

    private fun startAutoScroll() {
        val speed = preferences.novelAutoScrollSpeed.get().coerceIn(1, 10)
        isAutoScrolling = true

        autoScrollJob?.cancel()
        autoScrollJob = scope.launch {
            while (isActive && isAutoScrolling) {
                val scrollAmount = speed
                evaluateJavascriptSafe(
                    """
                    (function() {
                        window.scrollBy(0, $scrollAmount);
                    })();
                    """.trimIndent(),
                    null,
                )
                delay(50L)
            }
        }
    }

    private fun stopAutoScroll() {
        isAutoScrolling = false
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    fun isAutoScrollActive(): Boolean = isAutoScrolling

    fun getProgressPercent(): Int {
        return (lastSavedProgress * 100).toInt().coerceIn(0, 100)
    }

    fun setProgressPercent(percent: Int) {
        val progress = percent.coerceIn(0, 100)
        lastSavedProgress = progress / 100f

        evaluateJavascriptSafe(
            """
            (function() {
                var scrollHeight = document.documentElement.scrollHeight - window.innerHeight;
                var targetScroll = scrollHeight * $progress / 100;
                window.scrollTo(0, targetScroll);
                // A programmatic scrollTo from the slider does not reliably fire the page's
                // 'scroll' listener, so the infinite-scroll threshold check (which lives in
                // that listener) never runs. Dispatch one explicitly so a slider jump to the
                // end of the last loaded chapter loads the next chapter just like a manual scroll.
                window.dispatchEvent(new Event('scroll'));
            })();
            """.trimIndent(),
            null,
        )
    }

    fun reloadChapter() {
        val chapters = currentChapters ?: return
        chapterQueue.clear()
        currentChapterIndex = 0
        setChapters(chapters)
    }

    /**
     * Get the currently selected text from the WebView
     */
    fun getSelectedText(): String? {
        var selectedText: String? = null
        evaluateJavascriptSafe(
            """
            (function() {
                var selection = window.getSelection();
                if (selection && selection.toString().trim()) {
                    return selection.toString().trim();
                }
                return null;
            })();
            """.trimIndent(),
        ) { result ->
            selectedText = unescapeJsResult(result)
        }
        return selectedText
    }

    /**
     * Get the current chapter name for quote context
     */
    fun getCurrentChapterName(): String? {
        val loaded = loadedChapters.getOrNull(currentChapterIndex) ?: return null
        return loaded.chapter.name
    }

    /**
     * Clear text selection in the WebView
     */
    fun clearTextSelection() {
        evaluateJavascriptSafe(
            """
            (function() {
                var selection = window.getSelection();
                if (selection) {
                    selection.removeAllRanges();
                }
            })();
            """.trimIndent(),
            null,
        )
    }

    /**
     * Handle the "Remember" action from text selection menu
     */
    private fun onRememberSelectedText(actionMode: ActionMode? = null) {
        evaluateJavascriptSafe(
            """
        (function() {
            var selection = window.getSelection();
            if (selection && selection.toString().trim()) {
                return selection.toString().trim();
            }
            return null;
        })();
            """.trimIndent(),
        ) { result ->
            activity.runOnUiThread {
                actionMode?.finish() // finish AFTER JS has read the selection
                val selectedText = if (result != "null") unescapeJsResult(result).ifEmpty { null } else null

                if (!selectedText.isNullOrBlank()) {
                    pendingSelectedText = selectedText
                    activity.onRememberSelectedText()
                    clearTextSelection()
                } else {
                    activity.toast("No text selected")
                }
            }
        }
    }
}
