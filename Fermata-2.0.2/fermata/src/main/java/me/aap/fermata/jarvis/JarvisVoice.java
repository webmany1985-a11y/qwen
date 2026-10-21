package me.aap.fermata.jarvis;

import static java.nio.charset.StandardCharsets.UTF_8;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.aap.fermata.FermataApplication;
import me.aap.utils.log.Log;
import me.aap.utils.voice.TextToSpeech;

/**
 * Голос JARVIS для речи во вкладке JARVIS: тот голос, скорость и высота,
 * что выбраны в настройках самого JARVIS (замечание 16.10.2026: «голос
 * отличается от того, который я установил у оригинального Джарвиса»).
 *
 * Настройки JARVIS лежат в его закрытом файле
 * /data/data/com.jarvis.assistant/shared_prefs/jarvis.xml (ключи speech_rate,
 * speech_pitch, voice_id — core/Prefs.kt JARVIS). Другому приложению Android
 * этот файл не отдаёт, поэтому он читается через root (телефон с Magisk).
 * Из файла берутся только эти три значения; в журнал сам файл не пишется.
 * Без root — значения JARVIS по умолчанию: скорость 0.95, высота 0.9.
 */
public final class JarvisVoice {
	/**
	 * Где искать настройки JARVIS. По адресу /data/data/…/shared_prefs/jarvis.xml
	 * файла не оказалось (журнал 21.10.2026, 09:15:10: «No such file or
	 * directory»), поэтому просматриваются все папки настроек JARVIS — обычная
	 * и защищённая (user_de) — и берётся файл, где есть speech_rate или
	 * voice_id. Не нашлось — в ответ список файлов, он попадает в журнал.
	 */
	private static final String FIND_CMD =
			"for d in /data/user/0/com.jarvis.assistant /data/data/com.jarvis.assistant " +
					"/data/user_de/0/com.jarvis.assistant; do for f in $d/shared_prefs/*.xml; do " +
					"[ -f \"$f\" ] || continue; " +
					"x=$(abx2xml \"$f\" - 2>/dev/null || cat \"$f\"); " +
					"case \"$x\" in *speech_rate*|*voice_id*) echo \"$x\"; exit 0;; esac; done; done; " +
					"echo 'not found:'; ls -la /data/user/0/com.jarvis.assistant/shared_prefs " +
					"/data/user_de/0/com.jarvis.assistant/shared_prefs 2>&1; exit 1";
	/** Как часто перечитывать настройки JARVIS, мс: голос могли сменить. */
	private static final long TTL_MS = 60_000L;
	static final float DEFAULT_RATE = 0.95f;
	static final float DEFAULT_PITCH = 0.9f;

	/**
	 * Последние прочитанные настройки хранятся у себя: root в Magisk бывает выдан
	 * на время, и без него голос откатывался на стандартный (журнал 17.10.2026,
	 * 08:00:15 — в 07:27 root был, в 08:00 уже нет).
	 */
	private static final String STORE = "jarvis_voice";
	private static volatile Settings cached = loadStored();
	private static volatile long loadedAt;
	private static volatile boolean loading;

	private JarvisVoice() {
	}

	/** Перечитать настройки JARVIS в фоне, если давно не читали. */
	public static void refresh() {
		if (loading || (System.currentTimeMillis() - loadedAt < TTL_MS)) return;
		loading = true;
		FermataApplication.get().execute(() -> {
			String xml = null;
			try {
				xml = readPrefs();
				if (xml != null) {
					cached = parse(xml);
					store(cached);
					Log.i("JARVIS voice: rate=", cached.rate, " pitch=", cached.pitch, " voice=",
							cached.voice);
				}
				// Не прочитали (root ещё не разрешён) — попробовать снова через 10 с
				loadedAt = (xml != null) ? System.currentTimeMillis() :
						System.currentTimeMillis() - TTL_MS + 10_000L;
			} finally {
				loading = false;
			}
		});
	}

	/** Настроить синтезатор как в JARVIS. Вызывать после установки языка. */
	public static void apply(@NonNull TextToSpeech tts) {
		Settings s = cached;
		tts.setSpeechRate(s.rate);
		tts.setPitch(s.pitch);
		if ((s.voice != null) && !tts.setVoice(s.voice)) {
			Log.i("JARVIS voice: voice ", s.voice, " not found in TTS engine");
		}
		refresh();
	}

	@Nullable
	private static String readPrefs() {
		Process p = null;
		try {
			// На новых Android файлы бывают в двоичном XML (ABX) — тогда abx2xml
			// --mount-master: root в общем пространстве системы. Иначе root видит
			// только папку JARVIS Auto — папки JARVIS «нет» (журнал 21.10.2026,
			// 17:41: «/data/user/0/com.jarvis.assistant/shared_prefs: No such file»)
			p = new ProcessBuilder("su", "--mount-master", "-c", FIND_CMD).redirectErrorStream(true).start();
			p.getOutputStream().close();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (InputStream in = p.getInputStream()) {
				byte[] buf = new byte[8192];
				for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
			}
			if (!p.waitFor(15, TimeUnit.SECONDS) || (p.exitValue() != 0)) {
				String tail = out.toString(UTF_8.name()).trim();
				if (tail.length() > 600) tail = tail.substring(tail.length() - 600);
				Log.i("JARVIS voice: cannot read JARVIS settings (no root?), exit ",
						p.isAlive() ? "timeout" : p.exitValue(), ": ", tail);
				return null;
			}
			return out.toString(UTF_8.name());
		} catch (Exception ex) {
			Log.i("JARVIS voice: cannot read JARVIS settings: ", ex);
			return null;
		} finally {
			if (p != null) p.destroy();
		}
	}

	private static Settings loadStored() {
		try {
			android.content.SharedPreferences sp = FermataApplication.get()
					.getSharedPreferences(STORE, android.content.Context.MODE_PRIVATE);
			return new Settings(sp.getFloat("rate", DEFAULT_RATE), sp.getFloat("pitch", DEFAULT_PITCH),
					sp.getString("voice", null));
		} catch (Throwable ex) {
			return new Settings(DEFAULT_RATE, DEFAULT_PITCH, null);
		}
	}

	private static void store(Settings s) {
		try {
			FermataApplication.get().getSharedPreferences(STORE, android.content.Context.MODE_PRIVATE)
					.edit().putFloat("rate", s.rate).putFloat("pitch", s.pitch).putString("voice", s.voice)
					.apply();
		} catch (Throwable ignore) {
		}
	}

	@VisibleForTesting
	static Settings parse(String xml) {
		float rate = floatPref(xml, "speech_rate", DEFAULT_RATE);
		float pitch = floatPref(xml, "speech_pitch", DEFAULT_PITCH);
		Matcher m = Pattern.compile("<string name=\"voice_id\">([^<]*)</string>").matcher(xml);
		String voice = m.find() ? m.group(1).trim() : null;
		if ((voice != null) && voice.isEmpty()) voice = null;
		return new Settings(rate, pitch, voice);
	}

	private static float floatPref(String xml, String name, float def) {
		Matcher m = Pattern.compile("<float name=\"" + name + "\" value=\"([0-9.]+)\"").matcher(xml);
		if (!m.find()) return def;
		try {
			float v = Float.parseFloat(m.group(1));
			return ((v >= 0.5f) && (v <= 2f)) ? v : def;
		} catch (NumberFormatException ex) {
			return def;
		}
	}

	@VisibleForTesting
	static final class Settings {
		final float rate;
		final float pitch;
		@Nullable
		final String voice;

		Settings(float rate, float pitch, @Nullable String voice) {
			this.rate = rate;
			this.pitch = pitch;
			this.voice = voice;
		}
	}
}
