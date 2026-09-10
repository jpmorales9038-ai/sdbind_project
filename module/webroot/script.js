var MODDIR = "/data/adb/modules/sdcard_bind_ui";
var WEBCTL = MODDIR + "/webctl.sh";

function hasBridge() {
  return typeof ksu !== "undefined" && typeof ksu.exec === "function";
}

function ksuExec(cmd) {
  return new Promise(function (resolve, reject) {
    if (!hasBridge()) {
      reject(new Error("Puente KSU no disponible. Abrí esta página desde el Manager."));
      return;
    }
    var cb = "__ksu_cb_" + Date.now() + "_" + Math.floor(Math.random() * 1e6);
    window[cb] = function (errno, stdout, stderr) {
      delete window[cb];
      resolve({ errno: Number(errno), stdout: stdout || "", stderr: stderr || "" });
    };
    try { ksu.exec(cmd, "{}", cb); } catch (e) { delete window[cb]; reject(e); }
  });
}

function sh(cmd) {
  var escaped = cmd.replace(/'/g, "'\\''");
  return ksuExec("sh -c '" + escaped + "'");
}

function toast(msg, ms) {
  var el = document.getElementById("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(toast._t);
  toast._t = setTimeout(function () { el.classList.remove("show"); }, ms || 2500);
}

function setBridgeBadge() {
  var el = document.getElementById("bridgeStatus");
  if (hasBridge()) { el.textContent = "conectado"; el.className = "badge ok"; }
  else { el.textContent = "sin acceso"; el.className = "badge bad"; }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, function (c) {
    return { "&": "&", "<": "<", ">": ">", '"': """, "'": "&#39;" }[c];
  });
}

function slash(p) {
  if (!p) return p;
  return p.charAt(p.length - 1) === "/" ? p : p + "/";
}

function baseName(p) {
  var t = (p || "").replace(/\/+$/, "");
  var i = t.lastIndexOf("/");
  return i >= 0 ? t.slice(i + 1) : t;
}

function isUnsafeDest(path) {
  var p = (path || "").replace(/\/+$/, "");
  return [
    "", "/", "/storage", "/storage/emulated", "/storage/emulated/0",
    "/sdcard", "/data", "/data/media", "/data/media/0", "/mnt", "/mnt/media_rw"
  ].indexOf(p) >= 0;
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
  emptyHint.classList.toggle("hidden", rowsEl.children.length > 0);
}

function addRow(src, dest, enabled, status) {
  var row = document.createElement("div");
  row.className = "row";
  row.innerHTML =
    '<input type="text" class="src" placeholder="Origen" value="' + escapeHtml(src || "") + '">' +
    '<input type="text" class="dest" placeholder="Destino" value="' + escapeHtml(dest || "") + '">' +
    '<div class="row-meta">' +
      '<label><input type="checkbox" class="enabled" ' + (enabled === false ? "" : "checked") + "> Habilitado</label>" +
      '<span class="status-tag ' + (status || "") + '">' + statusLabel(status) + "</span>" +
      '<button class="iconbtn" title="Eliminar">✕</button>' +
    "</div>";
  row.querySelector(".iconbtn").addEventListener("click", function () { row.remove(); syncEmpty(); });
  rowsEl.appendChild(row);
  syncEmpty();
  return row;
}

function collectConfigText() {
  var lines = ["# Formato: ORIGEN|DESTINO|HABILITADO(1/0)"];
  Array.prototype.forEach.call(rowsEl.querySelectorAll(".row"), function (row) {
    var src = row.querySelector(".src").value.trim();
    var dest = row.querySelector(".dest").value.trim();
    var enabled = row.querySelector(".enabled").checked ? "1" : "0";
    if (src && dest && !isUnsafeDest(dest)) lines.push(slash(src) + "|" + slash(dest) + "|" + enabled);
  });
  return lines.join("\n") + "\n";
}

function loadStatus() {
  return sh(WEBCTL + " status").then(function (res) {
    rowsEl.innerHTML = "";
    var lines = res.stdout.split("\n").map(function (l) { return l.trim(); }).filter(Boolean);
    lines.forEach(function (line) {
      var p = line.split("|");
      addRow(p[0] || "", p[1] || "", p[2] === "1", p[3] || "");
    });
    syncEmpty();
  }).catch(function (err) { toast(err.message); });
}

function refreshLog() {
  return sh(WEBCTL + " log").then(function (res) {
    document.getElementById("log").textContent = res.stdout || "(sin registros aún)";
  });
}

function setRing(cell, pct, free, used, total, label) {
  var arc = cell.querySelector(".arc");
  var text = cell.querySelector("text");
  var C = 2 * Math.PI * 40;
  var p = Math.max(0, Math.min(100, pct || 0));
  arc.style.strokeDasharray = String(C);
  arc.style.strokeDashoffset = String(C * (1 - p / 100));
  text.textContent = p ? p + "%" : "—";
  cell.querySelector(".cell-label").textContent = label;
  cell.querySelector(".cell-free").textContent = free;
  cell.querySelector(".cell-used").textContent = used ? used + " / " + total : "";
}

function loadStorage() {
  return sh(WEBCTL + " storage").then(function (res) {
    var internal = null, external = null;
    res.stdout.split("\n").forEach(function (line) {
      var p = line.trim().split("|");
      if (p.length < 6) return;
      var vol = { kind: p[0], path: p[1], total: p[2], used: p[3], avail: p[4], pct: parseInt(p[5], 10) || 0 };
      if (vol.kind === "INTERNAL" && !internal) internal = vol;
      if (vol.kind === "EXTERNAL" && !external) external = vol;
    });
    var ci = document.getElementById("cellInternal");
    var ce = document.getElementById("cellExternal");
    if (internal) setRing(ci, internal.pct, internal.avail + " libres", internal.used, internal.total, "Interno");
    if (external) {
      var id = baseName(external.path);
      setRing(ce, external.pct, external.avail + " libres", external.used, external.total, id ? "SD " + id : "SD / OTG");
    }
  });
}

document.getElementById("saveApplyBtn").addEventListener("click", function () {
  var b64 = btoa(unescape(encodeURIComponent(collectConfigText())));
  toast("Guardando y montando…");
  sh("echo '" + b64 + "' | base64 -d > " + MODDIR + "/mounts.conf && sh " + WEBCTL + " apply")
    .then(function (res) {
      toast(res.errno === 0 ? "Listo" : "Hubo un problema, mirá el registro");
      return loadStatus();
    })
    .then(refreshLog)
    .then(loadStorage)
    .catch(function (err) { toast(err.message); });
});

document.getElementById("unmountBtn").addEventListener("click", function () {
  toast("Desmontando…");
  sh(WEBCTL + " unmount").then(loadStatus).then(refreshLog).then(function () { toast("Listo."); });
});

document.getElementById("bottomNav").addEventListener("click", function (e) {
  var btn = e.target.closest(".navchip");
  if (!btn) return;
  var tab = btn.getAttribute("data-tab");
  Array.prototype.forEach.call(document.querySelectorAll(".navchip"), function (b) {
    b.classList.toggle("on", b === btn);
  });
  document.getElementById("homePane").classList.toggle("hidden", tab !== "home");
  document.querySelector(".pane-storage").classList.toggle("hidden", tab !== "home");
  document.getElementById("logPane").classList.toggle("hidden", tab !== "log");
  document.getElementById("fab").classList.toggle("hidden", tab !== "home");
  if (tab === "log") refreshLog();
});

/* ---------- picker origen → destino ---------- */
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
    picker.hint.textContent = "Carpeta de la tarjeta o unidad que querés montar";
    picker.current = "/mnt/media_rw";
    picker.ok.textContent = "Siguiente";
  } else {
    picker.title.textContent = "Destino";
    picker.hint.textContent = "Carpeta del almacenamiento interno donde se va a ver";
    picker.current = "/storage/emulated/0";
    picker.ok.textContent = "Vincular y montar";
  }
  picker.el.classList.remove("hidden");
  loadPicker();
}

function closePicker() { picker.el.classList.add("hidden"); }

function loadPicker() {
  picker.path.textContent = picker.current;
  var blocked = picker.step === "dst" && isUnsafeDest(picker.current);
  picker.warn.classList.toggle("hidden", !blocked);
  picker.ok.disabled = blocked;
  picker.list.textContent = "Cargando…";
  var quoted = "'" + picker.current.replace(/'/g, "'\\''") + "'";
  ksuExec(WEBCTL + " list_children " + quoted).then(function (res) {
    var dirs = res.stdout.split("\n").map(function (l) { return l.trim(); }).filter(Boolean);
    picker.list.innerHTML = "";
    if (!dirs.length) {
      picker.list.innerHTML = '<p class="empty">Sin subcarpetas. Podés usar esta.</p>';
      return;
    }
    dirs.forEach(function (d) {
      var item = document.createElement("div");
      item.className = "dir";
      item.innerHTML = '<div class="dir-ico">📁</div><div>' + escapeHtml(baseName(d)) + "</div>";
      item.addEventListener("click", function () { picker.current = d; loadPicker(); });
      picker.list.appendChild(item);
    });
  }).catch(function (err) {
    picker.list.textContent = err.message;
  });
}

document.getElementById("fab").addEventListener("click", function () { openPicker("src"); });
document.getElementById("pickerBack").addEventListener("click", function () {
  if (picker.step === "dst") openPicker("src");
  else closePicker();
});
document.getElementById("pickerUp").addEventListener("click", function () {
  var t = picker.current.replace(/\/+$/, "");
  var i = t.lastIndexOf("/");
  picker.current = i > 0 ? t.slice(0, i) : "/";
  loadPicker();
});
document.querySelectorAll(".chips button").forEach(function (b) {
  b.addEventListener("click", function () { picker.current = b.getAttribute("data-jump"); loadPicker(); });
});
document.getElementById("pickerOk").addEventListener("click", function () {
  if (picker.step === "src") {
    picker.source = slash(picker.current);
    openPicker("dst");
    return;
  }
  var dest = slash(picker.current);
  if (isUnsafeDest(dest)) { toast("Elegí una subcarpeta, no la raíz"); return; }
  addRow(picker.source, dest, true, "");
  closePicker();
  var b64 = btoa(unescape(encodeURIComponent(collectConfigText())));
  toast("Montando…");
  sh("echo '" + b64 + "' | base64 -d > " + MODDIR + "/mounts.conf && sh " + WEBCTL + " apply")
    .then(loadStatus).then(refreshLog).then(loadStorage)
    .then(function () { toast("Montado en " + dest); })
    .catch(function (err) { toast(err.message); });
});

setBridgeBadge();
if (hasBridge()) {
  loadStatus().then(refreshLog).then(loadStorage);
} else {
  syncEmpty();
}
