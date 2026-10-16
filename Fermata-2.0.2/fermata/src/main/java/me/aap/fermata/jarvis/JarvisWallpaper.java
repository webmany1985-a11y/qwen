package me.aap.fermata.jarvis;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.utils.log.Log;

/**
 * Фон темы JARVIS: фото Opel. Какое фото — решает форма области, а не
 * ресурсные квалификаторы: у экрана машины в Android Auto ориентация
 * бывает не той, что ждёшь.
 *
 * <ul>
 * <li>Высокая область (телефон) — вертикальное фото jarvis_wallpaper_port,
 * растянутое с обрезкой краёв, машина прижата к низу.</li>
 * <li>Широкая (машина) — широкое фото jarvis_wallpaper. Машина ставится
 * целиком между верхней строкой и нижними панелями: если просто растянуть
 * фото, Opel уходит под панели (замечание 16.10.2026). Где фото не
 * закрывает экран, края плавно уходят в чёрное.</li>
 * </ul>
 */
public final class JarvisWallpaper extends Drawable {
	/** Машина на широком фото, доли ширины и высоты: left, top, right, bottom. */
	private static final float CAR_L = 0.17f, CAR_T = 0.25f, CAR_R = 0.855f, CAR_B = 0.9f;
	/** Где на широком экране центр машины по высоте и какую долю высоты она занимает. */
	private static final float CAR_CY = 0.42f, CAR_H = 0.55f;
	/** Доля ширины/высоты фото, на которой край уходит в чёрное. */
	private static final float FADE = 0.12f;

	private final Resources res;
	private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
	private final Paint fade = new Paint();
	private final Matrix matrix = new Matrix();
	private final RectF img = new RectF();
	@Nullable
	private Bitmap bitmap;
	private boolean wide;

	public JarvisWallpaper(Context ctx) {
		res = ctx.getResources();
	}

	@Nullable
	private Bitmap decode(int id, int max) {
		try {
			BitmapFactory.Options o = new BitmapFactory.Options();
			o.inJustDecodeBounds = true;
			BitmapFactory.decodeResource(res, id, o);
			int big = Math.max(o.outWidth, o.outHeight);
			int sample = 1;
			while (big / (sample * 2) >= max) sample *= 2;
			o = new BitmapFactory.Options();
			o.inSampleSize = sample;
			return BitmapFactory.decodeResource(res, id, o);
		} catch (Throwable ex) {
			Log.e(ex, "JARVIS: failed to load wallpaper");
			return null;
		}
	}

	@Override
	protected void onBoundsChange(@NonNull Rect b) {
		super.onBoundsChange(b);
		if (b.isEmpty()) return;
		boolean w = b.width() >= b.height();
		if ((bitmap == null) || (w != wide)) {
			wide = w;
			bitmap = decode(w ? R.drawable.jarvis_wallpaper : R.drawable.jarvis_wallpaper_port,
					Math.max(b.width(), b.height()));
		}
		Bitmap bm = bitmap;
		if (bm == null) return;
		float bw = bm.getWidth(), bh = bm.getHeight();
		float cover = Math.max(b.width() / bw, b.height() / bh);

		if (!wide) {
			matrix.setScale(cover, cover);
			// Машина на фото внизу: по высоте прижимаем к низу, по ширине — по центру
			matrix.postTranslate(b.left + (b.width() - bw * cover) / 2f,
					b.top + (b.height() - bh * cover));
			img.set(b);
			return;
		}

		float carW = (CAR_R - CAR_L) * bw, carH = (CAR_B - CAR_T) * bh;
		float scale = Math.min(cover, Math.min(0.92f * b.width() / carW, CAR_H * b.height() / carH));
		float iw = bw * scale, ih = bh * scale;
		float carCx = (CAR_L + CAR_R) / 2f * iw, carCy = (CAR_T + CAR_B) / 2f * ih;
		float tx = b.width() / 2f - carCx;
		float ty = b.height() * CAR_CY - carCy;
		// Где фото шире/выше экрана — не оставлять пустых полос
		if (iw >= b.width()) tx = Math.max(b.width() - iw, Math.min(0f, tx));
		if (ih >= b.height()) ty = Math.max(b.height() - ih, Math.min(0f, ty));
		tx += b.left;
		ty += b.top;
		matrix.setScale(scale, scale);
		matrix.postTranslate(tx, ty);
		img.set(tx, ty, tx + iw, ty + ih);
	}

	@Override
	public void draw(@NonNull Canvas c) {
		c.drawColor(0xFF000000);
		Bitmap bm = bitmap;
		if (bm == null) return;
		c.drawBitmap(bm, matrix, paint);
		if (!wide) return;
		Rect b = getBounds();
		float fx = img.width() * FADE, fy = img.height() * FADE;
		// Края фото, оказавшиеся внутри экрана, — в чёрное
		if (img.left > b.left) edge(c, img.left, img.top, img.left + fx, img.bottom, true, false);
		if (img.right < b.right) edge(c, img.right - fx, img.top, img.right, img.bottom, true, true);
		if (img.top > b.top) edge(c, img.left, img.top, img.right, img.top + fy, false, false);
		if (img.bottom < b.bottom) edge(c, img.left, img.bottom - fy, img.right, img.bottom, false, true);
	}

	private void edge(Canvas c, float l, float t, float r, float btm, boolean horizontal,
										boolean toEnd) {
		int black = 0xFF000000, clear = 0x00000000;
		int from = toEnd ? clear : black, to = toEnd ? black : clear;
		fade.setShader(horizontal ? new LinearGradient(l, 0, r, 0, from, to, Shader.TileMode.CLAMP) :
				new LinearGradient(0, t, 0, btm, from, to, Shader.TileMode.CLAMP));
		c.drawRect(l, t, r, btm, fade);
		fade.setShader(null);
	}

	@Override
	public void setAlpha(int alpha) {
		paint.setAlpha(alpha);
	}

	@Override
	public void setColorFilter(@Nullable ColorFilter cf) {
		paint.setColorFilter(cf);
	}

	@Override
	public int getOpacity() {
		return PixelFormat.OPAQUE;
	}
}
