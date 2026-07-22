(ns uix.counter.core
  "The counter, rendered on the UIx substrate.

   UIx is plain React with hooks, so this is the same app as the Reagent
   counter with exactly one thing changed: how a view reads state and gets
   hold of `dispatch`. Everything above the view layer — the events and the
   subscription — knows nothing about React, so it reads the same on every
   substrate. Put this file beside the Reagent counter and only the views
   differ; that seam is the whole point of an adapter.

   What you'll see here:

     - `reg-event` / `reg-sub` — the same on every substrate
     - `rf/init!` with the UIx adapter
     - `use-subscribe`, the UIx-idiomatic way to read a subscription
     - `use-frame`, the hook that hands a view its frame ops (`dispatch`)
     - the frame-provider, which scopes the app frame to the view subtree
       so `use-subscribe` and `use-frame` resolve to it

   For the full substrate tour see
   `docs/core/how-to/use-uix-or-slim.md`."
  (:require [uix.core :refer [$ defui]]
            [uix.dom  :as uix-dom]
            [re-frame.core    :as rf]
            [re-frame.adapter.uix :as uix-adapter]))

;; -- Events / subs -----------------------------------------------------------
;;
;; This is where the work happens, and it's all just data. An event handler
;; is a pure function: hand it the coeffects (which carry the current app-db)
;; and the event vector, and it hands back an effect map. The `{:db …}` key
;; means "replace app-db with this value", and the runtime commits it
;; atomically at the end of the pipeline run. Not a word about React in here — so
;; these four registrations are byte-for-byte the same on every substrate. See
;; `docs/core/glossary.md#event-handler`.

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
;; Here's the one thing UIx does differently. It's plain React, so a view is
;; a `defui` component that asks for what it needs out loud: `use-subscribe`
;; is a hook that reads a subscription, and `dispatch` comes off the
;; `use-frame` hook — capture-frame in hook position. The frame api it
;; returns freezes the in-scope frame into a value, so the `dispatch` we
;; close over still knows which frame to target when a click fires much
;; later — by which point we're deep in an event handler with no frame in
;; scope to ask. One primitive, three faces: capture-frame is the hold,
;; and `use-frame` is its hook spelling (Reagent's is `reg-view`
;; injection). See `docs/core/glossary.md#capture-frame`.

(defui counter-buttons []
  (let [count              (uix-adapter/use-subscribe [:counter/value])
        {:keys [dispatch]} (uix-adapter/use-frame)]
    ($ :div
       ($ :button {:on-click #(dispatch [:counter/dec])} "-")
       ($ :span {:style #js {:margin "0 1em"} :data-testid "counter-value"} count)
       ($ :button {:on-click #(dispatch [:counter/inc])} "+"))))

(defui counter-app []
  ($ counter-buttons))

;; -- Mount -------------------------------------------------------------------
;;
;; The React root lives in an atom and is created lazily inside `run`, never
;; at ns-load. The rule is that loading a namespace touches no DOM — so when
;; several example namespaces get co-required, they don't all elbow each
;; other trying to `create-root` onto the same shared `#app`. See
;; examples/TESTING.md, "mount-isolation".

(defonce react-root (atom nil))

;; The whole frame lifecycle lives in one spot — the `frame-root {:id
;; app-frame …}` down in `run`. The first mount creates the app frame and
;; runs its `:initial-events` once to seed app-db. After that, every
;; `use-subscribe` hook and every render-time `use-frame` resolves to
;; that frame. Mount again under the same `:id` — which is what a hot reload
;; does — and it reuses the live frame without re-seeding, so the counter
;; holds onto whatever you'd clicked it up to. Forget the provider entirely
;; and `use-subscribe` / `use-frame` raise `:rf.error/no-frame-context`:
;; the runtime won't conjure a frame for you, so the app has to stand one up.
;; See `docs/core/frames.md`.
;;
;; `app-frame` is just an id we picked. `:rf/default` looks special but
;; isn't — it's an ordinary frame id like any other. The runtime never
;; guesses which frame you mean, so we name one here and hand it to the
;; provider. See
;; `docs/core/glossary.md#frame-identity-is-carried-not-found`.
(def app-frame :rf/default)

;; DOM setup lives in `mount!`, tagged `^:dev/after-load` so shadow-cljs re-runs
;; it after each hot reload — edited views re-render into the same root and frame.
(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (when-not @react-root
      (reset! react-root (uix-dom/create-root el)))
    (uix-dom/render-root
      ($ uix-adapter/frame-root {:id app-frame
                                     :initial-events [[:counter/initialise]]}
         ($ counter-app))
      @react-root)))

(defn run []
  ;; `init!` tells the runtime which reactive substrate to render through —
  ;; once, for the whole process. Every adapter ns exports an `adapter` var;
  ;; require the ns and hand that var over. Call it once at startup and
  ;; forget about it. See `docs/core/glossary.md#init`.
  (rf/init! uix-adapter/adapter)
  (mount!))
