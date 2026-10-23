package me.aap.fermata.addon.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Ворота через Умный дом Яндекса: фраза, выбор сценария или устройства, токен. */
public class YandexHomeTest {

	@Test
	public void gatePhrases() {
		assertEquals(Boolean.TRUE, YandexHome.gateCommand("открой ворота"));
		assertEquals(Boolean.TRUE, YandexHome.gateCommand("Джарвис, открой ворота"));
		assertEquals(Boolean.TRUE, YandexHome.gateCommand("открыть ворота"));
		assertEquals(Boolean.FALSE, YandexHome.gateCommand("закрой ворота"));
		assertNull(YandexHome.gateCommand("открой окно"));
		assertNull(YandexHome.gateCommand("какие ворота лучше"));
		assertNull(YandexHome.gateCommand("открой поворот"));
	}

	private static JSONObject info(String json) throws Exception {
		return new JSONObject(json);
	}

	@Test
	public void scenarioWithVerbFirst() throws Exception {
		JSONObject i = info("{\"scenarios\":[{\"id\":\"s1\",\"name\":\"Закрыть ворота\"}," +
				"{\"id\":\"s2\",\"name\":\"Открыть ворота\"}],\"devices\":[{\"id\":\"d1\",\"name\":\"Ворота\"}]}");
		YandexHome.Target t = YandexHome.pick(i, true);
		assertTrue(t.scenario);
		assertEquals("s2", t.id);
		assertEquals("s1", YandexHome.pick(i, false).id);
	}

	@Test
	public void deviceWhenNoScenario() throws Exception {
		JSONObject i = info("{\"scenarios\":[{\"id\":\"s1\",\"name\":\"Свет в гараже\"}]," +
				"\"devices\":[{\"id\":\"d0\",\"name\":\"Лампа\"},{\"id\":\"d1\",\"name\":\"Ворота Sonoff\"}]}");
		YandexHome.Target t = YandexHome.pick(i, true);
		assertFalse(t.scenario);
		assertEquals("d1", t.id);
		assertNull(YandexHome.pick(info("{\"scenarios\":[],\"devices\":[]}"), true));
	}

	@Test
	public void tokenFromRedirect() {
		assertEquals("y0_AgAAA", YandexHome.tokenFromRedirect(
				"https://oauth.yandex.ru/verification_code#access_token=y0_AgAAA&token_type=bearer&expires_in=31536000"));
		assertNull(YandexHome.tokenFromRedirect("https://oauth.yandex.ru/verification_code?error=access_denied"));
	}
}
