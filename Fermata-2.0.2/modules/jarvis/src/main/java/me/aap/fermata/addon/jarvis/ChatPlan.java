package me.aap.fermata.addon.jarvis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;

/**
 * Кто ведёт разговор во вкладке JARVIS (пожелание пользователя 30.10.2026): Qwen или DeepSeek — выбор в настройках,
 * вторая модель — запасная. У каждой свои почта, пароль ({@link ChatSecrets}), режим и модель; всё сохраняется.
 * Чистая логика — проверяется на JVM ({@code ChatPlanTest}).
 */
final class ChatPlan {
	enum Site {
		QWEN("qwen", "Qwen"), DEEPSEEK("deepseek", "DeepSeek");

		final String id;
		final String title;

		Site(String id, String title) {
			this.id = id;
			this.title = title;
		}

		static Site of(String id) {
			return "deepseek".equals(id) ? DEEPSEEK : QWEN;
		}

		Site other() {
			return (this == QWEN) ? DEEPSEEK : QWEN;
		}
	}

	static final Pref<Supplier<String>> MAIN = Pref.s("JARVIS_CHAT_MAIN", "qwen");
	static final Pref<BooleanSupplier> FALLBACK = Pref.b("JARVIS_CHAT_FALLBACK", true);

	static final Pref<Supplier<String>> QWEN_EMAIL = Pref.s("JARVIS_QWEN_EMAIL", "");
	/** Режим Qwen: подпись на сайте («Быстрый», «Авто», «Мышление»); пусто — как выбрано на сайте. */
	static final Pref<Supplier<String>> QWEN_MODE = Pref.s("JARVIS_QWEN_MODE", "");
	/** Модель Qwen — название на сайте («Qwen3-Max»); пусто — как выбрано на сайте. */
	static final Pref<Supplier<String>> QWEN_MODEL = Pref.s("JARVIS_QWEN_MODEL", "");
	/** Поиск в интернете у Qwen: «on», «off»; пусто — как выбрано на сайте. */
	static final Pref<Supplier<String>> QWEN_SEARCH = Pref.s("JARVIS_QWEN_SEARCH", "");

	static final Pref<Supplier<String>> DS_EMAIL = Pref.s("JARVIS_DS_EMAIL", "");
	/** DeepSeek: «DeepThink» — с рассуждением (дольше и глубже). */
	static final Pref<BooleanSupplier> DS_THINK = Pref.b("JARVIS_DS_THINK", false);
	static final Pref<BooleanSupplier> DS_SEARCH = Pref.b("JARVIS_DS_SEARCH", false);

	/** Ждать ответа до перехода на запасную, мс: быстрый режим — 25 с, мышление — 60 с. */
	static final long FAST_WAIT_MS = 25_000;
	static final long AUTO_WAIT_MS = 40_000;
	static final long THINK_WAIT_MS = 60_000;
	/** «Как на сайте» — неизвестно, думает ли модель. */
	static final long SITE_WAIT_MS = 45_000;

	private ChatPlan() {
	}

	/** Порядок: основная, затем — если включено — запасная. */
	static List<Site> order(String main, boolean fallback) {
		List<Site> r = new ArrayList<>(2);
		Site m = Site.of(main);
		r.add(m);
		if (fallback) r.add(m.other());
		return r;
	}

	/** Сколько ждать ответа Qwen при режиме mode (подпись с сайта). */
	static long qwenWait(String mode) {
		String m = (mode == null) ? "" : mode.trim().toLowerCase(Locale.ROOT);
		if (m.isEmpty()) return SITE_WAIT_MS;
		if (m.startsWith("мышл") || m.startsWith("think") || m.startsWith("размышл")) return THINK_WAIT_MS;
		if (m.startsWith("авто") || m.startsWith("auto")) return AUTO_WAIT_MS;
		return FAST_WAIT_MS;
	}

	static long deepseekWait(boolean think) {
		return think ? THINK_WAIT_MS : FAST_WAIT_MS;
	}

	static long waitFor(PreferenceStore ps, Site s) {
		return (s == Site.QWEN) ? qwenWait(ps.getStringPref(QWEN_MODE)) : deepseekWait(ps.getBooleanPref(DS_THINK));
	}

	/** Совпадает ли подпись на сайте с выбранной: без регистра, по первым пяти буквам (сайт меняет окончания). */
	static boolean sameLabel(String site, String want) {
		if ((site == null) || (want == null)) return false;
		String a = site.trim().toLowerCase(Locale.ROOT), b = want.trim().toLowerCase(Locale.ROOT);
		if (a.isEmpty() || b.isEmpty()) return false;
		int n = Math.min(5, Math.min(a.length(), b.length()));
		return a.regionMatches(0, b, 0, n);
	}
}
