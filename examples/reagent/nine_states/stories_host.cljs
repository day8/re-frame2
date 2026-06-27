(ns nine-states.stories-host
  "The showcase entry point for the Nine States example — one page that
   wires together the live app, the Story shell, and Xray.

   A URL hash routes between two surfaces (the same shape as
   `counter-with-stories.core`):

   - `#/`        → the live Nine States app: the example's `root-view`
                   with its control panel and form.
   - `#/stories` → the Story shell, mounted via
                   `re-frame.story/mount-shell!`. The nine canonical
                   variants, the lifecycle variants, and three workspaces
                   all appear in the sidebar, each variant rendering in its
                   own isolated frame.

   ## Xray

   The build switches Xray on through shadow-cljs's `:devtools {:preloads
   [day8.re-frame2-xray.preload]}` (see the
   `:examples/nine-states-with-stories` build in
   `implementation/shadow-cljs.edn`). The preload installs the
   Ctrl+Shift+C keybinding and the trace / epoch collectors. We turn auto-
   open OFF (`:rf.xray/auto-open? false`) on purpose, so the page greets
   you with the live app or the Story shell rather than Xray. Press
   Ctrl+Shift+C over either surface, drive a state, and watch the
   `:nine-states.story/load` → `:rf.http/managed` → reply cascade light up
   the Epoch / Trace / Side Effects panels.

   ## One adapter

   This host installs the full Reagent adapter (`re-frame.adapter.reagent`)
   — the substrate the Story shell itself renders on — and runs everything
   on it: live app, shell, and every variant canvas. (The example's own
   `core.cljs` mounts on the stock Reagent adapter for its standalone
   `:examples/nine-states` build; this showcase is a separate entry point.)
   It all works because `nine-states.core/root-view` is a
   substrate-agnostic `reg-view` — it renders identically either way.

   ## Elision

   Compiled under `:advanced` with `:closure-defines
   {re-frame.story.config/enabled? false}`, every `reg-*` in
   `nine-states.stories` compiles down to nil and `mount-shell!`
   short-circuits — not one byte of the Story body rides along into a
   production bundle. The bundle-isolation gate keeps it honest by checking
   that the Story / Xray sentinels never show up in the plain
   `:examples/nine-states` build."
  (:require [re-frame.core  :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [day8.re-frame2-xray.config :as xray-config]
            ;; Pull in the example's registrations (machine / schemas / demo
            ;; events / subs / the `:ui/render` selector / views) and the
            ;; Story artefacts. Requiring `nine-states.stories` drags in
            ;; `nine-states.core` along with it.
            [nine-states.core :as core]
            [nine-states.stories]
            ;; The shared Story-host helper — it owns the
            ;; live-app↔Story-shell hash router and the React-root handle,
            ;; so we don't have to.
            [re-frame.testbed.story-host :as story-host])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- The live-app frame ----------------------------------------------------
;;
;; This host runs its own boot rather than calling `core/run` (which brings
;; its own stock-Reagent mount lifecycle along). So it stands up the live-app
;; frame right here, handing the `#/` surface the example's demo HTTP
;; override — `:rf.http/managed` routed to the in-process
;; `:nine-states.http/managed-demo` stub, no backend in the loop.

(defn- install-live-frame! []
  (rf/reg-frame :rf/default
    {:doc          "Nine-states showcase live-app frame."
     :fx-overrides {:rf.http/managed :nine-states.http/managed-demo}})
  ;; Now seed it. The `with-frame :rf/default` wrapper is what aims the
  ;; `dispatch-sync` at that frame — dispatch with no frame in scope and
  ;; you'd get `:rf.error/no-frame-context`.
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:nine-states.app/initialise])))

(reg-view nine-states-app []
  [:div {:style {:padding "1.5em" :font-family "system-ui, sans-serif"}}
   [:p {:style {:font-size "13px" :color "#666" :margin "0 0 1em 0"}}
    "Open "
    [:a {:href "#/stories"} "#/stories"]
    " for the Story playground — every one of the nine UI states as a "
    "variant. Press "
    [:kbd "Ctrl+Shift+C"]
    " on either surface to open Xray and inspect the fetch cascade."]
   [core/root-view]])

;; Wrap the live `#/` surface in the showcase's `:rf/default` frame, so the
;; dispatch/subscribe injected into `nine-states-app` (and `core/root-view`'s
;; subs) resolve to it. The Story side (`#/stories`) hands out its own
;; per-variant frames, so this wrapper is only about the live app. We pass it
;; to the shared story-host as the live-app root (same as
;; counter-with-stories.core).
(defn live-app-root []
  [rf/frame-provider {:frame :rf/default} [nine-states-app]])

;; -- Routing between the live app and the Story shell ----------------------
;;
;; The live-app↔Story-shell hash router and the React-root handle both live
;; in the shared `re-frame.testbed.story-host` helper, so `run` just hands it
;; `nine-states-app` as the live-app surface and lets it do the rest (same
;; as counter-with-stories.core).

(defn ^:export run []
  ;; Keep Xray's collectors and keybinding installed, but don't pop the
  ;; panel open on load — the page should show the live app / shell first,
  ;; and the reader opens Xray with Ctrl+Shift+C when they're ready.
  (xray-config/configure! {:rf.xray/auto-open? false})
  (rf/init! reagent-adapter/adapter)
  ;; No `(story/install-canonical-vocabulary!)` call needed — the first
  ;; `reg-*` over in `nine-states.stories` (loaded via the require above)
  ;; installs the canonical Story vocabulary for us.
  (install-live-frame!)
  ;; Wire up the live-app↔Story-shell hash router (the shared helper), so
  ;; reloading `#/stories` lands back on the shell with no manual click.
  ;; The `:source-subdir` opt is the important bit: it tells the host that
  ;; this showcase's Story source-coords are classpath-relative to
  ;; `examples/reagent`, so it can find the on-disk project root, call
  ;; `story/configure!` itself, and bridge that root into Xray's slot.
  ;; Leave it off and the Story 'open in editor' chips would try to resolve
  ;; `nine_states/stories.cljs` against a nil root — and quietly fail. The
  ;; live-app root is already frame-scoped by `live-app-root` (the
  ;; `frame-provider` wrapper), so the bare `#/` surface mounts under the
  ;; app frame.
  (story-host/mount-with-hash-routing! live-app-root {:source-subdir "examples/reagent"}))
