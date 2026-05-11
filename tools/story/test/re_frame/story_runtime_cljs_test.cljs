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
  (machines/reset-counters!)
  (loaders/clear-watchers!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

;; Per cljs.test: async tests require fixtures to be supplied in
;; map form — function-form fixtures can't suspend around the async
;; body. Wrap the reset fn as a map.
(use-fixtures :each {:before reset-all!})

;; ---- stage marker -------------------------------------------------------

(deftest cljs-stage-marker
  (testing "Stage 4 supersedes Stage 3 — the loaded CLJS surface advertises :render-shell"
    (is (= :render-shell story/stage))))

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
      (is (= 8 (count (:content-hash s)))))))

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
