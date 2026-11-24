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
    /** 탭 고유 번호 (미리보기 파일 이름, 앱을 다시 켜도 유지) */
    var id: Long = newId()

    companion object {
        private var last = 0L
        fun newId(): Long {
            val now = System.currentTimeMillis() * 100
            last = if (now > last) now else last + 1
            return last
        }
    }
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
    private lateinit var jobsBtn: FrameLayout
    private lateinit var jobsIcon: ImageView
    private lateinit var jobsBadge: TextView
    private lateinit var progress: ProgressBar
    private lateinit var content: FrameLayout
    private lateinit var homeView: ScrollView
    private lateinit var homeWrap: View
    private lateinit var homeGrid: GridLayout
    private lateinit var backBtn: ImageButton
    private lateinit var fwdBtn: ImageButton
    private lateinit var tabsBtn: TextView
    private lateinit var switcher: TabSwitcher
    private lateinit var pages: Pages
    private lateinit var grabber: Grabber
    private lateinit var history: HistoryPage
    lateinit var live: LiveTranslate
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

    // ---------------- 예약 저장 ----------------

    private val schedChoices = listOf(0 to "매일 정해진 시각에 (여러 개 가능)…", 30 to "30분마다", 60 to "1시간마다",
        180 to "3시간마다", 360 to "6시간마다", 720 to "12시간마다", 1440 to "하루마다")

    /** 지금 페이지를 예약 저장에 추가 */
    fun addSchedule() {
        val wv = currentWebView()
        val url = wv?.url
        if (wv == null || url == null || !url.startsWith("http")) { toast("예약할 페이지를 먼저 열어 주세요"); return }
        val name = EditText(this).apply {
            setText((wv.title ?: "").take(60))
            hint = "이름 (저장 폴더 이름)"
            isSingleLine = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(name)
            addView(TextView(this@MainActivity).apply {
                text = "화면이 꺼져 있어도 정한 때마다 이 페이지를 몰래 열어서, 아직 안 받은 그림만 저장해요.\n저장 위치: 다운로드/라이트 브라우저/이름/"
                textSize = 13f
                setTextColor(col(R.color.sub))
                setPadding(0, dp(8), 0, 0)
            })
        }
        AlertDialog.Builder(this)
            .setCustomTitle(titleView("이 페이지 예약 저장"))
            .setView(box)
            .setPositiveButton("다음") { _, _ ->
                val nm = name.text.toString().trim().ifBlank { wv.title ?: "예약 저장" }
                val modes = arrayOf(
                    "목록에 새 글이 올라오면 그 글의 그림 전부 저장\n(게시판·갤러리 목록 페이지)",
                    "이 페이지에 보이는 그림 저장\n(새 그림이 추가되는 한 페이지)")
                AlertDialog.Builder(this)
                    .setCustomTitle(titleView("$nm · 무엇을 받을까요?"))
                    .setItems(modes) { _, w ->
                        if (w == 1) pickInterval(nm) { every, times ->
                            schedOptions(nm, test = false) { ftp, _ ->
                                Sched.add(this, Sched.Item(System.currentTimeMillis(), url, nm, every, times = times, ftp = ftp), runNow = true)
                                askBatteryOnce()
                                toast("예약했어요 · 지금 한 번 바로 받아 볼게요")
                            }
                        } else addPostSchedule(wv, url, nm)
                    }
                    .setNegativeButton("취소", null).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 목록 페이지: 지금 있는 글 링크를 기준으로 삼고, 앞으로 새로 생기는 글만 받음 */
    private fun addPostSchedule(wv: WebView, url: String, nm: String) {
        wv.evaluateJavascript(Sched.LINKS_JS) { res ->
            val raw = try { org.json.JSONTokener(res).nextValue() as? String } catch (e: Exception) { null }
            val links = Sched.parseLinks(raw)
            val shapes = Sched.postShapes(url, links)
            if (shapes.isEmpty()) {
                AlertDialog.Builder(this).setTitle("글 목록을 못 알아봤어요")
                    .setMessage("이 페이지에서 글 링크(번호만 다른 링크가 여러 개)를 찾지 못했어요. 게시판이나 갤러리 목록 페이지에서 해 주세요.")
                    .setPositiveButton("확인", null).show()
                return@evaluateJavascript
            }
            val posts = links.count { Sched.shape(it) in shapes }
            AlertDialog.Builder(this)
                .setCustomTitle(titleView("$nm · 새 글 받기"))
                .setMessage("지금 글 ${posts}개를 찾았어요. 이 글들은 이미 본 것으로 두고, 앞으로 새로 올라오는 글만 받아요.\n\n" +
                    "새 글이 보이면 그 글을 (화면 없이) 새 탭처럼 열어 그림을 전부 저장하고 닫은 뒤, 다음 새 글도 똑같이 해요.\n" +
                    "저장 위치: 다운로드/라이트 브라우저/$nm/글 제목/")
                .setPositiveButton("다음") { _, _ ->
                    pickInterval(nm) { every, times ->
                        schedOptions(nm, test = true) { ftp, test ->
                            val id = System.currentTimeMillis()
                            val postLinks = links.filter { Sched.shape(it) in shapes }
                            // 테스트: 목록 맨 아래 글 하나는 '아직 안 본 것'으로 두고 바로 받아 봄
                            Sched.baseline(this, id, if (test && postLinks.isNotEmpty()) links - postLinks.last() else links)
                            Sched.add(this, Sched.Item(id, url, nm, every, times = times, mode = 1, patterns = shapes, ftp = ftp), runNow = test)
                            askBatteryOnce()
                            toast(if (test) "예약했어요 · 지금 맨 아래 글 하나를 받아 볼게요 (화면 꺼도 돼요)" else "예약했어요 · 새 글이 올라오면 받아요")
                        }
                    }
                }
                .setNegativeButton("취소", null).show()
        }
    }

    /** 목록 전부 받기: 예약 없이 한 번, 마지막 쪽 맨 아래 글부터 1쪽 맨 위 글까지 */
    fun fullList() {
        val wv = currentWebView()
        val url = wv?.url
        if (wv == null || url == null || !url.startsWith("http")) { toast("받을 목록 페이지를 먼저 열어 주세요"); return }
        wv.evaluateJavascript(Sched.LINKS_JS) { res ->
            val raw = try { org.json.JSONTokener(res).nextValue() as? String } catch (e: Exception) { null }
            val links = Sched.parseLinks(raw)
            val shapes = Sched.postShapes(url, links)
            if (shapes.isEmpty()) {
                AlertDialog.Builder(this).setTitle("글 목록을 못 알아봤어요")
                    .setMessage("이 페이지에서 글 링크(번호만 다른 링크가 여러 개)를 찾지 못했어요. 작품·글 목록 페이지에서 해 주세요.")
                    .setPositiveButton("확인", null).show()
                return@evaluateJavascript
            }
            val pages = Sched.pageTemplate(url, links)
            val posts = links.count { Sched.shape(it) in shapes }
            val nm = (wv.title ?: "목록").take(60)
            AlertDialog.Builder(this)
                .setCustomTitle(titleView("이 목록 전부 받기"))
                .setMessage("이 쪽 글 ${posts}개" + (if (pages != null) " · 쪽 번호 ${pages.second}쪽까지 보임 (끝 쪽은 들어가서 더 찾아요)" else " · 쪽 번호는 못 찾음 (이 쪽만 받아요)") +
                    "\n\n마지막 쪽 맨 아래(가장 오래된) 글부터 1쪽 맨 위 글까지 차례로, 글마다 그림을 다 받아요. 화면이 꺼져도 계속하고, 끊기면 이어서 해요.\n저장 위치: 다운로드/라이트 브라우저/$nm/")
                .setPositiveButton("다음") { _, _ ->
                    schedOptions(nm, test = false) { ftp, _ ->
                        val id = System.currentTimeMillis()
                        val l = Sched.list(this)
                        l.add(Sched.Item(id, url, nm, 0, enabled = false, mode = 1, patterns = shapes, ftp = ftp, full = true))
                        Sched.saveAll(this, l)
                        Sched.startBackfill(this, id)
                        askBatteryOnce()
                        toast("시작했어요 · 알림창과 메뉴 › 전부 받기·예약 목록에서 진행 상황을 볼 수 있어요")
                    }
                }
                .setNegativeButton("취소", null).show()
        }
    }

    /** 주기 고르기: 몇 분·시간마다, 또는 매일 정해진 시각들 (예: 09:00, 13:30, 22:00) */
    fun pickInterval(title: String, current: List<Int> = emptyList(), done: (Int, List<Int>) -> Unit) {
        AlertDialog.Builder(this)
            .setCustomTitle(titleView("$title · 언제 확인할까요?"))
            .setItems(schedChoices.map { it.second }.toTypedArray()) { _, w ->
                val every = schedChoices[w].first
                if (every > 0) { done(every, emptyList()); return@setItems }
                val et = EditText(this).apply {
                    hint = "예: 09:00, 13:30, 22:00"
                    setText(current.joinToString(", ") { "%02d:%02d".format(it / 60, it % 60) }.ifEmpty { "09:00, 21:00" })
                }
                val box = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(et, LinearLayout.LayoutParams(-1, -2)) }
                AlertDialog.Builder(this)
                    .setTitle("매일 확인할 시각")
                    .setMessage("쉼표로 여러 개 넣을 수 있어요 (24시간 형식)")
                    .setView(box)
                    .setPositiveButton("확인") { _, _ ->
                        val ts = Regex("(\\d{1,2})\\s*[:시.]\\s*(\\d{0,2})").findAll(et.text.toString()).mapNotNull { m ->
                            val h = m.groupValues[1].toInt()
                            val mi = m.groupValues[2].ifEmpty { "0" }.toInt()
                            if (h in 0..23 && mi in 0..59) h * 60 + mi else null
                        }.distinct().sorted().toList()
                        if (ts.isEmpty()) toast("시각을 못 알아봤어요 (예: 09:00, 21:30)") else done(0, ts)
                    }
                    .setNegativeButton("취소", null).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 예약 마지막 단계: FTP로도 올리기, (새 글 받기면) 지금 맨 아래 글 하나로 테스트 */
    private fun schedOptions(title: String, test: Boolean, done: (Boolean, Boolean) -> Unit) {
        val conf = Ftp.conf(this)
        val ftp = android.widget.CheckBox(this).apply {
            text = if (conf.ok) "FTP로도 올리기 (${conf.host})" else "FTP로도 올리기 (설정 › 예약 저장 FTP에서 서버를 먼저 넣어 주세요)"
            isChecked = conf.ok
            isEnabled = conf.ok
        }
        val t = android.widget.CheckBox(this).apply {
            text = "테스트: 지금 목록 맨 아래(가장 오래된) 글 하나를 바로 받아 보기"
            isChecked = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(ftp)
            if (test) addView(t)
        }
        AlertDialog.Builder(this)
            .setCustomTitle(titleView("$title · 마지막으로"))
            .setView(box)
            .setPositiveButton("예약하기") { _, _ -> done(ftp.isChecked, test && t.isChecked) }
            .setNegativeButton("취소", null).show()
    }

    /** 설정 › 테스트: 지금 페이지로 예약 저장을 한 번 돌려 봄 (목록이면 맨 아래 글 하나, 글이면 그 글) */
    fun schedTest() {
        val wv = currentWebView()
        val url = wv?.url
        if (wv == null || url == null || !url.startsWith("http")) { toast("테스트할 페이지를 먼저 열어 주세요"); return }
        wv.evaluateJavascript(Sched.LINKS_JS) { res ->
            val raw = try { org.json.JSONTokener(res).nextValue() as? String } catch (e: Exception) { null }
            val links = Sched.parseLinks(raw)
            val shapes = Sched.postShapes(url, links)
            val posts = links.filter { Sched.shape(it) in shapes }
            val target: String
            val what: String
            if (posts.isNotEmpty()) { target = posts.last(); what = "목록 맨 아래 글 (글 ${posts.size}개 중 가장 오래된 것)" }
            else { target = url; what = "지금 페이지 (글 목록이 아니라서 이 페이지 본문 그림)" }
            val ftp = Ftp.conf(this).ok
            AlertDialog.Builder(this)
                .setCustomTitle(titleView("예약 저장 테스트"))
                .setMessage("받을 것: $what\n$target\n\n저장 위치: 다운로드/라이트 브라우저/테스트/글 제목/" +
                    (if (ftp) "\nFTP에도 올려요" else "") + "\n\n시작하면 알림창에 '예약 저장 중'이 떠요. 그때 화면을 꺼도 계속 받는지 확인해 보세요.")
                .setPositiveButton("시작") { _, _ -> Sched.test(this, target, ftp); toast("시작했어요 · 화면 꺼 봐도 돼요") }
                .setNegativeButton("취소", null).show()
        }
    }

    /** 테스트 기록 창: 그대로 복사해서 보내 주면 원인을 볼 수 있게 */
    fun showTestLog() {
        val log = Sched.lastTest(this).ifBlank { "아직 테스트 기록이 없어요" }
        val tv = TextView(this).apply {
            text = log
            textSize = 12f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(col(R.color.text))
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("예약 저장 테스트 기록")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("복사") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("테스트 기록", log))
                toast("복사했어요 · 채팅에 붙여 넣어 주세요")
            }
            .setNeutralButton("공유") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log), "테스트 기록 보내기"))
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** FTP 서버에 들어가서 폴더를 직접 골라 들어가며 선택 */
    private fun browseFtp(conf: Ftp.Conf, start: String, pick: (String) -> Unit) {
        if (!conf.ok) { toast("서버 주소를 먼저 넣어 주세요"); return }
        val s = Ftp.Session(conf)
        var path = start.ifBlank { "/" }
        var dlg: AlertDialog? = null
        fun show() {
            toast("불러오는 중…")
            Thread {
                var dirs = s.dirs(path)
                if (dirs == null && path != "/") { path = "/"; dirs = s.dirs(path) }
                runOnUiThread {
                    dlg?.dismiss()
                    if (dirs == null) {
                        AlertDialog.Builder(this).setTitle("폴더를 못 불러왔어요").setMessage(s.error ?: "연결 실패")
                            .setPositiveButton("확인", null).show()
                        Thread { s.close() }.start()
                        return@runOnUiThread
                    }
                    val items = ArrayList<String>()
                    if (path != "/") items.add("⬆  위 폴더로")
                    items.add("＋  여기에 새 폴더 만들기")
                    dirs.forEach { items.add("📁  $it") }
                    dlg = AlertDialog.Builder(this)
                        .setCustomTitle(titleView(path))
                        .setItems(items.toTypedArray()) { _, w ->
                            val t = items[w]
                            when {
                                t.startsWith("⬆") -> { path = path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }; show() }
                                t.startsWith("＋") -> {
                                    val et = EditText(this).apply { hint = "새 폴더 이름" }
                                    AlertDialog.Builder(this).setTitle("새 폴더").setView(LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(et, LinearLayout.LayoutParams(-1, -2)) })
                                        .setPositiveButton("만들기") { _, _ ->
                                            val n = et.text.toString().trim()
                                            if (n.isNotEmpty()) Thread {
                                                val np = path.trimEnd('/') + "/" + n
                                                val ok = s.mkdir(np)
                                                runOnUiThread { if (ok) { path = np; show() } else { toast("못 만들었어요"); show() } }
                                            }.start()
                                        }
                                        .setNegativeButton("취소") { _, _ -> show() }.show()
                                }
                                else -> { path = path.trimEnd('/') + "/" + t.removePrefix("📁  "); show() }
                            }
                        }
                        .setPositiveButton("이 폴더 선택") { _, _ -> pick(path); Thread { s.close() }.start() }
                        .setNegativeButton("닫기") { _, _ -> Thread { s.close() }.start() }
                        .setCancelable(false)
                        .show()
                }
            }.start()
        }
        show()
    }

    /** 설정 › 예약 저장 FTP 서버 */
    fun editFtp() {
        val c = Ftp.conf(this)
        fun field(hint: String, v: String, pass: Boolean = false, num: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(v)
            isSingleLine = true
            if (pass) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (num) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val host = field("서버 주소 (예: 192.168.0.10 또는 my.nas.com)", c.host)
        val port = field("포트 (보통 21)", c.port.toString(), num = true)
        val user = field("아이디", c.user)
        val pass = field("비밀번호", c.pass, pass = true)
        val dir = field("올릴 폴더 (예: /사진/라이트 브라우저)", c.dir)
        val passive = android.widget.CheckBox(this).apply { text = "패시브 모드 (잘 안 되면 끄거나 켜 보세요)"; isChecked = c.passive }
        // 한글 이름 깨짐 방지: 서버 글자 방식
        val encs = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        val utf = android.widget.RadioButton(this).apply { text = "UTF-8"; id = View.generateViewId() }
        val euc = android.widget.RadioButton(this).apply { text = "EUC-KR (한글이 깨지면)"; id = View.generateViewId() }
        encs.addView(utf); encs.addView(euc)
        encs.check(if (c.enc == "EUC-KR") euc.id else utf.id)
        fun read() = Ftp.Conf(host.text.toString(), port.text.toString().toIntOrNull() ?: 21, user.text.toString(),
            pass.text.toString(), dir.text.toString().ifBlank { "/" }, passive.isChecked, if (encs.checkedRadioButtonId == euc.id) "EUC-KR" else "UTF-8")
        val pickDir = TextView(this).apply {
            text = "📁 서버에 들어가서 폴더 고르기"
            textSize = 15f
            setTextColor(col(R.color.accent))
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { browseFtp(read(), dir.text.toString().ifBlank { "/" }) { p -> dir.setText(p) } }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            listOf(host, port, user, pass, dir, pickDir, passive).forEach { addView(it) }
            addView(TextView(this@MainActivity).apply { text = "한글 글자 방식"; textSize = 13f; setTextColor(col(R.color.sub)); setPadding(0, dp(8), 0, 0) })
            addView(encs)
        }
        val d = AlertDialog.Builder(this)
            .setTitle("예약 저장 FTP 서버")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("저장") { _, _ -> Ftp.save(this, read()); toast("저장했어요") }
            .setNeutralButton("연결 테스트", null)
            .setNegativeButton("취소", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val conf = read()
                toast("연결해 보는 중…")
                Thread {
                    val r = Ftp.test(conf)
                    runOnUiThread { AlertDialog.Builder(this).setMessage(r).setPositiveButton("확인", null).show() }
                }.start()
            }
        }
        d.show()
    }

    /** 화면 꺼진 채로 제때 돌려면 배터리 최적화에서 빼는 게 좋음 (한 번만 물어봄) */
    private fun askBatteryOnce() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle("화면 꺼져도 제때 돌게")
            .setMessage("배터리 최적화가 켜져 있으면 폰이 잠들어 있을 때 예약이 늦어질 수 있어요. 이 앱만 최적화에서 뺄까요?")
            .setPositiveButton("빼기") { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                }
            }
            .setNegativeButton("괜찮아요", null)
            .show()
    }

    /** 긴 글자는 한 줄로 두고 자동으로 흘러가게 (전광판처럼) */
    fun marquee(tv: TextView) {
        tv.isSingleLine = true
        tv.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        tv.marqueeRepeatLimit = -1
        tv.isSelected = true
    }

    /** 창 제목: 길면 한 줄로 자동 흘러감 */
    fun titleView(text: CharSequence): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(col(R.color.text))
        setPadding(dp(24), dp(20), dp(24), dp(8))
        marquee(this)
    }
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
        mini = MiniWindow(this)
        collector = Collector(this)
        grabber = Grabber(this)
        history = HistoryPage(this)
        live = LiveTranslate(this)
        ModelStore.migrate(this)
        JobStore.listeners.add(jobsListener)
        Downloads.onActivity = { onJobsChanged() }
        JobStore.onFinished = { j -> onGrabFinished(j) }

        val (urls, idx) = store.loadSession()
        if (urls.isEmpty()) {
            newTab(null)
        } else {
            urls.forEach { (u, id) ->
                val t = newTab(if (u == HOME) null else u, select = false)
                if (id > 0) t.id = id
            }
            cur = idx.coerceIn(0, tabs.size - 1)
            showTab()
            // 닫힌 탭의 미리보기 파일 정리
            Thumbs.cleanupTabs(this, tabs.map { it.id }.toSet())
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        // 알림을 눌러 들어온 경우
        when {
            i.getLongExtra(BgWork.EXTRA_JOB, -1L) >= 0 -> {
                val id = i.getLongExtra(BgWork.EXTRA_JOB, -1L)
                i.removeExtra(BgWork.EXTRA_JOB)
                closeOverlays()
                grabber.openJobById(id)
                return
            }
            i.getBooleanExtra(BgWork.EXTRA_JOBS, false) -> {
                i.removeExtra(BgWork.EXTRA_JOBS)
                closeOverlays()
                grabber.openList()
                return
            }
            i.getBooleanExtra(BgWork.EXTRA_DOWNLOADS, false) -> {
                i.removeExtra(BgWork.EXTRA_DOWNLOADS)
                closeOverlays()
                pages.openDownloads()
                return
            }
            i.getBooleanExtra("open_test_log", false) -> {
                i.removeExtra("open_test_log")
                showTestLog()
                return
            }
            i.getBooleanExtra("open_sched", false) -> {
                i.removeExtra("open_sched")
                closeOverlays()
                pages.openSchedules()
                return
            }
        }
        if (i.action == Intent.ACTION_VIEW) {
            i.data?.toString()?.let {
                closeOverlays()
                newTab(it)
            }
        }
    }

    /** 앱 화면이 지금 보이는지 (화면 꺼짐·홈으로 나가면 false) */
    var isShown = false
        private set

    private lateinit var parking: FrameLayout
    lateinit var updater: Updater

    /**
     * 지금 보는 탭이 아닌데 넘기며 모으는 중인 페이지는 화면 뒤(parking)에 붙여 둔다.
     * 화면에서 떼어 내면 웹 엔진이 멈추기 때문. 모으기가 끝난 페이지는 떼어 냄.
     */
    fun parkCollecting() {
        if (!::parking.isInitialized || !::collector.isInitialized) return
        if (::mini.isInitialized && mini.isShowing) return
        val current = tabs.getOrNull(cur)?.takeIf { !it.home }?.webView
        val want = collector.miniPages().filter { it !== current && hasWebView(it) }
        // 더 이상 필요 없는 것 떼기
        for (i in parking.childCount - 1 downTo 0) {
            val v = parking.getChildAt(i)
            if (v !in want) parking.removeViewAt(i)
        }
        val cw = content.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val ch = content.height.takeIf { it > 0 } ?: (resources.displayMetrics.heightPixels * 0.8f).toInt()
        want.forEach { w ->
            if (w.parent !== parking) {
                (w.parent as? ViewGroup)?.removeView(w)
                parking.addView(w, FrameLayout.LayoutParams(w.width.takeIf { it > 0 } ?: cw, w.height.takeIf { it > 0 } ?: ch))
            }
        }
    }

    /** 넘기며 모으는 동안 화면 꺼짐 방지 */
    private var keepOn = false

    fun setKeepScreenOn(on: Boolean) {
        if (on == keepOn) return
        keepOn = on
        if (on) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (isShown) showSnack("넘기며 모으는 동안 화면이 꺼지지 않게 해 둘게요", null, 4_000)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (::mini.isInitialized) mini.setKeepScreenOn(on)
    }

    /** 홈·다른 앱 위에 띄우는 작은 창 (넘기며 모으는 페이지를 계속 '보이게' 둠) */
    lateinit var mini: MiniWindow

    override fun onStop() {
        super.onStop()
        if (::collector.isInitialized) collector.saveAll()
        if (::updater.isInitialized && ::collector.isInitialized) {
            updater.installOnLeave(busy = collector.hasJobs() || JobStore.activeCount() > 0)
        }
        // 넘기며 모으는 중이면 페이지를 미니 창으로 옮겨서 계속 움직이게 함
        if (::collector.isInitialized && collector.hasJobs() && mini.canUse()) {
            val labels = collector.miniLabels()
            val pages = collector.miniPages()
            mini.show(pages.map { w -> w to pageSize(w) }, pages.map { labels[it] ?: "" })
        }
    }

    override fun onStart() {
        super.onStart()
        // 돌아오면 미니 창의 페이지를 원래 자리로
        if (::mini.isInitialized && mini.isShowing) {
            mini.hide()
            if (tabs.isNotEmpty()) showTab()
        }
        if (::parking.isInitialized) parkCollecting()
    }

    /** 웹뷰의 원래 크기 (탭 화면 크기) */
    private fun pageSize(w: WebView): Pair<Int, Int> {
        val cw = content.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val ch = content.height.takeIf { it > 0 } ?: (resources.displayMetrics.heightPixels * 0.8f).toInt()
        return Pair(w.width.takeIf { it > 0 } ?: cw, w.height.takeIf { it > 0 } ?: ch)
    }

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
        onJobsChanged()
        if (::grabber.isInitialized) frame.post { askPendingZips() }
        tabs.getOrNull(cur)?.let { if (!it.home) it.webView.onResume() }
        if (::updater.isInitialized) updater.onResume()
    }

    override fun onDestroy() {
        if (::mini.isInitialized) mini.hide()
        tabs.forEach { it.webView.destroy() }
        closedTab?.first?.webView?.destroy()
        if (Downloads.onFinish != null) Downloads.onFinish = null
        Downloads.onActivity = null
        JobStore.listeners.remove(jobsListener)
        JobStore.onFinished = null
        grabber.release()
        super.onDestroy()
    }

    private fun saveSession() {
        val list = tabs.map { t ->
            val u = when {
                t.pendingUrl != null -> t.pendingUrl!!
                t.home -> HOME
                else -> t.webView.url ?: HOME
            }
            Pair(u, t.id)
        }
        store.saveSession(list, cur)
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
        // 작업 현황 아이콘 (넘기며 모으기·이미지 받기·파일 다운로드) + 진행 중 개수
        jobsIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_download)
            imageTintList = ColorStateList.valueOf(col(R.color.text))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        jobsBadge = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(col(R.color.accent))
            }
            visibility = View.GONE
        }
        jobsBtn = FrameLayout(this).apply {
            ripple(this, borderless = true)
            contentDescription = "작업 현황"
            visibility = View.GONE
            setOnClickListener { leaveAddress(); closeOverlays(); grabber.openList() }
            addView(jobsIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            addView(jobsBadge, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(4); rightMargin = dp(2)
            })
        }
        top.addView(jobsBtn, LinearLayout.LayoutParams(dp(40), dp(44)))
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
        homeWrap = FastScroll.wrap(homeView)

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
        // 다른 탭에서 넘기며 모으는 페이지를 붙여 두는 곳 (화면 뒤에 가려져 있지만 웹 엔진은 '보이는 중'으로 다룸)
        parking = FrameLayout(this)
        updater = Updater(this)
        frame.postDelayed({ updater.autoCheck() }, 3000)
        frame.postDelayed({ AdBlock.refresh(this, store) }, 8000)
        frame.postDelayed({ Sched.ensure(this) }, 5000)
        frame.postDelayed({ if (::collector.isInitialized) collector.askSavedOnStart() }, 1500)
        frame.addView(parking, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        onJobsChanged()
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
            tabs.getOrNull(cur)?.webView?.let { pauseTab(it) }
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
            tabs.getOrNull(cur)?.webView?.let { pauseTab(it) }
            cur = i
        }
        showTab()
    }

    /** 탭 순서를 통째로 바꿈 (탭 목록에서 끌어서 옮겼을 때) */
    fun reorderTabs(order: List<Tab>) {
        if (order.size != tabs.size || order.toSet() != tabs.toSet()) return
        val current = tabs.getOrNull(cur)
        tabs.clear()
        tabs.addAll(order)
        cur = tabs.indexOf(current).coerceAtLeast(0)
        updateUi()
    }

    /** 여러 탭 한꺼번에 닫기 (그룹 닫기) */
    fun closeTabsNow(list: List<Tab>) {
        list.toList().forEach { t -> tabs.indexOf(t).takeIf { it >= 0 }?.let { closeTab(it) } }
    }

    /** 같은 사이트끼리 묶는 열쇠 (네이버·구글 …). 새 탭은 모두 하나로 */
    fun tabKey(t: Tab): String {
        if (t.home) return "새 탭"
        val u = t.pendingUrl ?: t.webView.url ?: return "새 탭"
        return Thumbs.key(u).ifBlank { "새 탭" }
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
        parkCollecting()
        if (t.home) {
            buildHome()
            content.addView(homeWrap)
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
        if (::collector.isInitialized) collector.refreshBar()
        if (::live.isInitialized) live.onTabChanged()
    }

    /** 탭을 목록에서 빼기만 한다 (웹뷰는 그대로) */
    private fun detachTab(i: Int): Tab {
        val wasCurrent = i == cur
        val t = tabs.removeAt(i)
        (t.webView.parent as? ViewGroup)?.removeView(t.webView)
        collector.tabClosing(t.webView)
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
        val t = detachTab(i)
        t.webView.destroy()
        Thumbs.deleteTab(this, t.id)
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
        closedTab?.first?.let {
            it.webView.destroy()
            Thumbs.deleteTab(this, it.id)
        }
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

    /** 지금 탭 화면을 미리보기로 찍음 (웹페이지가 그대로 보일 때만). 끝나면 done */
    fun captureCurrentTab(done: (() -> Unit)? = null) {
        val t = tabs.getOrNull(cur)
        if (t == null || t.home || !pageClearlyVisible()) { done?.invoke(); return }
        Thumbs.capture(window, t.webView, 0.45f, null) { b ->
            if (b != null) { t.thumb = b; Thumbs.saveTab(this, t.id, b) }
            done?.invoke()
        }
    }

    /** 웹페이지 위를 덮는 화면(탭 목록·설정·이미지 화면 등)이 없는지 */
    /** 실시간 번역용: 웹페이지가 다른 화면에 가리지 않고 보이는지 */
    fun canSeePage() = pageClearlyVisible()

    fun contentView(): FrameLayout = content

    private fun pageClearlyVisible() = isShown && !switcher.isOpen && !pages.isOpen && !grabber.isOpen &&
        !(::history.isInitialized && history.isOpen)

    fun closeOverlays() {
        if (switcher.isOpen) switcher.close()
        pages.closeAll()
        if (::grabber.isInitialized) {
            grabber.closeView()
            grabber.closeList()
        }
        if (::history.isInitialized) history.close()
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
            history.back() -> {}
            grabber.back() -> {}
            pages.back() -> {}
            switcher.isOpen -> switcher.back()
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
        // 누른 자리의 글 복사 (링크 글자, 또는 그 문단)
        val tx = lastTouchX
        val ty = lastTouchY
        if (link != null) items.add("링크 글자 복사" to { textAt(wv, tx, ty, link = true) })
        items.add("이 부분 글 복사" to { textAt(wv, tx, ty, link = false) })
        if (img != null) {
            items.add("이미지 저장" to { saveImage(wv, img) })
            if (img.startsWith("http")) {
                items.add("이미지를 새 탭에서 보기" to { openInBackground(img, findTab(wv)) })
            }
        }
        if (items.isEmpty()) return
        val title = (link ?: img ?: "").let { if (it.startsWith("data:")) "이미지" else displayUrl(it) }
        AlertDialog.Builder(this)
            .setCustomTitle(titleView(title.take(300)))
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .show()
    }

    /**
     * 누른 자리의 글을 읽어 와서 보여주고 복사 (원하는 부분만 골라 복사할 수도 있음).
     * link면 링크 글자만, 아니면 그 문단·목록 한 줄 정도.
     */
    private fun textAt(wv: WebView, x: Float, y: Float, link: Boolean) {
        val js = "(function(X,Y,VW,VH,L){var e=document.elementFromPoint(X*innerWidth/VW,Y*innerHeight/VH);if(!e)return '';" +
            "var t=L?(e.closest('a')||e):e;" +
            "if(!L){while(t.parentElement&&(t.innerText||'').trim().length<20&&t.tagName!='BODY')t=t.parentElement;" +
            "var b=t.closest('p,li,td,th,blockquote,article,h1,h2,h3,h4,dd,dt,pre');if(b&&(b.innerText||'').length<4000)t=b;}" +
            "return (t.innerText||t.textContent||'').trim().slice(0,5000);})(" +
            x + "," + y + "," + wv.width.coerceAtLeast(1) + "," + wv.height.coerceAtLeast(1) + "," + link + ")"
        wv.evaluateJavascript(js) { res ->
            val text = try { org.json.JSONTokener(res).nextValue() as? String } catch (e: Exception) { null }
            if (text.isNullOrBlank()) {
                toast("여기서는 글을 찾지 못했어요")
                return@evaluateJavascript
            }
            val et = EditText(this).apply {
                setText(text)
                textSize = 15f
                setTextIsSelectable(true)
                background = null
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            AlertDialog.Builder(this)
                .setTitle("글 복사 (골라서 복사하려면 꾸욱 눌러 선택)")
                .setView(ScrollView(this).apply { addView(et) })
                .setPositiveButton("전체 복사") { _, _ -> copy(text) }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    /** 복사·선택을 막은 사이트도 글을 꾸욱 눌러 선택할 수 있게 */
    private val allowSelectJs = "(function(){try{if(document.getElementById('__lb_sel'))return;" +
        "var s=document.createElement('style');s.id='__lb_sel';" +
        "s.textContent='body,body *:not(input):not(textarea){-webkit-user-select:text!important;user-select:text!important}';" +
        "(document.head||document.documentElement).appendChild(s);" +
        "['selectstart','copy','cut'].forEach(function(n){window.addEventListener(n,function(ev){ev.stopImmediatePropagation();},true);});" +
        "}catch(e){}})();"

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
    private fun createWebView(): WebView = LbWebView(this).apply {
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
        setOnScrollChangeListener { _, _, _, _, _ -> if (::live.isInitialized) live.onScroll(this) }
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
            // 넘기며 모으는 탭이면 이미지를 받아 두면서 페이지에 넘겨 줌
            if (::collector.isInitialized) collector.intercept(view, request)?.let { return it }
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
            view.evaluateJavascript(allowSelectJs, null)
            if (url != null && url.startsWith("http") && !AdBlock.blockedUrl(url)) {
                store.addHistory(view.title ?: url, url)
                if (::live.isInitialized) live.onPageChanged(view)
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
        if (!pageClearlyVisible()) return
        Thumbs.capture(window, view, 0.45f, null) { b -> if (b != null) { t.thumb = b; Thumbs.saveTab(this, t.id, b) } }
        if (dialIndexByKey(url) >= 0 && !Thumbs.hasFresh(this, url, 3 * 3600_000L)) {
            Thumbs.capture(window, view, 0.5f, Thumbs.DIAL_ASPECT) { b -> if (b != null) Thumbs.saveDial(this, url, b) }
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
            tabs.getOrNull(cur)?.webView?.let { pauseTab(it) }
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
            Thumbs.capture(window, t.webView, 0.5f, Thumbs.DIAL_ASPECT) { b -> if (b != null) Thumbs.saveDial(this, url, b) }
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
                    .setCustomTitle(titleView(d.title))
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
        m.add(0, 14, 0, "받다 멈춘 작업")
        m.add(0, 17, 0, "이 목록 전부 받기 (옛날 글부터)")
        m.add(0, 15, 0, "이 페이지 예약 저장")
        m.add(0, 16, 0, "전부 받기 · 예약 목록")
        m.add(0, 13, 0, "실시간 번역 (일본어 → 한국어)").apply { isCheckable = true; isChecked = live.isOn }
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
                12 -> { closeOverlays(); collector.beginOrResume() }
                13 -> live.toggle()
                14 -> { closeOverlays(); pages.openStopped() }
                15 -> addSchedule()
                17 -> fullList()
                16 -> { closeOverlays(); pages.openSchedules() }
            }
            true
        }
        pm.show()
    }

    /** 탭을 바꿀 때 이전 탭을 멈춤 (넘기며 모으는 탭은 계속 돌아가게 둠) */
    private fun pauseTab(w: WebView) {
        if (!collector.holds(w)) w.onPause()
    }

    fun hasWebView(w: WebView) = tabs.any { it.webView === w }

    fun currentWebView(): WebView? = tabs.getOrNull(cur)?.takeIf { !it.home }?.webView

    fun startCollector() {
        closeOverlays()
        collector.begin()
    }

    fun openGrabberWith(urls: List<String>, page: String?, ua: String?, name: String, show: Boolean) {
        if (show) closeOverlays()
        grabber.startWithUrls(urls, page, ua, name, show)
    }

    fun collectJobsInfo() = collector.info()
    fun chooseSaved(s: CollectSaves.Save) = collector.chooseSaved(s)
    fun activeCollectIds(): Set<Long> = collector.activeGrabIds()

    fun openGrabJob(g: GrabJob) {
        closeOverlays()
        grabber.openJob(g)
    }

    fun openDownloadsPage() = pages.openDownloads()

    /** 목록 화면은 그대로 두고 그 위에 작업 화면 (뒤로가기로 목록에 돌아옴) */
    fun viewGrabJob(g: GrabJob) = grabber.openJob(g)

    fun snackMsg(text: String) = showSnack(text, "작업 보기", 4_000) { closeOverlays(); grabber.openList() }

    fun selectTabOf(w: WebView) {
        val i = tabs.indexOfFirst { it.webView === w }
        if (i >= 0) {
            closeOverlays()
            selectTab(i)
        }
    }

    private val jobsListener: (GrabJob?) -> Unit = { onJobsChanged() }

    /** 작업 현황 아이콘: 진행 중이면 개수, 끝났는데 안 본 작업이 있으면 점 */
    fun onJobsChanged() {
        if (!::jobsBtn.isInitialized || !::collector.isInitialized || !::grabber.isInitialized) return
        val active = BgWork.collectJobs + JobStore.activeCount() +
            Downloads.items.count { it.state == Downloads.RUNNING }
        val unseen = JobStore.jobs.count { it.state == GrabJob.DONE && !it.seen }
        val show = active > 0 || JobStore.jobs.isNotEmpty() || collector.info().isNotEmpty()
        jobsBtn.visibility = if (show && !address.hasFocus()) View.VISIBLE else View.GONE
        jobsIcon.imageTintList = ColorStateList.valueOf(col(if (active > 0) R.color.accent else R.color.text))
        when {
            active > 0 -> { jobsBadge.visibility = View.VISIBLE; jobsBadge.text = if (active > 9) "9+" else active.toString() }
            unseen > 0 -> { jobsBadge.visibility = View.VISIBLE; jobsBadge.text = "" }
            else -> jobsBadge.visibility = View.GONE
        }
        grabber.refreshList()
    }

    /** 이미지 받기 완료: 앱 안에 완료 표시 (알림창 알림은 BgWork가 따로 띄움) */
    private fun onGrabFinished(j: GrabJob) {
        onJobsChanged()
        if (j.pics.isNotEmpty() && j.fails == 0) {
            // 전부 받았으면 압축 (설정에 따라 묻거나 자동)
            when (store.autoZip) {
                1 -> { grabber.zipAll(j, deleteAfter = true); return }
                2 -> {}
                else -> if (isShown) { askZip(j); return } else j.askZip = true
            }
        }
        if (!isShown) return
        val msg = when {
            j.pics.isEmpty() -> "'${j.title}' 받기 실패"
            j.fails > 0 -> "'${j.title}' ${j.pics.size}장 받음 · 실패 ${j.fails}개 · 확인 후 압축하세요"
            else -> "'${j.title}' ${j.pics.size}장 받기 완료"
        }
        showSnack(msg, "보기", 6_000) {
            closeOverlays()
            grabber.openJob(j)
        }
    }

    /** 다 받았을 때: 압축하고 받은 이미지는 지울까요? (다음엔 묻지 않음) */
    private fun askZip(j: GrabJob) {
        j.askZip = false
        if (JobStore.get(j.id) == null) return
        val check = android.widget.CheckBox(this).apply {
            text = "다음엔 묻지 않음"
            setTextColor(col(R.color.text))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(check)
        }
        AlertDialog.Builder(this)
            .setCustomTitle(titleView("'${j.title}' ${j.pics.size}장 다 받았어요"))
            .setMessage("ZIP으로 압축하고, 받은 이미지는 지울까요?\n(압축 파일은 다운로드 폴더에 저장돼요)")
            .setView(box)
            .setPositiveButton("압축하고 지우기") { _, _ ->
                if (check.isChecked) store.autoZip = 1
                grabber.zipAll(j, deleteAfter = true)
                toast("압축 중… 끝나면 알림이 떠요")
            }
            .setNegativeButton("그냥 두기") { _, _ ->
                if (check.isChecked) store.autoZip = 2
            }
            .setNeutralButton("먼저 보기") { _, _ ->
                closeOverlays()
                grabber.openJob(j)
            }
            .show()
    }

    /** 앱에 돌아왔을 때 물어보지 못한 '압축할까요?'가 있으면 */
    private fun askPendingZips() {
        JobStore.jobs.firstOrNull { it.askZip && it.state == GrabJob.DONE }?.let { askZip(it) }
    }

    /** 알림 표시 권한, 배터리 최적화 제외 (백그라운드 작업이 끊기지 않게) */
    fun askBackgroundPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        if (!mini.canUse() && !store.askedOverlay) {
            store.askedOverlay = true
            AlertDialog.Builder(this)
                .setTitle("홈에 나가 있어도 계속 모으려면")
                .setMessage("웹 엔진은 화면에 보이는 페이지만 제대로 움직여요. 앱을 나가면 모으는 페이지를 다른 앱 위에 작은 창으로 띄워 두도록 '다른 앱 위에 표시'를 허용해 주세요.\n\n(나중에 설정 › 미니 창에서도 허용할 수 있어요)")
                .setPositiveButton("허용하러 가기") { _, _ -> mini.askPermission() }
                .setNegativeButton("나중에") { _, _ -> askBattery() }
                .show()
            return
        }
        askBattery()
    }

    private fun askBattery() {
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
            grabber.start(url, null, wv.settings.userAgentString, wv.title)
            return
        }
        val js = "(function(){var b=null,ba=0;document.querySelectorAll('img').forEach(function(i){" +
            "var r=i.getBoundingClientRect();var a=r.width*r.height;var s=i.currentSrc||i.src;" +
            "if(a>ba&&s&&s.indexOf('http')==0){ba=a;b=s;}});return b;})()"
        wv.evaluateJavascript(js) { res ->
            val img = try { org.json.JSONTokener(res).nextValue() as? String } catch (e: Exception) { null }
            if (img.isNullOrBlank()) toast("이 페이지에서 이미지를 찾지 못했어요. 이미지를 꾸욱 눌러서 해 보세요")
            else grabber.start(img, url, wv.settings.userAgentString, wv.title)
        }
    }

    fun showHistory() {
        closeOverlays()
        history.open()
    }

    /** 방문 기록 화면 등에서 주소 열기 */
    fun openUrl(url: String) {
        closeOverlays()
        load(url)
    }

    fun clearAllHistory() {
        store.clearHistory()
        tabs.forEach { it.webView.clearHistory() }
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
