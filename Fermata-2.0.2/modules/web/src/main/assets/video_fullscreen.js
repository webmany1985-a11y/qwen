// Вкладка «Видео»: видео, запущенное человеком, — сразу во весь экран
// (во всех кадрах страницы, и во встроенных плеерах тоже). Беззвучные
// превью и автозапуск без касания не трогаем.
(function () {
	if (window.__jarvisVideoFs) return;
	window.__jarvisVideoFs = true;
	document.addEventListener('play', function (e) {
		var v = e.target;
		if (!(v instanceof HTMLVideoElement) || v.muted) return;
		if (document.fullscreenElement || document.webkitFullscreenElement) return;
		if (navigator.userActivation && !navigator.userActivation.isActive) return;
		var fs = v.requestFullscreen || v.webkitRequestFullscreen || v.webkitEnterFullscreen;
		if (!fs) return;
		try {
			var r = fs.call(v);
			if (r && r.catch) r.catch(function () { });
		} catch (x) { }
	}, true);
})();
