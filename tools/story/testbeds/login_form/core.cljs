(ns login-form.core
  "Login-form testbed entry (rf2-0sg12).

  URL-hash-routed between two surfaces:

    `#/`        → the live login card (the `login-card` view).
    `#/stories` → the Story shell. The five login-flow variants and
                  two workspaces show up in the sidebar.

  Per `001-Authoring.md` §Registration macros + Stage 8: when compiled under `:advanced` with
  `:closure-defines {re-frame.story.config/enabled? false}`, every
  reg-* form in `login-form.stories` elides to nil; `mount-shell!`
  short-circuits; the bundle carries no Story body code. The
  bundle-isolation grep at `implementation/scripts/check-bundle-
  isolation.cjs` covers the Story-sentinel absence."
  (:require [re-frame.core      :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [day8.re-frame2-xray.config :as xray-config]
            [login-form.events]
            [login-form.subs]
            [login-form.views :as views]
            [login-form.stories]
            ;; Shared Story-host helper (rf2-tq26t / rf2-uv7sn): owns the
            ;; live-app↔Story-shell hash router + React-root handle, and
            ;; (rf2-77wqzi) the open-in-editor project-root config via the
            ;; `:source-subdir` opt.
            [re-frame.testbed.story-host :as story-host])
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

;; EP-0002 (rf2-9o48ih): the runtime never synthesises a frame from absence,
;; so the live-app surface must render under an explicit frame scope. The
;; Story shell side (`#/stories`) allocates its own per-variant frames; this
;; wrapper scopes only the live-app `#/` surface to the testbed's `:rf/default`
;; frame so `login-app`'s reg-view-injected dispatch/subscribe resolve. Passed
;; to the shared story-host as the live-app root view (mirrors
;; counter-with-stories.core).
(defn live-app-root []
  [rf/frame-provider-existing {:frame :rf/default} [login-app]])

;; ---------------------------------------------------------------------------
;; Hash-routing between the live app and the Story shell
;; ---------------------------------------------------------------------------
;;
;; The live-app↔Story-shell hash router + React-root host handle live in the
;; shared `re-frame.testbed.story-host` helper (rf2-tq26t / rf2-uv7sn); `run`
;; hands it `login-app` as the live-app surface.

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
  ;;
  ;; EP-0002 (rf2-9o48ih): `init!` installs the adapter only and a
  ;; frameless `dispatch-sync` raises `:rf.error/no-frame-context`.
  ;; Run the seed dispatch inside the testbed's `:rf/default` frame
  ;; scope — symmetric to counter-with-stories' migrated boot.
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:login/flow [:login/dismiss]]))
  ;; Wire the live-app↔Story-shell hash router (shared helper). The live-app
  ;; root is frame-scoped via `live-app-root` (the
  ;; `frame-provider-existing` wrapper — scope-only into the already-
  ;; registered `:rf/default` frame).
  ;; The `:source-subdir` opt (rf2-77wqzi) hands the host this testbed's
  ;; tool-relative source subdir; the host resolves the on-disk
  ;; open-in-editor project-root (build-env define or `?checkout-root=`
  ;; override, cross-platform) and calls `story/configure!` itself — which
  ;; also bridges the root into Xray's slot. Replaces the former inline
  ;; `resolve-source-root` + `story/configure!`.
  (story-host/mount-with-hash-routing! live-app-root {:source-subdir "tools/story/testbeds"}))
