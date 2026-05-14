(ns day8.re-frame2-causa.core-cljs-test
  "Tests for `day8.re-frame2-causa.core` — the canonical user-facing
  facade promised by `spec/API.md` (rf2-13bx9).

  ## Three contract surfaces under test

  1. **Re-export identity.** The mount + config re-exports MUST be
     `def`-aliases (not wrapper fns) so `(= mount/open! core/open!)`
     holds. Identity is the contract: a host that wires
     `core/toggle!` into its keybinding catalogue must land on the
     same Var the preload's listener calls.

  2. **Frame / panel wiring.** `set-target-frame!` and
     `set-active-panel!` dispatch into the `:rf/causa` frame; the
     dispatch updates `:rf.causa/target-frame` /
     `:rf.causa/selected-panel` slots in Causa's app-db, so the
     companion subs re-fire and `active-frame` / `active-panel`
     read back the new value.

  3. **TBD stubs do not crash.** `popout!` and `load-theme` emit a
     `:rf.warning/*` trace event and return nil — host code that
     wires the call ahead of the impl must not throw."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.core :as core]
            [day8.re-frame2-causa.mount :as mount]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- setup-causa-frame!
  "Per-test boot: register handlers, allocate the :rf/causa frame."
  []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- (1) re-export identity --------------------------------------------

(deftest mount-fns-are-aliases
  (testing "mount entry points are def-aliases, not wrapper fns"
    ;; Identity check — the facade Var resolves to the same fn-value
    ;; as the underlying mount Var.
    (is (identical? mount/open!   core/open!))
    (is (identical? mount/close!  core/close!))
    (is (identical? mount/toggle! core/toggle!))))

(deftest config-fns-are-aliases
  (testing "config knob setters are def-aliases"
    (is (identical? config/configure!          core/configure!))
    (is (identical? config/set-editor!         core/set-editor!))
    (is (identical? config/set-show-sensitive! core/set-show-sensitive!))))

;; ---- (2) frame wiring --------------------------------------------------
;;
;; The facade's `set-target-frame!` calls `rf/dispatch` (async — the
;; user-facing contract; the runtime dispatch queues into the
;; `:rf/causa` frame's router so it lands inside the next drain). The
;; tests assert the same wiring contract via `dispatch-sync` so the
;; drain runs to completion inside the assertion — mirrors the pattern
;; the registry / shell tests use for `:rf.causa/select-panel` etc.

(deftest target-frame-default-is-rf-default
  (testing "target-frame defaults to :rf/default (per defaults/default-target-frame)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (= :rf/default (core/target-frame))))))

(deftest set-target-frame-wires-target-frame
  (testing ":rf.causa/set-target-frame updates the slot target-frame reads"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-target-frame :app/main])
      (is (= :app/main (core/target-frame))
          "after dispatch the facade's read returns the new value")
      (rf/dispatch-sync [:rf.causa/set-target-frame :worker/db])
      (is (= :worker/db (core/target-frame))
          "subsequent flips also land")
      (rf/dispatch-sync [:rf.causa/set-target-frame nil])
      (is (= :rf/default (core/target-frame))
          "nil resets to the default target frame"))))

(deftest set-target-frame!-dispatches-set-target-frame
  (testing "set-target-frame! dispatches :rf.causa/set-target-frame into :rf/causa"
    ;; The `rf/dispatch` macro expands to `re-frame.core/dispatch*`, so
    ;; with-redefs the underlying fn — redef'ing the macro Var would have
    ;; no effect on already-compiled call sites.
    (let [seen (atom [])]
      (with-redefs [rf/dispatch* (fn [ev & _opts] (swap! seen conj ev))]
        (core/set-target-frame! :app/main))
      (is (= [[:rf.causa/set-target-frame :app/main]] @seen)
          "facade dispatches the right event with the right arg"))))

;; ---- (2) panel wiring --------------------------------------------------

(deftest active-panel-default-is-event-detail
  (testing "active-panel defaults to :event-detail (per defaults/default-panel-id)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (= :event-detail (core/active-panel))))))

(deftest select-panel-wires-active-panel
  (testing ":rf.causa/select-panel updates the slot active-panel reads"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-panel :causality-graph])
      (is (= :causality-graph (core/active-panel)))
      (rf/dispatch-sync [:rf.causa/select-panel :time-travel])
      (is (= :time-travel (core/active-panel))))))

(deftest set-active-panel!-dispatches-select-panel
  (testing "set-active-panel! dispatches :rf.causa/select-panel into :rf/causa"
    (let [seen (atom [])]
      (with-redefs [rf/dispatch* (fn [ev & _opts] (swap! seen conj ev))]
        (core/set-active-panel! :causality-graph))
      (is (= [[:rf.causa/select-panel :causality-graph]] @seen)
          "facade dispatches the right event with the right arg"))))

;; ---- (3) TBD stubs — emit warning trace, return nil --------------------

(deftest popout!-emits-warning-trace
  (testing "popout! emits :rf.warning/causa-popout-not-yet-implemented and returns nil"
    (preload/register-trace-collector!)
    (let [result (core/popout!)
          events (trace-bus/buffer)
          popout-event (first (filter #(= :rf.warning/causa-popout-not-yet-implemented
                                          (:operation %))
                                      events))]
      (is (nil? result) "stub returns nil")
      (is (some? popout-event)
          "trace bus carries the warning emit")
      (is (= :causa (get-in popout-event [:tags :origin]))
          "warning is tagged :origin :causa"))))

(deftest load-theme-emits-warning-trace
  (testing "load-theme emits :rf.warning/causa-load-theme-not-yet-implemented and returns nil"
    (preload/register-trace-collector!)
    (let [result (core/load-theme ".foo { color: red; }")
          events (trace-bus/buffer)
          theme-event (first (filter #(= :rf.warning/causa-load-theme-not-yet-implemented
                                         (:operation %))
                                     events))]
      (is (nil? result) "stub returns nil")
      (is (some? theme-event)
          "trace bus carries the warning emit")
      (is (= :causa (get-in theme-event [:tags :origin]))
          "warning is tagged :origin :causa"))))

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
    (is (some? (registrar/handler :sub :rf.causa/selected-panel))
        "registry/register-causa-handlers! ran")
    ;; Second call: each sub-side-effect is defonce-guarded so the
    ;; combined effect is a no-op. We just assert no throw.
    (core/init!)
    (is true "second init! did not throw")))

(deftest init!-with-default-frame-dispatches-set-target-frame
  (testing "init! threads :default-frame through to :rf.causa/set-target-frame"
    ;; The dispatch is async (queues into :rf/causa's router); we capture
    ;; the call to verify the facade routes through the registered event.
    ;; The dispatch-sync-driven landing is covered by
    ;; `set-target-frame-wires-target-frame` above.
    (setup-causa-frame!)
    (let [seen (atom [])]
      (with-redefs [rf/dispatch* (fn [ev & _opts] (swap! seen conj ev))]
        (core/init! {:default-frame :app/main}))
      (is (some #(= [:rf.causa/set-target-frame :app/main] %) @seen)
          "init! dispatched :rf.causa/set-target-frame with :default-frame"))))

(deftest init!-accepts-future-opts-without-throwing
  (testing "init! tolerates spec/API.md keys not yet wired (forward-compat)"
    ;; Per the docstring: :theme / :density / :ai-provider /
    ;; :buffer-depths are accepted today and ignored at runtime. The
    ;; contract is that passing them does not throw — host code wired
    ;; against the spec stays runnable as the impl fills in.
    (setup-causa-frame!)
    (core/init! {:default-frame :app/main
                 :theme         :dark
                 :density       :compact
                 :ai-provider   {:provider :claude}
                 :buffer-depths {:trace 200 :epoch 50}})
    (is true "init! did not throw on the full spec/API.md opts map")))
