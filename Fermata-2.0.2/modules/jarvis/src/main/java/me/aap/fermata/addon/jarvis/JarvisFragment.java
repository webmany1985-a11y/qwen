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
 * просьбу ждать дольше часто не выполняет. Поэтому после
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
	/** Последний промежуточный итог распознавания в этой записи. */
	@Nullable
	private String partial;
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
		Log.i("JARVIS tab: listen, car=", a.isCarActivityNotMirror());
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
		// В машине звук идёт через Android Auto с задержкой: телефон уже закончил
		// «Слушаю», а динамики ещё говорят, и микрофон слышит само «Слушаю»
		// (журнал/замечание 16.10.2026: JARVIS отвечал «Не расслышал»)
		long pause = a.isCarActivityNotMirror() ? AFTER_GREETING_CAR_MS : AFTER_GREETING_MS;
		sayListening(a, () -> main.postDelayed(() -> {
			if (busy && (hud != null) && (recognizer == null) && (greeting == null)) createRecognizer(a);
		}, pause));
	}

	/** Произнести «Слушаю» и затем, в главном потоке, выполнить then. */
	private void sayListening(MainActivityDelegate a, Runnable then) {
		Context ctx = requireContext();
		String text = dynCtx(ctx).getString(R.string.jarvis_say_listening);
		Locale locale = Locale.forLanguageTag(a.getPrefs().getVoiceControlLang(a));
		TextToSpeech.create(ctx, locale).onCompletion((tts, err) -> main.post(() -> {
			if (err != null) {
				Log.e(err, "JARVIS tab: TTS «Слушаю» init failed, lang=", locale);
				then.run();
				return;
			}
			if (!busy) {
				tts.close();
				return;
			}
			greeting = tts;
			Log.i("JARVIS tab: saying «", text, "», lang=", locale);
			tts.speak(text).onCompletion((r, serr) -> main.post(() -> {
				if (greeting != tts) return; // запись уже отменена
				if (serr != null) Log.e(serr, "JARVIS tab: TTS «Слушаю» failed");
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
		partial = null;
		Log.i("JARVIS tab: recognizer start, segment=", (phrase == null) ? 1 : 1 + CONTINUATIONS - continuations,
				", retries=", retries, ", lang=", a.getPrefs().getVoiceControlLang(a));
		SpeechRecognizer sr = SpeechRecognizer.createSpeechRecognizer(requireContext());
		sr.setRecognitionListener(this);
		recognizer = sr;
		Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
		i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, a.getPrefs().getVoiceControlLang(a));
		i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
		// Промежуточные итоги: распознавание Google нередко слышит фразу, а в конце
		// отвечает NO_MATCH (журнал 16.10.2026, 19:11) — тогда берём промежуточный.
		// Паузы в речи (EXTRA_SPEECH_INPUT_*_SILENCE_*) больше не задаём: Google их
		// не выполняет, длинную фразу дослушивает CONTINUATIONS
		i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
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
		Log.i("JARVIS tab: done");
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
		Log.i("JARVIS tab: ready for speech");
		setPhase(HudMath.Phase.LISTEN);
		if (phrase != null) main.postDelayed(endIfSilent, CONTINUE_WAIT_MS);
	}

	@Override
	public void onBeginningOfSpeech() {
		Log.i("JARVIS tab: speech began");
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
		Log.i("JARVIS tab: speech ended");
	}

	@Override
	public void onError(int error) {
		Log.i("JARVIS tab: recognizer error ", errorName(error), ", heard=", heard,
				", phrase=", phrase);
		if (recognizer == null) return;
		destroyRecognizer();
		String p = partial;
		if (((error == SpeechRecognizer.ERROR_NO_MATCH) ||
				(error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) && (p != null)) {
			Log.i("JARVIS tab: using partial result «", p, "»");
			onRecognized(Collections.singletonList(p));
			return;
		}
		if (phrase != null) {
			// Продолжения не было (или запись сорвалась) — выполнить то, что есть
			finishPhrase();
			return;
		}
		boolean silence = (error == SpeechRecognizer.ERROR_NO_MATCH) ||
				(error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
		if (silence && (retries > 0) && busy && (hud != null)) {
			// Тишина или неразборчиво — ещё одна попытка, без повторного «Слушаю»
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
		List<String> r = (results == null) ? null :
				results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
		// Пустой итог при непустом промежуточном — берём промежуточный
		if (((r == null) || r.isEmpty() || (r.get(0) == null) || r.get(0).trim().isEmpty()) &&
				(partial != null)) r = Collections.singletonList(partial);
		onRecognized(r);
	}

	private void onRecognized(@Nullable List<String> recognized) {
		List<String> q = stripGreeting(recognized);
		boolean empty = (q == null) || q.isEmpty() || q.get(0).trim().isEmpty();
		Log.i("JARVIS tab: recognized ", q);
		if (phrase == null) {
			if (empty && (retries > 0) && busy && (hud != null)) {
				// Услышали только эхо «Слушаю» — слушаем ещё раз
				retries--;
				createRecognizer(getActivityDelegate());
				return;
			}
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
		Log.i("JARVIS tab: phrase «", q.get(0), "»");

		MainActivityDelegate a = getActivityDelegate();
		if (a.handleVoiceCommand(q)) {
			Log.i("JARVIS tab: done by Fermata command");
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
		List<String> r = (partialResults == null) ? null :
				partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
		if ((r == null) || r.isEmpty() || (r.get(0) == null) || r.get(0).trim().isEmpty()) return;
		partial = r.get(0).trim();
		Log.i("JARVIS tab: partial «", partial, "»");
	}

	@Override
	public void onEvent(int eventType, Bundle params) {
	}

	/**
	 * Убрать из распознанного эхо «Слушаю», если микрофон его поймал. Варианты,
	 * от которых ничего не осталось, выбрасываются.
	 */
	@Nullable
	static List<String> stripGreeting(@Nullable List<String> q) {
		if (q == null) return null;
		java.util.ArrayList<String> out = new java.util.ArrayList<>(q.size());
		for (String s : q) {
			if (s == null) continue;
			String t = GREETING_ECHO.matcher(s.trim()).replaceFirst("").trim();
			if (!t.isEmpty()) out.add(t);
		}
		return out;
	}

	private static String errorName(int e) {
		return switch (e) {
			case SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT";
			case SpeechRecognizer.ERROR_NETWORK -> "NETWORK";
			case SpeechRecognizer.ERROR_AUDIO -> "AUDIO";
			case SpeechRecognizer.ERROR_SERVER -> "SERVER";
			case SpeechRecognizer.ERROR_CLIENT -> "CLIENT";
			case SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT";
			case SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH";
			case SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY";
			case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS";
			default -> String.valueOf(e);
		};
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
	/** Сколько раз начать запись заново, если вы ещё не начали говорить. */
	private static final int RETRIES = 1;
	/** Пауза после «Слушаю» до включения микрофона, мс: на телефоне и в машине. */
	private static final long AFTER_GREETING_MS = 300;
	private static final long AFTER_GREETING_CAR_MS = 1200;
	/** «Слушаю» (и «слушаю, сэр» и т. п.) в начале распознанного — это эхо. */
	private static final java.util.regex.Pattern GREETING_ECHO = java.util.regex.Pattern.compile(
			"^(?iu)(слушаю|слушай|слушаем)[\\s,.!…]*");
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
