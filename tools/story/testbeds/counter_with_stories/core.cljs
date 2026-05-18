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
            [day8.re-frame2-causa.config :as causa-config]
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
            [counter-with-stories.stories])
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

;; -- rf2-r1uod — Causa 'Open in editor' project-root for the live testbed.
;;
;; Story testbeds register source-coords with classpath-relative `:file`
;; slots (e.g. `"counter_with_stories/core.cljs"`); OS-side editor URI
;; handlers (`vscode://file/<path>...` etc.) resolve `<path>` against the
;; filesystem and reject relative paths. Mike's live testbed runs from
;; the mayor checkout `C:/Users/miket/code/re-frame2`; the Story
;; testbeds source-path under shadow-cljs is `../tools/story/testbeds`
;; so the absolute on-disk root that prepends to a coord like
;; `counter_with_stories/core.cljs:42` is the testbeds dir below.
;;
;; The value is plumbed via `story/configure! :project-root` and bridged
;; into Causa's slot by `re-frame.story.causa-preset/propagate-project-
;; root!` so both Story's own 'Open' chips and Causa-as-RHS's chips (the
;; Event lens Handler / Dispatch / Interceptors, Trace rows, Issues
;; ribbon) resolve against the same root.
;;
;; Symmetric to shop's rf2-6jyf6; other hosts (CI, other devs) can
;; override at runtime via the `?project-root=...` query string — no
;; code change needed.

(def ^:private default-project-root
  "C:/Users/miket/code/re-frame2/tools/story/testbeds")

(defn- query-param
  "Return the named URL query param as a string, or nil when absent
  / blank. Pure-data helper — kept private to this testbed since the
  query-string override is a per-host knob (not a Story-API surface)."
  [name]
  (when (exists? js/window)
    (let [params (-> js/window .-location .-search
                     (js/URLSearchParams.))
          v      (.get params name)]
      (when (and (string? v) (seq v)) v))))

(defn- resolve-project-root []
  (or (query-param "project-root") default-project-root))

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
  ;; Causa preload is present in shared dev test runs, keep its trace
  ;; collectors/API/keybinding installed but skip the default panel
  ;; launch; app pages that want Causa inline still provide the normal
  ;; `[data-rf-causa-host]` contract.
  (causa-config/configure! {:launch/auto-open? false})
  (rf/init! reagent-adapter/adapter)
  ;; Install the Story canonical vocabulary (seven tags, lifecycle
  ;; machine, seven :rf.assert/* handlers, force-fx-stub decorator,
  ;; layout-debug decorators, multi-substrate Reagent default, v1
  ;; panel set). Idempotent. Under :advanced + enabled?=false this
  ;; short-circuits internally.
  (story/install-canonical-vocabulary!)
  ;; Configure the global args layer (Layer 1 of the args-precedence
  ;; chain; see IMPL-SPEC §5.2). The stories layer their own args on
  ;; top via reg-story / reg-variant.
  ;;
  ;; rf2-r1uod — `:project-root` seeds Story's own 'Open' chips AND
  ;; (via the causa-preset bridge) Causa-as-RHS's open-in-editor
  ;; chips so the Event lens / Trace rows / Issues ribbon resolve
  ;; their classpath-relative source-coord `:file` slots to absolute
  ;; on-disk URIs the OS-side editor handler can stat. Symmetric to
  ;; shop's rf2-6jyf6. The `?project-root=...` query string lets
  ;; other hosts override the mayor-checkout default without a code
  ;; change.
  (story/configure! {:global-args  {:locale :en}
                     :project-root (resolve-project-root)})
  ;; Seed the live app's `:count` slot.
  (rf/dispatch-sync [:counter/initialise 5])
  ;; Install the always-on event-emit listener. The listener prints
  ;; every dispatched event's elided record to the browser console —
  ;; visitors can see `:rf/redacted` substitution for the `:sensitive?`
  ;; handler and the `:rf.size/large-elided` marker for the `:large?`
  ;; schema slot without needing the trace surface or Causa attached.
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
