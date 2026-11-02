package com.kakario.lightbrowser

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** 광고 서버 주소로 가는 요청을 빈 응답으로 막는 기본 광고 차단. */
object AdBlock {
    private val hosts = hashSetOf(
        // 해외 광고망
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "adnxs.com", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "amazon-adsystem.com", "adsrvr.org",
        "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com",
        "smartadserver.com", "moatads.com", "scorecardresearch.com",
        "advertising.com", "adform.net", "bidswitch.net", "33across.com",
        "media.net", "popads.net", "propellerads.com", "exoclick.com",
        "juicyads.com", "trafficjunky.net", "adsterra.com", "yieldmo.com",
        "teads.tv", "sharethrough.com", "inmobi.com", "applovin.com",
        "unityads.unity3d.com", "ads.yahoo.com",
        // 국내 광고망
        "mobon.net", "dable.io", "realclick.co.kr", "adop.cc", "tenping.kr",
        "cauly.co.kr", "ad.daum.net", "veta.naver.com", "mediacategory.com",
        "interworksmedia.co.kr", "adpnut.com", "tpmn.co.kr", "adinc.co.kr",
        "aceplanet.co.kr", "targetpush.co.kr", "wider.co.kr",
    )

    fun blocked(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        while (true) {
            if (h in hosts) return true
            val i = h.indexOf('.')
            if (i < 0 || h.indexOf('.', i + 1) < 0) return false
            h = h.substring(i + 1)
        }
    }

    fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
