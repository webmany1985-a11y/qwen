package me.aap.fermata.addon.jarvis;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import me.aap.utils.log.Log;

/**
 * Распознавание на телефоне, без интернета (Vosk, модель vosk-model-small-ru-0.22, ~45 МБ, скачивается один раз —
 * решение пользователя 05.11.2026). Два режима:
 * <ul>
 * <li>ответ в диалоге заправки — словарём шага ({@link FuelGrammar}): текст лучшего варианта и запас фразы оплаты
 * над ближайшим вариантом, который не оплата;</li>
 * <li>первая фраза, когда нет интернета (Google не отвечает), — свободная речь.</li>
 * </ul>
 * Модель — та же, что в JARVIS (core/ModelDownloader.kt), но своя копия: приложения независимы.
 */
final class FuelVosk {
	static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip";
	private static final String DIR = "vosk-model-small-ru";
	private static final int RATE = 16000;
	/** Сколько ждать ответа целиком, мс. */
	private static final long MAX_LISTEN_MS = 8000;
	/** Тишина до начала речи — «не услышал», мс. */
	private static final long NO_SPEECH_MS = 6000;

	interface Result {
		/** text — пусто, если ничего не распознано; heard — была ли речь; payGap — запас фразы оплаты. */
		void onResult(String text, boolean heard, double payGap);
	}

	private static volatile boolean downloading;
	@Nullable
	private static Model model;

	private final Context ctx;
	private final Handler main = new Handler(Looper.getMainLooper());
	private volatile boolean stop;

	FuelVosk(@NonNull Context ctx) {
		this.ctx = ctx.getApplicationContext();
	}

	private File dir() {
		return new File(ctx.getFilesDir(), DIR);
	}

	boolean ready() {
		return new File(dir(), "am/final.mdl").isFile();
	}

	/** Скачать модель в фоне, если её нет (один раз). */
	void ensureModel() {
		if (ready() || downloading) return;
		downloading = true;
		new Thread(() -> {
			File zip = new File(ctx.getCacheDir(), "vosk-ru.zip");
			File tmp = new File(ctx.getFilesDir(), DIR + ".tmp");
			try {
				Log.i("JARVIS fuel: downloading speech model");
				HttpURLConnection c = (HttpURLConnection) new URL(MODEL_URL).openConnection();
				c.setConnectTimeout(15000);
				c.setReadTimeout(30000);
				try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(zip)) {
					byte[] b = new byte[65536];
					for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
				}
				deleteAll(tmp);
				unzipStripRoot(zip, tmp);
				if (!new File(tmp, "am/final.mdl").isFile()) throw new java.io.IOException("model incomplete");
				deleteAll(dir());
				if (!tmp.renameTo(dir())) throw new java.io.IOException("rename failed");
				Log.i("JARVIS fuel: speech model ready");
			} catch (Exception ex) {
				Log.e(ex, "JARVIS fuel: speech model download failed");
				deleteAll(tmp);
			} finally {
				//noinspection ResultOfMethodCallIgnored
				zip.delete();
				downloading = false;
			}
		}, "jarvis-vosk-download").start();
	}

	/** Слушать один ответ: grammar — словарь шага (null — свободная речь). Результат — в главном потоке. */
	@SuppressLint("MissingPermission")
	void listen(@Nullable String grammar, boolean carMic, @NonNull Result cb) {
		stop = false;
		new Thread(() -> {
			String text = "";
			boolean heard = false;
			double gap = Double.POSITIVE_INFINITY;
			AudioRecord rec = null;
			Recognizer r = null;
			try {
				Model m = model();
				r = (grammar != null) ? new Recognizer(m, RATE, grammar) : new Recognizer(m, RATE);
				r.setMaxAlternatives(3);
				int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
				int src = carMic ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.VOICE_RECOGNITION;
				rec = new AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192) * 2);
				rec.startRecording();
				short[] buf = new short[RATE / 10];
				long start = System.currentTimeMillis();
				while (!stop) {
					int n = rec.read(buf, 0, buf.length);
					if (n <= 0) break;
					boolean end = r.acceptWaveForm(buf, n);
					if (!heard && hasSpeech(r.getPartialResult())) heard = true;
					long t = System.currentTimeMillis() - start;
					if (end && heard) break;
					if (!heard && t > NO_SPEECH_MS) break;
					if (t > MAX_LISTEN_MS) break;
				}
				JSONArray alts = new JSONObject(r.getFinalResult()).optJSONArray("alternatives");
				if ((alts != null) && (alts.length() > 0)) {
					JSONObject top = alts.getJSONObject(0);
					text = clean(top.optString("text", ""));
					if (!text.isEmpty()) heard = true;
					for (int i = 1; i < alts.length(); i++) {
						String t = clean(alts.getJSONObject(i).optString("text", ""));
						if (!FuelPhrase.isPay(FuelPhrase.norm(t))) {
							gap = top.optDouble("confidence", 0) - alts.getJSONObject(i).optDouble("confidence", 0);
							break;
						}
					}
				}
			} catch (Throwable ex) {
				Log.e(ex, "JARVIS fuel: local recognition failed");
			} finally {
				if (rec != null) {
					try {
						rec.stop();
					} catch (Exception ignore) {
					}
					rec.release();
				}
				if (r != null) r.close();
			}
			String t = text;
			boolean h = heard;
			double g = gap;
			main.post(() -> cb.onResult(t, h, g));
		}, "jarvis-vosk-listen").start();
	}

	void cancel() {
		stop = true;
	}

	private synchronized Model model() throws java.io.IOException {
		if (model == null) model = new Model(dir().getAbsolutePath());
		return model;
	}

	private static boolean hasSpeech(String partialJson) {
		try {
			return !clean(new JSONObject(partialJson).optString("partial", "")).isEmpty();
		} catch (Exception ex) {
			return false;
		}
	}

	private static String clean(String s) {
		return s.replace("[unk]", "").replaceAll("\\s+", " ").trim();
	}

	private static void unzipStripRoot(File zip, File to) throws java.io.IOException {
		String canon = to.getCanonicalPath() + File.separator;
		try (ZipInputStream z = new ZipInputStream(new java.io.FileInputStream(zip))) {
			for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
				String name = e.getName();
				int slash = name.indexOf('/');
				if (slash < 0) continue;
				name = name.substring(slash + 1);
				if (name.isEmpty()) continue;
				File f = new File(to, name);
				if (!f.getCanonicalPath().startsWith(canon)) throw new java.io.IOException("bad zip entry");
				if (e.isDirectory()) {
					//noinspection ResultOfMethodCallIgnored
					f.mkdirs();
					continue;
				}
				//noinspection ResultOfMethodCallIgnored
				f.getParentFile().mkdirs();
				try (OutputStream o = new FileOutputStream(f)) {
					byte[] b = new byte[65536];
					for (int n; (n = z.read(b)) > 0; ) o.write(b, 0, n);
				}
			}
		}
	}

	private static void deleteAll(File f) {
		File[] l = f.listFiles();
		if (l != null) for (File x : l) deleteAll(x);
		//noinspection ResultOfMethodCallIgnored
		f.delete();
	}
}
