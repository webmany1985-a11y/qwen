package me.aap.fermata.addon.jarvis;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static me.aap.fermata.util.Utils.dynCtx;
import static me.aap.utils.async.Completed.completed;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import me.aap.fermata.jarvis.JarvisBridge;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.MainActivityFragment;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.function.Cancellable;
import me.aap.utils.log.Log;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.FloatingButton;

/**
 * Вкладка JARVIS: круг во весь экран. Касание круга, плавающей кнопки или
 * кнопки «голосовое управление» (например, на руле) — сказать фразу.
 *
 * Порядок как при голосовом поиске Fermata: сначала фразу пробует выполнить
 * сама Fermata («пауза», «играй…»), непонятую получает JARVIS. Ответ JARVIS
 * только произносится — под кругом ничего не показывается.
 *
 * Музыка стоит на паузе, пока слушаем, ждём и произносим ответ, и
 * возобновляется после.
 */
public class JarvisFragment extends MainActivityFragment
		implements RecognitionListener, JarvisBridge.Listener {
	@Nullable
	private JarvisHudView hud;
	@Nullable
	private SpeechRecognizer recognizer;
	private Cancellable interrupt = Cancellable.CANCELED;
	/** Идёт разговор: слушаем, ждём JARVIS или произносим ответ. */
	private boolean busy;

	@Override
	public int getFragmentId() {
		return me.aap.fermata.R.id.jarvis_addon;
	}

	@Override
	public CharSequence getTitle() {
		return "JARVIS";
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		JarvisHudView v = new JarvisHudView(dynCtx(requireContext()));
		v.setOnClickListener(x -> onCircleClick());
		hud = v;
		return v;
	}

	@Override
	public void onDestroyView() {
		stopListening();
		hud = null;
		super.onDestroyView();
	}

	@Override
	public void onPause() {
		super.onPause();
		stopListening();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (hidden) stopListening();
	}

	@Override
	public FloatingButton.Mediator getFloatingButtonMediator() {
		return FbMediator.instance;
	}

	/** Кнопка «голосовое управление» Fermata, пока открыта эта вкладка. */
	@Override
	public boolean startVoiceAssistant() {
		listen();
		return true;
	}

	private void onCircleClick() {
		if (!busy) listen();
		else if ((recognizer != null) && (phase() == HudMath.Phase.LISTEN)) recognizer.stopListening();
	}

	private void listen() {
		if (busy || (hud == null)) return;
		MainActivityDelegate a = getActivityDelegate();
		Context ctx = requireContext();
		if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
			toast(R.string.jarvis_no_recognizer);
			return;
		}
		busy = true;
		setPhase(HudMath.Phase.APPEAR);
		FutureSupplier<int[]> perm = a.isCarActivityNotMirror() ?
				completed(new int[]{PERMISSION_GRANTED}) :
				a.getAppActivity().checkPermissions(Manifest.permission.RECORD_AUDIO);
		perm.onCompletion((r, err) -> {
			if ((err != null) || (r == null) || (r.length == 0) || (r[0] != PERMISSION_GRANTED)) {
				if (err != null) Log.e(err, "JARVIS tab: RECORD_AUDIO permission failed");
				toast(me.aap.fermata.R.string.err_no_audio_record_perm);
				done();
				return;
			}
			if (!busy || (hud == null)) return;
			startRecognizer(a);
		});
	}

	private void startRecognizer(MainActivityDelegate a) {
		interrupt = a.interruptPlayback();
		SpeechRecognizer sr = SpeechRecognizer.createSpeechRecognizer(requireContext());
		sr.setRecognitionListener(this);
		recognizer = sr;
		Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
		i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, a.getPrefs().getVoiceControlLang(a));
		i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
		sr.startListening(i);
	}

	/** Прервать запись, если она идёт. Запрос, уже ушедший JARVIS, доходит до конца. */
	private void stopListening() {
		if (recognizer == null) {
			if (busy && (phase() == HudMath.Phase.APPEAR)) done();
			return;
		}
		destroyRecognizer();
		done();
	}

	private void destroyRecognizer() {
		SpeechRecognizer sr = recognizer;
		recognizer = null;
		if (sr != null) sr.destroy();
	}

	/** Разговор окончен: музыку — обратно, круг — в ожидание. */
	private void done() {
		Cancellable i = interrupt;
		interrupt = Cancellable.CANCELED;
		i.cancel();
		busy = false;
		setPhase(HudMath.Phase.IDLE);
	}

	@Override
	public void onReadyForSpeech(Bundle params) {
		setPhase(HudMath.Phase.LISTEN);
	}

	@Override
	public void onBeginningOfSpeech() {
	}

	@Override
	public void onRmsChanged(float rmsdB) {
		if (hud != null) hud.setMicLevel(HudMath.levelFromRmsDb(rmsdB));
	}

	@Override
	public void onBufferReceived(byte[] buffer) {
	}

	@Override
	public void onEndOfSpeech() {
	}

	@Override
	public void onError(int error) {
		if (recognizer == null) return;
		destroyRecognizer();
		if ((error == SpeechRecognizer.ERROR_NO_MATCH) ||
				(error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
			toast(R.string.jarvis_not_heard);
		} else {
			Log.e("JARVIS tab: speech recognition error ", error);
		}
		done();
	}

	@Override
	public void onResults(Bundle results) {
		if (recognizer == null) return;
		destroyRecognizer();
		List<String> q = (results == null) ? null :
				results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
		if ((q == null) || q.isEmpty() || q.get(0).trim().isEmpty()) {
			toast(R.string.jarvis_not_heard);
			done();
			return;
		}

		MainActivityDelegate a = getActivityDelegate();
		if (a.handleVoiceCommand(q)) {
			// Команду выполнила Fermata. done() вернёт музыку, только если команда
			// её не трогала: так устроен interruptPlayback — «пауза» останется паузой
			done();
		} else if (!JarvisBridge.ask(a, q.get(0), this)) {
			toast(R.string.jarvis_not_installed);
			done();
		}
	}

	@Override
	public void onPartialResults(Bundle partialResults) {
	}

	@Override
	public void onEvent(int eventType, Bundle params) {
	}

	@Override
	public void onThinking() {
		if (busy) setPhase(HudMath.Phase.THINK);
	}

	@Override
	public void onSpeaking() {
		if (busy) setPhase(HudMath.Phase.SPEAK);
	}

	@Override
	public void onDone() {
		if (busy) done();
	}

	private HudMath.Phase phase() {
		return (hud == null) ? HudMath.Phase.IDLE : hud.getPhase();
	}

	private void setPhase(HudMath.Phase p) {
		if (hud != null) hud.setPhase(p);
	}

	private void toast(int msg) {
		Context ctx = getContext();
		if (ctx != null) Toast.makeText(ctx, dynCtx(ctx).getString(msg), Toast.LENGTH_SHORT).show();
	}

	private static final class FbMediator implements FloatingButton.Mediator {
		static final FbMediator instance = new FbMediator();

		@Override
		public void enable(FloatingButton b, ActivityFragment f) {
			b.setImageResource(me.aap.fermata.R.drawable.record_voice);
			b.setOnClickListener(v -> ((JarvisFragment) f).startVoiceAssistant());
			b.setOnLongClickListener(v -> ((JarvisFragment) f).startVoiceAssistant());
		}
	}
}
