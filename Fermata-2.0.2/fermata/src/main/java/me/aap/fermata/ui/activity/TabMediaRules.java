package me.aap.fermata.ui.activity;

/**
 * Правила «звук только из видимой вкладки» и «после перезапуска — туда, где шло видео»
 * (пожелание 14.11.2026). Без Android — проверяется модульными тестами.
 */
public final class TabMediaRules {
	/** Пауза, пришедшая не раньше чем за столько до закрытия окна, считается вызванной закрытием (машина заглохла). */
	public static final long PAUSE_BY_SHUTDOWN_MS = 5000L;
	/** Не раньше этой секунды фильм продолжается с места — ближе к началу смысла нет. */
	public static final double MIN_RESUME_SEC = 5.0;

	private TabMediaRules() {
	}

	/**
	 * Открыть ли при запуске вкладку, где шло видео.
	 *
	 * @param tab       вкладка, где последний раз шло видео (0 — нет)
	 * @param playing   последнее известное состояние — играло
	 * @param pausedAt  когда видео встало на паузу (0 — не вставало)
	 * @param destroyAt когда закрылось окно приложения (0 — неизвестно)
	 */
	public static boolean shouldResume(int tab, boolean playing, long pausedAt, long destroyAt) {
		if (tab == 0) return false;
		if (playing) return true;
		return (pausedAt > 0) && (destroyAt >= pausedAt) && (destroyAt - pausedAt <= PAUSE_BY_SHUTDOWN_MS);
	}

	/**
	 * Нужно ли при переходе на вкладку {@code to} остановить то, что играет в остальных вкладках.
	 * Вкладка JARVIS (сфера) ничего не останавливает; возврат с неё на ту же вкладку — тоже.
	 *
	 * @param lastMediaTab последняя открытая вкладка, кроме JARVIS (0 — не было)
	 */
	public static boolean shouldPauseOthers(int to, int jarvisTab, int lastMediaTab) {
		if (to == jarvisTab) return false;
		return to != lastMediaTab;
	}

	/**
	 * Ставить ли на паузу общий проигрыватель (ТВ, YouTube), когда {@link #shouldPauseOthers} сказал «да».
	 *
	 * @param fromJarvis    уходим с вкладки JARVIS
	 * @param sameAsAtLeave играет то же, что играло, когда уходили на JARVIS (а не включено командой JARVIS)
	 */
	public static boolean shouldPauseSession(boolean playingVideo, boolean fromJarvis, boolean sameAsAtLeave) {
		if (!playingVideo) return false;
		return !fromJarvis || sameAsAtLeave;
	}

	/** С какой секунды продолжить фильм (≤0 — с начала / как решит сайт). */
	public static double resumeAt(double time, double duration) {
		if (!(time >= MIN_RESUME_SEC)) return 0;
		if ((duration > 0) && (time > duration - 10)) return 0; // досмотрен
		return time;
	}
}
