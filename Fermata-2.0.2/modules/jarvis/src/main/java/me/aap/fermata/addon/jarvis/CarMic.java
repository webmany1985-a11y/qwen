package me.aap.fermata.addon.jarvis;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.utils.log.Log;

/**
 * Микрофон машины по Bluetooth (SCO) на время фразы во вкладке JARVIS —
 * пробно, выключателем в настройках (пожелание 22.10.2026).
 *
 * Пока SCO подключён, магнитола в режиме звонка: так было и в JARVIS
 * (задача android-team 2026-10-14-mashina-bez-zvonka — музыка «через звонок»,
 * узкая полоса, распознавание хуже). Поэтому SCO берётся только на запись
 * фразы и отпускается сразу после неё.
 *
 * Как в JARVIS (core/CarMicLink.kt): взят — только по
 * ACTION_SCO_AUDIO_STATE_UPDATED = CONNECTED; рассылка «липкая», и
 * DISCONNECTED до CONNECTING — прежнее состояние, а не ответ на запрос.
 */
final class CarMic {
	/** Сколько ждать подключения SCO, мс. */
	private static final long CONNECT_WAIT_MS = 5000;

	interface Result {
		void onResult(boolean taken);
	}

	private final Context ctx;
	private final AudioManager am;
	private final Handler main = new Handler(Looper.getMainLooper());
	@Nullable
	private BroadcastReceiver receiver;
	@Nullable
	private Result pending;
	private boolean requested;
	private boolean connected;
	private boolean connectingSeen;

	CarMic(@NonNull Context ctx) {
		this.ctx = ctx.getApplicationContext();
		am = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
	}

	boolean isTaken() {
		return requested && connected;
	}

	/** Взять микрофон машины; result — в главном потоке, ровно один раз. */
	@SuppressWarnings("deprecation")
	void take(@NonNull Result result) {
		if (isTaken()) {
			result.onResult(true);
			return;
		}
		release();
		if ((am == null) || !am.isBluetoothScoAvailableOffCall()) {
			Log.i("JARVIS tab: car mic — SCO off call not supported");
			result.onResult(false);
			return;
		}
		if (am.getMode() == AudioManager.MODE_IN_CALL) {
			// Идёт звонок — SCO его, не трогаем
			Log.i("JARVIS tab: car mic — phone call in progress, not taken");
			result.onResult(false);
			return;
		}
		pending = result;
		connectingSeen = false;
		connected = false;
		receiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context c, Intent i) {
				int s = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR);
				onState(s);
			}
		};
		ctx.registerReceiver(receiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
		try {
			am.startBluetoothSco();
			am.setBluetoothScoOn(true);
			requested = true;
			Log.i("JARVIS tab: car mic — SCO requested");
		} catch (Exception ex) {
			Log.e(ex, "JARVIS tab: car mic — startBluetoothSco failed");
			finish(false);
			return;
		}
		main.postDelayed(timeout, CONNECT_WAIT_MS);
	}

	private final Runnable timeout = () -> {
		if (pending == null) return;
		Log.i("JARVIS tab: car mic — SCO not connected in ", CONNECT_WAIT_MS, " ms, phone mic");
		finish(false);
	};

	private void onState(int s) {
		switch (s) {
			case AudioManager.SCO_AUDIO_STATE_CONNECTING -> connectingSeen = true;
			case AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
				connected = true;
				if (pending != null) {
					Log.i("JARVIS tab: car mic — SCO connected, recording from car");
					main.removeCallbacks(timeout);
					Result r = pending;
					pending = null;
					r.onResult(true);
				}
			}
			case AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
				boolean was = connected;
				connected = false;
				if (pending != null && connectingSeen) {
					Log.i("JARVIS tab: car mic — SCO refused, phone mic");
					finish(false);
				} else if (was) {
					Log.i("JARVIS tab: car mic — SCO lost");
				}
			}
			default -> {
				if (pending != null) {
					Log.i("JARVIS tab: car mic — SCO error, phone mic");
					finish(false);
				}
			}
		}
	}

	private void finish(boolean taken) {
		Result r = pending;
		pending = null;
		main.removeCallbacks(timeout);
		if (!taken) release();
		if (r != null) r.onResult(taken);
	}

	/** Отпустить: магнитола выходит из режима звонка. */
	@SuppressWarnings("deprecation")
	void release() {
		main.removeCallbacks(timeout);
		pending = null;
		BroadcastReceiver rcv = receiver;
		receiver = null;
		if (rcv != null) {
			try {
				ctx.unregisterReceiver(rcv);
			} catch (IllegalArgumentException ignore) {
			}
		}
		if (!requested) return;
		requested = false;
		connected = false;
		if ((am == null) || (am.getMode() == AudioManager.MODE_IN_CALL)) return;
		try {
			am.setBluetoothScoOn(false);
			am.stopBluetoothSco();
			Log.i("JARVIS tab: car mic — SCO released");
		} catch (Exception ex) {
			Log.e(ex, "JARVIS tab: car mic — stopBluetoothSco failed");
		}
	}
}
