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
import me.aap.utils.log.Log;

/**
 * Экран вкладки «Заправка» — как вкладка «Кинопоиск»: обычная страница под размер экрана, без версии для
 * компьютера; открывается веб-Telegram с приложением заправки ({@link ZapravkaAddon#getLastUrl()}).
 */
@Keep
@SuppressWarnings("unused")
public class ZapravkaFragment extends WebBrowserFragment {

	@Override
	public int getFragmentId() {
		return me.aap.fermata.R.id.zapravka_fragment;
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		WebBrowserAddon addon = getAddon();
		if (addon == null) return;
		Context ctx = view.getContext();
		FermataWebView webView = view.findViewById(R.id.browserWebView);
		ViewGroup fullScreenView = view.findViewById(R.id.browserFullScreenView);
		FermataChromeClient chromeClient = new FermataChromeClient(webView, fullScreenView);
		webView.init(addon, new ZapravkaWebClient(), chromeClient);
		ZapravkaAuto.install(webView);
		String url = addon.getLastUrl();
		Log.i("Zapravka: open ", ZapravkaLinks.WEB_TELEGRAM.equals(url) ? "web telegram (no link)" : "fuel app link");
		webView.loadUrl(url);
		MainActivityDelegate.getActivityDelegate(ctx).onSuccess(this::registerListeners);
	}

	@Nullable
	@Override
	protected WebBrowserAddon getAddon() {
		return AddonManager.get().getAddon(ZapravkaAddon.class);
	}

	@Override
	protected String getSearchUrl() {
		return ZapravkaLinks.WEB_TELEGRAM;
	}
}
