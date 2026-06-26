(ns counter-slim-and-fast.core
  "A minimal counter mounted on the day8/reagent-slim rewrite.

   The dataflow is identical to `examples/reagent/counter` — the same
   six dominoes, the same `:counter/*` events and subs. The only
   intentional divergence is the substrate beneath: every user-facing
   Reagent import points at `reagent2.*` instead of stock `reagent.*`,
   and `(rf/init!)` is called with the slim adapter Var. Read this file
   as the teaching example — it is plain, idiomatic re-frame2 with
   nothing but the example's own dataflow.

   IN-TREE NAMESPACE vs PUBLISHED ABI: the require below is the *in-tree*
   namespace `re-frame.adapter.reagent-slim`, used only because the
   unrenamed monorepo build shares a classpath with the stock adapter and
   must avoid an ns clash. The PUBLISHED `day8/reagent-slim` jar ships the
   adapter Var at the canonical, stock-identical `re-frame.adapter.reagent`
   (renamed at publication). An adopter therefore wires
   `(rf/init! re-frame.adapter.reagent/adapter)` exactly as for stock —
   slim is selected by deps coordinate, not by import line. Do not
   cargo-cult the in-tree `-slim` namespace into a published app. See
   docs/guide/how-to/use-uix-helix-or-slim.md and
   implementation/adapters/reagent-slim/DESIGN-RATIONALE.md §7.

   The slim adapter's bundle-isolation proof lives in its own gate-owned
   entrypoint, `counter-slim-and-fast.bundle-isolation-entry` (the
   `:init-fn` of the `:examples/counter-slim-and-fast` build), so this
   teaching file stays free of fixture plumbing. That entry boots the
   same app through the shared `boot!` helper below and passes a pre-mount
   hook that exercises the pure-CLJS SSR path the gate inspects. Because
   both paths call the one `boot!`, they cannot drift."
  (:require [reagent2.dom.client                :as rdc]
            [re-frame.core                      :as rf]
            [re-frame.views]
            ;; In-tree namespace (see ns docstring): published adopters
            ;; require the canonical `re-frame.adapter.reagent` instead.
            [re-frame.adapter.reagent-slim      :as reagent-slim-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs (handler registry is app-global) --------------------------
;;
;; These four forms are the substrate-agnostic core: pure handlers, a pure
;; subscription, all over plain data. They are byte-for-byte the stock
;; counter's, and they survive the substrate swap untouched — only the boot
;; below changes. That invariance is the whole point of this example.

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
;; reg-view is substrate-agnostic. The macro expands to a plain
;; React-component shape that reads through the active adapter at render
;; time, so the same view form renders on whichever substrate was
;; installed at boot. Here the slim adapter is installed (see `boot!`
;; below), so the view reads frame state through
;; `reagent2.core/current-component` rather than stock Reagent's.

(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"} :data-testid "counter-value"} @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

(reg-view counter-app []
  [counter-buttons])

;; -- Mount -------------------------------------------------------------------
;;
;; The React root is held in an atom and created lazily inside `boot!`,
;; not at ns-load, per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects, so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
;; This mirrors the behavioural twin `examples/reagent/counter` — the one
;; blessed mount shape across the example tree. The only divergence from
;; the twin is the substrate swap (the `reagent2.*` imports + the slim
;; adapter Var).

(defonce react-root (atom nil))

;; The app frame is established in one spot: the `frame-provider {:id
;; app-frame …}` at the render root below. On first mount the provider
;; creates the frame, applies its config, and runs `:initial-events`
;; once to seed it (the `[:counter/initialise]` boot dispatch). From then
;; on every in-tree `dispatch`/`subscribe` resolves to that frame. On hot
;; reload the provider reuses the existing frame and skips re-seeding.
;; Matches the canonical mount in examples/reagent/counter/core.cljs.
;;
;; `:rf/default` is an ordinary frame id with no special privilege — you
;; establish it like any other (the runtime won't infer it for you).
(def app-frame :rf/default)

(defn boot!
  "Boots the example: install the slim adapter, then lazily mount
   `counter-app` into `#app` under a `frame-provider {:id app-frame …}`.
   The provider creates and seeds the app frame (see the comment above).

   This is the single source of truth for the boot. Both the teaching path
   (`run` below) and the gate-owned path
   (`counter-slim-and-fast.bundle-isolation-entry/run`) call it, so a
   future boot change is made in one place and the two paths cannot drift.

   `on-frame` is an optional thunk the bundle-isolation entry uses; the
   teaching `run` passes nothing. It runs once before the client mount,
   inside its own short-lived frame (`with-new-frame`, auto-destroyed on
   exit), so the fixture's pre-mount SSR exercise stays clear of the app
   frame the provider creates at mount. Keeping this seam in `boot!`
   instead of re-copying the boot is what keeps `core` free of fixture
   plumbing while preventing drift."
  ([] (boot! nil))
  ([on-frame]
   ;; Slim adapter — the difference from `examples/reagent/counter` is
   ;; right here. Same `rf/init!` signature; different adapter Var.
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
