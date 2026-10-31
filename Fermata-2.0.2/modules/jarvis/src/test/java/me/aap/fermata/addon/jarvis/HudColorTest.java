package me.aap.fermata.addon.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HudColorTest {

	@Test
	public void tealOnlyWhileListening() {
		assertTrue(HudColor.listening(HudMath.Phase.LISTEN));
		assertFalse(HudColor.listening(HudMath.Phase.IDLE));
		assertFalse(HudColor.listening(HudMath.Phase.APPEAR));
		assertFalse(HudColor.listening(HudMath.Phase.THINK));
		assertFalse(HudColor.listening(HudMath.Phase.SPEAK));
	}

	@Test
	public void fadesWithinFadeSecondsBothWays() {
		float m = 0f;
		// Начал слушать — за 0,3 с бирюза
		for (int i = 0; i < 6; i++) m = HudColor.step(m, true, 0.05f);
		assertEquals(1f, m, 1e-4f);
		m = HudColor.step(m, true, 0.05f);
		assertEquals(1f, m, 0f);
		// Думает — за 0,3 с обратно золото
		for (int i = 0; i < 6; i++) m = HudColor.step(m, false, 0.05f);
		assertEquals(0f, m, 1e-4f);
		m = HudColor.step(m, false, 0.05f);
		assertEquals(0f, m, 0f);
	}

	@Test
	public void lerpEndsAreExact() {
		assertEquals(HudColor.GOLD_ARC, HudColor.lerp(HudColor.GOLD_ARC, HudColor.TEAL_ARC, 0f));
		assertEquals(HudColor.TEAL_ARC, HudColor.lerp(HudColor.GOLD_ARC, HudColor.TEAL_ARC, 1f));
		int mid = HudColor.lerp(0xFF000000, 0xFFFFFFFF, 0.5f);
		assertEquals(0xFF, mid >>> 24);
		assertEquals(128, (mid >> 16) & 0xFF);
	}

	@Test
	public void sphereMatrixIdentityWhenGoldTealWhenListening() {
		float[] id = HudColor.sphereMatrix(0f);
		for (int i = 0; i < 20; i++) {
			float want = (i == 0 || i == 6 || i == 12 || i == 18) ? 1f : 0f;
			assertEquals("элемент " + i, want, id[i], 1e-6f);
		}
		// Золотой пиксель сферы → бирюза: зелёный и синий выше красного
		float[] m = HudColor.sphereMatrix(1f);
		float r = 255, g = 178, b = 58;
		float or = m[0] * r + m[1] * g + m[2] * b;
		float og = m[5] * r + m[6] * g + m[7] * b;
		float ob = m[10] * r + m[11] * g + m[12] * b;
		assertTrue("зелёный > красного: " + og + " / " + or, og > or * 2f);
		assertTrue("синий > красного: " + ob + " / " + or, ob > or * 2f);
		assertTrue("не тусклый: " + og, og > 150f);
	}
}
