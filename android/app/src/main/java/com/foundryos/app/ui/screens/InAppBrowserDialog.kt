package com.foundryos.app.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foundryos.app.ui.theme.FoundryColors

/**
 * In-App Browser integrated inside CEO:
 * - Handles Google OAuth sign-in loopback callbacks (127.0.0.1) for Antigravity CLI without leaving the app
 * - Works as a full clean WebView browser with Chrome UA, cookies, back/forward/refresh, URL bar & quick bookmarks
 * - Includes a responsive 1-tap "Chrome" Custom Tab fallback if Google asks for hardware passkey
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppBrowserDialog(
    initialUrl: String,
    isOAuthSession: Boolean = false,
    onOAuthLoopbackHit: () -> Unit = {},
    onUrlVisited: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val chromeUa = remember {
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl.ifBlank { "https://www.google.com" }) }
    var addressInput by remember(initialUrl) { mutableStateOf(initialUrl.ifBlank { "https://www.google.com" }) }
    var pageTitle by remember { mutableStateOf(if (isOAuthSession) "Google Sign-In" else "CEO Browser") }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun openUrlOrSearch(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val normalized = when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            "." in trimmed && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        }
        currentUrl = normalized
        addressInput = normalized
        webViewRef?.loadUrl(normalized)
    }

    fun launchInChrome(url: String) {
        runCatching {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
        }.onFailure {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank() && webViewRef != null && currentUrl != initialUrl) {
            currentUrl = initialUrl
            addressInput = initialUrl
            webViewRef?.loadUrl(initialUrl)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { webViewRef?.stopLoading() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.93f)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White)
                .border(1.dp, FoundryColors.Border, RoundedCornerShape(22.dp)),
        ) {
            // === TOP HEADER BAR ===
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FoundryColors.SidebarBg)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = { webViewRef?.takeIf { it.canGoBack() }?.goBack() },
                    enabled = canGoBack,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = if (canGoBack) FoundryColors.InkPrimary else FoundryColors.InkMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = { webViewRef?.takeIf { it.canGoForward() }?.goForward() },
                    enabled = canGoForward,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = "Forward",
                        tint = if (canGoForward) FoundryColors.InkPrimary else FoundryColors.InkMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = { webViewRef?.reload() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "Reload",
                        tint = FoundryColors.InkPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }

                // URL Bar
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White)
                        .border(1.dp, FoundryColors.Border, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (currentUrl.startsWith("https://")) Icons.Rounded.Lock else Icons.Rounded.Public,
                        contentDescription = null,
                        tint = if (currentUrl.startsWith("https://")) FoundryColors.GreenInk else FoundryColors.InkSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        singleLine = true,
                        textStyle = TextStyle(color = FoundryColors.InkPrimary, fontSize = 12.5.sp),
                        cursorBrush = SolidColor(FoundryColors.InkPrimary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { openUrlOrSearch(addressInput) }),
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Responsive External Chrome fallback pill
                Row(
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(FoundryColors.SoftSurface)
                        .clickable { launchInChrome(currentUrl) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.OpenInBrowser, contentDescription = "Open in Chrome", tint = FoundryColors.InkPrimary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Chrome",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FoundryColors.InkPrimary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close Browser", tint = FoundryColors.InkPrimary, modifier = Modifier.size(19.dp))
                }
            }

            // === SUB-BAR: Page Title / OAuth Context + Quick Bookmarks ===
            if (isOAuthSession || currentUrl.contains("accounts.google.com") || currentUrl.contains("127.0.0.1")) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FoundryColors.BlueBg)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (currentUrl.startsWith("http://127.0.0.1") || currentUrl.startsWith("http://localhost"))
                            "Completing Antigravity sign-in..."
                        else
                            "$pageTitle · Google Sign-In for Antigravity CLI",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = FoundryColors.BlueInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Use Chrome",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = FoundryColors.BlueInk,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White)
                            .clickable { launchInChrome(currentUrl) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFAFAFA))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        "Google" to "https://www.google.com",
                        "GitHub" to "https://github.com",
                        "OpenRouter" to "https://openrouter.ai",
                        "AI Studio" to "https://aistudio.google.com",
                        "Docs" to "https://developer.android.com",
                    ).forEach { (label, url) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(FoundryColors.SoftSurface)
                                .clickable { openUrlOrSearch(url) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = FoundryColors.InkPrimary, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = FoundryColors.InkPrimary,
                    trackColor = FoundryColors.SoftSurface,
                )
            }

            // === WEBVIEW CONTENT ===
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                loadsImagesAutomatically = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                builtInZoomControls = true
                                displayZoomControls = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                userAgentString = chromeUa
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    if (!title.isNullOrBlank()) pageTitle = title
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val url = request?.url?.toString().orEmpty()
                                    if (url.startsWith("http://") || url.startsWith("https://")) {
                                        return false
                                    }
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        ctx.startActivity(intent)
                                    }
                                    return true
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                        addressInput = url
                                    }
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                        addressInput = url
                                        onUrlVisited(url)
                                        if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
                                            onOAuthLoopbackHit()
                                        }
                                    }
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                    CookieManager.getInstance().flush()
                                }
                            }
                            webViewRef = this
                            loadUrl(currentUrl)
                        }
                    },
                )
            }
        }
    }
}
