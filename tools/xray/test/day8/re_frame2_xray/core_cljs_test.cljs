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
            [day8.re-frame2-xray.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-bus/clear-buffer!))

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
    (is (identical? config/set-show-sensitive! core/set-show-sensitive!))))

;; ---- (2) frame wiring --------------------------------------------------
;;
;; The facade's `set-target-frame!` calls `rf/dispatch` (async — the
;; user-facing contract; the runtime dispatch queues into the
;; `:rf/xray` frame's router so it lands inside the next drain). The
;; tests assert the same wiring contract via `dispatch-sync` so the
;; drain runs to completion inside the assertion — mirrors the pattern
;; the registry / shell tests use for the rest of the `:rf.xray/*`
;; surface.

(deftest target-frame-default-is-rf-default
  (testing "target-frame defaults to :rf/default (per defaults/default-target-frame)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (= :rf/default (core/target-frame))))))

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
      (is (= :rf/default (core/target-frame))
          "nil resets to the default target frame"))))

(deftest set-target-frame!-dispatches-set-target-frame
  (testing "set-target-frame! dispatches :rf.xray/set-target-frame into :rf/xray"
    ;; The `rf/dispatch` macro expands to `re-frame.core/dispatch*`, so
    ;; with-redefs the underlying fn — redef'ing the macro Var would have
    ;; no effect on already-compiled call sites.
    (let [seen (atom [])]
      (with-redefs [rf/dispatch* (fn [ev & _opts] (swap! seen conj ev))]
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
          events (trace-bus/buffer)
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

(deftest init!-with-default-frame-dispatches-set-target-frame
  (testing "init! threads :default-frame through to :rf.xray/set-target-frame"
    ;; The dispatch is async (queues into :rf/xray's router); we capture
    ;; the call to verify the facade routes through the registered event.
    ;; The dispatch-sync-driven landing is covered by
    ;; `set-target-frame-wires-target-frame` above.
    (setup-xray-frame!)
    (let [seen (atom [])]
      (with-redefs [rf/dispatch* (fn [ev & _opts] (swap! seen conj ev))]
        (core/init! {:default-frame :app/main}))
      (is (some #(= [:rf.xray/set-target-frame :app/main] %) @seen)
          "init! dispatched :rf.xray/set-target-frame with :default-frame"))))

(deftest init!-accepts-future-opts-without-throwing
  (testing "init! tolerates spec/API.md keys not yet wired (forward-compat)"
    ;; Per the docstring: :theme / :density / :ai-provider /
    ;; :buffer-depths are accepted today and ignored at runtime. The
    ;; contract is that passing them does not throw — host code wired
    ;; against the spec stays runnable as the impl fills in.
    (setup-xray-frame!)
    (core/init! {:default-frame :app/main
                 :theme         :dark
                 :density       :compact
                 :ai-provider   {:provider :claude}
                 :buffer-depths {:trace 200 :epoch 50}})
    (is true "init! did not throw on the full spec/API.md opts map")))
