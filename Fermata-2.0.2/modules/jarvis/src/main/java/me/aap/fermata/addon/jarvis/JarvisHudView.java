package me.aap.fermata.addon.jarvis;

import static me.aap.fermata.addon.jarvis.HudMath.NODES;
import static me.aap.fermata.addon.jarvis.HudMath.RINGS;
import static me.aap.fermata.addon.jarvis.HudMath.TWO_PI;
import static me.aap.fermata.addon.jarvis.HudMath.clamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * Круг JARVIS в стиле Старка: семь колец, свечение ядра, вспышка при
 * появлении, сетка с узлами, надпись и эквалайзер. Тот же рисунок, что в
 * JARVIS (ui/hud/JarvisHud.kt), перенесённый с Compose на обычный Canvas:
 * Fermata построена на View, тащить в неё Compose ради одного круга незачем.
 *
 * Кадры идут, только пока вид показан на экране.
 */
public class JarvisHudView extends View {
	// Золото, как сфера JARVIS из фильма (в самом JARVIS круг голубой)
	private static final int ARC = 0xFFFFB23A;
	private static final int ARC_SOFT = 0xFFB5651D;
	private static final int INK = 0xFFFFF1D0;
	/** Полупрозрачный: в теме JARVIS сквозь круг видно фото. */
	private static final int BACKDROP = 0x8C000000;
	private static final float RAD_TO_DEG = (float) (180.0 / Math.PI);

	private final HudMath.Animator animator = new HudMath.Animator();
	private final float dp;
	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint plus = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Typeface titleFace = Typeface.create(Typeface.SANS_SERIF, 600, false);
	private final Typeface wordFace = Typeface.create(Typeface.SANS_SERIF, 500, false);
	private final Path tickPath = new Path();
	private final RectF box = new RectF();
	private final Path frontPath = new Path();
	private final Path backPath = new Path();
	private final float[] pt = new float[3];
	private final float[] pt2 = new float[3];
	private final String title;
	private final String listening;
	private final String thinking;

	private HudMath.Phase phase = HudMath.Phase.IDLE;
	private float micLevel;
	private long start = -1L;
	private long prev = -1L;
	private float since;

	public JarvisHudView(Context ctx) {
		super(ctx);
		dp = ctx.getResources().getDisplayMetrics().density;
		title = ctx.getString(R.string.jarvis_hud_title);
		listening = ctx.getString(R.string.jarvis_hud_listening);
		thinking = ctx.getString(R.string.jarvis_hud_thinking);
		setContentDescription(ctx.getString(R.string.jarvis_hud_description));
		PorterDuffXfermode add = new PorterDuffXfermode(PorterDuff.Mode.ADD);
		plus.setXfermode(add);
		stroke.setXfermode(add);
		stroke.setStyle(Paint.Style.STROKE);
		text.setTextAlign(Paint.Align.CENTER);
		// Свечение складывается (ADD) только с собственным фоном вида
		setLayerType(LAYER_TYPE_HARDWARE, null);
		setClickable(true);
		setFocusable(true);
	}

	public HudMath.Phase getPhase() {
		return phase;
	}

	public void setPhase(@NonNull HudMath.Phase phase) {
		this.phase = phase;
		if ((phase == HudMath.Phase.IDLE) || (phase == HudMath.Phase.THINK)) micLevel = 0f;
		invalidate();
	}

	/** Громкость микрофона 0..1; сглаживает сам вид. */
	public void setMicLevel(float level) {
		micLevel = clamp(level, 0f, 1f);
	}

	/** Раскрыть круг заново — при каждом показе вкладки. */
	public void restart() {
		start = -1L;
		prev = -1L;
		invalidate();
	}

	@Override
	public void onVisibilityAggregated(boolean isVisible) {
		super.onVisibilityAggregated(isVisible);
		if (isVisible) restart();
	}

	@Override
	protected void onDraw(@NonNull Canvas c) {
		long now = System.nanoTime();
		if (start < 0L) {
			start = now;
			prev = now;
		}
		float t = HudMath.sinceSeconds(now, start);
		float dt = HudMath.clampDt(HudMath.sinceSeconds(now, prev));
		float real = (phase == HudMath.Phase.SPEAK) ? HudMath.speakLevel(t) : micLevel;
		animator.advance(phase, real, t, dt);
		prev = now;
		since = t;

		drawHud(c);

		if (isShown()) postInvalidateOnAnimation();
	}

	private void drawHud(Canvas c) {
		float w = getWidth();
		float h = getHeight();
		float cx = w / 2f;
		float cy = h / 2f;
		float s = Math.min(w, h);
		float t = since;
		float open = HudMath.open(t);
		float level = animator.getLevel();

		c.drawColor(BACKDROP);

		// Свечение ядра
		float coreR = HudMath.coreRadius(s, level, open) * 2.6f;
		plus.setShader(new RadialGradient(cx, cy, coreR,
				new int[]{argb(clamp(0.55f + level * 0.45f, 0f, 1f), 255, 228, 170),
						withAlpha(ARC, clamp(0.35f + level * 0.4f, 0f, 1f)), withAlpha(ARC, 0f)},
				new float[]{0f, 0.18f, 1f}, Shader.TileMode.CLAMP));
		c.drawCircle(cx, cy, coreR, plus);
		plus.setShader(null);

		// Горизонтальная вспышка
		float flare = HudMath.flare(phase, t, level);
		if (flare > 0.01f) {
			float half = s * 0.006f * (1f + flare * 2f);
			plus.setShader(new LinearGradient(cx - s * 0.5f, 0f, cx + s * 0.5f, 0f,
					new int[]{withAlpha(ARC, 0f), argb(clamp(flare, 0f, 1f), 255, 236, 190),
							withAlpha(ARC, 0f)},
					new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
			c.drawRect(cx - s * 0.5f, cy - half, cx + s * 0.5f, cy + half, plus);
			plus.setShader(null);
		}

		// Сетка с узлами
		float drift = t * 0.02f;
		stroke.setStrokeWidth(0.6f * dp);
		for (int i = 0; i < NODES.length; i++) {
			HudMath.Node n = NODES[i];
			double a = n.a + drift;
			float d = HudMath.nodeDistance(s, n, open);
			float px = cx + (float) Math.cos(a) * d;
			float py = cy + (float) Math.sin(a) * d;
			HudMath.Node m = NODES[(i + 3) % NODES.length];
			double ma = m.a + drift;
			float md = HudMath.nodeDistance(s, m, open);
			stroke.setColor(withAlpha(ARC_SOFT, 0.18f * open));
			c.drawLine(px, py, cx + (float) Math.cos(ma) * md, cy + (float) Math.sin(ma) * md, stroke);
			plus.setColor(withAlpha(ARC, 0.5f * open));
			c.drawCircle(px, py, Math.max(0f, s * n.s * 0.15f), plus);
		}

		// Сфера-голограмма внутри колец
		drawSphere(c, cx, cy, HudMath.sphereRadius(s, level, open),
				t * 0.35f * animator.getSpin(), t, level, open);

		// Кольца
		for (int i = 0; i < RINGS.length; i++) {
			HudMath.Ring ring = RINGS[i];
			float appear = HudMath.ringAppear(open, i);
			if (appear <= 0f) continue;
			float r = HudMath.ringRadius(s, ring, i, level, appear);
			float base = animator.rotations[i] * TWO_PI;
			int color = (i % 3 == 0) ? ARC_SOFT : ARC;
			// Кольца — рамка, главная — сфера: приглушены
			float alpha = HudMath.ringAlpha(ring, appear, level) * 0.5f;
			float width = ring.w * dp;
			float glow = ((ring.w > 3f) ? 14f : 6f) * dp;
			box.set(cx - r, cy - r, cx + r, cy + r);

			for (int k = 0; k + 1 < ring.segs.length; k += 2) {
				float a0 = base + ring.segs[k] * TWO_PI;
				float sweep = ring.segs[k + 1] * TWO_PI * appear;
				if (ring.dash > 0f) {
					// Пунктир: штрих и такой же промежуток
					float end = a0 + sweep;
					float step = ring.dash * 2f * TWO_PI;
					float dashLen = ring.dash * TWO_PI;
					stroke.setColor(withAlpha(color, alpha));
					stroke.setStrokeWidth(width);
					for (float a = a0; a < end; a += step) {
						c.drawArc(box, a * RAD_TO_DEG, (Math.min(end, a + dashLen) - a) * RAD_TO_DEG,
								false, stroke);
					}
				} else {
					// Свечение: широкий слабый мазок под основной линией
					stroke.setColor(withAlpha(color, alpha * 0.22f));
					stroke.setStrokeWidth(width + glow);
					c.drawArc(box, a0 * RAD_TO_DEG, sweep * RAD_TO_DEG, false, stroke);
					stroke.setColor(withAlpha(color, alpha));
					stroke.setStrokeWidth(width);
					c.drawArc(box, a0 * RAD_TO_DEG, sweep * RAD_TO_DEG, false, stroke);
				}
			}

			if (ring.ticks > 0) {
				tickPath.rewind();
				for (int k = 0; k < ring.ticks; k++) {
					double a = base + (k / (float) ring.ticks) * TWO_PI;
					float len = s * ring.tickLen * ((k % 5 == 0) ? 2f : 1f);
					float cos = (float) Math.cos(a);
					float sin = (float) Math.sin(a);
					tickPath.moveTo(cx + cos * r, cy + sin * r);
					tickPath.lineTo(cx + cos * (r - len), cy + sin * (r - len));
				}
				stroke.setColor(withAlpha(color, alpha * 0.8f));
				stroke.setStrokeWidth(dp);
				c.drawPath(tickPath, stroke);
			}
		}

		// Сканирующий луч в фазе «думаю»
		if (HudMath.showsScan(phase)) {
			c.save();
			c.rotate((t * 4f * RAD_TO_DEG) % 360f, cx, cy);
			plus.setShader(new SweepGradient(cx, cy,
					new int[]{withAlpha(ARC, 0.35f), withAlpha(ARC, 0f), withAlpha(ARC, 0f)},
					new float[]{0f, 0.12f, 1f}));
			c.drawCircle(cx, cy, s * 0.3f, plus);
			plus.setShader(null);
			c.restore();
		}

		// Надпись в центре. Размер привязан к кругу, а не к масштабу шрифта системы
		text.setTypeface(titleFace);
		text.setTextSize(s * 0.05f);
		text.setColor(withAlpha(INK, open));
		text.setShadowLayer(12f * dp, 0f, 0f, ARC);
		drawCentered(c, title, cx, cy - s * 0.015f);
		text.clearShadowLayer();

		String word = (phase == HudMath.Phase.LISTEN) ? listening :
				(phase == HudMath.Phase.THINK) ? thinking : "";
		if (!word.isEmpty()) {
			text.setTypeface(wordFace);
			text.setTextSize(s * 0.032f);
			text.setColor(withAlpha(ARC, open * 0.85f));
			drawCentered(c, word, cx, cy + s * 0.05f);
		}

		// Эквалайзер под надписью
		if (HudMath.showsEqualizer(phase)) {
			int bars = 13;
			float bw = s * 0.007f;
			float gap = s * 0.006f;
			float x0 = cx - (bars * (bw + gap)) / 2f;
			fill.setColor(withAlpha(ARC, 0.9f));
			for (int k = 0; k < bars; k++) {
				float bh = HudMath.equalizerHeight(s, level, t, k);
				float x = x0 + k * (bw + gap);
				float y = cy + s * 0.095f - bh / 2f;
				c.drawRect(x, y, x + bw, y + bh, fill);
			}
		}
	}

	/**
	 * Золотая сфера: широты, вращающиеся меридианы, мерцающие фрагменты
	 * «схем» на поверхности, светящийся контур и вихрь в центре. Дышит с
	 * громкостью (радиус и яркость — от level), вращается быстрее при «думаю».
	 */
	private void drawSphere(Canvas c, float cx, float cy, float rs, float rot, float t,
													float level, float open) {
		if (rs <= 0f) return;
		float tilt = HudMath.SPHERE_TILT;
		float ct = (float) Math.cos(tilt), st = (float) Math.sin(tilt);

		// Широты: эллипсы из-за наклона оси
		stroke.setStrokeWidth(dp);
		stroke.setColor(withAlpha(ARC_SOFT, 0.45f * open));
		for (int k = -2; k <= 2; k++) {
			float lat = k * 0.5f;
			float r = rs * (float) Math.cos(lat);
			float y = cy - rs * (float) Math.sin(lat) * ct;
			box.set(cx - r, y - r * st, cx + r, y + r * st);
			c.drawOval(box, stroke);
		}

		// Меридианы: передняя половина ярче задней
		frontPath.rewind();
		backPath.rewind();
		for (int m = 0; m < 8; m++) {
			float lon = m * (float) Math.PI / 8f;
			for (int j = 0; j < 24; j++) {
				float la = -1.5708f + j * 3.1416f / 24f;
				float lb = la + 3.1416f / 24f;
				HudMath.project(la, lon, rot, pt);
				HudMath.project(lb, lon, rot, pt2);
				Path p = (pt[2] + pt2[2] >= 0f) ? frontPath : backPath;
				p.moveTo(cx + pt[0] * rs, cy + pt[1] * rs);
				p.lineTo(cx + pt2[0] * rs, cy + pt2[1] * rs);
			}
		}
		stroke.setColor(withAlpha(ARC_SOFT, 0.18f * open));
		c.drawPath(backPath, stroke);
		stroke.setColor(withAlpha(ARC, 0.4f * open));
		c.drawPath(frontPath, stroke);

		// Фрагменты «схем» и искры
		for (HudMath.Bit b : HudMath.BITS) {
			HudMath.project(b.lat, b.lon, rot, pt);
			HudMath.project(b.lat, b.lon + b.len, rot, pt2);
			float a = HudMath.bitAlpha(b, t, pt[2], level) * open;
			stroke.setStrokeWidth(b.w * dp * (1f + level * 0.6f));
			stroke.setColor(withAlpha((b.phase > 3f) ? INK : ARC, a));
			c.drawLine(cx + pt[0] * rs, cy + pt[1] * rs, cx + pt2[0] * rs, cy + pt2[1] * rs, stroke);
			if (pt[2] > 0.3f) {
				plus.setColor(withAlpha(INK, a * 0.9f));
				c.drawCircle(cx + pt[0] * rs, cy + pt[1] * rs, (0.8f + level) * dp, plus);
			}
		}

		// Светящийся контур сферы
		stroke.setStrokeWidth(6f * dp);
		stroke.setColor(withAlpha(ARC, 0.12f * open * (0.7f + level)));
		c.drawCircle(cx, cy, rs, stroke);
		stroke.setStrokeWidth(1.5f * dp);
		stroke.setColor(withAlpha(ARC, 0.65f * open));
		c.drawCircle(cx, cy, rs, stroke);

		// Вихрь в центре: дуга навстречу вращению сферы
		float vr = rs * (0.62f + 0.06f * level);
		float va = (-t * 1.3f * RAD_TO_DEG) % 360f;
		box.set(cx - vr, cy - vr * 0.62f, cx + vr, cy + vr * 0.62f);
		stroke.setStrokeWidth(9f * dp);
		stroke.setColor(withAlpha(ARC, 0.18f * open));
		c.drawArc(box, va, 250f, false, stroke);
		stroke.setStrokeWidth(2.5f * dp);
		stroke.setColor(withAlpha(INK, 0.75f * open));
		c.drawArc(box, va, 250f, false, stroke);
	}

	/** Строка с центром в точке (x, y) — как drawCentered в JARVIS. */
	private void drawCentered(Canvas c, String s, float x, float y) {
		Paint.FontMetrics fm = text.getFontMetrics();
		c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, text);
	}

	private static int withAlpha(int color, float alpha) {
		int a = Math.round(Color.alpha(color) * clamp(alpha, 0f, 1f));
		return (color & 0x00FFFFFF) | (a << 24);
	}

	private static int argb(float alpha, int r, int g, int b) {
		return Color.argb(Math.round(clamp(alpha, 0f, 1f) * 255f), r, g, b);
	}
}
