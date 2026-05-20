(ns day8.re-frame2-causa.test-helpers.e2e-multi-frame
  "Multi-frame end-to-end test harness for Causa (rf2-7icrs).

  Spins up TWO re-frame frames in one Node CLJS test process:

    - a HOST frame (default `:rf/default`) that plays the role of the
      observed application; a `:install-host` zero-arg fn registers
      whatever events / subs / machines / flows the test wants to
      drive;
    - the `:rf/causa` panel frame; `:install-causa` (default:
      `causa-test-support/reset-all!` + `registry/register-causa-
      handlers!` + the seed dispatches `mount.cljs/ensure-causa-
      frame!` runs) wires Causa's own subs / events / fxs.

  Then it wires Causa's trace-bus consumption to the host's dispatches
  exactly the way the production preload does — i.e. it registers
  `trace-bus/collect-trace!` under the framework's
  `re-frame.trace.tooling/register-trace-listener!` surface. After that, any
  REAL `(rf/dispatch-sync ev {:frame :host})` into the host fans out
  through the trace bus → Causa's `:trace-buffer` slot →
  `:rf.causa/cascades` / `:rf.causa/focus` / every panel sub.

  ## Why no test seam

  The synthetic-cascade approach (rf2-dhoc9) writes pre-formed
  cascades directly into Causa's app-db via
  `:rf.causa/set-epoch-history-for-test` (or analogous). That bypasses
  the trace-bus + epoch-capture pipeline where 3 of 4 critical bugs
  this session lived (rf2-hwuki `:frame` tag drop, rf2-83d4x wrong-
  frame dispatch, rf2-2f8jv machines empty). This harness uses the
  REAL pipeline — every test exercises the full ingest path.

  ## Cost

  Each invocation: ~5-10 ms — mostly re-frame frame setup and the
  one-time `register-causa-handlers!` run. Both frames live in the
  same JS process (re-frame is designed for it; the dispatcher is
  per-frame and the trace bus is process-global). No browser, no
  DOM (panels are NEVER mounted — we read subs directly via
  `rf/subscribe` under `with-frame :rf/causa`).

  ## Frame-resolution chain (Spec 002)

  Inside a `with-host-and-causa-frames` body the test calls (e.g.):

      (e2e/dispatch-host [:counter/inc])              ; → host frame
      (e2e/dispatch-causa [:rf.causa/toggle-mode])    ; → :rf/causa
      @(e2e/sub-causa [:rf.causa/focus])              ; → :rf/causa

  These helpers thread the correct `:frame` option without polluting
  the test with `(rf/with-frame ...)` boilerplate.

  ## Teardown

  `body-fn` runs inside a try/finally that clears the trace-bus atom +
  unregisters the trace callback so subsequent tests start from a
  fresh slate. The `reset-runtime-fixture` (which the per-test-file
  fixture wraps around `use-fixtures :each`) handles frame disposal
  and registrar restoration."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-causa.defaults :as defaults]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.spine :as spine]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- defaults ------------------------------------------------------------

(def default-host-frame
  "The conventional host-frame id. Tests can override via
  `:host-frame` in the opts map."
  :rf/default)

;; ---- install helpers -----------------------------------------------------

(defn install-causa-default!
  "The canonical Causa install path used by `with-host-and-causa-
  frames` when the caller did not supply `:install-causa`.

  Mirrors what production does:

    1. `causa-test-support/reset-all!` — wipe idempotency sentinels so
       a re-install in a re-used test process actually re-installs.
    2. `registry/register-causa-handlers!` — every `:rf.causa/*` sub /
       event / fx (the orchestrator).
    3. `reg-frame :rf/causa` — register Causa's frame so subs scoped
       under it resolve to the right app-db.
    4. Seed `:trace-buffer` + `:epoch-history` + `:target-frame` via
       the same dispatches `mount.cljs/ensure-causa-frame!` runs at
       first Ctrl+Shift+C. (Sub graphs depend on these slots being
       populated; without the seed the head walks would return nil
       and `compose-focus` would snap to head with nothing to land
       on.)
    5. `preload/register-trace-collector!` — register the trace cb so
       host dispatches mirror into Causa's buffer the same way the
       production preload does.

  Idempotent — repeated invocations are safe (the sentinels are reset
  by step 1)."
  []
  (causa-test-support/reset-all!)
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  ;; Seed app-db slots so subs that depend on `:epoch-history` /
  ;; `:target-frame` / `:trace-buffer` resolve to the canonical
  ;; shapes. `mount.cljs/ensure-causa-frame!` does the same.
  (let [buffer     (trace-bus/buffer)
        cascades   (into [] (remove trace-bus/causa-internal-cascade?)
                         (projection/group-cascades buffer))
        seed-frame (or (spine/focusable-head-frame-id cascades)
                       defaults/default-target-frame)]
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/sync-trace-buffer buffer])
      (rf/dispatch-sync [:rf.causa/set-target-frame seed-frame])))
  (preload/register-trace-collector!)
  nil)

;; ---- harness -------------------------------------------------------------

(defn with-host-and-causa-frames*
  "Function-style harness; see `with-host-and-causa-frames` (the doc-
  bearing canonical entry point) for usage. Separated so callers can
  pass a quoted body via fn rather than the macro-ish 2-arity sugar."
  [{:keys [host-frame install-host install-causa]
    :or   {host-frame default-host-frame
           install-causa install-causa-default!}}
   body-fn]
  ;; Register the host frame BEFORE Causa installs — Causa's seed
  ;; reads the host frame's epoch ring + cascade list, so the host
  ;; must exist (even if empty) at install time.
  (frame/reg-frame host-frame {})
  (when install-host (install-host))
  (install-causa)
  (try
    (body-fn)
    (finally
      ;; Be explicit about trace-bus state — the
      ;; `reset-runtime-fixture` clears registrar entries but not the
      ;; trace-bus atom (which is process-global / `defonce`). Clear
      ;; here so the next test starts from a fresh slate.
      (trace-bus/clear-buffer!))))

(defn with-host-and-causa-frames
  "Set up host + `:rf/causa` frames, run `body-fn`, tear down.

  Opts (all optional):

    :host-frame      — the host frame id (default `:rf/default`).
    :install-host    — zero-arg fn registering the host's events /
                       subs / machines / flows. Run AFTER the frame
                       is registered.
    :install-causa   — zero-arg fn installing Causa. Defaults to
                       `install-causa-default!`. Override only when a
                       test needs a non-canonical Causa setup (rare).

  `body-fn` runs inside the harness; usage looks like:

      (with-host-and-causa-frames
        {:host-frame :app
         :install-host counter-fixture/install!}
        (fn []
          (dispatch-host [:counter/inc] {:frame :app})
          (let [cascade @(sub-causa [:rf.causa/focus])]
            (is (= [:counter/inc]
                   (-> cascade :head-cascade :event))))))

  See the helper fns below (`dispatch-host`, `dispatch-causa`,
  `sub-host`, `sub-causa`) for the canonical access surface inside
  `body-fn`."
  [opts body-fn]
  (with-host-and-causa-frames* opts body-fn))

;; ---- dispatch / subscribe helpers ----------------------------------------

(defn- sub-causa-target-frame*
  "Reach into `:rf/causa`'s app-db for the currently-targeted host
  frame. Test-helper private — used by `sync-causa-epoch-history!`
  to know which frame's epoch ring to re-read."
  []
  (rf/with-frame :rf/causa
    @(rf/subscribe [:rf.causa/target-frame])))

(defn sync-causa-trace-mirror!
  "Synchronously copy `trace-bus/buffer` into Causa's app-db
  `:trace-buffer` slot. The production path uses a `next-tick`-
  coalesced `request-mirror-sync!` so the slot lands on the next
  microtask; in node-test we want the mirror to be visible to subs
  on the SAME tick the host dispatch returns, so the helpers below
  drive this synchronously after each `dispatch-host` / `dispatch-
  causa` call."
  []
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/sync-trace-buffer (trace-bus/buffer)])))

(defn sync-causa-epoch-history!
  "Synchronously re-read the framework's per-frame epoch ring into
  Causa's `:epoch-history` slot. The production path mirrors via the
  `:rf.causa/epoch-recorded` callback registered by
  `preload/register-epoch-collector!`. In node-test we drive it
  synchronously after each host dispatch — this is what the App-DB
  Diff and Views panels (which `:<-` on `:rf.causa/epoch-history`)
  consume to re-fire on the standard app-db-write reactive path."
  []
  (let [target (sub-causa-target-frame*)]
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-target-frame target]))))

(defn dispatch-host
  "Synchronously dispatch `event` into the host frame and drain to fixed
  point. Resolves the host frame via the supplied `:frame` opt, falling
  back to the default `:rf/default`. The 1-arity uses the default
  host frame.

  Always uses `dispatch-sync` so the test thread sees the post-drain
  state on return. After the dispatch settles, drives a synchronous
  trace-mirror + epoch-history sync into Causa's app-db so the panel
  subs reading `:rf.causa/trace-buffer` and `:rf.causa/epoch-history`
  resolve to current values without waiting for the production
  `next-tick` coalesce."
  ([event] (dispatch-host event {:frame default-host-frame}))
  ([event {:keys [frame] :or {frame default-host-frame} :as _opts}]
   (rf/dispatch-sync event {:frame frame})
   (sync-causa-trace-mirror!)
   (sync-causa-epoch-history!)))

(defn dispatch-causa
  "Synchronously dispatch `event` into the `:rf/causa` frame. The
  Causa-side control axes (mode toggle, view group-by, palette
  invoke, …) dispatch through this helper."
  [event]
  (rf/dispatch-sync event {:frame :rf/causa}))

(defn sub-host
  "Subscribe in the host frame and dereference. Always returns the
  current value (never a Reaction). The 1-arity uses the default host
  frame; the 2-arity accepts an opts map with `:frame`."
  ([query] (sub-host query {:frame default-host-frame}))
  ([query {:keys [frame] :or {frame default-host-frame}}]
   (rf/with-frame frame
     @(rf/subscribe query))))

(defn sub-causa
  "Subscribe in the `:rf/causa` frame and dereference. Always returns
  the current value (never a Reaction)."
  [query]
  (rf/with-frame :rf/causa
    @(rf/subscribe query)))

;; ---- assertion helpers ---------------------------------------------------

(defn causa-cascades
  "Convenience accessor — current value of `:rf.causa/cascades` (the
  shared projection over the trace buffer, with Causa-internal
  cascades filtered out)."
  []
  (sub-causa [:rf.causa/cascades]))

(defn causa-focused-event
  "Convenience accessor — return the `:event` vector of the cascade
  Causa's spine is currently focused on, or nil if no cascade exists
  yet. Reads `:rf.causa/focus` for the focused `:dispatch-id` then
  walks `:rf.causa/cascades` to find the matching cascade.

  Useful in panel tests asserting 'Causa is currently looking at the
  host event we just dispatched'."
  []
  (let [focus    (sub-causa [:rf.causa/focus])
        cascades (sub-causa [:rf.causa/cascades])
        head-id  (:dispatch-id focus)]
    (some (fn [c] (when (= head-id (:dispatch-id c)) (:event c)))
          cascades)))

(defn causa-focused-frame
  "Convenience accessor — return the `:frame` of the head cascade
  Causa's spine is focused on. Useful for asserting cross-frame
  routing: dispatch into host frame `:app`, assert Causa's focus
  carries `:app` in the cascade-`:frame` slot."
  []
  (let [focus    (sub-causa [:rf.causa/focus])
        cascades (sub-causa [:rf.causa/cascades])
        head-id  (:dispatch-id focus)]
    (some (fn [c] (when (= head-id (:dispatch-id c)) (:frame c)))
          cascades)))

(defn host-cascades-only
  "Return the subset of `:rf.causa/cascades` whose `:frame` is the
  supplied host-frame id. Filters out the Causa-internal cascades
  (those are already removed by the `:rf.causa/cascades` sub) and any
  cascades that landed on other frames (e.g. cross-frame dispatch
  fan-out in multi-frame testbeds). 1-arity defaults to
  `default-host-frame`."
  ([] (host-cascades-only default-host-frame))
  ([host-frame]
   (filterv (fn [c] (= host-frame (:frame c))) (causa-cascades))))
