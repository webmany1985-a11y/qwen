package me.aap.fermata.addon.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Ряд «Продолжить просмотр» вкладки «Видео» (пожелание 15.11.2026): последние открытые с главной страницы
 * фильмы, до {@link #MAX}, и секунда, на которой остановились. Хранится строкой (одна запись — строка,
 * поля через табуляцию). Чистая логика без Android — проверяется тестом.
 */
final class VideoContinue {
	static final int MAX = 10;

	static final class Entry {
		@NonNull
		final String url;
		@NonNull
		final String name;
		@NonNull
		final String poster;
		final int year;
		final double rating;
		final boolean serial;
		double time;
		double duration;

		Entry(@NonNull String url, @NonNull String name, @NonNull String poster, int year, double rating,
					boolean serial, double time, double duration) {
			this.url = url;
			this.name = name;
			this.poster = poster;
			this.year = year;
			this.rating = rating;
			this.serial = serial;
			this.time = time;
			this.duration = duration;
		}
	}

	private VideoContinue() {
	}

	@NonNull
	static List<Entry> decode(@Nullable String s) {
		if ((s == null) || s.isEmpty()) return new ArrayList<>();
		List<Entry> l = new ArrayList<>();
		for (String line : s.split("\n")) {
			String[] f = line.split("\t", -1);
			if ((f.length < 8) || f[0].isEmpty()) continue;
			try {
				l.add(new Entry(f[0], f[1], f[2], Integer.parseInt(f[3]), Double.parseDouble(f[4]),
						"1".equals(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7])));
			} catch (NumberFormatException ignore) {
			}
			if (l.size() == MAX) break;
		}
		return l;
	}

	@NonNull
	static String encode(@NonNull List<Entry> l) {
		StringBuilder b = new StringBuilder();
		for (Entry e : l) {
			if (b.length() > 0) b.append('\n');
			b.append(clean(e.url)).append('\t').append(clean(e.name)).append('\t').append(clean(e.poster))
					.append('\t').append(e.year).append('\t').append(e.rating).append('\t').append(e.serial ? 1 : 0)
					.append('\t').append(e.time).append('\t').append(e.duration);
		}
		return b.toString();
	}

	/** Фильм открыт с главной: в начало ряда; уже был — переносится в начало с прежней секундой. */
	@NonNull
	static List<Entry> opened(@NonNull List<Entry> l, @NonNull Entry e) {
		List<Entry> r = new ArrayList<>(l.size() + 1);
		Entry old = find(l, e.url);
		if (old != null) {
			e.time = old.time;
			e.duration = old.duration;
		}
		r.add(e);
		for (Entry x : l) {
			if ((x != old) && (r.size() < MAX)) r.add(x);
		}
		return r;
	}

	/**
	 * Сообщение плеера «играет на секунде time» со страницы pageUrl. Запись ищется по пути страницы (домен
	 * зеркала может смениться переадресацией); не нашлась — обновляется последняя открытая, если адрес
	 * страницы начинается с её пути (серия внутри страницы сериала). Вернуть true, если что-то изменилось.
	 */
	static boolean played(@NonNull List<Entry> l, @Nullable String pageUrl, double time, double duration) {
		if ((pageUrl == null) || l.isEmpty() || !(time >= 0)) return false;
		String p = path(pageUrl);
		Entry e = null;
		for (Entry x : l) {
			if (path(x.url).equals(p)) {
				e = x;
				break;
			}
		}
		if (e == null) {
			Entry first = l.get(0);
			String fp = path(first.url);
			if ((fp.length() > 1) && p.startsWith(fp)) e = first;
		}
		if (e == null) return false;
		e.time = time;
		if (duration > 0) e.duration = duration;
		return true;
	}

	@Nullable
	static Entry find(@NonNull List<Entry> l, @Nullable String url) {
		if (url == null) return null;
		for (Entry e : l) {
			if (e.url.equals(url)) return e;
		}
		return null;
	}

	/** Путь страницы без домена, параметров и конечной «/»: «https://w1.zona.im/movies/x/?a» → «/movies/x». */
	@NonNull
	static String path(@NonNull String url) {
		String s = url.replaceFirst("(?i)^[a-z]+://[^/]*", "");
		int i = s.indexOf('?');
		if (i >= 0) s = s.substring(0, i);
		i = s.indexOf('#');
		if (i >= 0) s = s.substring(0, i);
		while (s.endsWith("/") && (s.length() > 1)) s = s.substring(0, s.length() - 1);
		return s.isEmpty() ? "/" : s;
	}

	@NonNull
	static List<Entry> remove(@NonNull List<Entry> l, @NonNull String url) {
		List<Entry> r = new ArrayList<>(l);
		for (Iterator<Entry> it = r.iterator(); it.hasNext(); ) {
			if (it.next().url.equals(url)) it.remove();
		}
		return r;
	}

	private static String clean(String s) {
		return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
	}
}
