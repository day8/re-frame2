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

   The slim adapter's bundle-isolation proof is NOT here: it is kept out
   of this teaching surface entirely, behind the gate-owned entrypoint
   `counter-slim-and-fast.bundle-isolation-entry` (which the
   `:examples/counter-slim-and-fast` build uses as its `:init-fn`). That
   entry boots the SAME app through the shared `boot!` helper below — it
   does not re-copy the boot sequence — and passes a pre-mount hook that
   exercises the pure-CLJS SSR path the gate inspects; nothing about that
   fixture plumbing leaks into this namespace, and the two boot paths
   cannot drift because there is only one."
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
;; reg-view is substrate-agnostic — the macro expands to a plain
;; React-component shape that consults the *active* adapter's
;; `register-context-provider` / `current-component` seams at render
;; time. So the very same view form renders through whichever substrate
;; was installed at boot. With the slim adapter installed (see `boot!`
;; below), the rendered component reads frame state through
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

;; The runtime never synthesises a frame from absence — an app must
;; establish its frame explicitly. `init!` installs the adapter (it does
;; NOT create the frame), `reg-frame` registers the app frame, the boot
;; dispatch runs under `with-frame`, and the client render is wrapped in
;; `frame-provider-existing` so every in-tree `dispatch`/`subscribe`
;; resolves to the app frame. `frame-provider-existing` is the scope-only
;; sibling — it provides the ALREADY-registered frame; the lifecycle-owning
;; `frame-provider` (which would create a frame on mount) is deliberately
;; not used, since `reg-frame` already established it. Matches the canonical
;; mount in examples/reagent/counter/core.cljs.
(def app-frame :rf/default)

(defn boot!
  "The one canonical boot sequence for this example: install the slim
   adapter, register the app frame, dispatch the boot event under the frame
   scope, then lazily mount `counter-app` into `#app` under a
   `frame-provider-existing` (the scope-only sibling — `reg-frame` already
   created the frame, so the mount only re-scopes into it, not re-creates it).

   `boot!` is the single source of truth for the boot so the teaching path
   (`run` below) and the gate-owned path
   (`counter-slim-and-fast.bundle-isolation-entry/run`) cannot drift: both
   call this fn, so a future EP/API boot change is made in one place.

   `on-frame` is an optional thunk run inside the `with-frame` scope, after
   the boot dispatch and before the client mount. The teaching `run` passes
   nothing; only the bundle-isolation entry uses it, to weave its pure-CLJS
   SSR exercise into the frame scope at the one point its ordering requires.
   Keeping the seam here — rather than re-copying the boot in the entry — is
   what keeps `core` free of fixture plumbing while preventing drift."
  ([] (boot! nil))
  ([on-frame]
   ;; Slim adapter — the difference from `examples/reagent/counter` is
   ;; right here. Same `rf/init!` signature; different adapter Var.
   (rf/init! reagent-slim-adapter/adapter)
   (rf/reg-frame app-frame {})
   (rf/with-frame app-frame
     (rf/dispatch-sync [:counter/initialise])
     (when on-frame (on-frame)))
   (when (exists? js/document)
     (when-not @react-root
       (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
     (rdc/render @react-root
                 [rf/frame-provider-existing {:frame app-frame}
                  [counter-app]]))))

(defn run []
  (boot!))
