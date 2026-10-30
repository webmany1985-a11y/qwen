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
	public void flameMovesOnlyOutsideRing() {
		for (float a = 0f; a < 6.3f; a += 0.3f) {
			assertEquals(0f, EmblemMorph.flame(a, 0.5f, 3f), 0f);
			assertTrue(Math.abs(EmblemMorph.flame(a, 1.3f, 3f)) <= 0.05f);
		}
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
