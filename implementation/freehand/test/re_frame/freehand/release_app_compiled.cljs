(ns re-frame.freehand.release-app-compiled
  "The entry of the COMPILED-ONLY twin of the Freehand release build
  (`:freehand-release-compiled`).

  It is `re-frame.freehand.release-app-interpreted` with `{:compiled true}`
  added to every view and nothing else changed — same handlers, same
  parameter vectors, same bodies character-for-character. The mixed release
  build carries one interpreted leaf and its compiled twin; this one carries
  both leaves compiled, under a compiled root, so no interpreted view
  reaches the bundle at all. The three markers here are the ONLY textual
  difference from the interpreted twin, which is what makes the byte
  difference between the two bundles a reading of lowering and nothing else
  — the per-promotion delta B5 publishes.

  The root composes its two leaves by literal reference
  (`[counter-a {:label \"a\"}]`), which the compiled tier lowers like any
  other child-view reference (D010 refuses only a RUNTIME markup value inside
  a compiled body, never a compile-time view reference). Like the mixed
  entry it is a real APPLICATION, not a require-only stub, so `:advanced`
  keeps the whole mount graph rather than eliding an unused require.

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
;; Handlers — identical to the interpreted twin's. Lowering changes the view
;; bodies, never the event/subscription graph, so these are unchanged.
;; ---------------------------------------------------------------------------

(rf/reg-event ::seed (fn [_ _] {:db {:count 0}}))

(rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)}))

(rf/reg-sub ::count (fn [db _] (:count db)))

;; ---------------------------------------------------------------------------
;; Two views, both COMPILED — the interpreted twin's bodies plus the marker.
;; ---------------------------------------------------------------------------

(v/defview counter-a
  "A compiled counter — the interpreted twin's body under `{:compiled true}`."
  {:compiled true}
  [{:keys [label]}]
  [:div.counter
   [:span.label label]
   [:output.count (str (v/sub [::count]))]
   [:button.bump {:on-click [::bump]} "+"]])

(v/defview counter-b
  "The second compiled counter — the same body as `counter-a`, so the two
  leaves are matched."
  {:compiled true}
  [{:keys [label]}]
  [:div.counter
   [:span.label label]
   [:output.count (str (v/sub [::count]))]
   [:button.bump {:on-click [::bump]} "+"]])

(v/defview app
  "The mounted root, itself compiled: two compiled leaves on one page, over
  one frame. Nothing interpreted reaches this bundle."
  {:compiled true}
  [_]
  [:main#freehand-release-compiled
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
