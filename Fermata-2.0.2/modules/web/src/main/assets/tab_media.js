// Звук и видео вкладки (пожелание 14.11.2026), во всех кадрах страницы, и во встроенных плеерах тоже:
// - команда 'pause' (вкладка ушла с экрана на другую, не JARVIS) — всё на паузу, и пока вкладка скрыта,
//   новое воспроизведение (следующий ролик TikTok и т. п.) сразу останавливается; 'resume' — снова можно;
// - приложению сообщается «играет / встало» и секунда — чтобы после перезапуска открыть ту же вкладку;
// - window.__jarvisResumeAt (ставит приложение при продолжении) — фильм с этой секунды.
(function () {
	if (window.__jarvisTabMedia) return;
	window.__jarvisTabMedia = true;
	var blocked = false, resumed = false, lastSent = 0;

	function isMedia(m) { return (typeof HTMLMediaElement !== 'undefined') && (m instanceof HTMLMediaElement); }
	function real(m) { return !m.muted && (m.volume > 0); }
	function dur(m) { var d = m.duration; return (d > 0 && isFinite(d)) ? d : -1; }
	function send(m, playing) {
		try {
			var api = window.Fermata;
			if (api && api.tabMedia) api.tabMedia(playing, m.currentTime || 0, dur(m));
		} catch (x) { }
	}
	function pauseAll() {
		try {
			var l = document.querySelectorAll('video,audio');
			for (var i = 0; i < l.length; i++) { try { if (!l[i].paused) l[i].pause(); } catch (x) { } }
		} catch (x) { }
	}
	function cmd(c) {
		if (c === 'pause') { blocked = true; pauseAll(); }
		else if (c === 'resume') blocked = false;
		else return;
		for (var i = 0; i < window.frames.length; i++) {
			try { window.frames[i].postMessage({ __jarvisTab: c }, '*'); } catch (x) { }
		}
	}
	window.__jarvisTabCmd = cmd;
	window.addEventListener('message', function (e) {
		var d = e.data;
		if (d && d.__jarvisTab) cmd(d.__jarvisTab);
	});

	function resume(m) {
		var t = window.__jarvisResumeAt;
		if (resumed || !(t > 0) || !real(m)) return;
		var d = dur(m);
		if (d < 60 || d < t + 5) return;
		resumed = true;
		try { if (Math.abs((m.currentTime || 0) - t) > 10) m.currentTime = t; } catch (x) { }
		if (m.paused && !blocked) { try { var p = m.play(); if (p && p.catch) p.catch(function () { }); } catch (x) { } }
	}

	document.addEventListener('loadedmetadata', function (e) { if (isMedia(e.target)) resume(e.target); }, true);
	document.addEventListener('play', function (e) {
		var m = e.target;
		if (!isMedia(m)) return;
		if (blocked) { try { m.pause(); } catch (x) { } return; }
		resume(m);
	}, true);
	document.addEventListener('playing', function (e) {
		var m = e.target;
		if (!isMedia(m) || blocked || !real(m)) return;
		lastSent = Date.now();
		send(m, true);
	}, true);
	document.addEventListener('timeupdate', function (e) {
		var m = e.target;
		if (!isMedia(m) || blocked || m.paused || !real(m)) return;
		var now = Date.now();
		if (now - lastSent < 5000) return;
		lastSent = now;
		send(m, true);
	}, true);
	document.addEventListener('pause', function (e) {
		var m = e.target;
		if (!isMedia(m) || blocked || !real(m)) return;
		send(m, false);
	}, true);
})();
