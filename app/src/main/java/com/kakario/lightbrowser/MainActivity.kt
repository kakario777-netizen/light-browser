package com.kakario.lightbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** 탭 하나 = 웹뷰 하나 */
class Tab(val webView: WebView) {
    var home = true            // 첫 화면(즐겨찾기)을 보여주는 중인지
    var homeByButton = false   // 홈 버튼으로 첫 화면에 온 경우 → 뒤로가기 시 보던 페이지로 복귀
    var desktop = false
    var openedByLink = false   // 새 창 링크로 열린 탭 → 더 뒤로 갈 곳이 없으면 탭을 닫음
    var pendingUrl: String? = null
    var title = ""
}

class MainActivity : Activity() {

    private lateinit var store: Store
    private val tabs = mutableListOf<Tab>()
    private var cur = 0
    private val tab get() = tabs[cur]

    @Volatile private var adblock = true

    private lateinit var root: LinearLayout
    private lateinit var address: EditText
    private lateinit var reloadBtn: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var content: FrameLayout
    private lateinit var homeView: ScrollView
    private lateinit var homeGrid: GridLayout
    private lateinit var backBtn: ImageButton
    private lateinit var fwdBtn: ImageButton
    private lateinit var tabsBtn: TextView

    private var loading = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = getColor(id)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // ================= 시작 =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        adblock = store.adblock
        buildUi()
        setupWindow()

        val (urls, idx) = store.loadSession()
        if (urls.isEmpty()) {
            newTab(null)
        } else {
            urls.forEach { newTab(if (it == HOME) null else it, select = false) }
            cur = idx.coerceIn(0, tabs.size - 1)
            showTab()
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) {
            i.data?.toString()?.let { newTab(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        saveSession()
        CookieManager.getInstance().flush()
        tabs.getOrNull(cur)?.webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        tabs.getOrNull(cur)?.webView?.onResume()
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.destroy() }
        super.onDestroy()
    }

    private fun saveSession() {
        val urls = tabs.map { t ->
            when {
                t.pendingUrl != null -> t.pendingUrl!!
                t.home -> HOME
                else -> t.webView.url ?: HOME
            }
        }
        store.saveSession(urls, cur)
    }

    // ================= 화면 구성 =================

    private fun iconBtn(res: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(col(R.color.text))
        contentDescription = desc
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    private fun line() = View(this).apply {
        setBackgroundColor(col(R.color.line))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(col(R.color.bar))
            isFocusableInTouchMode = true
        }

        // ---- 위: 주소창 ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(4), dp(6))
        }
        address = EditText(this).apply {
            hint = "검색어 또는 주소 입력"
            setSingleLine(true)
            textSize = 15f
            setTextColor(col(R.color.text))
            setHintTextColor(col(R.color.sub))
            background = rounded(col(R.color.field), dp(20))
            setPadding(dp(16), dp(9), dp(16), dp(9))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSelectAllOnFocus(true)
            setOnFocusChangeListener { _, has ->
                if (has) {
                    val t = tabs.getOrNull(cur) ?: return@setOnFocusChangeListener
                    setText(if (t.home) "" else (t.pendingUrl ?: t.webView.url ?: ""))
                    selectAll()
                } else {
                    updateUi()
                }
            }
            setOnEditorActionListener { _, action, ev ->
                val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_GO || enter) {
                    val q = text.toString().trim()
                    leaveAddress()
                    if (q.isNotEmpty()) load(q)
                    true
                } else false
            }
        }
        reloadBtn = iconBtn(R.drawable.ic_refresh, "새로고침") {
            val t = tab
            if (t.home) return@iconBtn
            if (loading) t.webView.stopLoading() else t.webView.reload()
        }
        top.addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(reloadBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(col(R.color.accent))
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            visibility = View.INVISIBLE
        }

        content = FrameLayout(this)

        // ---- 첫 화면(즐겨찾기 바로가기) ----
        homeGrid = GridLayout(this)
        val homeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(24))
            addView(TextView(context).apply {
                text = "즐겨찾기"
                textSize = 13f
                setTextColor(col(R.color.sub))
                setPadding(dp(6), 0, 0, dp(14))
            })
            addView(homeGrid)
        }
        homeView = ScrollView(this).apply {
            setBackgroundColor(col(R.color.bar))
            addView(homeBox)
        }

        // ---- 아래: 이동 버튼 ----
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        backBtn = iconBtn(R.drawable.ic_back, "뒤로") { goBack() }
        fwdBtn = iconBtn(R.drawable.ic_forward, "앞으로") {
            val t = tab
            if (!t.home && t.webView.canGoForward()) t.webView.goForward()
        }
        val homeBtn = iconBtn(R.drawable.ic_home, "홈") {
            val t = tab
            if (!t.home) {
                t.home = true
                t.homeByButton = true
                showTab()
            }
        }
        tabsBtn = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(col(R.color.text))
            background = rounded(Color.TRANSPARENT, dp(5), dp(2), col(R.color.text))
            contentDescription = "탭 목록"
            setOnClickListener { showTabs() }
            setOnLongClickListener { newTab(null); true }
        }
        val tabsWrap = FrameLayout(this).apply {
            val tv = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { showTabs() }
            addView(tabsBtn, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        val menuBtn = iconBtn(R.drawable.ic_menu, "메뉴") {}
        menuBtn.setOnClickListener { showMenu(menuBtn) }
        listOf(backBtn, fwdBtn, homeBtn, tabsWrap, menuBtn).forEach {
            bottom.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f))
        }

        root.addView(top)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(line())
        root.addView(bottom)
        setContentView(root)
    }

    /** 안드로이드 15(S24·폴드5)는 화면이 상태바 밑까지 그려지므로 여백을 직접 맞춘다 */
    @Suppress("DEPRECATION")
    private fun setupWindow() {
        val light = !isNight()
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            root.setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else {
            window.statusBarColor = col(R.color.bar)
            window.navigationBarColor = col(R.color.bar)
            if (light) {
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }

    private fun leaveAddress() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
        root.requestFocus()
    }

    private fun displayUrl(u: String) = u.removePrefix("https://").removePrefix("http://").removeSuffix("/")

    private fun updateUi() {
        val t = tabs.getOrNull(cur) ?: return
        if (!address.hasFocus()) {
            address.setText(if (t.home) "" else displayUrl(t.pendingUrl ?: t.webView.url ?: ""))
        }
        val canBack = if (t.home) t.homeByButton && t.webView.url != null
        else t.webView.canGoBack() || !t.openedByLink || tabs.size > 1
        backBtn.isEnabled = canBack
        backBtn.alpha = if (canBack) 1f else 0.3f
        val canFwd = !t.home && t.webView.canGoForward()
        fwdBtn.isEnabled = canFwd
        fwdBtn.alpha = if (canFwd) 1f else 0.3f
        reloadBtn.alpha = if (t.home) 0.3f else 1f
        reloadBtn.setImageResource(if (loading && !t.home) R.drawable.ic_close else R.drawable.ic_refresh)
        tabsBtn.text = if (tabs.size > 99) ":D" else tabs.size.toString()
        if (t.home || !loading) progress.visibility = View.INVISIBLE
    }

    // ================= 탭 =================

    private fun newTab(url: String?, select: Boolean = true, byLink: Boolean = false): Tab {
        val t = Tab(createWebView())
        t.openedByLink = byLink
        if (url != null) {
            t.home = false
            t.pendingUrl = toUrl(url)
        }
        tabs.add(t)
        if (select) {
            tabs.getOrNull(cur)?.webView?.onPause()
            cur = tabs.size - 1
            showTab()
        }
        return t
    }

    private fun showTab() {
        val t = tab
        content.removeAllViews()
        (t.webView.parent as? ViewGroup)?.removeView(t.webView)
        if (t.home) {
            buildHome()
            content.addView(homeView)
        } else {
            content.addView(t.webView)
            t.webView.onResume()
            t.pendingUrl?.let {
                t.pendingUrl = null
                t.webView.loadUrl(it)
            }
        }
        loading = false
        updateUi()
    }

    private fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs.removeAt(i)
        (t.webView.parent as? ViewGroup)?.removeView(t.webView)
        t.webView.destroy()
        if (tabs.isEmpty()) {
            cur = 0
            newTab(null)
            return
        }
        if (i < cur) cur--
        if (cur >= tabs.size) cur = tabs.size - 1
        showTab()
    }

    private fun closeAll() {
        tabs.forEach {
            (it.webView.parent as? ViewGroup)?.removeView(it.webView)
            it.webView.destroy()
        }
        tabs.clear()
        cur = 0
        newTab(null)
    }

    private fun tabLabel(t: Tab): Pair<String, String> = when {
        t.home -> Pair("새 탭", "")
        else -> {
            val u = t.pendingUrl ?: t.webView.url ?: ""
            Pair(t.title.ifBlank { displayUrl(u) }, displayUrl(u))
        }
    }

    private fun showTabs() {
        var dlg: AlertDialog? = null
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        tabs.forEachIndexed { i, t ->
            val (title, sub) = tabLabel(t)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(4), dp(10))
                background = if (i == cur) rounded(Color.TRANSPARENT, dp(12), dp(2), col(R.color.accent))
                else rounded(col(R.color.field), dp(12))
                setOnClickListener {
                    dlg?.dismiss()
                    if (i != cur) {
                        tab.webView.onPause()
                        cur = i
                        showTab()
                    }
                }
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 15f
                    setTextColor(col(R.color.text))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (sub.isNotEmpty()) addView(TextView(context).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(col(R.color.sub))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(iconBtn(R.drawable.ic_close, "탭 닫기") {
                dlg?.dismiss()
                closeTab(i)
                showTabs()
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        dlg = AlertDialog.Builder(this)
            .setTitle("열린 탭 ${tabs.size}개")
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("새 탭") { _, _ -> newTab(null) }
            .setNeutralButton("모두 닫기") { _, _ -> closeAll() }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ================= 이동 =================

    private fun toUrl(input: String): String {
        val s = input.trim()
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*").matches(s) || s.startsWith("about:") || s.startsWith("javascript:")) return s
        if (!s.contains(' ') && (s.contains('.') || s.startsWith("localhost"))) return "https://$s"
        return store.searchUrl(s)
    }

    private fun load(input: String) {
        val t = tab
        t.home = false
        t.homeByButton = false
        t.pendingUrl = toUrl(input)
        showTab()
    }

    /** 뒤로 갈 곳이 있으면 이동하고 true */
    private fun goBack(): Boolean {
        val t = tab
        if (t.home) {
            if (t.homeByButton && t.webView.url != null) {
                t.home = false
                t.homeByButton = false
                showTab()
                return true
            }
            return false
        }
        if (t.webView.canGoBack()) {
            t.webView.goBack()
            return true
        }
        if (t.openedByLink && tabs.size > 1) {
            closeTab(cur)
            return true
        }
        if (!t.openedByLink) {
            t.home = true
            t.homeByButton = false
            showTab()
            return true
        }
        return false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> exitFullscreen()
            address.hasFocus() -> leaveAddress()
            goBack() -> {}
            else -> moveTaskToBack(true)
        }
    }

    private fun openExternal(view: WebView, url: String) {
        try {
            if (url.startsWith("intent:")) {
                val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                i.component = null
                i.selector = null
                try {
                    startActivity(i)
                } catch (e: ActivityNotFoundException) {
                    val fb = i.getStringExtra("browser_fallback_url")
                    val pkg = i.`package`
                    when {
                        fb != null -> view.loadUrl(fb)
                        pkg != null -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
                        else -> toast("이 링크를 열 앱이 없어요")
                    }
                }
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            }
        } catch (e: Exception) {
            toast("이 링크를 열 앱이 없어요")
        }
    }

    // ================= 웹뷰 =================

    private fun findTab(v: WebView?) = tabs.firstOrNull { it.webView === v }
    private fun isCurrent(v: WebView?) = tabs.getOrNull(cur)?.let { !it.home && it.webView === v } == true

    private fun applyDark(s: WebSettings) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkening(s, store.darkPages)
        }
    }

    private fun setDesktop(t: Tab, on: Boolean) {
        t.desktop = on
        t.webView.settings.userAgentString = if (on) DESKTOP_UA else null
        if (!t.home) t.webView.reload()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        setBackgroundColor(col(R.color.bar))
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = true
            applyDark(this)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }
    }

    private fun download(url: String, ua: String?, cd: String?, mime: String?) {
        if (!url.startsWith("http")) {
            toast("이 형식의 다운로드는 아직 지원하지 않아요")
            return
        }
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                if (mime != null) setMimeType(mime)
                if (ua != null) addRequestHeader("User-Agent", ua)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("다운로드 시작: $name")
        } catch (e: Exception) {
            toast("다운로드를 시작하지 못했어요")
        }
    }

    inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase() ?: return false
            if (scheme in WEB_SCHEMES) return false
            openExternal(view, request.url.toString())
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (adblock && !request.isForMainFrame && AdBlock.blocked(request.url.host)) return AdBlock.empty()
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (isCurrent(view)) {
                loading = true
                progress.visibility = View.VISIBLE
                updateUi()
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (isCurrent(view)) {
                loading = false
                updateUi()
            }
            if (url != null && url.startsWith("http")) store.addHistory(view.title ?: url, url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (isCurrent(view)) updateUi()
        }
    }

    inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, p: Int) {
            if (!isCurrent(view)) return
            progress.progress = p
            progress.visibility = if (p < 100) View.VISIBLE else View.INVISIBLE
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            findTab(view)?.title = title ?: ""
        }

        /** 새 창으로 열리는 링크 → 새 탭. 사용자가 누르지 않은 팝업은 막는다 */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) {
                toast("팝업을 막았어요")
                return false
            }
            val t = Tab(createWebView())
            t.home = false
            t.openedByLink = true
            tabs.getOrNull(cur)?.webView?.onPause()
            tabs.add(t)
            cur = tabs.size - 1
            showTab()
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = t.webView
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            findTab(window)?.let { closeTab(tabs.indexOf(it)) }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            enterFullscreen(view, callback)
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }

        override fun onShowFileChooser(
            webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(fileChooserParams.createIntent(), REQ_FILE)
                true
            } catch (e: Exception) {
                fileCallback = null
                false
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        }
    }

    // ================= 동영상 전체화면 =================

    private fun enterFullscreen(v: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            cb.onCustomViewHidden()
            return
        }
        customView = v
        customCallback = cb
        v.setBackgroundColor(Color.BLACK)
        (window.decorView as FrameLayout).addView(
            v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        systemBars(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        customView = null
        (window.decorView as FrameLayout).removeView(v)
        customCallback?.onCustomViewHidden()
        customCallback = null
        systemBars(true)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    @Suppress("DEPRECATION")
    private fun systemBars(show: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                if (show) {
                    it.show(WindowInsets.Type.systemBars())
                } else {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        } else {
            window.decorView.systemUiVisibility = if (show) {
                if (!isNight()) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            } else {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }
    }

    // ================= 첫 화면 =================

    private val tileColors = intArrayOf(
        0xFF03C75A.toInt(), 0xFF4285F4.toInt(), 0xFFFF0000.toInt(), 0xFF1E88E5.toInt(),
        0xFF00A495.toInt(), 0xFFE5262D.toInt(), 0xFF8E44AD.toInt(), 0xFFF39C12.toInt(),
    )

    private fun buildHome() {
        homeGrid.removeAllViews()
        val cols = if (resources.configuration.screenWidthDp >= 600) 6 else 4
        homeGrid.columnCount = cols
        val width = (if (content.width > 0) content.width else resources.displayMetrics.widthPixels) - dp(32)
        val cell = width / cols
        val items = store.dials()
        items.forEachIndexed { i, d ->
            val color = tileColors[d.url.hashCode().mod(tileColors.size)]
            val letter = d.title.trim().take(1).ifEmpty { "?" }
            homeGrid.addView(tile(letter, d.title, color, cell, false, { load(d.url) }) {
                AlertDialog.Builder(this)
                    .setTitle(d.title)
                    .setItems(arrayOf("새 탭에서 열기", "수정", "삭제")) { _, which ->
                        when (which) {
                            0 -> newTab(d.url)
                            1 -> editDial(i)
                            2 -> {
                                val l = store.dials(); l.removeAt(i); store.saveDials(l); buildHome()
                            }
                        }
                    }.show()
            })
        }
        homeGrid.addView(tile("+", "추가", col(R.color.field), cell, true, { editDial(-1) }, null))
    }

    private fun tile(
        letter: String, label: String, color: Int, cell: Int, plus: Boolean,
        onClick: () -> Unit, onLong: (() -> Unit)?
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(6), 0, dp(14))
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
        addView(TextView(context).apply {
            text = letter
            gravity = Gravity.CENTER
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (plus) col(R.color.sub) else Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(TextView(context).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(col(R.color.text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), dp(6), dp(4), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setOnClickListener { onClick() }
        if (onLong != null) setOnLongClickListener { onLong(); true }
        layoutParams = GridLayout.LayoutParams().apply { width = cell }
    }

    /** index = -1 이면 새로 추가 */
    private fun editDial(index: Int, presetTitle: String = "", presetUrl: String = "") {
        val list = store.dials()
        val old = list.getOrNull(index)
        val titleEt = EditText(this).apply {
            hint = "이름"
            setSingleLine(true)
            setText(old?.title ?: presetTitle)
        }
        val urlEt = EditText(this).apply {
            hint = "주소 (예: m.naver.com)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(old?.url ?: presetUrl)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(titleEt)
            addView(urlEt)
        }
        AlertDialog.Builder(this)
            .setTitle(if (old == null) "즐겨찾기 추가" else "즐겨찾기 수정")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                var u = urlEt.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                if (!u.contains("://")) u = "https://$u"
                val t = titleEt.text.toString().trim().ifEmpty { displayUrl(u) }
                val l = store.dials()
                if (index in l.indices) l[index] = Store.Item(t, u) else l.add(Store.Item(t, u))
                store.saveDials(l)
                if (tab.home) buildHome()
                toast("저장했어요")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (tabs.isNotEmpty() && tab.home) content.post { buildHome() }
    }

    // ================= 메뉴 =================

    private fun showMenu(anchor: View) {
        val t = tab
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        m.add(0, 1, 0, "새 탭")
        m.add(0, 2, 0, "즐겨찾기에 추가")
        m.add(0, 3, 0, "방문 기록")
        m.add(0, 4, 0, "다운로드 목록")
        m.add(0, 5, 0, "페이지 공유")
        m.add(0, 6, 0, "데스크톱 모드").apply { isCheckable = true; isChecked = t.desktop }
        m.add(0, 7, 0, "광고 차단").apply { isCheckable = true; isChecked = adblock }
        m.add(0, 8, 0, "다크 모드일 때 웹페이지도 어둡게").apply { isCheckable = true; isChecked = store.darkPages }
        m.add(0, 9, 0, "검색 엔진: ${Store.ENGINES[store.engine]}")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> newTab(null)
                2 -> if (t.home) editDial(-1)
                else editDial(-1, t.title, t.webView.url ?: "")
                3 -> showHistory()
                4 -> try {
                    startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                } catch (e: Exception) {
                    toast("다운로드 목록을 열 수 없어요")
                }
                5 -> {
                    val u = t.webView.url
                    if (t.home || u == null) toast("공유할 페이지가 없어요")
                    else startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), "공유"
                    ))
                }
                6 -> setDesktop(t, !t.desktop)
                7 -> {
                    adblock = !adblock
                    store.adblock = adblock
                    toast(if (adblock) "광고 차단 켬" else "광고 차단 끔")
                    if (!t.home) t.webView.reload()
                }
                8 -> {
                    store.darkPages = !store.darkPages
                    tabs.forEach { applyDark(it.webView.settings) }
                    if (!isNight()) toast("폰이 다크 모드일 때 적용돼요")
                }
                9 -> AlertDialog.Builder(this)
                    .setTitle("검색 엔진")
                    .setSingleChoiceItems(Store.ENGINES, store.engine) { d, which ->
                        store.engine = which
                        d.dismiss()
                    }.show()
            }
            true
        }
        pm.show()
    }

    private fun showHistory() {
        val list = store.history()
        if (list.isEmpty()) {
            toast("방문 기록이 없어요")
            return
        }
        val shown = list.take(150)
        AlertDialog.Builder(this)
            .setTitle("방문 기록")
            .setItems(shown.map { "${it.title}\n${displayUrl(it.url)}" }.toTypedArray()) { _, which ->
                load(shown[which].url)
            }
            .setNeutralButton("기록 지우기") { _, _ ->
                store.clearHistory()
                tabs.forEach { it.webView.clearHistory() }
                toast("방문 기록을 지웠어요")
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    companion object {
        private const val HOME = "about:home"
        private const val REQ_FILE = 7
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "file")
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
