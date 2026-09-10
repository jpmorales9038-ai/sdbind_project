// SD/OTG Bind Mount UI — script.js
// Usa el puente global "ksu" que expone el Manager de KernelSU/KernelSU-Next
// en las páginas WebUI de los módulos: ksu.exec(cmd, optionsJson, callbackFnName)

var MODDIR = "/data/adb/modules/sdcard_bind_ui";
var WEBCTL = MODDIR + "/webctl.sh";

function hasBridge() {
  return typeof ksu !== "undefined" && typeof ksu.exec === "function";
}

function ksuExec(cmd) {
  return new Promise(function (resolve, reject) {
    if (!hasBridge()) {
      reject(new Error("Puente KSU no disponible. Abre esta página desde el Manager."));
      return;
    }
    var cb = "__ksu_cb_" + Date.now() + "_" + Math.floor(Math.random() * 1e6);
    window[cb] = function (errno, stdout, stderr) {
      delete window[cb];
      resolve({ errno: Number(errno), stdout: stdout || "", stderr: stderr || "" });
    };
    try {
      ksu.exec(cmd, "{}", cb);
    } catch (e) {
      delete window[cb];
      reject(e);
    }
  });
}

function b64encode(str) {
  return btoa(unescape(encodeURIComponent(str)));
}

function sh(cmd) {
  // Escapa comillas simples para embeber cmd dentro de sh -c '...'
  var escaped = cmd.replace(/'/g, "'\\''");
  return ksuExec("sh -c '" + escaped + "'");
}

function toast(msg, ms) {
  var el = document.getElementById("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(toast._t);
  toast._t = setTimeout(function () {
    el.classList.remove("show");
  }, ms || 2500);
}

function setBridgeBadge() {
  var el = document.getElementById("bridgeStatus");
  if (hasBridge()) {
    el.textContent = "conectado";
    el.className = "badge ok";
  } else {
    el.textContent = "sin acceso root (abre desde el Manager)";
    el.className = "badge bad";
  }
}

/* ---------- Filas de configuración ---------- */

var rowsEl = document.getElementById("rows");

function addRow(src, dest, enabled, status) {
  var row = document.createElement("div");
  row.className = "row";
  row.innerHTML =
    '<div class="row-line">' +
      '<input type="text" class="src" placeholder="Origen (ej. /mnt/media_rw/1234-5678/Musica)" value="' + (src ? escapeHtml(src) : "") + '">' +
      '<button class="iconbtn" title="Eliminar">✕</button>' +
    "</div>" +
    '<div class="row-line">' +
      '<input type="text" class="dest" placeholder="Destino (ej. /storage/emulated/0/Musica)" value="' + (dest ? escapeHtml(dest) : "") + '">' +
    "</div>" +
    '<div class="row-line">' +
      '<label><input type="checkbox" class="enabled" ' + (enabled === false ? "" : "checked") + "> Habilitado</label>" +
      '<span class="status-tag ' + (status || "") + '">' + statusLabel(status) + "</span>" +
    "</div>";

  row.querySelector(".iconbtn").addEventListener("click", function () {
    row.remove();
  });

  rowsEl.appendChild(row);
  return row;
}

function statusLabel(status) {
  switch (status) {
    case "MOUNTED": return "montado";
    case "UNMOUNTED": return "no montado";
    case "SOURCE_MISSING": return "origen no encontrado";
    default: return "sin comprobar";
  }
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, function (c) {
    return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
  });
}

document.getElementById("addRowBtn").addEventListener("click", function () {
  addRow("", "", true, "");
});

/* ---------- Cargar estado desde mounts.conf ---------- */

function loadStatus() {
  return sh(WEBCTL + " status").then(function (res) {
    rowsEl.innerHTML = "";
    var lines = res.stdout.split("\n").map(function (l) { return l.trim(); }).filter(Boolean);
    if (lines.length === 0) {
      addRow("", "", true, "");
      return;
    }
    lines.forEach(function (line) {
      var parts = line.split("|");
      var src = parts[0] || "";
      var dest = parts[1] || "";
      var enabled = parts[2] === "1";
      var status = parts[3] || "";
      addRow(src, dest, enabled, status);
    });
  }).catch(function (err) {
    toast(err.message);
  });
}

/* ---------- Guardar y aplicar ---------- */

function collectConfigText() {
  var lines = ["# Formato: ORIGEN|DESTINO|HABILITADO(1/0)"];
  Array.prototype.forEach.call(rowsEl.querySelectorAll(".row"), function (row) {
    var src = row.querySelector(".src").value.trim();
    var dest = row.querySelector(".dest").value.trim();
    var enabled = row.querySelector(".enabled").checked ? "1" : "0";
    if (src && dest) {
      lines.push(src + "|" + dest + "|" + enabled);
    }
  });
  return lines.join("\n") + "\n";
}

document.getElementById("saveApplyBtn").addEventListener("click", function () {
  var text = collectConfigText();
  var b64 = b64encode(text);
  toast("Guardando y montando…");
  sh("echo '" + b64 + "' | base64 -d > " + MODDIR + "/mounts.conf && sh " + WEBCTL + " apply")
    .then(function (res) {
      if (res.errno === 0) {
        toast("Guardado. Comprobando estado…");
      } else {
        toast("Hubo un problema al aplicar. Revisa el registro.");
      }
      return loadStatus();
    })
    .then(refreshLog)
    .catch(function (err) { toast(err.message); });
});

document.getElementById("unmountBtn").addEventListener("click", function () {
  toast("Desmontando…");
  sh(WEBCTL + " unmount")
    .then(loadStatus)
    .then(refreshLog)
    .then(function () { toast("Listo."); })
    .catch(function (err) { toast(err.message); });
});

document.getElementById("refreshStatusBtn").addEventListener("click", function () {
  loadStatus().then(refreshLog);
});

/* ---------- Detección de SD / OTG ---------- */

document.getElementById("detectBtn").addEventListener("click", function () {
  var box = document.getElementById("detected");
  box.innerHTML = "Buscando…";
  sh(WEBCTL + " detect").then(function (res) {
    var paths = res.stdout.split("\n").map(function (l) { return l.trim(); }).filter(Boolean);
    box.innerHTML = "";
    if (paths.length === 0) {
      box.innerHTML = '<div class="hint">No se detectaron unidades externas montadas.</div>';
      return;
    }
    paths.forEach(function (p) {
      var item = document.createElement("div");
      item.className = "detected-item";
      item.innerHTML = "<span>" + escapeHtml(p) + "</span>";
      var btn = document.createElement("button");
      btn.textContent = "Usar";
      btn.addEventListener("click", function () {
        var base = p.split("/").pop();
        addRow(p, "/storage/emulated/0/" + base, true, "");
        toast("Carpeta añadida abajo. Ajusta el destino si quieres.");
      });
      item.appendChild(btn);
      box.appendChild(item);
    });
  }).catch(function (err) {
    box.innerHTML = "";
    toast(err.message);
  });
});

/* ---------- Registro ---------- */

function refreshLog() {
  return sh(WEBCTL + " log").then(function (res) {
    document.getElementById("log").textContent = res.stdout || "(sin registros aún)";
  });
}

/* ---------- Init ---------- */

setBridgeBadge();
if (hasBridge()) {
  loadStatus().then(refreshLog);
} else {
  addRow("", "", true, "");
}
