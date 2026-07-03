(ns boot.views
  "Views for the boot example (docs/core/glossary.md#view).

   The whole view tree pivots on one question — has the boot finished? —
   and `root-view` is where that fork lives:

   1. **Not yet** — while the boot machine is in any non-terminal state,
      we render `[boot-progress]`, a screen that just names the current
      phase. The main app stays unmounted, which spares it from reading
      half-empty slices and guarding against `nil` at every turn.

   2. **Ready** — once the boot reaches `:ready`, we swap in `[main-app]`.
      It reads `:config`, `:flags`, `:user`, and `:routes` straight from
      app-db with no defensiveness, because by now they're all there and
      not going anywhere.

   3. **Failed** — `[boot-failed]` shows the error and a retry button.
      Retry dispatches `[:app/boot [:boot/restart]]` to run the boot
      again from `:configuring`. (A real event, note — not the
      `:rf.machine/start` creation marker, which goes inert the moment
      the machine exists.)

   Forking here, in one place, is the whole trick. It keeps every scrap
   of loading logic inside the boot machine. The alternative — mount the
   main app and let each sub-view paint its own loading skeleton — would
   smear that logic across the entire tree. Instead the views just ask
   `:app.boot/ready?` and get on with it.

   The views are deliberately bare. The point is the boot shape, not a
   pretty UI."
  (:require [re-frame.core :as rf]
            [boot.boot]))

(rf/reg-view ^{:doc "The waiting screen — what you see while the boot
                  machine is still working through its phases."}
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

(rf/reg-view ^{:doc "The :failed screen — shows what went wrong and offers
                  a retry that re-runs the boot from the top."}
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

(rf/reg-view ^{:doc "The main app — the screen the boot was clearing the
                  way for. By the time it renders, all four loaded slices
                  are sitting in app-db, populated and stable."}
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

(rf/reg-view ^{:doc "The fork in the road. Reads the boot machine's state
                  and picks one of three screens — and it's the only
                  place in the app that makes that call."}
          root-view []
  (let [state @(subscribe [:app.boot/state])]
    (cond
      (= state :ready)  [main-app]
      (= state :failed) [boot-failed]
      :else             [boot-progress])))
