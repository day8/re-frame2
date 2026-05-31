(ns routes-epochs.core
  "ROUTES-EPOCHS testbed (rf2-5crg4) — the ROUTING sibling of the
  `standard_epochs` deck. A deliberately simple Xray driving surface
  aimed squarely at the **Routing panel** (`rf-xray-routing`).

  ## Shape

  ONE tall column of NUMBERED buttons, top to bottom — the same shape as
  `standard_epochs`. Beside each button a one-line caption says WHAT TO
  WATCH in Xray's Routing tab when you press it. Each button is ONE test:
  it fires one event that

    (a) bumps a shared baseline counter (so App-db / Epoch always show a
        delta on every press), and
    (b) exercises exactly ONE additional ROUTING feature.

  Progressive: button 1 is a plain `navigate`; each later button layers
  one more routing concept on top. A SINGLE frame, plainly mounted, with
  the routing artefact booted (`[re-frame.routing]`) and Xray
  auto-mounting inline on the right (`[data-rf-xray-host]` + the xray
  preload) reading that one frame. No tabs, no live ACTION→CHECK strip:
  the static caption is the 'what to look for', and the Routing panel
  itself is the check.

  ## North star (acceptance)

  Click the buttons top to bottom → the Xray **Routing panel** is
  COMPLETELY exercised: its three sections —

    - CURRENT ROUTE         (active id · params · matched path)
    - NAVIGATION THIS EPOCH (FROM ──► TO · params · outcome chip:
                             transitioned / blocked / fragment-changed /
                             not-found)
    - ROUTE TABLE           (registered graph as a tree, nested rows
                             indented, current row highlighted, FROM/TO
                             overlay glyphs)

  — each light up under one or more rungs of the ladder.

  ## The routing ladder (per-rung Routing-panel coverage)

    #1  navigate/push      — CURRENT ROUTE lands on `:routes-epochs/home`;
                             NAVIGATION THIS EPOCH shows ──► home · transitioned.
    #2  replace            — same slice write via `{:replace? true}`
                             (`:rf.nav/replace-url`, no history push).
    #3  route params       — `/articles/:id`; CURRENT ROUTE params `{:id ..}`.
    #4  query params       — `?q=routing&sort=asc`; the `:query` slice +
                             the `:rf.route/query` sub.
    #5  nested routes      — a `:parent`-linked child; ROUTE TABLE draws
                             the indented tree + the current-row highlight
                             walks the parent chain (`:rf.route/chain`).
    #6  redirect           — a route whose `:on-match` re-navigates; the
                             cascade lands the slice on a DIFFERENT route.
    #7  not-found/fallback — an unmatched URL; CURRENT ROUTE =
                             `:rf.route/not-found`, outcome `not-found`.
    #8  route-driven app-db— an `:on-match` loader writes app-db from the
                             route params (transition :loading → :idle).
    #9  back/forward        — push, then a popstate-style back; NAVIGATION
                             THIS EPOCH's FROM ──► TO reverses.
    #10 guarded/blocked nav— a `:can-leave` guard refuses; outcome
                             `blocked` + `:rf/pending-navigation` is set.

  ## Test surface, not tutorial

  Per `feedback_testbeds_are_test_surfaces`: no deliberate bugs as
  anti-patterns, no teaching layers. The guard / not-found / redirect
  buttons exercise the REAL routing surface — each is a feature being
  driven, not a buggy demo. Captions are guidance, not lessons.

  ## Test-free + self-contained

  Per rf2-8cevm this testbed carries no spec.cjs; regression coverage
  lives in the substrate contract tests + the Xray feature-matrix gate
  (`tools/xray/testbeds/feature_matrix/scenarios.cjs` — the
  `routes-epochs routing ladder` scenario). The routes / events / subs /
  views below are OWNED here — this deck does NOT reuse the shared
  `testdeck.*` modules."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Routing artefact — load-time hook so `reg-route`,
            ;; `:rf.route/navigate`, the `:rf.route/*` subs and
            ;; `route-link` resolve. Without this require `reg-route`
            ;; below throws `:rf.error/routing-artefact-missing`.
            [re-frame.routing]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Xray's `configure!` to seed `:project-root` so the Event
            ;; lens 'open' chip resolves a classpath-relative `:file` to
            ;; an absolute on-disk URI.
            [day8.re-frame2-xray.config :as xray-config]
            ;; Shared testbed-config helper (rf2-5dphw): derives the
            ;; open-in-editor project-root from the build env.
            [re-frame.testbed.config :as testbed-config])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; ROUTES — the deck OWNS its own route table
;; ============================================================================
;;
;; A small, flat-plus-one-nested graph chosen for per-section Routing-panel
;; coverage:
;;
;;   :routes-epochs/home              /                       (landing)
;;   :routes-epochs/articles          /articles               (list — also
;;                                                              the nested
;;                                                              parent)
;;   :routes-epochs/article           /articles/:id           (params; nested
;;                                                              under articles)
;;   :routes-epochs/search            /search?q&sort          (query params)
;;   :routes-epochs/old-home          /old-home               (redirects → home)
;;   :routes-epochs/settings          /settings               (guarded by a
;;                                                              :can-leave gate)
;;   :rf.route/not-found              /_404                   (fallback)

(rf/reg-route :routes-epochs/home
  {:doc  "Landing page — the ladder's first navigation target."
   :path "/"})

(rf/reg-route :routes-epochs/articles
  {:doc  "Articles list — the nested-route PARENT (button #5's child sits
          under it, so the ROUTE TABLE draws the indented tree)."
   :path "/articles"})

(rf/reg-route :routes-epochs/article
  {:doc    "Article detail — carries a `:id` path param (button #3) and is
            NESTED under `:routes-epochs/articles` (button #5) so the
            ROUTE TABLE indents it and the current-row highlight walks the
            parent chain."
   :path   "/articles/:id"
   :parent :routes-epochs/articles
   :params [:map [:id :string]]})

(rf/reg-route :routes-epochs/search
  {:doc   "Search — declares a `:query` schema so the query keys round-trip
           into the route slice (button #4)."
   :path  "/search"
   :query [:map
           [:q {:optional true} :string]
           [:sort {:optional true} :string]]})

(rf/reg-route :routes-epochs/old-home
  {:doc      "A retired URL that REDIRECTS to `:routes-epochs/home` via its
              `:on-match` (button #6) — navigating here lands the slice on
              home, one cascade later."
   :path     "/old-home"
   :on-match [[:routes-epochs/redirect-to-home]]})

(rf/reg-route :routes-epochs/profile
  {:doc      "A profile route whose `:on-match` LOADER writes app-db from
              the route params (button #8): transition runs :loading → :idle
              while the loader fills `:profile`."
   :path     "/profile/:user"
   :params   [:map [:user :string]]
   :on-match [[:routes-epochs/load-profile]]})

(rf/reg-route :routes-epochs/settings
  {:doc       "Settings — GUARDED by a `:can-leave` gate (button #10). The
               gate refuses to leave while `:settings-dirty?` is set, so a
               navigation AWAY is blocked and `:rf/pending-navigation` fills."
   :path      "/settings"
   :can-leave :routes-epochs/can-leave-settings?})

;; The runtime emits :rf.route/not-found for unmatched URLs (button #7).
(rf/reg-route :rf.route/not-found
  {:doc  "Fallback page for unmatched URLs — CURRENT ROUTE reads
          `:rf.route/not-found`; NAVIGATION THIS EPOCH's outcome chip
          reads `not-found`."
   :path "/_404"})

;; ============================================================================
;; APP-DB SEED
;; ============================================================================
;;
;; `:baseline` is the shared counter every button bumps (so App-db / Epoch
;; always show a delta). `:articles` feeds the params / nested-route
;; targets. `:profile` is the slot the route-driven loader (button #8)
;; fills from the route params. `:settings-dirty?` arms the `:can-leave`
;; guard (button #10).

(def initial-db
  {:baseline        0
   :articles        [{:id "intro" :title "Intro to re-frame2 routing"}
                     {:id "nested" :title "Nested routes & the route tree"}]
   :profile         nil
   :settings-dirty? false})

(rf/reg-event-fx :routes-epochs/reset
  {:doc "Button 0 — re-seed app-db AND navigate home. Start clean."}
  (fn handler-reset [_ _ev]
    {:db initial-db
     :fx [[:dispatch [:rf.route/navigate :routes-epochs/home]]]}))

;; ============================================================================
;; A small shared helper: every ladder event bumps the baseline counter.
;; ============================================================================
;;
;; The routing events themselves are FRAMEWORK events (`:rf.route/navigate`
;; etc.) we cannot edit, so each rung is a thin deck-owned event-fx that
;; (a) bumps `:baseline` via `:db` and (b) dispatches the routing
;; event/fx. The Epoch / App-db delta on every press comes from the bump;
;; the Routing panel lights up from the dispatched routing event.

(defn- bump [db] (update db :baseline inc))

;; A reusable "bump + navigate" event-fx: every navigation rung routes
;; through here so the baseline delta and the navigation are one cascade.
(rf/reg-event-fx :routes-epochs/go
  {:doc "Bump the baseline and dispatch `:rf.route/navigate` with the
         supplied target / params / opts. The shared driver behind every
         navigation rung."}
  (fn handler-go [{:keys [db]} [_ target params opts]]
    {:db (bump db)
     :fx [[:dispatch [:rf.route/navigate target params (or opts {})]]]}))

;; ============================================================================
;; REDIRECT (#6) + ROUTE-DRIVEN APP-DB LOADER (#8)
;; ============================================================================

(rf/reg-event-fx :routes-epochs/redirect-to-home
  {:doc "Button #6's redirect — fired as `:routes-epochs/old-home`'s
         `:on-match`. Re-navigates to home so the slice lands on a
         DIFFERENT route one cascade later."}
  (fn handler-redirect [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:dispatch [:rf.route/navigate :routes-epochs/home]]]}))

(rf/reg-event-db :routes-epochs/load-profile
  {:doc "Button #8's route-driven loader — fired as
         `:routes-epochs/profile`'s `:on-match`. Reads the route params
         from the slice and writes `:profile` into app-db (the
         transition runs :loading → :idle around this loader)."}
  (fn handler-load-profile [db _ev]
    (let [user (get-in db [:rf/runtime :routing :current :params :user])]
      (-> db bump (assoc :profile {:user user :loaded-at-baseline (:baseline db)})))))

;; ============================================================================
;; GUARDED NAV (#10) — a :can-leave gate
;; ============================================================================
;;
;; `:routes-epochs/settings` declares `:can-leave :routes-epochs/can-leave-
;; settings?`. The guard returns `false` (block) while `:settings-dirty?`
;; is set — so a navigation AWAY from settings is refused, the runtime
;; writes `:rf/pending-navigation`, and the Routing panel's NAVIGATION
;; THIS EPOCH outcome reads `blocked`. The contract is closed (rf2-5pyyl):
;; only literal `true` / `false` are accepted, hence `(boolean ...)`.

(rf/reg-sub :routes-epochs/can-leave-settings?
  (fn [db _] (boolean (not (:settings-dirty? db)))))

(rf/reg-event-fx :routes-epochs/enter-dirty-settings
  {:doc "Button #10a — navigate INTO settings AND arm the dirty flag, so
         the `:can-leave` guard will block the next attempt to leave."}
  (fn handler-enter-dirty [{:keys [db]} _ev]
    {:db (-> db bump (assoc :settings-dirty? true))
     :fx [[:dispatch [:rf.route/navigate :routes-epochs/settings]]]}))

(rf/reg-event-fx :routes-epochs/try-leave-settings
  {:doc "Button #10b — attempt to navigate home FROM dirty settings. The
         `:can-leave` guard refuses: the slice stays on settings, the
         outcome reads `blocked`, and `:rf/pending-navigation` fills."}
  (fn handler-try-leave [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:dispatch [:rf.route/navigate :routes-epochs/home]]]}))

;; ============================================================================
;; BACK / FORWARD HISTORY (#9)
;; ============================================================================
;;
;; A plain history step: dispatch `:rf.route/handle-url-change` for the
;; previous URL (what a popstate listener does on the browser Back
;; button), so NAVIGATION THIS EPOCH's FROM ──► TO reverses relative to
;; the forward navigation that preceded it.

(rf/reg-event-fx :routes-epochs/back
  {:doc "Button #9 — emulate the browser Back button by handling a
         url-change back to the articles list (a popstate-style
         transition). NAVIGATION THIS EPOCH's FROM ──► TO reverses."}
  (fn handler-back [{:keys [db]} _ev]
    {:db (bump db)
     :fx [[:dispatch [:rf.route/handle-url-change "/articles"]]]}))

;; ============================================================================
;; SUBSCRIPTIONS — route reads (framework subs) + deck reads
;; ============================================================================
;;
;; The framework ships `:rf.route/id`, `:rf.route/params`, `:rf.route/query`,
;; `:rf.route/transition`, `:rf.route/chain` and `:rf/pending-navigation`.
;; The deck only needs a couple of app-db reads for its own status strip.

(rf/reg-sub :routes-epochs/baseline (fn [db _] (:baseline db)))
(rf/reg-sub :routes-epochs/profile  (fn [db _] (:profile db)))

;; ============================================================================
;; THE BUTTON LADDER
;; ============================================================================

(def ^:private ladder
  "The ordered routing ladder. Each row: [n label caption event]. `event`
  is the dispatch vector; `:section` rows are separators (label only)."
  [[:section "Navigation basics — CURRENT ROUTE + NAVIGATION THIS EPOCH"]
   [1  "navigate / push"
    "CURRENT ROUTE → :home · NAVIGATION ──► home · outcome transitioned"
    [:routes-epochs/go :routes-epochs/home]]
   [2  "replace (no history push)"
    "Same slice write via :replace? true (:rf.nav/replace-url, no push)"
    [:routes-epochs/go :routes-epochs/home nil {:replace? true}]]
   [:section "Params & query — the slice carries params / query"]
   [3  "route params (/articles/:id)"
    "CURRENT ROUTE → :article · params {:id \"intro\"}"
    [:routes-epochs/go :routes-epochs/article {:id "intro"}]]
   [4  "query params (?q&sort)"
    "CURRENT ROUTE → :search · query {:q \"routing\" :sort \"asc\"} (:rf.route/query)"
    [:routes-epochs/go :routes-epochs/search nil {:query {:q "routing" :sort "asc"}}]]
   [:section "ROUTE TABLE — nested tree, redirect, fallback"]
   [5  "nested route (under :articles)"
    "ROUTE TABLE indents :article under :articles · highlight walks the parent chain"
    [:routes-epochs/go :routes-epochs/article {:id "nested"}]]
   [6  "redirect (:on-match re-navigates)"
    "Navigate :old-home → its :on-match lands the slice on :home one cascade later"
    [:routes-epochs/go :routes-epochs/old-home]]
   [7  "not-found / fallback"
    "Unmatched URL → CURRENT ROUTE :rf.route/not-found · outcome not-found"
    [:routes-epochs/go {:url "/no-such-page"}]]
   [:section "Loaders & transitions — :on-match drives app-db"]
   [8  "route-driven app-db (:on-match loader)"
    "Navigate :profile → :on-match writes :profile from params · transition :loading→:idle"
    [:routes-epochs/go :routes-epochs/profile {:user "ada"}]]
   [9  "back / forward (popstate)"
    "Emulate Back → :articles · NAVIGATION THIS EPOCH's FROM ──► TO reverses"
    [:routes-epochs/back]]
   [:section "Guarded navigation — :can-leave blocks"]
   [10 "enter dirty settings (arm the guard)"
    "Navigate INTO :settings + arm :settings-dirty? so the guard will block leaving"
    [:routes-epochs/enter-dirty-settings]]
   [11 "try to leave (blocked by :can-leave)"
    "Attempt → home FROM dirty settings · outcome blocked · :rf/pending-navigation fills"
    [:routes-epochs/try-leave-settings]]])

(reg-view ladder-button
  "One numbered ladder row: a numbered button on the left, its caption on
  the right. Pressing it dispatches the row's event. The button carries a
  per-rung `data-testid` (`routes-epochs-rung-<n>`) so each rung is
  uniquely addressable even though several share the `:routes-epochs/go`
  event-id (the feature-matrix scenario drives them by rung)."
  [n label caption event]
  [:div {:style {:display "grid" :grid-template-columns "auto 1fr"
                 :gap "0.75em" :align-items "center" :margin "0.35em 0"}}
   [:button {:data-testid (str "routes-epochs-rung-" n)
             :on-click    #(dispatch event)
             :style {:min-width "20em" :text-align "left"
                     :padding "0.4em 0.6em" :cursor "pointer"
                     :border "1px solid #cfc8ff" :border-radius "6px"
                     :background "#fff"}}
    [:span {:style {:font-weight "bold" :color "#7C5CFF" :margin-right "0.5em"}}
     (str n ".")]
    label]
   [:span {:style {:color "#666" :font-size "12px"}} caption]])

(reg-view section-heading
  "A section separator inside the ladder."
  [label]
  [:div {:style {:margin "1em 0 0.25em 0" :font-size "11px" :font-weight "bold"
                 :color "#7C5CFF" :text-transform "uppercase"
                 :letter-spacing "0.04em" :border-top "1px dashed #ddd"
                 :padding-top "0.5em"}}
   label])

(reg-view current-route-strip
  "A small live read-out of the active route slice — mirrors what the
  Xray Routing panel projects, so the deck stays legible standalone. Pure
  app-state reads; the Routing panel is the actual check."
  []
  (let [id      @(subscribe [:rf.route/id])
        params  @(subscribe [:rf.route/params])
        query   @(subscribe [:rf.route/query])
        trans   @(subscribe [:rf.route/transition])
        pending @(subscribe [:rf/pending-navigation])]
    [:div {:data-testid "routes-epochs-current-strip"
           :style {:border "1px solid #d8d2ff" :border-radius "6px"
                   :padding "0.5em 0.75em" :margin "0.75em 0"
                   :background "#fcfbff" :font-size "12px"}}
     [:div {:style {:font-size "11px" :color "#7C5CFF" :font-weight "bold"
                    :text-transform "uppercase" :letter-spacing "0.04em"}}
      "Current route slice"]
     [:div "id: " [:strong (str id)]]
     [:div "params: " [:strong (pr-str (or params {}))]]
     [:div "query: " [:strong (pr-str (or query {}))]]
     [:div "transition: " [:strong (str trans)]]
     (when pending
       [:div {:style {:color "#b8860b"}}
        "pending-navigation: " [:strong (pr-str pending)]])]))

(reg-view root []
  [:div {:data-testid "routes-epochs-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "760px"}}
   [:header {:style {:margin-bottom "0.5em"}}
    [:h2 {:style {:margin 0}} "Routes-epochs"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "One frame, one tall column of routing test buttons. Each button bumps a shared "
     [:strong "baseline"] " counter (so App-db / Epoch always show a delta) and "
     "exercises exactly one more routing feature. The caption says what to watch in "
     "Xray's " [:strong "Routing"] " panel on the right — click top to bottom and the "
     "panel's three sections (Current route · Navigation this epoch · Route table) are "
     "fully exercised."]]
   [current-route-strip]
   ;; Button 0 — Reset.
   [:button {:data-testid "reset-button"
             :on-click #(dispatch [:routes-epochs/reset])
             :style {:padding "0.4em 0.8em" :cursor "pointer"
                     :border "1px solid #cfc8ff" :border-radius "6px"
                     :background "#f4f1ff" :margin "0.5em 0"}}
    "0. Reset — re-seed app-db, navigate home"]
   ;; The ladder.
   (for [row ladder]
     (if (= :section (first row))
       ^{:key (second row)} [section-heading (second row)]
       (let [[n label caption event] row]
         ^{:key n} [ladder-button n label caption event])))])

;; ============================================================================
;; ROUTER WIRING
;; ============================================================================

(defn- current-url []
  (str (.. js/window -location -pathname)
       (.. js/window -location -search)
       (.. js/window -location -hash)))

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

;; rf2-5dphw — open-in-editor project-root is derived from the build
;; environment, not a hardcoded personal path. `re-frame.testbed.config`
;; joins the build-time repo-root goog-define with this testbed's
;; tool-relative subdir; `?project-root=<path>` still overrides per
;; session. See that ns for the cross-platform mechanism.
(defn- resolve-project-root []
  (testbed-config/resolve-project-root "tools/xray/testbeds"))

(defn ^:export run []
  ;; Configure Xray BEFORE `rf/init!` so the preload's auto-open reads the
  ;; right project-root on its first paint of any chip.
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; Seed app-db and pull the current URL into the route slice (what a
  ;; popstate / initial-load handler does). The default frame is the one
  ;; Xray reads. The browser History listener is installed so button #9's
  ;; back/forward emulation has somewhere to land.
  (rf/install-history-listener!)
  (rf/dispatch-sync [:routes-epochs/reset])
  (rf/dispatch-sync [:rf.route/handle-url-change (current-url)])
  (rdc/render react-root [root]))
