package me.aap.fermata.addon.jarvis;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.aap.utils.log.Log;

/**
 * Сервер приложения А-100 для заправки голосом — те же запросы, что у своей вкладки «Заправка»
 * (assets/zapravka_a100.html модуля web; поля — по коду приложения А-100 и журналу 05.11.2026):
 * GET /azs, GET /azs/{N}/pumps/{p}/ready (400 — колонка не активна), POST /fuel/calculate,
 * GET /users/me (bank_cards), GET /users/{id}/loyalty-cards, POST /payment/recurring | /payment.
 * Вход — тот, что пользователь сделал во вкладке (вкладка передаёт его приложению, см. ZapravkaFragment).
 * Разбор ответов — статическими методами без сети (проверяется тестом).
 */
final class FuelApi {
	static final String API = "https://tgminiappback.a-100.by/api/v1";
	/** Общие настройки приложения: вход А-100 кладёт вкладка «Заправка» (модуль web). */
	static final String PREFS = "jarvis_a100";
	static final String KEY_TOKEN = "token";
	/** Вкладка ставит после первой успешной оплаты касанием — тогда разрешена оплата голосом (критерий 9). */
	static final String KEY_PAID_OK = "paid_ok";

	/** Нет связи (повторить можно). */
	static final class NetException extends IOException {
		NetException(Throwable cause) {
			super(cause);
		}
	}

	/** Вход устарел или не выполнен. */
	static final class AuthException extends IOException {
		AuthException() {
			super("auth");
		}
	}

	/** Ответ сервера с ошибкой (код и сообщение). */
	static final class ServerException extends IOException {
		final int code;

		ServerException(int code, String msg) {
			super(msg);
			this.code = code;
		}
	}

	static final class Station {
		final String number;
		final String name;
		final double lat;
		final double lon;
		final List<String[]> fuels; // {NAME, CODE}
		double distance = -1;

		Station(String number, String name, double lat, double lon, List<String[]> fuels) {
			this.number = number;
			this.name = name;
			this.lat = lat;
			this.lon = lon;
			this.fuels = fuels;
		}

		@Nullable
		String fuelCode(@Nullable String fuelName) {
			for (String[] f : fuels) if (f[0].equals(fuelName)) return f[1];
			return null;
		}
	}

	static final class Ready {
		final int nozzle;
		final String fuel;
		final double price;

		Ready(int nozzle, String fuel, double price) {
			this.nozzle = nozzle;
			this.fuel = fuel;
			this.price = price;
		}
	}

	static final class Calc {
		final double amount;
		final int liters;

		Calc(double amount, int liters) {
			this.amount = amount;
			this.liters = liters;
		}
	}

	private final String token;

	FuelApi(@NonNull String token) {
		this.token = token;
	}

	// ------------------------------------------------------------------ запросы
	List<Station> stations() throws IOException {
		return parseStations(call("GET", "/azs", null));
	}

	Ready ready(String azs, int pump) throws IOException {
		return parseReady(call("GET", "/azs/" + azs + "/pumps/" + pump + "/ready", null));
	}

	Calc calculate(String azs, int nozzle, int pump, int liters) throws IOException {
		JSONObject b = new JSONObject();
		try {
			b.put("liters", liters).put("azs_number", Integer.parseInt(azs)).put("nozzle_number", nozzle).put("pump_number", pump);
		} catch (Exception ex) {
			throw new IOException(ex);
		}
		return parseCalc(call("POST", "/fuel/calculate", b), liters);
	}

	/** Ответ {@link #pay}: нужна страница банка (нет карты или банк просит подтверждение). */
	static final String NEEDS_BANK = "needs-bank";

	/** Скидка А-100 по карте лояльности (POST /discount/calculate); ok=false — расчёт не удался. */
	static final class Discount {
		final boolean ok;
		final double amount;
		final double discount;

		Discount(boolean ok, double amount, double discount) {
			this.ok = ok;
			this.amount = amount;
			this.discount = discount;
		}

		static final Discount NONE = new Discount(false, 0, 0);
	}

	/**
	 * Оплата сохранённой основной картой: номер заказа или {@link #NEEDS_BANK}. Как в приложении А-100: сначала
	 * расчёт скидки по карте лояльности, затем оплата суммой со скидкой, sale_point и номером карты лояльности.
	 * Без расчёта скидки сервер отвечал 500 на оплату с картой лояльности (журнал 05.11.2026, 18:21–18:23).
	 */
	String pay(Station s, int pump, Ready r, Calc c) throws IOException {
		JSONObject me = call("GET", "/users/me", null);
		JSONObject card = mainCard(me);
		if (card == null) return NEEDS_BANK;
		String loyalty = null;
		long userId = userId(me);
		if (userId > 0) {
			try {
				loyalty = mainLoyalty(call("GET", "/users/" + userId + "/loyalty-cards", null));
			} catch (ServerException ignore) {
			}
		}
		Discount dc = Discount.NONE;
		try {
			dc = parseDiscount(call("POST", "/discount/calculate", discountBody(s, pump, r, c, client(me), loyalty)), c);
		} catch (ServerException ex) {
			Log.i("JARVIS fuel: discount failed ", ex.code);
		}
		JSONObject body = payBody(s, pump, r, c, card.optLong("id"), loyalty, dc);
		JSONObject ans = call("POST", "/payment/recurring", body);
		JSONObject d = ans.optJSONObject("data");
		if ((d != null) && d.has("redirectUrl") && !d.isNull("redirectUrl")) return NEEDS_BANK;
		return (d != null) ? d.optString("orderNumber", "") : "";
	}

	private JSONObject call(String method, String path, @Nullable JSONObject body) throws IOException {
		HttpURLConnection c;
		try {
			c = (HttpURLConnection) new URL(API + path).openConnection();
			c.setConnectTimeout(10000);
			c.setReadTimeout(15000);
			c.setRequestMethod(method);
			c.setRequestProperty("Accept", "application/json");
			c.setRequestProperty("Authorization", "Bearer " + token);
			if (body != null) {
				c.setDoOutput(true);
				c.setRequestProperty("Content-Type", "application/json");
				try (OutputStream o = c.getOutputStream()) {
					o.write(body.toString().getBytes(StandardCharsets.UTF_8));
				}
			}
		} catch (IOException ex) {
			throw new NetException(ex);
		}
		int code;
		String text;
		try {
			code = c.getResponseCode();
			InputStream in = (code < 400) ? c.getInputStream() : c.getErrorStream();
			text = (in == null) ? "" : read(in);
		} catch (IOException ex) {
			throw new NetException(ex);
		} finally {
			c.disconnect();
		}
		if (code == 401) throw new AuthException();
		JSONObject j;
		try {
			j = text.isEmpty() ? new JSONObject() : new JSONObject(text);
		} catch (Exception ex) {
			j = new JSONObject();
		}
		if (code >= 400) throw new ServerException(code, j.optString("message", "ошибка " + code));
		return j;
	}

	private static String read(InputStream in) throws IOException {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		byte[] b = new byte[8192];
		for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
		return o.toString("UTF-8");
	}

	// ------------------------------------------------------------------ разбор (без сети)
	static List<Station> parseStations(JSONObject j) {
		JSONArray a = j.optJSONArray("data");
		if (a == null) return Collections.emptyList();
		List<Station> out = new ArrayList<>();
		for (int i = 0; i < a.length(); i++) {
			JSONObject s = a.optJSONObject(i);
			if (s == null) continue;
			Object act = s.opt("ACTIVE");
			if ("N".equals(act) || Boolean.FALSE.equals(act) || Integer.valueOf(0).equals(act)) continue;
			String num = s.optString("NUMBER", "");
			if (num.isEmpty()) continue;
			String[] p = s.optString("POINT", "").split(",");
			double lat = Double.NaN, lon = Double.NaN;
			try {
				lat = Double.parseDouble(p[0].trim());
				lon = Double.parseDouble(p[1].trim());
			} catch (Exception ignore) {
			}
			String name = s.optString("NAME", "");
			if (name.isEmpty()) name = s.optString("NAME_RU", "");
			List<String[]> fuels = new ArrayList<>();
			JSONArray f = s.optJSONArray("FUEL");
			if (f != null) for (int k = 0; k < f.length(); k++) {
				JSONObject x = f.optJSONObject(k);
				if (x != null) fuels.add(new String[]{x.optString("NAME", ""), x.optString("CODE", "")});
			}
			out.add(new Station(num, name, lat, lon, fuels));
		}
		return out;
	}

	/** Станции по расстоянию от точки; без координат — в конце. */
	static List<Station> sortByDistance(List<Station> list, double lat, double lon) {
		List<Station> out = new ArrayList<>(list);
		for (Station s : out) s.distance = (Double.isNaN(s.lat) || Double.isNaN(lat)) ? -1 : dist(lat, lon, s.lat, s.lon);
		out.sort((a, b) -> Double.compare(a.distance < 0 ? 1e12 : a.distance, b.distance < 0 ? 1e12 : b.distance));
		return out;
	}

	static double dist(double la1, double lo1, double la2, double lo2) {
		double r = Math.PI / 180, dLa = (la2 - la1) * r, dLo = (lo2 - lo1) * r;
		double x = Math.sin(dLa / 2) * Math.sin(dLa / 2) + Math.cos(la1 * r) * Math.cos(la2 * r) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
		return 2 * 6371000 * Math.asin(Math.sqrt(x));
	}

	static Ready parseReady(JSONObject j) {
		JSONObject d = j.optJSONObject("data");
		if (d == null) d = new JSONObject();
		return new Ready(d.optInt("nozzle_number"), d.optString("fuel_name", ""), d.optDouble("price", 0));
	}

	static Calc parseCalc(JSONObject j, int liters) {
		JSONObject d = j.optJSONObject("data");
		if (d == null) d = new JSONObject();
		return new Calc(d.optDouble("amount", 0), d.has("liters") ? (int) d.optDouble("liters", liters) : liters);
	}

	/** Пользователь: data.user или data — как в приложении А-100 ({...data, ...data.user}). */
	private static JSONObject user(JSONObject me) {
		JSONObject d = me.optJSONObject("data");
		if (d == null) return new JSONObject();
		JSONObject u = d.optJSONObject("user");
		return (u != null) ? u : d;
	}

	static long userId(JSONObject me) {
		return user(me).optLong("id", 0);
	}

	/** Основная сохранённая карта (is_main) или первая; null — карт нет. */
	@Nullable
	static JSONObject mainCard(JSONObject me) {
		JSONArray a = user(me).optJSONArray("bank_cards");
		if (a == null) {
			JSONObject d = me.optJSONObject("data");
			if (d != null) a = d.optJSONArray("bank_cards");
		}
		if ((a == null) || (a.length() == 0)) return null;
		for (int i = 0; i < a.length(); i++) {
			JSONObject c = a.optJSONObject(i);
			if ((c != null) && c.optBoolean("is_main")) return c;
		}
		return a.optJSONObject(0);
	}

	@Nullable
	static String mainLoyalty(JSONObject j) {
		JSONArray a = j.optJSONArray("data");
		if ((a == null) || (a.length() == 0)) return null;
		JSONObject pick = null;
		for (int i = 0; i < a.length(); i++) {
			JSONObject c = a.optJSONObject(i);
			if ((c != null) && c.optBoolean("is_main")) pick = c;
		}
		if (pick == null) pick = a.optJSONObject(0);
		String n = (pick != null) ? pick.optString("number", "") : "";
		return n.isEmpty() ? null : n;
	}

	/** Клиент для расчёта скидки — «telegram_id,loyalty_customer_id», как в приложении А-100. */
	static String client(JSONObject me) {
		JSONObject u = user(me);
		StringBuilder b = new StringBuilder();
		for (String k : new String[]{"telegram_id", "loyalty_customer_id"}) {
			if (!u.has(k) || u.isNull(k)) continue;
			String v = u.optString(k, "");
			if (v.isEmpty()) continue;
			if (b.length() > 0) b.append(',');
			b.append(v);
		}
		return b.toString();
	}

	static JSONObject discountBody(Station s, int pump, Ready r, Calc c, String client, @Nullable String loyalty) {
		JSONObject b = new JSONObject();
		try {
			int azs = Integer.parseInt(s.number);
			b.put("client", client).put("liters", c.liters).put("azs_number", azs).put("pump_number", pump)
					.put("nozzle_number", r.nozzle).put("points", JSONObject.NULL).put("promotions", new JSONArray())
					.put("type", "FUEL").put("sale_point", azs);
			if (loyalty != null) b.put("card_number", loyalty);
		} catch (Exception ignore) {
		}
		return b;
	}

	/**
	 * Ответ расчёта скидки. Сумма со скидкой принимается, только если она больше нуля и не больше суммы, названной
	 * пользователю перед «Оплатить?» — списать больше названного нельзя.
	 */
	static Discount parseDiscount(JSONObject j, Calc c) {
		JSONObject d = j.optJSONObject("data");
		if ((d == null) || !d.has("amount") || d.isNull("amount")) return Discount.NONE;
		double amount = d.optDouble("amount", 0);
		if (!(amount > 0) || (amount > c.amount + 0.005)) return Discount.NONE;
		double disc = d.optDouble("discount", 0) + d.optDouble("promotions_discount", 0) + d.optDouble("points_discount", 0);
		if (Double.isNaN(disc)) disc = 0;
		return new Discount(true, amount, disc);
	}

	/** Тело оплаты — как во вкладке и в приложении А-100 (type FUEL, оплата «картой»). */
	static JSONObject payBody(Station s, int pump, Ready r, Calc c, long cardId, @Nullable String loyalty, Discount dc) {
		JSONObject b = new JSONObject();
		try {
			int azs = Integer.parseInt(s.number);
			b.put("promotions", new JSONArray()).put("discount", dc.ok ? dc.discount : 0).put("sale_point", azs)
					.put("payment_method", "card").put("type", "FUEL")
					.put("fuel_name", r.fuel).put("azs_number", azs).put("nozzle_number", r.nozzle)
					.put("liters", c.liters).put("fuel_code", s.fuelCode(r.fuel) == null ? "" : s.fuelCode(r.fuel))
					.put("amount", dc.ok ? dc.amount : c.amount).put("price", r.price).put("pump_number", pump)
					.put("bank_card_id", cardId);
			// номер карты лояльности — только после расчёта скидки (как в приложении); расчёт не удался — без неё
			if (dc.ok && (loyalty != null)) b.put("loyalty_card_number", loyalty);
		} catch (Exception ignore) {
		}
		return b;
	}
}
