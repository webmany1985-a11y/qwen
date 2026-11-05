package me.aap.fermata.addon.jarvis;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Диалог заправки А-100 голосом (карточка android-team/tasks/2026-11-05-zapravka-golosom). Чистая логика без
 * Android: на входе — распознанные фразы, тишина и ответы сервера, на выходе — что сказать, слушать ли дальше и
 * что сделать (подготовить заправку на сервере, оплатить, закончить).
 *
 * Правила пользователя:
 * <ul>
 * <li>одной фразой — сразу вопрос «Оплатить?»; не хватает колонки или количества — спросить;</li>
 * <li>оплата — только фразой оплаты ({@link FuelPhrase#isPay}) в ответ на «Оплатить?»; «да», «нет», тишина — не платёж;</li>
 * <li>«не расслышал», ошибка связи, неактивная колонка, перерыв — сказанное не теряется, спрашивается текущий шаг;
 * диалог помнится 5 минут;</li>
 * <li>отмена — только со словом «заправка»; пределы — 40 литров, 120 рублей (больше — назвать ещё раз).</li>
 * </ul>
 */
final class FuelDialog {
	static final long MEMORY_MS = 5 * 60_000L;
	static final int MAX_LITERS = 40;
	static final int MAX_RUBLES = 120;
	static final int MAX_PUMP = 24;
	static final int MAX_MISSES = 3;
	static final int MAX_NET_RETRIES = 2;
	static final int FAR_M = 1000;
	static final java.util.Set<String> NOT_NOW = new java.util.HashSet<>(java.util.Arrays.asList("нет", "отмена", "отменить",
			"отмени", "не надо", "не плати", "не оплачивай", "не надо платить", "не буду платить"));

	enum State {IDLE, ASK_PUMP, ASK_AMOUNT, PREPARING, WAIT_READY, ASK_PAY, PAYING, OFFLINE}

	enum Effect {NONE, PREPARE, PAY, END}

	/** Что сделать после фразы: сказать, слушать ли ответ (узким словарём), действие. */
	static final class Step {
		final String say;
		final boolean listen;
		final Effect effect;

		Step(String say, boolean listen, Effect effect) {
			this.say = say;
			this.listen = listen;
			this.effect = effect;
		}

		@Override
		public String toString() {
			return effect + (listen ? " listen" : "") + ": " + say;
		}
	}

	/** Ответ сервера на подготовку: станция, топливо, итог расчёта. */
	static final class Prepared {
		final String station;
		final String fuel;
		final int liters;
		final double amount;
		final int distanceM; // < 0 — неизвестно

		Prepared(String station, String fuel, int liters, double amount, int distanceM) {
			this.station = station;
			this.fuel = fuel;
			this.liters = liters;
			this.amount = amount;
			this.distanceM = distanceM;
		}
	}

	private State state = State.IDLE;
	private long lastActivity;
	int pump;
	int liters;
	int rubles;
	/** Сколько станций пропустить от ближайшей («другая заправка»). */
	int stationShift;
	private int overValue; // число сверх предела, ждёт повтора
	private int misses;
	private int netRetries;
	@Nullable
	private Prepared prepared;

	State state() {
		return state;
	}

	/** Идёт ли заправка (с учётом 5 минут памяти). */
	boolean active(long now) {
		if (state == State.IDLE) return false;
		if (now - lastActivity > MEMORY_MS) {
			reset();
			return false;
		}
		return true;
	}

	/** Нужно ли сейчас слушать узким словарём (ответ в диалоге). */
	boolean expectsAnswer(long now) {
		if (!active(now)) return false;
		return state == State.ASK_PUMP || state == State.ASK_AMOUNT || state == State.WAIT_READY || state == State.ASK_PAY;
	}

	private void reset() {
		state = State.IDLE;
		pump = liters = rubles = stationShift = overValue = misses = netRetries = 0;
		prepared = null;
	}

	/** Фраза, начинающая заправку или относящаяся к ней; null — не про заправку (обрабатывает остальной JARVIS). */
	@Nullable
	Step onPhrase(@Nullable String text, long now) {
		String n = FuelPhrase.norm(text);
		boolean act = active(now);
		if (FuelPhrase.isCancel(n)) {
			if (!act) return new Step("Заправка не идёт.", false, Effect.NONE);
			reset();
			return new Step("Заправка отменена.", false, Effect.END);
		}
		if (FuelPhrase.isResume(n)) {
			if (!act) return new Step("Незаконченной заправки нет. Скажите «заправь».", false, Effect.NONE);
			lastActivity = now;
			return current();
		}
		if (!act) {
			if (!FuelPhrase.isStart(n)) return null;
			reset();
			lastActivity = now;
			state = State.ASK_PUMP;
			absorb(FuelPhrase.parse(n, FuelPhrase.Expect.NONE));
			return next();
		}
		lastActivity = now;
		// заправка идёт: новая команда «заправь …» — дополняет сказанное
		if (FuelPhrase.isStart(n)) {
			FuelPhrase.Parsed p = FuelPhrase.parse(n, FuelPhrase.Expect.NONE);
			if (!p.empty()) {
				prepared = null;
				absorb(p);
				return next();
			}
			return current();
		}
		if (FuelPhrase.isRepeat(n)) return current();
		if (FuelPhrase.isOtherStation(n)) {
			stationShift++;
			prepared = null;
			if (pump > 0 && (liters > 0 || rubles > 0)) return prepare("Ищу следующую станцию.");
			return withPrefix("Хорошо, следующая станция. ", next());
		}
		switch (state) {
			case ASK_PUMP:
			case ASK_AMOUNT: {
				FuelPhrase.Parsed p = FuelPhrase.parse(n, state == State.ASK_PUMP ? FuelPhrase.Expect.PUMP : FuelPhrase.Expect.AMOUNT);
				if (p.empty()) return miss();
				absorb(p);
				return next();
			}
			case WAIT_READY:
				if (FuelPhrase.isReady(n)) return prepare(null);
				FuelPhrase.Parsed p = FuelPhrase.parse(n, FuelPhrase.Expect.PUMP);
				if (p.pump > 0) {
					absorb(p);
					return next();
				}
				return miss();
			case ASK_PAY:
				// «нет», «отмена» без слова «заправка» — не платить и не отменять заправку, коротко (как в JARVIS 255)
				if (NOT_NOW.contains(n)) return new Step("Хорошо, не оплачиваю. Скажите «оплатить» или «стоп заправка».", false, Effect.NONE);
				if (FuelPhrase.isPay(n)) {
					state = State.PAYING;
					misses = 0;
					return new Step("Оплачиваю.", false, Effect.PAY);
				}
				// другое количество или колонка вместо оплаты — пересчитать
				FuelPhrase.Parsed q = FuelPhrase.parse(n, FuelPhrase.Expect.AMOUNT);
				if (!q.empty() && (q.liters > 0 || q.rubles > 0 || q.pump > 0)) {
					prepared = null;
					absorb(q);
					return next();
				}
				return miss();
			case PREPARING:
			case PAYING:
				return new Step("Секунду.", false, Effect.NONE);
			case OFFLINE:
				return current();
			default:
				return null;
		}
	}

	/** Распознавание ничего не услышало (тишина). */
	Step onSilence(long now) {
		if (!active(now)) return new Step("", false, Effect.NONE);
		if (state == State.ASK_PAY) {
			// 15 с тишины на «Оплатить?» — платежа нет, диалог помнится
			return new Step("Оплата не подтверждена. Скажите «оплатить» или «стоп заправка».", false, Effect.NONE);
		}
		return miss();
	}

	/** Сервер посчитал: станция, топливо, сумма — задать вопрос «Оплатить?». */
	Step onPrepared(Prepared p, long now) {
		lastActivity = now;
		netRetries = 0;
		prepared = p;
		liters = p.liters;
		state = State.ASK_PAY;
		misses = 0;
		return payQuestion();
	}

	/** Колонка не активна (пистолет не снят). */
	Step onPumpInactive(long now) {
		lastActivity = now;
		netRetries = 0;
		state = State.WAIT_READY;
		return new Step("Колонка " + pump + " не активна. Снимите пистолет и скажите «готово».", true, Effect.NONE);
	}

	/** Нет ответа сервера при подготовке: повторить до 2 раз, потом ждать «продолжить заправку». */
	Step onNetError(long now) {
		lastActivity = now;
		if (state == State.PAYING) {
			// платёж мог пройти — не повторяем
			state = State.ASK_PAY;
			return new Step("Нет ответа от А-100 об оплате. Проверьте во вкладке «Заправка», прежде чем оплачивать снова.", false, Effect.NONE);
		}
		if (netRetries < MAX_NET_RETRIES) {
			netRetries++;
			return new Step("Нет ответа от А-100, повторяю.", false, Effect.PREPARE);
		}
		netRetries = 0;
		state = State.OFFLINE;
		return new Step("Связи с А-100 нет. Скажите «продолжить заправку», когда появится.", false, Effect.NONE);
	}

	/** Ошибка сервера с сообщением (не связь): сказать, вернуться к нужному шагу. */
	Step onServerError(@Nullable String msg, long now) {
		lastActivity = now;
		String m = (msg == null || msg.isEmpty()) ? "ошибка" : msg;
		if (state == State.PAYING) {
			state = State.ASK_PAY;
			return new Step("Оплата не прошла: " + m + ". Скажите «оплатить», чтобы попробовать ещё раз, или «стоп заправка».", true, Effect.NONE);
		}
		state = State.ASK_AMOUNT;
		return new Step("А-100 ответил: " + m + ". Сколько литров?", true, Effect.NONE);
	}

	/** Оплата прошла. */
	Step onPaid(long now) {
		reset();
		return new Step("Оплачено, можно заправляться.", false, Effect.END);
	}

	/** Нужна страница банка (нет сохранённой карты) — голосом не оплатить. */
	Step onNeedsBank(long now) {
		reset();
		return new Step("Для оплаты нужна карта: откройте вкладку «Заправка» и оплатите там.", false, Effect.END);
	}

	// ------------------------------------------------------------------ внутреннее
	private void absorb(FuelPhrase.Parsed p) {
		if (p.pump > 0) {
			if (p.pump <= MAX_PUMP) pump = p.pump;
		}
		int q = p.liters > 0 ? p.liters : p.rubles;
		if (q > 0) {
			boolean rub = p.rubles > 0;
			int max = rub ? MAX_RUBLES : MAX_LITERS;
			if (q > max && q != overValue) {
				overValue = q;
				overRub = rub;
				liters = rubles = 0;
				over = true;
				return;
			}
			overValue = 0;
			over = false;
			liters = rub ? 0 : q;
			rubles = rub ? q : 0;
		}
	}

	private boolean over;
	private boolean overRub;

	private Step next() {
		misses = 0;
		if (over) {
			over = false;
			state = State.ASK_AMOUNT;
			return new Step(overValue + (overRub ? " " + rubWord(overValue) : " " + litWord(overValue)) +
					" — больше обычного. Назовите количество ещё раз.", true, Effect.NONE);
		}
		if (pump == 0) {
			state = State.ASK_PUMP;
			return new Step("Какая колонка?", true, Effect.NONE);
		}
		if (liters == 0 && rubles == 0) {
			state = State.ASK_AMOUNT;
			return new Step("Сколько литров?", true, Effect.NONE);
		}
		return prepare(null);
	}

	private Step prepare(@Nullable String say) {
		state = State.PREPARING;
		misses = 0;
		return new Step(say == null ? "" : say, false, Effect.PREPARE);
	}

	/** Текущий вопрос заново (повтор, «продолжить заправку»). */
	private Step current() {
		switch (state) {
			case ASK_PUMP:
				return new Step("Какая колонка?", true, Effect.NONE);
			case ASK_AMOUNT:
				return new Step("Сколько литров?", true, Effect.NONE);
			case WAIT_READY:
				return new Step("Снимите пистолет на колонке " + pump + " и скажите «готово».", true, Effect.NONE);
			case ASK_PAY:
				return prepared != null ? payQuestion() : prepare(null);
			case OFFLINE:
				return prepare("Пробую снова.");
			case PREPARING:
			case PAYING:
				return new Step("Секунду.", false, Effect.NONE);
			default:
				return next();
		}
	}

	private Step miss() {
		misses++;
		Step q = current();
		if (misses > MAX_MISSES) {
			// не сбрасываем: ждём, пока скажут «продолжить заправку» (память 5 минут)
			misses = 0;
			return new Step("Не расслышал. Скажите «продолжить заправку», когда будете готовы.", false, Effect.NONE);
		}
		return withPrefix("Не расслышал. ", q);
	}

	private static Step withPrefix(String prefix, Step s) {
		return new Step(prefix + s.say, s.listen, s.effect);
	}

	private Step payQuestion() {
		Prepared p = prepared;
		if (p == null) return prepare(null);
		StringBuilder b = new StringBuilder();
		if (p.distanceM > FAR_M) b.append("Внимание, вы в ").append(km(p.distanceM)).append(" от станции. ");
		else if (p.distanceM < 0) b.append("Внимание, не знаю, где вы, — проверьте станцию. ");
		b.append("АЗС ").append(p.station).append(", колонка ").append(pump);
		if (p.fuel != null && !p.fuel.isEmpty()) b.append(", ").append(p.fuel);
		b.append(", ").append(p.liters).append(' ').append(litWord(p.liters)).append(", ").append(money(p.amount)).append(". Оплатить?");
		return new Step(b.toString(), true, Effect.NONE);
	}

	static String money(double a) {
		long k = Math.round(a * 100);
		long r = k / 100, c = k % 100;
		String s = r + " " + rubWord((int) (r % 1000));
		if (c > 0) s += " " + c + " " + plural((int) c, "копейка", "копейки", "копеек");
		return s;
	}

	static String km(int m) {
		double k = m / 1000.0;
		if (k < 10) return String.format(Locale.ROOT, "%.1f", k).replace('.', ',') + " километра";
		int r = (int) Math.round(k);
		return r + " " + plural(r, "километре", "километрах", "километрах");
	}

	static String litWord(int n) {
		return plural(n, "литр", "литра", "литров");
	}

	static String rubWord(int n) {
		return plural(n, "рубль", "рубля", "рублей");
	}

	static String plural(int n, String one, String few, String many) {
		int a = Math.abs(n) % 100, b = a % 10;
		if (a >= 11 && a <= 14) return many;
		if (b == 1) return one;
		if (b >= 2 && b <= 4) return few;
		return many;
	}
}
