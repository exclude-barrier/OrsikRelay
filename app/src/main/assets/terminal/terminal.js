// Bridge between xterm.js and the app (androidx.webkit WebMessageListener
// "OrsikTerminal", only for this page's origin). Messages are strings:
//   page -> app: "r:" ready, "s:<cols>,<rows>" size, "d:<text>" input,
//                "b:<base64>" binary input, "c:1"/"c:0" Ctrl latched or not
//   app -> page: "o:<base64>" output, "t:<json>" theme and font size,
//                "f:" focus, "k:<key>" a key of the extra key row
(function () {
  "use strict";
  var port = window.OrsikTerminal;
  if (!port) return;
  var term = new Terminal({
    cursorBlink: true,
    scrollback: 5000,
    fontFamily: "monospace",
    fontSize: 14,
    allowProposedApi: false
  });
  var fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(document.getElementById("terminal"));

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

  // The extra key row's Ctrl applies to the next single typed character.
  var ctrl = false;
  function setCtrl(on) {
    ctrl = on;
    port.postMessage(on ? "c:1" : "c:0");
  }
  function controlled(data) {
    if (data.length !== 1) return data;
    var code = data.toUpperCase().charCodeAt(0);
    return code >= 64 && code <= 95 ? String.fromCharCode(code & 31) : data;
  }
  var KEYS = { esc: "\x1b", tab: "\t", pipe: "|", dash: "-", slash: "/", tilde: "~" };
  var ARROWS = { up: "A", down: "B", right: "C", left: "D" };
  function pressKey(name) {
    if (name === "ctrl") { setCtrl(!ctrl); return; }
    var data = KEYS[name];
    if (data === undefined && ARROWS[name] !== undefined) {
      // Full-screen programs (vim, less) switch the cursor keys to the
      // application mode; the sequence must follow it.
      data = (term.modes.applicationCursorKeysMode ? "\x1bO" : "\x1b[") + ARROWS[name];
    }
    if (data !== undefined) term.input(data, true);
    term.focus();
  }

  term.onData(function (data) {
    if (ctrl) {
      data = controlled(data);
      setCtrl(false);
    }
    port.postMessage("d:" + data);
  });
  term.onBinary(function (data) { port.postMessage("b:" + btoa(data)); });

  port.onmessage = function (event) {
    var message = String(event.data);
    var kind = message.substring(0, 2);
    var body = message.substring(2);
    if (kind === "o:") {
      term.write(decode(body));
    } else if (kind === "t:") {
      var theme = JSON.parse(body);
      document.body.style.setProperty("--bg", theme.background);
      term.options.theme = {
        background: theme.background,
        foreground: theme.foreground,
        cursor: theme.cursor,
        selectionBackground: theme.selection
      };
      term.options.fontSize = theme.fontSize;
      lastSize = "";
      reportSize();
    } else if (kind === "f:") {
      term.focus();
    } else if (kind === "k:") {
      pressKey(body);
    }
  };

  window.addEventListener("resize", reportSize);
  reportSize();
  port.postMessage("r:");
})();
