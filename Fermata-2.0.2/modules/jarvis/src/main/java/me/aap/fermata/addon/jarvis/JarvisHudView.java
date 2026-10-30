package me.aap.fermata.addon.jarvis;

import static me.aap.fermata.addon.jarvis.HudMath.NODES;
import static me.aap.fermata.addon.jarvis.HudMath.RINGS;
import static me.aap.fermata.addon.jarvis.HudMath.TWO_PI;
import static me.aap.fermata.addon.jarvis.HudMath.clamp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import androidx.annotation.Nullable;

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
	private static final int BUCKETS = 12;
	private final float[][] bucketPts = new float[BUCKETS][HudMath.DOTS * 2];
	private final int[] bucketSize = new int[BUCKETS];
	private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
	/** Сетка деформации сферы-картинки: MESH × MESH ячеек. */
	private static final int MESH = 32;
	private final float[] mesh = new float[(MESH + 1) * (MESH + 1) * 2];
	/**
	 * Сфера с кадра, присланного пользователем (16.10.2026), вырезанная из фона:
	 * res/drawable-nodpi/jarvis_sfera.webp. Нет файла — рисуется облако точек.
	 */
	@Nullable
	private final Bitmap sfera;
	/**
	 * Значок Opel в огне — покой круга (пожелание пользователя 30.10.2026, вырезан из присланного им фото), двумя
	 * слоями с прозрачностью вместо чёрного фона: res/drawable-nodpi/jarvis_opel_znachok.webp — сам значок
	 * (неподвижен), jarvis_opel_ogon.webp — огонь (живёт). Нет файлов — в покое прежний круг.
	 */
	@Nullable
	private final Bitmap opel;
	@Nullable
	private final Bitmap ogon;
	private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
	private final float[] flameOut = new float[2];
	/** Живое пламя вокруг значка — частицы ({@link EmblemMorph.Fire}). */
	private final EmblemMorph.Fire fire = new EmblemMorph.Fire();
	/** Мягкое пятно частицы: белое с прозрачными краями, цвет — фильтром по возрасту. */
	private final Bitmap puff = makePuff();
	private static final int FIRE_SHADES = 16;
	private final Paint[] firePaint = new Paint[FIRE_SHADES];
	private final RectF puffBox = new RectF();
	@Nullable
	private final EmblemMorph.Sparks sparks;
	/** Сетка огня гуще сетки сферы: у языков пламени мелкие волны, на 32 ячейках они рвались бы. */
	private static final int FIRE_MESH = 48;
	private final float[] opelMesh = new float[(FIRE_MESH + 1) * (FIRE_MESH + 1) * 2];
	private final float[] sparkPos = new float[2];
	/** Превращение: 0 — значок, 1 — сфера; меньше 0 — ещё не задано (первый кадр). */
	private float morph = -1f;
	private float fireDt;
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
		sfera = loadSfera(ctx);
		Bitmap z = loadBitmap(ctx, R.drawable.jarvis_opel_znachok);
		Bitmap f = loadBitmap(ctx, R.drawable.jarvis_opel_ogon);
		opel = ((z != null) && (f != null)) ? z : null;
		ogon = (opel != null) ? f : null;
		sparks = (opel == null) ? null : sparksOf(z, f);
		PorterDuffXfermode addMode = new PorterDuffXfermode(PorterDuff.Mode.ADD);
		for (int i = 0; i < FIRE_SHADES; i++) {
			Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
			p.setColorFilter(new android.graphics.PorterDuffColorFilter(
					EmblemMorph.fireColor((i + 0.5f) / FIRE_SHADES), PorterDuff.Mode.MULTIPLY));
			p.setXfermode(addMode);
			firePaint[i] = p;
		}
		PorterDuffXfermode add = new PorterDuffXfermode(PorterDuff.Mode.ADD);
		plus.setXfermode(add);
		stroke.setXfermode(add);
		stroke.setStyle(Paint.Style.STROKE);
		dots.setXfermode(add);
		image.setXfermode(add);
		dots.setStyle(Paint.Style.STROKE);
		text.setTextAlign(Paint.Align.CENTER);
		// Свечение складывается (ADD) только с собственным фоном вида
		setLayerType(LAYER_TYPE_HARDWARE, null);
		setClickable(true);
		setFocusable(true);
	}

	/** Касание в точке (x, y) попало в шар (с небольшим запасом вокруг). */
	public boolean isOnSphere(float x, float y) {
		float w = getWidth(), h = getHeight();
		float r = HudMath.sphereRadius(Math.min(w, h), 0f, 1f) *
				((sfera != null) ? HudMath.IMAGE_SPHERE_SCALE : 1f) * 1.15f;
		float dx = x - w / 2f, dy = y - h / 2f;
		return dx * dx + dy * dy <= r * r;
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
		morph = -1f;
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
		fireDt = dt;
		float real = (phase == HudMath.Phase.SPEAK) ? HudMath.speakLevel(t) : micLevel;
		animator.advance(phase, real, t, dt);
		prev = now;
		since = t;
		boolean idle = phase == HudMath.Phase.IDLE;
		// При показе вкладки — сразу нужный вид, без превращения
		morph = (morph < 0f) ? (idle ? 0f : 1f) : EmblemMorph.step(morph, idle, dt);

		if (opel == null) {
			drawHud(c, 1f);
		} else {
			float vis = EmblemMorph.hudVisibility(morph);
			// Без чёрного фона под значком (замечание 30.10.2026): затемнение — только у сферы, проявляется с ней
			if (vis > 0f) drawHud(c, vis);
			drawEmblem(c, EmblemMorph.emblemAlpha(morph));
			drawSparks(c, morph);
		}

		if (isShown()) postInvalidateOnAnimation();
	}

	/** Круг со сферой и кольцами; vis — видимость 0..1 (проявление из значка). */
	private void drawHud(Canvas c, float vis) {
		float w = getWidth();
		float h = getHeight();
		float cx = w / 2f;
		float cy = h / 2f;
		float s = Math.min(w, h);
		float t = since;
		float open = HudMath.open(t) * vis;
		float level = animator.getLevel();

		c.drawColor(withAlpha(BACKDROP, vis));

		// Свечение ядра. Под сферой-картинкой — меньше: иначе центр засвечен и мутный
		float coreR = HudMath.coreRadius(s, level, open) * ((sfera != null) ? 1.6f : 2.6f);
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
			// Со сферой-картинкой два внешних кольца не рисуются (замечание 16.10.2026)
			if ((sfera != null) && (i < 2)) continue;
			HudMath.Ring ring = RINGS[i];
			float appear = HudMath.ringAppear(open, i);
			if (appear <= 0f) continue;
			float r = HudMath.ringRadius(s, ring, i, level, appear);
			float base = animator.rotations[i] * TWO_PI;
			int color = (i % 3 == 0) ? ARC_SOFT : ARC;
			// Кольца — рамка, главная — сфера: приглушены
			float alpha = HudMath.ringAlpha(ring, appear, level) * ((sfera != null) ? 0.2f : 0.35f);
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
	 * Золотая сфера из точек (образец — сфера JARVIS из фильма): облако из
	 * {@link HudMath#DOTS} точек вращается, ближние ярче дальних, каждая
	 * мерцает сама. С голосом облако растёт, точки крупнеют, по поверхности
	 * бежит рябь. Поверх — короткие вспышки-фрагменты и вихрь в центре.
	 */
	private void drawSphere(Canvas c, float cx, float cy, float rs, float rot, float t,
													float level, float open) {
		if (rs <= 0f) return;
		if (sfera != null) {
			drawImageSphere(c, cx, cy, rs, rot, t, level, open);
			return;
		}
		float cr = (float) Math.cos(rot), sr = (float) Math.sin(rot);
		float ct = (float) Math.cos(HudMath.SPHERE_TILT), st = (float) Math.sin(HudMath.SPHERE_TILT);
		for (int i = 0; i < BUCKETS; i++) bucketSize[i] = 0;

		for (int i = 0; i < HudMath.DOTS; i++) {
			float x = HudMath.DX[i], y = HudMath.DY[i], z = HudMath.DZ[i];
			float xr = x * cr + z * sr;
			float zr = -x * sr + z * cr;
			float yy = y * ct - zr * st;
			float zz = y * st + zr * ct;
			float k = rs * HudMath.DR[i] * (1f + HudMath.ripple(HudMath.DP[i], y, t, level));
			// Корзина: глубина (3 уровня) × мерцание (4 уровня) — одна отрисовка на корзину
			int depth = (zz > 0.35f) ? 2 : (zz > -0.2f) ? 1 : 0;
			int tw = Math.min(3, (int) (HudMath.twinkle(HudMath.DP[i], t, level) * 4f));
			int bkt = depth * 4 + tw;
			int n = bucketSize[bkt];
			bucketPts[bkt][n] = cx + xr * k;
			bucketPts[bkt][n + 1] = cy - yy * k;
			bucketSize[bkt] = n + 2;
		}

		dots.setStrokeCap(Paint.Cap.ROUND);
		for (int depth = 0; depth < 3; depth++) {
			float da = (depth == 2) ? 1f : (depth == 1) ? 0.55f : 0.22f;
			float size = (depth == 2 ? 2.4f : depth == 1 ? 1.8f : 1.3f) * dp * (1f + level * 0.7f);
			for (int tw = 0; tw < 4; tw++) {
				int bkt = depth * 4 + tw;
				if (bucketSize[bkt] == 0) continue;
				float a = da * (0.25f + 0.25f * tw) * open * (0.75f + 0.25f * level);
				// Ореол: крупная тусклая точка под яркой
				dots.setStrokeWidth(size * 2.6f);
				dots.setColor(withAlpha(ARC, a * 0.22f));
				c.drawPoints(bucketPts[bkt], 0, bucketSize[bkt], dots);
				dots.setStrokeWidth(size);
				dots.setColor(withAlpha((tw == 3 && depth == 2) ? INK : ARC, a));
				c.drawPoints(bucketPts[bkt], 0, bucketSize[bkt], dots);
			}
		}

		// Вспышки-фрагменты на поверхности
		for (HudMath.Bit b : HudMath.BITS) {
			HudMath.project(b.lat, b.lon, rot, pt);
			HudMath.project(b.lat, b.lon + b.len, rot, pt2);
			float a = HudMath.bitAlpha(b, t, pt[2], level) * open * 0.8f;
			stroke.setStrokeWidth(b.w * 0.8f * dp * (1f + level * 0.6f));
			stroke.setColor(withAlpha((b.phase > 3f) ? INK : ARC, a));
			c.drawLine(cx + pt[0] * rs, cy + pt[1] * rs, cx + pt2[0] * rs, cy + pt2[1] * rs, stroke);
		}

		// Мягкое свечение контура
		stroke.setStrokeWidth(10f * dp);
		stroke.setColor(withAlpha(ARC, 0.07f * open * (0.7f + level)));
		c.drawCircle(cx, cy, rs, stroke);

		// Вихрь в центре: дуга навстречу вращению сферы
		float vr = rs * (0.55f + 0.06f * level);
		float va = (-t * 1.3f * RAD_TO_DEG) % 360f;
		box.set(cx - vr, cy - vr * 0.6f, cx + vr, cy + vr * 0.6f);
		stroke.setStrokeWidth(8f * dp);
		stroke.setColor(withAlpha(ARC, 0.14f * open));
		c.drawArc(box, va, 240f, false, stroke);
		stroke.setStrokeWidth(1.8f * dp);
		stroke.setColor(withAlpha(INK, 0.6f * open));
		c.drawArc(box, va, 240f, false, stroke);
	}

	/**
	 * Сфера с кадра. Картинка натянута на сетку {@link #MESH}×{@link #MESH}, и
	 * каждый узел сетки смещается своей волной ({@link HudMath#warp}): края
	 * сферы ходят по частям, независимо друг от друга, сильнее — с голосом.
	 * Сфера медленно вращается; при громком голосе — второй, чуть больший
	 * слой-свечение. Поверх — редкие искры, чтобы картинка жила.
	 */
	private void drawImageSphere(Canvas c, float cx, float cy, float rs, float rot, float t,
															 float level, float open) {
		Bitmap b = sfera;
		if (b == null) return;
		rs *= HudMath.IMAGE_SPHERE_SCALE;
		float bw = b.getWidth(), bh = b.getHeight();
		float pulse = 1f + 0.05f * level + 0.015f * (float) Math.sin(t * 1.6f);
		float sc = rs * 2.36f * pulse / bw;
		// Радиус сферы на картинке, px: картинка вырезана со стороной 2.36 радиуса
		float imgR = bw / 2.36f;
		float ang = rot * 0.25f;
		float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
		int k = 0;
		for (int gy = 0; gy <= MESH; gy++) {
			float dy = gy * bh / MESH - bh / 2f;
			for (int gx = 0; gx <= MESH; gx++) {
				float dx = gx * bw / MESH - bw / 2f;
				float rn = (float) Math.sqrt(dx * dx + dy * dy) / imgR;
				// Только волны по краю, без поворота слоёв: поворот растягивал линии
				// в завихрения, и сфера выглядела размытой (замечание 16.10.2026)
				float f = 1f + HudMath.warp((float) Math.atan2(dy, dx), rn, t, level);
				float x = dx * f, y = dy * f;
				mesh[k++] = cx + (x * ca - y * sa) * sc;
				mesh[k++] = cy + (x * sa + y * ca) * sc;
			}
		}
		// Один слой: второй, увеличенный слой-свечение двоил картинку — сфера
		// выглядела размытой (замечание 16.10.2026: «должно быть чётче»)
		image.setAlpha(Math.round(255f * clamp(open * (0.85f + 0.15f * level), 0f, 1f)));
		c.drawBitmapMesh(b, MESH, MESH, mesh, 0, null, 0, image);

		// Искры: каждая пятая точка поверхности, только на пике мерцания и спереди
		float cr = (float) Math.cos(rot), sr = (float) Math.sin(rot);
		float ct = (float) Math.cos(HudMath.SPHERE_TILT), st = (float) Math.sin(HudMath.SPHERE_TILT);
		int n = 0;
		float[] pts = bucketPts[0];
		for (int i = 0; i < HudMath.DOTS; i += 5) {
			if (HudMath.DR[i] < 1f) continue;
			if (HudMath.twinkle(HudMath.DP[i], t, level) < 0.88f) continue;
			float x = HudMath.DX[i], y = HudMath.DY[i], z = HudMath.DZ[i];
			float xr = x * cr + z * sr, zr = -x * sr + z * cr;
			if (y * st + zr * ct < 0f) continue;
			float kr = rs * 0.95f * pulse;
			pts[n++] = cx + xr * kr;
			pts[n++] = cy - (y * ct - zr * st) * kr;
		}
		if (n == 0) return;
		dots.setStrokeCap(Paint.Cap.ROUND);
		dots.setStrokeWidth(4.5f * dp * (1f + level));
		dots.setColor(withAlpha(ARC, 0.25f * open));
		c.drawPoints(pts, 0, n, dots);
		dots.setStrokeWidth(1.8f * dp * (1f + level * 0.6f));
		dots.setColor(withAlpha(INK, 0.9f * open));
		c.drawPoints(pts, 0, n, dots);
	}

	/**
	 * Значок в огне. Сам значок неподвижен; огонь живёт: слой пламени натянут на сетку, узлы которой гонят волны
	 * {@link EmblemMorph#flame} от кольца наружу, яркость неровно мерцает; второй слой того же огня, чуть больше и
	 * медленно покачиваясь, с другой турбулентностью — языки не повторяют друг друга.
	 */
	private void drawEmblem(Canvas c, float alpha) {
		Bitmap z = opel, f = ogon;
		if ((z == null) || (f == null) || (alpha <= 0.01f)) return;
		float t = since;
		float cx = getWidth() / 2f, cy = getHeight() / 2f;
		float ringPx = Math.min(getWidth(), getHeight()) * EmblemMorph.RING_OF_VIEW;
		float sc = ringPx / (EmblemMorph.RING_OF_SIDE * f.getWidth());

		// Подложка — огонь с фото, приглушён и неровно мерцает; поверх — живые языки пламени
		fireMesh(f, cx, cy, sc, 1f, 0f, t, 0f);
		image.setAlpha(Math.round(255f * clamp(alpha * 0.55f * EmblemMorph.flicker(t, 0f), 0f, 1f)));
		c.drawBitmapMesh(f, FIRE_MESH, FIRE_MESH, opelMesh, 0, null, 0, image);
		fire.step(HudMath.clampDt(fireDt));
		for (int i = 0; i < EmblemMorph.Fire.MAX; i++) {
			if (!fire.alive[i]) continue;
			float a = fire.alpha(i) * alpha;
			if (a < 0.004f) continue;
			// Пятно — гауссово с краем на 2 радиусах, как в предпросмотре
			float r = fire.radius(i) * ringPx * 2f;
			float px = cx + fire.x[i] * ringPx, py = cy + fire.y[i] * ringPx;
			puffBox.set(px - r, py - r, px + r, py + r);
			Paint p = firePaint[Math.min(FIRE_SHADES - 1, (int) (fire.progress(i) * FIRE_SHADES))];
			p.setAlpha(Math.round(255f * clamp(a, 0f, 1f)));
			c.drawBitmap(puff, null, puffBox, p);
		}

		// Значок поверх огня — обычным наложением: хром не должен просвечивать пламенем
		float zs = ringPx / (EmblemMorph.RING_OF_SIDE * z.getWidth());
		float hw = z.getWidth() * zs / 2f, hh = z.getHeight() * zs / 2f;
		box.set(cx - hw, cy - hh, cx + hw, cy + hh);
		logoPaint.setAlpha(Math.round(255f * clamp(alpha, 0f, 1f)));
		c.drawBitmap(z, null, box, logoPaint);
	}

	/** Белое пятно 64×64 с гауссовым спадом прозрачности — одно на все частицы. */
	private static Bitmap makePuff() {
		int n = 64;
		int[] px = new int[n * n];
		for (int y = 0; y < n; y++) {
			for (int x = 0; x < n; x++) {
				float dx = (x + 0.5f - n / 2f) / (n / 4f), dy = (y + 0.5f - n / 2f) / (n / 4f);
				int a = Math.round(255f * (float) Math.exp(-(dx * dx + dy * dy) * 1.6f));
				px[y * n + x] = (a << 24) | 0xFFFFFF;
			}
		}
		return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
	}

	/** Сетка слоя огня: масштаб, поворот (рад) и турбулентность со своим временем и зерном. */
	private void fireMesh(Bitmap b, float cx, float cy, float sc, float scale, float rot, float t, float seed) {
		float bw = b.getWidth(), bh = b.getHeight();
		float ringImg = EmblemMorph.RING_OF_SIDE * bw;
		float cr = (float) Math.cos(rot), sr = (float) Math.sin(rot);
		int k = 0;
		for (int gy = 0; gy <= FIRE_MESH; gy++) {
			float dy = gy * bh / FIRE_MESH - bh / 2f;
			for (int gx = 0; gx <= FIRE_MESH; gx++) {
				float dx = gx * bw / FIRE_MESH - bw / 2f;
				float d = (float) Math.sqrt(dx * dx + dy * dy);
				float a = (float) Math.atan2(dy, dx);
				EmblemMorph.flame(a, d / ringImg, t, seed, flameOut);
				float rr = d + flameOut[0] * ringImg;
				float aa = a + rot + ((d > 1f) ? flameOut[1] * ringImg / d : 0f);
				opelMesh[k++] = cx + (float) Math.cos(aa) * rr * sc * scale;
				opelMesh[k++] = cy + (float) Math.sin(aa) * rr * sc * scale;
			}
		}
	}

	/** Искры значка посреди превращения: разлетаются из значка и садятся на сферу. */
	private void drawSparks(Canvas c, float m) {
		EmblemMorph.Sparks sp = sparks;
		float a = EmblemMorph.sparksAlpha(m);
		if ((sp == null) || (a <= 0.01f)) return;
		float s = Math.min(getWidth(), getHeight());
		float cx = getWidth() / 2f, cy = getHeight() / 2f;
		float ringPx = s * EmblemMorph.RING_OF_VIEW;
		float sphereR = HudMath.sphereRadius(s, 0f, 1f) * ((sfera != null) ? HudMath.IMAGE_SPHERE_SCALE : 1f) * 0.8f;
		dots.setStrokeCap(Paint.Cap.ROUND);
		for (int j = 0; j < sp.count; j++) {
			EmblemMorph.position(sp, j, m, ringPx, sphereR, sparkPos);
			float x = cx + sparkPos[0], y = cy + sparkPos[1];
			// Ореол и ядро искры; цвет — свой, из значка, к концу золотится под сферу
			dots.setStrokeWidth(4f * dp);
			dots.setColor(withAlpha(ARC, a * 0.18f));
			c.drawPoint(x, y, dots);
			dots.setStrokeWidth(1.8f * dp);
			dots.setColor(withAlpha(blend(sp.color[j], ARC, m), a));
			c.drawPoint(x, y, dots);
		}
	}

	/** Искры из обоих слоёв: цвет точки — с учётом прозрачности (тусклое пламя не даёт ярких искр). */
	private static EmblemMorph.Sparks sparksOf(Bitmap z, Bitmap f) {
		int w = z.getWidth(), h = z.getHeight();
		int[] a = new int[w * h], b = new int[w * h];
		z.getPixels(a, 0, w, 0, 0, w, h);
		f.getPixels(b, 0, w, 0, 0, w, h);
		for (int i = 0; i < a.length; i++) {
			int p = (Color.alpha(a[i]) >= Color.alpha(b[i])) ? a[i] : b[i];
			float k = Color.alpha(p) / 255f;
			a[i] = Color.rgb(Math.round(Color.red(p) * k), Math.round(Color.green(p) * k), Math.round(Color.blue(p) * k));
		}
		return EmblemMorph.sparks(a, w, h, EmblemMorph.SPARKS);
	}

	private static int blend(int a, int b, float k) {
		float q = clamp(k, 0f, 1f);
		int r = Math.round(Color.red(a) * (1 - q) + Color.red(b) * q);
		int g = Math.round(Color.green(a) * (1 - q) + Color.green(b) * q);
		int bl = Math.round(Color.blue(a) * (1 - q) + Color.blue(b) * q);
		return Color.rgb(r, g, bl);
	}

	@Nullable
	private static Bitmap loadBitmap(Context ctx, int id) {
		try {
			return BitmapFactory.decodeResource(ctx.getResources(), id);
		} catch (Throwable ex) {
			return null;
		}
	}

	@Nullable
	private static Bitmap loadSfera(Context ctx) {
		try {
			return BitmapFactory.decodeResource(ctx.getResources(), R.drawable.jarvis_sfera);
		} catch (Throwable ex) {
			return null;
		}
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
