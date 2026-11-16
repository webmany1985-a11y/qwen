package me.aap.fermata.addon.web;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewFeature;

import me.aap.utils.log.Log;

/**
 * Кинопоиск во вкладке: ссылки «открыть в приложении» и в магазин не
 * открываются (во вкладке это белая страница — так было у TikTok), вход в
 * Яндекс (passport.yandex.*) — обычными страницами внутри вкладки.
 */
public class KinopoiskWebClient extends FermataWebClient {

	/** Реклама (сеть Яндекса, AdFox, счётчики) не грузится — 16.11.2026: «во вкладке Кинопоиска выдаёт рекламу». */
	@Nullable
	@Override
	public android.webkit.WebResourceResponse shouldInterceptRequest(@NonNull WebView view,
																																	 @NonNull WebResourceRequest request) {
		Uri u = request.getUrl();
		if (AdHosts.isAdUrl(u.getHost(), u.getPath())) {
			return new android.webkit.WebResourceResponse("text/plain", "utf-8",
					new java.io.ByteArrayInputStream(new byte[0]));
		}
		return super.shouldInterceptRequest(view, request);
	}

	/** Переход внутри страницы (сайт без перезагрузки) — круглая кнопка должна сразу стать «Назад». */
	@Override
	public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
		super.doUpdateVisitedHistory(view, url, isReload);
		me.aap.fermata.ui.activity.MainActivityDelegate.getActivityDelegate(view.getContext()).onSuccess(a ->
				a.fireBroadcastEvent(me.aap.utils.ui.activity.ActivityListener.FRAGMENT_CONTENT_CHANGED));
	}

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
