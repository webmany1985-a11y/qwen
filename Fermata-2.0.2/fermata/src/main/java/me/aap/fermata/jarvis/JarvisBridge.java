package me.aap.fermata.jarvis;

import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.function.Cancellable;
import me.aap.utils.log.Log;
import me.aap.utils.voice.TextToSpeech;

/**
 * Мост к голосовому ассистенту JARVIS (пакет com.jarvis.assistant).
 *
 * Фразу, которую голосовые команды Fermata не поняли, передаём JARVIS:
 * он выполняет её своим движком и возвращает текст ответа, ответ Fermata
 * произносит сама, приостановив воспроизведение.
 *
 * Обмен через Messenger. Числа и ключи должны совпадать с
 * com.jarvis.assistant.service.ExternalCommandService.
 */
public final class JarvisBridge {
	public static final String PKG = "com.jarvis.assistant";
	public static final String ACTION = "com.jarvis.assistant.action.KOMANDA";
	public static final String PERMISSION = "com.jarvis.assistant.permission.KOMANDA";

	private static final int MSG_KOMANDA = 1;
	private static final int MSG_OTVET = 2;
	private static final String KEY_TEKST = "tekst";
	private static final String KEY_OTVET = "otvet";

	/** Чуть больше, чем ждёт сам JARVIS (30 с): его «не дождался» должно успеть прийти. */
	private static final long TIMEOUT_MS = 35_000;

	private JarvisBridge() {
	}

	/**
	 * Ход запроса — для вкладки JARVIS, которая рисует круг по фазам.
	 * Все вызовы — в главном потоке.
	 */
	public interface Listener {
		/** Фраза отправлена, ждём ответ. */
		default void onThinking() {
		}

		/** Ответ получен, Fermata начинает его произносить. */
		default void onSpeaking() {
		}

		/** Ответ произнесён (или произнести не удалось) — запрос окончен. */
		default void onDone() {
		}
	}

	/** Установлен ли JARVIS с входом для команд. */
	public static boolean isInstalled(Context ctx) {
		Intent i = new Intent(ACTION).setPackage(PKG);
		return !ctx.getPackageManager().queryIntentServices(i, 0).isEmpty();
	}

	/**
	 * Передать фразу JARVIS и произнести ответ.
	 *
	 * @return false, если JARVIS не установлен — тогда фраза остаётся непонятой, как раньше
	 */
	public static boolean ask(@NonNull MainActivityDelegate a, @NonNull String text) {
		return ask(a, text, null);
	}

	/**
	 * То же, с отчётом о ходе запроса. С {@code listener} ответ только произносится,
	 * без всплывающего сообщения: во вкладке JARVIS под кругом ничего не показываем.
	 * При false {@code listener} не вызывается.
	 */
	public static boolean ask(@NonNull MainActivityDelegate a, @NonNull String text,
														@Nullable Listener listener) {
		Context ctx = a.getContext();
		if (!isInstalled(ctx)) return false;

		a.getAppActivity().checkPermissions(PERMISSION).onCompletion((r, err) -> {
			if ((err != null) || (r == null) || (r.length == 0) || (r[0] != PERMISSION_GRANTED)) {
				if (err != null) Log.e(err, "JARVIS: failed to request permission");
				Toast.makeText(ctx, "Нет разрешения «команды для JARVIS»", Toast.LENGTH_LONG).show();
				if (listener != null) listener.onDone();
				return;
			}
			new Call(a, text, listener).start();
		});
		return true;
	}

	/** Один запрос: привязаться, отправить, дождаться ответа, отвязаться. */
	private static final class Call implements ServiceConnection {
		private final MainActivityDelegate a;
		private final Context ctx;
		private final String text;
		@Nullable
		private final Listener listener;
		private final Handler handler = new Handler(Looper.getMainLooper());
		private final Messenger replyTo = new Messenger(new Handler(Looper.getMainLooper(), this::onReply));
		private final Runnable timeout = () -> finish("JARVIS не ответил");
		private boolean bound;
		private boolean done;

		Call(MainActivityDelegate a, String text, @Nullable Listener listener) {
			this.a = a;
			this.ctx = a.getContext().getApplicationContext();
			this.text = text;
			this.listener = listener;
		}

		void start() {
			Intent i = new Intent(ACTION).setPackage(PKG);
			try {
				bound = ctx.bindService(i, this, BIND_AUTO_CREATE);
			} catch (SecurityException ex) {
				Log.e(ex, "JARVIS: bind refused");
			}
			if (!bound) {
				finish("JARVIS недоступен");
				return;
			}
			handler.postDelayed(timeout, TIMEOUT_MS);
			if (listener != null) listener.onThinking();
		}

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			if (done) return;
			Message m = Message.obtain(null, MSG_KOMANDA);
			Bundle b = new Bundle();
			b.putString(KEY_TEKST, text);
			m.setData(b);
			m.replyTo = replyTo;
			try {
				new Messenger(service).send(m);
			} catch (RemoteException ex) {
				Log.e(ex, "JARVIS: send failed");
				finish("JARVIS недоступен");
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			finish("JARVIS закрылся, не ответив");
		}

		private boolean onReply(Message m) {
			if (m.what != MSG_OTVET) return false;
			Bundle b = m.getData();
			String reply = (b == null) ? null : b.getString(KEY_OTVET);
			finish(((reply == null) || reply.trim().isEmpty()) ? "Готово" : reply);
			return true;
		}

		private void finish(String reply) {
			if (done) return;
			done = true;
			handler.removeCallbacks(timeout);
			if (bound) {
				bound = false;
				try {
					ctx.unbindService(this);
				} catch (IllegalArgumentException ignore) {
				}
			}
			if (listener == null) Toast.makeText(ctx, reply, Toast.LENGTH_LONG).show();
			else listener.onSpeaking();
			speak(reply);
		}

		private void speak(String reply) {
			Locale locale = Locale.forLanguageTag(a.getPrefs().getVoiceControlLang(a));
			TextToSpeech.create(ctx, locale).onCompletion((tts, err) -> {
				if (err != null) {
					Log.e(err, "JARVIS: TTS failed");
					if (listener != null) listener.onDone();
					return;
				}
				Cancellable interrupt = a.interruptPlayback();
				tts.speak(reply).onCompletion((r, serr) -> {
					interrupt.cancel();
					tts.close();
					if (listener != null) listener.onDone();
				});
			});
		}
	}
}
