(ns counter-uix.core
  "UIx variant of the counter example.

   Same dataflow as examples/reagent/counter, rendered through the UIx
   adapter — React hooks rather than Reagent's reactive atoms. The point
   is the substrate boundary: everything above the view layer is the
   Reagent counter's code verbatim, and the only thing that moves is how
   a component reads state and gets hold of `dispatch`. Demonstrates:

     - `reg-event` / `reg-sub` (substrate-agnostic — identical to Reagent)
     - `rf/init!` with the UIx adapter
     - `use-subscribe` hook (the UIx-idiomatic subscription read)
     - `(:dispatch (rf/frame-handle))` for click handlers — a UIx
       component reads dispatch and subscriptions off explicit calls
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
;; it atomically at the end of the cascade. This whole block is identical
;; to the Reagent and Helix counters — same ids, same bodies. That
;; sameness is the cross-substrate parity: events and subs are pure data
;; and logic, independent of whichever substrate renders the views.

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
;; substrate boundary is here. UIx is plain React, so a view is a `defui`
;; component that reads what it needs explicitly: `use-subscribe` is a
;; hook that reads a subscription, and `dispatch` comes off a
;; `(rf/frame-handle)`. The handle captures the in-scope frame as a value,
;; so the closed-over `dispatch` still targets this frame when a click
;; fires later. Grab it at render time, while the frame is in scope.

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

;; The whole frame lifecycle lives in one spot at the render root: the
;; `frame-provider {:id app-frame …}` below. On first mount it creates the
;; app frame, applies its config, and runs `:initial-events` once to seed
;; app-db. That context is what lets the `use-subscribe` hook and the
;; render-time `(rf/frame-handle)` capture resolve to the app frame. On hot
;; reload the provider reuses the existing frame and skips re-seeding, so
;; the counter keeps its value across re-mounts. (Render a UIx tree with no
;; provider and `use-subscribe` / `frame-handle` raise
;; `:rf.error/no-frame-context` — the app must establish its frame.)
;;
;; `app-frame` is just an id we pick. `:rf/default` is an ordinary frame id
;; with no framework privilege — the runtime won't infer it, so we name it
;; here and hand it to the provider like any other id.
(def app-frame :rf/default)

(defn run []
  ;; `init!` installs the UIx reactive adapter for the process. Each adapter
  ;; ns exports an `adapter` var; require the ns and pass that var directly.
  (rf/init! uix-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (uix-dom/create-root (js/document.getElementById "app"))))
    (uix-dom/render-root
      ($ uix-adapter/frame-provider {:id app-frame
                                     :initial-events [[:counter/initialise]]}
         ($ counter-app))
      @react-root)))
