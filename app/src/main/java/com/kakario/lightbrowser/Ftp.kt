package com.kakario.lightbrowser

import android.content.Context
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.ByteArrayInputStream

/**
 * 예약 저장한 그림을 FTP(NAS 등)로도 올림 (녹화 앱처럼).
 * 서버 정보는 설정 › 예약 저장 FTP에서 한 번만 넣고, 예약마다 'FTP로도 올리기'를 켜고 끔.
 */
object Ftp {
    class Conf(val host: String, val port: Int, val user: String, val pass: String, val dir: String, val passive: Boolean) {
        val ok get() = host.isNotBlank()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ftp", Context.MODE_PRIVATE)

    fun conf(ctx: Context) = prefs(ctx).let {
        Conf(it.getString("host", "") ?: "", it.getInt("port", 21), it.getString("user", "") ?: "",
            it.getString("pass", "") ?: "", it.getString("dir", "/라이트 브라우저") ?: "/라이트 브라우저", it.getBoolean("passive", true))
    }

    fun save(ctx: Context, c: Conf) {
        prefs(ctx).edit().putString("host", c.host.trim()).putInt("port", c.port).putString("user", c.user)
            .putString("pass", c.pass).putString("dir", c.dir.trim()).putBoolean("passive", c.passive).apply()
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
                val f = FTPClient()
                f.controlEncoding = "UTF-8"
                f.connectTimeout = 15000
                f.connect(c.host, c.port)
                if (!FTPReply.isPositiveCompletion(f.replyCode)) throw Exception("서버가 연결을 거절함 (${f.replyCode})")
                if (!f.login(c.user.ifBlank { "anonymous" }, c.pass)) throw Exception("로그인 실패 (아이디·비밀번호 확인)")
                f.setFileType(FTP.BINARY_FILE_TYPE)
                if (c.passive) f.enterLocalPassiveMode()
                f.soTimeout = 30000
                // 한글 파일 이름
                runCatching { f.sendCommand("OPTS UTF8 ON") }
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
