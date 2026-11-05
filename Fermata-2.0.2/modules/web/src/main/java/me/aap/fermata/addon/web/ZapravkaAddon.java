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
 * Вкладка «Заправка» (пожелание 04.11.2026): приложение заправки из Telegram на экране машины.
 * Встроенный браузер с веб-Telegram ({@link ZapravkaLinks}); вход в Telegram — один раз, сохраняется, как
 * вход в Кинопоиске. Ссылка на приложение заправки ({@code t.me/…}) — в настройках вкладки; пусто — веб-Telegram
 * со списком чатов. Голосом — следующим шагом (указание пользователя: «пока только управление через вкладку»).
 */
@Keep
@SuppressWarnings("unused")
public class ZapravkaAddon extends WebBrowserAddon {
	@NonNull
	private static final AddonInfo info = FermataAddon.findAddonInfo(ZapravkaAddon.class.getName());
	/** Бот сети АЗС «А-100» — ссылка от пользователя 04.11.2026; в настройках можно заменить. */
	static final String DEFAULT_LINK = "https://t.me/A100_app_bot";
	static final Pref<Supplier<String>> ZP_LINK = Pref.s("ZP_LINK", DEFAULT_LINK);
	private static final Pref<IntSupplier> ZP_DARK_MODE = Pref.i("ZP_DARK_MODE", DARK_MODE_AUTO);
	private static final Pref<BooleanSupplier> ZP_DESKTOP_VERSION = Pref.b("ZP_DESKTOP_VERSION", false);
	private static final Pref<Supplier<String[]>> ZP_BOOKMARKS = Pref.sa("ZP_BOOKMARKS");

	@IdRes
	@Override
	public int getAddonId() {
		return me.aap.fermata.R.id.zapravka_fragment;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new ZapravkaFragment();
	}

	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		set.addStringPref(o -> {
			o.store = getPreferenceStore();
			o.pref = ZP_LINK;
			o.title = me.aap.fermata.R.string.zapravka_link;
			o.stringHint = "t.me/…";
			o.visibility = visibility;
			o.maxLines = 2;
		});
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
		return ZP_DARK_MODE;
	}

	@Override
	public Pref<BooleanSupplier> getDesktopVersionPref() {
		return ZP_DESKTOP_VERSION;
	}

	@Override
	public Pref<Supplier<String[]>> getBookmarksPref() {
		return ZP_BOOKMARKS;
	}

	/** Ссылка на бота из настроек. */
	String getLink() {
		return getPreferenceStore().getStringPref(ZP_LINK);
	}

	/** Своя вкладка А-100 (05.11.2026); ссылка на бота — для кнопки «Telegram» на ней. */
	String getFastUrl() {
		return ZapravkaLinks.fastUrl(getPreferenceStore().getStringPref(ZP_LINK));
	}

	/** Вкладка всегда открывается на приложении заправки, а не на последней странице. */
	@Override
	String getLastUrl() {
		return ZapravkaLinks.startUrl(getPreferenceStore().getStringPref(ZP_LINK));
	}

	@Override
	void setLastUrl(String url) {
	}
}
