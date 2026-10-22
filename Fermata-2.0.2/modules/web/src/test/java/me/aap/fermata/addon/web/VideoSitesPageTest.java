package me.aap.fermata.addon.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Страница «Мои сайты»: адреса, названия, экранирование, правка. */
public class VideoSitesPageTest {

	@Test
	public void addressCompleted() {
		assertEquals("https://rutube.ru", VideoSitesPage.normalizeUrl(" rutube.ru "));
		assertEquals("http://192.168.1.10:8096", VideoSitesPage.normalizeUrl("http://192.168.1.10:8096"));
		assertNull(VideoSitesPage.normalizeUrl(""));
		assertNull(VideoSitesPage.normalizeUrl("javascript:alert(1)"));
		assertNull(VideoSitesPage.normalizeUrl("intent://x"));
	}

	@Test
	public void defaultNameFromHost() {
		assertEquals("vkvideo.ru", VideoSitesPage.defaultName("https://www.vkvideo.ru/video/123"));
	}

	@Test
	public void emptyListInvitesToAdd() {
		String h = VideoSitesPage.html(Collections.emptyMap(), null);
		assertTrue(h.contains("Пока пусто"));
		assertTrue(h.contains("value=\"add\""));
	}

	@Test
	public void sitesShownAndEscaped() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("https://rutube.ru", "Rutube <тест>");
		String h = VideoSitesPage.html(m, null);
		assertTrue(h.contains("href=\"https://rutube.ru\""));
		assertTrue(h.contains("Rutube &lt;тест&gt;"));
		assertFalse(h.contains("<тест>"));
		assertTrue(h.contains(VideoSitesPage.action("remove", "https://rutube.ru").replace("&", "&amp;")));
	}

	@Test
	public void editFormFilled() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("https://rutube.ru", "Rutube");
		String h = VideoSitesPage.html(m, "https://rutube.ru");
		assertTrue(h.contains("value=\"save\""));
		assertTrue(h.contains("Изменить сайт"));
		assertTrue(h.contains("name=\"name\" placeholder=\"Название\" value=\"Rutube\""));
	}
}
