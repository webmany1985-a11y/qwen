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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.utils.log.Log;

/**
 * Заправка голосом в JARVIS Auto: выполняет действия {@link FuelDialog} на сервере А-100 ({@link FuelApi}) в фоне —
 * без переключения вкладок (пожелание 05.11.2026) — и отдаёт следующий шаг диалога в главном потоке.
 * В журнал — шаги и коды ответов, без входа, номеров карт и сумм.
 */
final class FuelRunner {
	interface Callback {
		void onStep(FuelDialog.Step step);
	}

	private final Context ctx;
	private final FuelDialog dialog;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final ExecutorService exec = Executors.newSingleThreadExecutor();
	@Nullable
	private FuelApi.Station station;
	@Nullable
	private FuelApi.Ready ready;
	@Nullable
	private FuelApi.Calc calc;

	FuelRunner(@NonNull Context ctx, @NonNull FuelDialog dialog) {
		this.ctx = ctx.getApplicationContext();
		this.dialog = dialog;
	}

	private SharedPreferences prefs() {
		return ctx.getSharedPreferences(FuelApi.PREFS, Context.MODE_PRIVATE);
	}

	@Nullable
	private String token() {
		return prefs().getString(FuelApi.KEY_TOKEN, null);
	}

	boolean hasLogin() {
		return token() != null;
	}

	/** Голосовая оплата разрешена после первой оплаты касанием во вкладке (критерий 9). */
	boolean voicePayAllowed() {
		return prefs().getBoolean(FuelApi.KEY_PAID_OK, false);
	}

	/** Подготовить: станция (ближайшая + сдвиг), колонка (ready), расчёт. */
	void prepare(Callback cb) {
		String tok = token();
		if (tok == null) {
			post(cb, new FuelDialog.Step("Сначала войдите в А-100 во вкладке «Заправка» — один раз.", false, FuelDialog.Effect.END));
			return;
		}
		final int pump = dialog.pump, liters = dialog.liters, rubles = dialog.rubles, shift = dialog.stationShift;
		Location loc = lastLocation();
		exec.execute(() -> {
			long now = System.currentTimeMillis();
			try {
				FuelApi api = new FuelApi(tok);
				List<FuelApi.Station> list = api.stations();
				if (list.isEmpty()) throw new FuelApi.ServerException(0, "нет списка станций");
				if (loc != null) list = FuelApi.sortByDistance(list, loc.getLatitude(), loc.getLongitude());
				FuelApi.Station s = list.get(Math.min(shift, list.size() - 1));
				Log.i("JARVIS fuel: station ", (s.distance >= 0 && s.distance <= FuelDialog.FAR_M) ? "near" : "far/unknown", ", shift ", shift);
				FuelApi.Ready r;
				try {
					r = api.ready(s.number, pump);
				} catch (FuelApi.ServerException ex) {
					if (ex.code == 400 || ex.code == 404 || ex.code == 422) {
						Log.i("JARVIS fuel: pump not active (", ex.code, ")");
						post(cb, () -> dialog.onPumpInactive(System.currentTimeMillis()));
						return;
					}
					throw ex;
				}
				int l = liters;
				if (rubles > 0) l = (r.price > 0) ? (int) Math.floor(rubles / r.price) : 0;
				if (l <= 0) {
					post(cb, () -> dialog.onServerError("сумма меньше цены литра", System.currentTimeMillis()));
					return;
				}
				FuelApi.Calc c = api.calculate(s.number, r.nozzle, pump, l);
				station = s;
				ready = r;
				calc = c;
				Log.i("JARVIS fuel: calculated");
				FuelDialog.Prepared p = new FuelDialog.Prepared(s.number, r.fuel, c.liters, c.amount, (int) Math.round(s.distance));
				post(cb, () -> dialog.onPrepared(p, System.currentTimeMillis()));
			} catch (FuelApi.AuthException ex) {
				Log.i("JARVIS fuel: login expired");
				post(cb, new FuelDialog.Step("Вход в А-100 устарел. Войдите снова во вкладке «Заправка».", false, FuelDialog.Effect.END));
			} catch (FuelApi.NetException ex) {
				Log.i("JARVIS fuel: network error");
				post(cb, () -> dialog.onNetError(System.currentTimeMillis()));
			} catch (FuelApi.ServerException ex) {
				Log.i("JARVIS fuel: server error ", ex.code);
				String m = ex.getMessage();
				post(cb, () -> dialog.onServerError(m, System.currentTimeMillis()));
			} catch (Exception ex) {
				Log.e(ex, "JARVIS fuel: prepare failed");
				post(cb, () -> dialog.onNetError(now));
			}
		});
	}

	/** Оплатить сохранённой картой (после фразы оплаты). */
	void pay(Callback cb) {
		String tok = token();
		FuelApi.Station s = station;
		FuelApi.Ready r = ready;
		FuelApi.Calc c = calc;
		int pump = dialog.pump;
		if ((tok == null) || (s == null) || (r == null) || (c == null)) {
			post(cb, () -> dialog.onServerError("нет расчёта", System.currentTimeMillis()));
			return;
		}
		exec.execute(() -> {
			try {
				String res = new FuelApi(tok).pay(s, pump, r, c);
				if (FuelApi.NEEDS_BANK.equals(res)) {
					Log.i("JARVIS fuel: bank page needed");
					post(cb, () -> dialog.onNeedsBank(System.currentTimeMillis()));
				} else {
					Log.i("JARVIS fuel: paid");
					post(cb, () -> dialog.onPaid(System.currentTimeMillis()));
				}
			} catch (FuelApi.AuthException ex) {
				post(cb, new FuelDialog.Step("Вход в А-100 устарел. Войдите снова во вкладке «Заправка».", false, FuelDialog.Effect.END));
			} catch (FuelApi.NetException ex) {
				Log.i("JARVIS fuel: network error on payment");
				post(cb, () -> dialog.onNetError(System.currentTimeMillis()));
			} catch (FuelApi.ServerException ex) {
				Log.i("JARVIS fuel: payment error ", ex.code);
				String m = ex.getMessage();
				post(cb, () -> dialog.onServerError(m, System.currentTimeMillis()));
			} catch (Exception ex) {
				Log.e(ex, "JARVIS fuel: payment failed");
				post(cb, () -> dialog.onNetError(System.currentTimeMillis()));
			}
		});
	}

	private interface StepSupplier {
		FuelDialog.Step get();
	}

	private void post(Callback cb, StepSupplier s) {
		main.post(() -> cb.onStep(s.get()));
	}

	private void post(Callback cb, FuelDialog.Step s) {
		main.post(() -> cb.onStep(s));
	}

	/** Последнее известное местоположение (GPS, сеть, «сплавленное»); null — нет или нет разрешения. */
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
