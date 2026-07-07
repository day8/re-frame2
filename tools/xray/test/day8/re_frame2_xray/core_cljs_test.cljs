(ns day8.re-frame2-xray.core-cljs-test
  "Tests for `day8.re-frame2-xray.core` — the canonical user-facing
  facade promised by `spec/API.md` (rf2-13bx9).

  ## Three contract surfaces under test

  1. **Re-export identity.** The mount + config re-exports MUST be
     `def`-aliases (not wrapper fns) so `(= mount/open! core/open!)`
     holds. Identity is the contract: a host that wires
     `core/toggle!` into its keybinding catalogue must land on the
     same Var the preload's listener calls.

  2. **Frame wiring.** `set-target-frame!` dispatches into the
     `:rf/xray` frame; the dispatch updates `:rf.xray/target-frame`
     in Xray's app-db, so the companion sub re-fires and
     `target-frame` reads back the new value. (The legacy panel
     picker — `active-panel` / `set-active-panel!` — was deleted with
     rf2-qy0nu; the 4-layer shell switches via `:rf.xray/selected-
     tab` and the API is gone.)

  3. **`load-theme` is a safe no-op without a DOM.** It injects a
     host-supplied CSS override into `<head>` when a DOM is present
     (rf2-ee38b.2 wired it through `global-styles/set-host-theme-css!`);
     under node-test there is no `js/document`, so it must return nil
     without throwing for any input (CSS string / empty / nil)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.core :as core]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  ;; rf2-sdqsla — `reset-runtime!` folds the sentinel + trace-collector
  ;; + settings reset into one call. rf2-2thl2 — the settings reset
  ;; matters because init! writes through to the persisted Settings atom;
  ;; reset between tests so per-test mutations don't leak into the next
  ;; test's read.
  (xray-test-support/reset-runtime!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- setup-xray-frame!
  "Per-test boot: register handlers, allocate the :rf/xray frame."
  []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; ---- (1) re-export identity --------------------------------------------

(deftest mount-fns-are-aliases
  (testing "mount entry points are def-aliases, not wrapper fns"
    ;; Identity check — the facade Var resolves to the same fn-value
    ;; as the underlying mount Var.
    (is (identical? mount/open!   core/open!))
    (is (identical? mount/close!  core/close!))
    (is (identical? mount/toggle! core/toggle!))
    (is (identical? mount/popout! core/popout!))))

(deftest config-fns-are-aliases
  (testing "config knob setters are def-aliases"
    (is (identical? config/configure!          core/configure!))
    (is (identical? config/set-editor!         core/set-editor!))
    (is (identical? config/set-auto-open!      core/set-auto-open!))
    (is (identical? config/set-egress-profile! core/set-egress-profile!))))

;; ---- (2) frame wiring --------------------------------------------------
;;
;; The facade's `set-target-frame!` calls `rf/dispatch` (async — the
;; user-facing contract; the runtime dispatch queues into the
;; `:rf/xray` frame's router so it lands inside the next drain). The
;; tests assert the same wiring contract via `dispatch-sync` so the
;; drain runs to completion inside the assertion — mirrors the pattern
;; the registry / shell tests use for the rest of the `:rf.xray/*`
;; surface.

(deftest target-frame-default-is-unselected
  (testing "EP-0002 (rf2-bd4div) — target-frame defaults to nil = UNSELECTED
            (per defaults/default-target-frame), NOT :rf/default. The
            inspected target is never absence-repaired to the ordinary
            :rf/default id; it starts unselected and the picker / discovery
            policy selects one."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (nil? (core/target-frame))))))

(deftest set-target-frame-wires-target-frame
  (testing ":rf.xray/set-target-frame updates the slot target-frame reads"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-target-frame :app/main])
      (is (= :app/main (core/target-frame))
          "after dispatch the facade's read returns the new value")
      (rf/dispatch-sync [:rf.xray/set-target-frame :worker/db])
      (is (= :worker/db (core/target-frame))
          "subsequent flips also land")
      (rf/dispatch-sync [:rf.xray/set-target-frame nil])
      (is (nil? (core/target-frame))
          "EP-0002 (rf2-bd4div) — nil resets to UNSELECTED (not through :rf/default)"))))

(deftest set-target-frame!-dispatches-set-target-frame
  (testing "set-target-frame! dispatches :rf.xray/set-target-frame into :rf/xray"
    ;; The `rf/dispatch` macro's expansion calls the `^:no-doc`
    ;; `re-frame.core/dispatch-impl` seam directly (rf2-m90brg retired the
    ;; `re-frame.core/dispatch*` facade twin), so with-redefs THAT seam —
    ;; redef'ing `re-frame.router/dispatch!` directly would fail (a plain
    ;; `defn`'s static arity-dispatch bypasses `with-redefs`), and redef'ing
    ;; `re-frame.core/dispatch` (the CLJS value-alias) would have no effect
    ;; on the already-compiled macro call site inside `core.cljs` either.
    (let [seen (atom [])]
      (with-redefs [rf/dispatch-impl (fn [ev & _opts] (swap! seen conj ev))]
        (core/set-target-frame! :app/main))
      (is (= [[:rf.xray/set-target-frame :app/main]] @seen)
          "facade dispatches the right event with the right arg"))))

;; ---- (3) load-theme — DOM-bearing impl, no-op without a DOM -------------

(deftest load-theme-is-safe-without-document
  (testing "rf2-ee38b.2 — load-theme is wired through
            global-styles/set-host-theme-css!. Under node-test there is
            no js/document, so it returns nil without throwing for any
            input and emits NO warning trace (the old not-yet-implemented
            stub is gone). DOM-bearing CSS injection is exercised by the
            browser target."
    (preload/register-trace-collector!)
    (let [result (core/load-theme ".foo { color: red; }")
          events (trace-collector/buffer-for-test)
          stale  (filter #(= :rf.warning/xray-load-theme-not-yet-implemented
                             (:operation %))
                         events)]
      (is (nil? result) "load-theme returns nil")
      (is (empty? stale)
          "no not-yet-implemented warning — the stub is gone")
      (is (nil? (core/load-theme ""))  "empty string is a safe no-op")
      (is (nil? (core/load-theme nil)) "nil is a safe no-op"))))

;; ---- init! contract ----------------------------------------------------

(deftest init!-no-opts-is-idempotent
  (testing "init! wires the four foundation side-effects; second call is no-op"
    ;; First call wires registry + trace-cb + epoch-cb + keybinding.
    ;; The keybinding listener requires js/window which the node-test
    ;; host does not expose; the attach call no-ops on that host (the
    ;; (when (exists? js/window) ...) guard inside keybinding/attach!).
    ;; The contract here is that init! runs to completion without
    ;; throwing and the registry's idempotency sentinel reports
    ;; installed.
    (core/init!)
    (is (some? (registrar/handler :sub :rf.xray/target-frame))
        "registry/register-xray-handlers! ran")
    ;; Second call: each sub-side-effect is defonce-guarded so the
    ;; combined effect is a no-op. We just assert no throw.
    (core/init!)
    (is true "second init! did not throw")))

(deftest init!-with-target-frame-dispatches-set-target-frame
  (testing "EP-0002 (rf2-bd4div) — init! threads :target-frame through to
            :rf.xray/set-target-frame (the legacy :default-frame opt is
            retired in favour of the distinct :target-frame vocabulary)"
    ;; The dispatch is async (queues into :rf/xray's router); we capture
    ;; the call to verify the facade routes through the registered event.
    ;; The dispatch-sync-driven landing is covered by
    ;; `set-target-frame-wires-target-frame` above.
    (setup-xray-frame!)
    (let [seen (atom [])]
      (with-redefs [rf/dispatch-impl (fn [ev & _opts] (swap! seen conj ev))]
        (core/init! {:target-frame :app/main}))
      (is (some #(= [:rf.xray/set-target-frame :app/main] %) @seen)
          "init! dispatched :rf.xray/set-target-frame with :target-frame"))))

(deftest init!-wires-theme-density-and-buffer-depths
  (testing "rf2-2thl2 — init! threads :theme / :density / :buffer-depths
            through to the persisted Settings shape so a host's boot-time
            opts land in the same slots the Settings popup writes."
    (setup-xray-frame!)
    (core/init! {:target-frame :app/main
                 :theme         :dark
                 :density       :compact
                 :buffer-depths {:epoch 75}})
    (is (= :dark    (config/get-setting :theme nil))
        ":theme landed in the persisted Settings shape")
    (is (= :compact (config/get-setting :general :density))
        ":density landed under :general")
    (is (= 75       (config/get-setting :general :epoch-history))
        ":buffer-depths :epoch landed under :general :epoch-history")))

(deftest init!-tolerates-unknown-opts-keys
  (testing "rf2-2thl2 — init! silently ignores keys it doesn't recognise
            (forward-compat: a host passing a key a future Xray release
            adds MUST NOT break the current Xray boot)."
    (setup-xray-frame!)
    (core/init! {:target-frame    :app/main
                 :unknown/future  :something
                 :rf.xray/another 42})
    (is true "init! did not throw on unknown keys")))

(deftest init!-buffer-depths-with-nil-epoch-is-noop
  (testing "rf2-2thl2 — init! ignores :buffer-depths shapes without a
            usable :epoch (nil, non-numeric, non-positive)."
    (setup-xray-frame!)
    (let [start (config/get-setting :general :epoch-history)]
      (core/init! {:buffer-depths {:trace 200}})
      (is (= start (config/get-setting :general :epoch-history))
          ":trace-only map left :epoch-history at the prior value")
      (core/init! {:buffer-depths {:epoch -5}})
      (is (= start (config/get-setting :general :epoch-history))
          "negative epoch depth was rejected by the apply-epoch-history! gate"))))
