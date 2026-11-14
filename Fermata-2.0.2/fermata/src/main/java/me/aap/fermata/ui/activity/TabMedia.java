package me.aap.fermata.ui.activity;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import me.aap.fermata.R;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.utils.log.Log;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * Звук и видео во вкладках (пожелание 14.11.2026):
 * <ul>
 * <li>переход на другую вкладку ставит на паузу то, что играло в прежней; вкладка JARVIS (сфера) — исключение:
 * на неё можно перейти, дать команду и вернуться, видео при этом идёт;</li>
 * <li>запоминается вкладка, где шло видео, адрес страницы и секунда; если окно закрылось при идущем видео
 * (машина заглохла) — при следующем запуске в машине открывается эта вкладка и видео продолжается.</li>
 * </ul>
 * Решения — в {@link TabMediaRules}.
 */
public final class TabMedia {
	/** Вкладка, в которой может играть звук или видео (веб-вкладки). */
	public interface Pausable {
		/** Вкладка ушла с экрана на другую (не JARVIS) — остановить воспроизведение. */
		void pauseTabMedia();
	}

	private static final String PREFS = "jarvis_tab_media";
	private static final String TAB = "tab";
	private static final String URL = "url";
	private static final String TIME = "time";
	private static final String PLAYING = "playing";
	private static final String PAUSED_AT = "pausedAt";
	private static final String DESTROY_AT = "destroyAt";
	private static final String INSTALL = "install";
	private static final long SNAPSHOT_MS = 10_000L;

	private static int lastMediaTab;
	@Nullable
	private static PlayableItem itemAtLeave;
	private static long lastWebReport;
	@Nullable
	private static Pending pending;
	@Nullable
	private static Handler handler;
	@Nullable
	private static Runnable snapshot;
	@Nullable
	private static MainActivityDelegate snapshotOwner;

	/** Что продолжить во вкладке после запуска. */
	public static final class Pending {
		public final int tab;
		@Nullable
		public final String url;
		public final double time;

		Pending(int tab, @Nullable String url, double time) {
			this.tab = tab;
			this.url = url;
			this.time = time;
		}
	}

	private TabMedia() {
	}

	static int jarvisTab() {
		return R.id.jarvis_addon;
	}

	/** Новое окно приложения (в том числе в машине). */
	static void onActivityCreate(MainActivityDelegate a) {
		lastMediaTab = 0;
		itemAtLeave = null;
		startSnapshots(a);
	}

	static void onActivityDestroy(MainActivityDelegate a) {
		if (snapshotOwner == a) stopSnapshots();
		try {
			snapshotSession(a);
			prefs(a.getContext()).edit().putLong(DESTROY_AT, System.currentTimeMillis()).apply();
		} catch (RuntimeException ex) {
			Log.e(ex, "TabMedia: destroy");
		}
	}

	/** Вызывается после переключения вкладки. */
	static void onSwitch(MainActivityDelegate a, @Nullable ActivityFragment from, ActivityFragment to) {
		int toId = to.getFragmentId();
		int jarvis = jarvisTab();
		boolean fromJarvis = (from != null) && (from.getFragmentId() == jarvis);
		FermataServiceUiBinder b = a.getMediaServiceBinder();

		if (toId == jarvis) {
			if (!fromJarvis) itemAtLeave = playingVideo(b);
			return;
		}

		if (TabMediaRules.shouldPauseOthers(toId, jarvis, lastMediaTab)) {
			for (Fragment f : a.getSupportFragmentManager().getFragments()) {
				if ((f != to) && (f instanceof Pausable p)) {
					try {
						p.pauseTabMedia();
					} catch (RuntimeException ex) {
						Log.e(ex, "TabMedia: pause ", f);
					}
				}
			}
			PlayableItem cur = playingVideo(b);
			if (TabMediaRules.shouldPauseSession(cur != null, fromJarvis, (cur != null) && (cur == itemAtLeave))) {
				Log.i("TabMedia: pause player on tab switch: ", cur);
				b.getMediaSessionCallback().onPause();
			}
			clear(a.getContext());
		}

		lastMediaTab = toId;
		itemAtLeave = null;
	}

	@Nullable
	private static PlayableItem playingVideo(FermataServiceUiBinder b) {
		PlayableItem i = b.getCurrentItem();
		return ((i != null) && i.isVideo() && b.isPlaying()) ? i : null;
	}

	/** Сообщение веб-вкладки: видео играет / встало. */
	public static void reportWeb(Context ctx, int tab, @Nullable String url, double time, double duration,
															 boolean playing) {
		lastWebReport = System.currentTimeMillis();
		SharedPreferences p = prefs(ctx);
		if (playing) {
			p.edit().putInt(TAB, tab).putString(URL, url).putFloat(TIME, (float) time)
					.putFloat("duration", (float) duration).putBoolean(PLAYING, true).putLong(PAUSED_AT, 0)
					.putLong(INSTALL, installTime(ctx)).apply();
		} else if (p.getInt(TAB, 0) == tab) {
			p.edit().putFloat(TIME, (float) time).putBoolean(PLAYING, false)
					.putLong(PAUSED_AT, System.currentTimeMillis()).apply();
		}
	}

	/** Общий проигрыватель (ТВ и т. п.): раз в 10 с, пока окно открыто. */
	private static void snapshotSession(MainActivityDelegate a) {
		if (System.currentTimeMillis() - lastWebReport < 15_000L) return;
		FermataServiceUiBinder b = a.getMediaServiceBinder();
		SharedPreferences p = prefs(a.getContext());
		PlayableItem cur = playingVideo(b);
		if (cur != null) {
			int tab = (lastMediaTab != 0) ? lastMediaTab : a.getActiveFragmentId();
			if ((tab == 0) || (tab == jarvisTab())) return;
			p.edit().putInt(TAB, tab).putString(URL, "").putFloat(TIME, 0).putBoolean(PLAYING, true)
					.putLong(PAUSED_AT, 0).putLong(INSTALL, installTime(a.getContext())).apply();
		} else if (p.getBoolean(PLAYING, false) && "".equals(p.getString(URL, null))) {
			p.edit().putBoolean(PLAYING, false).putLong(PAUSED_AT, System.currentTimeMillis()).apply();
		}
	}

	private static void startSnapshots(MainActivityDelegate a) {
		stopSnapshots();
		snapshotOwner = a;
		Handler h = handler = new Handler(Looper.getMainLooper());
		Runnable r = snapshot = new Runnable() {
			@Override
			public void run() {
				try {
					snapshotSession(a);
				} catch (RuntimeException ex) {
					Log.e(ex, "TabMedia: snapshot");
				}
				if (snapshot == this) h.postDelayed(this, SNAPSHOT_MS);
			}
		};
		h.postDelayed(r, SNAPSHOT_MS);
	}

	private static void stopSnapshots() {
		if ((handler != null) && (snapshot != null)) handler.removeCallbacks(snapshot);
		handler = null;
		snapshot = null;
		snapshotOwner = null;
	}

	/**
	 * При запуске: вкладка, в которой надо продолжить видео (0 — нет, открывать как обычно).
	 * Запись при этом расходуется: второй раз не продолжит.
	 */
	static int takeResumeTab(Context ctx) {
		SharedPreferences p = prefs(ctx);
		int tab = p.getInt(TAB, 0);
		boolean ok = TabMediaRules.shouldResume(tab, p.getBoolean(PLAYING, false), p.getLong(PAUSED_AT, 0),
				p.getLong(DESTROY_AT, 0)) && (p.getLong(INSTALL, -1) == installTime(ctx));
		if (!ok) return 0;
		double time = TabMediaRules.resumeAt(p.getFloat(TIME, 0), p.getFloat("duration", -1));
		pending = new Pending(tab, p.getString(URL, null), time);
		Log.i("TabMedia: resume tab ", tab, " url=", pending.url, " at ", time);
		clear(ctx);
		return tab;
	}

	/** Вкладка при создании забирает, что ей продолжить (null — ничего). */
	@Nullable
	public static Pending takePending(int tab) {
		Pending p = pending;
		if ((p == null) || (p.tab != tab)) return null;
		pending = null;
		return p;
	}

	/** Продолжение в общем проигрывателе (вкладка без адреса страницы — ТВ и т. п.). */
	static void resumeSession(MainActivityDelegate a, int tab) {
		Pending p = pending;
		if ((p == null) || (p.tab != tab) || ((p.url != null) && !p.url.isEmpty())) return;
		pending = null;
		a.postDelayed(() -> {
			FermataServiceUiBinder b = a.getMediaServiceBinder();
			PlayableItem i = b.getCurrentItem();
			if ((i != null) && i.isVideo() && !b.isPlaying()) b.getMediaSessionCallback().onPlay();
		}, 1500L);
	}

	private static void clear(Context ctx) {
		prefs(ctx).edit().remove(TAB).remove(URL).remove(TIME).remove("duration").remove(PLAYING)
				.remove(PAUSED_AT).apply();
	}

	private static SharedPreferences prefs(Context ctx) {
		return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	private static long installTime(Context ctx) {
		try {
			return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
		} catch (Exception ex) {
			return 0;
		}
	}
}
