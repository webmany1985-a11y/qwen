package me.aap.fermata.addon.web;

import static me.aap.fermata.util.Utils.dynCtx;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import me.aap.utils.log.Log;

/**
 * После загрузки страницы TikTok вставляет скрипт автоперелистывания
 * (assets/tiktok_autonext.js). Повторная вставка безвредна: скрипт
 * запускается один раз на страницу.
 */
public class TikTokWebClient extends FermataWebClient {
	@Nullable
	private static String script;

	private long recoveredAt;

	/**
	 * Ссылка «открыть в приложении» всё-таки загрузилась (так бывает при
	 * переадресации сервером: журнал 17.10.2026, 07:24:55) — вернуться в ленту.
	 */
	private boolean recoverFromAppLink(WebView view, String url) {
		if ((url == null) || !isAppLink(Uri.parse(url))) return false;
		view.stopLoading();
		long now = System.currentTimeMillis();
		if (now - recoveredAt < 5000) return true;
		recoveredAt = now;
		Log.i("TikTok: left app link, back to the feed");
		view.loadUrl(TikTokAddon.DEFAULT_URL);
		return true;
	}

	@Override
	public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
		if (recoverFromAppLink(view, url)) return;
		super.onPageStarted(view, url, favicon);
	}

	@Override
	public void onPageFinished(WebView view, String url) {
		if (recoverFromAppLink(view, url)) return;
		super.onPageFinished(view, url);
		Log.i("TikTok: page finished ", url, ", view ", view.getWidth(), "x", view.getHeight());
		if (!isTikTok(Uri.parse(url))) return;
		String js = script(view.getContext());
		if (js != null) view.evaluateJavascript(js, null);
	}

	/** Страница не загрузилась — сказать об этом, а не оставлять белый экран. */
	@Override
	public void onReceivedError(@NonNull WebView view, @NonNull WebResourceRequest request,
															@NonNull WebResourceErrorCompat error) {
		super.onReceivedError(view, request, error);
		if (!request.isForMainFrame()) return;
		String d = WebViewFeature.isFeatureSupported(
				WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION) ? String.valueOf(error.getDescription()) : "";
		Log.i("TikTok: main frame error ", d, " for ", request.getUrl());
		Toast.makeText(view.getContext(), "TikTok не загрузился " + d, Toast.LENGTH_LONG).show();
	}

	@Override
	public boolean shouldOverrideUrlLoading(@NonNull WebView view,
																					@NonNull WebResourceRequest request) {
		Uri u = request.getUrl();
		String scheme = u.getScheme();
		// «Открыть приложение» ведёт на snssdk…:// и intent:// — во вкладке их не открыть
		if (!"http".equals(scheme) && !"https".equals(scheme)) return true;
		// …и на https-ссылки «открыть в приложении» (snssdk1180.onelink.me): во вкладке
		// это белая страница, которая к тому же запоминалась как последняя (16.10.2026)
		if (isAppLink(u)) {
			Log.i("TikTok: app link blocked ", u);
			return true;
		}
		return super.shouldOverrideUrlLoading(view, request);
	}

	/** Ссылка, ведущая не на страницу, а в приложение TikTok или в магазин. */
	static boolean isAppLink(Uri uri) {
		String host = uri.getHost();
		if (host == null) return false;
		return host.endsWith("onelink.me") || host.endsWith("app.link") ||
				host.endsWith("appsflyer.com") || host.equals("play.google.com") ||
				host.equals("apps.apple.com") || host.startsWith("snssdk");
	}

	static boolean isTikTok(Uri uri) {
		String host = uri.getHost();
		return (host != null) && (host.equals("tiktok.com") || host.endsWith(".tiktok.com"));
	}

	@Nullable
	private static String script(Context ctx) {
		if (script != null) return script;
		try (InputStream in = dynCtx(ctx).getAssets().open("tiktok_autonext.js")) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
			return script = out.toString(StandardCharsets.UTF_8.name());
		} catch (Exception ex) {
			Log.e(ex, "TikTok: failed to load tiktok_autonext.js");
			return null;
		}
	}
}
