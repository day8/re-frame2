(ns login.stories-host
  "The showcase's front door — one page that wires together three things:
   the live login app, the Story shell, and Xray.

   It's hash-routed between two surfaces, so a single build serves both:

   - `#/`        → the live login app (the example's `root-view`).
   - `#/stories` → the Story shell, mounted via
                   `re-frame.story/mount-shell!`. The form-state variants
                   and the two workspaces line the sidebar, and each
                   variant renders in its own isolated frame.

   ## Xray

   Xray rides in on a shadow-cljs preload (`:devtools {:preloads
   [day8.re-frame2-xray.preload]}` — see the `:examples/login-with-stories`
   build in `implementation/shadow-cljs.edn`). The preload quietly bolts on
   the Ctrl+Shift+C keybinding and the trace / epoch collectors. We turn
   *off* auto-open here (`:rf.xray/auto-open? false`) on purpose: the page
   should greet you with the actual app or the shell, not a debugger. When
   you're curious, Ctrl+Shift+C opens Xray over whichever surface you're on,
   and a submit lets you watch the `:auth.login/submit-form` → `:rf.http/managed`
   → reply cascade roll across the Epoch / Trace / Side Effects panels — or,
   on the bad-input path, the form's pre-submit validation catch it, and on the
   failure path, the server error recorded on the machine.

   ## One adapter for everything

   The host installs the full Reagent adapter (`re-frame.adapter.reagent`) —
   the substrate the Story shell itself is built on. Happily, the example's
   `core.cljs` already runs on stock Reagent (it's the cross-substrate
   reference base), so the live app, the shell, and every variant canvas all
   share one adapter. `login.core/root-view` is a substrate-agnostic
   `reg-view`, so it doesn't mind which one.

   ## Production

   This page is a dev showcase. The production build is the plain
   `:examples/login`, whose entry never requires this namespace or
   `login.stories`. Under `:advanced` with `:closure-defines
   {re-frame.story.config/enabled? false}`, every `reg-*` in
   `login.stories` compiles to nil and `mount-shell!` short-circuits, but
   no gate builds this host that way, so nothing checks how much of Story
   and Xray it would still carry."
  (:require [re-frame.core  :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [day8.re-frame2-xray.config :as xray-config]
            ;; Pull in the example's registrations (machine / schemas / demo
            ;; fx / subs / views) and its Story artefacts. We get `login.core`
            ;; for free — `login.stories` already requires it.
            [login.core :as core]
            ;; The substrate-free model owner, for `model/frame-config` — the
            ;; one copy of the frame config all three login mounts share.
            [login.model :as model]
            [login.stories]
            [re-frame.http.stub-transport :as rf.http.stub-transport]
            ;; The shared Story-host helper, which owns the fiddly bits: the
            ;; live-app↔Story-shell hash router and the React-root handle.
            [re-frame.testbed.story-host :as rf.testbed.story-host]))

;; -- The live-app frame ----------------------------------------------------
;;
;; The live `#/` surface needs the same two pieces of setup the standalone
;; example's `login.core/run` does, so this hand-rolled frame behaves
;; identically:
;;
;; - `model/demo-backend`, installed as the frame's stub transport, answers
;;   `:rf.http/managed` in place of a backend that isn't there.
;; - `:initial-events` seeds the login-form slice at [:auth :login-form]
;;   *before* the first render. Drop it and the inputs read `nil` for their
;;   `:value` on that first paint — and React quietly demotes a `nil`-valued
;;   input to an uncontrolled one, then logs the "changing an uncontrolled
;;   input to be controlled" warning on the first keystroke. The machine
;;   seeds itself; the slice is app-db, so it has to be seeded here — exactly
;;   as `login.core/run`'s frame-root does.
;;
;; Since the host is running its own boot from top to bottom, it sets the
;; frame up itself here rather than calling `login.core/run` (which would
;; insist on running its own mount lifecycle).

;; Both come from `login.model` — the one owner the three login mounts share —
;; with `frame-config` merged in exactly as `login.core/mount!` merges it into
;; its `frame-root` props. `make-frame` takes it as ordinary record-config, so
;; the only thing this surface adds is its own `:id` / `:doc`.
(defn- install-live-frame! []
  (rf/make-frame (merge {:id  :rf/default
                         :doc "Login showcase live-app frame."}
                        model/frame-config))
  (rf.http.stub-transport/install-transport! :rf/default model/demo-backend))

(rf/reg-view login-app []
  [:div {:style {:padding "1.5em" :font-family "system-ui, sans-serif"}}
   [:p {:style {:font-size "13px" :color "#666" :margin "0 0 1em 0"}}
    "Open "
    [:a {:href "#/stories"} "#/stories"]
    " for the Story playground — every reachable login state as a "
    "variant. Press "
    [:kbd "Ctrl+Shift+C"]
    " on either surface to open Xray and inspect the auth-submit cascade."]
   [core/root-view]])

;; re-frame2 never conjures a frame for you, so the live `#/` surface has to
;; be wrapped in one explicitly (docs/core/frames.md). The Story
;; side (`#/stories`) hands out its own per-variant frames and needs no help;
;; this little wrapper exists only to scope the live surface to the
;; showcase's `:rf/default` frame, so `login-app`'s injected
;; dispatch/subscribe (and `core/root-view`'s subs) have a frame to bind to.
(defn live-app-root []
  [rf/frame-provider {:frame :rf/default} [login-app]])

;; -- Routing between the live app and the Story shell ----------------------
;;
;; We don't hand-roll the router. The live-app↔Story-shell hash routing and
;; the React-root handle both live in the shared
;; `re-frame.testbed.story-host` helper; `run` just hands it `login-app` as
;; the live surface and lets it do the wiring.

(defn ^:export run []
  ;; Leave Xray's collectors and keybinding in place, but don't fling the
  ;; panel open — the page should land on the app or the shell first, and the
  ;; reader pops Xray with Ctrl+Shift+C when they want it.
  (xray-config/configure! {:rf.xray/auto-open? false})
  (rf/init! rf.adapter.reagent/adapter)
  ;; No `(story/install-canonical-vocabulary!)` call needed: the first
  ;; `reg-*` over in `login.stories` (loaded via the require above) installs
  ;; the canonical Story vocabulary for us.
  (install-live-frame!)
  ;; Hand off to the shared router so that reloading on `#/stories` lands you
  ;; back on the shell instead of bouncing to the app. No project-root is
  ;; configured: the dev server's `POST /__rf-open-in-editor` endpoint
  ;; resolves `login/stories.cljs` against the live JVM source paths at
  ;; request time, so the 'open in editor' chips land on the real file with
  ;; nothing to configure. We pass `live-app-root` (not the bare `login-app`)
  ;; so the `#/` surface arrives already wrapped in its frame.
  (rf.testbed.story-host/mount-with-hash-routing! live-app-root))
