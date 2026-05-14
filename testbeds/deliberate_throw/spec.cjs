/*
 * Cross-cutting scenario #1 of 6 from rf2-fe84r §Section B.
 *
 *   "Deliberately-throwing handler surfaces structured trace in both
 *    Causa + Story `:play`."
 *
 * Exercises `testbeds/deliberate_throw/` (rf2-kzcim). The surface ships
 * four trigger sites — handler / fx / flow / machine. This spec drives
 * Button A (handler-exception), the canonical case: a synchronous throw
 * in the event-handler body. The runtime's outer interceptor catches
 * and emits a structured `:rf.error/handler-exception` trace event
 * with `:op-type :error`, the failing handler's id, the original
 * message, and a `:where :handler` tag (per Spec 009 §Error contract).
 *
 * What "structured trace" means here — the trace event the framework
 * lands on the trace bus after the throw carries:
 *
 *   - `:operation :rf.error/handler-exception` (the category)
 *   - `:op-type :error` (severity tier)
 *   - `:tags { :exception ... :where :handler ... }` (the structured
 *     detail). The Story `:play` replay path AND Causa's trace panel
 *     both consume off this single bus, so asserting on the framework
 *     trace event is the cross-cutting observable that proves the
 *     contract holds independent of which tool is consuming.
 *
 * Observation surface — every testbed wires `day8.re-frame2-causa.preload`
 * via shadow-cljs `:devtools/:preloads`, which registers Causa's
 * `:rf.causa/trace-collector` callback against the framework's trace
 * bus. Reading `day8.re_frame2_causa.trace_bus.buffer()` returns the
 * same CLJS vector of trace-event maps the framework deposited. The
 * preload's collector runs whether the user opened Causa or not, so
 * the spec stays a framework-behavior observation (no shell mount, no
 * panel render required).
 *
 * Recovery posture — handler-exception is `:no-recovery` per Spec 009.
 * The router's `emit-handler-exception!` (re-frame/routing.cljc:285)
 * captures the exception into `:rf/interceptor-error` rather than
 * re-throwing; the drain continues, the failing handler's `:db` is
 * dropped, no pageerror bubbles. So this spec does NOT need to
 * intercept `page.on('pageerror', ...)` — the runner's pageerror gate
 * stays clean.
 */

const path = require('path');
const { expectVisible } = require(path.join('..', '..', 'examples', 'scripts', 'spec-helpers.cjs'));
const { readTraceEventsAsEdn, pollUntil } = require(path.join('..', 'spec-helpers.cjs'));

module.exports = {
  name: 'cross-cutting #1 — deliberate-throw structured trace',
  url: '/testbeds/deliberate-throw/',
  run: async (page) => {
    // The surface mounts a four-button view; wait for the root testid
    // so we know the substrate has initialised and event registration
    // has run. The buttons fan dispatches at the framework via
    // reagent-adapter → router → trace bus.
    await expectVisible(page.locator('[data-testid="deliberate-throw"]'), 10000);

    // Pre-condition: the handler-counter mirror in app-db starts at 0.
    // Button A's body throws as its first statement, so `:db` never
    // commits and the mirror stays 0 after the click. Reading the
    // mirror after the click is the rule-of-recovery assertion
    // (handler-exception is `:no-recovery` per Spec 009).
    const handlerCount = page.locator('[data-testid="handler-count"]');
    if ((await handlerCount.textContent())?.trim() !== '0') {
      throw new Error('handler-count did not start at 0');
    }

    // Drive Button A. The dispatched event ::throw-in-handler is a
    // `reg-event-db` whose body's first statement is
    // `(throw (ex-info "deliberate-throw / handler" {:where :handler}))`.
    await page.locator('[data-testid="throw-handler"]').click();

    // Poll the Causa-side trace bus mirror for the structured error
    // event. The bus is the framework's emitted stream — Causa's
    // preload registers a `:rf.causa/trace-collector` cb that pushes
    // every framework emit into `trace_bus/buffer-state`. We render
    // each event through `pr-str` to get a JS-comparable string.
    //
    // The cross-cutting contract — ONE event with:
    //   :operation :rf.error/handler-exception
    //   :op-type   :error
    //   :tags carrying :where :handler (from the testbed's ex-data)
    const match = await pollUntil(async () => {
      const probe = await readTraceEventsAsEdn(page);
      if (!probe.ok) {
        throw new Error(`could not read trace bus: ${probe.reason}`);
      }
      return probe.events.find((s) =>
        /:operation\s+:rf\.error\/handler-exception/.test(s) &&
        /:op-type\s+:error/.test(s),
      );
    }, 'a :rf.error/handler-exception trace event with :op-type :error after Button A click');

    // The `:where :handler` tag survives — it's the testbed's
    // discriminator that confirms the framework hoisted the ex-data
    // onto `:tags`. The testbed's throw site is `(throw (ex-info
    // "..." {:where :handler}))`, and Spec 009 §Error contract requires
    // ex-data tags to ride through onto `:tags` (the privacy walker
    // may redact values but key presence is preserved).
    if (!/:where\s+:handler/.test(match)) {
      throw new Error(
        `expected matched error event to carry :where :handler from the ex-info data; sample: ${match}`,
      );
    }

    // Recovery posture: handler-exception is `:no-recovery` per Spec
    // 009 — the failing handler's `:db` is suppressed. The mirror
    // stays 0 after a click that should have bumped it (the testbed
    // throws as its first statement so `:db` never commits).
    if ((await handlerCount.textContent())?.trim() !== '0') {
      throw new Error(
        'handler-count advanced after Button A click — expected :no-recovery semantics to suppress the :db write',
      );
    }
  },
};
