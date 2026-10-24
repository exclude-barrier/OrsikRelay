// Bridge between xterm.js and the app (androidx.webkit WebMessageListener
// "OrsikTerminal", only for this page's origin). Messages are strings:
//   page -> app: "r:" ready, "s:<cols>,<rows>" size, "d:<text>" input,
//                "b:<base64>" binary input, "m:ctrl|alt|" latched modifier,
//                "z:<px>" font size after a pinch, "S:<text>" selection
//                ("" = none), "L:<text>" the last output (answer to "q:")
//   app -> page: "o:<base64>" output, "t:<json>" theme, "F:<px>" font size,
//                "f:" focus, "B:" blur, "k:<key>" a key of the key rows,
//                "q:" last output, "C:" clear the selection, "D:1|0" dim
(function () {
  "use strict";
  var port = window.OrsikTerminal;
  if (!port) return;
  var FONT = '"Roboto Mono", monospace';
  var term = new Terminal({
    cursorBlink: true,
    scrollback: 5000,
    fontFamily: FONT,
    fontSize: 12,
    allowProposedApi: false
  });
  var fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  var host = document.getElementById("terminal");
  term.open(host);

  var lastSize = "";
  function reportSize() {
    try { fit.fit(); } catch (e) { return; }
    var size = term.cols + "," + term.rows;
    if (size !== lastSize) {
      lastSize = size;
      port.postMessage("s:" + size);
    }
  }

  function decode(base64) {
    var raw = atob(base64);
    var bytes = new Uint8Array(raw.length);
    for (var i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    return bytes;
  }

  // Ctrl and Alt of the key rows latch for the next single key.
  var modifier = "";
  function setModifier(next) {
    modifier = next;
    port.postMessage("m:" + modifier);
  }
  function applyModifier(data) {
    if (modifier === "" || data.length !== 1) return data;
    if (modifier === "alt") return "\x1b" + data;
    var code = data.toUpperCase().charCodeAt(0);
    if (data === " ") return "\x00";
    return code >= 64 && code <= 95 ? String.fromCharCode(code & 31) : data;
  }
  var KEYS = {
    esc: "\x1b", tab: "\t", enter: "\r", pipe: "|", tilde: "~", slash: "/",
    dash: "-", underscore: "_", gt: ">", dollar: "$"
  };
  var ARROWS = { up: "A", down: "B", right: "C", left: "D" };
  function pressKey(name) {
    if (name === "ctrl" || name === "alt") {
      setModifier(modifier === name ? "" : name);
      return;
    }
    var data = KEYS[name];
    if (data === undefined && ARROWS[name] !== undefined) {
      // Full-screen programs (vim, less) switch the cursor keys to the
      // application mode; the sequence must follow it. Alt + arrow is
      // ESC + sequence, Ctrl + arrow the xterm modifier form.
      var final = ARROWS[name];
      if (modifier === "ctrl") {
        data = "\x1b[1;5" + final;
      } else {
        data = (term.modes.applicationCursorKeysMode ? "\x1bO" : "\x1b[") + final;
        if (modifier === "alt") data = "\x1b" + data;
      }
      if (modifier !== "") setModifier("");
      port.postMessage("d:" + data);
      return;
    }
    if (data !== undefined) term.input(data, true);
  }

  term.onData(function (data) {
    if (modifier !== "") {
      data = applyModifier(data);
      setModifier("");
    }
    port.postMessage("d:" + data);
  });
  term.onBinary(function (data) { port.postMessage("b:" + btoa(data)); });

  function lastOutput() {
    var buffer = term.buffer.active;
    var lines = [];
    for (var i = Math.max(0, buffer.length - 400); i < buffer.length; i++) {
      var line = buffer.getLine(i);
      lines.push(line ? line.translateToString(true) : "");
    }
    while (lines.length && lines[lines.length - 1].trim() === "") lines.pop();
    return lines.slice(-60).join("\n");
  }

  function setFontSize(px) {
    var size = Math.max(8, Math.min(28, Math.round(px)));
    if (term.options.fontSize === size) return size;
    term.options.fontSize = size;
    lastSize = "";
    reportSize();
    return size;
  }

  // Touch: a long press starts a selection that follows the finger; two
  // fingers pinch the font size.
  var press = null, pinch = null, selecting = false;
  function cellAt(touch) {
    var screen = host.querySelector(".xterm-screen");
    var rect = screen.getBoundingClientRect();
    var col = Math.floor((touch.clientX - rect.left) / (rect.width / term.cols));
    var row = Math.floor((touch.clientY - rect.top) / (rect.height / term.rows));
    col = Math.max(0, Math.min(term.cols - 1, col));
    row = Math.max(0, Math.min(term.rows - 1, row)) + term.buffer.active.viewportY;
    return { col: col, row: row };
  }
  function selectRange(a, b) {
    var start = a.row < b.row || (a.row === b.row && a.col <= b.col) ? a : b;
    var end = start === a ? b : a;
    var length = (end.row - start.row) * term.cols + (end.col - start.col) + 1;
    term.select(start.col, start.row, length);
  }
  function distance(t) {
    var dx = t[0].clientX - t[1].clientX, dy = t[0].clientY - t[1].clientY;
    return Math.sqrt(dx * dx + dy * dy);
  }
  host.addEventListener("touchstart", function (event) {
    if (event.touches.length === 2) {
      clearTimeout(press && press.timer);
      press = null;
      pinch = { start: distance(event.touches), size: term.options.fontSize };
      return;
    }
    if (event.touches.length !== 1) return;
    var touch = event.touches[0];
    var origin = { x: touch.clientX, y: touch.clientY, cell: cellAt(touch) };
    origin.timer = setTimeout(function () {
      selecting = true;
      selectRange(origin.cell, origin.cell);
    }, 450);
    press = origin;
  }, { passive: true });
  host.addEventListener("touchmove", function (event) {
    if (pinch && event.touches.length === 2) {
      setFontSize(pinch.size * distance(event.touches) / pinch.start);
      event.preventDefault();
      return;
    }
    if (!press) return;
    var touch = event.touches[0];
    if (selecting) {
      selectRange(press.cell, cellAt(touch));
      event.preventDefault();
    } else if (Math.abs(touch.clientX - press.x) > 10 || Math.abs(touch.clientY - press.y) > 10) {
      clearTimeout(press.timer);
      press = null;
    }
  }, { passive: false });
  host.addEventListener("touchend", function () {
    if (pinch) {
      port.postMessage("z:" + term.options.fontSize);
      pinch = null;
    }
    if (press) clearTimeout(press.timer);
    press = null;
    if (selecting) {
      selecting = false;
      port.postMessage("S:" + term.getSelection());
    }
  });
  term.onSelectionChange(function () {
    if (!selecting && !term.hasSelection()) port.postMessage("S:");
  });

  port.onmessage = function (event) {
    var message = String(event.data);
    var kind = message.substring(0, 2);
    var body = message.substring(2);
    if (kind === "o:") {
      term.write(decode(body));
    } else if (kind === "t:") {
      var theme = JSON.parse(body);
      document.body.style.setProperty("--bg", theme.background);
      var colors = {
        background: theme.background,
        foreground: theme.foreground,
        cursor: theme.cursor,
        cursorAccent: theme.background,
        selectionBackground: theme.selection
      };
      var names = ["black", "red", "green", "yellow", "blue", "magenta", "cyan", "white"];
      for (var i = 0; i < 16; i++) {
        colors[(i < 8 ? "" : "bright") + (i < 8 ? names[i] : names[i - 8].charAt(0).toUpperCase() + names[i - 8].slice(1))] = theme.ansi[i];
      }
      term.options.theme = colors;
      setFontSize(theme.fontSize);
      lastSize = "";
      reportSize();
    } else if (kind === "F:") {
      setFontSize(Number(body));
    } else if (kind === "f:") {
      term.focus();
    } else if (kind === "B:") {
      term.blur();
    } else if (kind === "k:") {
      pressKey(body);
    } else if (kind === "q:") {
      port.postMessage("L:" + lastOutput());
    } else if (kind === "C:") {
      term.clearSelection();
    } else if (kind === "D:") {
      document.body.classList.toggle("dimmed", body === "1");
    }
  };

  // The WebView gets its final size only after the first layout (and again
  // when the keyboard opens), so every size change of the area counts.
  if (window.ResizeObserver) {
    new ResizeObserver(reportSize).observe(host);
  } else {
    window.addEventListener("resize", reportSize);
  }
  var ready = function () {
    reportSize();
    port.postMessage("r:");
  };
  // Measure the cells with Roboto Mono, not a fallback font.
  if (document.fonts && document.fonts.load) {
    document.fonts.load('12px "Roboto Mono"').then(ready, ready);
  } else {
    ready();
  }
})();
