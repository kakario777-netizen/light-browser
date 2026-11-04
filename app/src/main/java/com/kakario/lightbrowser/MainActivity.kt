package com.kakario.lightbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
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
import android.os.Handler
import android.os.Message
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** 탭 하나 = 웹뷰 하나 */
class Tab(val webView: WebView) {
    var home = true            // 첫 화면(즐겨찾기)을 보여주는 중인지
    var homeByButton = false   // 홈 버튼으로 첫 화면에 온 경우 → 뒤로가기 시 보던 페이지로 복귀
    var desktop = false
    var openedByLink = false   // 새 창 링크로 열린 탭 → 더 뒤로 갈 곳이 없으면 탭을 닫음
    var pendingUrl: String? = null
    var title = ""
    var opener: Tab? = null            // 이 탭을 연 탭 (닫으면 여기로 돌아감)
    var allowUrl: String? = null       // 사용자가 직접 입력한 주소는 차단하지 않음
    var dialogCount = 0                // 한 페이지에서 띄운 알림창 수
    var thumb: Bitmap? = null          // 탭 목록 미리보기
}

class MainActivity : Activity() {

    lateinit var store: Store
    private val tabs = mutableListOf<Tab>()
    private var cur = 0
    private val tab get() = tabs[cur]
    val tabList: List<Tab> get() = tabs
    val curIndex get() = cur

    @Volatile private var adblock = true
    val adblockOn get() = adblock

    lateinit var frame: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var address: EditText
    private lateinit var starBtn: ImageButton
    private lateinit var reloadBtn: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var content: FrameLayout
    private lateinit var homeView: ScrollView
    private lateinit var homeGrid: GridLayout
    private lateinit var backBtn: ImageButton
    private lateinit var fwdBtn: ImageButton
    private lateinit var tabsBtn: TextView
    private lateinit var switcher: TabSwitcher
    private lateinit var pages: Pages
    private lateinit var grabber: Grabber
    private lateinit var collector: Collector
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private var loading = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var folderPicked: (() -> Unit)? = null
    private var blobPending = 0

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = getColor(id)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    fun toastMsg(s: String) = toast(s)
    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // ================= 시작 =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        adblock = store.adblock
        AdBlock.load(this)
        Downloads.init(this)
        Downloads.onFinish = { onDownloadFinished(it) }
        if (store.bypass) applyBypass(true, reload = false)
        buildUi()
        setupWindow()
        switcher = TabSwitcher(this)
        pages = Pages(this)
        grabber = Grabber(this)
        collector = Collector(this)

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
            i.data?.toString()?.let {
                closeOverlays()
                newTab(it)
            }
        }
    }

    /** 앱 화면이 지금 보이는지 (화면 꺼짐·홈으로 나가면 false) */
    var isShown = false
        private set

    override fun onPause() {
        super.onPause()
        isShown = false
        saveSession()
        CookieManager.getInstance().flush()
        // 넘기며 모으는 중인 페이지는 멈추지 않는다
        tabs.getOrNull(cur)?.webView?.let { if (!collector.holds(it)) it.onPause() }
    }

    override fun onResume() {
        super.onResume()
        isShown = true
        BgWork.clearDone(this)
        tabs.getOrNull(cur)?.let { if (!it.home) it.webView.onResume() }
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.destroy() }
        closedTab?.first?.webView?.destroy()
        if (Downloads.onFinish != null) Downloads.onFinish = null
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

    fun ripple(v: View, borderless: Boolean = false) {
        val tv = TypedValue()
        theme.resolveAttribute(
            if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground,
            tv, true
        )
        v.setBackgroundResource(tv.resourceId)
    }

    fun iconBtn(res: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(col(R.color.text))
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        ripple(this, borderless = true)
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
        frame = FrameLayout(this).apply { setBackgroundColor(col(R.color.bar)) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(col(R.color.bar))
            isFocusableInTouchMode = true
        }

        // ---- 위: 주소창 + 별(즐겨찾기) + 새로고침/이동 ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(4), dp(6))
        }
        val field = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(col(R.color.field), dp(20))
        }
        address = EditText(this).apply {
            hint = "검색어 또는 주소 입력"
            setSingleLine(true)
            textSize = 15f
            setTextColor(col(R.color.text))
            setHintTextColor(col(R.color.sub))
            background = null
            setPadding(dp(16), dp(9), dp(6), dp(9))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSelectAllOnFocus(true)
            setOnFocusChangeListener { _, has ->
                if (has) {
                    val t = tabs.getOrNull(cur) ?: return@setOnFocusChangeListener
                    setText(if (t.home) "" else (t.pendingUrl ?: t.webView.url ?: ""))
                    selectAll()
                }
                updateUi()
            }
            setOnEditorActionListener { _, action, ev ->
                val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_GO || enter) {
                    goFromAddress()
                    true
                } else false
            }
        }
        starBtn = iconBtn(R.drawable.ic_star_border, "즐겨찾기") { toggleStar() }
        field.addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        field.addView(starBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        reloadBtn = iconBtn(R.drawable.ic_refresh, "새로고침") {
            if (address.hasFocus()) {
                goFromAddress()
                return@iconBtn
            }
            val t = tab
            if (t.home) return@iconBtn
            if (loading) t.webView.stopLoading() else t.webView.reload()
        }
        top.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(reloadBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(col(R.color.accent))
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            visibility = View.INVISIBLE
        }

        content = FrameLayout(this)

        // ---- 첫 화면(즐겨찾기 미리보기) ----
        homeGrid = GridLayout(this)
        val homeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(22), dp(10), dp(24))
            addView(TextView(context).apply {
                text = "즐겨찾기"
                textSize = 13f
                setTextColor(col(R.color.sub))
                setPadding(dp(8), 0, 0, dp(12))
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
                captureCurrentTab()
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
        }
        val tabsWrap = FrameLayout(this).apply {
            ripple(this, borderless = true)
            setOnClickListener { leaveAddress(); switcher.open() }
            setOnLongClickListener { newTab(null); true }
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
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)
    }

    /** 안드로이드 15(S24·폴드5)는 화면이 상태바 밑까지 그려지므로 여백을 직접 맞춘다 */
    @Suppress("DEPRECATION")
    private fun setupWindow() {
        val light = !isNight()
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            frame.setOnApplyWindowInsetsListener { v, ins ->
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

    private fun goFromAddress() {
        val q = address.text.toString().trim()
        leaveAddress()
        if (q.isNotEmpty()) load(q)
    }

    private fun displayUrl(u: String) = u.removePrefix("https://").removePrefix("http://").removeSuffix("/")

    private fun updateUi() {
        val t = tabs.getOrNull(cur) ?: return
        val typing = address.hasFocus()
        if (!typing) {
            address.setText(if (t.home) "" else displayUrl(t.pendingUrl ?: t.webView.url ?: ""))
        }
        val canBack = if (t.home) t.homeByButton && t.webView.url != null
        else t.webView.canGoBack() || !t.openedByLink || tabs.size > 1
        backBtn.isEnabled = canBack
        backBtn.alpha = if (canBack) 1f else 0.3f
        val canFwd = !t.home && t.webView.canGoForward()
        fwdBtn.isEnabled = canFwd
        fwdBtn.alpha = if (canFwd) 1f else 0.3f

        // 입력 중이면 오른쪽 버튼이 '이동'으로 바뀜
        when {
            typing -> {
                reloadBtn.setImageResource(R.drawable.ic_go)
                reloadBtn.alpha = 1f
                reloadBtn.imageTintList = ColorStateList.valueOf(col(R.color.accent))
            }
            else -> {
                reloadBtn.setImageResource(if (loading && !t.home) R.drawable.ic_close else R.drawable.ic_refresh)
                reloadBtn.alpha = if (t.home) 0.3f else 1f
                reloadBtn.imageTintList = ColorStateList.valueOf(col(R.color.text))
            }
        }
        val url = t.webView.url
        starBtn.visibility = if (typing || t.home || url == null || !url.startsWith("http")) View.GONE else View.VISIBLE
        if (starBtn.visibility == View.VISIBLE) {
            val on = dialIndex(url!!) >= 0
            starBtn.setImageResource(if (on) R.drawable.ic_star else R.drawable.ic_star_border)
            starBtn.imageTintList = ColorStateList.valueOf(col(if (on) R.color.accent else R.color.sub))
        }
        tabsBtn.text = if (tabs.size > 99) ":D" else tabs.size.toString()
        if (t.home || !loading) progress.visibility = View.INVISIBLE
    }

    // ================= 알림 막대 (하단) =================

    private var snackView: View? = null
    private var snackHide: Runnable? = null
    private var snackDismissOnTouch = false

    private fun showSnack(text: String, action: String?, ms: Long, dismissOnTouch: Boolean = false, onAction: (() -> Unit)? = null) {
        hideSnack()
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(col(R.color.snack), dp(12))
            elevation = dp(8).toFloat()
            setPadding(dp(16), dp(4), dp(6), dp(4))
            minimumHeight = dp(50)
            isClickable = true
        }
        v.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) {
            v.addView(TextView(this).apply {
                this.text = action
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFF6B70.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                ripple(this)
                setOnClickListener {
                    hideSnack()
                    onAction?.invoke()
                }
            })
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        lp.setMargins(dp(12), 0, dp(12), dp(if (switcher.isOpen) 84 else 62))
        frame.addView(v, lp)
        v.alpha = 0f
        v.translationY = dp(20).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(180).start()
        snackView = v
        snackDismissOnTouch = dismissOnTouch
        val r = Runnable { hideSnack() }
        snackHide = r
        frame.postDelayed(r, ms)
    }

    private fun hideSnack() {
        snackHide?.let { frame.removeCallbacks(it) }
        snackHide = null
        snackView?.let { frame.removeView(it) }
        snackView = null
        snackDismissOnTouch = false
    }

    // ================= 탭 =================

    fun newTab(url: String?, select: Boolean = true, byLink: Boolean = false): Tab {
        val t = Tab(createWebView())
        t.openedByLink = byLink
        if (url != null) {
            t.home = false
            t.pendingUrl = toUrl(url)
        }
        tabs.add(t)
        if (select) {
            captureCurrentTab()
            tabs.getOrNull(cur)?.webView?.onPause()
            cur = tabs.size - 1
            showTab()
        } else {
            updateUi()
        }
        return t
    }

    /** 길게 눌러 '새 탭에서 열기': 지금 탭 바로 옆에 뒤에서 열고, 10초간 '이동' 버튼 */
    private fun openInBackground(url: String, from: Tab?) {
        val t = Tab(createWebView())
        t.home = false
        t.openedByLink = true
        t.opener = from
        val at = (from?.let { tabs.indexOf(it) } ?: cur).coerceAtLeast(0) + 1
        // 같은 탭에서 연달아 연 탭들은 순서대로 이어 붙임
        var pos = at
        while (pos < tabs.size && tabs[pos].opener === from && from != null) pos++
        tabs.add(pos.coerceAtMost(tabs.size), t)
        if (pos <= cur) cur++
        t.webView.loadUrl(url)
        updateUi()
        showSnack("새 탭에서 열었어요", "새 탭으로 이동", 10_000, dismissOnTouch = true) {
            if (t in tabs) selectTab(tabs.indexOf(t))
        }
    }

    fun selectTab(i: Int) {
        if (i !in tabs.indices) return
        if (i != cur) {
            captureCurrentTab()
            tabs.getOrNull(cur)?.webView?.onPause()
            cur = i
        }
        showTab()
    }

    fun moveTab(from: Int, to: Int) {
        if (from !in tabs.indices || to !in tabs.indices) return
        val current = tabs[cur]
        val t = tabs.removeAt(from)
        tabs.add(to, t)
        cur = tabs.indexOf(current)
    }

    fun tabTitle(t: Tab): String {
        if (t.home) return "새 탭"
        val u = t.pendingUrl ?: t.webView.url ?: ""
        return t.title.ifBlank { displayUrl(u) }.ifBlank { "로딩 중" }
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
        loading = t.webView.progress in 1..99 && !t.home
        updateUi()
    }

    /** 탭을 목록에서 빼기만 한다 (웹뷰는 그대로) */
    private fun detachTab(i: Int): Tab {
        val wasCurrent = i == cur
        val t = tabs.removeAt(i)
        (t.webView.parent as? ViewGroup)?.removeView(t.webView)
        t.webView.onPause()
        tabs.forEach { if (it.opener === t) it.opener = t.opener }
        if (tabs.isEmpty()) {
            cur = 0
            newTab(null)
            return t
        }
        if (i < cur) cur--
        if (cur >= tabs.size) cur = tabs.size - 1
        if (wasCurrent) t.opener?.let { o -> tabs.indexOf(o).takeIf { it >= 0 }?.let { cur = it } }
        showTab()
        return t
    }

    private fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        detachTab(i).webView.destroy()
    }

    // 닫은 탭 되살리기용 (탭, 원래 위치, 현재 탭이었는지)
    private var closedTab: Triple<Tab, Int, Boolean>? = null
    private var closedTimer: Runnable? = null

    fun closeTabUndoable(i: Int) {
        if (i !in tabs.indices) return
        finalizeClosed()
        val wasCurrent = i == cur
        val t = detachTab(i)
        closedTab = Triple(t, i, wasCurrent)
        val r = Runnable { finalizeClosed() }
        closedTimer = r
        frame.postDelayed(r, 5_500)
        showSnack("탭을 닫았어요", "되살리기", 5_000) { restoreClosed() }
    }

    private fun finalizeClosed() {
        closedTimer?.let { frame.removeCallbacks(it) }
        closedTimer = null
        closedTab?.first?.webView?.destroy()
        closedTab = null
    }

    private fun restoreClosed() {
        val (t, i, wasCurrent) = closedTab ?: return
        closedTimer?.let { frame.removeCallbacks(it) }
        closedTimer = null
        closedTab = null
        // 마지막 탭을 닫아서 빈 새 탭이 자동으로 생겼다면 그건 치운다
        if (tabs.size == 1 && tabs[0].home && tabs[0].webView.url == null) {
            tabs[0].webView.destroy()
            tabs.clear()
            cur = 0
        }
        val pos = i.coerceIn(0, tabs.size)
        tabs.add(pos, t)
        if (wasCurrent || tabs.size == 1) cur = pos
        else if (pos <= cur) cur++
        showTab()
        switcher.refresh()
    }

    fun captureCurrentTab() {
        val t = tabs.getOrNull(cur) ?: return
        if (t.home) return
        Thumbs.capture(t.webView, 0.45f)?.let { t.thumb = it }
    }

    private fun closeOverlays() {
        if (switcher.isOpen) switcher.close()
        pages.closeAll()
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
        t.allowUrl = t.pendingUrl
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
            // 광고 페이지는 건너뛰고 그 전 페이지로
            val list = t.webView.copyBackForwardList()
            var i = list.currentIndex - 1
            while (i > 0 && isJunk(list.getItemAtIndex(i).url)) i--
            if (i >= 0 && !isJunk(list.getItemAtIndex(i).url)) {
                t.webView.goBackOrForward(i - list.currentIndex)
                return true
            }
            if (i == 0 && isJunk(list.getItemAtIndex(0).url)) {
                if (t.openedByLink && tabs.size > 1) closeTab(cur) else {
                    t.home = true; t.homeByButton = false; showTab()
                }
                return true
            }
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
            collector.back() -> {}
            grabber.back() -> {}
            pages.back() -> {}
            switcher.isOpen -> switcher.close()
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

    // ================= 길게 누르기 메뉴 =================

    private fun onWebLongPress(wv: WebView): Boolean {
        if (collector.waitingForPoint) {
            collector.setPoint(lastTouchX, lastTouchY)
            return true
        }
        val hr = wv.hitTestResult
        val type = hr.type
        val extra = hr.extra
        when (type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                if (extra != null) linkMenu(wv, extra, null)
                return extra != null
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, WebView.HitTestResult.IMAGE_TYPE -> {
                val h = Handler(mainLooper) { m ->
                    val link = m.data?.getString("url")?.takeIf { it.isNotBlank() }
                    linkMenu(wv, if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) link else link, extra)
                    true
                }
                wv.requestFocusNodeHref(h.obtainMessage())
                return true
            }
        }
        return false
    }

    private fun linkMenu(wv: WebView, link: String?, img: String?) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (link != null && !link.startsWith("javascript:")) {
            items.add("새 탭에서 열기" to { openInBackground(link, findTab(wv)) })
            items.add("링크 복사" to { copy(link) })
            items.add("링크 공유" to { share(link) })
        }
        if (img != null) {
            items.add("이미지 저장" to { saveImage(wv, img) })
            if (img.startsWith("http")) {
                items.add("이 이미지 폴더 전체 받기" to { grabber.start(img, wv.url, wv.settings.userAgentString) })
                items.add("주소 분석해서 찾기" to { grabber.analyze(img, wv.url, wv.settings.userAgentString) })
                items.add("이미지를 새 탭에서 보기" to { openInBackground(img, findTab(wv)) })
            }
        }
        if (items.isEmpty()) return
        val title = (link ?: img ?: "").let { if (it.startsWith("data:")) "이미지" else displayUrl(it) }
        AlertDialog.Builder(this)
            .setTitle(title.take(120))
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .show()
    }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", s))
        if (Build.VERSION.SDK_INT < 33) toast("복사했어요")
    }

    private fun share(s: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s), "공유"))
    }

    private fun saveImage(wv: WebView, img: String) {
        when {
            img.startsWith("data:") -> { Downloads.startData(this, img, "image", null); dlStarted() }
            img.startsWith("blob:") -> saveBlob(wv, img, "image")
            else -> startDownload(img, wv.settings.userAgentString, null, null, wv.url)
        }
    }

    // ================= 다운로드 =================

    fun startDownload(url: String, ua: String?, cd: String?, mime: String?, referer: String?) {
        Downloads.start(this, url, ua ?: WebSettings.getDefaultUserAgent(this), cd, mime, referer)
        dlStarted()
    }

    private fun dlStarted() {
        val name = Downloads.items.firstOrNull()?.name ?: ""
        showSnack("다운로드 시작" + if (name.isNotEmpty() && name != "준비 중…") ": $name" else "", "보기", 4_000) {
            pages.openDownloads()
        }
    }

    private fun onDownloadFinished(it: Downloads.Item) {
        when (it.state) {
            Downloads.DONE -> showSnack("다운로드 완료: ${it.name}", "열기", 5_000) { openFile(it) }
            Downloads.FAILED -> showSnack("다운로드 실패: ${it.name}", "보기", 5_000) { pages.openDownloads() }
            else -> {}
        }
    }

    fun openFile(it: Downloads.Item) {
        val u = it.uri ?: return
        try {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(u), it.mime ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, it.name))
        } catch (e: Exception) {
            toast("이 파일을 열 앱이 없어요")
        }
    }

    private fun onDownloadRequest(wv: WebView, url: String, ua: String?, cd: String?, mime: String?) {
        when {
            url.startsWith("data:") -> { Downloads.startData(this, url, Downloads.fileName("http://x/download", cd, mime), mime); dlStarted() }
            url.startsWith("blob:") -> {
                if (!store.unblockDownloads) {
                    toast("사이트 안에서 만든 파일은 '다운로드 차단 해제'를 켜야 받을 수 있어요")
                    return
                }
                saveBlob(wv, url, Downloads.fileName("http://x/download", cd, mime))
            }
            url.startsWith("http") -> startDownload(url, ua, cd, mime, wv.url)
            else -> toast("이 형식의 다운로드는 지원하지 않아요")
        }
        // 다운로드만 하려고 열린 빈 새 탭은 닫는다
        val t = findTab(wv)
        if (t != null && t.openedByLink && t.webView.copyBackForwardList().size == 0 && tabs.size > 1) {
            frame.post { if (t in tabs) closeTab(tabs.indexOf(t)) }
        }
    }

    /** blob: 주소는 페이지 안에서만 읽을 수 있어서, 페이지에서 data로 바꿔 앱에 넘겨받는다 */
    private fun saveBlob(wv: WebView, url: String, name: String) {
        blobPending++
        val js = "(function(){try{var x=new XMLHttpRequest();x.open('GET'," + JSONObject.quote(url) + ",true);" +
            "x.responseType='blob';x.onload=function(){var r=new FileReader();r.onloadend=function(){" +
            "__lbdl.save(r.result,x.response.type||''," + JSONObject.quote(name) + ");};r.readAsDataURL(x.response);};" +
            "x.onerror=function(){__lbdl.fail();};x.send();}catch(e){__lbdl.fail();}})();"
        wv.evaluateJavascript(js, null)
    }

    inner class DlBridge {
        @JavascriptInterface
        fun save(data: String, mime: String, name: String) {
            runOnUiThread {
                if (blobPending <= 0) return@runOnUiThread
                blobPending--
                Downloads.startData(this@MainActivity, data, name, mime.ifEmpty { null })
                dlStarted()
            }
        }

        @JavascriptInterface
        fun fail() {
            runOnUiThread {
                if (blobPending > 0) blobPending--
                toast("파일을 받지 못했어요")
            }
        }
    }

    fun pickDownloadFolder(done: () -> Unit) {
        folderPicked = done
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
        } catch (e: Exception) {
            toast("폴더 선택 화면을 열 수 없어요")
        }
    }

    private val fileExt = Regex(
        "\\.(apk|zip|rar|7z|alz|egg|tar|gz|pdf|hwp|hwpx|docx?|xlsx?|pptx?|txt|mp4|mkv|avi|mov|webm|mp3|flac|wav|m4a|exe|msi|dmg|iso|torrent|smi|srt|epub|cbz)$",
        RegexOption.IGNORE_CASE
    )

    private fun looksLikeFile(url: String?): Boolean =
        url != null && fileExt.containsMatchIn(Uri.parse(url).path ?: "")

    // ================= 웹뷰 =================

    private fun findTab(v: WebView?) = tabs.firstOrNull { it.webView === v }
    private fun isCurrent(v: WebView?) = tabs.getOrNull(cur)?.let { !it.home && it.webView === v } == true

    private fun applyDark(s: WebSettings) {
        // 안드로이드 13 이상: 앱이 다크 테마일 때 웹페이지도 자동으로 어둡게
        if (Build.VERSION.SDK_INT >= 33) s.isAlgorithmicDarkeningAllowed = store.darkPages
    }

    fun applyDarkAll() {
        tabs.forEach { applyDark(it.webView.settings) }
        if (!isNight()) toast("폰이 다크 모드일 때 적용돼요")
    }

    fun setTextZoom(z: Int) {
        store.textZoom = z
        tabs.forEach { it.webView.settings.textZoom = z }
    }

    fun setAdblock(on: Boolean) {
        adblock = on
        store.adblock = on
        tabs.getOrNull(cur)?.let { if (!it.home) it.webView.reload() }
    }

    private fun setDesktop(t: Tab, on: Boolean) {
        t.desktop = on
        t.webView.settings.userAgentString = if (on) DESKTOP_UA else null
        if (!t.home) t.webView.reload()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createWebView(): WebView = WebView(this).apply {
        setBackgroundColor(col(R.color.bar))
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            // 크롬처럼: PC용 페이지를 줄여 보여줄 때 글자만 읽을 만한 크기로 키움
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            // 폰 글꼴 크기 설정이 웹페이지 배치를 망가뜨리지 않게 고정
            textZoom = store.textZoom
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
        addJavascriptInterface(DlBridge(), "__lbdl")
        setDownloadListener { url, ua, cd, mime, _ -> onDownloadRequest(this, url, ua, cd, mime) }
        setOnLongClickListener { onWebLongPress(this) }
        setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchX = e.x
                lastTouchY = e.y
            }
            if (e.actionMasked == MotionEvent.ACTION_DOWN && snackDismissOnTouch) hideSnack()
            false
        }
    }

    inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase() ?: return false
            val url = request.url.toString()
            if (adblock && request.isForMainFrame && (scheme == "http" || scheme == "https") &&
                AdBlock.blocked(request.url.host) && !(store.unblockDownloads && looksLikeFile(url))
            ) {
                // 링크를 눌렀는데 광고 페이지로 넘기려는 경우 → 이동 자체를 취소
                dropAdPage(view)
                return true
            }
            if (scheme in WEB_SCHEMES) return false
            openExternal(view, url)
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (adblock && !request.isForMainFrame && AdBlock.blocked(request.url.host)) return AdBlock.empty()
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            val t = findTab(view)
            if (t != null) t.dialogCount = 0
            if (adblock && url != null && url != t?.allowUrl && AdBlock.blockedUrl(url) &&
                !(store.unblockDownloads && looksLikeFile(url))
            ) {
                view.stopLoading()
                dropAdPage(view)
                return
            }
            if (isCurrent(view)) {
                loading = true
                progress.visibility = View.VISIBLE
                updateUi()
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            if (adblock) view.evaluateJavascript(AdBlock.hideScript(Uri.parse(url ?: "").host), null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (isCurrent(view)) {
                loading = false
                updateUi()
            }
            if (adblock) view.evaluateJavascript(AdBlock.hideScript(Uri.parse(url ?: "").host), null)
            if (url != null && url.startsWith("http") && !AdBlock.blockedUrl(url)) {
                store.addHistory(view.title ?: url, url)
                // 잠시 뒤 화면을 찍어 탭 미리보기·즐겨찾기 미리보기에 쓴다
                view.postDelayed({ snapshot(view, url) }, 1500)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (isCurrent(view)) updateUi()
        }
    }

    private fun snapshot(view: WebView, url: String) {
        if (!isCurrent(view) || view.url != url || switcher.isOpen) return
        val t = findTab(view) ?: return
        Thumbs.capture(view, 0.45f)?.let { t.thumb = it }
        if (dialIndexByKey(url) >= 0 && !Thumbs.hasFresh(this, url, 3 * 3600_000L)) {
            Thumbs.capture(view, 0.5f, Thumbs.DIAL_ASPECT)?.let { Thumbs.saveDial(this, url, it) }
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
            if (!isUserGesture) return false   // 저절로 뜨는 팝업은 조용히 막음
            val t = Tab(createWebView())
            t.home = false
            t.openedByLink = true
            t.opener = findTab(view)
            captureCurrentTab()
            tabs.getOrNull(cur)?.webView?.onPause()
            val at = (t.opener?.let { tabs.indexOf(it) } ?: cur) + 1
            tabs.add(at.coerceIn(0, tabs.size), t)
            cur = tabs.indexOf(t)
            showTab()
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = t.webView
            resultMsg.sendToTarget()
            return true
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
            jsDialog(view, url, message, result, confirm = false)

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
            jsDialog(view, url, message, result, confirm = true)

        override fun onJsPrompt(
            view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult
        ): Boolean {
            if (adblock && shouldMuteDialog(view, url)) {
                result.cancel()
                return true
            }
            return false
        }

        /** "이 페이지를 나가시겠습니까?" 창은 묻지 않고 바로 나감 */
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.confirm()
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
        } else if (requestCode == REQ_TREE) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                }
                store.downloadTree = uri.toString()
                folderPicked?.invoke()
            }
            folderPicked = null
        }
    }

    // ================= 광고 처리 =================

    private val mutedSites = HashSet<String>()

    private fun isJunk(url: String?) = adblock && AdBlock.blockedUrl(url)

    /** 광고 페이지로 넘어가려 할 때: 광고용 새 탭이면 닫고, 아니면 그 자리에 머문다 */
    private fun dropAdPage(view: WebView) {
        val t = findTab(view) ?: return
        content.post {
            if (t !in tabs) return@post
            val list = t.webView.copyBackForwardList()
            val nothingReal = (0 until list.size).all { isJunk(list.getItemAtIndex(it).url) }
            if (t.openedByLink && nothingReal && tabs.size > 1) {
                closeTab(tabs.indexOf(t))
            } else if (isJunk(t.webView.url) && t === tabs.getOrNull(cur)) {
                goBack()
            }
        }
    }

    /** 광고 서버에서 띄운 창, 반복해서 뜨는 창, 그만 보기 한 사이트의 창은 막음 */
    private fun shouldMuteDialog(view: WebView, url: String?): Boolean {
        val pageSite = AdBlock.site(Uri.parse(view.url ?: "").host)
        val frameHost = Uri.parse(url ?: "").host
        val t = findTab(view)
        return AdBlock.blocked(frameHost) ||
            pageSite in mutedSites ||
            (t != null && t.dialogCount >= 3) ||
            (t != null && tabs.getOrNull(cur) !== t)
    }

    private fun jsDialog(view: WebView, url: String?, message: String?, result: JsResult, confirm: Boolean): Boolean {
        val t = findTab(view)
        if (adblock && shouldMuteDialog(view, url)) {
            result.cancel()
            return true
        }
        t?.dialogCount = (t?.dialogCount ?: 0) + 1
        val pageSite = AdBlock.site(Uri.parse(view.url ?: "").host)
        var answered = false
        val b = AlertDialog.Builder(this)
            .setTitle(pageSite.ifEmpty { "알림" })
            .setMessage(message ?: "")
            .setPositiveButton("확인") { _, _ -> answered = true; result.confirm() }
            .setNeutralButton("이 사이트 알림 그만") { _, _ ->
                answered = true
                mutedSites.add(pageSite)
                result.cancel()
            }
            .setOnDismissListener { if (!answered) result.cancel() }
        if (confirm) b.setNegativeButton("취소") { _, _ -> answered = true; result.cancel() }
        b.show()
        return true
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

    // ================= 즐겨찾기 =================

    private fun norm(u: String) = u.removeSuffix("/").removePrefix("https://").removePrefix("http://").removePrefix("www.")

    /** 이 주소와 같은 즐겨찾기 위치 (없으면 -1) */
    private fun dialIndex(url: String): Int = store.dials().indexOfFirst { norm(it.url) == norm(url) }

    /** 같은 사이트(첫 화면 미리보기용) 즐겨찾기 위치 */
    private fun dialIndexByKey(url: String): Int {
        val k = Thumbs.key(url)
        return store.dials().indexOfFirst { Thumbs.key(it.url) == k }
    }

    private fun toggleStar() {
        val t = tab
        val url = t.webView.url ?: return
        val l = store.dials()
        val i = dialIndex(url)
        if (i >= 0) {
            l.removeAt(i)
            store.saveDials(l)
            showSnack("즐겨찾기에서 뺐어요", null, 2_500)
        } else {
            val title = t.title.ifBlank { displayUrl(url) }.take(30)
            l.add(Store.Item(title, url))
            store.saveDials(l)
            Thumbs.capture(t.webView, 0.5f, Thumbs.DIAL_ASPECT)?.let { Thumbs.saveDial(this, url, it) }
            showSnack("즐겨찾기에 추가했어요", "이름 바꾸기", 4_000) { editDial(store.dials().size - 1) }
        }
        updateUi()
    }

    private val tileColors = intArrayOf(
        0xFF03C75A.toInt(), 0xFF4285F4.toInt(), 0xFFE53935.toInt(), 0xFF1E88E5.toInt(),
        0xFF00A495.toInt(), 0xFFE5262D.toInt(), 0xFF8E44AD.toInt(), 0xFFF39C12.toInt(),
    )

    private fun buildHome() {
        homeGrid.removeAllViews()
        val cols = when {
            resources.configuration.screenWidthDp >= 840 -> 6
            resources.configuration.screenWidthDp >= 600 -> 5
            else -> 3
        }
        homeGrid.columnCount = cols
        val width = (if (content.width > 0) content.width else resources.displayMetrics.widthPixels) - dp(20)
        val cell = width / cols
        val items = store.dials()
        items.forEachIndexed { i, d ->
            homeGrid.addView(dialTile(d, cell, { load(d.url) }) {
                AlertDialog.Builder(this)
                    .setTitle(d.title)
                    .setItems(arrayOf("새 탭에서 열기", "수정", "삭제")) { _, which ->
                        when (which) {
                            0 -> newTab(d.url)
                            1 -> editDial(i)
                            2 -> {
                                val l = store.dials(); l.removeAt(i); store.saveDials(l)
                                if (dialIndexByKey(d.url) < 0) Thumbs.forget(this, d.url)
                                buildHome()
                            }
                        }
                    }.show()
            })
        }
        homeGrid.addView(dialTile(null, cell, { editDial(-1) }, null))
    }

    /** 큰 네모 미리보기 카드 (d = null 이면 '추가' 카드) */
    private fun dialTile(d: Store.Item?, cell: Int, onClick: () -> Unit, onLong: (() -> Unit)?): View {
        val cw = cell - dp(12)
        val ch = (cw * Thumbs.DIAL_ASPECT).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(14))
            layoutParams = GridLayout.LayoutParams().apply { width = cell }
        }
        val card = FrameLayout(this).apply {
            background = rounded(col(R.color.field), dp(12), 1, col(R.color.line))
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dp(2).toFloat()
            foreground = TypedValue().let { tv ->
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                getDrawable(tv.resourceId)
            }
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        val thumb = d?.let { Thumbs.dial(this, it.url) }
        when {
            d == null -> card.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_add)
                imageTintList = ColorStateList.valueOf(col(R.color.sub))
                scaleType = ImageView.ScaleType.CENTER
            })
            thumb != null -> card.addView(ImageView(this).apply {
                setImageBitmap(thumb)
                scaleType = ImageView.ScaleType.CENTER_CROP
            })
            else -> card.addView(TextView(this).apply {
                text = d.title.trim().take(1).ifEmpty { "?" }
                gravity = Gravity.CENTER
                textSize = 34f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(tileColors[d.url.hashCode().mod(tileColors.size)])
            })
        }
        box.addView(card, LinearLayout.LayoutParams(cw, ch))
        box.addView(TextView(this).apply {
            text = d?.title ?: "추가"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(col(if (d == null) R.color.sub else R.color.text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), dp(6), dp(4), 0)
        }, LinearLayout.LayoutParams(cw, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
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
                updateUi()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (tabs.isNotEmpty() && tab.home) content.post { buildHome() }
        if (switcher.isOpen) {
            switcher.close()
            frame.post { switcher.open() }
        }
    }

    // ================= 차단 우회 =================

    /** 켜면 웹뷰의 모든 접속을 앱 내부 중계기(Bypass)로 돌린다 */
    fun applyBypass(on: Boolean, reload: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            toast("이 폰의 웹뷰는 우회 기능을 지원하지 않아요. 플레이스토어에서 Android System WebView를 업데이트해 주세요")
            return
        }
        val main = Handler(mainLooper)
        val exec = java.util.concurrent.Executor { main.post(it) }
        val done = Runnable {
            if (reload) tabs.getOrNull(cur)?.let { if (!it.home) it.webView.reload() }
        }
        try {
            if (on) {
                val port = Bypass.start()
                val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
                ProxyController.getInstance().setProxyOverride(cfg, exec, done)
            } else {
                ProxyController.getInstance().clearProxyOverride(exec, done)
                Bypass.stop()
            }
        } catch (e: Exception) {
            toast("우회 기능을 켜지 못했어요")
        }
    }

    // ================= 메뉴 =================

    private fun showMenu(anchor: View) {
        val t = tab
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        m.add(0, 1, 0, "새 탭")
        m.add(0, 4, 0, "다운로드")
        m.add(0, 3, 0, "방문 기록")
        m.add(0, 5, 0, "페이지 공유")
        m.add(0, 6, 0, "데스크톱 모드").apply { isCheckable = true; isChecked = t.desktop }
        m.add(0, 11, 0, "이미지 폴더 받기")
        m.add(0, 12, 0, "페이지 넘기며 이미지 모으기")
        m.add(0, 9, 0, "설정")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> newTab(null)
                3 -> showHistory()
                4 -> pages.openDownloads()
                5 -> {
                    val u = t.webView.url
                    if (t.home || u == null) toast("공유할 페이지가 없어요") else share(u)
                }
                6 -> setDesktop(t, !t.desktop)
                9 -> pages.openSettings()
                11 -> grabFromPage(t)
                12 -> startCollector()
            }
            true
        }
        pm.show()
    }

    fun currentWebView(): WebView? = tabs.getOrNull(cur)?.takeIf { !it.home }?.webView

    fun startCollector() {
        closeOverlays()
        collector.begin()
    }

    fun openGrabberWith(urls: List<String>, page: String?, ua: String?, name: String, autoStart: Boolean = false) {
        grabber.startWithUrls(urls, page, ua, name, autoStart)
    }

    /** 알림 표시 권한, 배터리 최적화 제외 (백그라운드 작업이 끊기지 않게) */
    fun askBackgroundPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !store.askedBattery) {
            store.askedBattery = true
            AlertDialog.Builder(this)
                .setTitle("화면이 꺼져도 계속 모으려면")
                .setMessage("배터리 절약 때문에 작업이 끊기지 않도록, 이 앱을 배터리 최적화에서 빼 주세요.")
                .setPositiveButton("허용하러 가기") { _, _ ->
                    try {
                        @SuppressLint("BatteryLife")
                        val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                        startActivity(i)
                    } catch (e: Exception) {
                        toast("설정 화면을 열 수 없어요")
                    }
                }
                .setNegativeButton("나중에", null)
                .show()
        }
    }

    /** 지금 페이지에서 가장 크게 보이는 이미지를 기준으로 폴더 받기 */
    private fun grabFromPage(t: Tab) {
        val wv = t.webView
        val url = wv.url
        if (t.home || url == null) { toast("이미지가 있는 페이지에서 써 주세요"); return }
        if (Regex("\\.(jpe?g|png|gif|webp|bmp|avif)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            grabber.start(url, null, wv.settings.userAgentString)
            return
        }
        val js = "(function(){var b=null,ba=0;document.querySelectorAll('img').forEach(function(i){" +
            "var r=i.getBoundingClientRect();var a=r.width*r.height;var s=i.currentSrc||i.src;" +
            "if(a>ba&&s&&s.indexOf('http')==0){ba=a;b=s;}});return b;})()"
        wv.evaluateJavascript(js) { res ->
            val img = try { org.json.JSONTokener(res).nextValue() as? String } catch (e: Exception) { null }
            if (img.isNullOrBlank()) toast("이 페이지에서 이미지를 찾지 못했어요. 이미지를 꾸욱 눌러서 해 보세요")
            else grabber.start(img, url, wv.settings.userAgentString)
        }
    }

    fun showHistory() {
        val list = store.history()
        if (list.isEmpty()) {
            toast("방문 기록이 없어요")
            return
        }
        val shown = list.take(150)
        AlertDialog.Builder(this)
            .setTitle("방문 기록")
            .setItems(shown.map { "${it.title}\n${displayUrl(it.url)}" }.toTypedArray()) { _, which ->
                closeOverlays()
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
        private const val REQ_TREE = 8
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "file")
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
