package me.aap.fermata.addon.jarvis;

import static me.aap.fermata.addon.jarvis.HudMath.clamp;

import java.util.Random;

/**
 * Значок Opel в огне в покое и его превращение в сферу (пожелание пользователя 30.10.2026: «в состоянии покоя
 * пульсирующий значок Опель как на фото; при нажатии значок разлетается на нынешнюю сферу»). Чистая математика без
 * Android — проверяется на JVM ({@code EmblemMorphTest}); рисует {@link JarvisHudView}.
 *
 * <p>Превращение — величина {@code m}: 0 — значок, 1 — сфера. В покое (фаза IDLE) она идёт к 0, в остальных фазах — к 1,
 * за {@link #MORPH_SECONDS}. Значок гаснет в начале превращения, искры, взятые из его ярких точек, разлетаются и
 * собираются в круг сферы, сфера с кольцами проявляется в конце. Обратно — то же в обратном порядке.
 */
public final class EmblemMorph {
	/** За сколько секунд значок становится сферой (и обратно). */
	public static final float MORPH_SECONDS = 0.9f;
	/** Радиус огненного кольца на картинке значка — доля стороны картинки (jarvis_opel.webp, замер по кадру). */
	public static final float RING_OF_SIDE = 0.37f;
	/** Радиус огненного кольца на экране — доля меньшей стороны вида. */
	public static final float RING_OF_VIEW = 0.27f;
	/** Сколько искр берётся из значка. */
	public static final int SPARKS = 700;

	private EmblemMorph() {
	}

	/** Следующее значение превращения: к 0 в покое, к 1 в работе. */
	public static float step(float m, boolean idle, float dt) {
		float d = dt / MORPH_SECONDS;
		return idle ? Math.max(0f, m - d) : Math.min(1f, m + d);
	}

	/** Мягкий переход 0..1 между краями a и b. */
	static float smooth(float a, float b, float x) {
		float t = clamp((x - a) / (b - a), 0f, 1f);
		return t * t * (3f - 2f * t);
	}

	/** Яркость значка: гаснет в первой трети превращения. */
	public static float emblemAlpha(float m) {
		return 1f - smooth(0f, 0.3f, m);
	}

	/** Видимость сферы с кольцами: проявляется в последней половине. */
	public static float hudVisibility(float m) {
		return smooth(0.5f, 1f, m);
	}

	/** Яркость искр: есть только посреди превращения. */
	public static float sparksAlpha(float m) {
		return clamp(m * 5f, 0f, 1f) * clamp((1f - m) * 4f, 0f, 1f);
	}

	/** Пульс значка в покое: масштаб около 1, вдох-выдох раз в {@code 1.6} с. */
	public static float pulse(float t) {
		return 1f + 0.035f * (float) Math.sin(t * Math.PI * 2.0 / 1.6);
	}

	/** Яркость значка в покое: дышит вместе с пульсом, 0,85..1. */
	public static float glow(float t) {
		return 0.925f + 0.075f * (float) Math.sin(t * Math.PI * 2.0 / 1.6);
	}

	/**
	 * Колыхание пламени: смещение узла сетки картинки по радиусу, доля. Внутри огненного кольца (rn < 0,85, где rn —
	 * расстояние в радиусах кольца) — 0: сам значок не плывёт, колышутся только языки огня снаружи.
	 */
	public static float flame(float angle, float rn, float t) {
		if (rn < 0.85f) return 0f;
		float k = clamp((rn - 0.85f) / 0.5f, 0f, 1f);
		float w = (float) (Math.sin(angle * 3f + t * 2.1f) + 0.6 * Math.sin(angle * 7f - t * 3.3f)
				+ 0.4 * Math.sin(angle * 11f + t * 5.2f));
		return 0.045f * k * w / 2f;
	}

	/** Искры. Координаты — в радиусах: from — от центра значка в радиусах огненного кольца, to — в радиусах сферы. */
	public static final class Sparks {
		public final int count;
		public final float[] fromX, fromY, toX, toY, dirX, dirY, delay;
		public final int[] color;

		Sparks(int n) {
			count = n;
			fromX = new float[n];
			fromY = new float[n];
			toX = new float[n];
			toY = new float[n];
			dirX = new float[n];
			dirY = new float[n];
			delay = new float[n];
			color = new int[n];
		}
	}

	/**
	 * Искры из ярких точек картинки значка (ARGB, {@code w}×{@code h}). Точка берётся, если её яркость выше порога;
	 * из подходящих — равномерно {@link #SPARKS} штук (случайность с постоянным зерном — одна и та же картина).
	 * Цель — точка на видимой половине единичной сферы (гуще у края — читается как шар).
	 */
	public static Sparks sparks(int[] argb, int w, int h, int max) {
		int[] idx = new int[argb.length];
		int n = 0;
		for (int i = 0; i < argb.length; i++) {
			int c = argb[i];
			int lum = ((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF);
			if (lum > 1400) idx[n++] = i;
		}
		Random r = new Random(30_10_2026L);
		int k = Math.min(max, n);
		Sparks s = new Sparks(k);
		float ringPx = RING_OF_SIDE * w;
		for (int j = 0; j < k; j++) {
			int i = idx[(int) ((long) j * n / Math.max(1, k))];
			float x = ((i % w) - w / 2f) / ringPx;
			float y = ((i / w) - h / 2f) / ringPx;
			s.fromX[j] = x;
			s.fromY[j] = y;
			s.color[j] = argb[i] | 0xFF000000;
			// Цель: точка единичной сферы (z ≥ 0), проекция на экран
			double u = r.nextDouble() * 2.0 - 1.0;
			double phi = r.nextDouble() * Math.PI * 2.0;
			double q = Math.sqrt(1.0 - u * u);
			s.toX[j] = (float) (q * Math.cos(phi));
			s.toY[j] = (float) u;
			// Разлёт: от центра значка, с разбросом
			double a = Math.atan2(y, x) + (r.nextDouble() - 0.5) * 1.2;
			double len = 0.5 + r.nextDouble();
			s.dirX[j] = (float) (Math.cos(a) * len);
			s.dirY[j] = (float) (Math.sin(a) * len);
			s.delay[j] = (float) r.nextDouble();
		}
		return s;
	}

	/**
	 * Положение искры j при превращении m, в пикселях от центра: ringPx — радиус огненного кольца на экране,
	 * sphereR — радиус сферы. Каждая искра стартует со своей задержкой, летит по дуге (разлёт наружу) и садится на сферу.
	 */
	public static void position(Sparks s, int j, float m, float ringPx, float sphereR, float[] out) {
		float p = smooth(0f, 1f, (m - s.delay[j] * 0.3f) / 0.7f);
		float burst = (float) Math.sin(Math.PI * p) * 0.6f * Math.max(ringPx, sphereR);
		out[0] = s.fromX[j] * ringPx * (1f - p) + s.toX[j] * sphereR * p + s.dirX[j] * burst;
		out[1] = s.fromY[j] * ringPx * (1f - p) + s.toY[j] * sphereR * p + s.dirY[j] * burst;
	}
}
