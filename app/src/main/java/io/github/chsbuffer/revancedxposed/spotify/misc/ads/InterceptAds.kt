package io.github.chsbuffer.revancedxposed.spotify.misc.ads

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import java.net.InetAddress
import java.io.IOException

private const val TAG = "AD_DIAG"
private const val DNS_DISCOVERY_MODE = false

@Suppress("UNCHECKED_CAST")
fun SpotifyHook.InterceptAds() {
    Log.i(TAG, "═══ InterceptAds (Stable Pure-Network Block) starting ═══")
    var hooks = 0

    // ══════════════════════════════════════════════════════════════
    // Blocked ad-serving domains (spclient and dealer kept unblocked)
    // Updated with verified 2026 Spotify ad-serving CDNs
    // ══════════════════════════════════════════════════════════════
    val blockedDomains = setOf(
        // ── Spotify ad infrastructure ────────────────────────────
        "ads.spotify.com",
        "ads-fa.spotify.com",
        "audio-ads.spotify.com",
        "audio-ak-spotify-com.akamaized.net",
        "audio2.spotify.com",
        "audio-ec.spotify.com",
        "heads-ec.spotify.com",
        "adstats.spotify.com",
        "adeventtracker.spotify.com",
        "sponsored-recommendations.spotify.com",
        "desktop.spotify.com",
        "weblb-wg.gslb.spotify.com",
        "redirect.spotify.net",
        
        // ── Spotify analytics & telemetry ────────────────────────
        "analytics.spotify.com",
        "metrics.spotify.com",
        "tracking.spotify.com",
        "log.spotify.com",
        "crashdump.spotify.com",

        // ── Third-party tracking & ads ───────────────────────────
        "firebaseinstallations.googleapis.com",
        "firebase-settings.crashlytics.com",
        "cdn.branch.io",
        "api2.branch.io",
        "pagead2.googlesyndication.com",
        "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "googleads.g.doubleclick.net",
        "tpc.googlesyndication.com",
        "bs.serving-sys.com",
        "bounceexchange.com",
        "sb.scorecardresearch.com",
        "b.scorecardresearch.com",
        "segment-data-us-east.zqtk.net",
        "live.ravelin.click"
    )

    // Blocked URL path prefixes (for shared domains like spclient)
    val blockedPathPrefixes = setOf(
        "/ads/",
        "/ad-logic/",
        "/ad-monetization/",
        "/v1/ads/",
        "/v2/ads/",
        "/v3/ads/",
        "/ads?"
    )

    fun isSpclientDomain(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return h.contains("spclient") && h.endsWith(".spotify.com")
    }

    fun isBlockedPath(path: String?): Boolean {
        if (path == null) return false
        val p = path.lowercase()
        return blockedPathPrefixes.any { prefix -> p.startsWith(prefix) }
    }

    val loopback = InetAddress.getByAddress("blocked.local", byteArrayOf(127, 0, 0, 1))
    val loopbackArray = arrayOf(loopback)

    fun isBlocked(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return blockedDomains.any { domain ->
            h == domain || h.endsWith(".$domain")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // LAYER 1: Hook InetAddress DNS resolution
    // ══════════════════════════════════════════════════════════════
    try {
        val method = InetAddress::class.java.getMethod("getAllByName", String::class.java)
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val host = param.args[0] as? String ?: return
                if (isBlocked(host)) {
                    Log.i(TAG, "★ DNS: BLOCKED getAllByName($host) → 127.0.0.1")
                    param.result = loopbackArray
                }
            }
        })
        Log.i(TAG, "✓ 1A: InetAddress.getAllByName hooked")
        hooks++
    } catch (e: Throwable) { Log.w(TAG, "✗ 1A: getAllByName: ${e.message}") }

    try {
        val method = InetAddress::class.java.getMethod("getByName", String::class.java)
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val host = param.args[0] as? String ?: return
                if (isBlocked(host)) {
                    Log.i(TAG, "★ DNS: BLOCKED getByName($host) → 127.0.0.1")
                    param.result = loopback
                }
            }
        })
        Log.i(TAG, "✓ 1B: InetAddress.getByName hooked")
        hooks++
    } catch (e: Throwable) { Log.w(TAG, "✗ 1B: getByName: ${e.message}") }

    // ══════════════════════════════════════════════════════════════
    // LAYER 2: Connection-level blocks
    // ══════════════════════════════════════════════════════════════
    try {
        val urlClass = java.net.URL::class.java
        val openConn = urlClass.getMethod("openConnection")
        XposedBridge.hookMethod(openConn, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val url = param.thisObject as? java.net.URL ?: return
                if (isBlocked(url.host)) {
                    Log.i(TAG, "★ URL: BLOCKED openConnection(${url.host}${url.path})")
                    param.throwable = IOException("Blocked: ${url.host}")
                }
            }
        })
        Log.i(TAG, "✓ 2A: URL.openConnection hooked")
        hooks++
    } catch (e: Throwable) { Log.w(TAG, "✗ 2A: URL.openConnection: ${e.message}") }

    // Path-level blocking on spclient domains (returning empty 204 No Content for ad paths)
    try {
        val interceptorClass = Class.forName("okhttp3.Interceptor\$Chain", false, classLoader)
        val requestClass = Class.forName("okhttp3.Request", false, classLoader)
        val responseClass = Class.forName("okhttp3.Response", false, classLoader)
        val responseBuilderClass = Class.forName("okhttp3.Response\$Builder", false, classLoader)
        val protocolClass = Class.forName("okhttp3.Protocol", false, classLoader)
        val responseBodyClass = Class.forName("okhttp3.ResponseBody", false, classLoader)
        val mediaTypeClass = Class.forName("okhttp3.MediaType", false, classLoader)
        val httpUrlClass = Class.forName("okhttp3.HttpUrl", false, classLoader)

        val urlMethod = requestClass.getMethod("url")
        val hostMethod = httpUrlClass.getMethod("host")
        val encodedPathMethod = httpUrlClass.getMethod("encodedPath")

        val newBuilder = responseBuilderClass.getConstructor()
        val builderRequest = responseBuilderClass.getMethod("request", requestClass)
        val builderProtocol = responseBuilderClass.getMethod("protocol", protocolClass)
        val builderCode = responseBuilderClass.getMethod("code", Int::class.java)
        val builderMessage = responseBuilderClass.getMethod("message", String::class.java)
        val builderBody = responseBuilderClass.getMethod("body", responseBodyClass)
        val builderBuild = responseBuilderClass.getMethod("build")

        val http11 = protocolClass.getField("HTTP_1_1").get(null)
        val parseMediaType = mediaTypeClass.getMethod("parse", String::class.java)
        val emptyMediaType = parseMediaType.invoke(null, "text/plain")
        val createBody = responseBodyClass.getMethod("create", mediaTypeClass, String::class.java)

        val proceedMethod = interceptorClass.getMethod("proceed", requestClass)
        XposedBridge.hookMethod(proceedMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val request = param.args[0] ?: return
                    val httpUrl = urlMethod.invoke(request) ?: return
                    val host = hostMethod.invoke(httpUrl) as? String ?: return
                    val path = encodedPathMethod.invoke(httpUrl) as? String ?: return

                    if (isSpclientDomain(host) && isBlockedPath(path)) {
                        Log.i(TAG, "★ PATH: BLOCKED $host$path")
                        val emptyBody = createBody.invoke(null, emptyMediaType, "")
                        val builder = newBuilder.newInstance()
                        builderRequest.invoke(builder, request)
                        builderProtocol.invoke(builder, http11)
                        builderCode.invoke(builder, 204)
                        builderMessage.invoke(builder, "Blocked by RVX")
                        builderBody.invoke(builder, emptyBody)
                        param.result = builderBuild.invoke(builder)
                    }
                } catch (_: Throwable) {}
            }
        })
        Log.i(TAG, "✓ 2B: OkHttp path-level blocking hooked")
        hooks++
    } catch (e: Throwable) { Log.w(TAG, "✗ 2B: OkHttp path blocking: ${e.message}") }

    // OkHttp DNS Interceptor
    try {
        val dnsInterface = Class.forName("okhttp3.Dns", false, classLoader)
        val systemDnsField = dnsInterface.getField("SYSTEM")
        val systemDns = systemDnsField.get(null) ?: throw Exception("SYSTEM dns is null")
        val lookupMethod = systemDns.javaClass.getMethod("lookup", String::class.java)
        XposedBridge.hookMethod(lookupMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val host = param.args[0] as? String ?: return
                if (isBlocked(host)) {
                    Log.i(TAG, "★ OkHttp: BLOCKED Dns.lookup($host) → 127.0.0.1")
                    param.result = listOf(loopback)
                }
            }
        })
        Log.i(TAG, "✓ 3: OkHttp Dns.SYSTEM.lookup hooked")
        hooks++
    } catch (e: Throwable) { Log.w(TAG, "✗ 3: OkHttp Dns: ${e.message}") }

    // ══════════════════════════════════════════════════════════════
    // LAYER 3: Visual Ad Suppression (Completely Safe Activity Finishes)
    // ══════════════════════════════════════════════════════════════
    try {
        val displayAdClass = Class.forName(
            "com.spotify.adsdisplay.display.DisplayAdActivity", false, classLoader
        )
        val onCreateMethod = displayAdClass.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
        onCreateMethod.isAccessible = true
        XposedBridge.hookMethod(onCreateMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val activity = param.thisObject as android.app.Activity
                    Log.i(TAG, "★ 3A: DisplayAdActivity.finish()")
                    activity.finish()
                } catch (e: Throwable) { Log.w(TAG, "3A: ${e.message}") }
            }
        })
        Log.i(TAG, "✓ 3A: DisplayAdActivity → finish()")
        hooks++
    } catch (e: Throwable) { Log.w(TAG, "✗ 3A: ${e.message}") }

    try {
        val browserClass = Class.forName(
            "com.spotify.adsdisplay.browser.inapp.InAppBrowserActivity", false, classLoader
        )
        val onCreateMethod = browserClass.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
        onCreateMethod.isAccessible = true
        XposedBridge.hookMethod(onCreateMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val activity = param.thisObject as android.app.Activity
                    Log.i(TAG, "★ 3B: InAppBrowserActivity.finish()")
                    activity.finish()
                } catch (e: Throwable) { Log.w(TAG, "3B: ${e.message}") }
            }
        })
        Log.i(TAG, "✓ 3B: InAppBrowserActivity → finish()")
        hooks++
    } catch (e: Throwable) { Log.w(TAG, "✗ 3B: ${e.message}") }

    Log.i(TAG, "═══ InterceptAds complete: $hooks hooks ═══")
}
