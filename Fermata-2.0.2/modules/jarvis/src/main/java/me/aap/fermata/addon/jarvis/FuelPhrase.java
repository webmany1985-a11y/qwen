package me.aap.fermata.addon.jarvis;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Фразы заправки А-100 голосом (согласованы с пользователем 05.11.2026, карточка
 * android-team/tasks/2026-11-05-zapravka-golosom). Чистая логика, без Android.
 *
 * <ul>
 * <li>начать: «заправка», «заправь», «заправиться», «заправь Опель», «давай заправимся», «Опель хочет кушать»…;</li>
 * <li>колонка: «колонка три», «третья колонка», «третью», «на третьей», «колонку номер три»; числа 1–24;</li>
 * <li>количество: число без единицы — литры; «… рублей», «на сумму …» — рубли; только целые;</li>
 * <li>оплата — только эти фразы: «оплатить», «оплачивай», «оплату разрешаю», «оплати», «да оплати»,
 * «да разрешаю», «плати», «да плати», «заплати» (просто «да» — нет);</li>
 * <li>отмена — только со словом «заправка»: «стоп заправка», «отмена заправки» и т. п.;</li>
 * <li>служебные: «продолжить заправку», «готово», «другая заправка», «повтори».</li>
 * </ul>
 */
final class FuelPhrase {
	private FuelPhrase() {
	}

	/** Нормализация: строчные, ё→е, без знаков препинания и без обращения «джарвис». */
	static String norm(@Nullable String s) {
		if (s == null) return "";
		String p = s.toLowerCase(Locale.ROOT).replace('ё', 'е')
				.replaceAll("[.,!?;:«»\"()\\-–—]+", " ").replaceAll("\\s+", " ").trim();
		p = p.replaceFirst("^(джарвис|джервис|jarvis)\\s*", "");
		return p.trim();
	}

	private static final Pattern START = Pattern.compile(
			"^(ну\\s+)?(давай(те)?\\s+|хочу\\s+|надо\\s+|нужно\\s+)?" +
					"(заправка|заправь(те)?|заправить|заправиться|заправься|заправимся|заправим)(\\s.*)?$");
	private static final Pattern START_OPEL = Pattern.compile("^опель\\s+хочет\\s+(кушать|есть|кушать\\s.*)$");
	private static final Pattern CANCEL = Pattern.compile(
			"(^|\\s)(стоп|отмен[а-я]*|прекрат[а-я]*|не\\s+надо|хватит|отбой)(\\s|$)");
	private static final Pattern FUEL_WORD = Pattern.compile("(^|\\s)заправк[а-я]*");
	private static final Pattern RESUME = Pattern.compile(
			"^(продолж(ить|аем|им|и|ай)\\s+заправку|вернись\\s+к\\s+заправке|вернемся\\s+к\\s+заправке)$");
	private static final Pattern READY = Pattern.compile("^(готово|снял|снят|пистолет\\s+снят|снял\\s+пистолет)$");
	private static final Pattern OTHER = Pattern.compile("^(другая|следующая|не\\s+эта)\\s+заправка$");
	private static final Pattern REPEAT = Pattern.compile("^(повтори|повтори\\s+пожалуйста|повтори\\s+вопрос)$");
	private static final Pattern PAY = Pattern.compile(
			"^(оплатить|оплачивай|оплату\\s+разрешаю|оплати|да\\s+оплати|да\\s+разрешаю|плати|да\\s+плати|заплати)$");

	static boolean isCancel(String n) {
		return FUEL_WORD.matcher(n).find() && CANCEL.matcher(n).find();
	}

	static boolean isResume(String n) {
		return RESUME.matcher(n).matches();
	}

	static boolean isReady(String n) {
		return READY.matcher(n).matches();
	}

	static boolean isOtherStation(String n) {
		return OTHER.matcher(n).matches();
	}

	static boolean isRepeat(String n) {
		return REPEAT.matcher(n).matches();
	}

	static boolean isPay(String n) {
		return PAY.matcher(n).matches();
	}

	/** Начало заправки (отмена, «продолжить» и «другая заправка» — не начало). */
	static boolean isStart(String n) {
		if (isCancel(n) || isResume(n) || isOtherStation(n)) return false;
		return START.matcher(n).matches() || START_OPEL.matcher(n).matches();
	}

	// ------------------------------------------------------------------ числа
	/** Число в словах или цифрах; ordinal — порядковое («третья»). */
	static final class Num {
		final int value;
		final boolean ordinal;
		final int start; // номер первого слова
		final int end;   // номер слова после числа

		Num(int value, boolean ordinal, int start, int end) {
			this.value = value;
			this.ordinal = ordinal;
			this.start = start;
			this.end = end;
		}
	}

	private static final String[] UNITS = {"", "од", "дв", "тр", "четыр", "пят", "шест", "сем", "восем", "девят"};

	/** Количественное слово → число (0 — не число). Тысячи не нужны. */
	private static int cardinal(String w) {
		switch (w) {
			case "один": case "одна": case "одну": case "одного": case "одной": case "одним": return 1;
			case "два": case "две": case "двух": case "двум": return 2;
			case "три": case "трех": case "трем": return 3;
			case "четыре": case "четырех": return 4;
			case "пять": case "пяти": return 5;
			case "шесть": case "шести": return 6;
			case "семь": case "семи": return 7;
			case "восемь": case "восьми": return 8;
			case "девять": case "девяти": return 9;
			case "десять": case "десяти": return 10;
			case "одиннадцать": case "одиннадцати": return 11;
			case "двенадцать": case "двенадцати": return 12;
			case "тринадцать": case "тринадцати": return 13;
			case "четырнадцать": case "четырнадцати": return 14;
			case "пятнадцать": case "пятнадцати": return 15;
			case "шестнадцать": case "шестнадцати": return 16;
			case "семнадцать": case "семнадцати": return 17;
			case "восемнадцать": case "восемнадцати": return 18;
			case "девятнадцать": case "девятнадцати": return 19;
			case "двадцать": case "двадцати": return 20;
			case "тридцать": case "тридцати": return 30;
			case "сорок": case "сорока": return 40;
			case "пятьдесят": case "пятидесяти": return 50;
			case "шестьдесят": case "шестидесяти": return 60;
			case "семьдесят": case "семидесяти": return 70;
			case "восемьдесят": case "восьмидесяти": return 80;
			case "девяносто": return 90;
			case "сто": case "ста": return 100;
			case "двести": case "двухсот": return 200;
			case "триста": case "трехсот": return 300;
			default: return 0;
		}
	}

	private static final Pattern ORD_END = Pattern.compile("(ый|ой|ий|ая|ую|ое|ые|ого|ому|ом|ой|ей|ья|ью|ье|ьей|ьего|ьему|ьем|ий|ем|его|ему)$");

	/** Порядковое слово → число 1–20, 30 (0 — нет): «первая», «третью», «двадцатой». */
	private static int ordinal(String w) {
		if (!ORD_END.matcher(w).find()) return 0;
		String[][] stems = {
				{"перв", "1"}, {"втор", "2"}, {"трет", "3"}, {"четверт", "4"}, {"пят", "5"}, {"шест", "6"},
				{"седьм", "7"}, {"восьм", "8"}, {"девят", "9"}, {"десят", "10"}, {"одиннадцат", "11"},
				{"двенадцат", "12"}, {"тринадцат", "13"}, {"четырнадцат", "14"}, {"пятнадцат", "15"},
				{"шестнадцат", "16"}, {"семнадцат", "17"}, {"восемнадцат", "18"}, {"девятнадцат", "19"},
				{"двадцат", "20"}, {"тридцат", "30"}};
		for (String[] s : stems) {
			if (!w.startsWith(s[0])) continue;
			String rest = w.substring(s[0].length());
			if (rest.length() <= 4 && ORD_END.matcher(rest).matches()) return Integer.parseInt(s[1]);
		}
		return 0;
	}

	/** Все числа фразы по порядку; «двадцать три» = 23, «сто двадцать» = 120, «двадцать третья» = 23 (порядк.). */
	static List<Num> numbers(String n) {
		String[] w = n.isEmpty() ? new String[0] : n.split(" ");
		List<Num> out = new ArrayList<>();
		int i = 0;
		while (i < w.length) {
			String t = w[i];
			if (t.matches("\\d{1,4}")) {
				out.add(new Num(Integer.parseInt(t), false, i, i + 1));
				i++;
				continue;
			}
			int c = cardinal(t), o = (c == 0) ? ordinal(t) : 0;
			if (c == 0 && o == 0) {
				i++;
				continue;
			}
			int start = i, value = (c != 0) ? c : o;
			boolean ord = (c == 0);
			i++;
			// сотни + десятки + единицы: «сто двадцать пять», «двадцать третья»
			if (!ord && value >= 100 && i < w.length) {
				int c2 = cardinal(w[i]), o2 = (c2 == 0) ? ordinal(w[i]) : 0;
				if ((c2 > 0 && c2 < 100) || (o2 > 0)) {
					value += (c2 > 0) ? c2 : o2;
					ord = (c2 == 0);
					i++;
				}
			}
			if (!ord && value % 10 == 0 && value % 100 >= 20 && i < w.length) {
				int c3 = cardinal(w[i]), o3 = (c3 == 0) ? ordinal(w[i]) : 0;
				if ((c3 > 0 && c3 < 10) || (o3 > 0 && o3 < 10)) {
					value += (c3 > 0) ? c3 : o3;
					ord = (c3 == 0);
					i++;
				}
			}
			out.add(new Num(value, ord, start, i));
		}
		return out;
	}

	// ------------------------------------------------------------------ разбор
	/** Что названо во фразе: колонка, литры, рубли (0 — не названо). */
	static final class Parsed {
		int pump;
		int liters;
		int rubles;

		boolean empty() {
			return pump == 0 && liters == 0 && rubles == 0;
		}
	}

	/** Что спрашивали: голое число — колонка или количество. */
	enum Expect {PUMP, AMOUNT, NONE}

	static Parsed parse(String n, Expect expect) {
		Parsed r = new Parsed();
		String[] w = n.isEmpty() ? new String[0] : n.split(" ");
		List<Num> nums = numbers(n);
		boolean[] used = new boolean[nums.size()];
		// колонка: число сразу после «колонк*» (можно через «номер»), порядковое перед «колонк*», любое порядковое
		for (int k = 0; k < nums.size(); k++) {
			Num x = nums.get(k);
			boolean afterCol = x.start > 0 && (isCol(w[x.start - 1]) ||
					(w[x.start - 1].equals("номер") && x.start > 1 && isCol(w[x.start - 2])));
			boolean beforeCol = x.end < w.length && isCol(w[x.end]);
			if ((afterCol || beforeCol || x.ordinal) && r.pump == 0) {
				r.pump = x.value;
				used[k] = true;
			}
		}
		// количество: перед «литр*» / «л» — литры; перед «рубл*» / «р» / «руб» или после «на сумму» — рубли
		for (int k = 0; k < nums.size(); k++) {
			if (used[k]) continue;
			Num x = nums.get(k);
			String next = x.end < w.length ? w[x.end] : "";
			boolean sum = x.start >= 2 && w[x.start - 1].equals("сумму") && w[x.start - 2].equals("на");
			if (next.startsWith("рубл") || next.equals("р") || next.equals("руб") || next.equals("byn") || sum) {
				if (r.rubles == 0 && r.liters == 0) r.rubles = x.value;
				used[k] = true;
			} else if (next.startsWith("литр") || next.equals("л")) {
				if (r.rubles == 0 && r.liters == 0) r.liters = x.value;
				used[k] = true;
			}
		}
		// голые числа: в ответ на «Какая колонка?» — колонка, иначе — литры (решение пользователя)
		for (int k = 0; k < nums.size(); k++) {
			if (used[k]) continue;
			Num x = nums.get(k);
			if (expect == Expect.PUMP && r.pump == 0) r.pump = x.value;
			else if (r.liters == 0 && r.rubles == 0) r.liters = x.value; // «колонка три двадцать», «заправь на двадцать»
		}
		return r;
	}

	private static boolean isCol(String w) {
		return w.startsWith("колонк");
	}
}
