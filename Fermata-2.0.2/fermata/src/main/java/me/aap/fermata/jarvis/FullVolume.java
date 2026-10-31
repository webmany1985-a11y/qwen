package me.aap.fermata.jarvis;

/**
 * Голос JARVIS на полной громкости, какой бы ни была громкость телефона (пожелание пользователя 31.10.2026: «по
 * умолчанию голос Джарвиза на максимальной мощности, несмотря на силу аудио, когда играет, например, музыка»).
 * На время речи громкость потока поднимается до максимума, после — возвращается прежняя. Музыка на это время
 * приостановлена ({@code interruptPlayback}), поэтому громче становится только голос.
 *
 * Чистая логика без Android — проверяется тестом. Речь может накладываться («Слушаю» и ответ): поднимается один
 * раз, возвращается после последней. Если пользователь сам изменил громкость во время речи — его выбор не трогаем.
 */
public final class FullVolume {
	private int users;
	private int saved = -1;
	private int raisedTo = -1;

	/** Начало речи. Возвращает громкость, которую выставить, или -1 — ничего не менять. */
	public synchronized int begin(int current, int max) {
		users++;
		if (users > 1) return -1;
		if (current >= max) {
			saved = -1;
			raisedTo = -1;
			return -1;
		}
		saved = current;
		raisedTo = max;
		return max;
	}

	/** Конец речи. Возвращает громкость, которую вернуть, или -1 — ничего не менять. */
	public synchronized int end(int current) {
		if (users == 0) return -1;
		users--;
		if (users > 0) return -1;
		int r = ((saved >= 0) && (current == raisedTo)) ? saved : -1;
		saved = -1;
		raisedTo = -1;
		return r;
	}
}
