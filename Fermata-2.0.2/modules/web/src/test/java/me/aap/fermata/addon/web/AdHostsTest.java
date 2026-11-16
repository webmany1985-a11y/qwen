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
}
