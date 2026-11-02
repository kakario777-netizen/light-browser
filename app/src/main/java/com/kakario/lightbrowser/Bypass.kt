package com.kakario.lightbrowser

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 차단 우회용 앱 내부 중계기.
 * 웹뷰의 모든 접속을 이 중계기로 돌려서
 *  1) 사이트 주소를 통신사 DNS 대신 보안 DNS(Cloudflare·Google, 암호화)로 찾고
 *  2) 접속 첫 신호(접속할 사이트 이름이 드러나는 부분)를 잘게 쪼개 보내
 *     통신사의 주소 검사(SNI 검사)를 피한다.
 * HTTPS 내용은 건드리지 않고 그대로 전달하므로 보안 인증서 확인은 원래대로 이뤄진다.
 */
object Bypass {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    pool.execute { handle(c) }
                } catch (e: Exception) {
                }
            }
        }
        return s.localPort
    }

    /** 중계기가 켜져 있으면 포트 번호 */
    fun port(): Int? = server?.takeIf { !it.isClosed }?.localPort

    @Synchronized
    fun stop() {
        try {
            server?.close()
        } catch (e: Exception) {
        }
        server = null
    }

    private fun close(s: Socket?) {
        try {
            s?.close()
        } catch (e: Exception) {
        }
    }

    private fun handle(c: Socket) {
        var remote: Socket? = null
        try {
            c.soTimeout = 30000
            val cin = BufferedInputStream(c.getInputStream())
            val head = readHead(cin)
            if (head == null) {
                close(c); return
            }
            val first = head.substringBefore("\r\n").split(" ")
            if (first.size < 3) {
                close(c); return
            }
            val method = first[0]
            val target = first[1]
            if (method.equals("CONNECT", ignoreCase = true)) {
                val host = target.substringBeforeLast(":").trim('[', ']')
                val port = target.substringAfterLast(":").toIntOrNull() ?: 443
                remote = connect(host, port)
                c.getOutputStream().apply {
                    write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    flush()
                }
                c.soTimeout = 0
                remote.soTimeout = 0
                tunnel(c, cin, remote, host, tls = true)
            } else {
                // 일반 http 주소
                val uri = URI(target)
                val host = uri.host
                if (host == null) {
                    close(c); return
                }
                val port = if (uri.port > 0) uri.port else 80
                remote = connect(host, port)
                val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                val headers = head.split("\r\n").drop(1).filter {
                    it.isNotEmpty() && !it.startsWith("Proxy-Connection", true) && !it.startsWith("Connection:", true)
                }
                val req = "$method $path ${first[2]}\r\n" + headers.joinToString("\r\n") +
                    "\r\nConnection: close\r\n\r\n"
                val out = remote.getOutputStream()
                sendSplit(out, req.toByteArray(Charsets.ISO_8859_1), host, tls = false)
                c.soTimeout = 0
                remote.soTimeout = 0
                tunnel(c, cin, remote, null, tls = false)
            }
        } catch (e: Exception) {
            close(c)
            close(remote)
        }
    }

    private fun readHead(ins: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (buf.size() < 65536) {
            val b = ins.read()
            if (b < 0) return null
            buf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return buf.toString("ISO-8859-1")
        }
        return null
    }

    /** 브라우저 ↔ 사이트 사이에서 데이터를 양방향으로 그대로 넘긴다. 첫 묶음만 쪼개서 보냄 */
    private fun tunnel(c: Socket, cin: InputStream, r: Socket, splitHost: String?, tls: Boolean) {
        val rin = r.getInputStream()
        val rout = r.getOutputStream()
        val cout = c.getOutputStream()
        pool.execute {
            try {
                val b = ByteArray(16384)
                while (true) {
                    val n = rin.read(b)
                    if (n < 0) break
                    cout.write(b, 0, n)
                    cout.flush()
                }
            } catch (e: Exception) {
            }
            close(c)
            close(r)
        }
        try {
            val b = ByteArray(16384)
            var firstChunk = splitHost != null
            while (true) {
                val n = cin.read(b)
                if (n < 0) break
                if (firstChunk) {
                    firstChunk = false
                    sendSplit(rout, b.copyOf(n), splitHost!!, tls)
                } else {
                    rout.write(b, 0, n)
                    rout.flush()
                }
            }
        } catch (e: Exception) {
        }
        close(c)
        close(r)
    }

    private fun indexOf(data: ByteArray, pat: ByteArray, from: Int = 0): Int {
        if (pat.isEmpty()) return -1
        outer@ for (i in from..data.size - pat.size) {
            for (j in pat.indices) if (data[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    /**
     * 사이트 이름이 들어 있는 부분을 두 조각으로 나눠 따로 보낸다.
     * HTTPS는 TLS 기록 자체도 둘로 나눠서(서버는 정상적으로 다시 합쳐 읽음) 검사기가 이름을 못 읽게 한다.
     */
    private fun sendSplit(out: OutputStream, data: ByteArray, host: String, tls: Boolean) {
        var d = data
        var cut: Int
        val hostBytes = host.toByteArray(Charsets.ISO_8859_1)
        if (tls && d.size > 5 && d[0] == 0x16.toByte()) {
            val len = ((d[3].toInt() and 0xff) shl 8) or (d[4].toInt() and 0xff)
            if (5 + len <= d.size) {
                val idx = indexOf(d, hostBytes, 5)
                val k = if (idx > 5) (idx - 5) + 1 else 1   // 사이트 이름 첫 글자 바로 뒤에서 자름
                if (k in 1 until len) {
                    val o = ByteArrayOutputStream()
                    o.write(0x16); o.write(d[1].toInt()); o.write(d[2].toInt())
                    o.write(k shr 8); o.write(k and 0xff)
                    o.write(d, 5, k)
                    val rest = len - k
                    o.write(0x16); o.write(d[1].toInt()); o.write(d[2].toInt())
                    o.write(rest shr 8); o.write(rest and 0xff)
                    o.write(d, 5 + k, rest)
                    o.write(d, 5 + len, d.size - 5 - len)
                    d = o.toByteArray()
                }
                cut = minOf(5 + k, d.size)
            } else {
                cut = minOf(1, d.size)
            }
        } else {
            val idx = indexOf(d, hostBytes)
            cut = if (idx > 0) idx + 1 else minOf(1, d.size)
        }
        out.write(d, 0, cut)
        out.flush()
        try {
            Thread.sleep(3)
        } catch (e: InterruptedException) {
        }
        out.write(d, cut, d.size - cut)
        out.flush()
    }

    private fun connect(host: String, port: Int): Socket {
        var last: Exception? = null
        for (a in resolve(host)) {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(a, port), 10000)
                return s
            } catch (e: Exception) {
                close(s)
                last = e
            }
        }
        throw last ?: IOException("주소를 찾지 못함: $host")
    }

    // ---------------- 보안 DNS ----------------

    private val DOH = listOf(
        "https://1.1.1.1/dns-query?type=A&name=",
        "https://8.8.8.8/resolve?type=A&name=",
        "https://1.0.0.1/dns-query?type=A&name=",
    )

    private fun isIp(h: String) = h.contains(':') || Regex("^[0-9.]+$").matches(h)

    fun resolve(host: String): List<InetAddress> {
        if (isIp(host) || host == "localhost") return listOf(InetAddress.getByName(host))
        val now = System.currentTimeMillis()
        cache[host]?.let { if (it.second > now) return it.first }
        for (base in DOH) {
            try {
                val conn = URL(base + URLEncoder.encode(host, "UTF-8")).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Accept", "application/dns-json")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val ans = JSONObject(body).optJSONArray("Answer") ?: continue
                val list = mutableListOf<InetAddress>()
                var ttl = 300
                for (i in 0 until ans.length()) {
                    val o = ans.getJSONObject(i)
                    if (o.optInt("type") == 1) {
                        list.add(InetAddress.getByName(o.getString("data")))
                        ttl = minOf(ttl, maxOf(60, o.optInt("TTL", 300)))
                    }
                }
                if (list.isNotEmpty()) {
                    cache[host] = Pair(list, now + ttl * 1000L)
                    return list
                }
            } catch (e: Exception) {
            }
        }
        // 보안 DNS가 모두 실패하면 폰 기본 DNS로
        return InetAddress.getAllByName(host).toList()
    }
}
