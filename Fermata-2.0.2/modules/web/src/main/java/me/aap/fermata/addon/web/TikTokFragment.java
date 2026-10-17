package me.aap.fermata.addon.web;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.ui.activity.MainActivityDelegate;

/**
 * Экран вкладки TikTok: тот же встроенный браузер, что у «Браузера», но со
 * своим веб-клиентом ({@link TikTokWebClient}) и поиском по TikTok.
 */
@Keep
@SuppressWarnings("unused")
public class TikTokFragment extends WebBrowserFragment {

	@Override
	public int getFragmentId() {
		return me.aap.fermata.R.id.tiktok_fragment;
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		WebBrowserAddon addon = getAddon();
		if (addon == null) return;
		Context ctx = view.getContext();
		FermataWebView webView = view.findViewById(R.id.browserWebView);
		ViewGroup fullScreenView = view.findViewById(R.id.browserFullScreenView);
		FermataChromeClient chromeClient = new FermataChromeClient(webView, fullScreenView);
		webView.init(addon, new TikTokWebClient(), chromeClient);
		if (isCarScreen(ctx)) {
			// В машине — версия TikTok для компьютера: под широкий экран, без окна
			// «Открыть TikTok», которое в мобильной версии затемняло экран и
			// останавливало ленту (замечание 17.10.2026)
			android.webkit.WebSettings ws = webView.getSettings();
			String ua = FermataWebView.UserAgent.getUaDesktop(ws, addon);
			ws.setUserAgentString(ua);
			me.aap.utils.log.Log.i("TikTok: car screen, desktop version: ", ua);
		}
		webView.loadUrl(addon.getLastUrl());
		MainActivityDelegate.getActivityDelegate(ctx).onSuccess(this::registerListeners);
	}

	private static boolean isCarScreen(Context ctx) {
		try {
			return MainActivityDelegate.get(ctx).isCarActivityNotMirror();
		} catch (RuntimeException ex) {
			return false;
		}
	}

	@Nullable
	@Override
	protected WebBrowserAddon getAddon() {
		return AddonManager.get().getAddon(TikTokAddon.class);
	}

	@Override
	protected String getSearchUrl() {
		return "https://www.tiktok.com/search?q=";
	}
}
