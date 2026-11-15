// Вкладка «Видео»: фильм, открытый кнопкой «Смотреть» / «Продолжить» главной, запускается сам (замечание 15.11.2026:
// «кнопка смотреть не включает фильм» — на странице Zona надо было ещё раз нажать «Смотреть фильм» и «▶»).
// Страница Zona (w140.zona.plus, проверено 15.11.2026): кнопка a.entity-play-btn, проигрыватель video.js с обычным
// <video id="player_html5_api">. Сценарий нажимает кнопку, запускает видео со звуком и растягивает его на всю вкладку
// (во весь экран без касания браузер не пускает); «✕» в углу — вернуться к странице. Ждёт проигрыватель до 60 с.
(function () {
  if (window.__jarvisZonaPlay) return;
  window.__jarvisZonaPlay = 1;
  var log = function (m) { try { console.log('[JARVIS-ZONA] ' + m); } catch (x) { } };
  var start = Date.now(), clicked = false, pinned = null, started = false;
  var PIN = { position: 'fixed', left: '0', top: '0', width: '100vw', height: '100vh', 'max-width': 'none',
    'max-height': 'none', margin: '0', transform: 'none', 'z-index': '2147483646', background: '#000',
    'object-fit': 'contain', visibility: 'visible', opacity: '1', display: 'block' };
  function video() {
    var v = document.getElementById('player_html5_api');
    if (v) return v;
    var best = null, area = 0, vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) {
      var r = vs[i].getBoundingClientRect();
      if (r.width * r.height > area) { area = r.width * r.height; best = vs[i]; }
    }
    return best;
  }
  function playButton() {
    var b = document.querySelector('.entity-play-btn');
    if (b) return b;
    var l = document.querySelectorAll('a,button');
    for (var i = 0; i < l.length; i++) {
      if (/^смотреть\s+(фильм|сериал|онлайн)/i.test((l[i].innerText || '').trim())) return l[i];
    }
    return null;
  }
  function pin(v) {
    if (pinned === v) return;
    pinned = v;
    for (var k in PIN) v.style.setProperty(k, PIN[k], 'important');
    v.controls = true;
    var x = document.createElement('div');
    x.id = 'jarvis-zona-x';
    x.textContent = '✕';
    x.style.cssText = 'position:fixed;right:12px;top:10px;z-index:2147483647;color:#fff;background:rgba(0,0,0,.55);' +
      'font:600 28px sans-serif;width:52px;height:52px;line-height:52px;text-align:center;border-radius:26px';
    x.addEventListener('click', function () {
      for (var k in PIN) v.style.removeProperty(k);
      x.remove();
      log('player back to page');
    });
    document.body.appendChild(x);
    log('player on full tab');
  }
  var t = setInterval(function () {
    if (Date.now() - start > 60000) { clearInterval(t); if (!started) log('player not started in 60 s'); return; }
    var v = video();
    if (!v && !clicked) {
      var b = playButton();
      if (b) { clicked = true; try { b.click(); } catch (x) { } log('watch button pressed'); }
      return;
    }
    if (!v) return;
    if (!clicked) { var b2 = playButton(); if (b2) { clicked = true; try { b2.click(); } catch (x) { } log('watch button pressed'); } }
    if (v.paused && !started) {
      v.muted = false;
      var p = v.play();
      if (p && p.catch) p.catch(function (e) { log('play refused: ' + e); });
    }
    if (!v.paused && v.currentTime > 0) {
      if (!started) { started = true; log('playing'); }
      pin(v);
      clearInterval(t);
    }
  }, 700);
})();
