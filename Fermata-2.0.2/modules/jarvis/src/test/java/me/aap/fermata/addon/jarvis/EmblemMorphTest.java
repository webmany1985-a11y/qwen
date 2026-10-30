package me.aap.fermata.addon.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EmblemMorphTest {

	@Test
	public void idleGoesToEmblemWorkToSphere() {
		float m = 0f;
		// Касание: за 0,9 с — сфера
		for (int i = 0; i < 18; i++) m = EmblemMorph.step(m, false, 0.05f);
		assertEquals(1f, m, 1e-4f);
		// Разговор окончен — обратно в значок
		for (int i = 0; i < 18; i++) m = EmblemMorph.step(m, true, 0.05f);
		assertEquals(0f, m, 1e-4f);
	}

	@Test
	public void emblemFadesFirstSphereAppearsLast() {
		assertEquals(1f, EmblemMorph.emblemAlpha(0f), 1e-4f);
		assertEquals(0f, EmblemMorph.emblemAlpha(0.3f), 1e-4f);
		assertEquals(0f, EmblemMorph.hudVisibility(0.5f), 1e-4f);
		assertEquals(1f, EmblemMorph.hudVisibility(1f), 1e-4f);
		// Искры — только посреди превращения
		assertEquals(0f, EmblemMorph.sparksAlpha(0f), 1e-4f);
		assertEquals(0f, EmblemMorph.sparksAlpha(1f), 1e-4f);
		assertEquals(1f, EmblemMorph.sparksAlpha(0.5f), 1e-4f);
	}

	@Test
	public void fireLivesOnlyOutsideRingEmblemStill() {
		float[] out = new float[2];
		boolean moved = false;
		for (float a = 0f; a < 6.3f; a += 0.3f) {
			for (float t = 0f; t < 3f; t += 0.5f) {
				// Значок (внутри кольца) — неподвижен
				EmblemMorph.flame(a, 0.5f, t, 0f, out);
				assertEquals(0f, out[0], 0f);
				assertEquals(0f, out[1], 0f);
				// Пламя снаружи — колышется, но в пределах
				EmblemMorph.flame(a, 1.3f, t, 0f, out);
				assertTrue(Math.abs(out[0]) <= 0.081f && Math.abs(out[1]) <= 0.041f);
				if (Math.abs(out[0]) > 0.01f) moved = true;
			}
		}
		assertTrue(moved);
	}

	@Test
	public void flickerStaysBright() {
		for (float t = 0f; t < 10f; t += 0.07f) {
			float f = EmblemMorph.flicker(t, 1f);
			assertTrue(f >= 0.8f && f <= 1f);
		}
	}

	@Test
	public void fireBurnsRisesAndDies() {
		EmblemMorph.Fire f = new EmblemMorph.Fire();
		for (int i = 0; i < 60; i++) f.step(1f / 30f);
		int alive = 0;
		double sumY = 0;
		for (int i = 0; i < EmblemMorph.Fire.MAX; i++) {
			if (!f.alive[i]) continue;
			alive++;
			sumY += f.vy[i];
			assertTrue(f.alpha(i) >= 0f && f.alpha(i) <= 0.15f);
		}
		// Горит постоянно: сотни языков; в среднем всплывают (ось y вниз)
		assertTrue(alive > 300);
		assertTrue(sumY / alive < 0);
		// Цвет: начало жёлто-белое, конец тёмный
		assertTrue((EmblemMorph.fireColor(0f) & 0xFF) > 100);
		assertTrue(((EmblemMorph.fireColor(1f) >> 16) & 0xFF) < 100);
	}

	@Test
	public void sparksStartOnEmblemEndOnSphere() {
		// Картинка 10×10: яркий только центр 2×2
		int[] px = new int[100];
		for (int y = 4; y < 6; y++) for (int x = 4; x < 6; x++) px[y * 10 + x] = 0xFFFFFFFF;
		EmblemMorph.Sparks s = EmblemMorph.sparks(px, 10, 10, 50);
		assertEquals(4, s.count);
		float[] out = new float[2];
		for (int j = 0; j < s.count; j++) {
			EmblemMorph.position(s, j, 0f, 100f, 200f, out);
			// В начале — в центре значка (не дальше пикселя картинки, в радиусах кольца)
			assertTrue(Math.hypot(out[0], out[1]) < 100f * 1.5f / (EmblemMorph.RING_OF_SIDE * 10f));
			EmblemMorph.position(s, j, 1f, 100f, 200f, out);
			// В конце — внутри круга сферы
			assertTrue(Math.hypot(out[0], out[1]) <= 200f + 1e-3f);
		}
	}
}
