(function () {
  if (window.__nativeTts || !window.AndroidTTS) {
    return;
  }
  window.__nativeTts = true;

  var bridge = window.AndroidTTS;
  var voices = [];
  var queue = [];
  var current = null;
  var paused = false;
  var nextId = 1;
  var byId = {};

  function loadVoices() {
    if (voices.length > 0) {
      return;
    }
    try {
      voices = JSON.parse(bridge.getVoices()).map(function (v) {
        return {
          name: v.name,
          lang: v.lang,
          voiceURI: v.name,
          localService: v.local,
          default: v.def
        };
      });
    } catch (e) {
      voices = [];
    }
  }

  class SpeechSynthesisUtterance extends EventTarget {
    constructor(text) {
      super();
      this.text = text === undefined ? '' : String(text);
      this.lang = '';
      this.voice = null;
      this.pitch = 1;
      this.rate = 1;
      this.volume = 1;
      this.onstart = null;
      this.onend = null;
      this.onerror = null;
      this.onboundary = null;
      this.onpause = null;
      this.onresume = null;
      this.onmark = null;
    }
  }

  function fire(target, type, extra) {
    var e = new Event(type);
    e.utterance = target;
    e.charIndex = 0;
    e.charLength = 0;
    e.elapsedTime = 0;
    e.name = '';
    Object.assign(e, extra || {});
    var handler = target['on' + type];
    if (typeof handler === 'function') {
      try {
        handler.call(target, e);
      } catch (err) {
        console.error(err);
      }
    }
    target.dispatchEvent(e);
  }

  function startNext() {
    if (current || paused || queue.length === 0) {
      return;
    }
    var u = queue.shift();
    current = u;
    u._offset = u._offset || 0;
    u._id = String(nextId++);
    byId[u._id] = u;
    bridge.speak(
      u._id,
      u.text.substring(u._offset),
      u.voice ? u.voice.name : '',
      u.lang || (u.voice ? u.voice.lang : ''),
      u.rate,
      u.pitch,
      u.volume
    );
  }

  window.__ttsEvent = function (id, type, charIndex) {
    var u = byId[id];
    if (!u) {
      return;
    }
    if (type === 'start') {
      if (!u._started) {
        u._started = true;
        fire(u, 'start');
      }
    } else if (type === 'boundary') {
      u._pos = u._offset + charIndex;
      fire(u, 'boundary', { charIndex: u._pos, name: 'word' });
    } else if (type === 'end') {
      delete byId[id];
      current = null;
      fire(u, 'end', { charIndex: u.text.length });
      startNext();
    } else if (type === 'error') {
      delete byId[id];
      current = null;
      fire(u, 'error', { error: 'synthesis-failed' });
      startNext();
    }
  };

  class SpeechSynthesis extends EventTarget {
    constructor() {
      super();
      this.onvoiceschanged = null;
    }
    get speaking() {
      return current !== null || queue.length > 0;
    }
    get pending() {
      return queue.length > 0;
    }
    get paused() {
      return paused;
    }
    getVoices() {
      loadVoices();
      return voices.slice();
    }
    speak(u) {
      queue.push(u);
      startNext();
    }
    cancel() {
      var all = [];
      var interrupted = current;
      if (current) {
        delete byId[current._id];
        all.push(current);
        current = null;
      }
      all = all.concat(queue);
      queue = [];
      paused = false;
      bridge.stop();
      all.forEach(function (u) {
        fire(u, 'error', { error: u === interrupted ? 'interrupted' : 'canceled' });
      });
    }
    pause() {
      if (!current || paused) {
        return;
      }
      var u = current;
      paused = true;
      delete byId[u._id];
      bridge.stop();
      u._offset = u._pos || u._offset || 0;
      queue.unshift(u);
      current = null;
      fire(u, 'pause');
    }
    resume() {
      if (!paused) {
        return;
      }
      paused = false;
      if (queue.length > 0) {
        fire(queue[0], 'resume');
      }
      startNext();
    }
  }

  var synth = new SpeechSynthesis();
  Object.defineProperty(window, 'speechSynthesis', {
    value: synth,
    configurable: true,
    writable: true
  });
  window.SpeechSynthesisUtterance = SpeechSynthesisUtterance;

  setTimeout(function () {
    loadVoices();
    var e = new Event('voiceschanged');
    if (typeof synth.onvoiceschanged === 'function') {
      synth.onvoiceschanged(e);
    }
    synth.dispatchEvent(e);
  }, 300);
})();