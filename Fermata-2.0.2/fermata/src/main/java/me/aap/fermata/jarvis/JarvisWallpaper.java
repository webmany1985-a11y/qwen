package me.aap.fermata.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.utils.log.Log;

/**
 * Фон темы JARVIS: фото jarvis_wallpaper (drawable-port-nodpi — телефон, drawable-nodpi — остальное),
 * растянутое с обрезкой краёв, без искажения пропорций — как centerCrop.
 * Вертикальное фото — для телефона, широкое — для экрана машины.
 */
public final class JarvisWallpaper extends Drawable {
	private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
	private final Matrix matrix = new Matrix();
	@Nullable
	private final Bitmap bitmap;

	public JarvisWallpaper(Context ctx) {
		bitmap = decode(ctx);
	}

	@Nullable
	private static Bitmap decode(Context ctx) {
		try {
			DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
			int max = Math.max(dm.widthPixels, dm.heightPixels);
			BitmapFactory.Options o = new BitmapFactory.Options();
			o.inJustDecodeBounds = true;
			BitmapFactory.decodeResource(ctx.getResources(), R.drawable.jarvis_wallpaper, o);
			int big = Math.max(o.outWidth, o.outHeight);
			int sample = 1;
			while (big / (sample * 2) >= max) sample *= 2;
			o = new BitmapFactory.Options();
			o.inSampleSize = sample;
			return BitmapFactory.decodeResource(ctx.getResources(), R.drawable.jarvis_wallpaper, o);
		} catch (Throwable ex) {
			Log.e(ex, "JARVIS: failed to load wallpaper");
			return null;
		}
	}

	@Override
	protected void onBoundsChange(@NonNull Rect b) {
		super.onBoundsChange(b);
		if (bitmap == null || b.isEmpty()) return;
		float bw = bitmap.getWidth(), bh = bitmap.getHeight();
		float scale = Math.max(b.width() / bw, b.height() / bh);
		matrix.setScale(scale, scale);
		// Машина на фото внизу: по высоте прижимаем к низу, по ширине — по центру
		matrix.postTranslate(b.left + (b.width() - bw * scale) / 2f,
				b.top + (b.height() - bh * scale));
	}

	@Override
	public void draw(@NonNull Canvas c) {
		c.drawColor(0xFF000000);
		if (bitmap != null) c.drawBitmap(bitmap, matrix, paint);
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
