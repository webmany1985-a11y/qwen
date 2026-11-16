package me.aap.fermata.addon.web;

import static me.aap.fermata.util.Utils.dynCtx;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;

/**
 * Вкладка «Видео» — общий встроенный проигрыватель (пожелание 22.10.2026):
 * открывается со страницы «Мои сайты» (адреса вписывает пользователь),
 * видео при запуске само разворачивается во весь экран ({@link AutoFullScreen}).
 * Как вкладки YouTube и «Кинопоиск»: страница под размер экрана, без версии
 * для компьютера и без скрытия панелей — от них изображение меняло размер.
 */
@Keep
@SuppressWarnings("unused")
public class VideoSitesFragment extends WebBrowserFragment {

	@Override
	public int getFragmentId() {
		return me.aap.fermata.R.id.video_sites_fragment;
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		VideoSitesAddon addon = AddonManager.get().getAddon(VideoSitesAddon.class);
		if (addon == null) return;
		Context ctx = view.getContext();
		FermataWebView webView = view.findViewById(R.id.browserWebView);
		ViewGroup fullScreenView = view.findViewById(R.id.browserFullScreenView);
		FermataChromeClient chromeClient = me.aap.fermata.BuildConfig.ZHENA ?
				new PopupFilter(webView, fullScreenView) : new FermataChromeClient(webView, fullScreenView);
		webView.init(addon, new VideoSitesWebClient(addon), chromeClient);
		if (me.aap.fermata.BuildConfig.ZHENA) {
			// Н6: всплывающие окна — через PopupFilter (реклама не открывается вместо фильма)
			webView.getSettings().setSupportMultipleWindows(true);
			webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
		}
		AutoFullScreen.install(webView, "Video");
		StablePage.install(webView, "Video");
		webView.loadUrl(resumeUrl(webView, VideoSitesPage.HOME));
		MainActivityDelegate.getActivityDelegate(ctx).onSuccess(this::registerListeners);
	}

	@Nullable
	@Override
	protected WebBrowserAddon getAddon() {
		return AddonManager.get().getAddon(VideoSitesAddon.class);
	}

	/**
	 * Без верхней строки с адресом (пожелание 14.11.2026), как у Кинопоиска и TikTok; назад по страницам — кнопкой
	 * «Назад», к списку — «Мои сайты» в меню вкладки.
	 */
	@Override
	public me.aap.utils.ui.view.ToolBarView.Mediator getToolBarMediator() {
		return me.aap.utils.ui.view.ToolBarView.Mediator.Invisible.instance;
	}

	@Override
	public void contributeToNavBarMenu(OverlayMenu.Builder b) {
		Context ctx = dynCtx(requireContext());
		Resources res = ctx.getResources();
		b.addItem(me.aap.fermata.R.id.video_sites_home,
				ResourcesCompat.getDrawable(res, me.aap.fermata.R.drawable.bookmark_filled, ctx.getTheme()),
				"Мои сайты").setHandler(this::home);
		super.contributeToNavBarMenu(b);
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (hidden) pageFullScreenOff();
	}

	@Override
	public void onPause() {
		pageFullScreenOff();
		super.onPause();
	}

	/** Ушли с вкладки — панели приложения обратно, если их прятал фильм (Н6). */
	private void pageFullScreenOff() {
		View v = getView();
		FermataWebView w = (v == null) ? null : v.findViewById(R.id.browserWebView);
		FermataChromeClient c = (w == null) ? null : w.getWebChromeClient();
		if ((c != null) && c.isPageFullScreen()) c.setPageFullScreen(false);
	}

	/**
	 * Сборка жены (Н6, 16.11.2026, «куча рекламы»): окно, которое страница открывает сама, — открыть во вкладке, только
	 * если оно того же сайта и не рекламное; иначе — это всплывающая реклама, она не открывается вместо фильма.
	 */
	private static final class PopupFilter extends FermataChromeClient {
		PopupFilter(FermataWebView web, ViewGroup fullScreenView) {
			super(web, fullScreenView);
		}

		@Override
		public boolean onCreateWindow(android.webkit.WebView view, boolean isDialog, boolean userGesture,
																	android.os.Message resultMsg) {
			String from = view.getUrl();
			String fromHost = (from == null) ? null : android.net.Uri.parse(from).getHost();
			android.webkit.WebView tmp = new android.webkit.WebView(view.getContext());
			boolean[] gone = {false};
			Runnable drop = () -> {
				if (gone[0]) return;
				gone[0] = true;
				tmp.destroy();
			};
			tmp.setWebViewClient(new android.webkit.WebViewClient() {
				@Override
				public boolean shouldOverrideUrlLoading(android.webkit.WebView v, android.webkit.WebResourceRequest r) {
					String host = r.getUrl().getHost();
					if (userGesture && AdHosts.sameSite(host, fromHost) && !AdHosts.isAd(host)) {
						view.loadUrl(r.getUrl().toString());
					} else {
						me.aap.utils.log.Log.i("Video: popup blocked ", host);
					}
					v.post(drop);
					return true;
				}
			});
			// Окно без адреса (пишет само в себя) — убрать через 10 с
			tmp.postDelayed(drop, 10_000L);
			android.webkit.WebView.WebViewTransport t = (android.webkit.WebView.WebViewTransport) resultMsg.obj;
			t.setWebView(tmp);
			resultMsg.sendToTarget();
			return true;
		}
	}

	private boolean home(OverlayMenuItem item) {
		loadUrl(VideoSitesPage.HOME);
		return true;
	}
}
