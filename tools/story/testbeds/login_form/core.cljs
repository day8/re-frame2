(ns login-form.core
  "Login-form testbed entry (rf2-0sg12).

  URL-hash-routed between two surfaces:

    `#/`        → the live login card (the `login-card` view).
    `#/stories` → the Story shell. The five login-flow variants and
                  two workspaces show up in the sidebar.

  Per IMPL-SPEC §6.5 + Stage 8: when compiled under `:advanced` with
  `:closure-defines {re-frame.story.config/enabled? false}`, every
  reg-* form in `login-form.stories` elides to nil; `mount-shell!`
  short-circuits; the bundle carries no Story body code. The
  bundle-isolation grep at `implementation/scripts/check-bundle-
  isolation.cjs` covers the Story-sentinel absence."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core      :as rf]
            [re-frame.story     :as story]
            [re-frame.adapter.reagent :as reagent-adapter]
            [day8.re-frame2-xray.config :as xray-config]
            [login-form.events]
            [login-form.subs]
            [login-form.views :as views]
            [login-form.stories]
            ;; Shared testbed-config helper (rf2-5dphw): derives the
            ;; open-in-editor project-root from the build env.
            [re-frame.testbed.config :as testbed-config])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ---------------------------------------------------------------------------
;; Live-app root view
;; ---------------------------------------------------------------------------

(reg-view login-app []
  [:div {:style {:padding "2em" :font-family "system-ui, sans-serif"
                 :max-width "640px" :margin "0 auto"}}
   [:h2 {:style {:margin "0 0 0.5em 0"}}
    "Login form (Story tutorial testbed)"]
   [:p {:style {:font-size "13px" :color "#666" :margin "0 0 1.5em 0"}}
    "Try password "
    [:code {:style {:background "#f5f5f5" :padding "1px 4px"}}
     "correct-horse"]
    " for success, anything else for the error path. Open "
    [:a {:href "#/stories"} "#/stories"]
    " for the five-variant Story playground."]
   [views/login-card {:heading "Sign in"}]
   [:aside {:style {:font-size "12px" :color "#555"
                    :background "#f5f5f5" :border "1px solid #e0e0e0"
                    :padding "8px 12px" :margin-top "2em" :border-radius "4px"}}
    [:strong "Tutorial testbed."]
    " The five FSM states from "
    [:a {:href "../docs/story/"} "the Story tutorial"]
    "'s scenario are runnable variants of this card. Open #/stories,"
    " click each state in the sidebar, mount the workspace to see all"
    " five side-by-side, switch to Test mode and watch the assertions"
    " flip green."]])

;; ---------------------------------------------------------------------------
;; rf2-r1uod — Xray-as-RHS open-in-editor project-root for the live
;; testbed. Story testbeds register source-coords with classpath-
;; relative `:file` slots; OS-side editor URI handlers reject relative
;; paths. The Story testbeds source-path under shadow-cljs is
;; `../tools/story/testbeds` so the on-disk root that prepends to a
;; coord like `login_form/stories.cljs:42` is the testbeds dir below.
;; Plumbed via `story/configure! :rf.story/project-root` and bridged into
;; Xray's slot by `xray-preset/propagate-project-root!`. Symmetric
;; to shop's rf2-6jyf6.
;; ---------------------------------------------------------------------------

;; rf2-5dphw — open-in-editor project-root derived from the build
;; environment (the build-time `re-frame.testbed.config/repo-root`
;; goog-define joined with this testbed's tool-relative subdir), not a
;; hardcoded personal path. `?project-root=<path>` still overrides per
;; session. See `re-frame.testbed.config` for the cross-platform mechanism.
(defn- resolve-project-root []
  (testbed-config/resolve-project-root "tools/story/testbeds"))

;; ---------------------------------------------------------------------------
;; Hash-routing between the live app and the Story shell
;; ---------------------------------------------------------------------------

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
  (rdc/render @app-root [login-app]))

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
  ;; No explicit `(story/install-canonical-vocabulary!)` — the first
  ;; `reg-*` in `login-form.stories` (loaded via the :require above)
  ;; auto-installs the canonical vocabulary per rf2-p1ydc (audit D-2
  ;; / rf2-y8gag).
  ;; rf2-r1uod — `:rf.story/project-root` plumbed through Story; the
  ;; `xray-preset` bridge propagates it into Xray's slot so the
  ;; Xray-as-RHS open-in-editor chips resolve absolute on-disk paths.
  (story/configure! {:rf.story/project-root (resolve-project-root)})
  ;; The live page wires `:rf.http/managed` to a demo override so
  ;; submit / retry have something to do. Story variants don't see
  ;; this — they allocate their own frames and the `force-fx-stub`
  ;; decorator overrides `:rf.http/managed` per-frame.
  (rf/reg-frame :rf/default
    {:doc          "Login-form testbed default frame."
     :fx-overrides {:rf.http/managed :login/demo-http}})
  ;; Seed the FSM by routing a no-op event into :login/flow. The
  ;; machine self-initialises (per [005 §Restore semantics]) — its
  ;; :initial state and :data seed the snapshot on first dispatch.
  ;; Without this, the live page's state-pill renders "state: "
  ;; (nil) until the user submits.
  (rf/dispatch-sync [:login/flow [:login/dismiss]])
  (.addEventListener js/window "hashchange" on-hash-change!)
  (on-hash-change!))
