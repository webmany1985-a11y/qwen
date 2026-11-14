package me.aap.fermata.addon.jarvis;

import static me.aap.fermata.addon.jarvis.Pogoda.Kind.FIRE;
import static me.aap.fermata.addon.jarvis.Pogoda.Kind.FROST;
import static me.aap.fermata.addon.jarvis.Pogoda.Kind.RAIN;
import static me.aap.fermata.addon.jarvis.Pogoda.Kind.SNOW;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Значок Opel по погоде (карточка android-team/tasks/2026-11-14-auto-temperatura-vidzhet). */
public class PogodaTest {

	@Test
	public void kindByWmoCode() {
		// Снег: 71–77, ливневый снег 85, 86
		for (int c : new int[]{71, 73, 75, 77, 85, 86}) assertEquals("код " + c, SNOW, Pogoda.kind(c, -2.0));
		// Дождь, морось, ледяной дождь, ливень, гроза
		for (int c : new int[]{51, 55, 61, 63, 65, 66, 67, 80, 82, 95, 99}) assertEquals("код " + c, RAIN, Pogoda.kind(c, 3.0));
		// Без осадков: ясно, облачно, туман — огонь, а при −5° и ниже — мороз
		for (int c : new int[]{0, 1, 2, 3, 45, 48}) {
			assertEquals(FIRE, Pogoda.kind(c, 3.0));
			assertEquals(FIRE, Pogoda.kind(c, -4.9));
			assertEquals(FROST, Pogoda.kind(c, -5.0));
		}
		// Снег при морозе — всё равно снег
		assertEquals(SNOW, Pogoda.kind(75, -12.0));
		// Кода нет (старое значение в памяти) — по температуре
		assertEquals(FROST, Pogoda.kind(null, -8.0));
		assertEquals(FIRE, Pogoda.kind(null, null));
	}

	@Test
	public void staleWeatherIsFire() {
		long t = 1_000_000_000L;
		assertEquals(SNOW, Pogoda.shown(73, -2.0, t, t + 30 * 60_000L));
		assertEquals(FIRE, Pogoda.shown(73, -2.0, t, t + 61 * 60_000L));
		assertEquals(FIRE, Pogoda.shown(73, -2.0, 0L, t));
	}

	@Test
	public void ringRecolorKeepsAlphaAndTurnsCold() {
		int orange = 0xC0FF9020;
		int[] px = {orange, 0x00000000, 0xFFFFFFF0};
		Pogoda.recolorRing(px, SNOW);
		assertEquals(0xC0, px[0] >>> 24);
		assertTrue("лёд синее, чем красный", (px[0] & 255) > ((px[0] >> 16) & 255));
		assertEquals(0, px[1]);
		// Белое ядро огня — почти белый лёд
		assertTrue(((px[2] >> 16) & 255) > 220);
		// Огонь не трогается
		int[] f = {orange};
		Pogoda.recolorRing(f, FIRE);
		assertArrayEquals(new int[]{orange}, f);
	}

	@Test
	public void badgeFireBecomesIceChromeStaysGrey() {
		int w = 512;
		int[] px = new int[w * w];
		int fireAt = 256 * w + 256 + 60;   // огонь внутри круга значка
		int chromeAt = 256 * w + 256;      // хром в центре
		px[fireAt] = 0xFFE07018;
		px[chromeAt] = 0xFFB0B0B0;
		Pogoda.recolorBadge(px, w, RAIN);
		int f = px[fireAt], c = px[chromeAt];
		assertTrue("огонь в значке стал холодным", (f & 255) > ((f >> 16) & 255));
		int r = (c >> 16) & 255, b = c & 255;
		assertTrue("хром — серый с холодным оттенком", (b > r) && (r > 140));
		assertEquals(0xFF, c >>> 24);
		assertEquals(0, px[0]);
	}

	@Test
	public void particlesStayAroundEmblemAndMove() {
		for (Pogoda.Kind k : new Pogoda.Kind[]{SNOW, RAIN, FROST}) {
			Pogoda.Fx fx = new Pogoda.Fx(k);
			assertTrue(fx.count > 0);
			float y0 = fx.y[0];
			fx.step(0.016f);
			assertTrue(k + " движется", fx.y[0] != y0);
			for (int n = 0; n < 2000; n++) fx.step(0.05f);
			for (int i = 0; i < fx.count; i++) {
				assertTrue(Math.abs(fx.x[i]) <= Pogoda.Fx.HALF + 1e-4f);
				assertTrue(Math.abs(fx.y[i]) <= Pogoda.Fx.HALF + 1e-4f);
				float a = fx.alpha(i);
				assertTrue(a > 0f && a <= 1f);
			}
		}
		assertEquals(0, new Pogoda.Fx(FIRE).count);
	}
}
