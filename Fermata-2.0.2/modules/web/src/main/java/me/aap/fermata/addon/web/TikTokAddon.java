package me.aap.fermata.addon.web;

import android.content.Context;

import androidx.annotation.IdRes;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import java.util.List;

import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.FermataAddon;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * Вкладка TikTok в JARVIS Auto: мобильный tiktok.com во встроенном браузере,
 * как вкладка YouTube. Досмотренное видео перелистывается само
 * (assets/tiktok_autonext.js, вставляет {@link TikTokWebClient}).
 * Свои настройки (последняя страница, тёмная тема, закладки) — отдельно от
 * «Браузера», чтобы вкладки не мешали друг другу.
 */
@Keep
@SuppressWarnings("unused")
public class TikTokAddon extends WebBrowserAddon {
	static final String DEFAULT_URL = "https://www.tiktok.com/foryou";
	@NonNull
	private static final AddonInfo info = FermataAddon.findAddonInfo(TikTokAddon.class.getName());
	private static final Pref<Supplier<String>> TT_LAST_URL = Pref.s("TT_LAST_URL", DEFAULT_URL);
	private static final Pref<IntSupplier> TT_DARK_MODE = Pref.i("TT_DARK_MODE", DARK_MODE_AUTO);
	private static final Pref<BooleanSupplier> TT_DESKTOP_VERSION = Pref.b("TT_DESKTOP_VERSION", false);
	private static final Pref<Supplier<String[]>> TT_BOOKMARKS = Pref.sa("TT_BOOKMARKS");

	@IdRes
	@Override
	public int getAddonId() {
		return me.aap.fermata.R.id.tiktok_fragment;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new TikTokFragment();
	}

	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		// Только тёмная тема; слушатели «Браузера» (открывать при запуске) не нужны
		set.addListPref(o -> {
			o.store = getPreferenceStore();
			o.pref = getForceDarkPref();
			o.title = R.string.force_dark;
			o.subtitle = R.string.force_dark_sub;
			o.visibility = visibility;
			o.formatSubtitle = true;
			o.values = new int[]{R.string.force_dark_disabled, R.string.force_dark_enabled,
					R.string.force_dark_auto};
		});
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<Pref<?>> prefs) {
		// Настройка «открывать при запуске» принадлежит «Браузеру» — не трогаем
	}

	@Override
	public Pref<IntSupplier> getForceDarkPref() {
		return TT_DARK_MODE;
	}

	@Override
	public Pref<BooleanSupplier> getDesktopVersionPref() {
		return TT_DESKTOP_VERSION;
	}

	@Override
	public Pref<Supplier<String[]>> getBookmarksPref() {
		return TT_BOOKMARKS;
	}

	@Override
	String getLastUrl() {
		String u = getPreferenceStore().getStringPref(TT_LAST_URL);
		// Запомненная ссылка «открыть в приложении» давала белый экран при каждом входе
		return isTikTokPage(u) ? u : DEFAULT_URL;
	}

	@Override
	void setLastUrl(String url) {
		if (isTikTokPage(url)) getPreferenceStore().applyStringPref(TT_LAST_URL, url);
	}

	/** Запоминаем только страницы tiktok.com. */
	static boolean isTikTokPage(@androidx.annotation.Nullable String url) {
		if (url == null) return false;
		android.net.Uri u = android.net.Uri.parse(url);
		return "https".equals(u.getScheme()) && TikTokWebClient.isTikTok(u);
	}
}
