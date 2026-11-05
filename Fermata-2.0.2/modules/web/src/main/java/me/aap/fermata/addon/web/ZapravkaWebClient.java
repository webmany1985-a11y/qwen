package me.aap.fermata.addon.web;

import static me.aap.fermata.util.Utils.dynCtx;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewFeature;

import me.aap.utils.log.Log;

/**
 * Веб-Telegram во вкладке «Заправка»: ссылки «открыть в приложении» (tg://, intent://, магазин) не уходят из
 * вкладки — в машине это белая страница или выход из Android Auto (как у Кинопоиска и TikTok). Страницы
 * Telegram и самого приложения заправки (свой сайт, оплата) — обычными страницами во вкладке.
 * Адреса страниц в журнал не пишутся: в них бывают данные входа Telegram.
 */
public class ZapravkaWebClient extends FermataWebClient {
	/** Ссылка на бота из настроек — для возврата в свою вкладку после страницы банка. */
	@androidx.annotation.Nullable
	private final String link;

	public ZapravkaWebClient() {
		this(null);
	}

	public ZapravkaWebClient(@androidx.annotation.Nullable String link) {
		this.link = link;
	}

	/** Своя вкладка А-100: её 4 файла из assets по адресу https://appassets.androidplatform.net/zp/… */
	@Override
	public WebResourceResponse shouldInterceptRequest(@NonNull WebView view, @NonNull WebResourceRequest request) {
		Uri u = request.getUrl();
		String name = ZapravkaLinks.fastAsset(u.getHost(), u.getPath());
		if (name == null) {
			if (ZapravkaLinks.FAST_HOST.equals(u.getHost())) {
				return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null);
			}
			return super.shouldInterceptRequest(view, request);
		}
		try {
			return new WebResourceResponse(ZapravkaLinks.mimeOf(name), name.endsWith(".html") ? "UTF-8" : null,
					dynCtx(view.getContext()).getAssets().open(name));
		} catch (Exception ex) {
			Log.e(ex, "Zapravka: asset not available ", name);
			return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null);
		}
	}

	@Override
	public void onPageFinished(WebView view, String url) {
		super.onPageFinished(view, url);
		Uri u = Uri.parse(url);
		Log.i("Zapravka: page finished, host ", u.getHost(), ", view ", view.getWidth(), "x", view.getHeight());
	}

	@Override
	public void onReceivedError(@NonNull WebView view, @NonNull WebResourceRequest request,
															@NonNull WebResourceErrorCompat error) {
		super.onReceivedError(view, request, error);
		if (!request.isForMainFrame()) return;
		String d = WebViewFeature.isFeatureSupported(
				WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION) ? String.valueOf(error.getDescription()) : "";
		Log.i("Zapravka: main frame error ", d, " for host ", request.getUrl().getHost());
		Toast.makeText(view.getContext(), "Telegram не загрузился " + d, Toast.LENGTH_LONG).show();
	}

	@Override
	public boolean shouldOverrideUrlLoading(@NonNull WebView view,
																					@NonNull WebResourceRequest request) {
		Uri u = request.getUrl();
		// Оплата новой картой: банк закончил — обратно в свою вкладку с результатом (без адреса и данных заказа в журнале)
		String paid = request.isForMainFrame() ? ZapravkaLinks.payResult(u.getHost(), u.getPath(), u.getQuery()) : null;
		if (paid != null) {
			Log.i("Zapravka: back from bank, ", paid);
			view.loadUrl(ZapravkaLinks.fastUrlPaid(link, paid));
			return true;
		}
		String scheme = u.getScheme();
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			Log.i("Zapravka: app link blocked, scheme ", scheme);
			return true;
		}
		if (TikTokWebClient.isAppLink(u)) {
			Log.i("Zapravka: store link blocked ", u.getHost());
			return true;
		}
		// Ссылка t.me в главном окне — открыть её внутри веб-Telegram, а не страницей «Открыть в Telegram»
		if (!"t.me".equalsIgnoreCase(u.getHost())) {
			return super.shouldOverrideUrlLoading(view, request);
		}
		view.loadUrl(ZapravkaLinks.startUrl(u.toString()));
		return true;
	}
}
