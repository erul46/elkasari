package com.elkasari.dsps3

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bleBridge: BleGattBridge

    private var pendingBleCallback: ((Boolean) -> Unit)? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        pendingBleCallback?.invoke(allGranted)
        pendingBleCallback = null
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val req = pendingPermissionRequest
        pendingPermissionRequest = null
        if (req != null) {
            if (granted) req.grant(req.resources) else req.deny()
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        if (result.resultCode != RESULT_OK || result.data == null) {
            cb.onReceiveValue(null)
            return@registerForActivityResult
        }
        val data = result.data!!
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) {
            val uris = Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            cb.onReceiveValue(uris)
        } else if (data.data != null) {
            cb.onReceiveValue(arrayOf(data.data!!))
        } else {
            cb.onReceiveValue(null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        bleBridge = BleGattBridge(this, webView) { onResult ->
            ensureBlePermissions(onResult)
        }
        webView.addJavascriptInterface(bleBridge, "AndroidBleBridge")

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
    super.onPageStarted(view, url, favicon)
    if (url != null && url.startsWith("https://appassets.androidplatform.net/")) {
        view.evaluateJavascript(WEB_BLUETOOTH_POLYFILL_JS, null)
    }
}

        override fun onPageFinished(view: WebView, url: String?) {
    super.onPageFinished(view, url)
    if (url != null && url.startsWith("https://appassets.androidplatform.net/")) {
        view.evaluateJavascript(WEB_BLUETOOTH_POLYFILL_JS, null)
    }
}

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("https://appassets.androidplatform.net/")) {
                    return false
                }
if (url.startsWith("intent://")) {
    try {
        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            component = null
            selector = null
        }
        startActivity(intent)
    } catch (_: Exception) {}
    return true
}
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {}
                    return true
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val needsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (needsAudio) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.resources)
                    } else {
                        pendingPermissionRequest = request
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    request.grant(request.resources)
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                fileChooserLauncher.launch(Intent.createChooser(intent, "Pilih File Audio"))
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
    }

    private fun ensureBlePermissions(callback: (Boolean) -> Unit) {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            callback(true)
        } else {
            pendingBleCallback = callback
            blePermissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        bleBridge.closeActiveGatt()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private val WEB_BLUETOOTH_POLYFILL_JS = """
            (function() {
              if (window.__androidBleInstalled) return;
              window.__androidBleInstalled = true;

              var cbSeq = 0;
              var pending = {};
              var charRegistry = {};
              var activeDevices = {};

              function nextId() { return 'cb_' + (++cbSeq); }

              function callNative(fnName) {
                var args = Array.prototype.slice.call(arguments, 1);
                return new Promise(function(resolve, reject) {
                  var id = nextId();
                  pending[id] = { resolve: resolve, reject: reject };
                  try {
                    window.AndroidBleBridge[fnName].apply(window.AndroidBleBridge, [id].concat(args));
                  } catch (e) {
                    delete pending[id];
                    reject(e);
                  }
                });
              }

              function bufToBase64(buf) {
                var bytes;
                if (buf instanceof ArrayBuffer) bytes = new Uint8Array(buf);
                else if (ArrayBuffer.isView(buf)) bytes = new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
                else bytes = new Uint8Array(buf);
                var bin = '';
                for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
                return btoa(bin);
              }

              function base64ToDataView(b64) {
                var bin = atob(b64);
                var len = bin.length;
                var bytes = new Uint8Array(len);
                for (var i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i);
                return new DataView(bytes.buffer);
              }

              window.__androidBle = {
                _resolve: function(id, val) {
                  var p = pending[id];
                  if (p) { delete pending[id]; p.resolve(val); }
                },
                _reject: function(id, msg) {
                  var p = pending[id];
                  if (p) { delete pending[id]; p.reject(new Error(msg)); }
                },
                _onNotify: function(serviceUuid, charUuid, b64) {
                  var key = serviceUuid.toLowerCase() + '|' + charUuid.toLowerCase();
                  var ch = charRegistry[key];
                  if (!ch) return;
                  ch.value = base64ToDataView(b64);
                  var evt = { type: 'characteristicvaluechanged', target: ch };
                  (ch._listeners['characteristicvaluechanged'] || []).forEach(function(fn) {
                    try { fn(evt); } catch (err) { console.error(err); }
                  });
                },
                _onDisconnect: function(addr) {
                  var dev = activeDevices[addr];
                  if (!dev) return;
                  dev.gatt.connected = false;
                  var evt = { type: 'gattserverdisconnected', target: dev };
                  (dev._listeners['gattserverdisconnected'] || []).forEach(function(fn) {
                    try { fn(evt); } catch (err) { console.error(err); }
                  });
                }
              };

              function makeCharacteristic(serviceUuid, charUuid) {
                var sUuid = serviceUuid.toLowerCase();
                var cUuid = charUuid.toLowerCase();
                var key = sUuid + '|' + cUuid;
                if (charRegistry[key]) return charRegistry[key];

                var ch = {
                  uuid: cUuid,
                  value: null,
                  _listeners: {},
                  addEventListener: function(type, fn) {
                    this._listeners[type] = this._listeners[type] || [];
                    if (this._listeners[type].indexOf(fn) === -1) this._listeners[type].push(fn);
                  },
                  removeEventListener: function(type, fn) {
                    var list = this._listeners[type];
                    if (!list) return;
                    var idx = list.indexOf(fn);
                    if (idx !== -1) list.splice(idx, 1);
                  },
                  startNotifications: function() {
                    var self = this;
                    return callNative('startNotifications', sUuid, cUuid).then(function() { return self; });
                  },
                  readValue: function() {
                    var self = this;
                    return callNative('readCharacteristic', sUuid, cUuid).then(function(b64) {
                      var dv = base64ToDataView(b64);
                      self.value = dv;
                      return dv;
                    });
                  },
                  writeValueWithResponse: function(buf) {
                    return callNative('writeCharacteristic', sUuid, cUuid, bufToBase64(buf), true);
                  },
                  writeValueWithoutResponse: function(buf) {
                    return callNative('writeCharacteristic', sUuid, cUuid, bufToBase64(buf), false);
                  },
                  writeValue: function(buf) {
                    return callNative('writeCharacteristic', sUuid, cUuid, bufToBase64(buf), true);
                  }
                };
                charRegistry[key] = ch;
                return ch;
              }

              var btPolyfill = {
                getAvailability: function() { return Promise.resolve(true); },
                requestDevice: function(options) {
                  return callNative('requestDevice', JSON.stringify(options || {})).then(function(devInfo) {
                    var dev = {
                      id: devInfo.id,
                      name: devInfo.name,
                      _listeners: {},
                      addEventListener: function(type, fn) {
                        this._listeners[type] = this._listeners[type] || [];
                        if (this._listeners[type].indexOf(fn) === -1) this._listeners[type].push(fn);
                      },
                      removeEventListener: function(type, fn) {
                        var list = this._listeners[type];
                        if (!list) return;
                        var idx = list.indexOf(fn);
                        if (idx !== -1) list.splice(idx, 1);
                      },
                      gatt: null
                    };

                    dev.gatt = {
                      device: dev,
                      connected: false,
                      _services: [],
                      connect: function() {
                        var server = this;
                        return callNative('connect', dev.id).then(function(res) {
                          server.connected = true;
                          server._services = (res && res.services) || [];
                          return server;
                        });
                      },
                      disconnect: function() {
                        this.connected = false;
                        window.AndroidBleBridge.disconnect(dev.id);
                      },
                      getPrimaryService: function(uuid) {
                        var target = uuid.toLowerCase();
                        var found = this._services.find(function(s) { return s.uuid.toLowerCase() === target; });
                        if (!found) return Promise.reject(new Error('Service tidak ditemukan: ' + uuid));
                        return Promise.resolve({
                          uuid: target,
                          device: dev,
                          getCharacteristic: function(charUuid) {
                            var cTarget = charUuid.toLowerCase();
                            var cFound = (found.characteristics || []).find(function(c) { return c.uuid.toLowerCase() === cTarget; });
                            if (!cFound) return Promise.reject(new Error('Characteristic tidak ditemukan: ' + charUuid));
                            return Promise.resolve(makeCharacteristic(target, cTarget));
                          }
                        });
                      }
                    };

                    activeDevices[dev.id] = dev;
                    return dev;
                  });
                }
              };

              Object.defineProperty(navigator, 'bluetooth', {
                value: btPolyfill,
                configurable: true
              });
            })();
        """.trimIndent()
    }
}
