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

	@Override
	public void onPageFinished(WebView view, String url) {
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
		String scheme = request.getUrl().getScheme();
		// «Открыть приложение» ведёт на snssdk…:// и intent:// — во вкладке их не открыть
		if (!"http".equals(scheme) && !"https".equals(scheme)) return true;
		return super.shouldOverrideUrlLoading(view, request);
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
