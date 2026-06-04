(ns re-frame.story-runtime-cljs-test
  "CLJS smoke tests for re-frame2-story Stage 3 (rf2-von3).

  The bulk of runtime coverage lives in the JVM test ns
  (`re-frame.story-runtime-test`) — args precedence, decorator
  composition, snapshot-identity, lifecycle state-machine — all of
  which run faster on the JVM with no Reagent / DOM dependencies.

  This namespace covers the CLJS-specific surface: that the runtime
  compiles under CLJS, that `run-variant` returns a `js/Promise`,
  and that `snapshot-identity` produces stable hex hashes on both
  hosts (the matching JVM test asserts identical hashes; the CLJS
  smoke just confirms the function runs)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.machines :as machines]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.async :as async-lib]
            [re-frame.story.loaders :as loaders]
            [re-frame.story.play.runner-events :as re]))

;; ---- fixtures ------------------------------------------------------------
;;
;; CLJS doesn't allow runtime `require`, so the JVM fixture's
;; `(require 're-frame.machines :reload)` step (which re-installs the
;; machines artefact's reg-subs / event handlers after a registrar
;; clear-all!) needs a different shape on CLJS. The CLJS approach
;; manually re-runs the side-effecting parts of the machines ns.
;; Since CLJS test isolation between deftests is less stringent than
;; the JVM corpus (no per-test require :reload), we tolerate a
;; non-empty registrar carrying over between tests.

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  ;; Seat the plain-atom adapter. `rf/init!` is idempotent at the
  ;; install-adapter! layer (the test build may have seated it
  ;; already via another suite's boot).
  (try (rf/init! plain-atom/adapter)
       (catch :default _ nil))
  ;; Re-register the machines artefact's framework-shipped sub
  ;; (`:rf/machine`) after the registrar clear. The JVM equivalent
  ;; uses `(require 're-frame.machines :reload)` which is unavailable
  ;; in CLJS — we manually re-invoke the side-effecting part.
  (rf/reg-sub :rf/machine
              (fn [db [_ machine-id]]
                (get-in db [:rf/runtime :machines :snapshots machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

;; Per cljs.test: async tests require fixtures to be supplied in
;; map form — function-form fixtures can't suspend around the async
;; body. Wrap the reset fn as a map.
(use-fixtures :each {:before reset-all!})

;; ---- run-variant returns a Promise --------------------------------------

(deftest cljs-run-variant-returns-promise
  (testing "run-variant returns a js/Promise"
    (rf/reg-event-db :test/inc
      (fn [db _] (update db :counter (fnil inc 0))))
    (story/reg-variant :story.cljs.run/v
      {:events [[:test/inc] [:test/inc]]})
    (let [p (story/run-variant :story.cljs.run/v)]
      (is (async-lib/promise? p))
      (async done
        (-> p
            (async-lib/then
              (fn [r]
                (is (= :story.cljs.run/v (:frame r)))
                (is (= :ready            (:lifecycle r)))
                (is (= 2                 (:counter (:app-db r))))
                (story/destroy-variant! :story.cljs.run/v)
                (done))))))))

;; ---- rf2-043cm — events-only fast-path on CLJS --------------------------
;;
;; The JVM-side `re-frame.story-runtime-test` covers the lifecycle
;; transitions exhaustively. This CLJS smoke pins the cross-host
;; contract: dispatching the new `:mount-ready` event drives the
;; lifecycle from `:pre-mount` directly to `:ready` on CLJS too, so
;; the canvas's loading skeleton (rf2-0s4p1) reads `:ready` post-
;; allocate and never engages for events-only variants like
;; counter_with_stories' `:story.counter/events-only-loaded` (the
;; canonical events-only loader-body shape preserved from the retired
;; xray-rhs-smoke testbed per rf2-9jfo1.2).

(deftest cljs-events-only-fast-path-to-ready
  (testing "rf2-043cm — events-only variant lands :ready directly on
            CLJS too. Drives the regression's repro shape: a variant
            body declaring only `:events`, with no `:loaders` / no
            `:frame-setup` decorators / no `:loaders-complete-when`."
    (rf/reg-event-db :test.eo/seed
      (fn [db _] (assoc db :seeded? true)))
    (story/reg-variant :story.cljs.eo/v
      {:events [[:test.eo/seed]]})
    (let [p (story/run-variant :story.cljs.eo/v)]
      (async done
        (-> p
            (async-lib/then
              (fn [r]
                (is (= :ready  (:lifecycle r))
                    "events-only variant lands :ready")
                (is (true? (:seeded? (:app-db r)))
                    "events still dispatched after the fast-path mount")
                (is (empty? (:assertions r))
                    "no `:rf.error/loader-incomplete` projection on the fast-path")
                (story/destroy-variant! :story.cljs.eo/v)
                (done))))))))

(deftest cljs-events-only-classifier
  (testing "rf2-043cm — `loaders/events-only-variant?` classifies the
            canonical events-only loader-body shape on CLJS"
    (is (true?  (loaders/events-only-variant? {:events [[:counter/initialise 5]]}
                                              {:hiccup [] :frame-setup []
                                               :fx-override [] :errors []}))
        "the counter_with_stories `:story.counter/events-only-loaded`
         body shape (preserved from the retired xray-rhs-smoke
         testbed per rf2-9jfo1.2) → events-only")
    (is (false? (loaders/events-only-variant? {:loaders [[:l]]} {}))
        ":loaders disqualifies")
    (is (false? (loaders/events-only-variant? {:loaders-complete-when :p?} {}))
        ":loaders-complete-when disqualifies")
    (is (false? (loaders/events-only-variant? {} {:frame-setup [{:body {}}]}))
        ":frame-setup decorators disqualify")))

;; ---- rf2-9x5fm — the run-variant promise resolves even when the play -----
;;      runner aborts mid-:wait (frame torn down during the async yield) ----
;;
;; The defect: `runner-events/run-loop!` had two silent-abort branches that
;; returned WITHOUT invoking done-cb — the `(nil? state)` branch (frame torn
;; down mid-run) and the token-mismatch branch (a concurrent run! took over).
;; When either fired during an async `:wait` yield (CLJS `js/setTimeout`), the
;; play-promise never resolved → `run-phase-4!`'s continuation never fired →
;; `finalise-run!`'s `then` never fired → the outer `run-variant` promise hung
;; FOREVER (it chains only `then`, never `catch` / a timeout).
;;
;; This is the end-to-end regression guard for the ACTUAL failing path: a
;; variant whose play has a `:wait` step, with the frame destroyed during the
;; wait yield. Before the fix this test would NEVER call `done` and the
;; cljs.test async runner would time out (a red hang). With the fix the
;; aborted run still settles, so `run-variant` resolves and `done` fires.

(deftest cljs-run-variant-resolves-when-frame-torn-down-mid-wait
  (testing "rf2-9x5fm — tearing the variant frame down DURING a play's
            `:wait` yield must still resolve the run-variant promise; the
            aborted run loop settles its continuation instead of hanging"
    (rf/reg-event-db :test.hang/touch
      (fn [db _] (update db :n (fnil inc 0))))
    (story/reg-variant :story.cljs.hang/torn-down
      {:events      []
       :play-script {:script [[:dispatch-sync [:test.hang/touch]]
                              ;; The async yield window: the frame is
                              ;; destroyed while this :wait's setTimeout is
                              ;; pending, so the run loop resumes onto a
                              ;; vanished run-state.
                              [:wait 60]
                              [:dispatch-sync [:test.hang/touch]]]}})
    (async done
      (let [p (story/run-variant :story.cljs.hang/torn-down)]
        ;; Destroy the frame mid-:wait (after run-phase-4! has scheduled the
        ;; wait's setTimeout, before it resumes). This wipes the run-state
        ;; slot (`clear-state!` via the :drop-run-state teardown hook), so the
        ;; resuming loop hits the `(nil? state)` abort branch.
        (js/setTimeout #(story/destroy-variant! :story.cljs.hang/torn-down) 15)
        (-> p
            (async-lib/then
              (fn [r]
                (is (map? r)
                    "the run-variant promise RESOLVED — the torn-down-mid-wait
                     run settled instead of hanging forever (rf2-9x5fm)")
                (done))))))))

(deftest cljs-run-variant-resolves-when-concurrent-run-takes-over-mid-wait
  (testing "rf2-9x5fm — a concurrent `run!` that takes over the run-state slot
            (token swap, rf2-ftow6) DURING a play's `:wait` yield must still
            resolve the original run-variant promise; the stale loop settles
            its own continuation rather than stranding the chain"
    (rf/reg-event-db :test.hang2/touch
      (fn [db _] (update db :n (fnil inc 0))))
    (story/reg-variant :story.cljs.hang/token-swap
      {:events      []
       :play-script {:auto-run? false
                     :script [[:dispatch-sync [:test.hang2/touch]]
                              [:wait 60]
                              [:dispatch-sync [:test.hang2/touch]]]}})
    (async done
      (let [p (story/run-variant :story.cljs.hang/token-swap)]
        (-> p
            (async-lib/then
              (fn [r]
                (is (map? r)
                    "the original run-variant promise RESOLVED even though a
                     concurrent run! swapped the run-state token mid-:wait —
                     the stale loop's continuation settled (rf2-9x5fm)")
                (story/destroy-variant! :story.cljs.hang/token-swap)
                (done))))
        ;; Mid-:wait, fire a concurrent runner-events/run! for the SAME
        ;; variant. It stamps a fresher :run-token onto the shared slot, so
        ;; when the original run's loop resumes it sees a token mismatch and
        ;; aborts — but must still settle ITS continuation. The concurrent run
        ;; itself is allowed to complete; we only assert the ORIGINAL promise
        ;; resolves.
        (js/setTimeout
          #(re/run! :story.cljs.hang/token-swap)
          15)))))

;; ---- snapshot-identity --------------------------------------------------

(deftest cljs-snapshot-identity-shape
  (testing "snapshot-identity produces an 8-char hex hash"
    (story/reg-story :story.cljs.id
      {:component :app/v :args {:a 1}})
    (story/reg-variant :story.cljs.id/v
      {:events [[:init]] :tags #{:dev}})
    (let [s (story/snapshot-identity :story.cljs.id/v
                                     {:substrate :reagent})]
      (is (= :story.cljs.id/v (:variant-id s)))
      (is (string?            (:content-hash s)))
      (is (re-matches #"[0-9a-f]{8}" (:content-hash s))
          "content-hash is unsigned fixed-width lowercase hex"))))

;; ---- args precedence ----------------------------------------------------

(deftest cljs-resolve-args-precedence
  (testing "args precedence chain works on CLJS"
    (story/configure! {:rf.story/global-args {:theme :light}})
    (story/reg-story :story.cljs.args
      {:args {:label "story"}})
    (story/reg-variant :story.cljs.args/v
      {:args {:label "variant"} :events []})
    (let [r (story/resolve-args :story.cljs.args/v
                                {:cell-overrides {:icon :star}})]
      (is (= :light    (:theme r)))
      (is (= "variant" (:label r)))
      (is (= :star     (:icon r))))))

;; ---- decorator composition ----------------------------------------------

(deftest cljs-decorator-resolution
  (testing "decorator resolution works on CLJS"
    (story/reg-decorator :centered
      {:kind :hiccup
       :wrap (fn [body _] [:div.centered body])})
    (story/reg-variant :story.cljs.dec/v
      {:decorators [[:centered]]
       :events     []})
    (let [r (story/resolve-decorators :story.cljs.dec/v)]
      (is (= 1 (count (:hiccup r))))
      (is (= :centered (-> r :hiccup first :id))))))
