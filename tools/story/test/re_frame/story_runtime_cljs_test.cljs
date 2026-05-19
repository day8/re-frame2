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
            [re-frame.story.loaders :as loaders]))

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
                (get-in db [:rf/machines machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

;; Per cljs.test: async tests require fixtures to be supplied in
;; map form — function-form fixtures can't suspend around the async
;; body. Wrap the reset fn as a map.
(use-fixtures :each {:before reset-all!})

;; ---- stage marker -------------------------------------------------------

(deftest cljs-stage-marker
  (testing "Stage 6 supersedes Stage 5 — the loaded CLJS surface advertises :sota-features"
    (is (= :sota-features story/stage))))

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
;; allocate and never engages for events-only variants like the
;; causa-rhs-smoke testbed's `:story.counter/loaded`.

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
            causa-rhs-smoke testbed variant shape on CLJS"
    (is (true?  (loaders/events-only-variant? {:events [[:counter/initialise 5]]}
                                              {:hiccup [] :frame-setup []
                                               :fx-override [] :errors []}))
        "the causa-rhs-smoke `:story.counter/loaded` body shape → events-only")
    (is (false? (loaders/events-only-variant? {:loaders [[:l]]} {}))
        ":loaders disqualifies")
    (is (false? (loaders/events-only-variant? {:loaders-complete-when :p?} {}))
        ":loaders-complete-when disqualifies")
    (is (false? (loaders/events-only-variant? {} {:frame-setup [{:body {}}]}))
        ":frame-setup decorators disqualify")))

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
    (story/configure! {:global-args {:theme :light}})
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
