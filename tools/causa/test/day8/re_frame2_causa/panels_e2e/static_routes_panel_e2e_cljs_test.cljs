(ns day8.re-frame2-causa.panels-e2e.static-routes-panel-e2e-cljs-test
  "Multi-frame end-to-end coverage for Causa's Static Routes panel
  (rf2-wj46n, spec/017 — Static Routes panel row).

  The original Playwright scenario `runStaticRoutesPanel` (recoverable
  from `git show 85b86a7b:tools/causa/testbeds/feature_matrix/scenarios.cjs`)
  exercised four observables:

    1. Static-mode opt-in via `configure!` + chord-into-Static.
    2. A synthetic 3-route catalogue injected via
       `:rf.causa/set-registered-routes-override-for-test`.
    3. Simulate-URL `/articles` resolves a WINNER candidate without
       mutating the host's `[:rf/route]` slot (hermetic preview).
    4. The `:rf.causa.static.routes/jump-to-runtime` cross-link flips
       mode → `:runtime` and opens the Runtime Routing tab.

  The Playwright surface was retired (browser-level chrome/scenario
  tests live in framework + Causa gates; the e2e tier is sub-layer).
  This test re-authors the same four assertions against Causa's
  `:rf.causa/*` sub graph in the multi-frame node-test harness used by
  the rest of `panels_e2e/`.

  ## Why sub layer, not view layer

  The pure-fn / view-tree coverage already lives in
  `static/routes/panel_cljs_test.cljs` (registry wiring, silent state,
  flat-list rendering, search filter, Simulate-URL row, expand toggle,
  hermetic preview, cross-link tab + mode flip). The rf2-wj46n row in
  the test-coverage matrix is the cross-frame e2e tier — proving that
  the WHOLE pipeline (host frame + `:rf/causa` frame + the override
  seam + the cross-link fx) survives the multi-frame fixture. We hit
  exactly the four observables the Playwright scenario watched."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

;; ---- synthetic 3-route catalogue ----------------------------------------
;;
;; Mirrors the synthetic routes the Playwright scenario fed into the
;; override seam — three routes with ascending specificity so the
;; rank cascade is exercised non-trivially.

(def synthetic-routes
  {:route/home           {:path "/"             :doc "Home page."}
   :route/articles       {:path "/articles"     :doc "Articles list."}
   :route/article-detail {:path "/articles/:id" :doc "Article detail."}})

(defn- install-override!
  "Inject the synthetic catalogue under `:rf/causa` via the test-only
  override seam. Production sources `:rf.causa/registered-routes` from
  `(rf/registrations :route)`; the override skips the framework's
  route registry so the test does not depend on routing-artefact
  registration order."
  []
  (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test
                     synthetic-routes]
                    {:frame :rf/causa}))

;; ---- (1) browse list — 3 rows from the override -------------------------

(deftest static-routes-browse-list-renders-all-3-override-rows
  (testing "tab-data composite surfaces all 3 routes from the override seam"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (install-override!)
        (let [data (e2e/sub-causa [:rf.causa.static.routes/tab-data])
              rows (:routes data)
              ids  (set (map :route-id rows))]
          (is (map? data)
              ":rf.causa.static.routes/tab-data did not return a map")
          (is (false? (:silent? data))
              ":silent? must be false when the override registers 3 routes")
          (is (= 3 (count rows))
              "browse list did not surface exactly 3 rows from the synthetic override")
          (is (= #{:route/home :route/articles :route/article-detail} ids)
              "browse list route-ids do not match the synthetic override catalogue"))))))

;; ---- (2) Simulate-URL — winner + hermetic --------------------------------

(deftest static-routes-simulate-url-resolves-winner-hermetically
  (testing "Simulate-URL `/articles` picks a winner candidate"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (install-override!)
        ;; Snapshot the host's :rf/route slot BEFORE simulating. The
        ;; counter fixture does not write to :rf/route, so we expect
        ;; nil here — and the same nil afterwards.
        (let [counter-before    (e2e/sub-host [:counter/value])
              host-route-before (some-> (rf/get-frame-db :rf/default) :rf/route)]
          (rf/dispatch-sync [:rf.causa.static.routes/set-sim-url "/articles"]
                            {:frame :rf/causa})
          (let [data       (e2e/sub-causa [:rf.causa.static.routes/tab-data])
                sim-result (:sim-result data)
                candidates (:candidates sim-result)
                winner-id  (:winner sim-result)
                winner     (first (filter :winner? candidates))]
            (is (some? sim-result)
                ":sim-result missing from tab-data after set-sim-url")
            (is (pos? (count candidates))
                "Simulate-URL `/articles` produced zero candidates")
            (is (= :route/articles winner-id)
                "winner is not :route/articles — rank cascade picked the wrong row")
            (is (true? (:winner? winner))
                "winner candidate not flagged :winner? true"))
          ;; Hermetic — the host's :rf/route slot is unchanged.
          (let [host-route-after (some-> (rf/get-frame-db :rf/default) :rf/route)]
            (is (= host-route-before host-route-after)
                "Simulate-URL was NOT hermetic — host :rf/route slot changed"))
          ;; Sanity — the host's counter app-db is also undisturbed.
          (is (= counter-before (e2e/sub-host [:counter/value]))
              "Simulate-URL leaked into the host's counter slot"))))))

;; ---- (3) → Runtime JUMP — mode flip + Routing tab opens -----------------

(deftest static-routes-jump-to-runtime-flips-mode-and-opens-routing-tab
  (testing "jump-to-runtime cross-link flips :static → :runtime + opens Routing"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (install-override!)
        ;; Start in :static mode on the Static Routes sub-tab — the
        ;; chord-into-Static-then-Routes shape the Playwright scenario
        ;; established.
        (rf/dispatch-sync [:rf.causa/set-mode :static] {:frame :rf/causa})
        (rf/dispatch-sync [:rf.causa.static/select-tab :routes] {:frame :rf/causa})
        (is (= :static (e2e/sub-causa [:rf.causa/mode]))
            "precondition failed — mode did not start at :static")
        ;; Dispatch the cross-link with a route-id payload (panel
        ;; ignores the id but the event-shape requires the vector form).
        (rf/dispatch-sync [:rf.causa.static.routes/jump-to-runtime
                           :route/articles]
                          {:frame :rf/causa})
        (is (= :runtime (e2e/sub-causa [:rf.causa/mode]))
            "mode did not flip to :runtime after jump-to-runtime")
        (is (= :routing (e2e/sub-causa [:rf.causa/selected-tab]))
            "Runtime Routing tab was not opened after jump-to-runtime")))))
