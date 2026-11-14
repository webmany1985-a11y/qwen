package me.aap.fermata.addon.web;

import static me.aap.fermata.util.Utils.dynCtx;

import android.content.Context;
import android.webkit.WebView;

import androidx.annotation.Nullable;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

import me.aap.utils.app.App;
import me.aap.utils.log.Log;

/**
 * Скрипт assets/tab_media.js во всех кадрах страницы каждой веб-вкладки: пауза при уходе с вкладки,
 * сообщения «играет / встало», продолжение фильма с секунды. См. {@link me.aap.fermata.ui.activity.TabMedia}.
 */
final class TabMediaScript {
	/** Пауза: через скрипт во всех кадрах, а если его нет (старый WebView) — в самой странице. */
	static final String PAUSE_JS = "(function(){if(window.__jarvisTabCmd){window.__jarvisTabCmd('pause');return;}" +
			"var l=document.querySelectorAll('video,audio');for(var i=0;i<l.length;i++){try{l[i].pause();}catch(x){}}})();";
	static final String RESUME_JS = "if(window.__jarvisTabCmd)window.__jarvisTabCmd('resume');";
	@Nullable
	private static String script;

	private TabMediaScript() {
	}

	static void install(WebView webView) {
		String js = script(webView.getContext());
		if ((js == null) || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
			Log.i("tab media script not available");
			return;
		}
		WebViewCompat.addDocumentStartJavaScript(webView, js, Collections.singleton("*"));
	}

	/** Продолжить фильм с секунды {@code sec} — на 3 минуты, пока страница и плеер грузятся. */
	static void resumeAt(WebView webView, double sec) {
		if (!(sec > 0) || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
		ScriptHandler h = WebViewCompat.addDocumentStartJavaScript(webView,
				String.format(Locale.US, "window.__jarvisResumeAt=%.1f;", sec), Collections.singleton("*"));
		App.get().getHandler().postDelayed(h::remove, 180_000L);
	}

	@Nullable
	private static String script(Context ctx) {
		if (script != null) return script;
		try (InputStream in = dynCtx(ctx).getAssets().open("tab_media.js")) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
			return script = out.toString(StandardCharsets.UTF_8.name());
		} catch (Exception ex) {
			Log.e(ex, "failed to load tab_media.js");
			return null;
		}
	}
}
