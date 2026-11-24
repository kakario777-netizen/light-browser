package com.kakario.lightbrowser

import android.content.Context
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream

/**
 * 예약 저장한 그림을 FTP(NAS 등)로도 올림 (녹화 앱처럼).
 * 서버 정보는 설정 › 예약 저장 FTP에서 한 번만 넣고, 예약마다 'FTP로도 올리기'를 켜고 끔.
 */
object Ftp {
    class Conf(val host: String, val port: Int, val user: String, val pass: String, val dir: String, val passive: Boolean,
               val enc: String = "UTF-8", val secure: Boolean = false) {
        val ok get() = host.isNotBlank()
    }

    /**
     * 접속 정보는 안드로이드 보안 저장소(Keystore 키로 암호화한 저장소)에 둠.
     * 예전 일반 저장소(ftp)에 있던 값은 한 번 옮기고 지움. 구글 자동 백업에서도 빠짐(backup_rules).
     */
    private fun prefs(ctx: Context): android.content.SharedPreferences {
        val app = ctx.applicationContext
        val sec = try {
            val key = androidx.security.crypto.MasterKey.Builder(app).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
            androidx.security.crypto.EncryptedSharedPreferences.create(app, "ftp_secure", key,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        } catch (e: Exception) {
            return app.getSharedPreferences("ftp", Context.MODE_PRIVATE)
        }
        val old = app.getSharedPreferences("ftp", Context.MODE_PRIVATE)
        if (old.all.isNotEmpty()) {
            val ed = sec.edit()
            old.all.forEach { (k, v) ->
                when (v) {
                    is String -> ed.putString(k, v); is Int -> ed.putInt(k, v); is Boolean -> ed.putBoolean(k, v)
                }
            }
            ed.apply()
            old.edit().clear().apply()
        }
        return sec
    }

    fun conf(ctx: Context) = prefs(ctx).let {
        Conf(it.getString("host", "") ?: "", it.getInt("port", 21), it.getString("user", "") ?: "",
            it.getString("pass", "") ?: "", it.getString("dir", "/라이트 브라우저") ?: "/라이트 브라우저", it.getBoolean("passive", true),
            it.getString("enc", "UTF-8") ?: "UTF-8", it.getBoolean("secure", false))
    }

    fun save(ctx: Context, c: Conf) {
        prefs(ctx).edit().putString("host", c.host.trim()).putInt("port", c.port).putString("user", c.user)
            .putString("pass", c.pass).putString("dir", c.dir.trim()).putBoolean("passive", c.passive).putString("enc", c.enc).putBoolean("secure", c.secure).apply()
    }

    /** 한 번 실행하는 동안 연결 하나를 열어 두고 여러 파일을 올림 */
    class Session(private val c: Conf) {
        private var cl: FTPClient? = null
        private val made = HashSet<String>()
        var error: String? = null
            private set

        private fun client(): FTPClient? {
            cl?.let { if (it.isConnected) return it }
            return try {
                // FTPS(암호화)를 켜면 아이디·비밀번호·파일이 암호화돼서 오감
                val f = if (c.secure) FTPSClient(false) else FTPClient()
                // 한글 파일·폴더 이름: 서버에 맞춰 UTF-8 또는 EUC-KR (오래된 NAS·공유기는 EUC-KR)
                f.controlEncoding = c.enc
                f.connectTimeout = 15000
                f.connect(c.host, c.port)
                if (!FTPReply.isPositiveCompletion(f.replyCode)) throw Exception("서버가 연결을 거절함 (${f.replyCode})")
                if (!f.login(c.user.ifBlank { "anonymous" }, c.pass)) throw Exception("로그인 실패 (아이디·비밀번호 확인)")
                if (f is FTPSClient) { f.execPBSZ(0); f.execPROT("P") }
                f.setFileType(FTP.BINARY_FILE_TYPE)
                if (c.passive) f.enterLocalPassiveMode()
                f.soTimeout = 30000
                if (c.enc == "UTF-8") runCatching { f.sendCommand("OPTS UTF8 ON") }
                cl = f
                f
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                null
            }
        }

        private fun mkdirs(f: FTPClient, path: String) {
            var cur = ""
            for (part in path.split('/').filter { it.isNotBlank() }) {
                cur += "/$part"
                if (cur in made) continue
                f.makeDirectory(cur)
                made.add(cur)
            }
        }

        /** sub: '예약이름/글 제목' 같은 폴더, name: 파일 이름 */
        fun upload(sub: String, name: String, bytes: ByteArray): Boolean {
            val f = client() ?: return false
            return try {
                val dir = (c.dir.trimEnd('/') + "/" + sub).replace(Regex("/+"), "/")
                mkdirs(f, dir)
                val ok = f.storeFile("$dir/$name", ByteArrayInputStream(bytes))
                if (!ok) error = "올리기 실패 (${f.replyString?.trim()})"
                ok
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                runCatching { cl?.disconnect() }
                cl = null
                false
            }
        }

        /** 큰 파일을 그대로 흘려 올림 (zip) */
        fun uploadFile(sub: String, name: String, file: java.io.File): Boolean {
            val f = client() ?: return false
            return try {
                val dir = (c.dir.trimEnd('/') + "/" + sub).replace(Regex("/+"), "/")
                mkdirs(f, dir)
                val ok = file.inputStream().buffered().use { s -> f.storeFile("$dir/$name", s) }
                if (!ok) error = "올리기 실패 (${f.replyString?.trim()})"
                ok
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                runCatching { cl?.disconnect() }
                cl = null
                false
            }
        }

        /** 폴더 고르기: 그 폴더 안의 하위 폴더 이름들 (실패하면 null) */
        fun dirs(path: String): List<String>? {
            val f = client() ?: return null
            return try {
                f.listDirectories(path.ifBlank { "/" })?.map { it.name }?.filter { it != "." && it != ".." }?.sorted()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                null
            }
        }

        fun mkdir(path: String): Boolean = try { client()?.makeDirectory(path) == true } catch (e: Exception) { false }

        fun close() {
            runCatching { cl?.logout() }
            runCatching { cl?.disconnect() }
            cl = null
        }
    }

    /** 설정 화면의 '연결 테스트' (백그라운드에서 부를 것) */
    fun test(c: Conf): String {
        val s = Session(c)
        val ok = s.upload("_연결테스트", "test.txt", "light-browser ftp test".toByteArray())
        s.close()
        return if (ok) "연결 성공 · ${c.dir.trimEnd('/')}/_연결테스트/test.txt 를 올렸어요" else "연결 실패: ${s.error ?: "알 수 없음"}"
    }
}
