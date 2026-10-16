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
	private static final String PREFS = "/data/data/com.jarvis.assistant/shared_prefs/jarvis.xml";
	/** Как часто перечитывать настройки JARVIS, мс: голос могли сменить. */
	private static final long TTL_MS = 60_000L;
	static final float DEFAULT_RATE = 0.95f;
	static final float DEFAULT_PITCH = 0.9f;

	private static volatile Settings cached = new Settings(DEFAULT_RATE, DEFAULT_PITCH, null);
	private static volatile long loadedAt;
	private static volatile boolean loading;

	private JarvisVoice() {
	}

	/** Перечитать настройки JARVIS в фоне, если давно не читали. */
	public static void refresh() {
		if (loading || (System.currentTimeMillis() - loadedAt < TTL_MS)) return;
		loading = true;
		FermataApplication.get().execute(() -> {
			try {
				String xml = readPrefs();
				if (xml != null) {
					cached = parse(xml);
					Log.i("JARVIS voice: rate=", cached.rate, " pitch=", cached.pitch, " voice=",
							cached.voice);
				}
			} finally {
				loadedAt = System.currentTimeMillis();
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
			p = new ProcessBuilder("su", "-c",
					"abx2xml " + PREFS + " - 2>/dev/null || cat " + PREFS).start();
			p.getOutputStream().close();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (InputStream in = p.getInputStream()) {
				byte[] buf = new byte[8192];
				for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
			}
			if (!p.waitFor(15, TimeUnit.SECONDS) || (p.exitValue() != 0)) {
				Log.i("JARVIS voice: cannot read JARVIS settings (no root?)");
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
