(ns counter-uix.core
  "The counter, rendered on the UIx substrate.

   UIx is plain React with hooks, so this is the same app as the Reagent
   counter with one thing changed: how a view reads state and gets hold of
   `dispatch`. Everything above the view layer — the events and the
   subscription — is substrate-agnostic and looks the same on any substrate.

   Shows:

     - `reg-event` / `reg-sub` — the same on every substrate
     - `rf/init!` with the UIx adapter
     - `use-subscribe`, the UIx-idiomatic way to read a subscription
     - `(:dispatch (rf/frame-handle))` to dispatch from a click handler
     - the frame-provider, which scopes the app frame to the view subtree
       so `use-subscribe` and `frame-handle` resolve to it

   For the full substrate tour see
   `docs/guide/how-to/use-uix-helix-or-slim.md`."
  (:require [uix.core :refer [$ defui]]
            [uix.dom  :as uix-dom]
            [re-frame.core    :as rf]
            [re-frame.adapter.uix :as uix-adapter]))

;; -- Events / subs -----------------------------------------------------------
;;
;; An event handler is a pure function from coeffects (which carry the
;; current app-db) and the event vector to an effect map. The `{:db …}` key
;; means "replace app-db with this value"; the runtime commits it atomically
;; at the end of the cascade. This block is pure data and logic with no
;; mention of React, so it is identical on every substrate. See
;; `docs/guide/glossary.md#event-handler`.

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
;; UIx is plain React, so a view is a `defui` component that reads what it
;; needs explicitly. `use-subscribe` is a hook that reads a subscription.
;; `dispatch` comes off a `(rf/frame-handle)`: the handle captures the
;; in-scope frame as a value, so the closed-over `dispatch` still targets
;; this frame when a click fires later, on a stack with no frame in scope.
;; Grab it at render time, while the frame is in scope. See
;; `docs/guide/glossary.md#frame-handle`.

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
;; The React root is held in an atom and created lazily inside `run`, not at
;; ns-load. Loading a namespace must produce no DOM side effects, so that
;; co-required example namespaces don't race `create-root` onto the shared
;; `#app`. See examples/TESTING.md, "mount-isolation".

(defonce react-root (atom nil))

;; The whole frame lifecycle lives in one spot: the `frame-provider {:id
;; app-frame …}` in `run` below. The first mount creates the app frame and
;; runs its `:initial-events` once to seed app-db. From then on the
;; `use-subscribe` hook and the render-time `(rf/frame-handle)` capture
;; resolve to that frame. A later mount under the same `:id` (a hot reload)
;; reuses the live frame and does not re-seed, so the counter keeps its
;; value. Render a UIx tree with no provider and `use-subscribe` /
;; `frame-handle` raise `:rf.error/no-frame-context` — the app must
;; establish its frame. See `docs/guide/concepts/frames.md`.
;;
;; `app-frame` is just an id we pick. `:rf/default` is an ordinary frame id
;; with no special status: the runtime never infers a frame, so we name one
;; here and hand it to the provider. See
;; `docs/guide/glossary.md#frame-identity-is-carried-not-found`.
(def app-frame :rf/default)

(defn run []
  ;; `init!` installs the reactive adapter for the process. Each adapter ns
  ;; exports an `adapter` var; require the ns and pass that var. Call once
  ;; at startup. See `docs/guide/glossary.md#init`.
  (rf/init! uix-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (uix-dom/create-root (js/document.getElementById "app"))))
    (uix-dom/render-root
      ($ uix-adapter/frame-provider {:id app-frame
                                     :initial-events [[:counter/initialise]]}
         ($ counter-app))
      @react-root)))
