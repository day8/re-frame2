(ns day8.re-frame2-xray.panels-e2e.static-routes-panel-e2e-cljs-test
  "Multi-frame end-to-end coverage for Xray's Static Routes panel
  (rf2-wj46n, spec/017 — Static Routes panel row).

  The original Playwright scenario `runStaticRoutesPanel` (recoverable
  from `git show 85b86a7b:tools/xray/testbeds/feature_matrix/scenarios.cjs`)
  exercised four observables:

    1. Static-mode opt-in via `configure!` + chord-into-Static.
    2. A synthetic 3-route catalogue injected via
       `:rf.xray/set-registered-routes-override-for-test`.
    3. Simulate-URL `/articles` resolves a WINNER candidate without
       mutating the host's runtime-db route slice at
       `[:rf.runtime/routing :current]` (hermetic preview). EP-0001
       (rf2-vzld77) moved the framework-owned route slice into
       runtime-db; the guard compares that slice (via
       `(:rf.db/runtime (rf/frame-state-value id))`, rf2-t3lftq —
       API-shrink #3 retired the dedicated `rf/runtime-db-value` reader)
       so a future accidental navigation / runtime-db write can't slip
       past by leaving only the retired app-db path untouched.
    4. The `:rf.xray.static.routes/jump-to-dynamic` cross-link flips
       mode → `:dynamic` and opens the Dynamic Routing tab.

  The Playwright surface was retired (browser-level chrome/scenario
  tests live in framework + Xray gates; the e2e tier is sub-layer).
  This test re-authors the same four assertions against Xray's
  `:rf.xray/*` sub graph in the multi-frame node-test harness used by
  the rest of `panels_e2e/`.

  ## Why sub layer, not view layer

  The pure-fn / view-tree coverage already lives in
  `static/routes/panel_cljs_test.cljs` (registry wiring, silent state,
  flat-list rendering, search filter, Simulate-URL row, expand toggle,
  hermetic preview, cross-link tab + mode flip). The rf2-wj46n row in
  the test-coverage matrix is the cross-frame e2e tier — proving that
  the WHOLE pipeline (host frame + `:rf/xray` frame + the override
  seam + the cross-link fx) survives the multi-frame fixture. We hit
  exactly the four observables the Playwright scenario watched."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

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
  "Inject the synthetic catalogue under `:rf/xray` via the test-only
  override seam. Production sources `:rf.xray/registered-routes` from
  `(rf/registrations :route)`; the override skips the framework's
  route registry so the test does not depend on routing-artefact
  registration order."
  []
  (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test
                     synthetic-routes]
                    {:frame :rf/xray}))

;; ---- (1) browse list — 3 rows from the override -------------------------

(deftest static-routes-browse-list-renders-all-3-override-rows
  (testing "tab-data composite surfaces all 3 routes from the override seam"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (install-override!)
        (let [data (e2e/sub-xray [:rf.xray.static.routes/tab-data])
              rows (:routes data)
              ids  (set (map :route-id rows))]
          (is (map? data)
              ":rf.xray.static.routes/tab-data did not return a map")
          (is (false? (:silent? data))
              ":silent? must be false when the override registers 3 routes")
          (is (= 3 (count rows))
              "browse list did not surface exactly 3 rows from the synthetic override")
          (is (= #{:route/home :route/articles :route/article-detail} ids)
              "browse list route-ids do not match the synthetic override catalogue"))))))

;; ---- (2) Simulate-URL — winner + hermetic --------------------------------

(deftest static-routes-simulate-url-resolves-winner-hermetically
  (testing "Simulate-URL `/articles` picks a winner candidate"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (install-override!)
        ;; Snapshot the host's REAL current-route slice BEFORE simulating.
        ;; EP-0001 (rf2-vzld77): real navigation writes the route slice to
        ;; the host frame's RUNTIME-DB at [:rf.runtime/routing :current],
        ;; not app-db. The counter fixture does not navigate, so we expect
        ;; nil here — and the same nil afterwards. Reading the runtime-db
        ;; path (not the retired app-db path) is what makes the guard
        ;; catch a future accidental runtime-db write.
        (let [counter-before    (e2e/sub-host [:counter/value])
              host-route-before (some-> (:rf.db/runtime (rf/frame-state-value :rf/default))
                                        (get-in [:rf.runtime/routing :current]))]
          (rf/dispatch-sync [:rf.xray.static.routes/set-sim-url "/articles"]
                            {:frame :rf/xray})
          (let [data       (e2e/sub-xray [:rf.xray.static.routes/tab-data])
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
          ;; Hermetic — the host's real runtime-db route slice is unchanged.
          (let [host-route-after (some-> (:rf.db/runtime (rf/frame-state-value :rf/default))
                                          (get-in [:rf.runtime/routing :current]))]
            (is (= host-route-before host-route-after)
                "Simulate-URL was NOT hermetic — host runtime-db route slice changed"))
          ;; Sanity — the host's counter app-db is also undisturbed.
          (is (= counter-before (e2e/sub-host [:counter/value]))
              "Simulate-URL leaked into the host's counter slot"))))))

;; ---- (3) → Dynamic JUMP — mode flip + Routing tab opens -----------------

(deftest static-routes-jump-to-dynamic-flips-mode-and-opens-routing-tab
  (testing "jump-to-dynamic cross-link flips :static → :dynamic + opens Routing"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (install-override!)
        ;; Start in :static mode on the Static Routes sub-tab — the
        ;; chord-into-Static-then-Routes shape the Playwright scenario
        ;; established.
        (rf/dispatch-sync [:rf.xray/set-mode :static] {:frame :rf/xray})
        (rf/dispatch-sync [:rf.xray.static/select-tab :routes] {:frame :rf/xray})
        (is (= :static (e2e/sub-xray [:rf.xray/mode]))
            "precondition failed — mode did not start at :static")
        ;; Dispatch the cross-link with a route-id payload (panel
        ;; ignores the id but the event-shape requires the vector form).
        (rf/dispatch-sync [:rf.xray.static.routes/jump-to-dynamic
                           :route/articles]
                          {:frame :rf/xray})
        (is (= :dynamic (e2e/sub-xray [:rf.xray/mode]))
            "mode did not flip to :dynamic after jump-to-dynamic")
        (is (= :routing (e2e/sub-xray [:rf.xray/selected-tab]))
            "Dynamic Routing tab was not opened after jump-to-dynamic")))))
