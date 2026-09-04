(ns day8.re-frame2-xray.panels-e2e.reactive-data-view-rows-dom-cljs-test
  "Real-substrate browser e2e for the Views panel's reactive-data
  render projection (rf2-yr6wtx · rf2-vxgfnd.22 Finding B follow-up ·
  spec/021 §3).

  ## The gap this closes

  The sibling `panels-e2e/reactive-data-e2e-cljs-test` drives the
  `:sub-runs` → `:subs-ran` axis through the REAL substrate and is
  redden-proven on a `capture.cljc/sub-run-row` key rename. But it runs
  the PLAIN-ATOM substrate (Node CLJS, no React), so no view ever mounts
  and no `:rf.view/rendered` op fires — the render half of the pipeline
  (`capture.cljc/render-row` → the epoch record's `:renders` →
  `reactive-panel-subs/project-record`) was validated only by
  hand-built epoch records and by absence. A `render-row` key rename in
  `capture.cljc` reddened NO e2e — the rf2-wyvf2 class one level up on the
  render axis.

  This closes it with a REAL React mount: the Reagent adapter mounts a
  view via `reagent.dom.client/create-root` into a real DOM node. The
  mount's `:rf.view/rendered` op fires at React commit time — AFTER the
  causing cascade settled — and the framework's post-settle render
  back-fill (`epoch.listeners/record-render!`, the `:epoch/record-render!`
  hook) attributes it into the head epoch's `:renders` (via
  `capture.cljc/render-row`) and its `:trace-events`. We then mirror the
  corrected epoch ring into Xray's `:epoch-history` (the same sync the
  production epoch-collector does) and read `:rf.xray/reactive-data`,
  asserting on CONTENT projected from that real render.

  ## Why a MOUNT render (and why it lands in the head epoch)

  We dispatch `[:counter/inc]` FIRST (settling the head epoch, no view
  mounted yet), THEN mount the view. React commits the mount post-settle,
  so the mount render's `:rf.view/rendered` and the fresh
  `:counter/value` sub recompute both back-fill into the head epoch
  (`last-settled`) — the deterministic, synchronous real-render signal a
  browser test can pin without racing React's rAF re-render scheduler. (A
  dispatch-driven RE-render of an ALREADY-mounted view is not reliably
  attributable under `dispatch-sync`: Reagent recomputes the view's
  reaction WHILE the cascade is still in flight, so the reactive sub-run —
  and the render that follows it — anchor to the PRIOR epoch, not the
  dispatched one. That timing is exercised faithfully by production's rAF
  scheduler, out of a synchronous test's reach.) The mount render is a
  real `:rf.view/rendered` op that exercises `render-row` end to end —
  exactly the projection this gap was about.

  ## Two projection slots, one real render — and which mutation-proofs what

  `:rf.xray/reactive-data` carries two render-derived slots (see
  `reactive-panel-subs` ns docstring):

    - `:views-rendered` is the `capture.cljc/render-row` projection
      (`:renders` record slot, `:view-id` = `(first :render-key)`). THIS
      is the `render-row` mutation-proof target: a `render-row` output-key
      rename in `capture.cljc` (e.g. `:render-key` → `:renderkey`, or
      `:mount?` → `:mountp`) makes the projected row lack that key (and its
      `:view-id`, sourced from `(first :render-key)`, goes nil), so
      `reactive-data-render-row-projects-real-mounted-render` reddens.
    - `:view-rows` is the panel-facing table row (action / reason /
      triggered-by), re-sourced from the `:rf.view/rendered` op off
      `:trace-events` (NOT `render-row`). It proves the REAL render
      pipeline reaches the panel with the right view-id + reactive reason;
      it does not itself mutation-proof `render-row`.

  Both are asserted below so the test pins the panel content AND the
  render-row projection against one real mounted render.

  ## Mutation-proof (rf2-yr6wtx / rf2-vxgfnd.22 fix-PR protocol)

  Renaming any `render-row` output key in
  `implementation/epoch/src/re_frame/epoch/capture.cljc` — e.g.
  `:render-key` → `:renderkey`, or `:mount?` → `:mountp` — reddens
  `reactive-data-render-row-projects-real-mounted-render`: the projected
  `:views-rendered` row then lacks that key (and its `:view-id`, sourced
  from `(first :render-key)`, goes nil). Verified by mutating the key,
  running this ns under the `:browser-test` build, then reverting.

  ## Test target

  This file's name ends in `_dom_cljs_test.cljs` so it runs under the
  `:browser-test` build (real DOM / React via Chromium) per
  `implementation/shadow-cljs.edn`. The `:node-test` build's `cljs-test$`
  regex ALSO matches the ns suffix, so it loads under Node too — where the
  body short-circuits via `(browser?)` (no `js/document` / `react-dom`
  mount) and the real assertions run only under Chromium."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

;; Reagent adapter (not plain-atom) — a real React mount is the whole
;; point of this file. The default ambient `:rf/default` scope is kept
;; (NOT `:ambient-frame nil`): the host frame IS `:rf/default` and the
;; view mounts under a `frame-provider {:frame :rf/default}`, so the
;; ambient scope and the React-context tier resolve to the SAME frame —
;; and the counter fixture's frameless `[:counter/initialise]` boot
;; dispatch still resolves against the ambient scope.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.adapter.reagent/adapter}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- mount-counter-view-in-head-epoch!
  "Drive a REAL cascade + a REAL React mount, leaving the head epoch
  carrying a real `:rf.view/rendered` for `:vr-e2e/counter`. Returns the
  live DOM container (caller unmounts + removes it).

  Ordering is deliberate: dispatch `[:counter/inc]` FIRST (head epoch
  settles at `:counter/value` = 6, no view mounted), THEN mount — so the
  post-settle mount render + fresh sub recompute both back-fill into that
  head epoch. Finally re-mirror the corrected ring into Xray."
  []
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    ;; A view whose ONLY dependency is the host's `:counter/value` sub —
    ;; a Level-1 reader, so its render reason is unambiguous.
    (rf/reg-view* :vr-e2e/counter
                  (fn counter-view []
                    [:div {:data-testid "vr-counter"}
                     @(rf/subscribe [:counter/value])]))
    ;; Settle the head epoch BEFORE the mount.
    (rf/dispatch-sync [:counter/inc] {:frame e2e/default-host-frame})
    ;; Real React mount, committed synchronously via flushSync (React 19
    ;; root.render is otherwise async).
    (react-dom/flushSync
      (fn []
        (rdc/render root
                    [rf/frame-provider {:frame e2e/default-host-frame}
                     [(rf/view :vr-e2e/counter)]])))
    ;; Mirror the corrected epoch ring + trace into Xray's app-db (the
    ;; production epoch-collector's sync).
    (e2e/sync-xray-trace-mirror!)
    (e2e/sync-xray-epoch-history!)
    {:container container :root root}))

(deftest reactive-data-render-row-projects-real-mounted-render
  (testing "rf2-yr6wtx — a REAL React mount lands a `:rf.view/rendered` op
            that the substrate's `render-row` projects into the head
            epoch's `:renders`; the Views panel's `:rf.xray/reactive-data`
            `:views-rendered` slot names the real mounted view. Reddens on
            a `render-row` key rename in capture.cljc (NO injection seam)."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (e2e/with-host-and-xray-frames
        {:install-host counter/install-and-init!}
        (fn []
          (let [{:keys [container root]} (mount-counter-view-in-head-epoch!)]
            (try
              (is (= "6" (.-textContent container))
                  "the mounted view rendered the real :counter/value (6)")
              (let [d              (e2e/sub-xray [:rf.xray/reactive-data])
                    views-rendered (:views-rendered d)]
                (is (seq views-rendered)
                    (str ":views-rendered is EMPTY — no real :rf.view/rendered "
                         "captured into the focused epoch's :renders (the render "
                         "half of the rf2-wyvf2 class). reactive-data: "
                         (pr-str (select-keys d [:views-rendered :view-rows
                                                 :counts :has-event-bundle?]))))
                (let [row (some #(when (= :vr-e2e/counter (:view-id %)) %)
                                views-rendered)]
                  ;; :view-id is (first :render-key) in project-record, so a
                  ;; :render-key rename in render-row nils it → row is nil here.
                  (is (some? row)
                      (str ":views-rendered does not name the real mounted "
                           ":vr-e2e/counter view — render-row projection broke "
                           "(key rename?). views-rendered: " (pr-str views-rendered)))
                  ;; render-row output keys the projection carries — each is a
                  ;; rename target:
                  (is (= :vr-e2e/counter (first (:render-key row)))
                      (str "the :renders row carries the real :render-key tuple "
                           "(a :render-key rename in render-row reddens here). "
                           "row: " (pr-str row)))
                  (is (true? (:mount? row))
                      (str "the mount render row carries :mount? true (a :mount? "
                           "rename in render-row reddens here). row: "
                           (pr-str row))))
                (is (pos? (-> d :counts :views-rendered))
                    ":counts :views-rendered tallies the real render-set"))
              (finally
                (try (.unmount root) (catch :default _ nil))
                (.remove container)))))))))

(deftest reactive-data-view-rows-names-real-mounted-render
  (testing "rf2-yr6wtx — the panel-facing `:view-rows` names the real
            mounted view, its `:mount` action, and the reactive reason
            (`:counter/value`) — projected from the SAME real render,
            re-sourced off `:trace-events`. Proves the real render pipeline
            reaches the Views panel with correct content."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (e2e/with-host-and-xray-frames
        {:install-host counter/install-and-init!}
        (fn []
          (let [{:keys [container root]} (mount-counter-view-in-head-epoch!)]
            (try
              (let [d         (e2e/sub-xray [:rf.xray/reactive-data])
                    view-rows (:view-rows d)]
                (is (seq view-rows)
                    (str ":view-rows is EMPTY — no real :rf.view/rendered op in "
                         "the focused epoch's :trace-events. reactive-data: "
                         (pr-str (select-keys d [:view-rows :counts
                                                 :has-event-bundle?]))))
                (let [row (some #(when (= :vr-e2e/counter (:view-id %)) %)
                                view-rows)]
                  (is (some? row)
                      (str ":view-rows does not name the real mounted "
                           ":vr-e2e/counter view. view-rows: " (pr-str view-rows)))
                  (is (= :mount (:action row))
                      (str "the real React mount is a :mount action. row: "
                           (pr-str row)))
                  ;; reason is computed from the view's :rf.view/deref-subs ∩
                  ;; the epoch's value-changed subs. The mount deref'd
                  ;; :counter/value, whose fresh recompute reports value-changed
                  ;; into the head epoch's :sub-runs.
                  (is (= {:kind :reactive :subs [:counter/value]} (:reason row))
                      (str "the render's reason names the real read sub "
                           "(:counter/value). row: " (pr-str row)))))
              (finally
                (try (.unmount root) (catch :default _ nil))
                (.remove container)))))))))
