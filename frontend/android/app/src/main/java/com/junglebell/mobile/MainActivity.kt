package com.junglebell.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.getcapacitor.BridgeActivity
import com.getcapacitor.BridgeWebViewClient

class MainActivity : BridgeActivity() {

    private var notificationPermissionLauncher: ActivityResultLauncher<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = bridge ?: return
        val webView = b.getWebView()

        // Disable the rubber-band overscroll bounce. Without this, dragging
        // past the end of the page shifts the whole rendered page —
        // including the fixed bottom navigation — which makes the nav look
        // like it "gets pulled along" when scrolling.
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        // Android 15+ forces edge-to-edge and the web app does not consume
        // window insets, so its top bar (sidebar trigger + bell + settings)
        // slides under the status bar and its fixed bottom nav tucks behind
        // the gesture bar. Note: setPadding() on the WebView itself does NOT
        // shrink the web viewport (Chromium renders over the full view
        // bounds), so the padding goes on the content frame that contains
        // the WebView, which resizes it during layout. The initial value is
        // set synchronously from the platform dimensions (an inset listener
        // alone can miss the first dispatch); the listener then keeps it
        // exact on rotation / display changes. The frame background matches
        // the app theme so the strips are invisible.
        val content = findViewById<ViewGroup>(android.R.id.content)
        content.setBackgroundColor(getColor(R.color.bell_webview_background))
        val topBar = systemBarDimen("status_bar_height")
        // Top padding only: the top app bar must clear the status bar. The
        // WebView keeps reaching the screen bottom so the page's fixed
        // bottom nav (and its blurred background) spans the whole bottom
        // zone; the nav's own content is lifted above the gesture pill by
        // the injected safe-bottom padding (see PWA_STANDALONE_SPOOF /
        // applyNavSafeBottom), because this WebView reports
        // env(safe-area-inset-bottom) as 0.
        content.setPadding(0, topBar, 0, 0)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            view.setPadding(
                0,
                insets.getInsets(WindowInsetsCompat.Type.systemBars()).top,
                0,
                0,
            )
            applyNavSafeBottom(insets)
            insets
        }
        content.requestApplyInsets()

        b.setWebViewClient(
            object : BridgeWebViewClient(b) {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (!url.isNullOrEmpty() && url.startsWith(BASE_URL)) {
                        view.evaluateJavascript(PWA_STANDALONE_SPOOF, null)
                    }
                    super.onPageStarted(view, url, favicon)
                }
            },
        )

        webView.addJavascriptInterface(NativeBell(), "BellNative")
        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) postTestNotification()
            }
    }

    private fun systemBarDimen(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /**
     * Lifts the page's fixed bottom navigation above the gesture pill.
     * The stable bottom inset is the full system bar zone (small gesture
     * strip devices report 0 live, so the stable value is the safe one),
     * converted to CSS pixels for the injected style override.
     */
    private fun applyNavSafeBottom(insets: WindowInsetsCompat) {
        val cssPx = insets.getStableInsetBottom() / resources.displayMetrics.density
        val js = "window.__jbApplyNavBottom && window.__jbApplyNavBottom($cssPx)"
        val webView = bridge?.getWebView() ?: return
        webView.evaluateJavascript(js, null)
        // React may not have mounted the nav (or the injection may not have
        // run) when the first inset dispatch lands; retry once shortly after.
        Handler(Looper.getMainLooper()).postDelayed({
            webView.evaluateJavascript(js, null)
        }, 2500)
    }

    /** JS bridge for the injected "notification test" button. */
    inner class NativeBell {
        @JavascriptInterface
        fun sendTestNotification() {
            Handler(Looper.getMainLooper()).post {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    postTestNotification()
                }
            }
        }
    }

    private fun postTestNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Jungle Bell",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Jungle Bell 앱 알림" },
            )
        }
        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(this)
            }
        manager.notify(
            TEST_NOTIFICATION_ID,
            builder
                .setSmallIcon(R.drawable.ic_notification_bell)
                .setContentTitle("Jungle Bell 알림 테스트")
                .setContentText("알림 설정이 정상 동작합니다.")
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val BASE_URL = "https://jungle-bell.sijun-yang.com"
        private const val NOTIFICATION_CHANNEL_ID = "jungle_bell"
        private const val TEST_NOTIFICATION_ID = 1001

        /**
         * The Capacitor WebView loads the production site, and the production
         * platform detection only treats a client as an "installed PWA" when
         * navigator.standalone is true or (display-mode: standalone) matches.
         * This native shell *is* the installed companion app, so spoof both
         * signals at document start, before the page bundle runs. That flips
         * the production code into companion mode (cookie auth + pairing code
         * entry) without waiting for a production deploy of the
         * isCapacitorNative() fix.
         *
         * Android WebView also has no Web Push support (no window.PushManager),
         * so the post-pairing "prepare push" step would throw PUSH_UNSUPPORTED
         * and render a red error on load. We stub PushManager and wrap the
         * service worker registration so prepare-push succeeds silently and
         * only an explicit "push connect" click surfaces the clean
         * PUSH_UNSUPPORTED message ("use the installed PWA in a browser").
         * Real in-app push would need native FCM (future work).
         *
         * Finally, the script adds a floating native "notification test"
         * button (window.BellNative.sendTestNotification) because web push
         * cannot work in the WebView; it verifies the Android notification
         * pipeline (permission, channel, display) end to end.
         */
        private val PWA_STANDALONE_SPOOF =
            """
            (function () {
              try {
                Object.defineProperty(navigator, 'standalone', { value: true, configurable: true });
              } catch (e) {}
              try {
                if (window.matchMedia) {
                  var orig = window.matchMedia.bind(window);
                  window.matchMedia = function (query) {
                    var m = orig(query);
                    try {
                      if (typeof query === 'string' &&
                          query.indexOf('display-mode: standalone') !== -1 &&
                          !m.matches) {
                        return {
                          matches: true,
                          media: m.media,
                          onchange: null,
                          addListener: function () {},
                          removeListener: function () {},
                          addEventListener: function () {},
                          removeEventListener: function () {},
                          dispatchEvent: function () { return false; },
                        };
                      }
                    } catch (e2) {}
                    return m;
                  };
                }
              } catch (e) {}
              try {
                if (!('PushManager' in window)) {
                  window.PushManager = function PushManager() {};
                }
              } catch (e) {}
              try {
                var sw = navigator.serviceWorker;
                if (sw) {
                  var stubRegistration = null;
                  var unsupportedPush = {
                    subscribe: function () {
                      return Promise.reject(new Error('PUSH_UNSUPPORTED'));
                    }
                  };
                  var cleanRegistration = function (reg) {
                    if (!reg) return reg;
                    try {
                      Object.defineProperty(reg, 'pushManager', {
                        configurable: true,
                        value: unsupportedPush
                      });
                    } catch (e) {}
                    return reg;
                  };
                  var originalRegister = sw.register.bind(sw);
                  sw.register = function (scriptUrl, options) {
                    return originalRegister(scriptUrl, options).then(
                      function (reg) { return cleanRegistration(reg); },
                      function () {
                        if (!stubRegistration) {
                          stubRegistration = {
                            active: null,
                            installing: null,
                            navigating: null,
                            waiting: null,
                            scope: (options && options.scope) || '/',
                            update: function () { return Promise.resolve(null); },
                            uninstall: function () { return Promise.resolve(false); },
                            pushManager: unsupportedPush
                          };
                        }
                        return stubRegistration;
                      }
                    );
                  };
                  var readyDescriptor =
                    Object.getOwnPropertyDescriptor(sw, 'ready') ||
                    Object.getOwnPropertyDescriptor(Object.getPrototypeOf(sw), 'ready');
                  if (readyDescriptor && readyDescriptor.get) {
                    Object.defineProperty(sw, 'ready', {
                      configurable: true,
                      get: function () {
                        return readyDescriptor.get.call(sw).then(
                          cleanRegistration,
                          function () { return stubRegistration || null; }
                        );
                      }
                    });
                  }
                }
              } catch (e) {}
              try {
                function addNativeTestButton() {
                  if (!window.BellNative || document.getElementById('jb-native-test-btn')) return;
                  var btn = document.createElement('button');
                  btn.id = 'jb-native-test-btn';
                  btn.type = 'button';
                  btn.textContent = '🔔 알림 테스트';
                  btn.style.cssText = [
                    'position:fixed','right:16px','bottom:96px','z-index:2147483647',
                    'border:1px solid rgba(255,255,255,0.16)','border-radius:9999px',
                    'background:rgba(24,30,26,0.94)','color:#E7ECE9',
                    'padding:10px 14px','font-size:13px','line-height:1',
                    'font-family:inherit','letter-spacing:-0.01em',
                    'box-shadow:0 8px 24px rgba(0,0,0,0.4)'
                  ].join(';');
                  btn.addEventListener('click', function () {
                    btn.disabled = true;
                    btn.textContent = '보내는 중...';
                    try {
                      window.BellNative.sendTestNotification();
                      btn.textContent = '알림 보냄 ✓';
                    } catch (e) {
                      btn.textContent = '알림 실패';
                    }
                    setTimeout(function () {
                      btn.disabled = false;
                      btn.textContent = '🔔 알림 테스트';
                    }, 4000);
                  });
                  document.body.appendChild(btn);
                }
                if (document.readyState === 'loading') {
                  document.addEventListener('DOMContentLoaded', addNativeTestButton, { once: true });
                } else {
                  addNativeTestButton();
                }
              } catch (e) {}
              try {
                // Kill rubber-band overscroll at the CSS level as well (the
                // native OVER_SCROLL_NEVER covers the root; this also stops
                // scroll chaining from inner scroll containers).
                var __jbNoOverscroll = function () {
                  if (document.documentElement) {
                    document.documentElement.style.overscrollBehavior = 'none';
                  }
                  if (document.body) {
                    document.body.style.overscrollBehavior = 'none';
                  }
                };
                __jbNoOverscroll();
                document.addEventListener('DOMContentLoaded', __jbNoOverscroll, { once: true });
              } catch (e) {}
              try {
                window.__jbNavSafeBottomPx = 0;
                window.__jbApplyNavBottom = function (cssPx) {
                  window.__jbNavSafeBottomPx = cssPx;
                  var tries = 0;
                  var apply = function () {
                    var nav = document.querySelector('nav[data-navigation-group="primary"]');
                    if (nav) {
                      nav.style.paddingBottom = window.__jbNavSafeBottomPx + 'px';
                      return;
                    }
                    if (tries++ < 100) {
                      setTimeout(apply, 100);
                    }
                  };
                  apply();
                };
              } catch (e) {}
            })();
            """
                .trimIndent()
    }
}
