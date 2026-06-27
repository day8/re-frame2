(ns boot.views
  "Views for the boot example. See views in the guide
   (docs/guide/glossary.md#view).

   The view tree has two halves split by the boot state:

   1. **Pre-ready** — while the boot machine is in any non-terminal
      state, the root renders a `[boot-progress]` screen showing the
      current phase. The main app view is NOT mounted yet — its
      subscriptions would read empty slices and have to defend against
      `nil` everywhere.

   2. **Post-ready** — once the boot machine reaches `:ready`, the root
      swaps in `[main-app]`. The main view freely reads `:config`,
      `:flags`, `:user`, and `:routes` from app-db; the slices are
      populated and stable.

   The failure path renders `[boot-failed]` (terminal `:failed`), which
   surfaces the error and offers a retry button. The retry dispatches
   `[:app/boot [:boot/restart]]` straight back into the boot machine,
   re-running it from `:configuring`. Re-boot uses a real event, not the
   `:rf.machine/start` creation marker — the marker is inert once the
   machine already exists.

   Splitting the views by boot state keeps all the loading logic in the
   boot machine. The alternative — mount the main app and let each
   sub-view render its own per-phase skeleton — scatters that logic
   across the whole view tree. Here the views just read `:app.boot/ready?`.

   The views are intentionally minimal — the goal is to show the boot
   shape, not a polished UI."
  (:require [re-frame.core :as rf]
            [boot.boot])
  (:require-macros [re-frame.core :refer [reg-view]]))

(reg-view ^{:doc "Pre-ready screen — visible while the boot machine
                  is in any non-terminal state."}
          boot-progress []
  (let [state @(subscribe [:app.boot/state])]
    [:div.boot-progress {:data-testid "boot-progress"}
     [:h1 "Booting…"]
     [:p {:data-testid "boot-state"}
      "Phase: "
      [:strong (case state
                 :configuring  "Loading configuration"
                 :loading-deps "Loading dependencies (routes / flags / user)"
                 :hydrating    "Hydrating application state"
                 (str state))]]
     [:p.boot-hint
      "The boot machine is at "
      [:code (str state)]
      ". The main app view does not mount until the boot reaches "
      [:code ":ready"] "."]]))

(reg-view ^{:doc "Terminal :failed screen — surfaces the recorded
                  error and offers a retry."}
          boot-failed []
  (let [err @(subscribe [:app.boot/error])]
    [:div.boot-failed {:data-testid "boot-failed"}
     [:h1 "Boot failed"]
     [:p {:data-testid "boot-error"}
      (if-let [msg (or (:message err)
                       (some-> (:kind err) name)
                       (some-> err pr-str))]
        (str "Reason: " msg)
        "An unexpected error occurred during application boot.")]
     [:button {:data-testid "boot-retry"
               :on-click    #(dispatch [:app/boot [:boot/restart]])}
      "Retry boot"]]))

(reg-view ^{:doc "Post-ready screen — the main app. By the time this
                  renders, the four boot-loaded slices are populated
                  in app-db and stable."}
          main-app []
  (let [config @(subscribe [:app/config])
        flags  @(subscribe [:app/flags])
        user   @(subscribe [:app/user])
        routes @(subscribe [:app/routes])]
    [:div.main-app {:data-testid "main-app"}
     [:h1 {:data-testid "main-title"}
      (or (:title config) "Boot example")]
     [:p (str "Welcome, " (:username user) "!")]
     [:section
      [:h2 "Loaded configuration"]
      [:dl
       [:dt "Environment"]  [:dd {:data-testid "config-env"} (str (:env config))]
       [:dt "API base"]     [:dd (str (:api-base config))]
       [:dt "Build"]        [:dd (str (:build config))]]]
     [:section
      [:h2 "Feature flags"]
      [:ul {:data-testid "flags-list"}
       [:li "dark-mode? "       (str (:dark-mode? flags))]
       [:li "beta-channel? "    (str (:beta-channel? flags))]
       [:li "onboarding-skip? " (str (:onboarding-skip? flags))]]]
     [:section
      [:h2 "Routes"]
      [:ul {:data-testid "routes-list"}
       (for [{:keys [id path]} routes]
         ^{:key id} [:li (str id " → " path)])]]]))

(reg-view ^{:doc "Root view — switches on the boot machine's state.
                  This is the only place in the application that
                  decides whether to mount the main app vs the
                  boot-progress screen."}
          root-view []
  (let [state @(subscribe [:app.boot/state])]
    (cond
      (= state :ready)  [main-app]
      (= state :failed) [boot-failed]
      :else             [boot-progress])))
