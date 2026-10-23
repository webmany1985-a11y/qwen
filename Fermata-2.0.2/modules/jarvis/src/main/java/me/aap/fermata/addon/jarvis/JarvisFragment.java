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
	private CarMic carMic;
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
		rootView = root;
		preloadHome(ctx);
		QwenChat q = new QwenChat(ctx);
		// Страница Qwen — через 3 с, если не идёт разговор (иначе — при вопросе)
		main.postDelayed(() -> {
			if ((qwen == q) && !busy) q.load();
		}, QWEN_LOAD_DELAY_MS);
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
		q.load();
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
		q.rememberModel();
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
		rootView = null;
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

	/** Номер запроса к JARVIS: касание шара во время ожидания отменяет текущий. */
	private int request;

	/** Слушатель запроса к JARVIS (приложению): после отмены ответ не произносится. */
	private JarvisBridge.Listener forRequest() {
		int id = ++request;
		return new JarvisBridge.Listener() {
			@Override
			public void onThinking() {
				if (id == request) JarvisFragment.this.onThinking();
			}

			@Override
			public void onSpeaking() {
				if (id == request) JarvisFragment.this.onSpeaking();
			}

			@Override
			public void onDone() {
				if (id == request) JarvisFragment.this.onDone();
			}

			@Override
			public boolean isCancelled() {
				return id != request;
			}
		};
	}

	/** Идёт ответ голосом — чем его прервать. */
	private Cancellable speech = Cancellable.CANCELED;

	private void onCircleClick() {
		if (!busy) listen();
		else if ((phase() == HudMath.Phase.SPEAK) || (phase() == HudMath.Phase.THINK)) {
			// Ответ не нужен дальше — замолчать; следующее касание — снова «Слушаю»
			Log.i("JARVIS tab: reply stopped by touch, phase=", phase());
			QwenChat q = qwen;
			if (q != null) q.cancel();
			Cancellable s = speech;
			speech = Cancellable.CANCELED;
			s.cancel();
			JarvisBridge.stopSpeaking();
			request++;
			done();
		}
		else if ((phrase != null) && (recognizer != null) && !heard) {
			// Дослушиваем продолжение, а вы уже договорили — выполнить сразу
			destroyRecognizer();
			finishPhrase();
		}
		else if (inConversation && (phrase == null) && !heard) {
			// Разговор, а говорить больше нечего — касание заканчивает его
			Log.i("JARVIS tab: conversation ended by touch");
			stopListening();
			if (busy) done();
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
			if (busy && (hud != null) && (recognizer == null) && (greeting == null)) withMic(a, () -> createRecognizer(a));
		}, pause));
	}

	/**
	 * Включён «Микрофон машины» и мы в машине — сначала взять микрофон
	 * магнитолы (CarMic), затем then. Не вышло — микрофон телефона.
	 */
	private void withMic(MainActivityDelegate a, Runnable then) {
		boolean on = a.isCarActivityNotMirror() &&
				me.aap.fermata.FermataApplication.get().getPreferenceStore().getBooleanPref(JarvisAddon.CAR_MIC);
		if (!on) {
			then.run();
			return;
		}
		CarMic m = carMic;
		if (m == null) carMic = m = new CarMic(requireContext());
		m.take(taken -> {
			if (busy && (hud != null) && (recognizer == null)) then.run();
		});
	}

	private void releaseMic() {
		CarMic m = carMic;
		if (m != null) m.release();
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
		releaseMic();
		abandonFocus();
		Cancellable i = interrupt;
		interrupt = Cancellable.CANCELED;
		i.cancel();
		busy = false;
		inConversation = false;
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
		if (silence && inConversation) {
			Log.i("JARVIS tab: silence — conversation over");
		} else if (silence) {
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
		// Фраза записана — магнитолу из режима звонка, ответ звучит обычным звуком
		releaseMic();
		List<String> q = (alt != null) ? alt : Collections.singletonList(p.toString());
		Log.i("JARVIS tab: phrase «", q.get(0), "»");
		if (inConversation && isConversationEnd(q.get(0))) {
			Log.i("JARVIS tab: conversation ended by phrase");
			done();
			return;
		}

		MainActivityDelegate a = getActivityDelegate();
		// Умный дом и маршрут — раньше команд Fermata: её «открой…» и «включи…»
		// забрали бы «открой ворота» и «включи свет» себе
		if (gateCommand(q) != null) {
			gate(a, gateCommand(q));
		} else if (routeDestination(q) != null) {
			startRoute(a, routeDestination(q));
		} else if (!home(a, q)) {
			afterHome(a, q);
		}
	}

	/** Не Умный дом: команды Fermata, телефона, Qwen. */
	private void afterHome(MainActivityDelegate a, List<String> q) {
		if (a.handleVoiceCommand(q)) {
			Log.i("JARVIS tab: done by Fermata command");
			// Команду выполнила Fermata. done() вернёт музыку, только если команда
			// её не трогала: так устроен interruptPlayback — «пауза» останется паузой
			done();
		} else if ((QwenChat.phoneCommand(q) != null) && JarvisBridge.isInstalled(a.getContext())) {
			String cmd = QwenChat.phoneCommand(q);
			Log.i("JARVIS tab: phone command — to JARVIS: «", cmd, "»");
			JarvisBridge.ask(a, cmd, forRequest());
		} else if (qwen != null) {
			askQwen(a, q.get(0));
		} else if (!JarvisBridge.ask(a, q.get(0), forRequest())) {
			toast(R.string.jarvis_not_installed);
			done();
		}
	}

	@Nullable
	private android.widget.FrameLayout rootView;

	@Nullable
	private static Boolean gateCommand(List<String> alternatives) {
		for (String s : alternatives) {
			Boolean g = YandexHome.gateCommand(s);
			if (g != null) return g;
		}
		return null;
	}

	private static final String YANDEX_PREFS = "jarvis_yandex";

	/** Ворота через Умный дом Яндекса ({@link YandexHome}). */
	private void gate(MainActivityDelegate a, boolean open) {
		Context ctx = a.getContext().getApplicationContext();
		android.content.SharedPreferences sp = ctx.getSharedPreferences(YANDEX_PREFS, Context.MODE_PRIVATE);
		String token = sp.getString("token", null);
		if (token == null) {
			yandexLogin(a, () -> gate(a, open));
			return;
		}
		setPhase(HudMath.Phase.THINK);
		me.aap.fermata.FermataApplication.get().execute(() -> {
			String reply;
			try {
				reply = YandexHome.gate(token, open);
			} catch (YandexHome.AuthException ex) {
				Log.i("JARVIS gate: token rejected (", ex.getMessage(), ") — login again");
				sp.edit().remove("token").apply();
				main.post(() -> yandexLogin(a, () -> gate(a, open)));
				return;
			} catch (YandexHome.NoAccessException ex) {
				reply = noAccess(sp);
			} catch (Exception ex) {
				Log.e(ex, "JARVIS gate: request failed");
				reply = "Умный дом Яндекса не ответил";
			}
			String r = reply;
			main.post(() -> say(a, r));
		});
	}

	/** Состав Умного дома (v1.0/user/info) — общий для вкладки, обновляется раз в 10 минут. */
	@Nullable
	private static volatile org.json.JSONObject homeInfo;
	private static volatile long homeInfoAt;
	/** Вход, которым загружен состав: вошли заново — состав не наш. */
	@Nullable
	private static volatile String homeOwner;
	/** Последняя неудачная загрузка — «включи музыку» не ждёт сеть на каждой фразе. */
	private static volatile long homeFailedAt;
	private static final long HOME_RETRY_MS = 60_000L;
	/** Загрузка заранее получила 403 — сказать об этом на ближайшей команде дома. */
	private static volatile boolean homeNoAccess;

	/**
	 * 403 (журнал 23.10.2026 11:43: вход выполнен, user/info — «Forbidden»): у
	 * приложения нет прав на Умный дом. Вход стереть: после правки прав на
	 * oauth.yandex.ru нужен новый вход — следующая команда покажет страницу.
	 */
	private static String noAccess(android.content.SharedPreferences sp) {
		Log.i("JARVIS home: HTTP 403 — the Yandex app has no smart home rights (iot:view, iot:control)");
		sp.edit().remove("token").apply();
		homeInfo = null;
		return YandexHome.NO_ACCESS;
	}
	private static final long HOME_INFO_TTL_MS = 10 * 60_000L;

	@Nullable
	private static String yandexToken(Context ctx) {
		return ctx.getApplicationContext().getSharedPreferences(YANDEX_PREFS, Context.MODE_PRIVATE)
				.getString("token", null);
	}

	private static boolean homeInfoFresh(@Nullable String token) {
		return (homeInfo != null) && (token != null) && token.equals(homeOwner) &&
				(android.os.SystemClock.elapsedRealtime() - homeInfoAt < HOME_INFO_TTL_MS);
	}

	/** Не в главном потоке. */
	private static org.json.JSONObject loadHomeInfo(String token) throws Exception {
		org.json.JSONObject i;
		try {
			i = YandexHome.info(token);
		} catch (Exception ex) {
			homeFailedAt = android.os.SystemClock.elapsedRealtime();
			throw ex;
		}
		homeInfo = i;
		homeOwner = token;
		homeInfoAt = android.os.SystemClock.elapsedRealtime();
		Log.i("JARVIS home: ", count(i, "scenarios"), " scenarios, ", count(i, "devices"), " devices");
		return i;
	}

	private static int count(org.json.JSONObject i, String key) {
		org.json.JSONArray a = i.optJSONArray(key);
		return (a == null) ? 0 : a.length();
	}

	/** Загрузить состав Умного дома заранее — при открытии вкладки, если вход уже был. */
	private static void preloadHome(Context ctx) {
		String token = yandexToken(ctx);
		if (token == null) Log.i("JARVIS home: no Yandex login yet");
		if ((token == null) || homeInfoFresh(token)) return;
		me.aap.fermata.FermataApplication.get().execute(() -> {
			try {
				loadHomeInfo(token);
			} catch (YandexHome.NoAccessException ex) {
				noAccess(ctx.getApplicationContext().getSharedPreferences(YANDEX_PREFS, Context.MODE_PRIVATE));
				homeNoAccess = true;
			} catch (YandexHome.AuthException ex) {
				// Вход отозван (журнал 23.10.2026 12:11: после правки прав приложения — 401):
				// стереть, чтобы ближайшая команда дома показала страницу входа
				Log.i("JARVIS home: token rejected on preload (", ex.getMessage(), ") — login on next command");
				android.content.SharedPreferences sp = ctx.getApplicationContext()
						.getSharedPreferences(YANDEX_PREFS, Context.MODE_PRIVATE);
				// Только если за это время не вошли заново
				if (token.equals(sp.getString("token", null))) sp.edit().remove("token").apply();
				homeInfo = null;
			} catch (Exception ex) {
				Log.i("JARVIS home: preload failed: ", ex.getMessage());
			}
		});
	}

	@Nullable
	private static YandexHome.Action homeMatch(List<String> q, @Nullable org.json.JSONObject info) {
		for (String s : q) {
			YandexHome.Action m = YandexHome.match(s, info);
			if (m != null) return m;
		}
		return null;
	}

	/**
	 * Устройство или сценарий Умного дома по фразе («включи свет в гараже»,
	 * «запусти сценарий Утро»). true — фраза взята; false — не Умный дом, она
	 * идёт дальше. Без входа в Яндекс — всегда false: «включи музыку» как раньше.
	 */
	private boolean home(MainActivityDelegate a, List<String> q) {
		String token = yandexToken(a.getContext());
		boolean looks = false;
		for (String s : q) looks |= YandexHome.looksLike(s);
		if ((token == null) && looks && homeNoAccess) {
			// Состав не дали (403) при загрузке заранее — сказать почему; следующая команда — вход
			homeNoAccess = false;
			say(a, YandexHome.NO_ACCESS);
			return true;
		}
		if (token == null) {
			// Входа ещё не было (журнал 23.10.2026 10:44: «включи свет» молча ушёл
			// в фонарик): если ClientID вписан — страница входа, затем та же фраза
			String clientId = me.aap.fermata.FermataApplication.get().getPreferenceStore()
					.getStringPref(JarvisAddon.YANDEX_CLIENT_ID).trim();
			if (!looks || clientId.isEmpty() || (rootView == null) || a.isCarActivityNotMirror()) {
				if (looks) Log.i("JARVIS home: no Yandex login", clientId.isEmpty() ? ", no ClientID" : "");
				return false;
			}
			Log.i("JARVIS home: no Yandex login — showing login");
			yandexLogin(a, () -> {
				if (!home(a, q)) afterHome(a, q);
			}, () -> afterHome(a, q));
			return true;
		}
		if (homeInfoFresh(token)) {
			YandexHome.Action m = homeMatch(q, homeInfo);
			if (m == null) {
				if (looks) Log.i("JARVIS home: no device or scenario for «", q.get(0), "» — ", YandexHome.names(homeInfo));
				return false;
			}
			runHome(a, token, m);
			return true;
		}
		if (!looks) return false;
		if (android.os.SystemClock.elapsedRealtime() - homeFailedAt < HOME_RETRY_MS) {
			Log.i("JARVIS home: user/info failed recently — not waiting");
			preloadHome(a.getContext());
			return false;
		}
		// Состав ещё не загружен — спросить, затем решить
		setPhase(HudMath.Phase.THINK);
		me.aap.fermata.FermataApplication.get().execute(() -> {
			YandexHome.Action m = null;
			try {
				m = homeMatch(q, loadHomeInfo(token));
			} catch (YandexHome.NoAccessException ex) {
				String r = noAccess(a.getContext().getApplicationContext()
						.getSharedPreferences(YANDEX_PREFS, Context.MODE_PRIVATE));
				main.post(() -> {
					if (busy) say(a, r);
				});
				return;
			} catch (YandexHome.AuthException ex) {
				Log.i("JARVIS home: token rejected (", ex.getMessage(), ")");
				a.getContext().getApplicationContext().getSharedPreferences(YANDEX_PREFS, Context.MODE_PRIVATE)
						.edit().remove("token").apply();
			} catch (Exception ex) {
				Log.i("JARVIS home: user/info failed: ", ex.getMessage());
			}
			YandexHome.Action found = m;
			if (m == null) Log.i("JARVIS home: no device or scenario for «", q.get(0), "» — ", YandexHome.names(homeInfo));
			main.post(() -> {
				if (!busy) return;
				if (found != null) runHome(a, token, found);
				else afterHome(a, q);
			});
		});
		return true;
	}

	private void runHome(MainActivityDelegate a, String token, YandexHome.Action m) {
		setPhase(HudMath.Phase.THINK);
		me.aap.fermata.FermataApplication.get().execute(() -> {
			String reply;
			try {
				reply = YandexHome.run(token, m);
			} catch (YandexHome.AuthException ex) {
				Log.i("JARVIS home: token rejected (", ex.getMessage(), ") — login again");
				a.getContext().getApplicationContext().getSharedPreferences(YANDEX_PREFS, Context.MODE_PRIVATE)
						.edit().remove("token").apply();
				main.post(() -> yandexLogin(a, () -> runHome(a, yandexToken(a.getContext()), m)));
				return;
			} catch (YandexHome.NoAccessException ex) {
				reply = noAccess(a.getContext().getApplicationContext()
						.getSharedPreferences(YANDEX_PREFS, Context.MODE_PRIVATE));
			} catch (Exception ex) {
				Log.e(ex, "JARVIS home: request failed");
				reply = "Умный дом Яндекса не ответил";
			}
			String r = reply;
			main.post(() -> say(a, r));
		});
	}

	/**
	 * Вход в Яндекс ID — один раз, на телефоне: страница входа поверх круга;
	 * Яндекс возвращает на {@link YandexHome#REDIRECT} с токеном в адресе —
	 * токен сохраняется на телефоне, страница закрывается, then продолжает.
	 */
	private void yandexLogin(MainActivityDelegate a, Runnable then) {
		yandexLogin(a, then, () -> {
			if (busy) done();
		});
	}

	/** onCancel — «Отмена» на странице входа. */
	private void yandexLogin(MainActivityDelegate a, Runnable then, Runnable onCancel) {
		String clientId = me.aap.fermata.FermataApplication.get().getPreferenceStore()
				.getStringPref(JarvisAddon.YANDEX_CLIENT_ID).trim();
		if (clientId.isEmpty()) {
			say(a, "Для Умного дома нужен ClientID Яндекса: настройки, дополнения, JARVIS");
			return;
		}
		android.widget.FrameLayout root = rootView;
		if (a.isCarActivityNotMirror() || (root == null)) {
			say(a, "Нужно один раз войти в Яндекс: на телефоне откройте вкладку JARVIS и скажите «открой ворота»");
			return;
		}
		Context ctx = root.getContext();
		android.webkit.WebView w = new android.webkit.WebView(ctx);
		w.getSettings().setJavaScriptEnabled(true);
		w.getSettings().setDomStorageEnabled(true);
		android.widget.Button cancel = new android.widget.Button(ctx);
		cancel.setText("Отмена");
		Runnable close = () -> {
			root.removeView(w);
			root.removeView(cancel);
			w.destroy();
			if (hud != null) hud.setVisibility(View.VISIBLE);
		};
		cancel.setOnClickListener(v -> {
			close.run();
			if (busy) onCancel.run();
		});
		w.setWebViewClient(new android.webkit.WebViewClient() {
			private boolean caught(String url) {
				if ((url == null) || !url.startsWith(YandexHome.REDIRECT)) return false;
				String token = YandexHome.tokenFromRedirect(url);
				Log.i("JARVIS gate: Yandex login ", (token != null) ? "done" : "without token");
				close.run();
				if (token != null) {
					a.getContext().getApplicationContext().getSharedPreferences(YANDEX_PREFS, Context.MODE_PRIVATE)
							.edit().putString("token", token).apply();
					homeFailedAt = 0;
					homeNoAccess = false;
					preloadHome(a.getContext());
					then.run();
				} else if (busy) {
					say(a, "Войти в Яндекс не получилось");
				}
				return true;
			}

			@Override
			public boolean shouldOverrideUrlLoading(android.webkit.WebView view,
																							android.webkit.WebResourceRequest request) {
				String u = request.getUrl().toString();
				if (caught(u)) return true;
				String scheme = request.getUrl().getScheme();
				return !"https".equals(scheme) && !"http".equals(scheme);
			}

			@Override
			public void onPageStarted(android.webkit.WebView view, String url, android.graphics.Bitmap favicon) {
				if (!caught(url)) super.onPageStarted(view, url, favicon);
			}
		});
		if (hud != null) hud.setVisibility(View.GONE);
		root.addView(w, new android.widget.FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
				android.view.Gravity.BOTTOM | android.view.Gravity.END);
		root.addView(cancel, lp);
		Log.i("JARVIS gate: Yandex login shown");
		w.loadUrl(YandexHome.authorizeUrl(clientId));
	}

	@Nullable
	private static String routeDestination(List<String> alternatives) {
		for (String s : alternatives) {
			String d = RoutePhrase.destination(s);
			if (d != null) return d;
		}
		return null;
	}

	/**
	 * Маршрут в Google Картах (пожелание 23.10.2026: «открыть Гугл карты с
	 * маршрутом до работы»). Работа и дом — адреса из настроек вкладки; пусто —
	 * слова «Работа», «Дом» (Карты сами сопоставят с сохранёнными местами).
	 */
	private void startRoute(MainActivityDelegate a, String dest) {
		me.aap.utils.pref.PreferenceStore ps = me.aap.fermata.FermataApplication.get().getPreferenceStore();
		String q;
		String spoken;
		if (RoutePhrase.WORK.equals(dest)) {
			q = ps.getStringPref(JarvisAddon.WORK_ADDRESS).trim();
			if (q.isEmpty()) q = "Работа";
			spoken = "Прокладываю маршрут на работу";
		} else if (RoutePhrase.HOME.equals(dest)) {
			q = ps.getStringPref(JarvisAddon.HOME_ADDRESS).trim();
			if (q.isEmpty()) q = "Дом";
			spoken = "Прокладываю маршрут домой";
		} else {
			q = dest;
			spoken = "Прокладываю маршрут: " + dest;
		}
		Log.i("JARVIS tab: route to «", q, "»");
		Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=" +
				android.net.Uri.encode(q))).setPackage("com.google.android.apps.maps")
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		try {
			a.getContext().getApplicationContext().startActivity(i);
		} catch (Exception ex) {
			Log.e(ex, "JARVIS tab: Google Maps not started");
			spoken = "Не получилось открыть Google Карты";
		}
		say(a, spoken);
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
				if (s.isEmpty()) say(a, str(R.string.jarvis_qwen_empty));
				else sayAndContinue(a, s);
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
		speech = JarvisBridge.say(a, text, this);
	}

	/**
	 * Ответ Qwen — и разговор продолжается: после ответа снова слушаем, без
	 * касания шара (пожелание 23.10.2026: «спрашиваю погоду — он переспрашивает
	 * город и отключается»). Разговор кончается молчанием (~10 с: две записи
	 * по ~5 с), словами «спасибо», «всё», «хватит»… или касанием шара.
	 */
	private void sayAndContinue(MainActivityDelegate a, String text) {
		if (!busy) return;
		setPhase(HudMath.Phase.SPEAK);
		speech = JarvisBridge.say(a, text, new JarvisBridge.Listener() {
			@Override
			public void onDone() {
				if (busy && (hud != null)) followUp(a);
			}
		});
	}

	/** Слушать следующую фразу разговора — без «Слушаю», музыка остаётся на паузе. */
	private void followUp(MainActivityDelegate a) {
		Log.i("JARVIS tab: conversation goes on, listening");
		inConversation = true;
		retries = FOLLOW_UP_RETRIES;
		continuations = CONTINUATIONS;
		phrase = null;
		firstAlternatives = null;
		setPhase(HudMath.Phase.APPEAR);
		// В машине ответ ещё звучит из динамиков, когда телефон уже договорил: микрофон
		// не должен принять конец ответа за новую фразу
		long pause = a.isCarActivityNotMirror() ? FOLLOW_UP_CAR_MS : FOLLOW_UP_MS;
		main.postDelayed(() -> {
			if (busy && (hud != null) && (recognizer == null)) withMic(a, () -> createRecognizer(a));
		}, pause);
	}

	/** Идёт разговор: после ответа слушаем дальше ({@link #followUp}). */
	private boolean inConversation;

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

	/** Пауза между концом ответа и записью следующей фразы, мс: на телефоне и в машине. */
	private static final long FOLLOW_UP_MS = 500;
	private static final long FOLLOW_UP_CAR_MS = 1500;
	/** В разговоре после ответа: повторных записей при молчании (каждая ~5 с). */
	private static final int FOLLOW_UP_RETRIES = 1;
	/** Фраза, которой заканчивают разговор. */
	private static final java.util.regex.Pattern CONVERSATION_END = java.util.regex.Pattern.compile(
			"^(?iu)(спасибо|всё|все|хватит|достаточно|отбой|стоп|пока|ладно,? всё|ничего|конец)[\\s,.!…]*(спасибо|джарвис)?[\\s,.!…]*$");

	static boolean isConversationEnd(String phrase) {
		return CONVERSATION_END.matcher(phrase.trim()).matches();
	}

	/** Через сколько после открытия вкладки загружать страницу Qwen, мс. */
	private static final long QWEN_LOAD_DELAY_MS = 3000;
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
