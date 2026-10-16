package me.aap.fermata.jarvis;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.FermataApplication;
import me.aap.utils.log.Log;

/**
 * Журнал для разбора неполадок — один текстовый файл, который пользователь
 * пересылает разработчику (настройки → «Другое» → «Отправить журнал»).
 *
 * <ol>
 * <li>Сведения о телефоне и сборке.</li>
 * <li>Журнал приложения (Fermata.log и предыдущий Fermata.log.old): шаги
 * голоса во вкладке JARVIS, ответы JARVIS, загрузка TikTok, выбор фона.</li>
 * <li>Системный журнал. На телефоне с root — весь, через su: в нём видно
 * Android Auto и распознавание речи Google (Magisk один раз спросит
 * разрешение). Без root — только записи самого приложения.</li>
 * </ol>
 */
public final class LogReport {
	/** Сколько последних строк системного журнала взять. */
	private static final int LOGCAT_LINES = 15000;
	/** Сколько ждать ответа su (пока пользователь разрешит root), с. */
	private static final int SU_TIMEOUT = 25;

	private LogReport() {
	}

	/** Собрать файл; вызывать не в главном потоке. */
	@NonNull
	public static File build(@NonNull Context ctx) throws IOException {
		File log = FermataApplication.get().getLogFile();
		File dir = log.getParentFile();
		String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(new Date());
		File out = new File(dir, "jarvis-zhurnal-" + stamp + ".txt");
		File[] old = (dir == null) ? null : dir.listFiles((d, n) -> n.startsWith("jarvis-zhurnal-"));
		if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
			f.delete();

		try (Writer w = new OutputStreamWriter(new FileOutputStream(out), UTF_8)) {
			w.write("=== Журнал JARVIS Auto " + stamp + "\n");
			w.write("Сборка: " + ctx.getPackageName() + " " + BuildConfig.VERSION_NAME + " (" +
					BuildConfig.VERSION_CODE + ")\n");
			w.write("Телефон: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " +
					Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n\n");

			w.write("=== Журнал приложения (предыдущий)\n");
			copy(new File(log.getAbsolutePath() + ".old"), w);
			w.write("\n=== Журнал приложения\n");
			copy(log, w);

			String all = logcat(new String[]{"su", "-c", "logcat -d -v threadtime -t " + LOGCAT_LINES},
					SU_TIMEOUT);
			if (all != null) {
				w.write("\n=== Системный журнал (весь, root)\n");
				w.write(all);
			} else {
				w.write("\n=== Системный журнал (только приложение: root не дан)\n");
				String own = logcat(new String[]{"logcat", "-d", "-v", "threadtime", "-t",
						String.valueOf(LOGCAT_LINES)}, 15);
				w.write((own != null) ? own : "(не удалось прочитать)\n");
			}
		}
		Log.i("Log report saved: ", out, ", ", out.length(), " bytes");
		return out;
	}

	private static void copy(File f, Writer w) throws IOException {
		if (!f.isFile()) {
			w.write("(нет)\n");
			return;
		}
		try (InputStream in = new FileInputStream(f)) {
			BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF_8));
			char[] buf = new char[8192];
			for (int n; (n = r.read(buf)) > 0; ) w.write(buf, 0, n);
		}
	}

	/** Вывод команды или null, если она не отработала за timeout секунд или вернула ошибку. */
	private static String logcat(String[] cmd, int timeout) {
		Process p = null;
		try {
			p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			p.getOutputStream().close();
			Process proc = p;
			StringBuilder sb = new StringBuilder();
			Thread reader = new Thread(() -> {
				try (BufferedReader r = new BufferedReader(
						new InputStreamReader(proc.getInputStream(), UTF_8))) {
					for (String l; (l = r.readLine()) != null; ) sb.append(l).append('\n');
				} catch (IOException ignore) {
				}
			});
			reader.start();
			if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
				Log.i("Log report: command timed out: ", cmd[0]);
				return null;
			}
			reader.join(5000);
			if ((p.exitValue() != 0) || (sb.length() < 100)) {
				Log.i("Log report: command failed: ", cmd[0], ", exit ", p.exitValue());
				return null;
			}
			synchronized (sb) {
				return sb.toString();
			}
		} catch (Exception ex) {
			Log.i("Log report: command not available: ", cmd[0], " ", ex);
			return null;
		} finally {
			if (p != null) p.destroy();
		}
	}
}
