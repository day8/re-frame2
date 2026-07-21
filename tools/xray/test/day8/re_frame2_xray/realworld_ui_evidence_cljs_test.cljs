(ns day8.re-frame2-xray.realworld-ui-evidence-cljs-test
  "S7-ENTRY PROOF 2/2 — the XRAY seam (rf2-vvdzx). Xray attached to the REAL
  realworld re-frame.ui app (`examples/real-apps/realworld_resources`, the P-1
  full-app variant) renders TRUE ViewCell evidence: the versioned
  `re-frame.ui.tool` projection consumed by
  `day8.re-frame2-xray.viewcell-evidence` is exercised end-to-end against the
  app's OWN compiled views — the evidence schema is consumed, and the panels
  populate with real data from the app.

  Xray is a CONSUMER here (no tool-UI rewrite): the app CONTRIBUTES the
  compiled-view manifests + subs; Xray OWNS the evidence projection and reads
  it. Everything asserted below is the app's real data — the smallest complete
  re-frame.ui view (`realworld-resources.ui-counter/counter`, a `defview`) is
  the fixture, so its `(sub [:ui-counter/count])` dependency and its literal
  `:on-click [:ui-counter/inc 1]` intent vectors are the app's own, not
  synthetic.

  COVERAGE SHAPE — a CLJS unit/contract test on the node runtime, wired into
  the EXISTING `npm run test:cljs` suite (NOT Playwright). The observation
  movement is driven through the reactive substrate primitives — the same
  genuine-observation-port technique the sibling `viewcell_evidence_cljs_test`
  uses — because the node host has no DOM; the ownership/receipt fence,
  version honesty and query path are identical to a mounted host.

  Two arms:
    1. The STATIC manifest projection (`view-sites`) carries the app's REAL
       subs, event-sites, source coords and capabilities from the versioned
       public `view-manifest` / `view-dependencies` / `view-event-sites`.
    2. A REAL invalidation movement of the app's `[:ui-counter/count]` sub
       surfaces in Xray's `rows` under the app's real defview identity, is
       version-recognised, and reaches the `:rf.xray/viewcell-evidence` sub the
       Views panel reads."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.tool :as tool]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.viewcell-evidence :as viewcell-evidence]
            ;; THE REAL APP (P-1). Requiring it runs the app's own compiled-view
            ;; defviews + plain-re-frame2 dataflow: the `counter` `defview`
            ;; registers its `:rf.ui/manifest` (source, subs, event-sites,
            ;; capabilities) and the `:ui-counter/*` events + `:ui-counter/count`
            ;; sub register at ns-load. Nothing here is authored by the test —
            ;; it is the app as a consumer of the Xray evidence surface.
            [realworld-resources.ui-counter :as counter]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture {})
  (fn [f]
    (reactive/reset-scheduler!)
    (viewcell-evidence/reset-for-test!)
    (try (f) (finally
               (reactive/reset-scheduler!)
               (viewcell-evidence/reset-for-test!)))))

(def ^:private fid :rf/default)

;; The app's REAL compiled-view identity — `(keyword (str *ns*) (str vname))`
;; for `(defview counter …)` in `realworld-resources.ui-counter`.
(def ^:private counter-view-id :realworld-resources.ui-counter/counter)

(defn- boot-xray!
  "The production init path (mirrors the sibling evidence test): register the
  Xray handlers, then `ensure-xray-frame!` — so the `:rf/xray` frame and the
  `:rf.xray/*` sub graph exist exactly as a real page load builds them."
  []
  (registry/register-xray-handlers!)
  (mount/ensure-xray-frame!))

(defn- rc!
  "Render (probe) `queries` under the host frame, then commit — the cell ends
  observing them through REAL subscription handles (the ui tool-evidence
  technique for driving genuine observation-port fan-out)."
  [cell queries]
  (let [[_ capture] (rf/with-frame fid
                      (reactive/with-capture
                       cell (fn [] (mapv (fn [i q]
                                          (reactive/sub-read [:vce/site i] q))
                                        (range) queries))))]
    (reactive/commit! cell capture))
  cell)

(defn- xray-query []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/viewcell-evidence])))

;; ===========================================================================
;; ARM 1 — the app's compiled-view manifest projects through `view-sites`
;; (the versioned static tier the Views panel's [code] chips + dependency
;; lists read)
;; ===========================================================================

(deftest the-real-app-view-manifest-projects-its-true-sites
  (testing "the app's `counter` defview manifest — its real subs, event-sites,
            source coords and capabilities — projects through the versioned
            public tier, NOT fabricated"
    (let [[site] (viewcell-evidence/view-sites [counter-view-id
                                                counter-view-id ; de-dup
                                                :legacy/unregistered])]
      (is (= counter-view-id (:view-id site))
          "the one registered compiled view, de-duplicated, unregistered id skipped")
      (is (re-find #"ui_counter" (:file (:source site)))
          "the [code] chip source points at the app's own ui_counter file")
      (is (set? (:capabilities site))
          "capabilities project as a set — a pure static counter claims no
           special powers, exactly what the compiled AST scan found")
      (is (some? (:template-fingerprint site))
          "a real compiled-template fingerprint rides the row")

      (testing "the app's REAL subscription dependency, read verbatim"
        (let [subs (:subscriptions (:dependencies site))]
          (is (= 1 (count subs)) "the counter reads exactly one sub")
          (is (= [:ui-counter/count] (:query (first subs)))
              "its `(sub [:ui-counter/count])` dependency, projected verbatim")
          (is (false? (:dynamic? (first subs)))
              "a literal query is honestly non-dynamic")))

      (testing "the app's REAL literal event-site intent vectors"
        (let [events   (:event-sites site)
              handlers (set (map :handler events))]
          (is (= 5 (count events))
              "the counter's five button/input handlers, one site each")
          (is (contains? handlers [:ui-counter/inc 1]) "the + button intent")
          (is (contains? handlers [:ui-counter/dec 1]) "the - button intent")
          (is (contains? handlers [:ui-counter/inc 5]) "the +5 button intent")
          (is (contains? handlers [:ui-counter/reset]) "the reset button intent")
          (let [inc-site (first (filter #(= [:ui-counter/inc 1] (:handler %)) events))]
            (is (= :literal (:site-kind inc-site))
                "a serializable literal handler vector IS its inspectable shape")))))
    (is (= [] (viewcell-evidence/view-sites [:legacy/unregistered]))
        "an id with no compiled-view manifest yields NO fabricated site")))

;; ===========================================================================
;; ARM 2 — a real invalidation movement of the app's count sub surfaces in
;; Xray's rows, version-recognised, through the Views-panel query path
;; ===========================================================================

(deftest a-real-movement-of-the-apps-count-sub-surfaces-in-xrays-rows
  (boot-xray!)
  (is (some? (viewcell-evidence/acquire!)) "Xray owns the evidence projection")
  ;; The app's own sub, seeded through the app's own key.
  (frame/replace-app-db! fid {:ui-counter/count 0})
  (let [cell (rc! (reactive/make-cell counter-view-id) [[:ui-counter/count]])]
    ;; A REAL port fan-out: re-registering the app's `:ui-counter/count` sub —
    ;; a hot-reload of the app's own dataflow — fires each committed site's
    ;; live handle `on-change` with the rich `:hmr` payload.
    (rf/reg-sub :ui-counter/count (fn [db _] (:ui-counter/count db 0)))
    (reactive/flush-pending!)

    (testing "the consumer projection carries the app's real bounded row"
      (let [rows (viewcell-evidence/rows)
            row  (first rows)]
        (is (= 1 (count rows)))
        (is (= counter-view-id (:view-id row))
            "the row is stamped with the app's OWN defview identity")
        (is (number? (:occurrence row)) "a stable occurrence ordinal")
        (is (= 1 (:count row)))
        (is (= 1 (:batches row)))
        (is (= #{:hmr} (:causes row)) "the real cause is projected")
        (is (= [[:sub fid [:ui-counter/count]]] (:targets row))
            "…with the app's REAL sub as the moving target — this is app data")
        (is (= :connected (:connection row)))
        (is (true? (:targets-exact? row)) "observation identity kept exact")
        (is (contains? row :lifecycle))))

    (testing "the versioned evidence schema is consumed + recognised"
      (let [{:keys [version supported?]} (viewcell-evidence/version-status)]
        (is (= tool/schema-version version)
            "the producer's evidence-schema version, read off the real app")
        (is (true? supported?) "…recognised by this Xray build")))

    (testing "the SAME app row surfaces through the Views panel's query path"
      (let [rows (xray-query)]
        (is (= (viewcell-evidence/rows) rows))
        (is (= counter-view-id (:view-id (first rows)))
            "`:rf.xray/viewcell-evidence` — the sub the Views panel reads —
             carries the app's real view identity")))

    (testing "teardown prunes the app row — released references, empty query"
      (reactive/teardown! cell)
      (is (= [] (viewcell-evidence/rows)))
      (frame/replace-app-db! :rf/xray {:epoch-history [::pumped]})
      (is (= [] (xray-query))))))
