package me.aap.fermata.addon.web;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Рекламные и счётные адреса, которые вкладка «Видео» в сборке жены не грузит (Н6, 16.11.2026: «куча рекламы»).
 * Совпадение — по домену и его поддоменам. Чистая логика без Android — проверяется тестом.
 * Список нарочно короткий: только сети рекламы и счётчики; сами сайты с фильмами и их проигрыватели не трогаются.
 */
final class AdHosts {
	private static final String[] HOSTS = {
			// Google
			"doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
			"googletagmanager.com", "googletagservices.com", "adservice.google.com", "imasdk.googleapis.com",
			// Яндекс — реклама и Метрика (не поиск и не Кинопоиск)
			"an.yandex.ru", "yandexadexchange.net", "adfox.ru", "adfox.yandex.ru", "mc.yandex.ru", "mc.yandex.com",
			"yabs.yandex.ru", "yabs.yandex.by", "yabs.yandex.com", "awaps.yandex.net", "awaps.yandex.ru",
			// русскоязычные сети и VAST перед фильмом
			"adriver.ru", "betweendigital.com", "buzzoola.com", "mediametrics.ru", "relap.io", "adhigh.net",
			"admitad.com", "otm-r.com", "republer.com", "acint.net", "smi2.ru", "smi2.net", "24smi.org",
			"marketgid.com", "mgid.com", "lentainform.com", "directadvert.ru", "tds.bid", "adsterra.com",
			"popads.net", "popcash.net", "propellerads.com", "onclickads.net", "exoclick.com", "hilltopads.net",
			"clickadu.com", "adcash.com", "trafficstars.com", "juicyads.com", "a-ads.com", "vidroll.ru",
			"videoroll.net", "moevideo.biz", "playbuzz.com", "rtb.adx1.com", "adx1.com", "solarmedia.ru",
			// счётчики
			// реклама приложения самих сайтов с фильмами
			"android.zona.pub",
			"top-fwz1.mail.ru", "counter.yadro.ru", "liveinternet.ru", "tns-counter.ru", "scorecardresearch.com",
	};

	private AdHosts() {
	}

	/**
	 * Всплывающее окно ведёт на тот же сайт, что и страница (по двум последним частям имени: w140.zona.plus и
	 * zona.plus — один сайт). Такие окна открываются во вкладке; чужие — это всплывающая реклама, они не открываются.
	 */
	static boolean sameSite(@Nullable String a, @Nullable String b) {
		String x = site(a), y = site(b);
		return !x.isEmpty() && x.equals(y);
	}

	private static String site(@Nullable String host) {
		if (host == null) return "";
		String h = host.toLowerCase(Locale.ROOT);
		if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
		int i = h.lastIndexOf('.');
		if (i <= 0) return h;
		int j = h.lastIndexOf('.', i - 1);
		return (j < 0) ? h : h.substring(j + 1);
	}

	/** Главные домены Яндекса: реклама у них — путями /ads/ и /an/ (рекламная сеть, AdFox), остальное не трогается. */
	private static final String[] YANDEX = {"yandex.ru", "yandex.by", "yandex.com", "yandex.net", "yandex.kz"};

	/**
	 * Адрес рекламный: хост из списка или реклама Яндекса путём (yandex.ru/ads/…, yandex.ru/an/…) — так её грузит
	 * Кинопоиск (16.11.2026: блок «Реклама» на главной вкладки «Кинопоиск»).
	 */
	static boolean isAdUrl(@Nullable String host, @Nullable String path) {
		if (isAd(host)) return true;
		if ((host == null) || (path == null)) return false;
		String h = host.toLowerCase(Locale.ROOT);
		if (h.startsWith("www.")) h = h.substring(4);
		for (String y : YANDEX) {
			if (h.equals(y)) return path.startsWith("/ads/") || path.startsWith("/an/");
		}
		return false;
	}

	/** Хост рекламный: совпадает с доменом из списка или его поддомен. */
	static boolean isAd(@Nullable String host) {
		if ((host == null) || host.isEmpty()) return false;
		String h = host.toLowerCase(Locale.ROOT);
		if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
		for (String a : HOSTS) {
			if (h.equals(a) || h.endsWith("." + a)) return true;
		}
		return false;
	}
}
