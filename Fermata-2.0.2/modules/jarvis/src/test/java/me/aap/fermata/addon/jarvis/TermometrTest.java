package me.aap.fermata.addon.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Виджет температуры (карточка android-team/tasks/2026-11-14-auto-temperatura-vidzhet). Ответы — сохранённые 14.11.2026. */
public class TermometrTest {

	private static String res(String name) throws Exception {
		try (InputStream in = TermometrTest.class.getResourceAsStream("/termometr/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	public void temperatureFromOpenMeteo() throws Exception {
		assertEquals(4.6, Termometr.parseTemp(res("open-meteo.json")), 1e-9);
		// Ответ о превышении предела — не температура
		assertNull(Termometr.parseTemp(res("open-meteo-predel.json")));
		assertNull(Termometr.parseTemp(null));
		assertNull(Termometr.parseTemp("<html>"));
		assertEquals(-12.3, Termometr.parseTemp("{\"current\":{\"time\":\"x\",\"temperature_2m\":-12.3}}"), 1e-9);
		// Единицы в current_units не путаются с самим значением
		assertEquals(-1.0, Termometr.parseTemp(
				"{\"current_units\":{\"temperature_2m\":\"°C\"},\"current\":{\"temperature_2m\":-1}}"), 1e-9);
	}

	@Test
	public void placeFollowsPhone() throws Exception {
		// Деревня — её имя, а не сельсовет из поля city
		assertEquals("Самохваловичи", Termometr.parsePlace(res("samohvalovichi.json")));
		// Город — город, а не район города и не микрорайон
		assertEquals("Минск", Termometr.parsePlace(res("minsk-centr.json")));
		assertEquals("Минск", Termometr.parsePlace(res("minsk-drazhnya.json")));
		assertEquals("Жодино", Termometr.parsePlace(res("zhodino.json")));
		// Между населёнными пунктами — район
		assertEquals("Минский район", Termometr.parsePlace(res("mezhdu-derevnyami.json")));
		assertEquals("", Termometr.parsePlace(null));
		assertEquals("", Termometr.parsePlace("{\"error\":\"Unable to geocode\"}"));
		assertEquals("Ждановичи", Termometr.parsePlace("{\"address\":{\"village\":\"\\u0416дановичи\"}}"));
	}

	@Test
	public void format() {
		assertEquals("\u22123°", Termometr.format(-3.2));
		assertEquals("\u22124°", Termometr.format(-3.5));
		assertEquals("5°", Termometr.format(4.6));
		assertEquals("0°", Termometr.format(-0.4));
		assertEquals("0°", Termometr.format(0.0));
		assertEquals("—°", Termometr.format(null));
		assertEquals("—°", Termometr.format(Double.NaN));
	}

	@Test
	public void caption() {
		assertEquals("за бортом · Самохваловичи", Termometr.caption("Самохваловичи"));
		assertEquals("за бортом", Termometr.caption(""));
		assertEquals("за бортом", Termometr.caption(null));
	}

	@Test
	public void offlineKeepsLastValueForAnHour() {
		long saved = 1_000_000_000L;
		assertEquals("\u22123°", Termometr.shown(-3.0, saved, saved + 59 * 60_000L));
		assertEquals("\u22123°", Termometr.shown(-3.0, saved, saved + 60 * 60_000L));
		assertEquals("—°", Termometr.shown(-3.0, saved, saved + 61 * 60_000L));
		// Ни разу не получали
		assertEquals("—°", Termometr.shown(null, 0L, saved));
	}

	@Test
	public void refreshEvery15Minutes() {
		long t = 1_000_000_000L;
		assertTrue(Termometr.due(0L, t));
		assertFalse(Termometr.due(t, t + 14 * 60_000L));
		assertTrue(Termometr.due(t, t + 15 * 60_000L));
		assertTrue(Termometr.due(t, t - 60_000L));
	}

	@Test
	public void movedMoreThanAKilometre() {
		// Минск (центр) — Самохваловичи ≈ 19 км
		assertTrue(Termometr.moved(53.9006, 27.5590, 53.7383, 27.5044));
		// 500 м — то же место
		assertFalse(Termometr.moved(53.9006, 27.5590, 53.9051, 27.5590));
		assertEquals(500, Termometr.distanceM(53.9006, 27.5590, 53.9051, 27.5590), 10);
	}

	@Test
	public void urls() {
		assertEquals("https://api.open-meteo.com/v1/forecast?latitude=53.9006&longitude=27.5590&current=temperature_2m",
				Termometr.weatherUrl(53.9006, 27.559));
		assertTrue(Termometr.placeUrl(53.7383, 27.5044).contains("lat=53.73830&lon=27.50440"));
		assertTrue(Termometr.placeUrl(53.7383, 27.5044).contains("accept-language=ru"));
	}
}
