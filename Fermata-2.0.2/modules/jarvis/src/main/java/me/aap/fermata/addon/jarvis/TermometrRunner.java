package me.aap.fermata.addon.jarvis;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.utils.log.Log;

/**
 * Обновляет температуру для виджета на вкладке со сферой ({@link Termometr} — правила, этот класс — сеть,
 * местоположение и память). Работает, только пока вкладка на экране: {@link #start()} / {@link #stop()}.
 * Последнее значение хранится — после перезапуска оно на экране сразу, без сети — ещё час.
 */
final class TermometrRunner {
	interface Sink {
		void show(@NonNull String temp, @NonNull String caption);
	}

	/** Раз в минуту — проверить, не устарело ли значение и не пора ли обновить. */
	private static final long TICK_MS = 60_000L;
	/** Не получилось (нет сети, нет местоположения) — попробовать снова через 2 минуты, а не через 15. */
	private static final long RETRY_MS = 2 * 60_000L;
	private static final String UA = "JARVIS-Auto/1.0 (personal car app)";

	private final Context ctx;
	private final Sink sink;
	private final SharedPreferences prefs;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final ExecutorService exec = Executors.newSingleThreadExecutor();
	private boolean running;
	private boolean fetching;
	private long nextAt;
	private final Runnable tick = this::tick;

	TermometrRunner(@NonNull Context ctx, @NonNull Sink sink) {
		this.ctx = ctx.getApplicationContext();
		this.sink = sink;
		prefs = this.ctx.getSharedPreferences("jarvis_termometr", Context.MODE_PRIVATE);
		nextAt = prefs.getLong("at", 0L) + Termometr.REFRESH_MS;
	}

	void start() {
		if (running) return;
		running = true;
		tick();
	}

	void stop() {
		running = false;
		main.removeCallbacks(tick);
	}

	void destroy() {
		stop();
		exec.shutdownNow();
	}

	private void tick() {
		main.removeCallbacks(tick);
		if (!running) return;
		long now = System.currentTimeMillis();
		show(now);
		// nextAt: после удачи — через 15 мин, после неудачи — через 2; часы перевели назад — сразу
		if (!fetching && ((now >= nextAt) || (nextAt - now > Termometr.REFRESH_MS))) {
			fetching = true;
			try {
				exec.execute(this::fetch);
			} catch (Exception e) {
				fetching = false;
			}
		}
		main.postDelayed(tick, TICK_MS);
	}

	private void show(long now) {
		String t = prefs.getString("temp", null);
		Double v = null;
		if (t != null) {
			try {
				v = Double.parseDouble(t);
			} catch (NumberFormatException ignore) {
			}
		}
		sink.show(Termometr.shown(v, prefs.getLong("at", 0L), now), Termometr.caption(prefs.getString("place", "")));
	}

	/** Фоновый поток: местоположение → температура → (если переехали) название. */
	private void fetch() {
		boolean ok = false;
		try {
			Location l = lastLocation();
			if (l == null) {
				Log.i("JARVIS temp: no location (permission or none)");
				return;
			}
			double lat = l.getLatitude(), lon = l.getLongitude();
			Double t = Termometr.parseTemp(get(Termometr.weatherUrl(lat, lon)));
			if (t == null) {
				Log.i("JARVIS temp: no temperature in reply");
				return;
			}
			SharedPreferences.Editor e = prefs.edit().putString("temp", Double.toString(t))
					.putLong("at", System.currentTimeMillis());
			String place = prefs.getString("place", "");
			boolean moved = place.isEmpty() || Termometr.moved(
					Double.longBitsToDouble(prefs.getLong("plat", 0L)), Double.longBitsToDouble(prefs.getLong("plon", 0L)),
					lat, lon);
			if (moved) {
				String p = Termometr.parsePlace(get(Termometr.placeUrl(lat, lon)));
				// Не определилось — не держать старое название чужого места
				e.putString("place", p).putLong("plat", Double.doubleToLongBits(lat))
						.putLong("plon", Double.doubleToLongBits(lon));
				place = p;
			}
			e.apply();
			ok = true;
			Log.i("JARVIS temp: ", Termometr.format(t), " ", place, moved ? " (place updated)" : "");
		} catch (Throwable ex) {
			Log.i("JARVIS temp: failed ", ex);
		} finally {
			boolean done = ok;
			main.post(() -> {
				fetching = false;
				long now = System.currentTimeMillis();
				nextAt = now + (done ? Termometr.REFRESH_MS : RETRY_MS);
				if (running) show(now);
			});
		}
	}

	@Nullable
	private static String get(String url) {
		HttpURLConnection c = null;
		try {
			c = (HttpURLConnection) new URL(url).openConnection();
			c.setConnectTimeout(10_000);
			c.setReadTimeout(10_000);
			c.setRequestProperty("User-Agent", UA);
			c.setRequestProperty("Accept", "application/json");
			if (c.getResponseCode() != 200) {
				Log.i("JARVIS temp: HTTP ", c.getResponseCode(), " ", url);
				return null;
			}
			try (InputStream in = c.getInputStream()) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buf = new byte[8192];
				for (int n; (n = in.read(buf)) > 0; ) {
					out.write(buf, 0, n);
					if (out.size() > 256 * 1024) break;
				}
				return out.toString(StandardCharsets.UTF_8.name());
			}
		} catch (Exception e) {
			Log.i("JARVIS temp: ", e.getClass().getSimpleName(), " ", url);
			return null;
		} finally {
			if (c != null) c.disconnect();
		}
	}

	/** Последнее известное местоположение — как в {@link FuelRunner}; null — нет или нет разрешения. */
	@Nullable
	private Location lastLocation() {
		if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
				ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			return null;
		}
		LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
		if (lm == null) return null;
		Location best = null;
		for (String p : new String[]{"fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
			try {
				@SuppressWarnings("MissingPermission") Location l = lm.getLastKnownLocation(p);
				if ((l != null) && ((best == null) || (l.getTime() > best.getTime()))) best = l;
			} catch (Exception ignore) {
			}
		}
		return best;
	}
}
