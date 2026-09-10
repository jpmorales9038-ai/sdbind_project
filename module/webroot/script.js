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
  var row = document.createElement("div");
  row.className = "row";
  row.innerHTML =
    '<input type="text" class="src" placeholder="Origen" value="' + escapeHtml(src || "") + '">' +
    '<input type="text" class="dest" placeholder="Destino" value="' + escapeHtml(dest || "") + '">' +
    '<div class="row-meta">' +
      '<label><input type="checkbox" class="enabled"' + (enabled === false ? "" : " checked") + "> Habilitado</label>" +
      '<span class="status-tag ' + (status || "") + '">' + statusLabel(status) + "</span>" +
      '<button type="button" class="iconbtn">x</button>' +
    "</div>";
  row.querySelector(".iconbtn").onclick = function () { row.parentNode.removeChild(row); syncEmpty(); };
  rowsEl.appendChild(row);
  syncEmpty();
}

function collectConfigText() {
  var lines = ["# Formato: ORIGEN|DESTINO|HABILITADO(1/0)"];
  var list = rowsEl.querySelectorAll(".row");
  for (var i = 0; i < list.length; i++) {
    var src = list[i].querySelector(".src").value.trim();
    var dest = list[i].querySelector(".dest").value.trim();
    var enabled = list[i].querySelector(".enabled").checked ? "1" : "0";
    if (src && dest && !isUnsafeDest(dest)) lines.push(slash(src) + "|" + slash(dest) + "|" + enabled);
  }
  return lines.join("\n") + "\n";
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

function setRing(cell, pct, free, used, total, label) {
  if (!cell) return;
  var arc = cell.querySelector(".arc");
  var text = cell.querySelector("text");
  var C = 251.2;
  var p = Math.max(0, Math.min(100, pct || 0));
  if (arc) {
    arc.setAttribute("stroke-dasharray", String(C));
    arc.setAttribute("stroke-dashoffset", String(C * (1 - p / 100)));
  }
  if (text) text.textContent = p ? p + "%" : "-";
  var lab = cell.querySelector(".cell-label");
  var fr = cell.querySelector(".cell-free");
  var us = cell.querySelector(".cell-used");
  if (lab) lab.textContent = label;
  if (fr) fr.textContent = free;
  if (us) us.textContent = used ? used + " / " + total : "";
}

function loadStorage() {
  return sh(WEBCTL + " storage").then(function (res) {
    var internal = null, external = null;
    var lines = String(res.stdout).split("\n");
    for (var i = 0; i < lines.length; i++) {
      var p = lines[i].trim().split("|");
      if (p.length < 6) continue;
      var vol = { kind: p[0], path: p[1], total: p[2], used: p[3], avail: p[4], pct: parseInt(p[5], 10) || 0 };
      if (vol.kind === "INTERNAL" && !internal) internal = vol;
      if (vol.kind === "EXTERNAL" && !external) external = vol;
    }
    if (internal) {
      setRing(document.getElementById("cellInternal"), internal.pct, internal.avail + " libres", internal.used, internal.total, "Interno");
    }
    if (external) {
      var id = baseName(external.path);
      setRing(document.getElementById("cellExternal"), external.pct, external.avail + " libres", external.used, external.total, id ? "SD " + id : "SD / OTG");
    }
  });
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
    refreshAll();
  } else {
    setBadge("sin acceso", "bad");
    syncEmpty();
  }
}

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
