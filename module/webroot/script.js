var MODDIR = "/data/adb/modules/sdcard_bind_ui";
var WEBCTL = MODDIR + "/webctl.sh";

function hasBridge() {
  try {
    return typeof ksu !== "undefined" && typeof ksu.exec === "function";
  } catch (e) {
    return false;
  }
}

function ksuExec(cmd) {
  return new Promise(function (resolve, reject) {
    if (!hasBridge()) {
      reject(new Error("Puente KSU no disponible"));
      return;
    }
    var cb = "__ksu_cb_" + Date.now() + "_" + Math.floor(Math.random() * 1e6);
    window[cb] = function (errno, stdout, stderr) {
      delete window[cb];
      resolve({
        errno: Number(errno),
        stdout: stdout == null ? "" : String(stdout),
        stderr: stderr == null ? "" : String(stderr)
      });
    };
    try {
      ksu.exec(cmd, "{}", cb);
      return;
    } catch (e1) {}
    try {
      ksu.exec(cmd, cb);
      return;
    } catch (e2) {}
    try {
      var out = ksu.exec(cmd);
      delete window[cb];
      resolve({ errno: 0, stdout: out == null ? "" : String(out), stderr: "" });
    } catch (e3) {
      delete window[cb];
      reject(e3);
    }
  });
}

function sh(cmd) {
  return ksuExec("sh -c '" + String(cmd).replace(/'/g, "'\\''") + "'");
}

function toast(msg, ms) {
  var el = document.getElementById("toast");
  if (!el) return;
  el.textContent = msg;
  el.className = "toast show";
  clearTimeout(toast._t);
  toast._t = setTimeout(function () { el.className = "toast"; }, ms || 2500);
}

function setBadge(text, cls) {
  var el = document.getElementById("bridgeStatus");
  if (!el) return;
  el.textContent = text;
  el.className = "badge" + (cls ? " " + cls : "");
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "\x26amp;")
    .replace(/</g, "\x26lt;")
    .replace(/>/g, "\x26gt;")
    .replace(/"/g, "\x26quot;")
    .replace(/'/g, "\x26#39;");
}

function slash(p) {
  if (!p) return p;
  return p.charAt(p.length - 1) === "/" ? p : p + "/";
}

function baseName(p) {
  var t = String(p || "").replace(/\/+$/, "");
  var i = t.lastIndexOf("/");
  return i >= 0 ? t.slice(i + 1) : t;
}

function isUnsafeDest(path) {
  var p = String(path || "").replace(/\/+$/, "");
  var bad = ["", "/", "/storage", "/storage/emulated", "/storage/emulated/0",
    "/sdcard", "/data", "/data/media", "/data/media/0", "/mnt", "/mnt/media_rw"];
  return bad.indexOf(p) >= 0;
}

function statusLabel(status) {
  if (status === "MOUNTED") return "montado";
  if (status === "UNMOUNTED") return "no montado";
  if (status === "SOURCE_MISSING") return "origen ausente";
  return "sin comprobar";
}

var rowsEl = document.getElementById("rows");
var emptyHint = document.getElementById("emptyHint");

function syncEmpty() {
  if (!emptyHint || !rowsEl) return;
  if (rowsEl.children.length > 0) emptyHint.classList.add("hidden");
  else emptyHint.classList.remove("hidden");
}

function addRow(src, dest, enabled, status) {
  src = slash(src || "");
  dest = slash(dest || "");
  var name = baseName(dest) || baseName(src) || "vínculo";
  var row = document.createElement("div");
  row.className = "row";
  row.setAttribute("data-src", src);
  row.setAttribute("data-dest", dest);
  row.innerHTML =
    '<div class="row-ico">📁</div>' +
    '<div class="row-body">' +
      '<div class="row-title">' + escapeHtml(name) + "</div>" +
      '<div class="row-src">' + escapeHtml(src || "sin origen") + "</div>" +
      '<div class="row-dest">→ ' + escapeHtml(dest || "sin destino") + "</div>" +
      '<span class="status-tag ' + (status || "") + '">' + statusLabel(status) + "</span>" +
    "</div>" +
    '<button type="button" class="iconbtn" aria-label="Eliminar">✕</button>';
  row.querySelector(".iconbtn").onclick = function () {
    askDelete(row, name, dest);
  };
  rowsEl.appendChild(row);
  syncEmpty();
}

function collectConfigText() {
  var lines = ["# Formato: ORIGEN|DESTINO|HABILITADO(1/0)"];
  var list = rowsEl.querySelectorAll(".row");
  for (var i = 0; i < list.length; i++) {
    var src = list[i].getAttribute("data-src") || "";
    var dest = list[i].getAttribute("data-dest") || "";
    if (src && dest && !isUnsafeDest(dest)) lines.push(slash(src) + "|" + slash(dest) + "|1");
  }
  return lines.join("\n") + "\n";
}

function persistConf() {
  var b64 = btoa(unescape(encodeURIComponent(collectConfigText())));
  return sh("echo '" + b64 + "' | base64 -d > " + MODDIR + "/mounts.conf");
}

function askDelete(row, name, dest) {
  var overlay = document.getElementById("confirm");
  var text = document.getElementById("confirmText");
  if (!overlay || !text) return;
  text.textContent = "Se borra «" + name + "» de forma permanente y se desmonta si está montado.";
  overlay.classList.remove("hidden");
  function close() {
    overlay.classList.add("hidden");
  }
  document.getElementById("confirmNo").onclick = close;
  document.getElementById("confirmYes").onclick = function () {
    close();
    if (row.parentNode) row.parentNode.removeChild(row);
    syncEmpty();
    toast("Eliminando...");
    var destBare = String(dest || "").replace(/\/+$/, "");
    persistConf()
      .then(function () {
        if (!destBare) return;
        return sh("nsenter -t 1 -m -- umount -l " + JSON.stringify(destBare) + " || true");
      })
      .then(refreshAll)
      .then(function () { toast("Vínculo eliminado"); })
      .catch(function (err) { toast(err.message || String(err)); });
  };
  overlay.onclick = function (e) { if (e.target === overlay) close(); };
}

function loadStatus() {
  return sh(WEBCTL + " status").then(function (res) {
    rowsEl.innerHTML = "";
    var lines = String(res.stdout).split("\n");
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].trim();
      if (!line) continue;
      var p = line.split("|");
      addRow(p[0] || "", p[1] || "", p[2] === "1", p[3] || "");
    }
    syncEmpty();
  });
}

function refreshLog() {
  return sh(WEBCTL + " log").then(function (res) {
    var el = document.getElementById("log");
    if (el) el.textContent = res.stdout || "(sin registros aún)";
  });
}

var lastVolKey = "";

function volKeyFromStdout(s) {
  return String(s).split("\n").map(function (l) {
    var p = l.trim().split("|");
    return p.length >= 2 ? p[0] + ":" + p[1] : "";
  }).filter(Boolean).join("|");
}

function makeCell(secondary) {
  var cell = document.createElement("div");
  cell.className = "cell";
  var cls = secondary ? "ring secondary" : "ring";
  cell.innerHTML =
    '<svg class="' + cls + '" viewBox="0 0 100 100" aria-hidden="true">' +
      '<circle class="track" cx="50" cy="50" r="38" pathLength="100" />' +
      '<circle class="arc" cx="50" cy="50" r="38" pathLength="100" />' +
      '<text x="50" y="55" text-anchor="middle">-</text>' +
    "</svg>" +
    '<div class="cell-label"></div>' +
    '<div class="cell-free"></div>' +
    '<div class="cell-used"></div>';
  return cell;
}

function setRing(cell, pct, free, used, total, label) {
  if (!cell) return;
  var arc = cell.querySelector(".arc");
  var text = cell.querySelector("text");
  var p = Math.max(0, Math.min(100, pct || 0));
  if (arc) {
    arc.style.transition = "none";
    arc.style.strokeDasharray = "0 100";
    void arc.getBoundingClientRect();
    requestAnimationFrame(function () {
      arc.style.transition = "stroke-dasharray .7s ease";
      arc.style.strokeDasharray = p + " 100";
    });
  }
  if (text) text.textContent = p + "%";
  var lab = cell.querySelector(".cell-label");
  var fr = cell.querySelector(".cell-free");
  var us = cell.querySelector(".cell-used");
  if (lab) lab.textContent = label;
  if (fr) fr.textContent = free;
  if (us) us.textContent = used ? used + " / " + total : "";
}

function parseKindLines(text, internals, externals, seen) {
  String(text || "").split("\n").forEach(function (line) {
    var p = line.trim().split("|");
    if (p.length < 6) return;
    if (p[0] !== "INTERNAL" && p[0] !== "EXTERNAL") return;
    var key = p[0] + ":" + baseName(p[1]);
    if (seen[key]) return;
    seen[key] = 1;
    var vol = { kind: p[0], path: p[1], total: p[2], used: p[3], avail: p[4], pct: parseInt(p[5], 10) || 0 };
    if (vol.kind === "INTERNAL") internals.push(vol);
    else externals.push(vol);
  });
}

function parseDfText(text, internals, externals, seen) {
  String(text || "").split("\n").forEach(function (line) {
    var cols = line.trim().split(/\s+/);
    if (cols.length < 6) return;
    var mp = cols[cols.length - 1];
    var kind = null;
    if (mp === "/data" || mp === "/data/media" || mp.indexOf("/storage/emulated") === 0) kind = "INTERNAL";
    else if (/^\/mnt\/media_rw\/[^/]+$/.test(mp) || /^\/mnt\/expand\/[^/]+$/.test(mp)) kind = "EXTERNAL";
    else if (/^\/mnt\/runtime\/default\/[^/]+$/.test(mp) && mp.indexOf("emulated") < 0) kind = "EXTERNAL";
    else if (mp.indexOf("/storage/") === 0 && mp.indexOf("emulated") < 0 && !/\/self$/.test(mp)) kind = "EXTERNAL";
    if (!kind) return;
    var key = kind + ":" + baseName(mp);
    if (seen[key]) return;
    seen[key] = 1;
    var vol = {
      kind: kind,
      path: mp,
      total: cols[cols.length - 5],
      used: cols[cols.length - 4],
      avail: cols[cols.length - 3],
      pct: parseInt(String(cols[cols.length - 2]).replace("%", ""), 10) || 0
    };
    if (kind === "INTERNAL") internals.push(vol);
    else externals.push(vol);
  });
}

function loadStorage() {
  return Promise.all([
    sh(WEBCTL + " storage").catch(function () { return { stdout: "" }; }),
    sh("cat " + MODDIR + "/storage.cache 2>/dev/null").catch(function () { return { stdout: "" }; }),
    sh("nsenter -t 1 -m -- df -Ph 2>/dev/null || nsenter --mount=/proc/1/ns/mnt -- df -Ph 2>/dev/null || df -Ph 2>/dev/null").catch(function () { return { stdout: "" }; })
  ]).then(function (rs) {
    var internals = [];
    var externals = [];
    var seen = {};
    parseKindLines(rs[0] && rs[0].stdout, internals, externals, seen);
    parseKindLines(rs[1] && rs[1].stdout, internals, externals, seen);
    parseDfText(rs[2] && rs[2].stdout, internals, externals, seen);
    paintStorageLists(internals, externals);
  });
}

function paintStorageLists(internals, externals) {
    var box = document.getElementById("rings");
    if (!box) return;
    box.innerHTML = "";
    function add(vol, secondary, label) {
      var cell = makeCell(secondary);
      box.appendChild(cell);
      setRing(cell, vol.pct, vol.avail + " libres", vol.used, vol.total, label);
    }
    for (var a = 0; a < internals.length; a++) add(internals[a], false, "Interno");
    for (var b = 0; b < externals.length; b++) {
      var id = baseName(externals[b].path);
      add(externals[b], true, id ? "SD " + id : "SD / OTG");
    }
    lastVolKey = internals.concat(externals).map(function (v) { return v.kind + ":" + baseName(v.path); }).join("|");
}

function refreshAll() {
  return loadStatus().then(refreshLog).then(loadStorage).catch(function (err) {
    toast(err.message || String(err));
  });
}

document.getElementById("saveApplyBtn").onclick = function () {
  var b64 = btoa(unescape(encodeURIComponent(collectConfigText())));
  toast("Guardando y montando...");
  sh("echo '" + b64 + "' | base64 -d > " + MODDIR + "/mounts.conf && sh " + WEBCTL + " apply")
    .then(function (res) {
      toast(res.errno === 0 ? "Listo" : "Hubo un problema, mira el registro");
      return refreshAll();
    })
    .catch(function (err) { toast(err.message || String(err)); });
};

document.getElementById("unmountBtn").onclick = function () {
  toast("Desmontando...");
  sh(WEBCTL + " unmount").then(refreshAll).then(function () { toast("Listo."); });
};

document.getElementById("bottomNav").onclick = function (e) {
  var t = e.target;
  while (t && t !== this && !(t.className && String(t.className).indexOf("navchip") >= 0)) t = t.parentNode;
  if (!t || t === this) return;
  var tab = t.getAttribute("data-tab");
  var chips = document.querySelectorAll(".navchip");
  for (var i = 0; i < chips.length; i++) {
    chips[i].className = chips[i] === t ? "navchip on" : "navchip";
  }
  document.getElementById("homePane").className = tab === "home" ? "pane-binds" : "pane-binds hidden";
  document.querySelector(".pane-storage").className = tab === "home" ? "pane-storage" : "pane-storage hidden";
  document.getElementById("logPane").className = tab === "log" ? "pane-log" : "pane-log hidden";
  document.getElementById("fab").className = tab === "home" ? "fab" : "fab hidden";
  if (tab === "log") refreshLog();
};

var picker = {
  el: document.getElementById("picker"),
  title: document.getElementById("pickerTitle"),
  hint: document.getElementById("pickerHint"),
  path: document.getElementById("pickerPath"),
  list: document.getElementById("pickerList"),
  warn: document.getElementById("pickerWarn"),
  ok: document.getElementById("pickerOk"),
  step: "src",
  current: "/mnt/media_rw",
  source: ""
};

function openPicker(step) {
  picker.step = step;
  if (step === "src") {
    picker.title.textContent = "Origen";
    picker.hint.textContent = "Carpeta de la tarjeta o unidad que queres montar";
    picker.current = "/mnt/media_rw";
    picker.ok.textContent = "Siguiente";
  } else {
    picker.title.textContent = "Destino";
    picker.hint.textContent = "Carpeta del almacenamiento interno donde se va a ver";
    picker.current = "/storage/emulated/0";
    picker.ok.textContent = "Vincular y montar";
  }
  picker.el.className = "picker";
  loadPicker();
}

function closePicker() { picker.el.className = "picker hidden"; }

function loadPicker() {
  picker.path.textContent = picker.current;
  var blocked = picker.step === "dst" && isUnsafeDest(picker.current);
  picker.warn.className = blocked ? "warn" : "warn hidden";
  picker.ok.disabled = blocked;
  picker.list.textContent = "Cargando...";
  ksuExec(WEBCTL + " list_children " + "'" + picker.current.replace(/'/g, "'\\''") + "'").then(function (res) {
    var dirs = String(res.stdout).split("\n");
    picker.list.innerHTML = "";
    var any = false;
    for (var i = 0; i < dirs.length; i++) {
      var d = dirs[i].trim();
      if (!d) continue;
      any = true;
      (function (path) {
        var item = document.createElement("div");
        item.className = "dir";
        item.innerHTML = '<div class="dir-ico">#</div><div>' + escapeHtml(baseName(path)) + "</div>";
        item.onclick = function () { picker.current = path; loadPicker(); };
        picker.list.appendChild(item);
      })(d);
    }
    if (!any) picker.list.innerHTML = '<p class="empty">Sin subcarpetas. Podes usar esta.</p>';
  }).catch(function (err) {
    picker.list.textContent = err.message || String(err);
  });
}

document.getElementById("fab").onclick = function () { openPicker("src"); };
document.getElementById("pickerBack").onclick = function () {
  if (picker.step === "dst") openPicker("src");
  else closePicker();
};
document.getElementById("pickerUp").onclick = function () {
  var t = picker.current.replace(/\/+$/, "");
  var i = t.lastIndexOf("/");
  picker.current = i > 0 ? t.slice(0, i) : "/";
  loadPicker();
};
var chipBtns = document.querySelectorAll(".chips button");
for (var c = 0; c < chipBtns.length; c++) {
  chipBtns[c].onclick = function () {
    picker.current = this.getAttribute("data-jump");
    loadPicker();
  };
}
document.getElementById("pickerOk").onclick = function () {
  if (picker.step === "src") {
    picker.source = slash(picker.current);
    openPicker("dst");
    return;
  }
  var dest = slash(picker.current);
  if (isUnsafeDest(dest)) { toast("Elegi una subcarpeta, no la raiz"); return; }
  addRow(picker.source, dest, true, "");
  closePicker();
  var b64 = btoa(unescape(encodeURIComponent(collectConfigText())));
  toast("Montando...");
  sh("echo '" + b64 + "' | base64 -d > " + MODDIR + "/mounts.conf && sh " + WEBCTL + " apply")
    .then(refreshAll)
    .then(function () { toast("Montado en " + dest); })
    .catch(function (err) { toast(err.message || String(err)); });
};

function boot(found) {
  if (found) {
    setBadge("conectado", "ok");
    loadThemeAndFont();
    refreshAll();
    startVolumeWatch();
  } else {
    setBadge("sin acceso", "bad");
    syncEmpty();
  }
}

function hexToRgb(hex) {
  hex = String(hex || "").replace("#", "");
  if (hex.length === 8) hex = hex.slice(2);
  if (hex.length === 3) hex = hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2];
  var n = parseInt(hex, 16);
  if (isNaN(n)) return null;
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}

function rgbToHsl(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  var max = Math.max(r, g, b), min = Math.min(r, g, b);
  var h = 0, s = 0, l = (max + min) / 2;
  if (max !== min) {
    var d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h *= 60;
  }
  return { h: h, s: s * 100, l: l * 100 };
}

function hslCss(h, s, l) {
  return "hsl(" + Math.round(h) + ", " + Math.round(s) + "%, " + Math.round(l) + "%)";
}

function applySeed(seed) {
  var rgb = hexToRgb(seed);
  if (!rgb) return;
  var hsl = rgbToHsl(rgb.r, rgb.g, rgb.b);
  var h = hsl.h, s = Math.min(42, Math.max(18, hsl.s));
  var dark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
  var r = document.documentElement.style;
  function set(k, v) { r.setProperty(k, v); }
  if (dark) {
    set("--bg", hslCss(h, 18, 8));
    set("--text", hslCss(h, 12, 94));
    set("--muted", hslCss(h, 10, 70));
    set("--primary", hslCss(h, s, 80));
    set("--on-primary", hslCss(h, 28, 16));
    set("--surface", hslCss(h, 16, 14));
    set("--surface-2", hslCss(h, 14, 20));
    set("--secondary", hslCss(h, s * 0.75, 72));
    set("--danger", hslCss(8, 55, 72));
    set("--ok", hslCss(145, 28, 70));
    set("--warn", hslCss(42, 48, 70));
  } else {
    set("--bg", hslCss(h, 16, 96));
    set("--text", hslCss(h, 18, 12));
    set("--muted", hslCss(h, 10, 38));
    set("--primary", hslCss(h, s, 38));
    set("--on-primary", hslCss(h, 20, 97));
    set("--surface", hslCss(h, 18, 92));
    set("--surface-2", hslCss(h, 14, 86));
    set("--secondary", hslCss(h, s * 0.8, 42));
    set("--danger", hslCss(8, 62, 42));
    set("--ok", hslCss(145, 35, 32));
    set("--warn", hslCss(42, 55, 38));
  }
}

function injectCss(text) {
  var old = document.getElementById("monet");
  if (old) old.parentNode.removeChild(old);
  var s = document.createElement("style");
  s.id = "monet";
  s.textContent = text;
  document.head.appendChild(s);
}

function injectFontFace(url) {
  var old = document.getElementById("gsr-font");
  if (old) old.parentNode.removeChild(old);
  var s = document.createElement("style");
  s.id = "gsr-font";
  s.textContent = '@font-face{font-family:"Google Sans Rounded";src:url(' + url + ') format("truetype");font-weight:1 1000;font-display:swap;}';
  document.head.appendChild(s);
  document.body.style.fontFamily = '"Google Sans Rounded", sans-serif';
}

function b64ToFontUrl(b64) {
  var raw = atob(String(b64).replace(/\s+/g, ""));
  var arr = new Uint8Array(raw.length);
  for (var i = 0; i < raw.length; i++) arr[i] = raw.charCodeAt(i);
  var blob = new Blob([arr], { type: "font/ttf" });
  return URL.createObjectURL(blob);
}

function loadThemeAndFont() {
  return sh(WEBCTL + " theme").then(function (res) {
    var seed = "";
    var hasCss = false;
    var font = "";
    String(res.stdout).split("\n").forEach(function (line) {
      var p = line.trim().split("|");
      if (p[0] === "SEED") seed = p[1] || "";
      if (p[0] === "CSS") hasCss = p[1] === "1";
      if (p[0] === "FONT" && !font) font = p.slice(1).join("|");
    });
    var jobs = [];
    if (hasCss) {
      jobs.push(sh("cat " + MODDIR + "/webroot/theme.css").then(function (c) {
        if (c.stdout && c.stdout.indexOf("--primary") >= 0) injectCss(c.stdout);
        else if (seed) applySeed(seed);
      }));
    } else if (seed) {
      applySeed(seed);
    }
    if (font) {
      jobs.push(sh("base64 " + font).then(function (b) {
        if (b.stdout && b.stdout.length > 100) injectFontFace(b64ToFontUrl(b.stdout));
      }));
    }
    return Promise.all(jobs);
  }).catch(function () {});
}

function startVolumeWatch() {
  if (startVolumeWatch._id) return;
  startVolumeWatch._id = setInterval(function () {
    if (!hasBridge()) return;
    sh(WEBCTL + " storage").then(function (res) {
      var key = volKeyFromStdout(res.stdout);
      if (lastVolKey && key !== lastVolKey) {
        loadStorage();
      } else if (!lastVolKey) {
        lastVolKey = key;
      }
    }).catch(function () {});
  }, 2000);
}

(function bindStoragePress() {
  var card = document.getElementById("storageCard");
  if (!card) return;
  var timer = 0;
  function start() {
    clearTimeout(timer);
    timer = setTimeout(function () {
      toast("Actualizando almacenamiento...");
      loadStorage().catch(function (err) { toast(err.message || String(err)); });
    }, 500);
  }
  function cancel() { clearTimeout(timer); }
  card.addEventListener("touchstart", start, { passive: true });
  card.addEventListener("touchend", cancel);
  card.addEventListener("touchmove", cancel);
  card.addEventListener("mousedown", start);
  card.addEventListener("mouseup", cancel);
  card.addEventListener("mouseleave", cancel);
  card.addEventListener("contextmenu", function (e) {
    e.preventDefault();
    toast("Actualizando almacenamiento...");
    loadStorage();
  });
})();

(function waitBridge() {
  var n = 0;
  function tick() {
    if (hasBridge()) { boot(true); return; }
    n += 1;
    if (n > 40) { boot(false); return; }
    setTimeout(tick, 50);
  }
  tick();
})();
