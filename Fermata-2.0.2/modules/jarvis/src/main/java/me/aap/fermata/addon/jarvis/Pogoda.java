package me.aap.fermata.addon.jarvis;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;

/**
 * Значок Opel в покое — по погоде (пожелание 14.11.2026, макет android-team/tasks/2026-11-14-auto-temperatura-vidzhet/
 * maket/maket-znachok-pogoda.png): снег — лёд и снежинки, дождь — мокрый блеск и струи, мороз — иней и пар, иначе огонь.
 * Чистая Java: выбор вида по коду погоды WMO (Open-Meteo, weather_code), перекраска пикселей слоёв и частицы.
 */
public final class Pogoda {
	public enum Kind {FIRE, SNOW, RAIN, FROST}

	/** Мороз без осадков — от −5° и ниже. */
	public static final double FROST_C = -5.0;

	private Pogoda() {
	}

	/**
	 * Вид по коду WMO и температуре: снег — 71–77, 85, 86; дождь, морось, ледяной дождь, ливень, гроза — 51–67,
	 * 80–82, 95–99; без осадков при −5° и ниже — мороз; иначе (ясно, облачно, туман, нет данных) — огонь.
	 */
	@NonNull
	public static Kind kind(@Nullable Integer code, @Nullable Double temp) {
		if (code != null) {
			int c = code;
			if ((c >= 71 && c <= 77) || c == 85 || c == 86) return Kind.SNOW;
			if ((c >= 51 && c <= 67) || (c >= 80 && c <= 82) || (c >= 95 && c <= 99)) return Kind.RAIN;
		}
		if ((temp != null) && (temp <= FROST_C)) return Kind.FROST;
		return Kind.FIRE;
	}

	/** Вид для показа: устаревшая погода (старше часа) — огонь, как было. */
	@NonNull
	public static Kind shown(@Nullable Integer code, @Nullable Double temp, long savedAt, long now) {
		return ((savedAt <= 0L) || Termometr.stale(savedAt, now)) ? Kind.FIRE : kind(code, temp);
	}

	// ------------------------------------------------------------------------------------------- перекраска
	/** Палитра по яркости: точки 0..1 и цвета RGB. */
	private static final float[] ICE_X = {0f, 0.35f, 0.7f, 1f};
	private static final int[] ICE = {0x05142D, 0x286EBE, 0x96D7FF, 0xF5FCFF};
	private static final float[] WATER_X = {0f, 0.4f, 0.75f, 1f};
	private static final int[] WATER = {0x050C19, 0x28466E, 0x78A0C8, 0xDCEBFA};
	private static final float[] FROST_X = {0f, 0.5f, 1f};
	private static final int[] FROST = {0x050F1E, 0x5A96C8, 0xE6F5FF};

	/** Цвет палитры вида для яркости l (0..1); огонь — null. */
	static int palette(@NonNull Kind k, float l) {
		switch (k) {
			case SNOW:
				return grad(ICE_X, ICE, l);
			case RAIN:
				return grad(WATER_X, WATER, l);
			case FROST:
				return grad(FROST_X, FROST, l);
			default:
				return 0;
		}
	}

	private static int grad(float[] xs, int[] cs, float l) {
		l = Math.max(0f, Math.min(1f, l));
		for (int i = 1; i < xs.length; i++) {
			if (l <= xs[i]) {
				float q = (l - xs[i - 1]) / (xs[i] - xs[i - 1]);
				return mix(cs[i - 1], cs[i], q);
			}
		}
		return cs[cs.length - 1];
	}

	private static int mix(int a, int b, float q) {
		int r = Math.round(((a >> 16) & 255) * (1 - q) + ((b >> 16) & 255) * q);
		int g = Math.round(((a >> 8) & 255) * (1 - q) + ((b >> 8) & 255) * q);
		int bl = Math.round((a & 255) * (1 - q) + (b & 255) * q);
		return (r << 16) | (g << 8) | bl;
	}

	/**
	 * Слой огня (jarvis_opel_ogon) под вид: цвет — палитра по яркости, прозрачность — та же. Огонь — без изменений.
	 */
	public static void recolorRing(@NonNull int[] argb, @NonNull Kind k) {
		if (k == Kind.FIRE) return;
		for (int i = 0; i < argb.length; i++) {
			int p = argb[i];
			int a = p >>> 24;
			if (a == 0) continue;
			argb[i] = (a << 24) | palette(k, lum(p));
		}
	}

	/**
	 * Слой значка (jarvis_opel_znachok): огонь внутри круга (тёплые пиксели) — в палитру вида, хром — холоднее;
	 * при снеге и морозе по хрому иней (зерно, гуще к краю значка). w — сторона квадратного слоя.
	 */
	public static void recolorBadge(@NonNull int[] argb, int w, @NonNull Kind k) {
		if (k == Kind.FIRE) return;
		float frost = (k == Kind.SNOW) ? 1f : (k == Kind.FROST) ? 1f : 0f;
		Random rnd = new Random(7);
		int h = argb.length / w;
		// Зерно инея — по клеткам 2×2, чтобы не было «шума телевизора»
		float[] grain = new float[((w + 1) / 2) * ((h + 1) / 2)];
		for (int i = 0; i < grain.length; i++) grain[i] = rnd.nextFloat();
		float c = w / 2f, unit = w / 512f;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				int p = argb[i];
				int a = p >>> 24;
				if (a == 0) continue;
				float r = ((p >> 16) & 255) / 255f, g = ((p >> 8) & 255) / 255f, b = (p & 255) / 255f;
				float warm = Math.max(0f, Math.min(1f, (r - b) * 2.2f));
				int pc = palette(k, 0.3f * r + 0.55f * g + 0.15f * b);
				float pr = ((pc >> 16) & 255) / 255f, pg = ((pc >> 8) & 255) / 255f, pb = (pc & 255) / 255f;
				r = (r * (1 - warm) + pr * warm) * 0.88f;
				g = (g * (1 - warm) + pg * warm) * 0.95f;
				b = (b * (1 - warm) + pb * warm) * 1.08f;
				if (frost > 0f) {
					float d = (float) Math.hypot(x - c, y - c) / unit;
					float edge = clamp01((d - 105f) / 30f) * clamp01((140f - d) / 10f);
					float f = clamp01((grain[(y / 2) * ((w + 1) / 2) + x / 2] - 0.45f) * 4f) * (0.35f + 0.65f * edge) * frost;
					r = r * (1 - f) + 0.92f * f;
					g = g * (1 - f) + 0.97f * f;
					b = b * (1 - f) + 1.00f * f;
				}
				argb[i] = (a << 24) | (to255(r) << 16) | (to255(g) << 8) | to255(b);
			}
		}
	}

	/** Насколько ярко светит слой кольца (огонь — как было, 1). */
	public static float ringGain(@NonNull Kind k) {
		switch (k) {
			case SNOW:
				return 1.3f;
			case RAIN:
				return 0.9f;
			case FROST:
				return 0.6f;
			default:
				return 1f;
		}
	}

	static float lum(int p) {
		return (0.3f * ((p >> 16) & 255) + 0.55f * ((p >> 8) & 255) + 0.15f * (p & 255)) / 255f;
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	private static int to255(float v) {
		return Math.round(clamp01(v) * 255f);
	}

	// ------------------------------------------------------------------------------------------- частицы
	/**
	 * Снежинки, струи дождя или клочья пара в квадрате ±{@link #HALF} (в радиусах кольца значка) вокруг центра.
	 * Падают вниз и возвращаются сверху; у снежинок — покачивание.
	 */
	public static final class Fx {
		public static final float HALF = 1.3f;
		public final Kind kind;
		public final int count;
		public final float[] x, y, size, speed, phase;
		/** Крупная шестиконечная снежинка. */
		public final boolean[] big;
		private final Random rnd = new Random(11);
		private float time;

		public Fx(@NonNull Kind kind) {
			this.kind = kind;
			count = (kind == Kind.SNOW) ? 110 : (kind == Kind.RAIN) ? 70 : (kind == Kind.FROST) ? 14 : 0;
			x = new float[count];
			y = new float[count];
			size = new float[count];
			speed = new float[count];
			phase = new float[count];
			big = new boolean[count];
			for (int i = 0; i < count; i++) {
				x[i] = (rnd.nextFloat() * 2f - 1f) * HALF;
				y[i] = (rnd.nextFloat() * 2f - 1f) * HALF;
				phase[i] = rnd.nextFloat() * 6.2832f;
				switch (kind) {
					case SNOW:
						big[i] = i < 7;
						size[i] = big[i] ? 0.035f + rnd.nextFloat() * 0.02f : 0.004f + rnd.nextFloat() * 0.012f;
						speed[i] = 0.12f + rnd.nextFloat() * 0.18f;
						break;
					case RAIN:
						size[i] = 0.07f + rnd.nextFloat() * 0.09f; // длина струи
						speed[i] = 2.2f + rnd.nextFloat() * 0.9f;
						break;
					default:
						size[i] = 0.12f + rnd.nextFloat() * 0.14f; // радиус клочка пара
						speed[i] = 0.02f + rnd.nextFloat() * 0.03f;
				}
			}
		}

		/** Сдвиг на dt секунд. */
		public void step(float dt) {
			time += dt;
			for (int i = 0; i < count; i++) {
				if (kind == Kind.FROST) {
					// Пар поднимается и медленно плывёт вбок
					y[i] -= speed[i] * dt;
					x[i] += (float) Math.sin(time * 0.3f + phase[i]) * 0.01f * dt;
				} else {
					y[i] += speed[i] * dt;
					if (kind == Kind.SNOW) x[i] += (float) Math.sin(time * 1.3f + phase[i]) * 0.05f * dt;
					else x[i] -= speed[i] * 0.18f * dt;
				}
				if (y[i] > HALF) {
					y[i] -= 2f * HALF;
					x[i] = (rnd.nextFloat() * 2f - 1f) * HALF;
				} else if (y[i] < -HALF) {
					y[i] += 2f * HALF;
					x[i] = (rnd.nextFloat() * 2f - 1f) * HALF;
				}
				if (x[i] < -HALF) x[i] += 2f * HALF;
				else if (x[i] > HALF) x[i] -= 2f * HALF;
			}
		}

		/** Прозрачность частицы 0..1 (мерцание снежинок, ровные струи, едва заметный пар). */
		public float alpha(int i) {
			switch (kind) {
				case SNOW:
					return 0.55f + 0.4f * (0.5f + 0.5f * (float) Math.sin(time * 2f + phase[i]));
				case RAIN:
					return 0.35f + 0.3f * (phase[i] / 6.2832f);
				default:
					return 0.11f;
			}
		}
	}
}
