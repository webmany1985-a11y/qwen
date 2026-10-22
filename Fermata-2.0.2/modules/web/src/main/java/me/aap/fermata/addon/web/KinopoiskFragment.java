package me.aap.fermata.addon.web;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.addon.AddonManager;

/**
 * Экран вкладки «Кинопоиск»: как у TikTok — в машине версия для компьютера и
 * вкладка на весь экран (значок в углу возвращает панели); фильм — во весь
 * экран плеером сайта.
 */
@Keep
@SuppressWarnings("unused")
public class KinopoiskFragment extends TikTokFragment {

	@Override
	public int getFragmentId() {
		return me.aap.fermata.R.id.kinopoisk_fragment;
	}

	@Override
	protected FermataWebClient createWebClient() {
		return new KinopoiskWebClient();
	}

	@NonNull
	@Override
	protected String tabName() {
		return "Kinopoisk";
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
