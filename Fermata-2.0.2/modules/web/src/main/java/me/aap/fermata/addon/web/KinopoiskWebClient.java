package me.aap.fermata.addon.web;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewFeature;

import me.aap.utils.log.Log;

/**
 * Кинопоиск во вкладке: ссылки «открыть в приложении» и в магазин не
 * открываются (во вкладке это белая страница — так было у TikTok), вход в
 * Яндекс (passport.yandex.*) — обычными страницами внутри вкладки.
 */
public class KinopoiskWebClient extends FermataWebClient {

	@Override
	public void onPageFinished(WebView view, String url) {
		super.onPageFinished(view, url);
		Log.i("Kinopoisk: page finished ", url, ", view ", view.getWidth(), "x", view.getHeight());
	}

	@Override
	public void onReceivedError(@NonNull WebView view, @NonNull WebResourceRequest request,
															@NonNull WebResourceErrorCompat error) {
		super.onReceivedError(view, request, error);
		if (!request.isForMainFrame()) return;
		String d = WebViewFeature.isFeatureSupported(
				WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION) ? String.valueOf(error.getDescription()) : "";
		Log.i("Kinopoisk: main frame error ", d, " for ", request.getUrl());
		Toast.makeText(view.getContext(), "Кинопоиск не загрузился " + d, Toast.LENGTH_LONG).show();
	}

	@Override
	public boolean shouldOverrideUrlLoading(@NonNull WebView view,
																					@NonNull WebResourceRequest request) {
		Uri u = request.getUrl();
		String scheme = u.getScheme();
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			Log.i("Kinopoisk: app link blocked ", u);
			return true;
		}
		if (TikTokWebClient.isAppLink(u)) {
			Log.i("Kinopoisk: store link blocked ", u);
			return true;
		}
		return super.shouldOverrideUrlLoading(view, request);
	}

	static boolean isKinopoisk(Uri uri) {
		String host = uri.getHost();
		return (host != null) && (host.equals("kinopoisk.ru") || host.endsWith(".kinopoisk.ru"));
	}
}
