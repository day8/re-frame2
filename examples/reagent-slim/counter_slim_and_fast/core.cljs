(ns counter-slim-and-fast.core
  "A minimal counter mounted on the day8/reagent-slim rewrite.

   The dataflow is identical to `examples/reagent/counter` — the same
   six dominoes, the same `:counter/*` events and subs. The only
   intentional divergence is the substrate beneath: every user-facing
   Reagent import points at `reagent2.*` instead of stock `reagent.*`,
   and `(rf/init!)` is called with the slim adapter Var
   `re-frame.adapter.reagent-slim/adapter`. Read this file as the
   teaching example; the adapter's bundle-isolation proof lives next to
   it in `counter-slim-and-fast.bundle-isolation-fixture` (fixture code,
   not app practice — wired into `run`)."
  (:require [reagent2.dom.client                :as rdc]
            [re-frame.core                      :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent-slim      :as reagent-slim-adapter]
            [counter-slim-and-fast.bundle-isolation-fixture :as fixture])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs (handler registry is app-global) --------------------------

(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:counter/value 5}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :counter/value dec)))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- Views -------------------------------------------------------------------
;;
;; reg-view is substrate-agnostic — the macro expands to a plain
;; React-component shape that consults the active adapter's
;; `register-context-provider` / `current-component` seams at render
;; time. With the slim adapter installed (see `run` below), the
;; rendered component reads frame state through
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
;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
;; This mirrors the behavioural twin `examples/reagent/counter` — the one
;; blessed mount shape across the whole example tree. The only intentional
;; divergence from the twin is the substrate swap (the `reagent2.*` imports
;; + the slim adapter Var).

(defonce react-root (atom nil))

(defn run []
  ;; Slim adapter — the difference from `examples/reagent/counter` is
  ;; right here. Same `rf/init!` signature; different adapter Var.
  (rf/init! reagent-slim-adapter/adapter)
  (rf/dispatch-sync [:counter/initialise])
  ;; Bundle-isolation fixture, not app practice. The slim adapter's
  ;; pure-CLJS SSR seam is the contract this build exists to prove; the
  ;; sentinel exercise that makes that proof non-vacuous — and its
  ;; sub-cache teardown — is isolated in
  ;; `counter-slim-and-fast.bundle-isolation-fixture`. It runs before the
  ;; client mount so the browser mount below starts from a clean
  ;; sub-cache and owns the only live `[:counter/value]` reaction.
  (fixture/prove-pure-cljs-ssr! [counter-app])
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root [counter-app])))
