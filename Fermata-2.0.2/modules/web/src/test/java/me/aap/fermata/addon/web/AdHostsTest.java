package me.aap.fermata.addon.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Н6 (16.11.2026): реклама во вкладке «Видео» сборки жены. */
public class AdHostsTest {
	@Test
	public void adNetworksAndSubdomains() {
		assertTrue(AdHosts.isAd("doubleclick.net"));
		assertTrue(AdHosts.isAd("securepubads.g.doubleclick.net"));
		assertTrue(AdHosts.isAd("an.yandex.ru"));
		assertTrue(AdHosts.isAd("MC.Yandex.RU."));
		assertTrue(AdHosts.isAd("ad.adriver.ru"));
		assertTrue(AdHosts.isAd("android.zona.pub"));
	}

	@Test
	public void filmSitesAndPlayersStay() {
		assertFalse(AdHosts.isAd(null));
		assertFalse(AdHosts.isAd(""));
		assertFalse(AdHosts.isAd("w140.zona.plus"));
		assertFalse(AdHosts.isAd("hd.kinopoisk.ru"));
		assertFalse(AdHosts.isAd("yandex.ru"));
		assertFalse(AdHosts.isAd("www.youtube.com"));
		// не путать с похожим окончанием
		assertFalse(AdHosts.isAd("notdoubleclick.net"));
		assertFalse(AdHosts.isAd("tv-afisha.jarvisauto.workers.dev"));
	}

	@Test
	public void popupSameSite() {
		assertTrue(AdHosts.sameSite("w140.zona.plus", "zona.plus"));
		assertTrue(AdHosts.sameSite("a.zona.plus", "b.zona.plus"));
		assertFalse(AdHosts.sameSite("zona.plus", "1xbet-promo.com"));
		assertFalse(AdHosts.sameSite(null, "zona.plus"));
		assertFalse(AdHosts.sameSite(null, null));
	}

	/** Кинопоиск, 16.11.2026: реклама Яндекса путями; сам Кинопоиск, вход и видео не трогаются. */
	@Test
	public void yandexAdsByPath() {
		assertTrue(AdHosts.isAdUrl("yandex.ru", "/ads/system/context.js"));
		assertTrue(AdHosts.isAdUrl("yandex.ru", "/an/count/x"));
		assertTrue(AdHosts.isAdUrl("www.yandex.by", "/ads/meta/1"));
		assertTrue(AdHosts.isAdUrl("yabs.yandex.ru", "/count/x"));
		assertFalse(AdHosts.isAdUrl("yandex.ru", "/clck/click"));
		assertFalse(AdHosts.isAdUrl("passport.yandex.ru", "/ads/"));
		assertFalse(AdHosts.isAdUrl("hd.kinopoisk.ru", "/ads/"));
		assertFalse(AdHosts.isAdUrl("graphql.kinopoisk.ru", "/graphql/"));
		assertFalse(AdHosts.isAdUrl("cdnrrviubnyuntopbez5.svc.cdn.yandex.net", "/get-ott/1"));
		assertFalse(AdHosts.isAdUrl("strm.yandex.ru", "/vh-ott/x.m3u8"));
		assertFalse(AdHosts.isAdUrl(null, null));
	}
}
