(ns counter-helix.core
  "Helix variant of the counter example.

   The one idea: the dataflow doesn't know which React-family library
   draws the pixels. Exercises the SAME events and sub as
   examples/reagent/counter and examples/uix/counter_uix — only the view
   and mount change — but renders through the Helix adapter, where the
   React state model is hooks all the way down. Demonstrates:

     - `rf/init!` with the Helix adapter
     - `reg-event` / `reg-sub` (substrate-agnostic)
     - `use-subscribe` hook (Helix idiomatic)
     - `(:dispatch (rf/frame-handle))` for click handlers — the
       component reads its own sub and takes its own dispatch
     - The shared frame-context — the same React Context
       object the Reagent and UIx adapters consume

   Different folder from examples/reagent/counter and
   examples/uix/counter_uix so all three canonical counters are
   undisturbed; bundle isolation is verified by the per-example
   shadow-cljs builds and the production-elision grep."
  (:require ["react-dom/client" :as react-dom-client]
            [helix.core         :refer [$ defnc]]
            [helix.dom          :as d]
            [re-frame.core      :as rf]
            [re-frame.adapter.helix :as helix-adapter]))

;; ============================================================================
;; SUBSTRATE-AGNOSTIC ARTEFACT LAYER  (events + sub)
;; ============================================================================
;;
;; Everything above the SUBSTRATE BOUNDARY divider below is the artefact
;; layer: events and the `:counter/value` sub. It is written exactly once
;; per *meaning* and is byte-for-byte IDENTICAL across the Reagent, UIx, and
;; Helix counters — same `:counter/*` ids, same handler bodies. That sameness
;; is deliberate and load-bearing: the id-identity *is* the cross-substrate
;; parity demonstration (examples/TESTING.md §Exception 2). The artefact layer
;; does not know — and must not know — which reactive substrate renders it.
;;
;; It is NOT extracted into a shared namespace on purpose. Each substrate
;; example is a self-contained `:browser` build, and `npm run
;; test:bundle-isolation` greps each released bundle to prove a Helix
;; `main.js` carries no Reagent/UIx code (and vice versa). A shared model
;; namespace required into all three builds would defeat that isolation and
;; the parity claim it underwrites. The boundary you should learn from this
;; example is the SUBSTRATE BOUNDARY below — same dataflow, three view layers
;; — not a file-extraction boundary.

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
;; Below this line is the only substrate-specific code in this example: the
;; Helix views + the mount. The Reagent and UIx counters share every line
;; ABOVE this divider and differ only in what sits BELOW it (Reagent
;; `reg-view`, UIx `defui` + `use-subscribe`, Helix `defnc` + `use-subscribe`).
;;
;; Helix users write `defnc` directly (the `reg-view` macro stays
;; Reagent-only). The component reads its sub with `use-subscribe`
;; and takes `dispatch` off a `(rf/frame-handle)` itself. The handle
;; captures the render-time frame, so the closed-over `dispatch`
;; targets the right frame even from an async callback.

(defnc counter-buttons []
  (let [count    (helix-adapter/use-subscribe [:counter/value])  ;; read: re-renders when :counter/value moves
        dispatch (:dispatch (rf/frame-handle))]                  ;; write: take dispatch off the render-time handle
    (d/div
       (d/button {:on-click #(dispatch [:counter/dec])} "-")
       (d/span {:style {:margin "0 1em"} :data-testid "counter-value"} count)
       (d/button {:on-click #(dispatch [:counter/inc])} "+"))))

(defnc counter-app []
  ($ counter-buttons))

;; -- Mount -------------------------------------------------------------------
;;
;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `createRoot` onto the shared `#app`.

(defonce react-root (atom nil))

;; The frame id this app runs in. `:rf/default` is an ordinary id with no
;; framework privilege; we just pick it. The provider below builds the frame
;; under this id and the `use-subscribe` hook + render-time `(rf/frame-handle)`
;; resolve to it.
(def app-frame :rf/default)

(defn run []
  ;; Install the Helix adapter. Pass the adapter spec map directly — no registry.
  (rf/init! helix-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (react-dom-client/createRoot (js/document.getElementById "app"))))
    ;; The frame lives in one spot: the provider. Given `:id`, it creates the
    ;; frame on first mount and runs `:initial-events` once to seed it. On hot
    ;; reload it reuses the existing frame and skips seeding, so the count you
    ;; were looking at survives.
    (.render @react-root
             ($ helix-adapter/frame-provider {:id app-frame
                                              :initial-events [[:counter/initialise]]}
                ($ counter-app)))))
