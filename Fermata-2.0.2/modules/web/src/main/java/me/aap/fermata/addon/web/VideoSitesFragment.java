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
		FermataChromeClient chromeClient = new FermataChromeClient(webView, fullScreenView);
		webView.init(addon, new VideoSitesWebClient(addon), chromeClient);
		AutoFullScreen.install(webView, "Video");
		webView.loadUrl(VideoSitesPage.HOME);
		MainActivityDelegate.getActivityDelegate(ctx).onSuccess(this::registerListeners);
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
