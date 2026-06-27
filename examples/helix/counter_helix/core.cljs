(ns counter-helix.core
  "Counter, rendered with the Helix substrate.

   The dataflow doesn't know which React-family library draws the pixels.
   This counter runs the same events and subscription as the Reagent and
   UIx counters; only the view and mount change. Here they render through
   the Helix adapter, whose React state model is hooks all the way down.
   It shows:

     - `rf/init!` with the Helix adapter
     - `reg-event` / `reg-sub`, which are substrate-agnostic
     - the `use-subscribe` hook, the Helix-idiomatic way to read a sub
     - `(:dispatch (rf/frame-handle))` in click handlers — the component
       reads its own sub and takes its own dispatch

   See docs/guide/how-to/use-uix-helix-or-slim.md for the full walkthrough
   of running one app on UIx or Helix."
  (:require ["react-dom/client" :as react-dom-client]
            [helix.core         :refer [$ defnc]]
            [helix.dom          :as d]
            [re-frame.core      :as rf]
            [re-frame.adapter.helix :as helix-adapter]))

;; ============================================================================
;; SUBSTRATE-AGNOSTIC LAYER  (events + sub)
;; ============================================================================
;;
;; Everything above the SUBSTRATE BOUNDARY below is the dataflow: the events
;; and the `:counter/value` subscription. It is identical across the Reagent,
;; UIx, and Helix counters — same `:counter/*` ids, same handler bodies. The
;; dataflow does not know which substrate renders it; that sameness is the
;; whole point of the example.
;;
;; The three counters do not share this code through a common namespace, on
;; purpose. Each substrate example is a self-contained `:browser` build, and
;; a bundle-isolation test proves a Helix bundle carries no Reagent or UIx
;; code (and vice versa). A shared namespace pulled into all three builds
;; would break that. The boundary worth learning here is the SUBSTRATE
;; BOUNDARY below — one dataflow, three view layers.

(rf/reg-event :counter/initialise
  (fn [{:keys [db]} _event] {:db {:counter/value 5}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

(rf/reg-event :counter/dec
  (fn [{:keys [db]} _event] {:db (update db :counter/value dec)}))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; ============================================================================
;; ──────────────────────────  SUBSTRATE BOUNDARY  ──────────────────────────
;; ============================================================================
;;
;; Below this line is the only substrate-specific code in the example: the
;; Helix views and the mount. The Reagent and UIx counters share every line
;; above this divider and differ only below it (Reagent `reg-view`, UIx
;; `defui` + `use-subscribe`, Helix `defnc` + `use-subscribe`).
;;
;; Helix users write `defnc` directly; the `reg-view` macro is Reagent-only.
;; The component reads its sub with `use-subscribe` and takes `dispatch` off
;; `(rf/frame-handle)` itself. The handle captures the frame in scope at
;; render time, so the closed-over `dispatch` still targets the right frame
;; from a click handler that fires later.

(defnc counter-buttons []
  (let [count    (helix-adapter/use-subscribe [:counter/value])  ;; read: re-renders when :counter/value changes
        dispatch (:dispatch (rf/frame-handle))]                  ;; write: take dispatch off the render-time handle
    (d/div
       (d/button {:on-click #(dispatch [:counter/dec])} "-")
       (d/span {:style {:margin "0 1em"} :data-testid "counter-value"} count)
       (d/button {:on-click #(dispatch [:counter/inc])} "+"))))

(defnc counter-app []
  ($ counter-buttons))

;; -- Mount -------------------------------------------------------------------
;;
;; The React root is held in an atom and created lazily inside `run`, not at
;; ns-load. Loading the namespace must produce no DOM side effects, so that
;; co-required example namespaces don't race each other to `createRoot` onto
;; the shared `#app` element.

(defonce react-root (atom nil))

;; The frame id this app runs in. `:rf/default` is an ordinary id with no
;; framework privilege; we just pick it. The provider below creates the frame
;; under this id, and the `use-subscribe` hook and render-time
;; `(rf/frame-handle)` both resolve to it.
(def app-frame :rf/default)

(defn run []
  ;; Install the Helix adapter. Pass the adapter value directly to init!.
  (rf/init! helix-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (react-dom-client/createRoot (js/document.getElementById "app"))))
    ;; The frame lives in one spot: the provider. Given `:id`, it creates the
    ;; frame on first mount and runs `:initial-events` once to seed it. On hot
    ;; reload it reuses the existing frame and skips seeding, so the count you
    ;; were looking at survives. See docs/guide/concepts/frames.md.
    (.render @react-root
             ($ helix-adapter/frame-provider {:id app-frame
                                              :initial-events [[:counter/initialise]]}
                ($ counter-app)))))
