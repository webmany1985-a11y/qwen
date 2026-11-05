package me.aap.fermata.addon.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Вкладка «Заправка»: ссылка пользователя → веб-Telegram. */
public class ZapravkaLinksTest {

	@Test
	public void linkForms() {
		assertEquals("https://t.me/fuel_bot", ZapravkaLinks.normalize("t.me/fuel_bot"));
		assertEquals("https://t.me/fuel_bot", ZapravkaLinks.normalize(" @fuel_bot "));
		assertEquals("https://t.me/fuel_bot", ZapravkaLinks.normalize("fuel_bot"));
		assertEquals("https://t.me/fuel_bot/app?startapp=x1", ZapravkaLinks.normalize("https://t.me/fuel_bot/app?startapp=x1"));
		assertEquals("https://t.me/fuel_bot/app", ZapravkaLinks.normalize("http://telegram.me/fuel_bot/app"));
		assertEquals("tg://resolve?domain=fuel_bot&appname=app", ZapravkaLinks.normalize("tg://resolve?domain=fuel_bot&appname=app"));
	}

	@Test
	public void badLinks() {
		assertNull(ZapravkaLinks.normalize(null));
		assertNull(ZapravkaLinks.normalize("  "));
		assertNull(ZapravkaLinks.normalize("https://example.com/fuel"));
		assertNull(ZapravkaLinks.normalize("javascript:alert(1)"));
		assertNull(ZapravkaLinks.normalize("https://t.me/"));
		assertNull(ZapravkaLinks.normalize("https://t.me/ab"));
	}

	@Test
	public void startUrl() {
		assertEquals("https://web.telegram.org/k/", ZapravkaLinks.startUrl(""));
		assertEquals("https://web.telegram.org/k/", ZapravkaLinks.startUrl("непонятно что"));
		assertEquals("https://web.telegram.org/k/#?tgaddr=https%3A%2F%2Ft.me%2Ffuel_bot%2Fapp%3Fstartapp%3Dx1",
				ZapravkaLinks.startUrl("t.me/fuel_bot/app?startapp=x1"));
	}

	@Test
	public void a100ByDefault() {
		assertEquals("https://web.telegram.org/k/#?tgaddr=https%3A%2F%2Ft.me%2FA100_app_bot",
				ZapravkaLinks.startUrl(ZapravkaAddon.DEFAULT_LINK));
	}

	@Test
	public void telegramHosts() {
		assertTrue(ZapravkaLinks.isTelegramHost("web.telegram.org"));
		assertTrue(ZapravkaLinks.isTelegramHost("t.me"));
		assertFalse(ZapravkaLinks.isTelegramHost("telegram.org.evil.com"));
		assertFalse(ZapravkaLinks.isTelegramHost(null));
	}

	@Test
	public void fastTab() {
		String u = ZapravkaLinks.fastUrl("https://t.me/A100_app_bot");
		assertTrue(u.startsWith("https://appassets.androidplatform.net/zp/zapravka_a100.html?tg=https%3A%2F%2Fweb.telegram.org%2Fk%2F"));
		assertEquals("zapravka_a100.html", ZapravkaLinks.fastAsset("appassets.androidplatform.net", "/zp/zapravka_a100.html"));
		assertEquals("zapravka_fon.jpg", ZapravkaLinks.fastAsset("appassets.androidplatform.net", "/zp/zapravka_fon.jpg"));
		assertNull(ZapravkaLinks.fastAsset("appassets.androidplatform.net", "/zp/zapravka_auto.js"));
		assertNull(ZapravkaLinks.fastAsset("appassets.androidplatform.net", "/zp/../zapravka_a100.html"));
		assertNull(ZapravkaLinks.fastAsset("example.com", "/zp/zapravka_a100.html"));
		assertNull(ZapravkaLinks.fastAsset("appassets.androidplatform.net", null));
		assertEquals("image/png", ZapravkaLinks.mimeOf("zapravka_logo.png"));
	}
}
