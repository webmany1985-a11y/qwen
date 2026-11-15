package me.aap.fermata;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Перенос настроек и входов на другой телефон (пожелание 15.11.2026: «она просто должна поставить себе приложение на
 * телефон и оно должно заработать точно так же как у меня»). Только в сборке, собранной с -PPERENOS=<папка>: в APK
 * лежит ассет perenos/perenos.zip — настройки (shared_prefs), данные WebView (входы в сайты), files/*. При первом
 * запуске, до чтения настроек и до WebView, он распаковывается в папку данных приложения один раз (метка
 * files/.perenos-done). В обычной сборке ассета нет — ничего не происходит. Архив в репозиторий не кладётся.
 */
public final class Perenos {
	public static final String ASSET = "perenos/perenos.zip";
	static final String MARKER = "files/.perenos-done";

	private Perenos() {
	}

	/** Распаковывает архив в dataDir, если метки нет. Возвращает число распакованных файлов (0 — уже было). */
	public static int apply(File dataDir, InputStream zip) throws IOException {
		File marker = new File(dataDir, MARKER);
		if (marker.isFile()) return 0;
		String root = dataDir.getCanonicalPath() + File.separator;
		int n = 0;
		byte[] buf = new byte[16384];
		try (ZipInputStream z = new ZipInputStream(zip)) {
			for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
				File f = new File(dataDir, e.getName());
				if (!f.getCanonicalPath().startsWith(root)) throw new IOException("bad entry " + e.getName());
				if (e.isDirectory()) {
					if (!f.isDirectory() && !f.mkdirs()) throw new IOException("mkdir " + f);
					continue;
				}
				File p = f.getParentFile();
				if ((p != null) && !p.isDirectory() && !p.mkdirs()) throw new IOException("mkdir " + p);
				try (OutputStream o = new FileOutputStream(f)) {
					for (int r; (r = z.read(buf)) > 0; ) o.write(buf, 0, r);
				}
				n++;
			}
		}
		File mp = marker.getParentFile();
		if ((mp != null) && !mp.isDirectory()) mp.mkdirs();
		new FileOutputStream(marker).close();
		return n;
	}
}
