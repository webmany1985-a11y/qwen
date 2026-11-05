package me.aap.fermata.addon.web;

import static me.aap.fermata.util.Utils.dynCtx;

import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import me.aap.utils.log.Log;

/**
 * Вкладка «Кинопоиск»: сценарий assets/kinopoisk_noapp.js — сам выбирает «остаться в браузере» в окне сайта
 * «Открыть в мобильном приложении?» (пожелание 05.11.2026). Только на сайтах Кинопоиска.
 */
final class KinopoiskNoApp {
	private KinopoiskNoApp() {
	}

	static void install(WebView webView) {
		if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
		try (InputStream in = dynCtx(webView.getContext()).getAssets().open("kinopoisk_noapp.js")) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] b = new byte[8192];
			for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
			Set<String> origins = new HashSet<>();
			origins.add("https://*.kinopoisk.ru");
			origins.add("https://kinopoisk.ru");
			WebViewCompat.addDocumentStartJavaScript(webView, out.toString(StandardCharsets.UTF_8.name()), origins);
		} catch (Exception ex) {
			Log.e(ex, "Kinopoisk: no-app script not installed");
		}
	}
}
