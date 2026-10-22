package me.aap.fermata.addon.web;

import android.content.Context;

import androidx.annotation.IdRes;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
 * Вкладка «Кинопоиск» (hd.kinopoisk.ru) вместо ChatGPT (пожелание 22.10.2026,
 * подписка у пользователя есть). Встроенный браузер, как TikTok: вход в
 * аккаунт Яндекса сохраняется, фильм — во весь экран плеером сайта
 * (полноэкранный режим страницы показывается на месте вкладки, как у YouTube).
 * Свои настройки (последняя страница, тёмная тема, закладки).
 */
@Keep
@SuppressWarnings("unused")
public class KinopoiskAddon extends WebBrowserAddon {
	static final String DEFAULT_URL = "https://hd.kinopoisk.ru/";
	@NonNull
	private static final AddonInfo info = FermataAddon.findAddonInfo(KinopoiskAddon.class.getName());
	private static final Pref<Supplier<String>> KP_LAST_URL = Pref.s("KP_LAST_URL", DEFAULT_URL);
	private static final Pref<IntSupplier> KP_DARK_MODE = Pref.i("KP_DARK_MODE", DARK_MODE_AUTO);
	private static final Pref<BooleanSupplier> KP_DESKTOP_VERSION = Pref.b("KP_DESKTOP_VERSION", false);
	private static final Pref<Supplier<String[]>> KP_BOOKMARKS = Pref.sa("KP_BOOKMARKS");

	@IdRes
	@Override
	public int getAddonId() {
		return me.aap.fermata.R.id.kinopoisk_fragment;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new KinopoiskFragment();
	}

	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
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
		return KP_DARK_MODE;
	}

	@Override
	public Pref<BooleanSupplier> getDesktopVersionPref() {
		return KP_DESKTOP_VERSION;
	}

	@Override
	public Pref<Supplier<String[]>> getBookmarksPref() {
		return KP_BOOKMARKS;
	}

	@Override
	String getLastUrl() {
		String u = getPreferenceStore().getStringPref(KP_LAST_URL);
		return isKinopoiskPage(u) ? u : DEFAULT_URL;
	}

	@Override
	void setLastUrl(String url) {
		// Страницы входа Яндекса не запоминаем: после входа — снова Кинопоиск
		if (isKinopoiskPage(url)) getPreferenceStore().applyStringPref(KP_LAST_URL, url);
	}

	static boolean isKinopoiskPage(@Nullable String url) {
		if (url == null) return false;
		android.net.Uri u = android.net.Uri.parse(url);
		return "https".equals(u.getScheme()) && KinopoiskWebClient.isKinopoisk(u);
	}
}
