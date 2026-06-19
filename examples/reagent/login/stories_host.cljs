(ns login.stories-host
  "Showcase entry point for the login example — the host that wires the
   live app, the Story shell, and Xray into one page.

   URL-hash-routed between two surfaces (mirrors
   `nine-states.stories-host`):

   - `#/`        → the live login app (the example's `root-view`).
   - `#/stories` → the Story shell mounted via
                   `re-frame.story/mount-shell!`. The reachable
                   form-state variants + two workspaces show up in the
                   sidebar; each variant renders in its own isolated
                   frame.

   ## Xray

   The build wires Xray via shadow-cljs's `:devtools {:preloads
   [day8.re-frame2-xray.preload]}` (see the `:examples/login-with-
   stories` build in `implementation/shadow-cljs.edn`). Loading the
   preload attaches the Ctrl+Shift+C keybinding and the trace / epoch
   collectors. We DISABLE auto-open here (`:rf.xray/auto-open? false`)
   so the page lands on the live app or the Story shell first; the
   reader presses Ctrl+Shift+C to open Xray over either surface and
   drive a submit to watch the `:auth.login/submit` →
   `:rf.http/managed` → reply cascade light up the Epoch / Trace / Side
   Effects panels (and the schema-rejection / failure paths light the
   Issues ribbon).

   ## One adapter

   The host installs the FULL Reagent adapter (`re-frame.adapter.
   reagent`) — the substrate the Story shell itself renders on. The
   example's own `core.cljs` already runs on stock Reagent (it is the
   cross-substrate reference base), so the live app + shell + variant
   canvases all render on one adapter. `login.core/root-view` is a
   substrate-agnostic `reg-view`.

   ## Elision

   Compiled under `:advanced` with `:closure-defines
   {re-frame.story.config/enabled? false}`, every `reg-*` in
   `login.stories` elides to nil and `mount-shell!` short-circuits —
   the Story body carries no code into a production bundle. The
   bundle-isolation gate verifies the Story / Xray sentinel sets stay
   out of the plain `:examples/login` build."
  (:require [re-frame.core  :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [day8.re-frame2-xray.config :as xray-config]
            ;; Source the example's registrations (machine / schemas /
            ;; demo fx / subs / views) and the Story artefacts. Requiring
            ;; `login.stories` transitively requires `login.core`.
            [login.core :as core]
            [login.stories]
            ;; Shared Story-host helper (rf2-tq26t / rf2-uv7sn): owns the
            ;; live-app↔Story-shell hash router + React-root handle.
            [re-frame.testbed.story-host :as story-host])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- The live-app frame ----------------------------------------------------
;;
;; The live `#/` surface needs the example's demo HTTP override on its
;; frame so `:rf.http/managed` routes to the in-process
;; `:auth.login.demo/managed-stub` (no backend). This mirrors what
;; `login.core/run` installs on `:rf/default`; we register it here so
;; the host owns the full boot (the host does not call `core/run`,
;; which hardcodes its own mount lifecycle).

(defn- install-live-frame! []
  (rf/reg-frame :rf/default
    {:doc          "Login showcase live-app frame."
     :fx-overrides {:rf.http/managed :auth.login.demo/managed-stub}}))

(reg-view login-app []
  [:div {:style {:padding "1.5em" :font-family "system-ui, sans-serif"}}
   [:p {:style {:font-size "13px" :color "#666" :margin "0 0 1em 0"}}
    "Open "
    [:a {:href "#/stories"} "#/stories"]
    " for the Story playground — every reachable login state as a "
    "variant. Press "
    [:kbd "Ctrl+Shift+C"]
    " on either surface to open Xray and inspect the auth-submit cascade."]
   [core/root-view]])

;; EP-0002 (rf2-9o48ih): the runtime never synthesises a frame from absence,
;; so the live-app `#/` surface must render under an explicit frame scope. The
;; Story shell side (`#/stories`) allocates its own per-variant frames; this
;; wrapper scopes only the live-app surface to the showcase's `:rf/default`
;; frame so `login-app`'s reg-view-injected dispatch/subscribe (and
;; `core/root-view`'s subs) resolve. Passed to the shared story-host as the
;; live-app root view (mirrors counter-with-stories.core).
(defn live-app-root []
  [rf/frame-provider-existing {:frame :rf/default} [login-app]])

;; -- Routing between the live app and the Story shell ----------------------
;;
;; The live-app↔Story-shell hash router + React-root host handle live in the
;; shared `re-frame.testbed.story-host` helper (rf2-tq26t / rf2-uv7sn); `run`
;; hands it `login-app` as the live-app surface (mirrors
;; nine-states.stories-host).

(defn ^:export run []
  ;; Keep Xray's collectors + keybinding installed but do not auto-open
  ;; the panel — the page lands on the live app / shell first; the
  ;; reader opens Xray with Ctrl+Shift+C.
  (xray-config/configure! {:rf.xray/auto-open? false})
  (rf/init! reagent-adapter/adapter)
  ;; No explicit `(story/install-canonical-vocabulary!)` — the first
  ;; `reg-*` in `login.stories` (loaded via the require above)
  ;; auto-installs the canonical Story vocabulary (rf2-p1ydc).
  (install-live-frame!)
  ;; Wire the live-app↔Story-shell hash router (shared helper) so reloading
  ;; `#/stories` lands on the shell without a manual click-through. The
  ;; `:story-subdir` opt (rf2-77wqzi) tells the host this showcase's Story
  ;; source-coords are classpath-relative to `examples/reagent`, so the host
  ;; resolves the on-disk project-root (build-env define or `?project-root=`
  ;; override, cross-platform) and calls `story/configure!` itself — that
  ;; also bridges the root into Xray's slot. Without it the Story 'open in
  ;; editor' chips would resolve `login/stories.cljs` against a nil root. The
  ;; live-app root is frame-scoped via `live-app-root` (the `frame-provider`
  ;; wrapper) so the bare `#/` surface mounts under the app frame.
  (story-host/mount-with-hash-routing! live-app-root {:story-subdir "examples/reagent"}))
