package me.aap.fermata.addon.jarvis;

import static me.aap.fermata.addon.jarvis.HudMath.clamp;

import java.util.Random;

/**
 * Значок Opel в огне в покое и его превращение в сферу (пожелание пользователя 30.10.2026: «при нажатии значок
 * разлетается на нынешнюю сферу»). Уточнение того же дня: чёрного фона нет, сам значок не пульсирует — живёт только
 * огонь; пульсация — у сферы. Чистая математика без Android — проверяется на JVM ({@code EmblemMorphTest});
 * рисует {@link JarvisHudView}.
 *
 * <p>Превращение — величина {@code m}: 0 — значок, 1 — сфера. В покое (фаза IDLE) она идёт к 0, в остальных фазах — к 1,
 * за {@link #MORPH_SECONDS}. Значок гаснет в начале превращения, искры, взятые из его ярких точек, разлетаются и
 * собираются в круг сферы, сфера с кольцами проявляется в конце. Обратно — то же в обратном порядке.
 */
public final class EmblemMorph {
	/** За сколько секунд значок становится сферой (и обратно). */
	public static final float MORPH_SECONDS = 0.9f;
	/** Радиус огненного кольца на картинке значка — доля стороны картинки (jarvis_opel_*.webp, замер по кадру). */
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

	/**
	 * Мерцание огня: яркость слоя пламени, неровная, как у живого огня, — сумма колебаний с несоизмеримыми
	 * частотами. {@code seed} разводит слои, чтобы они мерцали не в такт. Значения 0,8..1.
	 */
	public static float flicker(float t, float seed) {
		double v = Math.sin(t * 7.3 + seed) * 0.5 + Math.sin(t * 11.9 + seed * 2.1) * 0.3
				+ Math.sin(t * 2.3 + seed * 0.7) * 0.2;
		return 0.9f + 0.1f * (float) v;
	}

	/**
	 * Живой огонь: смещение узла сетки слоя пламени, в долях радиуса огненного кольца. {@code out[0]} — по радиусу
	 * (языки вытягиваются и опадают), {@code out[1]} — вдоль кольца (колышутся). Волны бегут от кольца наружу, как
	 * поднимается пламя. Внутри кольца (rn < 0,75) — 0: значок не двигается.
	 */
	public static void flame(float angle, float rn, float t, float seed, float[] out) {
		if (rn < 0.75f) {
			out[0] = 0f;
			out[1] = 0f;
			return;
		}
		float k = smooth(0.75f, 1.3f, rn);
		double a = angle, r = rn;
		double radial = Math.sin(a * 5 + r * 9 - t * 3.1 + seed) * 0.55
				+ Math.sin(a * 9 - r * 13 - t * 4.7 + seed * 1.7) * 0.3
				+ Math.sin(a * 17 + r * 21 - t * 7.9 + seed * 2.9) * 0.15;
		double along = Math.sin(a * 4 - r * 7 - t * 2.3 + seed * 0.5) * 0.6
				+ Math.sin(a * 11 + r * 15 - t * 5.3 + seed * 1.3) * 0.4;
		out[0] = 0.08f * k * (float) radial;
		out[1] = 0.04f * k * (float) along;
	}

	/**
	 * Живое пламя (замечание 30.10.2026: «огонь должен не просто колыхаться, а гореть»): языки — частицы, которые
	 * постоянно рождаются на огненном кольце, вылетают наружу, всплывают вверх, их сносит и закручивает; по возрасту
	 * цвет идёт от жёлто-белого к оранжевому, красному и гаснет. Координаты — в радиусах огненного кольца от центра,
	 * ось y вниз (как на экране). Параметры подобраны на кадрах предпросмотра.
	 */
	public static final class Fire {
		/** Частиц в секунду. */
		public static final float RATE = 750f;
		public static final int MAX = 1500;
		public final float[] x = new float[MAX], y = new float[MAX], vx = new float[MAX], vy = new float[MAX];
		public final float[] age = new float[MAX], life = new float[MAX], size = new float[MAX];
		public final boolean[] alive = new boolean[MAX];
		private final Random rnd = new Random(3010L);
		private float due;
		private float t;

		/** Шаг времени dt (с): родить новые, сдвинуть, состарить. */
		public void step(float dt) {
			t += dt;
			due += RATE * dt;
			int born = (int) due;
			due -= born;
			for (int i = 0; (i < MAX) && (born > 0); i++) {
				if (alive[i]) continue;
				born--;
				double th = rnd.nextDouble() * Math.PI * 2.0;
				double r = 1.0 + rnd.nextGaussian() * 0.05;
				double out = 0.15 + rnd.nextDouble() * 0.3;
				x[i] = (float) (Math.cos(th) * r);
				y[i] = (float) (Math.sin(th) * r);
				vx[i] = (float) (Math.cos(th) * out);
				vy[i] = (float) (Math.sin(th) * out - 0.35);
				age[i] = 0f;
				life[i] = 0.6f + rnd.nextFloat() * 0.65f;
				size[i] = 0.05f + rnd.nextFloat() * 0.04f;
				alive[i] = true;
			}
			for (int i = 0; i < MAX; i++) {
				if (!alive[i]) continue;
				vy[i] -= 2.4f * dt; // всплывает
				float swirl = (float) (Math.sin(y[i] * 7f + t * 5.3f) + 0.5 * Math.sin(y[i] * 13f - t * 8.1f + x[i] * 3f));
				vx[i] += swirl * 2.2f * dt;
				vx[i] *= 0.985f;
				vy[i] *= 0.99f;
				x[i] += vx[i] * dt;
				y[i] += vy[i] * dt;
				age[i] += dt;
				if (age[i] >= life[i]) alive[i] = false;
			}
		}

		/** Доля прожитого 0..1. */
		public float progress(int i) {
			return clamp(age[i] / life[i], 0f, 1f);
		}

		/**
		 * Яркость частицы: гаснет к концу жизни; над значком (ближе 0,82 радиуса кольца) не видна — языки снизу не
		 * заслоняют хром.
		 */
		public float alpha(int i) {
			float u = progress(i);
			float r = (float) Math.sqrt(x[i] * x[i] + y[i] * y[i]);
			return (float) Math.pow(1f - u, 1.3) * 0.14f * clamp((r - 0.82f) / 0.12f, 0f, 1f);
		}

		/** Радиус пятна частицы: растёт к середине жизни. */
		public float radius(int i) {
			float u = progress(i);
			return size[i] * (0.7f + 0.8f * (float) Math.sin(Math.PI * Math.min(1f, u * 1.3f)));
		}
	}

	/** Цвет пламени по возрасту u 0..1 (ARGB, непрозрачный): жёлто-белый → оранжевый → красный → тёмный. */
	public static int fireColor(float u) {
		float[][] stops = {{0f, 1f, 0.9f, 0.55f}, {0.15f, 1f, 0.7f, 0.2f}, {0.4f, 1f, 0.42f, 0.05f},
				{0.7f, 0.8f, 0.15f, 0.02f}, {1f, 0.25f, 0.03f, 0f}};
		float q = clamp(u, 0f, 1f);
		for (int i = 0; i + 1 < stops.length; i++) {
			float[] a = stops[i], b = stops[i + 1];
			if (q <= b[0]) {
				float k = (q - a[0]) / (b[0] - a[0]);
				int r = Math.round(255f * (a[1] + (b[1] - a[1]) * k));
				int g = Math.round(255f * (a[2] + (b[2] - a[2]) * k));
				int bl = Math.round(255f * (a[3] + (b[3] - a[3]) * k));
				return 0xFF000000 | (r << 16) | (g << 8) | bl;
			}
		}
		return 0xFF400800;
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
