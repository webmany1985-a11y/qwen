package me.aap.fermata.addon.jarvis;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Узкий словарь ответов в диалоге заправки для распознавания Vosk на телефоне (решение 05.11.2026: ответы диалога —
 * без интернета). Не набор слов, а готовые фразы — свои на каждом шаге диалога: на «Какая колонка?» — фразы колонки,
 * на «Оплатить?» — фразы оплаты и отмены. Плюс «ловушки» («да», «нет», «потом»…): без них распознавание тянуло
 * короткие слова к фразам оплаты (проверка 05.11.2026 на синтезированных записях — 16 ложных оплат из 108 при
 * словаре из отдельных слов). Проверка — modules/jarvis/src/test/fuel_grammar_check.py.
 *
 * Слова — из словаря модели vosk-model-small-ru-0.22: «оплачивай» в нём нет (Vosk его не услышит), «четвёртая» —
 * с буквой «ё».
 */
final class FuelGrammar {
	private FuelGrammar() {
	}

	private static final String[] ONES = {"", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
			"десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать",
			"восемнадцать", "девятнадцать"};
	private static final String[] TENS = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят",
			"восемьдесят", "девяносто"};
	private static final String[] HUNDREDS = {"", "сто", "двести", "триста"};
	/** Порядковые 1–10 для колонок: именительный, винительный, предложный женского рода. */
	private static final String[][] ORD = {{}, {"первая", "первую", "первой"}, {"вторая", "вторую", "второй"},
			{"третья", "третью", "третьей"}, {"четвёртая", "четвёртую", "четвёртой"}, {"пятая", "пятую", "пятой"},
			{"шестая", "шестую", "шестой"}, {"седьмая", "седьмую", "седьмой"}, {"восьмая", "восьмую", "восьмой"},
			{"девятая", "девятую", "девятой"}, {"десятая", "десятую", "десятой"}};

	/** Число словами, 1–399. */
	static String words(int n) {
		StringBuilder b = new StringBuilder();
		if (n >= 100) b.append(HUNDREDS[n / 100]).append(' ');
		int r = n % 100;
		if (r >= 20) {
			b.append(TENS[r / 10]).append(' ');
			if (r % 10 != 0) b.append(ONES[r % 10]);
		} else if (r > 0) b.append(ONES[r]);
		return b.toString().trim();
	}

	static final String[] PAY = {"оплатить", "оплату разрешаю", "оплати", "да оплати", "да разрешаю", "плати", "да плати",
			"заплати"};
	static final String[] CANCEL = {"стоп заправка", "отмена заправки", "отменить заправку", "заправку отменить",
			"заправку отмени", "прекрати заправку", "не надо заправку", "хватит заправки", "отбой заправки", "закончи заправку",
			"закончить заправку", "заверши заправку", "отключи заправку", "выключи заправку", "останови заправку"};
	static final String[] SERVICE = {"продолжить заправку", "продолжаем заправку", "вернись к заправке", "повтори",
			"другая заправка", "следующая заправка", "не эта заправка"};
	static final String[] READY = {"готово", "снял", "пистолет снят"};
	/** Ловушки: похожие на ответы, но не команды — чтобы распознавание не тянуло их к оплате. */
	static final String[] DECOYS = {"да", "нет", "давай", "конечно", "подожди", "потом", "не знаю", "хорошо", "ладно",
			"не надо", "сколько", "оплата", "платно", "платье", "платить", "платёж", "плато", "платок", "заплатка",
			"не плати", "не надо платить", "не буду платить", "не оплачивай", "отмена", "отменить", "отмени", "включи музыку", "музыка",
			"открой ворота", "включи свет", "алло", "привет", "спасибо"};
	/**
	 * Запас фразы оплаты над ближайшим вариантом распознавания, который не оплата (Vosk, setMaxAlternatives).
	 * Проверка 05.11.2026: «платье» → «плати» шло с запасом 0,3–1,3 над вариантом «платье»; уверенность слов
	 * (1,00) их не различала. Меньше запаса — переспросить, платежа нет.
	 */
	static final double PAY_MIN_GAP = 2.0;

	/**
	 * Что принять из распознанного Vosk: фразу словаря текущего шага целиком; склейка нескольких фраз («двадцать
	 * пятьдесят литров») и «[unk]» — не принимаются (пустая строка → «Не расслышал»). Фраза оплаты — только если
	 * любой вариант распознавания, который не оплата, отстаёт не меньше чем на {@link #PAY_MIN_GAP}
	 * (payGap — этот запас; нет других вариантов — бесконечность).
	 */
	static String accept(FuelDialog.State state, String text, double payGap) {
		String n = FuelPhrase.norm(text);
		if (n.isEmpty()) return "";
		boolean known = false;
		for (String p : phrases(state)) {
			if (FuelPhrase.norm(p).equals(n)) {
				known = true;
				break;
			}
		}
		if (!known) return "";
		if (FuelPhrase.isPay(n) && payGap < PAY_MIN_GAP) return "";
		return n;
	}

	static List<String> pumps() {
		Set<String> s = new LinkedHashSet<>();
		for (int n = 1; n <= FuelDialog.MAX_PUMP; n++) {
			String w = words(n);
			s.add(w);
			s.add("колонка " + w);
			s.add("колонку " + w);
			s.add("колонка номер " + w);
			s.add("колонку номер " + w);
			s.add(w + " колонка");
		}
		for (int n = 1; n < ORD.length; n++) {
			s.add(ORD[n][0]);
			s.add(ORD[n][1]);
			s.add("на " + ORD[n][2]);
			s.add(ORD[n][2]);
			s.add(ORD[n][0] + " колонка");
			s.add(ORD[n][1] + " колонку");
		}
		return new ArrayList<>(s);
	}

	static List<String> amounts() {
		Set<String> s = new LinkedHashSet<>();
		for (int n = 1; n <= 60; n++) {
			String w = words(n), l = FuelDialog.litWord(n);
			s.add(w);
			s.add(w + " " + l);
			s.add("на " + w + " " + l);
			s.add("на " + w);
		}
		for (int n = 1; n <= 200; n++) {
			String w = words(n), r = FuelDialog.rubWord(n);
			s.add(w + " " + r);
			s.add("на " + w + " " + r);
			s.add("на сумму " + w);
		}
		return new ArrayList<>(s);
	}

	/** Фразы для текущего шага диалога. */
	static List<String> phrases(FuelDialog.State state) {
		List<String> out = new ArrayList<>();
		switch (state) {
			case ASK_PUMP:
				out.addAll(pumps());
				break;
			case ASK_AMOUNT:
				out.addAll(amounts());
				break;
			case WAIT_READY:
				add(out, READY);
				out.addAll(pumps());
				break;
			case ASK_PAY:
				add(out, PAY);
				// другое количество вместо оплаты — пересчитать
				out.addAll(amounts());
				break;
			default:
				break;
		}
		add(out, CANCEL);
		add(out, SERVICE);
		add(out, DECOYS);
		return out;
	}

	private static void add(List<String> l, String[] a) {
		for (String s : a) l.add(s);
	}

	/** Словарь для Vosk: JSON-массив фраз и «[unk]» (всё прочее). */
	static String json(FuelDialog.State state) {
		JSONArray a = new JSONArray();
		for (String p : phrases(state)) a.put(p);
		a.put("[unk]");
		return a.toString();
	}
}
