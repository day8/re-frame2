// SPIKE, NOT FOR MERGE. Evaluated AFTER the bundle in every GraalJS context.
//
// The sidecar's worker freezes the call before the module sees it
// (implementation/ssr-node/src/worker.cjs, handleRender); this is the same
// freeze, fed from the same JSON the JVM renderer would have POSTed.
(function () {
  var mod = globalThis.returnExports;
  if (!mod || typeof mod.render !== 'function') {
    throw new Error('spike driver: the bundle published no render module (returnExports)');
  }
  if (typeof mod.boot === 'function') mod.boot();
  globalThis.__spikeModule = mod;
  globalThis.__spikeRender = function (requestJson) {
    // Run whatever the previous render left due (Fresco's reapers) first:
    // this slot's thread is the only one that may enter the context.
    globalThis.__spikePump();
    var request = JSON.parse(requestJson);
    var call = Object.freeze({
      entry: request.entry,
      state: Object.freeze(Object.assign({}, request.state)),
      runtime: Object.freeze(Object.assign({}, request.runtime)),
      args: request.args,
    });
    var html = mod.render(call);
    if (typeof html !== 'string') {
      throw new Error('spike driver: render returned ' + typeof html + ', not a string');
    }
    return html;
  };
  // The same render with NO pump: what a host that never runs the
  // context's timers would be doing.
  globalThis.__spikeRenderNoPump = function (requestJson) {
    var request = JSON.parse(requestJson);
    return mod.render(Object.freeze({
      entry: request.entry,
      state: Object.freeze(Object.assign({}, request.state)),
      runtime: Object.freeze(Object.assign({}, request.runtime)),
      args: request.args,
    }));
  };
  globalThis.__spikeStats = function () {
    return JSON.stringify({ use: globalThis.__spikeShimUse, pending: globalThis.__spikePending() });
  };
  globalThis.__spikeSpin = function () {
    for (;;) {}
  };
  return mod.buildId;
})();
