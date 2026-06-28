(ns helix.counter.core
  "The counter, rendered on the Helix substrate.

   The dataflow couldn't care less which React library draws the pixels.
   This is the same app as the Reagent and UIx counters — same events, same
   subscription — with exactly one thing changed: how a view reads state and
   gets hold of `dispatch`. Helix is React with hooks all the way down, so
   here that part is a `defnc` component and a hook. Put this file beside the
   other two counters and only the views differ; that seam is the whole point
   of an adapter.

   What you'll see here:

     - `reg-event` / `reg-sub` — the same on every substrate
     - `rf/init!` with the Helix adapter
     - `use-subscribe`, the Helix-idiomatic way to read a subscription
     - `(:dispatch (rf/capture-frame))` to dispatch from a click handler

   For the full substrate tour see
   `docs/core/how-to/use-uix-helix-or-slim.md`."
  (:require ["react-dom/client" :as react-dom-client]
            [helix.core         :refer [$ defnc]]
            [helix.dom          :as d]
            [re-frame.core      :as rf]
            [re-frame.adapter.helix :as helix-adapter]))

;; -- Events / subs -----------------------------------------------------------
;;
;; This is where the work happens, and it's all just data. An event handler is
;; a pure function: hand it the coeffects (which carry the current app-db) and
;; the event vector, and it hands back an effect map. The `{:db …}` key means
;; "replace app-db with this value", and the runtime commits it atomically at
;; the end of the cascade. Not a word about React in here — so this block is
;; byte-for-byte the same as the Reagent and UIx counters. See
;; `docs/core/glossary.md#event-handler`.
;;
;; You might wonder why the three counters retype these handlers instead of
;; sharing one namespace. It's deliberate. Each example is its own self-
;; contained build, and a bundle-isolation test checks that a Helix bundle
;; ships zero Reagent or UIx code (and vice versa). A shared namespace dragged
;; into all three builds would quietly break that guarantee. The seam worth
;; learning is the substrate boundary below — one dataflow, three view layers.

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
;; Everything below this line is the only substrate-specific code in the file:
;; the Helix views and the mount. Cross the divider and you've left the shared
;; dataflow behind.
;;
;; Helix is plain React, so a view is a `defnc` component that asks for what it
;; needs out loud — `defnc` directly, since the `reg-view` macro is Reagent-only
;; (UIx and Helix both read state with `use-subscribe`). `use-subscribe` is a
;; hook that reads a subscription.
;; `dispatch` comes off a `(rf/capture-frame)`: the frame api captures the in-scope
;; frame as a value, so the closed-over `dispatch` still finds this frame when
;; a click fires later — on a stack that no longer has any frame in scope. So
;; grab it at render time, while the frame is still around. See
;; `docs/core/glossary.md#capture-frame`.

(defnc counter-buttons []
  (let [count    (helix-adapter/use-subscribe [:counter/value])  ;; read: re-renders when :counter/value changes
        dispatch (:dispatch (rf/capture-frame))]                  ;; write: dispatch, captured off the render-time frame api
    (d/div
       (d/button {:on-click #(dispatch [:counter/dec])} "-")
       (d/span {:style {:margin "0 1em"} :data-testid "counter-value"} count)
       (d/button {:on-click #(dispatch [:counter/inc])} "+"))))

(defnc counter-app []
  ($ counter-buttons))

;; -- Mount -------------------------------------------------------------------
;;
;; The React root lives in an atom and gets created lazily inside `run`, never
;; at ns-load. Loading a namespace must produce zero DOM side effects, so that
;; co-required example namespaces don't race each other to call `createRoot` on
;; the one shared `#app` element. See examples/TESTING.md, "mount-isolation".

(defonce react-root (atom nil))

;; `app-frame` is just an id we pick. `:rf/default` is an ordinary frame id
;; with no special status — the runtime never conjures a frame for you, so we
;; name one here and hand it to the provider. From there the `use-subscribe`
;; hook and the render-time `(rf/capture-frame)` both resolve to it. See
;; `docs/core/glossary.md#frame-identity-is-carried-not-found`.
(def app-frame :rf/default)

(defn run []
  ;; `init!` installs the reactive adapter for the process. Each adapter ns
  ;; exports an `adapter` var; require the ns and pass that var. Call once at
  ;; startup. See `docs/core/glossary.md#init`.
  (rf/init! helix-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (react-dom-client/createRoot (js/document.getElementById "app"))))
    ;; The whole frame lifecycle lives in one spot: this provider. The first
    ;; mount creates the app frame and runs its `:initial-events` once to seed
    ;; app-db. A later mount under the same `:id` — a hot reload — reuses the
    ;; live frame and skips the seeding, so the count you were staring at
    ;; survives the reload. See `docs/core/concepts/frames.md`.
    (.render @react-root
             ($ helix-adapter/frame-provider {:id app-frame
                                              :initial-events [[:counter/initialise]]}
                ($ counter-app)))))
