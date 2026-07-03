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
   and a submit lets you watch the `:auth.login/submit` → `:rf.http/managed`
   → reply cascade roll across the Epoch / Trace / Side Effects panels — or,
   on the bad-input and failure paths, light up the Issues ribbon.

   ## One adapter for everything

   The host installs the full Reagent adapter (`re-frame.adapter.reagent`) —
   the substrate the Story shell itself is built on. Happily, the example's
   `core.cljs` already runs on stock Reagent (it's the cross-substrate
   reference base), so the live app, the shell, and every variant canvas all
   share one adapter. `login.core/root-view` is a substrate-agnostic
   `reg-view`, so it doesn't mind which one.

   ## Elision — none of this reaches production

   Compile under `:advanced` with `:closure-defines
   {re-frame.story.config/enabled? false}` and the whole Story layer simply
   evaporates: every `reg-*` in `login.stories` compiles to nil and
   `mount-shell!` short-circuits, so not a byte of showcase code rides into a
   production bundle. That's not a promise on trust, either — the
   bundle-isolation gate fails the build if any Story or Xray sentinel sneaks
   into the plain `:examples/login` output."
  (:require [re-frame.core  :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [day8.re-frame2-xray.config :as xray-config]
            ;; Pull in the example's registrations (machine / schemas / demo
            ;; fx / subs / views) and its Story artefacts. We get `login.core`
            ;; for free — `login.stories` already requires it.
            [login.core :as core]
            [login.stories]
            ;; The shared Story-host helper, which owns the fiddly bits: the
            ;; live-app↔Story-shell hash router and the React-root handle.
            [re-frame.testbed.story-host :as story-host]))

;; -- The live-app frame ----------------------------------------------------
;;
;; The live `#/` surface needs the same demo-HTTP override the standalone
;; example uses, so `:rf.http/managed` lands on the in-process
;; `:auth.login.demo/managed-stub` and not on a backend that isn't there.
;; Since the host is running its own boot from top to bottom, it sets the
;; override up itself here rather than calling `login.core/run` (which would
;; insist on running its own mount lifecycle).

(defn- install-live-frame! []
  (rf/reg-frame :rf/default
    {:doc          "Login showcase live-app frame."
     :fx-overrides {:rf.http/managed :auth.login.demo/managed-stub}}))

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
;; be wrapped in one explicitly (docs/core/concepts/frames.md). The Story
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
  (rf/init! reagent-adapter/adapter)
  ;; No `(story/install-canonical-vocabulary!)` call needed: the first
  ;; `reg-*` over in `login.stories` (loaded via the require above) installs
  ;; the canonical Story vocabulary for us.
  (install-live-frame!)
  ;; Hand off to the shared router so that reloading on `#/stories` lands you
  ;; back on the shell instead of bouncing to the app. The `:source-subdir`
  ;; opt is the one thing the helper can't guess: it says this showcase's
  ;; Story source lives under `examples/core`, which lets the host find the
  ;; on-disk project root and pass it to Story and Xray. Leave it out and the
  ;; 'open in editor' chips try to resolve `login/stories.cljs` against a nil
  ;; root — and miss. We pass `live-app-root` (not the bare `login-app`) so
  ;; the `#/` surface arrives already wrapped in its frame.
  (story-host/mount-with-hash-routing! live-app-root {:source-subdir "examples/core"}))
