// SPIKE, NOT FOR MERGE. Evaluated BEFORE the bundle in every GraalJS context:
// the Node/Web globals the self-contained login bundle reaches for that a
// bare GraalJS context does not have, and the one thing GraalJS embedding
// does not give you at all — an EVENT LOOP. Every shim counts its uses into
// `__spikeShimUse`, so the harness can report what a render actually touched.
(function () {
  var use = (globalThis.__spikeShimUse = {});
  function count(name) {
    use[name] = (use[name] || 0) + 1;
  }

  // shadow-cljs's :node-library prelude: `goog.global = global`.
  globalThis.global = globalThis;

  // ---- the macrotask queue --------------------------------------------------
  //
  // Node runs timers after the render returns; a GraalJS Context runs nothing
  // unless the host calls in. Fresco's collector arms a 4 ms reaper timer for
  // every subscription entry a server render mints
  // (re-frame.fresco.impl.collector/arm-entry-reaper!), and the timer is what
  // drops them, so without a pump the entries pile up for the life of the
  // context. The host calls `__spikePump()` on the slot's thread before each
  // render: it runs every task due by now, in due order.
  var tasks = [];
  var nextId = 1;
  function enqueue(fn, delay, args, kind) {
    var id = nextId++;
    var due = Date.now() + Math.max(0, Number(delay) || 0);
    tasks.push({ id: id, due: due, fn: fn, args: args, kind: kind });
    return id;
  }
  function cancel(id) {
    for (var i = 0; i < tasks.length; i++) {
      if (tasks[i].id === id) {
        tasks.splice(i, 1);
        return;
      }
    }
  }
  globalThis.setTimeout = function (fn, delay) {
    count('setTimeout');
    return enqueue(fn, delay, Array.prototype.slice.call(arguments, 2), 'timeout');
  };
  globalThis.clearTimeout = function (id) {
    count('clearTimeout');
    cancel(id);
  };
  globalThis.setImmediate = function (fn) {
    count('setImmediate');
    return enqueue(fn, 0, Array.prototype.slice.call(arguments, 1), 'immediate');
  };
  globalThis.clearImmediate = function (id) {
    cancel(id);
  };
  globalThis.__spikePump = function () {
    var ran = 0;
    for (;;) {
      var now = Date.now();
      var best = -1;
      for (var i = 0; i < tasks.length; i++) {
        if (tasks[i].due <= now && (best < 0 || tasks[i].due < tasks[best].due)) best = i;
      }
      if (best < 0) return ran;
      var t = tasks.splice(best, 1)[0];
      ran++;
      count('pump.ran');
      t.fn.apply(null, t.args);
    }
  };
  globalThis.__spikePending = function () {
    return tasks.length;
  };

  if (typeof globalThis.queueMicrotask !== 'function') {
    globalThis.queueMicrotask = function (fn) {
      count('queueMicrotask');
      Promise.resolve().then(fn);
    };
  }

  // react-dom/server.browser loads React's STREAMING browser build beside the
  // legacy one, and that build constructs one MessageChannel at module load
  // for `scheduleWork`. `renderToString` (the legacy build) never posts to it.
  if (typeof globalThis.MessageChannel === 'undefined') {
    globalThis.MessageChannel = function MessageChannel() {
      count('MessageChannel.new');
      var p1 = { onmessage: null, close: function () {} };
      var p2 = { onmessage: null, close: function () {} };
      p1.postMessage = function (data) {
        count('MessageChannel.postMessage');
        enqueue(function () { p2.onmessage && p2.onmessage({ data: data }); }, 0, [], 'message');
      };
      p2.postMessage = function (data) {
        count('MessageChannel.postMessage');
        enqueue(function () { p1.onmessage && p1.onmessage({ data: data }); }, 0, [], 'message');
      };
      this.port1 = p1;
      this.port2 = p2;
    };
  }

  // The same streaming build encodes its precomputed chunks at module load:
  // `new TextEncoder()` and `encode` on literal strings. A plain UTF-8
  // encoder; `renderToString` never encodes.
  if (typeof globalThis.TextEncoder === 'undefined') {
    globalThis.TextEncoder = function TextEncoder() {};
    globalThis.TextEncoder.prototype.encoding = 'utf-8';
    globalThis.TextEncoder.prototype.encode = function (input) {
      count('TextEncoder.encode');
      var s = String(input === undefined ? '' : input);
      var out = [];
      for (var i = 0; i < s.length; i++) {
        var c = s.charCodeAt(i);
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
          var d = s.charCodeAt(i + 1);
          if (d >= 0xdc00 && d <= 0xdfff) {
            c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00);
            i++;
          }
        }
        if (c < 0x80) out.push(c);
        else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
        else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
        else
          out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      }
      return new Uint8Array(out);
    };
  }
})();
