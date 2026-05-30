(ns counter-with-stories.core
  "Entry point. URL-hash-routed between two surfaces:

  - `#/`        → the live counter app (the `counter-card` view).
  - `#/stories` → the Story shell mounted via
                  `re-frame.story/mount-shell!`. The four counter
                  variants + two workspaces show up in the sidebar.

  Per IMPL-SPEC §6.5 + Stage 8: when this example is compiled under
  `:advanced` with `:closure-defines {re-frame.story.config/enabled?
  false}`, every `reg-*` form in `counter-with-stories.stories`
  elides to `nil`, `mount-shell!` short-circuits, and the bundle
  carries no Story body code. The bundle-isolation grep at
  `implementation/scripts/check-bundle-isolation.cjs` verifies the
  Story-sentinel set is absent under that build."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core      :as rf]
            [re-frame.story     :as story]
            [re-frame.story.play.ci-runner :as story-ci]
            [re-frame.adapter.reagent :as reagent-adapter]
            [day8.re-frame2-xray.config :as xray-config]
            ;; Source the events + subs + views via the stories ns,
            ;; which itself requires them. When Story is elided the
            ;; stories ns still loads (it's a regular CLJS ns) but
            ;; every reg-* expansion elides to nil.
            [counter-with-stories.views :as views]
            ;; Privacy + Size elision demo. Requiring the ns fires
            ;; the `:auth/sign-in` :sensitive? handler reg,
            ;; the `:user/avatar-pdf` :large? schema reg, the demo
            ;; subs, and exposes `install-listener!` for the boot
            ;; sequence below.
            [counter-with-stories.elision-demo :as elision]
            [counter-with-stories.stories]
            ;; Shared testbed-config helper (rf2-5dphw): derives the
            ;; open-in-editor project-root from the build env.
            [re-frame.testbed.config :as testbed-config])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- The live-app root view ------------------------------------------------

(reg-view counter-app []
  [:div {:style {:padding "2em" :font-family "system-ui, sans-serif"}}
   [:h2 {:style {:margin "0 0 0.5em 0"}}
    "Counter (with Stories)"]
   [:p {:style {:font-size "13px" :color "#666" :margin "0 0 1em 0"}}
    "Open "
    [:a {:href "#/stories"} "#/stories"]
    " for the Story playground."]
   [views/counter-card {:label "Count"}]
   [elision/elision-card]])

;; -- rf2-r1uod / rf2-5dphw — Xray 'Open in editor' project-root.
;;
;; Story testbeds register source-coords with classpath-relative `:file`
;; slots (e.g. `"counter_with_stories/core.cljs"`); OS-side editor URI
;; handlers (`vscode://file/<path>...` etc.) resolve `<path>` against the
;; filesystem and reject relative paths, so a testbed must hand Story an
;; absolute on-disk root that prepends to a coord like
;; `counter_with_stories/core.cljs:42`. That root is `<repo>/tools/story/
;; testbeds` (the Story testbeds source-path under shadow-cljs).
;;
;; The value is plumbed via `story/configure! :rf.story/project-root` and
;; bridged into Xray's slot by `re-frame.story.xray-preset/propagate-
;; project-root!` so both Story's own 'Open' chips and Xray-as-RHS's chips
;; (the Event lens Handler / Dispatch / Interceptors, Trace rows, Issues
;; ribbon) resolve against the same root.
;;
;; rf2-5dphw — the root is DERIVED from the build environment (the
;; build-time `re-frame.testbed.config/repo-root` goog-define joined with
;; this testbed's tool-relative subdir), NOT a hardcoded personal path, so
;; a fresh clone at any path on any OS gets working open-in-editor. A
;; `?project-root=<path>` query string still overrides per session (CI,
;; another dev's machine) — no code change needed. See
;; `re-frame.testbed.config` for the cross-platform mechanism.
(defn- resolve-project-root []
  (testbed-config/resolve-project-root "tools/story/testbeds"))

;; -- Routing between app and story shell ----------------------------------
;;
;; The live app and the Story shell each own their own React root on
;; the same `#app` DOM node, one at a time. The live app's root lives
;; in `app-root` here; the Story shell allocates and owns its root
;; internally via `rdc/create-root` inside `mount-shell!`. We tear
;; one down before mounting the other.

(defonce ^:private app-root (atom nil))

(defn- ensure-app-root! []
  (when (nil? @app-root)
    (reset! app-root (rdc/create-root (js/document.getElementById "app")))))

(defn- tear-down-app-root! []
  (when-let [r @app-root]
    (try (rdc/unmount r) (catch :default _ nil))
    (reset! app-root nil)))

(defn- mount-app! []
  (story/unmount-shell!)
  (ensure-app-root!)
  (rdc/render @app-root [counter-app]))

(defn- mount-stories! []
  (tear-down-app-root!)
  (story/mount-shell! (js/document.getElementById "app")))

(defn- on-hash-change! []
  (let [hash (or (.. js/window -location -hash) "")]
    (if (re-find #"^#/stories" hash)
      (mount-stories!)
      (mount-app!))))

(defn ^:export run []
  ;; Story owns this page's full-width browser-test canvas. When the
  ;; Xray preload is present in shared dev test runs, keep its trace
  ;; collectors/API/keybinding installed but skip the default panel
  ;; launch; app pages that want Xray inline still provide the normal
  ;; `[data-rf-xray-host]` contract.
  (xray-config/configure! {:rf.xray/auto-open? false})
  (rf/init! reagent-adapter/adapter)
  ;; No explicit `(story/install-canonical-vocabulary!)` call — the
  ;; first `reg-*` in `counter-with-stories.stories` (loaded via
  ;; :require above) auto-installs the canonical vocabulary on demand
  ;; per rf2-p1ydc + spec/001 §Boot. The explicit call is legacy
  ;; (rf2-y8gag — audit D-2) and removed from every canonical testbed.
  ;; Configure the global args layer (Layer 1 of the args-precedence
  ;; chain; see IMPL-SPEC §5.2). The stories layer their own args on
  ;; top via reg-story / reg-variant.
  ;;
  ;; rf2-r1uod — `:project-root` seeds Story's own 'Open' chips AND
  ;; (via the xray-preset bridge) Xray-as-RHS's open-in-editor
  ;; chips so the Event lens / Trace rows / Issues ribbon resolve
  ;; their classpath-relative source-coord `:file` slots to absolute
  ;; on-disk URIs the OS-side editor handler can stat. Symmetric to
  ;; shop's rf2-6jyf6. The `?project-root=...` query string lets
  ;; other hosts override the mayor-checkout default without a code
  ;; change.
  (story/configure! {:rf.story/global-args  {:locale :en}
                     :rf.story/project-root (resolve-project-root)})
  ;; Seed the live app's `:count` slot.
  (rf/dispatch-sync [:counter/initialise 5])
  ;; Install the always-on event-emit listener. The listener prints
  ;; every dispatched event's elided record to the browser console —
  ;; visitors can see `:rf/redacted` substitution for the `:sensitive?`
  ;; handler and the `:rf.size/large-elided` marker for the `:large?`
  ;; schema slot without needing the trace surface or Xray attached.
  (elision/install-listener!)
  ;; rf2-3qcxk — install the CI-as-test global hook the Playwright
  ;; play-script runner reads. Inert until the runner polls it; safe
  ;; to install unconditionally because the function body is gated
  ;; on Story being enabled (the ns itself is Story-tooling).
  (story-ci/install-ci-hooks!)
  ;; Wire hash-change so reloading `#/stories` lands on the shell
  ;; without a manual click-through.
  (.addEventListener js/window "hashchange" on-hash-change!)
  (on-hash-change!))
