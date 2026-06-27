(ns counter-slim-and-fast.core
  "A minimal counter on the reagent-slim substrate.

   This is the teaching file: plain, idiomatic re-frame2. The dataflow is
   the same counter as `examples/reagent/counter` — the same `:counter/*`
   events and subs. The one difference is the substrate beneath it: the
   view imports point at `reagent2.*`, and `rf/init!` is given the slim
   adapter. Picking a substrate is one line; see
   docs/guide/how-to/use-uix-helix-or-slim.md.

   The require below names `re-frame.adapter.reagent-slim` because this
   monorepo build shares a classpath with the stock adapter and must avoid
   a namespace clash. A real app picks slim by its `deps.edn` coordinate
   (`day8/reagent-slim`), not by this import line — see the guide page's
   coordinate table.

   The slim adapter's bundle-isolation proof runs from a separate
   gate-owned entrypoint, `counter-slim-and-fast.bundle-isolation-entry`,
   so this file carries no fixture plumbing. That entry boots the same app
   through the shared `boot!` helper below, passing a hook that exercises
   the pure-CLJS SSR path the gate inspects."
  (:require [reagent2.dom.client                :as rdc]
            [re-frame.core                      :as rf]
            [re-frame.views]
            ;; Monorepo-only namespace (see ns docstring): a real app picks
            ;; slim by its deps coordinate, not by this import.
            [re-frame.adapter.reagent-slim      :as reagent-slim-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs (handler registry is app-global) --------------------------
;;
;; These four forms are the substrate-agnostic core: pure handlers and a pure
;; subscription over plain data. The substrate swap does not touch them — only
;; the boot below changes. That invariance is the point of this example.
;; See docs/guide/glossary.md for event and subscription.

(rf/reg-event :counter/initialise
  (fn [{:keys [db]} _event] {:db {:counter/value 5}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

(rf/reg-event :counter/dec
  (fn [{:keys [db]} _event] {:db (update db :counter/value dec)}))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- Views -------------------------------------------------------------------
;;
;; reg-view is substrate-agnostic. The same view form renders on whichever
;; substrate was installed at boot, because it reads through the active
;; adapter at render time. Here that adapter is slim (see `boot!` below).
;; See docs/guide/concepts/views.md.

(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"} :data-testid "counter-value"} @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

(reg-view counter-app []
  [counter-buttons])

;; -- Mount -------------------------------------------------------------------
;;
;; The React root is held in an atom and created lazily inside `boot!`, not
;; at ns-load. Loading the namespace must produce no DOM side effects, so
;; that co-required example namespaces don't race `create-root` onto the
;; shared `#app`. This is the same mount shape as `examples/reagent/counter`.

(defonce react-root (atom nil))

;; The app frame is established in one spot: the `frame-provider {:id
;; app-frame …}` at the render root below. On first mount the provider
;; creates the frame, applies its config, and runs `:initial-events` once
;; to seed it (the `[:counter/initialise]` dispatch). After that every
;; `dispatch`/`subscribe` resolves to that frame. On hot reload the
;; provider reuses the existing frame and skips re-seeding.
;;
;; `:rf/default` is an ordinary frame id with no special privilege — you
;; establish it like any other; the runtime won't infer it for you.
;; See docs/guide/concepts/frames.md.
(def app-frame :rf/default)

(defn boot!
  "Boots the example: install the slim adapter, then lazily mount
   `counter-app` into `#app` under a `frame-provider {:id app-frame …}`.
   The provider creates and seeds the app frame (see the comment above).

   Both the teaching path (`run` below) and the gate-owned path
   (`counter-slim-and-fast.bundle-isolation-entry/run`) call this one
   helper, so the boot is defined in a single place.

   `on-frame` is an optional thunk the bundle-isolation entry uses; the
   teaching `run` passes nothing. When given, it runs once before the
   client mount inside its own short-lived frame (`with-new-frame`,
   destroyed on exit), so the fixture's pre-mount SSR exercise stays clear
   of the app frame the provider creates at mount."
  ([] (boot! nil))
  ([on-frame]
   ;; Slim adapter — this is the difference from `examples/reagent/counter`.
   ;; Same `rf/init!` signature; different adapter.
   (rf/init! reagent-slim-adapter/adapter)
   (when on-frame
     ;; Fixture-only seam: run the hook in a throwaway frame (destroyed on
     ;; exit) so it stays clear of the app frame the provider creates below.
     (rf/with-new-frame [_ (rf/make-frame {})]
       (on-frame)))
   (when (exists? js/document)
     (when-not @react-root
       (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
     (rdc/render @react-root
                 [rf/frame-provider {:id app-frame
                                     :initial-events [[:counter/initialise]]}
                  [counter-app]]))))

(defn run []
  (boot!))
