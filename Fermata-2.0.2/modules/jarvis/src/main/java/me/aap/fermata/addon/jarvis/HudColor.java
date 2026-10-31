package me.aap.fermata.addon.jarvis;

/**
 * Цвет сферы по фазе (пожелание пользователя 31.10.2026): пока JARVIS слушает — бирюзовый, «можно говорить»;
 * думает, отвечает и в покое — золотой. Переход плавный, но быстрый ({@link #FADE_SECONDS}), чтобы смену было видно
 * сразу. Чистая Java без Android — проверяется тестом на JVM.
 */
public final class HudColor {
	/** За сколько секунд цвет полностью сменяется. */
	public static final float FADE_SECONDS = 0.3f;

	// Золото (как было)
	public static final int GOLD_ARC = 0xFFFFB23A;
	public static final int GOLD_SOFT = 0xFFB5651D;
	public static final int GOLD_INK = 0xFFFFF1D0;
	public static final int GOLD_HOT = 0xFFFFE4AA;
	public static final int GOLD_FLARE = 0xFFFFECBE;
	// Бирюза
	public static final int TEAL_ARC = 0xFF2EE6D6;
	public static final int TEAL_SOFT = 0xFF13958B;
	public static final int TEAL_INK = 0xFFD6FFFA;
	public static final int TEAL_HOT = 0xFFB4FFF4;
	public static final int TEAL_FLARE = 0xFFC8FFF8;

	private HudColor() {
	}

	/** Бирюза нужна только в фазе «слушаю». */
	public static boolean listening(HudMath.Phase phase) {
		return phase == HudMath.Phase.LISTEN;
	}

	/** Шаг смешения: 0 — золото, 1 — бирюза; идёт к цели со скоростью 1/{@link #FADE_SECONDS}. */
	public static float step(float mix, boolean listening, float dt) {
		float d = Math.max(0f, dt) / FADE_SECONDS;
		return listening ? Math.min(1f, mix + d) : Math.max(0f, mix - d);
	}

	/** Цвет между a (mix = 0) и b (mix = 1), каналы ARGB по отдельности. */
	public static int lerp(int a, int b, float mix) {
		float q = Math.max(0f, Math.min(1f, mix));
		int r = 0;
		for (int sh = 0; sh <= 24; sh += 8) {
			int ca = (a >>> sh) & 0xFF, cb = (b >>> sh) & 0xFF;
			r |= (Math.round(ca + (cb - ca) * q) & 0xFF) << sh;
		}
		return r;
	}

	/**
	 * Матрица цвета (4×5, как у android.graphics.ColorMatrix) для золотой сферы-картинки: при mix = 0 — без
	 * изменений, при mix = 1 — яркость картинки окрашена в бирюзу (светлое — почти белая бирюза, тёмное — тёмное).
	 */
	public static float[] sphereMatrix(float mix) {
		float q = Math.max(0f, Math.min(1f, mix));
		float tr = ((TEAL_ARC >> 16) & 0xFF) / 255f;
		float tg = ((TEAL_ARC >> 8) & 0xFF) / 255f;
		float tb = (TEAL_ARC & 0xFF) / 255f;
		// Золото тёмнее по яркости, чем по красному каналу: усиление, чтобы бирюза не потускнела
		float gain = 1.35f;
		float[] lum = {0.299f, 0.587f, 0.114f};
		float[] tint = {tr * gain, tg * gain, tb * gain};
		float[] m = new float[20];
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 3; col++) {
				float id = (row == col) ? 1f : 0f;
				m[row * 5 + col] = id * (1f - q) + lum[col] * tint[row] * q;
			}
		}
		m[18] = 1f;
		return m;
	}
}
