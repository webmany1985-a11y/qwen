package me.aap.fermata.addon.web;

import static me.aap.fermata.util.Utils.dynCtx;

import android.content.Context;
import android.webkit.WebView;

import androidx.annotation.Nullable;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import me.aap.utils.log.Log;

/**
 * Видео, запущенное человеком, — сразу во весь экран (assets/video_fullscreen.js,
 * во всех кадрах страницы). Вкладки «Видео» и «Кинопоиск».
 */
final class AutoFullScreen {
	@Nullable
	private static String script;

	private AutoFullScreen() {
	}

	static void install(WebView webView, String tab) {
		String js = script(webView.getContext());
		if ((js == null) || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
			Log.i(tab, ": auto full screen script not available");
			return;
		}
		WebViewCompat.addDocumentStartJavaScript(webView, js, Collections.singleton("*"));
	}

	@Nullable
	private static String script(Context ctx) {
		if (script != null) return script;
		try (InputStream in = dynCtx(ctx).getAssets().open("video_fullscreen.js")) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
			return script = out.toString(StandardCharsets.UTF_8.name());
		} catch (Exception ex) {
			Log.e(ex, "failed to load video_fullscreen.js");
			return null;
		}
	}
}
