(ns re-frame.freehand.release-app-interpreted
  "The entry of the INTERPRETED-ONLY twin of the Freehand release build
  (`:freehand-release-interpreted`).

  It is `re-frame.freehand.release-app` with every view held on the
  interpreted paved path — no `{:compiled true}` anywhere. The mixed
  release build carries one interpreted leaf and its compiled twin; this
  one carries the same two leaves, both interpreted, under the same
  interpreted root. Its compiled sibling
  (`re-frame.freehand.release-app-compiled`) flips exactly those three
  markers and nothing else, so the byte difference between the two bundles
  is attributable to lowering alone — the per-promotion delta B5 publishes.

  Like the mixed entry it is a small real APPLICATION rather than a
  require-only stub: an unused require DCEs to nearly nothing under
  `:advanced`, and a bundle measuring an empty graph reports a cost no
  consumer pays. Everything below is reached from the mount — two views
  whose bodies READ a subscription and DISPATCH an event, and a root that
  composes both through `v/mount`.

  It lives under `test/` for the reason the sibling bundle probes do:
  `deps.edn` publishes `src` alone, so a fixture that exists to be measured
  stays out of the artefact a consumer resolves, while shadow-cljs — which
  carries `freehand/test` on `:source-paths` — compiles it into a real
  production bundle.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.adapter.uix :as uix]))

;; ---------------------------------------------------------------------------
;; Handlers — ordinary re-frame2, identical to the mixed entry's. The root's
;; preflight plan seeds app-db through `:initial-events`, so the first render
;; reads a value that is already there.
;; ---------------------------------------------------------------------------

(rf/reg-event ::seed (fn [_ _] {:db {:count 0}}))

(rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)}))

(rf/reg-sub ::count (fn [db _] (:count db)))

;; ---------------------------------------------------------------------------
;; Two views, both interpreted — the same body twice, so this bundle mounts
;; the same NUMBER of leaves the mixed build does, and only their lowering
;; differs across the matched trio. The declarations are separate namespaced
;; ids, so they are separate views; the bodies are deliberately identical.
;; ---------------------------------------------------------------------------

(v/defview counter-a
  "An interpreted counter: a read, a dispatch and static markup."
  [{:keys [label]}]
  [:div.counter
   [:span.label label]
   [:output.count (str (v/sub [::count]))]
   [:button.bump {:on-click [::bump]} "+"]])

(v/defview counter-b
  "The second interpreted counter — the same body as `counter-a`, so the
  two leaves are matched."
  [{:keys [label]}]
  [:div.counter
   [:span.label label]
   [:output.count (str (v/sub [::count]))]
   [:button.bump {:on-click [::bump]} "+"]])

(v/defview app
  "The mounted root: two interpreted leaves on one page, over one frame."
  [_]
  [:main#freehand-release-interpreted
   [counter-a {:label "a"}]
   [counter-b {:label "b"}]])

(defn ^:export -main
  "The build's `:init-fn`. Installs the UIx adapter, then mounts the root
  into `#app`, owning the frame it runs over so the bundle carries the whole
  preflight path — exactly the shape the mixed entry documents."
  []
  (rf/init! uix/adapter)
  (v/mount [app {}]
           (js/document.getElementById "app")
           {:frame {:id             ::frame
                    :initial-events [[::seed]]}}))
