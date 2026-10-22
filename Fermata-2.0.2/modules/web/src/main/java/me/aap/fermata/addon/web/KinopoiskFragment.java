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
 * Экран вкладки «Кинопоиск» — как вкладка YouTube (пожелание 22.10.2026:
 * «режимы взаимодействия… смены размеров изображений постоянно подтупливали»):
 * обычная страница под размер экрана, без версии для компьютера и без
 * скрытия панелей, как было у TikTok; фильм при запуске сразу во весь экран.
 */
@Keep
@SuppressWarnings("unused")
public class KinopoiskFragment extends WebBrowserFragment {

	@Override
	public int getFragmentId() {
		return me.aap.fermata.R.id.kinopoisk_fragment;
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		WebBrowserAddon addon = getAddon();
		if (addon == null) return;
		Context ctx = view.getContext();
		FermataWebView webView = view.findViewById(R.id.browserWebView);
		ViewGroup fullScreenView = view.findViewById(R.id.browserFullScreenView);
		FermataChromeClient chromeClient = new FermataChromeClient(webView, fullScreenView);
		webView.init(addon, new KinopoiskWebClient(), chromeClient);
		AutoFullScreen.install(webView, "Kinopoisk");
		webView.loadUrl(addon.getLastUrl());
		MainActivityDelegate.getActivityDelegate(ctx).onSuccess(this::registerListeners);
	}

	@Nullable
	@Override
	protected WebBrowserAddon getAddon() {
		return AddonManager.get().getAddon(KinopoiskAddon.class);
	}

	@Override
	protected String getSearchUrl() {
		return "https://hd.kinopoisk.ru/search?text=";
	}
}
