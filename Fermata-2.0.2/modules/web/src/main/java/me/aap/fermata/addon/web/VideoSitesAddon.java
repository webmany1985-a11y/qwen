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
 * Вкладка «Видео»: общий встроенный проигрыватель с сайтами пользователя
 * (см. {@link VideoSitesFragment}). Список сайтов — закладки этой вкладки,
 * отдельные от «Браузера».
 */
@Keep
@SuppressWarnings("unused")
public class VideoSitesAddon extends WebBrowserAddon {
	@NonNull
	private static final AddonInfo info = FermataAddon.findAddonInfo(VideoSitesAddon.class.getName());
	private static final Pref<IntSupplier> VS_DARK_MODE = Pref.i("VS_DARK_MODE", DARK_MODE_AUTO);
	private static final Pref<BooleanSupplier> VS_DESKTOP_VERSION = Pref.b("VS_DESKTOP_VERSION", false);
	private static final Pref<Supplier<String[]>> VS_SITES = Pref.sa("VS_SITES");

	@IdRes
	@Override
	public int getAddonId() {
		return me.aap.fermata.R.id.video_sites_fragment;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new VideoSitesFragment();
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
	}

	@Override
	public Pref<IntSupplier> getForceDarkPref() {
		return VS_DARK_MODE;
	}

	@Override
	public Pref<BooleanSupplier> getDesktopVersionPref() {
		return VS_DESKTOP_VERSION;
	}

	@Override
	public Pref<Supplier<String[]>> getBookmarksPref() {
		return VS_SITES;
	}

	@Override
	String getLastUrl() {
		return VideoSitesPage.HOME;
	}

	@Override
	void setLastUrl(String url) {
		// Вкладка всегда открывается со страницы «Мои сайты»
	}
}
