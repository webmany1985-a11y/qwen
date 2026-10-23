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

	private static final String HOME = "{\"rooms\":[{\"id\":\"r1\",\"name\":\"Гараж\"},{\"id\":\"r2\",\"name\":\"Кухня\"}]," +
			"\"scenarios\":[{\"id\":\"s1\",\"name\":\"Утро\"},{\"id\":\"s2\",\"name\":\"Я ухожу\"}," +
			"{\"id\":\"s3\",\"name\":\"Ночь\",\"is_active\":false}]," +
			"\"devices\":[" +
			"{\"id\":\"d1\",\"name\":\"Свет\",\"room\":\"r1\",\"capabilities\":[{\"type\":\"devices.capabilities.on_off\"}]}," +
			"{\"id\":\"d2\",\"name\":\"Свет\",\"room\":\"r2\",\"capabilities\":[{\"type\":\"devices.capabilities.on_off\"}]}," +
			"{\"id\":\"d3\",\"name\":\"Телевизор\",\"room\":\"r2\",\"capabilities\":[{\"type\":\"devices.capabilities.on_off\"}]}," +
			"{\"id\":\"d4\",\"name\":\"Светильник\",\"room\":\"r2\",\"capabilities\":[{\"type\":\"devices.capabilities.on_off\"}]}," +
			"{\"id\":\"d5\",\"name\":\"Датчик\",\"room\":\"r1\",\"capabilities\":[]}]}";

	@Test
	public void scenarios() throws Exception {
		JSONObject i = info(HOME);
		YandexHome.Action a = YandexHome.match("запусти сценарий утро", i);
		assertTrue(a.target.scenario);
		assertEquals("s1", a.target.id);
		assertEquals("s2", YandexHome.match("Я ухожу", i).target.id);
		assertEquals("s2", YandexHome.match("Джарвис, я ухожу!", i).target.id);
		assertNull(YandexHome.match("запусти сценарий ночь", i)); // выключен в Яндексе
		assertNull(YandexHome.match("запусти сценарий обед", i));
	}

	@Test
	public void devicesByNameAndRoom() throws Exception {
		JSONObject i = info(HOME);
		YandexHome.Action a = YandexHome.match("включи свет в гараже", i);
		assertFalse(a.target.scenario);
		assertEquals("d1", a.target.id);
		assertTrue(a.on);
		a = YandexHome.match("выключи свет на кухне", i);
		assertEquals("d2", a.target.id);
		assertFalse(a.on);
		assertNull(YandexHome.match("включи свет", i)); // два «Свет» — без комнаты не угадывать
		assertEquals("d3", YandexHome.match("выключи телевизор", i).target.id);
		assertEquals("d4", YandexHome.match("включи светильник", i).target.id);
		assertNull(YandexHome.match("включи датчик", i)); // нечего включать
	}

	@Test
	public void notHome() throws Exception {
		JSONObject i = info(HOME);
		assertNull(YandexHome.match("включи музыку", i));
		assertNull(YandexHome.match("включи песню утро", i));
		assertNull(YandexHome.match("какая погода", i));
		assertNull(YandexHome.match("включи свет", null));
		assertTrue(YandexHome.looksLike("включи музыку"));
		assertTrue(YandexHome.looksLike("Джарвис, запусти сценарий утро"));
		assertFalse(YandexHome.looksLike("какая погода"));
	}
}
