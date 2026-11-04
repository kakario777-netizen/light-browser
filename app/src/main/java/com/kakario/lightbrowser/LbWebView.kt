package com.kakario.lightbrowser

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * 이 앱의 웹뷰.
 * 넘기며 모으는 중(keepVisible)에는 앱이 화면에서 사라져도 웹 엔진에 '보이는 중'이라고 알려서
 * 숨은 페이지에 걸리는 타이머 절전·화면 갱신 정지로 모으기가 멈추지 않게 한다.
 */
class LbWebView(ctx: Context) : WebView(ctx) {
    var keepVisible = false
        set(v) {
            if (field == v) return
            field = v
            // 끌 때는 실제 상태를 다시 알려 줌
            if (!v) {
                super.onWindowVisibilityChanged(windowVisibility)
                super.onVisibilityChanged(this, visibility)
            }
        }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(if (keepVisible) View.VISIBLE else visibility)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, if (keepVisible) View.VISIBLE else visibility)
    }
}
