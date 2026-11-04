package me.aap.fermata.addon.web;

import static me.aap.fermata.util.Utils.dynCtx;

import android.content.Context;
import android.util.Base64;
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
 * Вкладка «Заправка»: сценарий assets/zapravka_auto.js во всех кадрах страницы (приложение заправки — кадр чужого
 * сайта внутри веб-Telegram) — путь до выбора колонки и своя оболочка с фото АЗС (assets/zapravka_fon.jpg). Записи сценария —
 * в журнал с приставкой {@code [JARVIS-ZP]} ({@link FermataChromeClient#onConsoleMessage}).
 */
final class ZapravkaAuto {
	@Nullable
	private static String script;

	private ZapravkaAuto() {
	}

	static void install(WebView webView) {
		String js = script(webView.getContext());
		if ((js == null) || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
			Log.i("Zapravka: auto click script not available");
			return;
		}
		WebViewCompat.addDocumentStartJavaScript(webView, js, Collections.singleton("*"));
	}

	private static byte[] read(Context ctx, String asset) throws java.io.IOException {
		try (InputStream in = dynCtx(ctx).getAssets().open(asset)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
			return out.toByteArray();
		}
	}

	@Nullable
	private static String script(Context ctx) {
		if (script != null) return script;
		try {
			String js = new String(read(ctx, "zapravka_auto.js"), StandardCharsets.UTF_8);
			// Фон оболочки — фото АЗС (assets/zapravka_fon.jpg), строкой data: перед сценарием
			String bg = "data:image/jpeg;base64," + Base64.encodeToString(read(ctx, "zapravka_fon.jpg"), Base64.NO_WRAP);
			// Логотип А-100 (от пользователя 04.11.2026): целиком — на заставке, клевер — в шапке оболочки
			String logo = "data:image/png;base64," + Base64.encodeToString(read(ctx, "zapravka_logo.png"), Base64.NO_WRAP);
			String klever = "data:image/png;base64," + Base64.encodeToString(read(ctx, "zapravka_klever.png"), Base64.NO_WRAP);
			return script = "window.__JZP_BG='" + bg + "';window.__JZP_LOGO='" + logo + "';window.__JZP_KLEVER='" + klever + "';\n" + js;
		} catch (Exception ex) {
			Log.e(ex, "failed to load zapravka_auto.js");
			return null;
		}
	}
}
