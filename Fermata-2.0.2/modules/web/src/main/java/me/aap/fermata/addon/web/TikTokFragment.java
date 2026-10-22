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
		webView.init(addon, createWebClient(), chromeClient);
		if (isCarScreen(ctx)) {
			// В машине — версия TikTok для компьютера: под широкий экран, без окна
			// «Открыть TikTok», которое в мобильной версии затемняло экран и
			// останавливало ленту (замечание 17.10.2026)
			android.webkit.WebSettings ws = webView.getSettings();
			String ua = FermataWebView.UserAgent.getUaDesktop(ws, addon);
			ws.setUserAgentString(ua);
			// Страница сама назначит себе ширину под высоту экрана (tiktok_autonext.js,
			// fitCarScreen), браузер её примет и впишет в экран
			ws.setUseWideViewPort(true);
			ws.setLoadWithOverviewMode(true);
			me.aap.utils.log.Log.i(tabName(), ": car screen, desktop version: ", ua);
		}
		onWebViewReady(webView);
		webView.loadUrl(startUrl(addon));
		addToggle(view);
		MainActivityDelegate.getActivityDelegate(ctx).onSuccess(this::registerListeners);
	}

	/**
	 * Во вкладке TikTok в машине панели приложения (верхняя строка и вкладки
	 * снизу) спрятаны — видео на весь экран (решение пользователя 21.10.2026).
	 * Полупрозрачный значок в углу возвращает панели и прячет их снова.
	 */
	private boolean carFullScreen;
	@Nullable
	private android.widget.ImageView toggle;

	private void setCarFullScreen(boolean on) {
		Context ctx = getContext();
		if ((ctx == null) || (on == carFullScreen) || !isCarScreen(ctx)) return;
		try {
			MainActivityDelegate a = MainActivityDelegate.get(ctx);
			carFullScreen = on;
			a.setBarsHidden(on);
			if (toggle != null) toggle.setAlpha(on ? 0.35f : 0.8f);
			me.aap.utils.log.Log.i(tabName(), ": car full screen ", on);
		} catch (RuntimeException ex) {
			me.aap.utils.log.Log.e(ex, tabName(), ": car full screen failed");
		}
	}

	private void addToggle(View root) {
		Context ctx = root.getContext();
		if (!(root instanceof ViewGroup) || !isCarScreen(ctx)) return;
		float dp = ctx.getResources().getDisplayMetrics().density;
		android.widget.ImageView b = new android.widget.ImageView(ctx);
		b.setImageResource(me.aap.fermata.R.drawable.expand);
		b.setColorFilter(0xFFFFB23A);
		android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
		bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
		bg.setColor(0x99000000);
		b.setBackground(bg);
		int pad = (int) (10 * dp);
		b.setPadding(pad, pad, pad, pad);
		b.setContentDescription("Панели приложения");
		int size = (int) (48 * dp);
		android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(size, size);
		lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
		lp.leftMargin = lp.topMargin = (int) (6 * dp);
		b.setOnClickListener(v -> setCarFullScreen(!carFullScreen));
		((ViewGroup) root).addView(b, lp);
		toggle = b;
	}

	/** Скрипт ленты не листает, пока вкладка не на экране. */
	private void setScriptPaused(boolean paused) {
		FermataWebView v = getWebView();
		if (v != null) v.evaluateJavascript("window.__jarvisTtPaused=" + paused + ";", null);
	}

	@Override
	public void onResume() {
		super.onResume();
		if (!isHidden()) {
			setCarFullScreen(true);
			setScriptPaused(false);
		}
	}

	@Override
	public void onPause() {
		setCarFullScreen(false);
		setScriptPaused(true);
		super.onPause();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		setCarFullScreen(!hidden);
		setScriptPaused(hidden);
	}

	@Override
	public void onDestroyView() {
		setCarFullScreen(false);
		toggle = null;
		super.onDestroyView();
	}

	/** С какой страницы открыть вкладку. */
	protected String startUrl(WebBrowserAddon addon) {
		return addon.getLastUrl();
	}

	/** Браузер вкладки настроен, страница ещё не загружена. */
	protected void onWebViewReady(FermataWebView webView) {
	}

	/** Веб-клиент вкладки: у TikTok — со скриптом ленты. */
	protected FermataWebClient createWebClient() {
		return new TikTokWebClient();
	}

	/** Имя вкладки для журнала. */
	@NonNull
	protected String tabName() {
		return "TikTok";
	}

	static boolean isCarScreen(Context ctx) {
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
