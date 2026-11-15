package me.aap.fermata.addon.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** «Продолжить просмотр», ссылки Zona на зеркало пользователя, данные страницы. */
public class VideoContinueTest {

	private static VideoContinue.Entry e(String url, String name) {
		return new VideoContinue.Entry(url, name, "https://img/" + name + ".jpg", 2026, 8.2, false, 0, 0);
	}

	@Test
	public void encodeDecodeRoundTrip() {
		List<VideoContinue.Entry> l = new ArrayList<>();
		VideoContinue.Entry a = e("https://w1.zona.im/movies/odisseya-2026", "Одиссея\tс табом");
		a.time = 1234.5;
		a.duration = 7200;
		l.add(a);
		l.add(new VideoContinue.Entry("https://w1.zona.im/tvseries/x", "Сериал", "", 2020, 0, true, 0, 0));
		List<VideoContinue.Entry> d = VideoContinue.decode(VideoContinue.encode(l));
		assertEquals(2, d.size());
		assertEquals("Одиссея с табом", d.get(0).name);
		assertEquals(1234.5, d.get(0).time, 0.01);
		assertEquals(7200, d.get(0).duration, 0.01);
		assertTrue(d.get(1).serial);
		assertEquals(0, VideoContinue.decode(null).size());
		assertEquals(0, VideoContinue.decode("мусор\nещё").size());
	}

	@Test
	public void openedGoesFirstKeepsTimeAndLimit() {
		List<VideoContinue.Entry> l = new ArrayList<>();
		for (int i = 0; i < 12; i++) l = VideoContinue.opened(l, e("https://z/movies/f" + i, "f" + i));
		assertEquals(VideoContinue.MAX, l.size());
		assertEquals("f11", l.get(0).name);
		l.get(5).time = 600;
		String u = l.get(5).url;
		l = VideoContinue.opened(l, e(u, "again"));
		assertEquals(u, l.get(0).url);
		assertEquals(600, l.get(0).time, 0.01);
		assertEquals(VideoContinue.MAX, l.size());
	}

	@Test
	public void playedMatchesByPathAcrossMirrors() {
		List<VideoContinue.Entry> l = new ArrayList<>();
		l = VideoContinue.opened(l, e("https://w140.zona.plus/movies/odisseya-2026", "Одиссея"));
		assertTrue(VideoContinue.played(l, "https://w1.zona.im/movies/odisseya-2026/?utm=1", 300, 7000));
		assertEquals(300, l.get(0).time, 0.01);
		assertEquals(7000, l.get(0).duration, 0.01);
		assertFalse(VideoContinue.played(l, "https://w1.zona.im/movies/drugoj", 50, 100));
		assertEquals(300, l.get(0).time, 0.01);
		// серия внутри страницы сериала
		l = VideoContinue.opened(l, e("https://w1.zona.im/tvseries/ser", "Сериал"));
		assertTrue(VideoContinue.played(l, "https://w1.zona.im/tvseries/ser/season-1/episode-2", 90, 2400));
		assertEquals(90, l.get(0).time, 0.01);
		assertFalse(VideoContinue.played(l, null, 1, 1));
	}

	@Test
	public void zonaLinksGoToUserMirror() {
		Map<String, String> sites = new LinkedHashMap<>();
		// как сохранено на телефоне (web.xml, VS_SITES)
		sites.put("https://w1.zona.im/", "Zona");
		sites.put("https://liftw.ws/?utm_source=channel", "Lift");
		assertEquals("https://w1.zona.im/movies/odisseya-2026",
				VideoSitesPage.zonaUrl("https://w140.zona.plus/movies/odisseya-2026", sites));
		assertEquals("https://example.com/x", VideoSitesPage.zonaUrl("https://example.com/x", sites));
		assertEquals("https://w1.zona.im/movies/a",
				VideoSitesPage.zonaUrl("https://w140.zona.plus/movies/a", new LinkedHashMap<>()));
		assertEquals("https://liftw.ws/?utm_source=channel", VideoSitesPage.savedSite(sites, "lift"));
	}

	@Test
	public void pageHasServicesAndSavedSites() {
		Map<String, String> sites = new LinkedHashMap<>();
		sites.put("https://w1.zona.im/", "Zona");
		sites.put("https://liftw.ws/?utm_source=channel&utm_medium=x", "Lift");
		String h = VideoSitesPage.html(sites, null);
		assertTrue(h.contains("class=\"sv\" href=\"https://w1.zona.im/\">Zona"));
		assertTrue(h.contains("class=\"sv\" href=\"https://liftw.ws/?utm_source=channel&amp;utm_medium=x\">Lift"));
		assertTrue(h.contains(">YouTube</a>"));
		for (String no : new String[]{"Кинопоиск", "TikTok", "Rutube", "VK"}) assertFalse(no, h.contains(no));
		assertTrue(h.contains("url(/fon.jpg)"));
		assertTrue(h.contains("Удалить"));
	}

	@Test
	public void continueDataEscaped() {
		List<VideoContinue.Entry> l = new ArrayList<>();
		VideoContinue.Entry a = e("https://w1.zona.im/movies/x", "</script><b>\"");
		a.time = 1800;
		a.duration = 3600;
		l.add(a);
		String h = VideoSitesPage.html(new LinkedHashMap<>(), null, l);
		assertFalse(h.contains("</script><b>"));
		assertTrue(h.contains("n:\"\\u003c/script\\u003e\\u003cb\\u003e\\\"\""));
		assertTrue(h.contains(",p:50}"));
		assertTrue(VideoSitesPage.isAfisha("{\"ryady\":[]}", "ryady"));
		assertFalse(VideoSitesPage.isAfisha("<html>", "ryady"));
		assertFalse(VideoSitesPage.isAfisha("{\"error\":1}", "ryady"));
	}
}
