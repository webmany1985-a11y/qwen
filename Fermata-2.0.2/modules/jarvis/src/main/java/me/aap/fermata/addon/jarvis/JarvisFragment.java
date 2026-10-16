package me.aap.fermata.addon.jarvis;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static me.aap.fermata.util.Utils.dynCtx;
import static me.aap.utils.async.Completed.completed;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import me.aap.fermata.jarvis.JarvisBridge;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.fragment.MainActivityFragment;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.function.Cancellable;
import me.aap.utils.log.Log;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.FloatingButton;
import me.aap.utils.voice.TextToSpeech;

/**
 * Вкладка JARVIS: круг во весь экран. Касание круга, плавающей кнопки или
 * кнопки «голосовое управление» (например, на руле) — сказать фразу.
 *
 * Порядок как при голосовом поиске Fermata: сначала фразу пробует выполнить
 * сама Fermata («пауза», «играй…»), непонятую получает JARVIS. Ответ JARVIS
 * только произносится — под кругом ничего не показывается.
 *
 * Перед записью JARVIS говорит «Слушаю». Тишина в начале не обрывает
 * запись сразу: если речь так и не началась, слушаем ещё один раз.
 *
 * Распознавание Google заканчивает фразу на первой же короткой паузе и
 * просьбу ждать дольше (SILENCE_MS) часто не выполняет. Поэтому после
 * распознанного куска слушаем дальше: если в течение CONTINUE_WAIT_MS
 * речь продолжилась, куски склеиваются в одну команду.
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
	/** Произносится «Слушаю». */
	@Nullable
	private TextToSpeech greeting;
	/** Сколько раз ещё можно начать запись заново, если речь не началась. */
	private int retries;
	/** В этой записи речь началась. */
	private boolean heard;
	private final Handler main = new Handler(Looper.getMainLooper());
	/** Уже распознанные куски фразы; null — первый кусок ещё не получен. */
	@Nullable
	private StringBuilder phrase;
	/** Варианты распознавания первого куска — если продолжения не будет. */
	@Nullable
	private List<String> firstAlternatives;
	/** Сколько раз ещё можно дослушать продолжение фразы. */
	private int continuations;
	private final Runnable endIfSilent = () -> {
		if ((phrase != null) && !heard && (recognizer != null)) {
			destroyRecognizer();
			finishPhrase();
		}
	};

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
		else if ((phrase != null) && (recognizer != null) && !heard) {
			// Дослушиваем продолжение, а вы уже договорили — выполнить сразу
			destroyRecognizer();
			finishPhrase();
		}
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
		retries = RETRIES;
		continuations = CONTINUATIONS;
		phrase = null;
		firstAlternatives = null;
		sayListening(a, () -> {
			if (busy && (hud != null) && (recognizer == null)) createRecognizer(a);
		});
	}

	/** Произнести «Слушаю» и затем, в главном потоке, выполнить then. */
	private void sayListening(MainActivityDelegate a, Runnable then) {
		Context ctx = requireContext();
		String text = dynCtx(ctx).getString(R.string.jarvis_say_listening);
		Locale locale = Locale.forLanguageTag(a.getPrefs().getVoiceControlLang(a));
		TextToSpeech.create(ctx, locale).onCompletion((tts, err) -> main.post(() -> {
			if (err != null) {
				Log.e(err, "JARVIS tab: TTS failed");
				then.run();
				return;
			}
			if (!busy) {
				tts.close();
				return;
			}
			greeting = tts;
			tts.speak(text).onCompletion((r, serr) -> main.post(() -> {
				if (greeting != tts) return; // запись уже отменена
				greeting = null;
				tts.close();
				then.run();
			}));
		}));
	}

	private void closeGreeting() {
		TextToSpeech tts = greeting;
		greeting = null;
		if (tts != null) tts.close();
	}

	private void createRecognizer(MainActivityDelegate a) {
		heard = false;
		SpeechRecognizer sr = SpeechRecognizer.createSpeechRecognizer(requireContext());
		sr.setRecognitionListener(this);
		recognizer = sr;
		Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
		i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, a.getPrefs().getVoiceControlLang(a));
		i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
		// Пауза в речи, после которой фраза считается законченной
		i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS);
		i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
				SILENCE_MS);
		sr.startListening(i);
	}

	/** Прервать запись, если она идёт. Запрос, уже ушедший JARVIS, доходит до конца. */
	private void stopListening() {
		closeGreeting();
		if (recognizer == null) {
			if (busy && (phase() == HudMath.Phase.APPEAR)) done();
			return;
		}
		destroyRecognizer();
		done();
	}

	private void destroyRecognizer() {
		main.removeCallbacks(endIfSilent);
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
		phrase = null;
		firstAlternatives = null;
		setPhase(HudMath.Phase.IDLE);
	}

	@Override
	public void onReadyForSpeech(Bundle params) {
		setPhase(HudMath.Phase.LISTEN);
		if (phrase != null) main.postDelayed(endIfSilent, CONTINUE_WAIT_MS);
	}

	@Override
	public void onBeginningOfSpeech() {
		heard = true;
		main.removeCallbacks(endIfSilent);
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
		if (phrase != null) {
			// Продолжения не было (или запись сорвалась) — выполнить то, что есть
			finishPhrase();
			return;
		}
		boolean silence = (error == SpeechRecognizer.ERROR_NO_MATCH) ||
				(error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
		if (silence && !heard && (retries > 0) && busy && (hud != null)) {
			retries--;
			createRecognizer(getActivityDelegate());
			return;
		}
		if (silence) {
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
		boolean empty = (q == null) || q.isEmpty() || q.get(0).trim().isEmpty();
		if (phrase == null) {
			if (empty) {
				toast(R.string.jarvis_not_heard);
				done();
				return;
			}
			phrase = new StringBuilder(q.get(0).trim());
			firstAlternatives = q;
		} else if (!empty) {
			phrase.append(' ').append(q.get(0).trim());
			firstAlternatives = null;
		}

		if ((continuations > 0) && busy && (hud != null)) {
			continuations--;
			createRecognizer(getActivityDelegate());
		} else {
			finishPhrase();
		}
	}

	/** Фраза закончена: выполнить командой Fermata или передать JARVIS. */
	private void finishPhrase() {
		StringBuilder p = phrase;
		List<String> alt = firstAlternatives;
		phrase = null;
		firstAlternatives = null;
		if ((p == null) || !busy) return;
		List<String> q = (alt != null) ? alt : Collections.singletonList(p.toString());

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

	/** Пауза в речи, после которой фраза закончена, мс (обычно около секунды). */
	private static final int SILENCE_MS = 3000;
	/** Сколько раз начать запись заново, если вы ещё не начали говорить. */
	private static final int RETRIES = 1;
	/** Сколько раз дослушать продолжение фразы после паузы. */
	private static final int CONTINUATIONS = 3;
	/** Сколько ждать продолжения фразы после паузы, мс. */
	private static final long CONTINUE_WAIT_MS = 1500;

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
