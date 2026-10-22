package me.aap.fermata.addon.web;

import static me.aap.fermata.util.Utils.dynCtx;

import android.content.Context;
import android.content.res.Resources;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;


import me.aap.fermata.addon.AddonManager;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;

/**
 * Вкладка «Видео» — общий встроенный проигрыватель (пожелание 22.10.2026):
 * открывается со страницы «Мои сайты» (адреса вписывает пользователь),
 * видео при запуске само разворачивается во весь экран
 * (assets/video_fullscreen.js — во всех кадрах страницы, и во встроенных
 * плеерах тоже). В машине — как TikTok: версия для компьютера, на весь экран.
 */
@Keep
@SuppressWarnings("unused")
public class VideoSitesFragment extends TikTokFragment {

	@Override
	public int getFragmentId() {
		return me.aap.fermata.R.id.video_sites_fragment;
	}

	@Override
	protected FermataWebClient createWebClient() {
		return new VideoSitesWebClient(AddonManager.get().getAddon(VideoSitesAddon.class));
	}

	@Override
	protected String startUrl(WebBrowserAddon addon) {
		return VideoSitesPage.HOME;
	}

	@Override
	protected void onWebViewReady(FermataWebView webView) {
		AutoFullScreen.install(webView, "Video");
	}

	@NonNull
	@Override
	protected String tabName() {
		return "Video";
	}

	@Nullable
	@Override
	protected WebBrowserAddon getAddon() {
		return AddonManager.get().getAddon(VideoSitesAddon.class);
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

	private boolean home(OverlayMenuItem item) {
		loadUrl(VideoSitesPage.HOME);
		return true;
	}
}
