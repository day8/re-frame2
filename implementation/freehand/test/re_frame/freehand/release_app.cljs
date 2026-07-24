(ns re-frame.freehand.release-app
  "The entry of the Freehand artefact's RELEASE build (`:freehand-release`).

  Until this namespace existed the artefact had exactly one shadow build,
  `:node-test-freehand`, and it is a test build. Nothing in the repo
  compiled Freehand the way a consumer ships it — `:advanced`,
  `goog.DEBUG` false — so anything wanting to state what the substrate
  COSTS on a page (bundle bytes, parse/eval, what survives dead-code
  elimination) had no artefact to point at. Not a thin measurement: no
  measurement at all, because there was nothing built.

  So this is a small Freehand APPLICATION rather than a require-only
  stub. A namespace that merely required the door would compile to almost
  nothing under `:advanced` — Closure keeps what is reachable, and an
  unused require is not — and a bundle measuring an empty graph would
  report a cost no consumer pays. Everything below is therefore reached
  from the mount: a declared view whose body READS a subscription and
  DISPATCHES an event (the reactive substrate, the event materializer and
  the ViewCell shell), its `{:compiled true}` twin (the compiled tier's
  emitted body and its own lowering), and a root that composes both
  through `v/mount` (root identity, the live-root registry and the frame
  preflight).

  It lives under `test/` rather than `src/` for the reason the sibling
  bundle probes do: `deps.edn` publishes `src` alone, so a fixture that
  exists to be measured stays out of the artefact a consumer resolves,
  while shadow-cljs — which carries `freehand/test` on `:source-paths` —
  can still compile it into a real production bundle."
  (:require [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.adapter.uix :as uix]))

;; ---------------------------------------------------------------------------
;; The application's handlers. Ordinary re-frame2: the root's preflight plan
;; seeds app-db through `:initial-events`, so the first render of the view
;; below reads a value that is already there.
;; ---------------------------------------------------------------------------

(rf/reg-event ::seed (fn [_ _] {:db {:count 0}}))

(rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)}))

(rf/reg-sub ::count (fn [db _] (:count db)))

;; ---------------------------------------------------------------------------
;; One view, declared twice — the same body in both execution modes, which is
;; what the two-mode substrate claims and therefore what a shipped bundle has
;; to carry. The declarations are separate namespaced ids, so they are
;; separate views; the bodies are deliberately identical.
;; ---------------------------------------------------------------------------

(v/defview counter
  "The interpreted paved path: a read, a dispatch and static markup."
  [{:keys [label]}]
  [:div.counter
   [:span.label label]
   [:output.count (str (v/sub [::count]))]
   [:button.bump {:on-click [::bump]} "+"]])

(v/defview counter-compiled
  "The compiled hot tier over the same body — `{:compiled true}` and
  nothing else changed."
  {:compiled true}
  [{:keys [label]}]
  [:div.counter
   [:span.label label]
   [:output.count (str (v/sub [::count]))]
   [:button.bump {:on-click [::bump]} "+"]])

(v/defview app
  "The mounted root: both tiers on one page, over one frame."
  [_]
  [:main#freehand-release
   [counter {:label "interpreted"}]
   [counter-compiled {:label "compiled"}]])

(defn ^:export -main
  "The build's `:init-fn`. Installs a substrate adapter, then mounts the
  root into `#app`, owning the frame it runs over so the bundle carries the
  whole preflight path.

  The reactive substrate needs an adapter before the first frame is made
  (Spec 006 §Adapter selection at boot): `make-state-container` raises
  `:rf.error/no-adapter-installed` until `rf/init!` has run. This is an
  INTERACTIVE mount — a click dispatches an event, app-db changes, and the
  view re-renders — so the adapter has to bridge reactive change into
  React's re-render, exactly as a shipped Freehand app would. The UIx
  adapter is the React-integrated adapter Freehand's own interactive DOM
  mounts run against; a real consumer ships one like it, so the cost it
  adds is a cost the bundle should carry rather than hide."
  []
  (rf/init! uix/adapter)
  (v/mount [app {}]
           (js/document.getElementById "app")
           {:frame {:id             ::frame
                    :initial-events [[::seed]]}}))
