package me.aap.fermata.addon.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Заправка голосом: фразы и диалог по согласованному с пользователем списку (05.11.2026). */
public class FuelDialogTest {
	private static final long T = 1_000_000L;

	private static String n(String s) {
		return FuelPhrase.norm(s);
	}

	@Test
	public void startPhrases() {
		for (String s : new String[]{"заправка", "Джарвис, заправка", "заправь", "заправить", "заправиться", "заправься",
				"хочу заправиться", "давай заправимся", "заправь машину", "заправь авто", "заправь тачку",
				"заправь автомобиль", "заправь Опель", "давай заправим машину", "давай заправим авто",
				"давай заправим автомобиль", "давай заправим Опель", "Опель хочет кушать",
				"заправь третью колонку на двадцать литров", "заправь колонку 3 на 20 литров"}) {
			assertTrue(s, FuelPhrase.isStart(n(s)));
		}
		for (String s : new String[]{"залей", "где ближайшая заправка", "стоп заправка", "продолжить заправку",
				"другая заправка", "открой ворота", "включи свет", "маршрут на работу"}) {
			assertFalse(s, FuelPhrase.isStart(n(s)));
		}
	}

	@Test
	public void cancelOnlyWithFuelWord() {
		for (String s : new String[]{"стоп заправка", "отмена заправки", "отменить заправку", "заправку отменить",
				"заправку отмени", "прекрати заправку", "не надо заправку", "хватит заправки", "отбой заправки"}) {
			assertTrue(s, FuelPhrase.isCancel(n(s)));
		}
		for (String s : new String[]{"стоп", "отмена", "нет", "хватит", "заправка"}) assertFalse(s, FuelPhrase.isCancel(n(s)));
	}

	@Test
	public void payOnlyAgreedPhrases() {
		for (String s : new String[]{"оплатить", "Оплатить!", "оплачивай", "оплату разрешаю", "оплати", "да оплати",
				"да, разрешаю", "плати", "да плати", "заплати"}) {
			assertTrue(s, FuelPhrase.isPay(n(s)));
		}
		for (String s : new String[]{"да", "давай", "нет", "оплата", "не плати", "оплатить потом", "ага"}) {
			assertFalse(s, FuelPhrase.isPay(n(s)));
		}
	}

	@Test
	public void serviceWords() {
		for (String s : new String[]{"продолжить заправку", "продолжаем заправку", "вернись к заправке"}) assertTrue(s, FuelPhrase.isResume(n(s)));
		for (String s : new String[]{"готово", "снял", "пистолет снят"}) assertTrue(s, FuelPhrase.isReady(n(s)));
		for (String s : new String[]{"другая заправка", "следующая заправка", "не эта заправка"}) assertTrue(s, FuelPhrase.isOtherStation(n(s)));
		assertTrue(FuelPhrase.isRepeat(n("повтори")));
	}

	private static FuelPhrase.Parsed p(String s, FuelPhrase.Expect e) {
		return FuelPhrase.parse(n(s), e);
	}

	@Test
	public void pumpForms() {
		FuelPhrase.Expect N = FuelPhrase.Expect.NONE;
		assertEquals(3, p("колонка три", N).pump);
		assertEquals(3, p("третья колонка", N).pump);
		assertEquals(3, p("третью", N).pump);
		assertEquals(3, p("на третьей", N).pump);
		assertEquals(3, p("колонку номер три", N).pump);
		assertEquals(3, p("три", FuelPhrase.Expect.PUMP).pump);
		assertEquals(23, p("двадцать третья колонка", N).pump);
		assertEquals(24, p("колонка двадцать четыре", N).pump);
		assertEquals(12, p("колонка 12", N).pump);
		assertEquals(7, p("седьмую", N).pump);
		assertEquals(15, p("пятнадцатая", N).pump);
		assertEquals(5, p("пятая колонка", N).pump);
	}

	@Test
	public void amountForms() {
		FuelPhrase.Expect A = FuelPhrase.Expect.AMOUNT;
		assertEquals(20, p("двадцать", A).liters);
		assertEquals(20, p("двадцать литров", A).liters);
		assertEquals(20, p("на двадцать литров", A).liters);
		assertEquals(30, p("на тридцать рублей", A).rubles);
		assertEquals(30, p("тридцать рублей", A).rubles);
		assertEquals(30, p("на сумму тридцать", A).rubles);
		assertEquals(0, p("на сумму тридцать", A).liters);
		assertEquals(25, p("25 л", A).liters);
		assertEquals(120, p("сто двадцать рублей", A).rubles);
		assertEquals(35, p("тридцать пять", A).liters);
	}

	@Test
	public void onePhrase() {
		FuelPhrase.Parsed a = p("заправь третью колонку на двадцать литров", FuelPhrase.Expect.NONE);
		assertEquals(3, a.pump);
		assertEquals(20, a.liters);
		FuelPhrase.Parsed b = p("колонка три тридцать рублей", FuelPhrase.Expect.NONE);
		assertEquals(3, b.pump);
		assertEquals(30, b.rubles);
		FuelPhrase.Parsed c = p("заправь колонку 3 на 20 литров", FuelPhrase.Expect.NONE);
		assertEquals(3, c.pump);
		assertEquals(20, c.liters);
		FuelPhrase.Parsed d = p("колонка три двадцать", FuelPhrase.Expect.NONE);
		assertEquals(3, d.pump);
		assertEquals(20, d.liters);
	}

	// ------------------------------------------------------------------ диалог
	@Test
	public void onePhraseGoesStraightToPrepare() {
		FuelDialog d = new FuelDialog();
		FuelDialog.Step s = d.onPhrase("Джарвис, заправь третью колонку на двадцать литров", T);
		assertEquals(FuelDialog.Effect.PREPARE, s.effect);
		assertEquals(3, d.pump);
		assertEquals(20, d.liters);
		FuelDialog.Step q = d.onPrepared(new FuelDialog.Prepared("14", "АИ-95", 20, 50.0, 30), T + 1000);
		assertEquals("АЗС 14, колонка 3, АИ-95, 20 литров, 50 рублей. Оплатить?", q.say);
		assertTrue(q.listen);
	}

	@Test
	public void dialogAsksMissing() {
		FuelDialog d = new FuelDialog();
		FuelDialog.Step s = d.onPhrase("заправка", T);
		assertEquals("Какая колонка?", s.say);
		s = d.onPhrase("три", T + 1);
		assertEquals("Сколько литров?", s.say);
		s = d.onPhrase("на тридцать рублей", T + 2);
		assertEquals(FuelDialog.Effect.PREPARE, s.effect);
		assertEquals(30, d.rubles);
	}

	@Test
	public void payOnlyByPayPhrase() {
		FuelDialog d = ready();
		for (String s : new String[]{"да", "нет", "давай", "конечно"}) {
			FuelDialog.Step st = d.onPhrase(s, T + 3);
			assertTrue(s, st.effect != FuelDialog.Effect.PAY);
			assertEquals(FuelDialog.State.ASK_PAY, d.state());
		}
		FuelDialog.Step sil = d.onSilence(T + 4);
		assertTrue(sil.effect != FuelDialog.Effect.PAY);
		assertEquals(FuelDialog.State.ASK_PAY, d.state());
		assertEquals(FuelDialog.Effect.PAY, d.onPhrase("да, оплати", T + 5).effect);
	}

	private static FuelDialog ready() {
		FuelDialog d = new FuelDialog();
		d.onPhrase("заправь колонку три на двадцать литров", T);
		d.onPrepared(new FuelDialog.Prepared("14", "АИ-95", 20, 50.0, 30), T + 2);
		return d;
	}

	@Test
	public void missKeepsWhatWasSaid() {
		FuelDialog d = new FuelDialog();
		d.onPhrase("заправка", T);
		d.onPhrase("колонка пять", T + 1);
		FuelDialog.Step s = d.onPhrase("ммм что", T + 2);
		assertEquals("Не расслышал. Сколько литров?", s.say);
		s = d.onSilence(T + 3);
		assertEquals("Не расслышал. Сколько литров?", s.say);
		assertEquals(5, d.pump);
		assertEquals(FuelDialog.Effect.PREPARE, d.onPhrase("двадцать", T + 4).effect);
	}

	@Test
	public void netErrorRetriesThenWaitsAndResumes() {
		FuelDialog d = new FuelDialog();
		d.onPhrase("заправь колонку три на двадцать литров", T);
		assertEquals(FuelDialog.Effect.PREPARE, d.onNetError(T + 1).effect);
		assertEquals(FuelDialog.Effect.PREPARE, d.onNetError(T + 2).effect);
		FuelDialog.Step s = d.onNetError(T + 3);
		assertEquals(FuelDialog.Effect.NONE, s.effect);
		assertTrue(s.say.contains("продолжить заправку"));
		s = d.onPhrase("продолжить заправку", T + 60_000);
		assertEquals(FuelDialog.Effect.PREPARE, s.effect);
		assertEquals(3, d.pump);
		assertEquals(20, d.liters);
	}

	@Test
	public void paymentNetErrorIsNotRetried() {
		FuelDialog d = ready();
		assertEquals(FuelDialog.Effect.PAY, d.onPhrase("оплатить", T + 3).effect);
		FuelDialog.Step s = d.onNetError(T + 4);
		assertEquals(FuelDialog.Effect.NONE, s.effect);
		assertEquals(FuelDialog.State.ASK_PAY, d.state());
	}

	@Test
	public void inactivePumpThenReady() {
		FuelDialog d = new FuelDialog();
		d.onPhrase("заправь колонку три на двадцать литров", T);
		FuelDialog.Step s = d.onPumpInactive(T + 1);
		assertEquals("Колонка 3 не активна. Снимите пистолет и скажите «готово».", s.say);
		assertEquals(FuelDialog.Effect.PREPARE, d.onPhrase("готово", T + 2).effect);
		assertEquals(20, d.liters);
	}

	@Test
	public void cancelAndMemory() {
		FuelDialog d = ready();
		assertNull(new FuelDialog().onPhrase("открой ворота", T));
		assertEquals(FuelDialog.Effect.NONE, d.onPhrase("стоп", T + 3).effect); // просто «стоп» — не отмена
		assertEquals(FuelDialog.State.ASK_PAY, d.state());
		assertEquals(FuelDialog.Effect.END, d.onPhrase("стоп заправка", T + 4).effect);
		assertFalse(d.active(T + 5));
		FuelDialog e = ready();
		assertTrue(e.active(T + FuelDialog.MEMORY_MS));
		assertFalse(e.active(T + 2 + FuelDialog.MEMORY_MS + 1));
		assertNull(e.onPhrase("оплатить", T + 2 * FuelDialog.MEMORY_MS)); // после 5 минут «оплатить» ничего не платит
	}

	@Test
	public void limits() {
		FuelDialog d = new FuelDialog();
		d.onPhrase("заправь колонку три", T);
		FuelDialog.Step s = d.onPhrase("пятьдесят литров", T + 1);
		assertEquals("50 литров — больше обычного. Назовите количество ещё раз.", s.say);
		assertEquals(0, d.liters);
		assertEquals(FuelDialog.Effect.PREPARE, d.onPhrase("пятьдесят", T + 2).effect); // повтор подтверждает
		assertEquals(50, d.liters);
		FuelDialog e = new FuelDialog();
		e.onPhrase("заправь колонку три", T);
		e.onPhrase("сто пятьдесят рублей", T + 1);
		assertEquals(FuelDialog.Effect.PREPARE, e.onPhrase("сто рублей", T + 2).effect); // другое — заменяет
		assertEquals(100, e.rubles);
		assertNotNull(e);
	}

	@Test
	public void farWarningAndWords() {
		FuelDialog d = new FuelDialog();
		d.onPhrase("заправь колонку три на двадцать один литр", T);
		FuelDialog.Step q = d.onPrepared(new FuelDialog.Prepared("14", "ДТ", 21, 52.5, 3200), T + 1);
		assertEquals("Внимание, вы в 3,2 километра от станции. АЗС 14, колонка 3, ДТ, 21 литр, 52 рубля 50 копеек. Оплатить?", q.say);
		assertEquals("1 рубль", FuelDialog.money(1));
		assertEquals("11 рублей", FuelDialog.money(11));
	}

	@Test
	public void fixesFromJarvisLogs() {
		// «Opel» латиницей у Google (журнал 12:37), «за правь» у Vosk, отмены «закончи/заверши…заправку»
		assertTrue(FuelPhrase.isStart(n("Opel хочет кушать")));
		assertTrue(FuelPhrase.isStart(n("заправь Opel")));
		assertTrue(FuelPhrase.isStart(n("за правь автомобиль")));
		assertFalse(FuelPhrase.isStart(n("где ближайшая заправка")));
		for (String s : new String[]{"закончи заправку", "заверши заправку", "отключи заправку", "выключи заправку", "останови заправку"}) {
			assertTrue(s, FuelPhrase.isCancel(n(s)));
		}
		assertFalse(FuelPhrase.isCancel(n("выключи музыку")));
		// «нет / отменить» на «Оплатить?» — не платить, заправка помнится
		FuelDialog d = ready();
		assertEquals("Хорошо, не оплачиваю. Скажите «оплатить» или «стоп заправка».", d.onPhrase("отменить", T + 3).say);
		assertEquals(FuelDialog.State.ASK_PAY, d.state());
		// без местоположения — предупреждение
		FuelDialog e = new FuelDialog();
		e.onPhrase("заправь колонку два на десять литров", T);
		assertTrue(e.onPrepared(new FuelDialog.Prepared("1", "ДТ", 10, 27.3, -1), T + 1).say.startsWith("Внимание, не знаю, где вы"));
		assertFalse(FuelGrammar.phrases(FuelDialog.State.ASK_AMOUNT).contains("включи"));
		assertTrue(FuelGrammar.phrases(FuelDialog.State.ASK_PUMP).contains("заверши заправку"));
	}
}
