// Раздача сборок JARVIS Auto для кнопки «Обновить приложение» (16.11.2026): жены (jarvis-zhena) и пользователя
// (jarvis-auto). Адрес: /<секрет>/<имя>.json и /<секрет>/<имя>-N.apk; всё остальное — 404.
// APK больше предела значения KV (25 МиБ) — лежит частями <имя>-N.apk.0, .1, …; число частей — в описании
// выпуска (поле chastej), воркер склеивает их потоком.
export default {
  async fetch(request, env) {
    if (request.method !== "GET" && request.method !== "HEAD") return new Response("", { status: 405 });
    const parts = new URL(request.url).pathname.split("/").filter(Boolean);
    const m0 = (parts.length === 2) ? /^(jarvis-(?:zhena|auto))(-\d+\.apk|\.json)$/.exec(parts[1]) : null;
    if (!m0 || parts[0] !== env.SEKRET) return new Response("", { status: 404 });
    const imya = m0[1], opis = imya + ".json";
    const nocache = { "Cache-Control": "no-store" };
    if (parts[1] === opis) {
      const j = await env.FAJLY.get(opis);
      if (!j) return new Response("", { status: 404 });
      return new Response(j, { headers: { "Content-Type": "application/json; charset=utf-8", ...nocache } });
    }
    const m = JSON.parse((await env.FAJLY.get(opis)) || "{}");
    if (m.fajl !== parts[1] || !(m.chastej > 0) || !(m.razmer > 0)) return new Response("", { status: 404 });
    const headers = { "Content-Type": "application/vnd.android.package-archive", "Content-Length": String(m.razmer),
      "Content-Disposition": "attachment; filename=" + m.fajl, ...nocache };
    if (request.method === "HEAD") return new Response(null, { headers });
    const { readable, writable } = new FixedLengthStream(m.razmer);
    (async () => {
      const w = writable.getWriter();
      for (let i = 0; i < m.chastej; i++) {
        const b = await env.FAJLY.get(m.fajl + "." + i, { type: "arrayBuffer" });
        if (!b) { await w.abort("part " + i); return; }
        await w.write(new Uint8Array(b));
      }
      await w.close();
    })();
    return new Response(readable, { headers });
  },
};
