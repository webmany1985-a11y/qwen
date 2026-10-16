package me.aap.fermata.addon.jarvis;

import static java.lang.Math.abs;
import static java.lang.Math.floor;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Математика круга JARVIS без Android: параметры колец, сглаживание громкости,
 * раскрытие по времени. Рисует по ней {@link JarvisHudView}.
 *
 * Перенос один в один из JARVIS (android-team, prilozheniya/jarvis,
 * ui/hud/HudMath.kt): круг во вкладке Fermata выглядит так же, как на телефоне.
 * Не перенесено только то, что во вкладке не нужно: закрытие окна и касание
 * вне круга — вкладка не закрывается.
 */
public final class HudMath {

	/** Что сейчас делает ассистент. Как AssistantUiState.Phase в JARVIS. */
	public enum Phase {
		/** Ждём команды. */
		IDLE,
		/** Круг раскрывается. */
		APPEAR,
		/** Записывается команда пользователя. */
		LISTEN,
		/** Ждём ответ JARVIS. */
		THINK,
		/** Ответ произносится. */
		SPEAK
	}

	/** За сколько секунд кольца раскрываются из центра. */
	public static final float OPEN_SECONDS = 1.6f;

	/** Предельный шаг времени между кадрами: после паузы анимация не прыгает. */
	public static final float MAX_DT = 0.05f;

	static final float TWO_PI = (float) (2.0 * Math.PI);

	/** Кольцо: радиус (доля от меньшей стороны), толщина, скорость (об/с), дуги, засечки. */
	public static final class Ring {
		public final float r;
		public final float w;
		public final float v;
		/** Дуги: пары «начало, длина» в долях круга. */
		public final float[] segs;
		public final int ticks;
		public final float tickLen;
		/** Длина штриха пунктира в долях; 0 — сплошная дуга. */
		public final float dash;
		public final float alpha;

		Ring(float r, float w, float v, float[] segs, int ticks, float tickLen, float dash,
				 float alpha) {
			this.r = r;
			this.w = w;
			this.v = v;
			this.segs = segs;
			this.ticks = ticks;
			this.tickLen = tickLen;
			this.dash = dash;
			this.alpha = alpha;
		}
	}

	/** Узел сетки: угол, расстояние от центра (доля), размер точки. */
	public static final class Node {
		public final float a;
		public final float d;
		public final float s;

		Node(float a, float d, float s) {
			this.a = a;
			this.d = d;
			this.s = s;
		}
	}

	public static final Ring[] RINGS = {
			new Ring(0.47f, 1.2f, 0.010f, new float[]{0f, 1f}, 120, 0.012f, 0f, 0.35f),
			new Ring(0.43f, 5f, -0.035f,
					new float[]{0.02f, 0.18f, 0.27f, 0.06f, 0.38f, 0.22f, 0.66f, 0.04f, 0.74f, 0.2f},
					0, 0f, 0f, 0.95f),
			new Ring(0.395f, 1f, 0.06f, new float[]{0f, 0.42f, 0.5f, 0.42f}, 0, 0f, 0.01f, 0.6f),
			new Ring(0.355f, 9f, 0.022f,
					new float[]{0.05f, 0.12f, 0.22f, 0.12f, 0.39f, 0.12f, 0.56f, 0.12f, 0.73f, 0.12f,
							0.9f, 0.06f},
					0, 0f, 0f, 0.9f),
			new Ring(0.31f, 1.4f, -0.09f, new float[]{0f, 0.7f}, 60, 0.02f, 0f, 0.55f),
			new Ring(0.265f, 3f, 0.14f, new float[]{0.1f, 0.3f, 0.6f, 0.3f}, 0, 0f, 0f, 0.85f),
			new Ring(0.22f, 1f, -0.05f, new float[]{0f, 1f}, 0, 0f, 0f, 0.4f),
	};

	public static final Node[] NODES = new Node[26];

	static {
		for (int i = 0; i < NODES.length; i++) {
			NODES[i] = new Node(
					(i / 26f) * TWO_PI + (float) Math.sin(i * 7.1f) * 0.3f,
					0.5f + ((i * 37) % 11) / 30f,
					0.02f + ((i * 13) % 7) / 600f);
		}
	}

	/**
	 * Фрагмент «голограммы» на поверхности сферы (образец — золотая сфера
	 * JARVIS из фильма): широта и долгота в радианах, длина штриха по долготе,
	 * фаза мерцания, толщина.
	 */
	public static final class Bit {
		public final float lat;
		public final float lon;
		public final float len;
		public final float phase;
		public final float w;

		Bit(float lat, float lon, float len, float phase, float w) {
			this.lat = lat;
			this.lon = lon;
			this.len = len;
			this.phase = phase;
			this.w = w;
		}
	}

	/** Наклон оси сферы к зрителю: широты видны эллипсами. */
	public static final float SPHERE_TILT = 0.38f;

	public static final Bit[] BITS = new Bit[300];

	static {
		for (int i = 0; i < BITS.length; i++) {
			// Детерминированный разброс: одинаковый рисунок при каждом запуске
			float a = (float) Math.sin(i * 12.9898) * 43758.5453f;
			float r1 = a - (float) Math.floor(a);
			float b = (float) Math.sin(i * 78.233) * 12543.531f;
			float r2 = b - (float) Math.floor(b);
			float lat = (float) Math.asin(2f * r1 - 1f) * 0.92f;
			float lon = r2 * TWO_PI;
			BITS[i] = new Bit(lat, lon, 0.04f + ((i * 7) % 11) / 90f, (i * 1.37f) % TWO_PI,
					0.6f + ((i * 5) % 4) * 0.45f);
		}
	}

	/**
	 * Проекция точки сферы (широта, долгота) при повороте вокруг вертикальной
	 * оси на rot и наклоне {@link #SPHERE_TILT}: out[0], out[1] — смещение от
	 * центра в долях радиуса (по модулю не больше 1), out[2] — глубина:
	 * больше нуля — передняя половина.
	 */
	public static void project(float lat, float lon, float rot, float[] out) {
		double cl = Math.cos(lat);
		double x = cl * Math.sin(lon + rot);
		double y = Math.sin(lat);
		double z = cl * Math.cos(lon + rot);
		double ct = Math.cos(SPHERE_TILT), st = Math.sin(SPHERE_TILT);
		out[0] = (float) x;
		out[1] = (float) (-(y * ct - z * st));
		out[2] = (float) (y * st + z * ct);
	}

	/** Радиус сферы: дышит с громкостью, раскрывается вместе с кругом. */
	public static float sphereRadius(float size, float level, float open) {
		return max(0f, size * (0.32f + level * 0.04f) * (0.5f + 0.5f * open));
	}

	/** Яркость фрагмента: мерцание по фазе, сзади — тусклее. */
	public static float bitAlpha(Bit b, float t, float depth, float level) {
		float flicker = 0.55f + 0.45f * (float) Math.sin(t * 3.1f + b.phase);
		float side = (depth >= 0f) ? 1f : 0.28f;
		return clamp(flicker * side * (0.6f + 0.4f * level), 0f, 1f);
	}

	private HudMath() {
	}

	static float clamp(float v, float lo, float hi) {
		return (v < lo) ? lo : (v > hi) ? hi : v;
	}

	/** Начальные обороты колец: разные, но одни и те же при каждом запуске. */
	public static float initialRotation(int i) {
		return (i * 0.37f + 0.11f) % 1f;
	}

	/** Шаг времени кадра в секундах: не меньше нуля и не больше {@link #MAX_DT}. */
	public static float clampDt(float rawSeconds) {
		return clamp(rawSeconds, 0f, MAX_DT);
	}

	/** Секунды от startNanos до nowNanos; отрицательным не бывает. */
	public static float sinceSeconds(long nowNanos, long startNanos) {
		return max(0L, nowNanos - startNanos) / 1_000_000_000f;
	}

	/** Раскрытие 0..1 по времени с показа. */
	public static float open(float sinceSeconds) {
		return clamp(sinceSeconds / OPEN_SECONDS, 0f, 1f);
	}

	/** Сглаживание громкости: вверх быстрее (18/с), вниз медленнее (6/с). */
	public static float smoothLevel(float level, float target, float dt) {
		float k = min(1f, max(0f, dt) * ((target > level) ? 18f : 6f));
		return level + (target - level) * k;
	}

	/** К чему стремится громкость в фазе: настоящий звук или условное «дыхание». */
	public static float targetLevel(Phase phase, float realLevel, float t) {
		switch (phase) {
			case LISTEN:
				return clamp(realLevel, 0f, 1f) * 0.9f;
			case SPEAK:
				return clamp(realLevel, 0f, 1f);
			case THINK:
				return 0.18f + 0.08f * (float) Math.sin(t * 6f);
			default:
				return 0.1f;
		}
	}

	/**
	 * Условная громкость, пока Fermata произносит ответ: синтез речи громкость
	 * не отдаёт, поэтому — ровная пульсация в ритме речи.
	 */
	public static float speakLevel(float t) {
		return 0.35f + 0.3f * abs((float) Math.sin(t * 7f)) * (0.6f + 0.4f * abs((float) Math.sin(t * 2.3f)));
	}

	/** Во сколько раз быстрее вращение: при появлении и «думаю» — быстрее. */
	public static float wantSpin(Phase phase, float sinceSeconds) {
		if (phase == Phase.THINK) return 3.2f;
		if ((phase == Phase.APPEAR) || (sinceSeconds < OPEN_SECONDS)) return 1.6f;
		return 1f;
	}

	public static float smoothSpin(float spin, float want, float dt) {
		return spin + (want - spin) * min(1f, max(0f, dt) * 3f);
	}

	/** Раскрытие кольца номер i: кольца выходят из центра по очереди. */
	public static float ringAppear(float open, int i) {
		return clamp(open * 1.6f - i * 0.09f, 0f, 1f);
	}

	/** Радиус кольца в пикселях, не меньше нуля. */
	public static float ringRadius(float size, Ring ring, int i, float level, float appear) {
		float pulse = 1f + level * (0.05f + (i % 2) * 0.04f);
		return max(0f, size * ring.r * pulse * (0.6f + 0.4f * appear));
	}

	public static float ringAlpha(Ring ring, float appear, float level) {
		return clamp(ring.alpha * appear * (0.75f + level * 0.25f), 0f, 1f);
	}

	/** Следующий оборот кольца, приведённый в [0, 1). */
	public static float nextRotation(float rot, Ring ring, float dt, float spin, float level) {
		float next = rot + ring.v * max(0f, dt) * spin * (1f + level * 0.6f);
		return next - (float) floor(next);
	}

	/** Радиус свечения ядра; не меньше 0,1 пикселя, градиент не терпит нуля. */
	public static float coreRadius(float size, float level, float open) {
		return max(0.1f, size * (0.16f + level * 0.12f) * open);
	}

	/** Горизонтальная вспышка при появлении: пик на 1,2 с после показа. */
	public static float introFlare(float sinceSeconds) {
		return max(0f, 1f - abs(sinceSeconds - 1.2f) * 2f);
	}

	/** Сила вспышки: при появлении — по времени, дальше — от громкости. */
	public static float flare(Phase phase, float sinceSeconds, float level) {
		float byLevel = (phase == Phase.APPEAR) ? 0f : level * 0.5f;
		return clamp(max(introFlare(sinceSeconds), byLevel), 0f, 1f);
	}

	public static float nodeDistance(float size, Node node, float open) {
		return max(0f, size * node.d * (0.8f + 0.2f * open));
	}

	/** Высота столбика эквалайзера номер k. */
	public static float equalizerHeight(float size, float level, float t, int k) {
		return size * (0.006f + level * 0.05f * (0.4f + 0.6f * abs((float) Math.sin(t * 9f + k * 1.7f))));
	}

	public static boolean showsEqualizer(Phase phase) {
		return (phase == Phase.LISTEN) || (phase == Phase.SPEAK);
	}

	public static boolean showsScan(Phase phase) {
		return phase == Phase.THINK;
	}

	/**
	 * Громкость микрофона 0..1 из RecognitionListener.onRmsChanged. Значения
	 * распознавателя — примерно от −2 дБ (тишина) до 10 дБ (громкая речь).
	 */
	public static float levelFromRmsDb(float rmsDb) {
		return clamp((rmsDb + 2f) / 12f, 0f, 1f);
	}

	/**
	 * Изменяемое состояние анимации между кадрами: обороты колец, сглаженная
	 * громкость и ускорение вращения. Без Android — проверяется на JVM.
	 */
	public static final class Animator {
		public final float[] rotations = new float[RINGS.length];
		private float level;
		private float spin = 1f;

		public Animator() {
			for (int i = 0; i < rotations.length; i++) rotations[i] = initialRotation(i);
		}

		public float getLevel() {
			return level;
		}

		public float getSpin() {
			return spin;
		}

		/** Один кадр. t — секунды с показа, dt — шаг, уже зажатый в 0..MAX_DT. */
		public void advance(Phase phase, float realLevel, float t, float dt) {
			level = smoothLevel(level, targetLevel(phase, realLevel, t), dt);
			spin = smoothSpin(spin, wantSpin(phase, t), dt);
			for (int i = 0; i < rotations.length; i++) {
				rotations[i] = nextRotation(rotations[i], RINGS[i], dt, spin, level);
			}
		}
	}
}
