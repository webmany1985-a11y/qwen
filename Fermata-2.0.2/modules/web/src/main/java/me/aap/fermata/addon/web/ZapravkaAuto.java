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
 * Вкладка «Заправка»: само нажать кнопку бота и пункт «Заправка» в приложении (assets/zapravka_auto.js, во всех
 * кадрах страницы — приложение заправки открыто кадром чужого сайта внутри веб-Telegram). Записи сценария —
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

	@Nullable
	private static String script(Context ctx) {
		if (script != null) return script;
		try (InputStream in = dynCtx(ctx).getAssets().open("zapravka_auto.js")) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
			return script = out.toString(StandardCharsets.UTF_8.name());
		} catch (Exception ex) {
			Log.e(ex, "failed to load zapravka_auto.js");
			return null;
		}
	}
}
