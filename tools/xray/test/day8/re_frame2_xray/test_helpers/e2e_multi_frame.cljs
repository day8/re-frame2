(ns day8.re-frame2-xray.test-helpers.e2e-multi-frame
  "Multi-frame end-to-end test harness for Xray (rf2-7icrs).

  Spins up TWO re-frame frames in one Node CLJS test process:

    - a HOST frame (default `:rf/default`) that plays the role of the
      observed application; a `:install-host` zero-arg fn registers
      whatever events / subs / machines / flows the test wants to
      drive;
    - the `:rf/xray` panel frame; `:install-xray` (default:
      `xray-test-support/reset-sentinels!` + `registry/register-xray-
      handlers!` + the seed dispatches `mount.cljs/ensure-xray-
      frame!` runs) wires Xray's own subs / events / fxs.

  Then it wires Xray's trace-bus consumption to the host's dispatches
  exactly the way the production preload does — i.e. it registers
  `trace-collector/seed-trace-for-test!` under the framework's
  `re-frame.trace.tooling/register-listener!` surface. After that, any
  REAL `(rf/dispatch-sync ev {:frame :host})` into the host fans out
  through the trace bus → Xray's `:trace-buffer` slot →
  `:rf.xray/event-bundles` / `:rf.xray/focus` / every panel sub.

  ## Why no test seam

  The synthetic-cascade approach (rf2-dhoc9) writes pre-formed
  cascades directly into Xray's app-db via
  `:rf.xray/set-epoch-history-for-test` (or analogous). That bypasses
  the trace-bus + epoch-capture pipeline where 3 of 4 critical bugs
  this session lived (rf2-hwuki `:frame` tag drop, rf2-83d4x wrong-
  frame dispatch, rf2-2f8jv machines empty). This harness uses the
  REAL pipeline — every test exercises the full ingest path.

  ## Cost

  Each invocation: ~5-10 ms — mostly re-frame frame setup and the
  one-time `register-xray-handlers!` run. Both frames live in the
  same JS process (re-frame is designed for it; the dispatcher is
  per-frame and the trace bus is process-global). No browser, no
  DOM (panels are NEVER mounted — we read subs directly via
  `rf/subscribe` under `with-frame :rf/xray`).

  ## Frame-resolution chain (Spec 002)

  Inside a `with-host-and-xray-frames` body the test calls (e.g.):

      (e2e/dispatch-host [:counter/inc])              ; → host frame
      (e2e/dispatch-xray [:rf.xray/toggle-mode])    ; → :rf/xray
      @(e2e/sub-xray [:rf.xray/focus])              ; → :rf/xray

  These helpers thread the correct `:frame` option without polluting
  the test with `(rf/with-frame ...)` boilerplate.

  ## Teardown

  `body-fn` runs inside a try/finally that clears the trace-bus atom +
  unregisters the trace callback so subsequent tests start from a
  fresh slate. The `make-reset-runtime-fixture` (which the per-test-file
  fixture wraps around `use-fixtures :each`) handles frame disposal
  and registrar restoration."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.self-noise :as self-noise]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- defaults ------------------------------------------------------------

(def default-host-frame
  "The conventional host-frame id. Tests can override via
  `:host-frame` in the opts map."
  :rf/default)

;; ---- install helpers -----------------------------------------------------

(defn install-xray-default!
  "The canonical Xray install path used by `with-host-and-xray-
  frames` when the caller did not supply `:install-xray`.

  Mirrors what production does:

    1. `xray-test-support/reset-sentinels!` — wipe idempotency
       sentinels so a re-install in a re-used test process actually
       re-installs. Sentinels ONLY — NOT the trace rings: the host
       frame is already registered by this point (rf2-sdqsla), and
       clearing the rings here would wipe its per-frame recording
       config. The harness teardown owns the ring clear (below).
    2. `registry/register-xray-handlers!` — every `:rf.xray/*` sub /
       event / fx (the orchestrator).
    3. `reg-frame :rf/xray` — register Xray's frame so subs scoped
       under it resolve to the right app-db.
    4. Seed `:trace-buffer` + `:epoch-history` + `:target-frame` via
       the same dispatches `mount.cljs/ensure-xray-frame!` runs at
       first Ctrl+Shift+C. (Sub graphs depend on these slots being
       populated; without the seed the head walks would return nil
       and `compose-focus` would snap to head with nothing to land
       on.)
    5. `preload/register-trace-collector!` — register the trace cb so
       host dispatches mirror into Xray's buffer the same way the
       production preload does.

  Idempotent — repeated invocations are safe (the sentinels are reset
  by step 1)."
  []
  (xray-test-support/reset-sentinels!)
  (registry/register-xray-handlers!)
  ;; rf2-e8330v (xxo3zz F3) — production registration installs no
  ;; `-for-test` ids; opt the e2e surface into the per-panel override +
  ;; seeding seam the harness's tests drive.
  (xray-test-support/install-test-overrides!)
  (frame/reg-frame :rf/xray {})
  ;; Seed app-db slots so subs that depend on `:epoch-history` /
  ;; `:target-frame` / `:trace-buffer` resolve to the canonical
  ;; shapes. `mount.cljs/ensure-xray-frame!` does the same.
  (let [buffer     (trace-collector/buffer-for-test)
        cascades   (into [] (remove self-noise/xray-internal-event-bundle?)
                         (projection/group-by-event buffer))
        seed-frame (or (spine/focusable-head-frame-id cascades)
                       defaults/default-target-frame)]
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer buffer])
      (rf/dispatch-sync [:rf.xray/set-target-frame seed-frame])))
  (preload/register-trace-collector!)
  nil)

;; ---- harness -------------------------------------------------------------

(defn with-host-and-xray-frames*
  "Function-style harness; see `with-host-and-xray-frames` (the doc-
  bearing canonical entry point) for usage. Separated so callers can
  pass a quoted body via fn rather than the macro-ish 2-arity sugar."
  [{:keys [host-frame install-host install-xray]
    :or   {host-frame default-host-frame
           install-xray install-xray-default!}}
   body-fn]
  ;; Register the host frame BEFORE Xray installs — Xray's seed
  ;; reads the host frame's epoch ring + cascade list, so the host
  ;; must exist (even if empty) at install time.
  (frame/reg-frame host-frame {})
  (when install-host (install-host))
  (install-xray)
  (try
    (body-fn)
    (finally
      ;; Be explicit about trace-bus state — the
      ;; `make-reset-runtime-fixture` clears registrar entries but not the
      ;; trace-bus atom (which is process-global / `defonce`). Clear
      ;; here so the next test starts from a fresh slate.
      (trace-collector/reset-for-test!))))

(defn with-host-and-xray-frames
  "Set up host + `:rf/xray` frames, run `body-fn`, tear down.

  Opts (all optional):

    :host-frame      — the host frame id (default `:rf/default`).
    :install-host    — zero-arg fn registering the host's events /
                       subs / machines / flows. Run AFTER the frame
                       is registered.
    :install-xray   — zero-arg fn installing Xray. Defaults to
                       `install-xray-default!`. Override only when a
                       test needs a non-canonical Xray setup (rare).

  `body-fn` runs inside the harness; usage looks like:

      (with-host-and-xray-frames
        {:host-frame :app
         :install-host counter-fixture/install!}
        (fn []
          (dispatch-host [:counter/inc] {:frame :app})
          (let [cascade @(sub-xray [:rf.xray/focus])]
            (is (= [:counter/inc]
                   (-> cascade :head-event-bundle :event))))))

  See the helper fns below (`dispatch-host`, `dispatch-xray`,
  `sub-host`, `sub-xray`) for the canonical access surface inside
  `body-fn`."
  [opts body-fn]
  (with-host-and-xray-frames* opts body-fn))

;; ---- dispatch / subscribe helpers ----------------------------------------

(defn- sub-xray-target-frame*
  "Reach into `:rf/xray`'s app-db for the currently-targeted host
  frame. Test-helper private — used by `sync-xray-epoch-history!`
  to know which frame's epoch ring to re-read."
  []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/target-frame])))

(defn sync-xray-trace-mirror!
  "Synchronously copy `trace-collector/buffer-for-test` into Xray's app-db
  `:trace-buffer` slot. The production path uses a `next-tick`-
  coalesced `request-mirror-sync!` so the slot lands on the next
  microtask; in node-test we want the mirror to be visible to subs
  on the SAME tick the host dispatch returns, so the helpers below
  drive this synchronously after each `dispatch-host` / `dispatch-
  xray` call."
  []
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/sync-trace-buffer (trace-collector/buffer-for-test)])))

(defn sync-xray-epoch-history!
  "Synchronously re-read the framework's per-frame epoch ring into
  Xray's `:epoch-history` slot. The production path mirrors via the
  `:rf.xray/epoch-recorded` callback registered by
  `preload/register-epoch-collector!`. In node-test we drive it
  synchronously after each host dispatch — this is what the App-DB
  Diff and Views panels (which `:<-` on `:rf.xray/epoch-history`)
  consume to re-fire on the standard app-db-write reactive path."
  []
  (let [target (sub-xray-target-frame*)]
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-target-frame target]))))

(defn dispatch-host
  "Synchronously dispatch `event` into the host frame and drain to fixed
  point. Resolves the host frame via the supplied `:frame` opt, falling
  back to the default `:rf/default`. The 1-arity uses the default
  host frame.

  Always uses `dispatch-sync` so the test thread sees the post-drain
  state on return. After the dispatch settles, drives a synchronous
  trace-mirror + epoch-history sync into Xray's app-db so the panel
  subs reading `:rf.xray/trace-buffer` and `:rf.xray/epoch-history`
  resolve to current values without waiting for the production
  `next-tick` coalesce."
  ([event] (dispatch-host event {:frame default-host-frame}))
  ([event {:keys [frame] :or {frame default-host-frame} :as _opts}]
   (rf/dispatch-sync event {:frame frame})
   (sync-xray-trace-mirror!)
   (sync-xray-epoch-history!)))

(defn dispatch-xray
  "Synchronously dispatch `event` into the `:rf/xray` frame. The
  Xray-side control axes (mode toggle, view group-by, palette
  invoke, …) dispatch through this helper."
  [event]
  (rf/dispatch-sync event {:frame :rf/xray}))

(defn sub-host
  "Subscribe in the host frame and dereference. Always returns the
  current value (never a Reaction). The 1-arity uses the default host
  frame; the 2-arity accepts an opts map with `:frame`."
  ([query] (sub-host query {:frame default-host-frame}))
  ([query {:keys [frame] :or {frame default-host-frame}}]
   (rf/with-frame frame
     @(rf/subscribe query))))

(defn sub-xray
  "Subscribe in the `:rf/xray` frame and dereference. Always returns
  the current value (never a Reaction)."
  [query]
  (rf/with-frame :rf/xray
    @(rf/subscribe query)))

;; ---- assertion helpers ---------------------------------------------------

(defn xray-cascades
  "Convenience accessor — current value of `:rf.xray/event-bundles` (the
  shared projection over the trace buffer, with Xray-internal
  cascades filtered out)."
  []
  (sub-xray [:rf.xray/event-bundles]))

(defn xray-focused-event
  "Convenience accessor — return the `:event` vector of the cascade
  Xray's spine is currently focused on, or nil if no cascade exists
  yet. Reads `:rf.xray/focus` for the focused `:dispatch-id` then
  walks `:rf.xray/event-bundles` to find the matching cascade.

  Useful in panel tests asserting 'Xray is currently looking at the
  host event we just dispatched'."
  []
  (let [focus    (sub-xray [:rf.xray/focus])
        cascades (sub-xray [:rf.xray/event-bundles])
        head-id  (:dispatch-id focus)]
    (some (fn [c] (when (= head-id (:dispatch-id c)) (:event c)))
          cascades)))

(defn xray-focused-frame
  "Convenience accessor — return the `:frame` of the head cascade
  Xray's spine is focused on. Useful for asserting cross-frame
  routing: dispatch into host frame `:app`, assert Xray's focus
  carries `:app` in the cascade-`:frame` slot."
  []
  (let [focus    (sub-xray [:rf.xray/focus])
        cascades (sub-xray [:rf.xray/event-bundles])
        head-id  (:dispatch-id focus)]
    (some (fn [c] (when (= head-id (:dispatch-id c)) (:frame c)))
          cascades)))

(defn host-cascades-only
  "Return the subset of `:rf.xray/event-bundles` whose `:frame` is the
  supplied host-frame id. Filters out the Xray-internal cascades
  (those are already removed by the `:rf.xray/event-bundles` sub) and any
  cascades that landed on other frames (e.g. cross-frame dispatch
  fan-out in multi-frame testbeds). 1-arity defaults to
  `default-host-frame`."
  ([] (host-cascades-only default-host-frame))
  ([host-frame]
   (filterv (fn [c] (= host-frame (:frame c))) (xray-cascades))))
