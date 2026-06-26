(ns counter-uix.core
  "UIx variant of the counter example.

   Same dataflow as examples/reagent/counter, rendered through the UIx
   adapter — React hooks all the way down rather than Reagent's reactive
   atoms. The point of the example is the substrate boundary: everything
   above the view layer is byte-for-byte the Reagent counter's, and the
   only thing that moves is how a component reads state and gets hold of
   `dispatch`. Demonstrates:

     - `reg-event` / `reg-sub` (substrate-agnostic — identical to Reagent)
     - `rf/init!` with the UIx adapter
     - `use-subscribe` hook (the UIx-idiomatic subscription read)
     - `(:dispatch (rf/frame-handle))` for click handlers — a UIx
       component reaches for dispatch / use-subscribe explicitly; there
       is no auto-injection
     - frame resolution via React context — `use-subscribe` and the
       `frame-handle` capture read the surrounding frame-provider

   A separate folder from examples/reagent/counter so the canonical
   Reagent counter is undisturbed; bundle isolation is verified by the
   per-example shadow-cljs builds and the production-elision grep."
  (:require [uix.core :refer [$ defui]]
            [uix.dom  :as uix-dom]
            [re-frame.core    :as rf]
            [re-frame.adapter.uix :as uix-adapter]))

;; -- Events / subs (the registrar is process-global) -------------------------
;;
;; Each handler is a pure (coeffects, event-vector) -> effect map fn: it
;; returns `{:db …}` ("replace app-db with this") and the runtime commits
;; it atomically at the end of the cascade. This whole block is
;; byte-for-byte identical to the Reagent and Helix counters — same ids,
;; same bodies. That sameness IS the cross-substrate parity: the artefact
;; layer does not know which reactive substrate renders it.

(rf/reg-event :counter/initialise
  (fn [{:keys [db]} _event] {:db {:counter/value 5}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

(rf/reg-event :counter/dec
  (fn [{:keys [db]} _event] {:db (update db :counter/value dec)}))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- Views (the only substrate-specific code in this file) -------------------
;;
;; Everything above is shared with the Reagent and Helix counters; the
;; substrate boundary is here. `reg-view` (the macro that auto-injects
;; `dispatch`/`subscribe`) stays Reagent-only — UIx is plain React, so a
;; component is a `defui` and reaches for what it needs explicitly:
;; `use-subscribe` reads a sub (a real hook), and `dispatch` comes off a
;; `(rf/frame-handle)`. The handle is the frame captured as a *value*, so
;; the closed-over `dispatch` keeps targeting this frame even when a click
;; fires later — grab it at render time, while the frame is in scope.

(defui counter-buttons []
  (let [count    (uix-adapter/use-subscribe [:counter/value])
        dispatch (:dispatch (rf/frame-handle))]
    ($ :div
       ($ :button {:on-click #(dispatch [:counter/dec])} "-")
       ($ :span {:style #js {:margin "0 1em"} :data-testid "counter-value"} count)
       ($ :button {:on-click #(dispatch [:counter/inc])} "+"))))

(defui counter-app []
  ($ counter-buttons))

;; -- Mount -------------------------------------------------------------------
;;
;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.

(defonce react-root (atom nil))

;; The runtime never synthesises a frame from absence — an app must establish
;; its frame explicitly. `init!` installs the adapter (it does NOT create the
;; frame), `reg-frame` registers the app frame, the boot dispatch runs under
;; `with-frame`, and the render is wrapped in `frame-provider` — the
;; scope-only provider that threads this already-registered frame's id down
;; the React tree (the owning `frame-provider` would *create* a frame; we
;; already own ours). That context is what lets the `use-subscribe` hook and
;; the render-time `(rf/frame-handle)` capture resolve to the app frame. There
;; is no `:rf/default` floor: a UIx tree rendered with NO provider observes the
;; no-provider sentinel and any `use-subscribe` / `frame-handle` raises
;; `:rf.error/no-frame-context`.
;;
;; `:rf/default` is an ORDINARY frame id with no framework privilege — a
;; migration may reach for it out of habit, but the runtime won't infer it.
(def app-frame :rf/default)

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! uix-adapter/adapter)
  (rf/reg-frame app-frame {})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:counter/initialise]))
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (uix-dom/create-root (js/document.getElementById "app"))))
    (uix-dom/render-root
      ($ uix-adapter/frame-provider {:frame app-frame}
         ($ counter-app))
      @react-root)))
