package me.aap.fermata.addon.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import me.aap.fermata.addon.jarvis.HudMath.Phase;

/**
 * Математика круга. Проверки перенесены из JARVIS (HudMathTest.kt), чтобы
 * перенос на Java ничего не поменял; добавлены громкость микрофона и
 * пульсация при ответе — их в JARVIS нет.
 */
public class HudMathTest {

	@Test
	public void timeNeverNegative() {
		assertEquals(0f, HudMath.sinceSeconds(100L, 5_000_000_000L), 0f);
		assertEquals(0f, HudMath.open(-3f), 0f);
		assertEquals(0f, HudMath.clampDt(-1f), 0f);
		assertEquals(HudMath.MAX_DT, HudMath.clampDt(10f), 0f);
	}

	@Test
	public void radiiNeverNegative() {
		for (float since : new float[]{-5f, 0f, 0.3f, 1f, 10f}) {
			float open = HudMath.open(since);
			for (int i = 0; i < HudMath.RINGS.length; i++) {
				float appear = HudMath.ringAppear(open, i);
				assertTrue(HudMath.ringRadius(500f, HudMath.RINGS[i], i, 0f, appear) >= 0f);
				assertTrue(HudMath.ringRadius(500f, HudMath.RINGS[i], i, 1f, appear) >= 0f);
			}
			assertTrue(HudMath.coreRadius(500f, 0f, open) > 0f);
			for (HudMath.Node n : HudMath.NODES) assertTrue(HudMath.nodeDistance(500f, n, open) >= 0f);
		}
	}

	@Test
	public void ringsOpenInTurnAndFully() {
		assertEquals(0f, HudMath.open(0f), 0f);
		assertEquals(1f, HudMath.open(HudMath.OPEN_SECONDS), 0f);
		assertEquals(1f, HudMath.open(99f), 0f);
		assertEquals(0f, HudMath.ringAppear(0f, 0), 0f);
		assertTrue(HudMath.ringAppear(0.3f, 0) > HudMath.ringAppear(0.3f, 6));
		for (int i = 0; i < HudMath.RINGS.length; i++) assertEquals(1f, HudMath.ringAppear(1f, i), 0f);
	}

	@Test
	public void levelRisesFasterThanFalls() {
		float up = HudMath.smoothLevel(0f, 1f, 0.016f);
		float down = 1f - HudMath.smoothLevel(1f, 0f, 0.016f);
		assertTrue(up > down);
		assertEquals(0.5f, HudMath.smoothLevel(0.5f, 0.5f, 0.016f), 0f);
		assertEquals(1f, HudMath.smoothLevel(0f, 1f, 100f), 0f);
		assertEquals(0.2f, HudMath.smoothLevel(0.2f, 1f, -1f), 0f);
	}

	@Test
	public void targetLevelByPhase() {
		assertEquals(0.9f, HudMath.targetLevel(Phase.LISTEN, 1f, 0f), 1e-6f);
		assertEquals(1f, HudMath.targetLevel(Phase.SPEAK, 5f, 0f), 0f);
		assertEquals(0.1f, HudMath.targetLevel(Phase.APPEAR, 1f, 0f), 0f);
		float think = HudMath.targetLevel(Phase.THINK, 0f, 1f);
		assertTrue(think >= 0.1f && think <= 0.26f);
	}

	@Test
	public void spinFasterWhenAppearingAndThinking() {
		assertEquals(3.2f, HudMath.wantSpin(Phase.THINK, 5f), 0f);
		assertEquals(1.6f, HudMath.wantSpin(Phase.APPEAR, 5f), 0f);
		assertEquals(1.6f, HudMath.wantSpin(Phase.LISTEN, 0.5f), 0f);
		assertEquals(1f, HudMath.wantSpin(Phase.LISTEN, 5f), 0f);
	}

	@Test
	public void animatorStaysInBounds() {
		HudMath.Animator a = new HudMath.Animator();
		Phase[] phases = Phase.values();
		float t = 0f;
		for (int n = 0; n < 2000; n++) {
			float dt = (n == 0) ? -1f : 0.016f;
			t += 0.016f;
			a.advance(phases[n % phases.length], (n % 7) / 6f, t, HudMath.clampDt(dt));
			assertTrue(a.getLevel() >= 0f && a.getLevel() <= 1f);
			for (float r : a.rotations) assertTrue(r >= 0f && r < 1f);
		}
	}

	@Test
	public void introFlarePeaksAt1_2s() {
		assertEquals(0f, HudMath.introFlare(0f), 0f);
		assertEquals(1f, HudMath.introFlare(1.2f), 1e-6f);
		assertEquals(0f, HudMath.introFlare(3f), 0f);
		assertEquals(0.25f, HudMath.flare(Phase.LISTEN, 5f, 0.5f), 1e-6f);
	}

	@Test
	public void sameGeometryAsJarvis() {
		assertEquals(7, HudMath.RINGS.length);
		assertEquals(26, HudMath.NODES.length);
		for (HudMath.Ring r : HudMath.RINGS) assertEquals(0, r.segs.length % 2);
		assertEquals(120, HudMath.RINGS[0].ticks);
		assertEquals(60, HudMath.RINGS[4].ticks);
	}

	@Test
	public void micLevelFromRms() {
		assertEquals(0f, HudMath.levelFromRmsDb(-10f), 0f);
		assertEquals(0f, HudMath.levelFromRmsDb(-2f), 0f);
		assertEquals(0.5f, HudMath.levelFromRmsDb(4f), 1e-6f);
		assertEquals(1f, HudMath.levelFromRmsDb(10f), 0f);
		assertEquals(1f, HudMath.levelFromRmsDb(30f), 0f);
	}

	@Test
	public void speakLevelPulsesInBounds() {
		float min = 1f;
		float max = 0f;
		for (int i = 0; i < 1000; i++) {
			float l = HudMath.speakLevel(i * 0.01f);
			assertTrue(l >= 0f && l <= 1f);
			min = Math.min(min, l);
			max = Math.max(max, l);
		}
		assertTrue("пульсация должна быть заметной", max - min > 0.2f);
	}

	@Test
	public void sphereProjectionStaysOnDisc() {
		float[] o = new float[3];
		for (HudMath.Bit b : HudMath.BITS) {
			for (float rot = 0f; rot < 7f; rot += 0.7f) {
				HudMath.project(b.lat, b.lon, rot, o);
				assertTrue(o[0] * o[0] + o[1] * o[1] <= 1.0001f);
				assertTrue(Math.abs(o[2]) <= 1.0001f);
			}
		}
		// Точка на экваторе прямо перед зрителем — в передней половине
		HudMath.project(0f, 0f, 0f, o);
		assertTrue(o[2] > 0f);
		assertEquals(0f, o[0], 1e-6f);
	}

	@Test
	public void sphereBreathesWithLevel() {
		assertTrue(HudMath.sphereRadius(500f, 1f, 1f) > HudMath.sphereRadius(500f, 0f, 1f));
		assertEquals(0f, HudMath.sphereRadius(500f, 0f, 0f) - 500f * 0.32f * 0.5f, 1e-4f);
		for (HudMath.Bit b : HudMath.BITS) {
			float a = HudMath.bitAlpha(b, 1.3f, -0.5f, 0.5f);
			assertTrue(a >= 0f && a <= 1f);
			assertTrue(HudMath.bitAlpha(b, 1.3f, 0.5f, 0.5f) >= a);
		}
	}

	@Test
	public void dotCloudIsOnUnitSphereAndTwinkleInBounds() {
		for (int i = 0; i < HudMath.DOTS; i++) {
			float len = HudMath.DX[i] * HudMath.DX[i] + HudMath.DY[i] * HudMath.DY[i] +
					HudMath.DZ[i] * HudMath.DZ[i];
			assertEquals(1f, len, 1e-4f);
			assertTrue(HudMath.DR[i] > 0f && HudMath.DR[i] <= 1f);
			float tw = HudMath.twinkle(HudMath.DP[i], i * 0.01f, 0.5f);
			assertTrue(tw >= 0f && tw <= 1f);
			assertTrue(Math.abs(HudMath.ripple(HudMath.DP[i], HudMath.DY[i], 1f, 1f)) <= 0.06f + 1e-6f);
		}
		assertEquals(0f, HudMath.ripple(1f, 0.5f, 2f, 0f), 0f);
	}

	@Test
	public void imageWarpBoundedCalmAtRestAndCentreFixed() {
		float idleMax = 0f, loudMax = 0f;
		for (int i = 0; i < 2000; i++) {
			float theta = i * 0.0314f;
			float t = i * 0.013f;
			for (float rn = 0f; rn <= 1.4f; rn += 0.2f) {
				float idle = HudMath.warp(theta, rn, t, 0f);
				float loud = HudMath.warp(theta, rn, t, 1f);
				assertTrue(Math.abs(loud) <= HudMath.WARP_MAX);
				idleMax = Math.max(idleMax, Math.abs(idle));
				loudMax = Math.max(loudMax, Math.abs(loud));
			}
			assertEquals(0f, HudMath.warp(theta, 0f, t, 1f), 0f);
		}
		assertTrue("в покое — заметно, но спокойнее", idleMax > 0.03f && idleMax < loudMax);
		assertTrue("с голосом — сильно", loudMax > 0.12f);
		for (float rn = 0f; rn <= 1.4f; rn += 0.05f) {
			for (float t = 0f; t < 20f; t += 0.37f) {
				assertTrue(Math.abs(HudMath.twist(rn, t, 1f)) <= HudMath.TWIST_MAX);
			}
		}
		// Слои на разной глубине повёрнуты по-разному
		float spread = 0f;
		for (float rn = 0f; rn <= 1f; rn += 0.1f)
			spread = Math.max(spread, Math.abs(HudMath.twist(rn, 2f, 0.5f) - HudMath.twist(0f, 2f, 0.5f)));
		assertTrue(spread > 0.1f);
		// Разные участки края в один момент сдвинуты по-разному
		assertTrue(Math.abs(HudMath.warp(0f, 1f, 1f, 1f) - HudMath.warp(1.5f, 1f, 1f, 1f)) > 0.005f);
	}
}
