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
 * сама Fermata («пауза», «играй…»). Команду телефону («позвони», «открой»…)
 * выполняет JARVIS на телефоне, а разговор ведёт Qwen с chat.qwen.ai
 * (QwenChat; пожелание 21.10.2026). Ответ только произносится голосом
 * JARVIS — под кругом ничего не показывается.
 *
 * Сайт Qwen открыт под кругом невидимым. Долгое касание шара показывает его
 * (вход, история разговора); кнопка «Готово» прячет обратно.
 *
 * Перед записью JARVIS говорит «Слушаю». Тишина в начале не обрывает
 * запись сразу: если речь так и не началась, слушаем ещё раз, молча. Если
 * речь была, но не разобрана, — JARVIS говорит «Не расслышал, повторите» и
 * слушает ещё раз (журнал 21.10.2026, 23:18: молчаливые повторы после речи
 * выглядели как «микрофон срабатывает несколько раз подряд»).
 *
 * Микрофон включается на фразу один раз: распознавание просится ждать паузу
 * SILENCE_MS. Дослушивание продолжения отдельной записью (CONTINUATIONS)
 * выключено по той же причине — каждое включение телефон отмечает сигналом.
 *
 * Музыка стоит на паузе, пока слушаем, ждём и произносим ответ, и
 * возобновляется после.
 */
public class JarvisFragment extends MainActivityFragment
		implements RecognitionListener, JarvisBridge.Listener {
	@Nullable
	private JarvisHudView hud;
	@Nullable
	private QwenChat qwen;
	@Nullable
	private View qwenDone;
	/** Когда коснулись шара — долгое касание открывает сайт Qwen. */
	private long downAt;
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
	@Nullable
	private FutureSupplier<TextToSpeech> warm;
	@Nullable
	private Locale warmLocale;
	/** Распознавание Google не запустилось — до конца сессии брать системное. */
	private boolean useDefaultRecognizer;
	private boolean usingGoogle;
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
		Context ctx = requireContext();
		android.widget.FrameLayout root = new android.widget.FrameLayout(ctx);
		QwenChat q = new QwenChat(ctx);
		View web = q.getView();
		web.setAlpha(0f);
		web.setFocusable(false);
		root.addView(web, new android.widget.FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		qwen = q;

		JarvisHudView v = new JarvisHudView(dynCtx(ctx));
		// Голос — только касанием шара; касание в любом другом месте показывает
		// строку приложений, которая сама прячется (пожелание 21.10.2026)
		v.setOnTouchListener((view, e) -> {
			if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) downAt = e.getEventTime();
			if (e.getAction() != android.view.MotionEvent.ACTION_UP) return true;
			if (v.isOnSphere(e.getX(), e.getY())) {
				if ((e.getEventTime() - downAt >= LONG_PRESS_MS) && !busy) showQwenPage();
				else onCircleClick();
			} else {
				showBarsForAWhile();
			}
			view.performClick();
			return true;
		});
		root.addView(v, new android.widget.FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		hud = v;

		android.widget.Button close = new android.widget.Button(ctx);
		close.setText(dynCtx(ctx).getString(R.string.jarvis_qwen_done));
		close.setVisibility(View.GONE);
		close.setOnClickListener(b -> hideQwenPage());
		android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
				android.view.Gravity.BOTTOM | android.view.Gravity.END);
		int m = (int) (16 * ctx.getResources().getDisplayMetrics().density);
		lp.setMargins(m, m, m, m);
		root.addView(close, lp);
		qwenDone = close;
		// Голос JARVIS читается заранее, чтобы первое «Слушаю» уже было его голосом
		me.aap.fermata.jarvis.JarvisVoice.refresh();
		// И синтезатор подключается заранее — первое «Слушаю» без задержки
		// Приложение могло ещё не подняться (восстановление после выгрузки) — ждать его
		MainActivityDelegate.getActivityDelegate(ctx).onSuccess(a -> {
			if (hud != null) warmTts(ctx, Locale.forLanguageTag(a.getPrefs().getVoiceControlLang(a)));
		});
		return root;
	}

	/** Показать сайт Qwen поверх круга: войти, посмотреть разговор. */
	private void showQwenPage() {
		QwenChat q = qwen;
		if ((q == null) || (hud == null) || (qwenDone == null)) return;
		Log.i("[QWEN] page shown");
		restoreBars();
		View web = q.getView();
		web.setAlpha(1f);
		web.setFocusable(true);
		web.setFocusableInTouchMode(true);
		hud.setVisibility(View.GONE);
		qwenDone.setVisibility(View.VISIBLE);
	}

	private void hideQwenPage() {
		QwenChat q = qwen;
		if ((q == null) || (hud == null) || (qwenDone == null)) return;
		Log.i("[QWEN] page hidden");
		View web = q.getView();
		web.clearFocus();
		web.setFocusable(false);
		web.setAlpha(0f);
		android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager)
				web.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
		if (im != null) im.hideSoftInputFromWindow(web.getWindowToken(), 0);
		qwenDone.setVisibility(View.GONE);
		hud.setVisibility(View.VISIBLE);
		showBarsForAWhile();
	}

	private boolean isQwenPageShown() {
		return (qwenDone != null) && (qwenDone.getVisibility() == View.VISIBLE);
	}

	/** Сколько строка приложений видна после касания или входа во вкладку, мс. */
	private static final long BARS_VISIBLE_MS = 4000;
	private final Runnable hideBars = () -> setBars(false);

	private void setBars(boolean visible) {
		Context ctx = getContext();
		if (ctx == null) return;
		try {
			MainActivityDelegate.get(ctx).setBarsHidden(!visible);
		} catch (RuntimeException ignore) {
		}
	}

	private void showBarsForAWhile() {
		main.removeCallbacks(hideBars);
		setBars(true);
		main.postDelayed(hideBars, BARS_VISIBLE_MS);
	}

	/** Вкладка ушла с экрана — строку вернуть насовсем. */
	private void restoreBars() {
		main.removeCallbacks(hideBars);
		setBars(true);
	}

	@Override
	public void onResume() {
		super.onResume();
		if (!isHidden()) showBarsForAWhile();
	}

	@Override
	public void onDestroyView() {
		restoreBars();
		stopListening();
		closeWarm();
		QwenChat q = qwen;
		qwen = null;
		qwenDone = null;
		if (q != null) q.destroy();
		// Вопрос Qwen остался без ответа — разговор окончен, иначе круг не примет новый
		if (busy) done();
		hud = null;
		super.onDestroyView();
	}

	@Override
	public void onPause() {
		super.onPause();
		restoreBars();
		stopListening();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		if (hidden) {
			restoreBars();
			stopListening();
		} else {
			showBarsForAWhile();
		}
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
		if (isQwenPageShown()) hideQwenPage();
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
		// После паузы своей музыки: иначе Fermata сочла бы себя прерванной и
		// после разговора сама включила бы музыку, даже после команды «пауза»
		requestFocus(requireContext());
		retries = RETRIES;
		continuations = CONTINUATIONS;
		phrase = null;
		firstAlternatives = null;
		// В машине звук идёт через Android Auto с задержкой: телефон уже закончил
		// «Слушаю», а динамики ещё говорят, и микрофон слышит само «Слушаю»
		// (журнал/замечание 16.10.2026: JARVIS отвечал «Не расслышал»)
		promptAndListen(a, R.string.jarvis_say_listening);
	}

	/** Произнести подсказку («Слушаю», «Не расслышал, повторите») и включить микрофон. */
	private void promptAndListen(MainActivityDelegate a, int textRes) {
		long pause = a.isCarActivityNotMirror() ? AFTER_GREETING_CAR_MS : AFTER_GREETING_MS;
		sayPrompt(a, textRes, () -> main.postDelayed(() -> {
			if (busy && (hud != null) && (recognizer == null) && (greeting == null)) createRecognizer(a);
		}, pause));
	}

	/** Произнести подсказку и затем, в главном потоке, выполнить then. */
	private void sayPrompt(MainActivityDelegate a, int textRes, Runnable then) {
		Context ctx = requireContext();
		String text = dynCtx(ctx).getString(textRes);
		Locale locale = Locale.forLanguageTag(a.getPrefs().getVoiceControlLang(a));
		warmTts(ctx, locale).onCompletion((tts, err) -> main.post(() -> {
			if (err != null) {
				Log.e(err, "JARVIS tab: TTS «Слушаю» init failed, lang=", locale);
				warm = null;
				then.run();
				return;
			}
			if (!busy) return;
			greeting = tts;
			me.aap.fermata.jarvis.JarvisVoice.apply(tts);
			Log.i("JARVIS tab: saying «", text, "», lang=", locale);
			tts.speak(text).onCompletion((r, serr) -> main.post(() -> {
				if (greeting != tts) return; // запись уже отменена
				if (serr != null) Log.e(serr, "JARVIS tab: TTS «Слушаю» failed");
				greeting = null;
				then.run();
			}));
		}));
	}

	private void closeGreeting() {
		TextToSpeech tts = greeting;
		greeting = null;
		if (tts != null) tts.stop();
	}

	/**
	 * Синтезатор речи держится готовым, пока открыта вкладка: подключение к нему
	 * занимало ~2 с при каждом «Слушаю» (журнал 17.10.2026, 07:22:25–33).
	 */
	private FutureSupplier<TextToSpeech> warmTts(Context ctx, Locale locale) {
		FutureSupplier<TextToSpeech> w = warm;
		if ((w == null) || !locale.equals(warmLocale)) {
			closeWarm();
			warmLocale = locale;
			warm = w = TextToSpeech.create(ctx.getApplicationContext(), locale);
		}
		return w;
	}

	private void closeWarm() {
		FutureSupplier<TextToSpeech> w = warm;
		warm = null;
		if (w != null) w.onSuccess(TextToSpeech::close);
	}

	/**
	 * Распознавание приложения Google (по сети). Распознавание по умолчанию на
	 * телефоне — офлайн-модуль «Распознавание и синтез речи Google», и на русских
	 * фразах он отвечал NO_MATCH (журнал 17.10.2026, 07:22:33–57).
	 */
	@Nullable
	private static android.content.ComponentName googleRecognizer(Context ctx) {
		try {
			for (android.content.pm.ResolveInfo r : ctx.getPackageManager().queryIntentServices(
					new Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)) {
				if ("com.google.android.googlequicksearchbox".equals(r.serviceInfo.packageName)) {
					return new android.content.ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
				}
			}
		} catch (Exception ex) {
			Log.e(ex, "JARVIS tab: failed to query recognition services");
		}
		return null;
	}

	private void createRecognizer(MainActivityDelegate a) {
		heard = false;
		partial = null;
		Log.i("JARVIS tab: recognizer start, segment=", (phrase == null) ? 1 : 1 + CONTINUATIONS - continuations,
				", retries=", retries, ", lang=", a.getPrefs().getVoiceControlLang(a));
		Context ctx = requireContext();
		android.content.ComponentName google = useDefaultRecognizer ? null : googleRecognizer(ctx);
		Log.i("JARVIS tab: recognizer ", (google != null) ? google.flattenToShortString() : "default");
		SpeechRecognizer sr = (google != null) ? SpeechRecognizer.createSpeechRecognizer(ctx, google) :
				SpeechRecognizer.createSpeechRecognizer(ctx);
		usingGoogle = (google != null);
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
		i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
		// Не обрывать фразу на короткой паузе. Распознавание может эту просьбу
		// не выполнить — тогда это будет видно по журналу (фраза обрезана)
		i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS);
		i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS);
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
		abandonFocus();
		Cancellable i = interrupt;
		interrupt = Cancellable.CANCELED;
		i.cancel();
		busy = false;
		phrase = null;
		firstAlternatives = null;
		setPhase(HudMath.Phase.IDLE);
	}

	/**
	 * Звук на время разговора — у JARVIS: другая музыка (не Fermata) встаёт
	 * на паузу, а не только приглушается (замечание 22.10.2026: «музыка толком
	 * не затихает»). Музыку Fermata ставит на паузу interruptPlayback.
	 */
	@Nullable
	private android.media.AudioFocusRequest focus;

	private void requestFocus(Context ctx) {
		if ((focus != null) || (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O)) return;
		android.media.AudioManager am = (android.media.AudioManager)
				ctx.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
		if (am == null) return;
		android.media.AudioFocusRequest r = new android.media.AudioFocusRequest.Builder(
				android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
				.setAudioAttributes(new android.media.AudioAttributes.Builder()
						.setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
						.setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
				.setOnAudioFocusChangeListener(c -> {
				}).build();
		int res = am.requestAudioFocus(r);
		Log.i("JARVIS tab: audio focus ", (res == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) ?
				"granted" : "refused " + res);
		focus = r;
	}

	private void abandonFocus() {
		android.media.AudioFocusRequest r = focus;
		focus = null;
		if ((r == null) || (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O)) return;
		Context ctx = getContext();
		if (ctx == null) ctx = me.aap.fermata.FermataApplication.get();
		android.media.AudioManager am = (android.media.AudioManager)
				ctx.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
		if (am != null) am.abandonAudioFocusRequest(r);
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
			retries--;
			if (heard) {
				// Речь была, но не разобрана — сказать об этом и слушать снова
				Log.i("JARVIS tab: speech not understood, asking to repeat");
				setPhase(HudMath.Phase.APPEAR);
				promptAndListen(getActivityDelegate(), R.string.jarvis_say_repeat);
			} else {
				// Речь ещё не началась — слушать ещё раз, молча
				createRecognizer(getActivityDelegate());
			}
			return;
		}
		if (!silence && usingGoogle && busy && (hud != null) &&
				((error == SpeechRecognizer.ERROR_CLIENT) || (error == SpeechRecognizer.ERROR_SERVER) ||
						(error == SpeechRecognizer.ERROR_NETWORK) || (error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) ||
						(error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))) {
			Log.i("JARVIS tab: Google recognizer failed, falling back to default");
			useDefaultRecognizer = true;
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
		} else if (QwenChat.isPhoneCommand(q.get(0)) && JarvisBridge.isInstalled(a.getContext())) {
			Log.i("JARVIS tab: phone command — to JARVIS");
			JarvisBridge.ask(a, q.get(0), this);
		} else if (qwen != null) {
			askQwen(a, q.get(0));
		} else if (!JarvisBridge.ask(a, q.get(0), this)) {
			toast(R.string.jarvis_not_installed);
			done();
		}
	}

	/** Вопрос — Qwen, ответ — голосом JARVIS. */
	private void askQwen(MainActivityDelegate a, String text) {
		QwenChat q = qwen;
		if (q == null) return;
		setPhase(HudMath.Phase.THINK);
		q.ask(text, new QwenChat.Callback() {
			@Override
			public void onReply(@NonNull String reply) {
				String s = QwenChat.forSpeech(reply);
				say(a, s.isEmpty() ? str(R.string.jarvis_qwen_empty) : s);
			}

			@Override
			public void onLogin() {
				if (a.isCarActivityNotMirror()) {
					say(a, str(R.string.jarvis_qwen_login_car));
				} else {
					showQwenPage();
					say(a, str(R.string.jarvis_qwen_login));
				}
			}

			@Override
			public void onError(@NonNull String why) {
				say(a, str(R.string.jarvis_qwen_error) + why);
			}
		});
	}

	private void say(MainActivityDelegate a, String text) {
		if (!busy) return;
		setPhase(HudMath.Phase.SPEAK);
		JarvisBridge.say(a, text, this);
	}

	private String str(int id) {
		Context ctx = getContext();
		return (ctx == null) ? "" : dynCtx(ctx).getString(id);
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

	/** Касание шара дольше этого, мс, — открыть сайт Qwen. */
	private static final long LONG_PRESS_MS = 700;
	/** Сколько раз начать запись заново, если вы ещё не начали говорить. */
	private static final int RETRIES = 1;
	/** Пауза после «Слушаю» до включения микрофона, мс: на телефоне и в машине. */
	private static final long AFTER_GREETING_MS = 300;
	private static final long AFTER_GREETING_CAR_MS = 800;
	/** «Слушаю» или «Не расслышал, повторите» в начале распознанного — это эхо подсказки. */
	private static final java.util.regex.Pattern GREETING_ECHO = java.util.regex.Pattern.compile(
			"^(?iu)((не\\s+)?расслышал[\\s,.!…]*(повторите|повтори)?|слушаю|слушай|слушаем)[\\s,.!…]*");
	/**
	 * Сколько раз дослушать продолжение фразы отдельной записью. 0 — не
	 * дослушивать: микрофон на фразу включается один раз (21.10.2026).
	 */
	private static final int CONTINUATIONS = 0;
	/** Пауза в речи, после которой фраза закончена, мс. */
	private static final long SILENCE_MS = 2000;
	/** Сколько ждать продолжения фразы после паузы, мс. */
	private static final long CONTINUE_WAIT_MS = 1000;

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
