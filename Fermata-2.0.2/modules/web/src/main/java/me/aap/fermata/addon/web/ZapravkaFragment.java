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
import me.aap.utils.ui.view.ToolBarView;

/**
 * Экран вкладки «Заправка» — как вкладка «Кинопоиск»: обычная страница под размер экрана, без версии для
 * компьютера; открывается своя вкладка А-100 ({@link ZapravkaAddon#getFastUrl()}), с неё — веб-Telegram с приложением заправки.
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
		// Своя вкладка А-100 (05.11.2026) — сразу; прежний путь через веб-Telegram — с неё кнопкой «Telegram»
		String url = ((ZapravkaAddon) addon).getFastUrl();
		Log.i("Zapravka: open own A-100 tab");
		webView.loadUrl(url);
		MainActivityDelegate.getActivityDelegate(ctx).onSuccess(this::registerListeners);
	}

	@Nullable
	@Override
	protected WebBrowserAddon getAddon() {
		return AddonManager.get().getAddon(ZapravkaAddon.class);
	}

	/**
	 * Без верхней полосы с адресом web.telegram.org, крестиком и закладкой (пожелание 04.11.2026: «не нравится верхняя
	 * строка с адресом, убери её»); нижние вкладки остаются. Так же сделано у вкладки YouTube.
	 */
	@Override
	public ToolBarView.Mediator getToolBarMediator() {
		return ToolBarView.Mediator.Invisible.instance;
	}

	@Override
	protected String getSearchUrl() {
		return ZapravkaLinks.WEB_TELEGRAM;
	}
}
