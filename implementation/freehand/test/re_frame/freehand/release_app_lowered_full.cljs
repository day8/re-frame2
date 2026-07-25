;; ---------------------------------------------------------------------------
;; B5's MATCHED PAIR, the arm with EVERY view lowered: all three declarations
;; here carry `{:compiled true}`, so no interpreted view reaches the bundle.
;;
;; The other arm, `re-frame.freehand.release-app-lowered-none`, is this file
;; with those three markers dropped and nothing else changed. Drop them,
;; rename the `lowered-full` segment to `lowered-none`, and the two sources
;; are identical character for character — which
;; `re-frame.freehand.bench.b5-matched-builds-cljs-test` asserts on every run,
;; built bundles or not. That is what makes the byte delta between the two
;; `:advanced` bundles they compile to (`:freehand-release-interpreted` and
;; `:freehand-release-compiled`) a reading of view lowering and nothing else:
;; the per-promotion delta B5 publishes.
;;
;; Two properties of this file exist only to hold that promise, and would be
;; odd in ordinary application code:
;;
;;   - The distinguishing segments `lowered-none` and `lowered-full` are the
;;     SAME LENGTH. `:advanced` keeps a namespace's own name — it rides
;;     inside the registered event, subscription and frame ids, the view
;;     manifest, and the source coordinates the compiled tier emits — so a
;;     longer name on one arm would weigh into the delta as though it were
;;     lowering.
;;   - Neither namespace carries a DOCSTRING; this prose is a comment
;;     instead. `:advanced` does not strip a namespace docstring, and the
;;     view manifest ships it once per declared view, so a docstring here
;;     would land three times inside the very artefact under the probe.
;;     Measured on the two entries this pair replaced, whose docstrings
;;     described different things: +339 bytes of a 17,905-byte raw delta.
;;     Comments are not compiled.
;;
;; The root composes its two leaves by literal reference
;; (`[counter-a {:label "a"}]`), which the compiled tier lowers like any
;; other child-view reference — D010 refuses a RUNTIME markup value inside a
;; compiled body, never a compile-time view reference.
;;
;; What it is otherwise: a small real APPLICATION rather than a require-only
;; stub, because an unused require is eliminated to nearly nothing under
;; `:advanced` and a bundle measuring an empty graph reports a cost no
;; consumer pays. Everything below is reached from the mount — two views
;; whose bodies READ a subscription and DISPATCH an event, and a root that
;; composes both through `v/mount`.
;;
;; It lives under `test/` for the reason the sibling bundle probes do:
;; `deps.edn` publishes `src` alone, so a fixture that exists to be measured
;; stays out of the artefact a consumer resolves, while shadow-cljs — which
;; carries `freehand/test` on `:source-paths` — compiles it into a real
;; production bundle.
;;
;; Normative owner: `docs/design/freehand/decisions/`
;; `D021-performance-budgets-and-release-evidence.md`.
;; ---------------------------------------------------------------------------

(ns re-frame.freehand.release-app-lowered-full
  (:require [re-frame.core :as rf]
            [re-frame.freehand :as v]))

;; ---------------------------------------------------------------------------
;; Handlers — ordinary re-frame2. The root's preflight plan seeds app-db
;; through `:initial-events`, so the first render reads a value that is
;; already there. Lowering changes view bodies and never the event or
;; subscription graph, so these are identical across the pair.
;; ---------------------------------------------------------------------------

(rf/reg-event ::seed (fn [_ _] {:db {:count 0}}))

(rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)}))

(rf/reg-sub ::count (fn [db _] (:count db)))

;; ---------------------------------------------------------------------------
;; Two counter leaves and a root: three view declarations, which is the
;; promoted-view count the delta's per-view mean divides by. The two leaves
;; carry the same body as each other as well as across the pair.
;; ---------------------------------------------------------------------------

(v/defview counter-a
  "A counter leaf: a subscription read, a dispatch and static markup."
  {:compiled true}
  [{:keys [label]}]
  [:div.counter
   [:span.label label]
   [:output.count (str (v/sub [::count]))]
   [:button.bump {:on-click [::bump]} "+"]])

(v/defview counter-b
  "The second counter leaf — the same body as `counter-a`."
  {:compiled true}
  [{:keys [label]}]
  [:div.counter
   [:span.label label]
   [:output.count (str (v/sub [::count]))]
   [:button.bump {:on-click [::bump]} "+"]])

(v/defview app
  "The mounted root: two counter leaves on one page, over one frame."
  {:compiled true}
  [_]
  [:main#freehand-release-twin
   [counter-a {:label "a"}]
   [counter-b {:label "b"}]])

(defn ^:export -main
  "The build's `:init-fn`. Installs Freehand's own adapter, then mounts the
  root into `#app`, owning the frame it runs over so the bundle carries the
  whole preflight path.

  The reactive substrate needs an adapter before the first frame is made
  (Spec 006 §Adapter selection at boot): `make-state-container` raises
  `:rf.error/no-adapter-installed` until `rf/init!` has run. The mount is
  INTERACTIVE — a click dispatches an event, app-db changes and the view
  re-renders — so the adapter has to bridge reactive change into React's
  re-render.

  That adapter is now Freehand's OWN (`v/adapter`, rf2-vo8fb). Both halves of
  the matched pair install the SAME one, so lowering stays the only variable
  between the two bundles — and neither of them carries a wrapper library it
  never renders an element through."
  []
  (rf/init! v/adapter)
  (v/mount [app {}]
           (js/document.getElementById "app")
           {:frame {:id             ::frame
                    :initial-events [[::seed]]}}))
