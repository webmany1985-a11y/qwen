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
	// Золото, как сфера JARVIS из фильма; пока слушает — бирюза (HudColor, пожелание 31.10.2026)
	private int arc = HudColor.GOLD_ARC;
	private int arcSoft = HudColor.GOLD_SOFT;
	private int ink = HudColor.GOLD_INK;
	private int hot = HudColor.GOLD_HOT;
	private int flareHot = HudColor.GOLD_FLARE;
	/** 0 — золото, 1 — бирюза. */
	private float tealMix;
	private float sphereMatrixMix = -1f;
	@Nullable
	private android.graphics.ColorFilter sphereTint;
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

	// ------------------------------------------------------------- температура в левом верхнем углу (14.11.2026)
	/** Цифра («−3°»); null — виджет не показывается. Задаёт вкладка ({@link #setWeather}). */
	@Nullable
	private String weatherTemp;
	private String weatherCaption = "";
	private final Paint weatherBig = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint weatherSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint weatherIcon = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path weatherBulb = new Path();
	private final RectF weatherBox = new RectF();

	// ------------------------------------------------------------- значок по погоде (14.11.2026, Pogoda)
	private Pogoda.Kind weatherKind = Pogoda.Kind.FIRE;
	/** Слои значка, перекрашенные под погоду; null — огонь, исходные слои. */
	@Nullable
	private Bitmap opelK, ogonK;
	/** Сосульки, капли, иней — поверх значка, в его масштабе. */
	@Nullable
	private Bitmap weatherOverlay;
	@Nullable
	private Paint[] weatherPuff;
	@Nullable
	private Pogoda.Fx weatherFx;
	private final Paint fxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	@Nullable
	private Paint mistPaint;

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
		initWeatherPaints();
		setClickable(true);
		setFocusable(true);
	}

	/**
	 * Температура в левом верхнем углу: temp — «−3°» или «—°», caption — «за бортом · Минск»; temp == null — убрать.
	 * Рисуется поверх сферы без подложки; касаний не берёт — касание там, как и раньше, показывает строку приложений.
	 */
	public void setWeather(@Nullable String temp, @Nullable String caption) {
		String c = (caption == null) ? "" : caption;
		if (java.util.Objects.equals(temp, weatherTemp) && c.equals(weatherCaption)) return;
		weatherTemp = temp;
		weatherCaption = c;
		invalidate();
	}

	/** Значок в покое — по погоде: огонь, лёд со снегом, мокрый под дождём, в инее на морозе. */
	public void setWeatherKind(@NonNull Pogoda.Kind k) {
		if ((k == weatherKind) || (opel == null) || (ogon == null)) return;
		weatherKind = k;
		if (k == Pogoda.Kind.FIRE) {
			opelK = ogonK = weatherOverlay = null;
			weatherPuff = null;
			weatherFx = null;
			mistPaint = null;
		} else {
			try {
				opelK = recolored(opel, k, true);
				ogonK = recolored(ogon, k, false);
				weatherOverlay = overlayFor(k, opel.getWidth());
				weatherPuff = puffPaints(k);
				weatherFx = new Pogoda.Fx(k);
				mistPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
				mistPaint.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFC8E1F5, PorterDuff.Mode.MULTIPLY));
			} catch (Throwable ex) {
				// Не хватило памяти и т. п. — остаётся огонь
				weatherKind = Pogoda.Kind.FIRE;
				opelK = ogonK = weatherOverlay = null;
				weatherPuff = null;
				weatherFx = null;
			}
		}
		invalidate();
	}

	public Pogoda.Kind getWeatherKind() {
		return weatherKind;
	}

	private static Bitmap recolored(Bitmap src, Pogoda.Kind k, boolean badge) {
		int w = src.getWidth(), h = src.getHeight();
		int[] px = new int[w * h];
		src.getPixels(px, 0, w, 0, 0, w, h);
		if (badge) Pogoda.recolorBadge(px, w, k);
		else Pogoda.recolorRing(px, k);
		return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
	}

	/** Живые языки поверх кольца — в палитре погоды (у огня — свои цвета, {@link #firePaint}). */
	private Paint[] puffPaints(Pogoda.Kind k) {
		Paint[] ps = new Paint[FIRE_SHADES];
		PorterDuffXfermode addMode = new PorterDuffXfermode(PorterDuff.Mode.ADD);
		for (int i = 0; i < FIRE_SHADES; i++) {
			Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
			int rgb = Pogoda.palette(k, 1f - 0.75f * (i + 0.5f) / FIRE_SHADES);
			p.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFF000000 | rgb, PorterDuff.Mode.MULTIPLY));
			p.setXfermode(addMode);
			ps[i] = p;
		}
		return ps;
	}

	/** Неподвижные детали в масштабе слоя значка (сторона w): сосульки — снег, капли и блик — дождь, иней — мороз. */
	@Nullable
	private static Bitmap overlayFor(Pogoda.Kind k, int w) {
		Bitmap b = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888);
		Canvas c = new Canvas(b);
		float u = w / 512f, cx = w / 2f;
		java.util.Random rnd = new java.util.Random(7);
		Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
		Path path = new Path();
		switch (k) {
			case SNOW:
				// Сосульки по низу хромового круга
				for (int i = 0; i < 16; i++) {
					double ang = Math.toRadians(40 + i * (100.0 / 15));
					float x = cx + (float) (103 * u * Math.cos(ang)), y = cx + (float) (103 * u * Math.sin(ang));
					float sin = (float) Math.sin(ang);
					float len = (18 + rnd.nextFloat() * 28) * sin * sin * u, wd = (2.5f + rnd.nextFloat() * 2.5f) * u;
					path.reset();
					path.moveTo(x - wd, y);
					path.lineTo(x + wd, y);
					path.lineTo(x + (rnd.nextFloat() - 0.5f) * 2 * u, y + len);
					path.close();
					p.setStyle(Paint.Style.FILL);
					p.setColor(0xD7D7F0FF);
					c.drawPath(path, p);
					p.setStyle(Paint.Style.STROKE);
					p.setStrokeWidth(u);
					p.setColor(0xC8FFFFFF);
					c.drawLine(x - wd * 0.3f, y + 2 * u, x, y + len * 0.7f, p);
				}
				break;
			case RAIN:
				p.setStyle(Paint.Style.STROKE);
				p.setStrokeWidth(5 * u);
				p.setColor(0xAAFFFFFF);
				p.setMaskFilter(new android.graphics.BlurMaskFilter(2 * u, android.graphics.BlurMaskFilter.Blur.NORMAL));
				c.drawArc(new RectF(cx - 118 * u, cx - 118 * u, cx + 118 * u, cx + 118 * u), 200, 50, false, p);
				p.setMaskFilter(null);
				p.setStyle(Paint.Style.FILL);
				for (int i = 0; i < 22; i++) {
					double a = rnd.nextFloat() * 2 * Math.PI;
					float r = (40 + rnd.nextFloat() * 85) * u, s = (2.5f + rnd.nextFloat() * 2.5f) * u;
					float x = cx + (float) (r * Math.cos(a)), y = cx + (float) (r * Math.sin(a));
					p.setColor(0x78C8E1FA);
					c.drawOval(x - s, y - s * 1.2f, x + s, y + s * 1.2f, p);
					p.setColor(0xDCFFFFFF);
					c.drawOval(x - s * 0.4f, y - s * 0.8f, x + s * 0.1f, y - s * 0.3f, p);
				}
				break;
			case FROST:
				p.setStyle(Paint.Style.STROKE);
				p.setStrokeWidth(u);
				p.setColor(0x96EBF8FF);
				for (int i = 0; i < 45; i++) {
					double a = rnd.nextFloat() * 2 * Math.PI;
					float r = (float) (103 + rnd.nextGaussian() * 6) * u, s = (1.5f + rnd.nextFloat() * 2f) * u;
					float x = cx + (float) (r * Math.cos(a)), y = cx + (float) (r * Math.sin(a));
					for (int j = 0; j < 3; j++) {
						double q = Math.toRadians(j * 60 + rnd.nextFloat() * 30);
						float dx = (float) (s * Math.cos(q)), dy = (float) (s * Math.sin(q));
						c.drawLine(x - dx, y - dy, x + dx, y + dy, p);
					}
				}
				break;
			default:
				return null;
		}
		return b;
	}

	/** Снежинки, струи дождя, пар — вокруг значка, в квадрате ±Fx.HALF радиусов кольца. */
	private void drawWeatherFx(Canvas c, float cx, float cy, float ringPx, float alpha) {
		Pogoda.Fx fx = weatherFx;
		if ((fx == null) || (alpha <= 0.01f)) return;
		fx.step(HudMath.clampDt(fireDt));
		for (int i = 0; i < fx.count; i++) {
			float x = cx + fx.x[i] * ringPx, y = cy + fx.y[i] * ringPx;
			float a = clamp(fx.alpha(i) * alpha, 0f, 1f);
			switch (fx.kind) {
				case SNOW:
					fxPaint.setColor(withAlpha(0xFFF0F8FF, a));
					if (fx.big[i]) {
						float r = fx.size[i] * ringPx;
						fxPaint.setStyle(Paint.Style.STROKE);
						fxPaint.setStrokeWidth(1.5f * dp);
						for (int k = 0; k < 3; k++) {
							float q = (float) Math.toRadians(k * 60) + fx.phase[i];
							float dx = r * (float) Math.cos(q), dy = r * (float) Math.sin(q);
							c.drawLine(x - dx, y - dy, x + dx, y + dy, fxPaint);
						}
					} else {
						fxPaint.setStyle(Paint.Style.FILL);
						c.drawCircle(x, y, Math.max(0.8f * dp, fx.size[i] * ringPx), fxPaint);
					}
					break;
				case RAIN:
					float len = fx.size[i] * ringPx;
					fxPaint.setStyle(Paint.Style.STROKE);
					fxPaint.setStrokeWidth(1f * dp);
					fxPaint.setColor(withAlpha(0xFFAACDF0, a));
					c.drawLine(x, y, x - len * 0.18f, y + len, fxPaint);
					break;
				default:
					Paint mp = mistPaint;
					if (mp == null) break;
					float r = fx.size[i] * ringPx * 2f;
					puffBox.set(x - r, y - r * 0.5f, x + r, y + r * 0.5f);
					mp.setAlpha(Math.round(255f * a));
					c.drawBitmap(puff, null, puffBox, mp);
			}
		}
	}

	@Nullable
	public String getWeatherTemp() {
		return weatherTemp;
	}

	public String getWeatherCaption() {
		return weatherCaption;
	}

	private void initWeatherPaints() {
		// Золото сферы (HudColor.GOLD_HOT); тень вместо подложки — цифра читается и на светлом фото
		int shadow = 0xE6000000;
		weatherBig.setColor(HudColor.GOLD_HOT);
		weatherBig.setTypeface(Typeface.create(Typeface.SANS_SERIF, 300, false));
		weatherBig.setTextSize(30 * dp);
		weatherBig.setShadowLayer(4 * dp, 0f, 0f, shadow);
		weatherSmall.setColor(withAlpha(HudColor.GOLD_HOT, 0.8f));
		weatherSmall.setTypeface(wordFace);
		weatherSmall.setTextSize(11 * dp);
		weatherSmall.setLetterSpacing(0.03f);
		weatherSmall.setShadowLayer(3 * dp, 0f, 0f, shadow);
		weatherIcon.setColor(HudColor.GOLD_HOT);
		weatherIcon.setStrokeWidth(1.6f * dp);
		weatherIcon.setStrokeCap(Paint.Cap.ROUND);
		weatherIcon.setShadowLayer(3 * dp, 0f, 0f, shadow);
	}

	/** Термометр, цифра и подпись — в левом верхнем углу, отступ 16 dp. */
	private void drawWeather(Canvas c) {
		String t = weatherTemp;
		if (t == null) return;
		float x = 16 * dp;
		float top = 12 * dp;
		// Термометр: трубка 6×22 dp со скруглённым верхом и шарик r = 5 dp
		float iw = 6 * dp, ih = 22 * dp, br = 5 * dp;
		float icx = x + br;
		weatherIcon.setStyle(Paint.Style.STROKE);
		weatherBulb.reset();
		weatherBox.set(icx - iw / 2f, top + 2 * dp, icx + iw / 2f, top + 2 * dp + iw);
		weatherBulb.arcTo(weatherBox, 180f, 180f, false);
		weatherBulb.lineTo(icx + iw / 2f, top + ih);
		weatherBox.set(icx - br, top + ih - br * 0.4f, icx + br, top + ih + br * 1.6f);
		weatherBulb.arcTo(weatherBox, -60f, 300f, false);
		weatherBulb.close();
		c.drawPath(weatherBulb, weatherIcon);
		weatherIcon.setStyle(Paint.Style.FILL);
		float bulbCy = top + ih + br * 0.6f;
		c.drawCircle(icx, bulbCy, br * 0.55f, weatherIcon);
		c.drawLine(icx, bulbCy, icx, top + 9 * dp, weatherIcon);

		float tx = x + 2 * br + 8 * dp;
		Paint.FontMetrics fm = weatherBig.getFontMetrics();
		float base = top - fm.ascent - 4 * dp;
		c.drawText(t, tx, base, weatherBig);
		if (!weatherCaption.isEmpty()) {
			Paint.FontMetrics sm = weatherSmall.getFontMetrics();
			c.drawText(weatherCaption, tx + 1 * dp, base + fm.descent - sm.ascent, weatherSmall);
		}
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
		applyColor(HudColor.step(tealMix, HudColor.listening(phase), dt));
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
		drawWeather(c);

		if (isShown()) postInvalidateOnAnimation();
	}

	/** Цвета кадра по смешению золото/бирюза; фильтр сферы-картинки — только когда смешение изменилось. */
	private void applyColor(float mix) {
		tealMix = mix;
		arc = HudColor.lerp(HudColor.GOLD_ARC, HudColor.TEAL_ARC, mix);
		arcSoft = HudColor.lerp(HudColor.GOLD_SOFT, HudColor.TEAL_SOFT, mix);
		ink = HudColor.lerp(HudColor.GOLD_INK, HudColor.TEAL_INK, mix);
		hot = HudColor.lerp(HudColor.GOLD_HOT, HudColor.TEAL_HOT, mix);
		flareHot = HudColor.lerp(HudColor.GOLD_FLARE, HudColor.TEAL_FLARE, mix);
		if (mix == sphereMatrixMix) return;
		sphereMatrixMix = mix;
		sphereTint = (mix <= 0f) ? null :
				new android.graphics.ColorMatrixColorFilter(HudColor.sphereMatrix(mix));
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
				new int[]{withAlpha(hot, clamp(0.55f + level * 0.45f, 0f, 1f)),
						withAlpha(arc, clamp(0.35f + level * 0.4f, 0f, 1f)), withAlpha(arc, 0f)},
				new float[]{0f, 0.18f, 1f}, Shader.TileMode.CLAMP));
		c.drawCircle(cx, cy, coreR, plus);
		plus.setShader(null);

		// Горизонтальная вспышка
		float flare = HudMath.flare(phase, t, level);
		if (flare > 0.01f) {
			float half = s * 0.006f * (1f + flare * 2f);
			plus.setShader(new LinearGradient(cx - s * 0.5f, 0f, cx + s * 0.5f, 0f,
					new int[]{withAlpha(arc, 0f), withAlpha(flareHot, clamp(flare, 0f, 1f)),
							withAlpha(arc, 0f)},
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
			stroke.setColor(withAlpha(arcSoft, 0.18f * open));
			c.drawLine(px, py, cx + (float) Math.cos(ma) * md, cy + (float) Math.sin(ma) * md, stroke);
			plus.setColor(withAlpha(arc, 0.5f * open));
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
			int color = (i % 3 == 0) ? arcSoft : arc;
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
					new int[]{withAlpha(arc, 0.35f), withAlpha(arc, 0f), withAlpha(arc, 0f)},
					new float[]{0f, 0.12f, 1f}));
			c.drawCircle(cx, cy, s * 0.3f, plus);
			plus.setShader(null);
			c.restore();
		}

		// Надпись в центре. Размер привязан к кругу, а не к масштабу шрифта системы
		text.setTypeface(titleFace);
		text.setTextSize(s * 0.05f);
		text.setColor(withAlpha(ink, open));
		text.setShadowLayer(12f * dp, 0f, 0f, arc);
		drawCentered(c, title, cx, cy - s * 0.015f);
		text.clearShadowLayer();

		String word = (phase == HudMath.Phase.LISTEN) ? listening :
				(phase == HudMath.Phase.THINK) ? thinking : "";
		if (!word.isEmpty()) {
			text.setTypeface(wordFace);
			text.setTextSize(s * 0.032f);
			text.setColor(withAlpha(arc, open * 0.85f));
			drawCentered(c, word, cx, cy + s * 0.05f);
		}

		// Эквалайзер под надписью
		if (HudMath.showsEqualizer(phase)) {
			int bars = 13;
			float bw = s * 0.007f;
			float gap = s * 0.006f;
			float x0 = cx - (bars * (bw + gap)) / 2f;
			fill.setColor(withAlpha(arc, 0.9f));
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
				dots.setColor(withAlpha(arc, a * 0.22f));
				c.drawPoints(bucketPts[bkt], 0, bucketSize[bkt], dots);
				dots.setStrokeWidth(size);
				dots.setColor(withAlpha((tw == 3 && depth == 2) ? ink : arc, a));
				c.drawPoints(bucketPts[bkt], 0, bucketSize[bkt], dots);
			}
		}

		// Вспышки-фрагменты на поверхности
		for (HudMath.Bit b : HudMath.BITS) {
			HudMath.project(b.lat, b.lon, rot, pt);
			HudMath.project(b.lat, b.lon + b.len, rot, pt2);
			float a = HudMath.bitAlpha(b, t, pt[2], level) * open * 0.8f;
			stroke.setStrokeWidth(b.w * 0.8f * dp * (1f + level * 0.6f));
			stroke.setColor(withAlpha((b.phase > 3f) ? ink : arc, a));
			c.drawLine(cx + pt[0] * rs, cy + pt[1] * rs, cx + pt2[0] * rs, cy + pt2[1] * rs, stroke);
		}

		// Мягкое свечение контура
		stroke.setStrokeWidth(10f * dp);
		stroke.setColor(withAlpha(arc, 0.07f * open * (0.7f + level)));
		c.drawCircle(cx, cy, rs, stroke);

		// Вихрь в центре: дуга навстречу вращению сферы
		float vr = rs * (0.55f + 0.06f * level);
		float va = (-t * 1.3f * RAD_TO_DEG) % 360f;
		box.set(cx - vr, cy - vr * 0.6f, cx + vr, cy + vr * 0.6f);
		stroke.setStrokeWidth(8f * dp);
		stroke.setColor(withAlpha(arc, 0.14f * open));
		c.drawArc(box, va, 240f, false, stroke);
		stroke.setStrokeWidth(1.8f * dp);
		stroke.setColor(withAlpha(ink, 0.6f * open));
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
		image.setColorFilter(sphereTint);
		c.drawBitmapMesh(b, MESH, MESH, mesh, 0, null, 0, image);
		image.setColorFilter(null);

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
		dots.setColor(withAlpha(arc, 0.25f * open));
		c.drawPoints(pts, 0, n, dots);
		dots.setStrokeWidth(1.8f * dp * (1f + level * 0.6f));
		dots.setColor(withAlpha(ink, 0.9f * open));
		c.drawPoints(pts, 0, n, dots);
	}

	/**
	 * Значок в огне. Сам значок неподвижен; огонь живёт: слой пламени натянут на сетку, узлы которой гонят волны
	 * {@link EmblemMorph#flame} от кольца наружу, яркость неровно мерцает; второй слой того же огня, чуть больше и
	 * медленно покачиваясь, с другой турбулентностью — языки не повторяют друг друга.
	 */
	private void drawEmblem(Canvas c, float alpha) {
		Bitmap z = (opelK != null) ? opelK : opel, f = (ogonK != null) ? ogonK : ogon;
		if ((z == null) || (f == null) || (alpha <= 0.01f)) return;
		Pogoda.Kind wk = weatherKind;
		Paint[] shades = (weatherPuff != null) ? weatherPuff : firePaint;
		float puffGain = (wk == Pogoda.Kind.RAIN) ? 0.4f : (wk == Pogoda.Kind.FROST) ? 0.5f : 1f;
		float t = since;
		float cx = getWidth() / 2f, cy = getHeight() / 2f;
		float ringPx = Math.min(getWidth(), getHeight()) * EmblemMorph.RING_OF_VIEW;
		float sc = ringPx / (EmblemMorph.RING_OF_SIDE * f.getWidth());

		// Подложка — огонь с фото, приглушён и неровно мерцает; поверх — живые языки пламени
		fireMesh(f, cx, cy, sc, 1f, 0f, t, 0f);
		image.setAlpha(Math.round(255f * clamp(alpha * 0.55f * Pogoda.ringGain(wk) * EmblemMorph.flicker(t, 0f), 0f, 1f)));
		c.drawBitmapMesh(f, FIRE_MESH, FIRE_MESH, opelMesh, 0, null, 0, image);
		fire.step(HudMath.clampDt(fireDt));
		for (int i = 0; i < EmblemMorph.Fire.MAX; i++) {
			if (!fire.alive[i]) continue;
			float a = fire.alpha(i) * alpha * puffGain;
			if (a < 0.004f) continue;
			// Пятно — гауссово с краем на 2 радиусах, как в предпросмотре
			float r = fire.radius(i) * ringPx * 2f;
			float px = cx + fire.x[i] * ringPx, py = cy + fire.y[i] * ringPx;
			puffBox.set(px - r, py - r, px + r, py + r);
			Paint p = shades[Math.min(FIRE_SHADES - 1, (int) (fire.progress(i) * FIRE_SHADES))];
			p.setAlpha(Math.round(255f * clamp(a, 0f, 1f)));
			c.drawBitmap(puff, null, puffBox, p);
		}

		// Значок поверх огня — обычным наложением: хром не должен просвечивать пламенем
		float zs = ringPx / (EmblemMorph.RING_OF_SIDE * z.getWidth());
		float hw = z.getWidth() * zs / 2f, hh = z.getHeight() * zs / 2f;
		box.set(cx - hw, cy - hh, cx + hw, cy + hh);
		logoPaint.setAlpha(Math.round(255f * clamp(alpha, 0f, 1f)));
		c.drawBitmap(z, null, box, logoPaint);
		Bitmap ov = weatherOverlay;
		if (ov != null) c.drawBitmap(ov, null, box, logoPaint);
		drawWeatherFx(c, cx, cy, ringPx, alpha);
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
			dots.setColor(withAlpha(arc, a * 0.18f));
			c.drawPoint(x, y, dots);
			dots.setStrokeWidth(1.8f * dp);
			dots.setColor(withAlpha(blend(sp.color[j], arc, m), a));
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

}
