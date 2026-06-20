(ns routes-epochs.core
  "ROUTES-EPOCHS testbed — the ROUTING sibling of the `standard_epochs`
  deck, on the shared queued-step RUNNER (`runner.core`). A deliberately
  simple Xray driving surface aimed squarely at the **Routing panel**
  (`rf-xray-routing`).

  ## Shape — the shared queued-step RUNNER

  ONE purple Step button (`routes-epochs-step`) walks the routing ladder
  top to bottom while the operator watches how Xray's Routing panel renders
  each rung; EVERY step row's index is ALSO a RANDOM-ACCESS RUN-THIS-STEP
  button (`routes-epochs-step-<n>-run`, n = 0-based step index) so any rung
  can be driven directly. The runner (`runner.core`) is the shared harness;
  this deck supplies a `steps` vector (CODE DATA) + the testid prefix
  `routes-epochs`. Each step DISPATCHES one event that

    (a) lands on the run-step epoch as the `:step` app-db delta the panels
        show (the runner sets `:step = n`), and
    (b) exercises exactly ONE additional ROUTING feature via a child dispatch.

  The runner's per-step RUN-THIS-STEP affordance is the RANDOM-ACCESS
  addressing the feature-matrix scenario needs — it drives rungs OUT OF
  ORDER (#3, #1, #4, #5, #7, #10, #11), asserting the Routing panel after
  each, which the runner's sequential cursor alone could not provide.

  Progressive: step 1 is a plain `navigate`; each later step layers one
  more routing concept on top. A SINGLE frame, plainly mounted, with the
  routing artefact booted (`[re-frame.routing]`) and Xray auto-mounting
  inline on the right (`[data-rf-xray-host]` + the xray preload) reading
  that one frame. The Routing panel itself is the check; each step's
  `:watch` note says what to look for.

  ## North star (acceptance)

  Step through the ladder top to bottom → the Xray **Routing panel** is
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
    #10 guarded/enter       — navigate INTO settings + arm the dirty flag.
    #11 guarded/blocked nav — a `:can-leave` guard refuses; outcome
                             `blocked` + `:rf/pending-navigation` is set.

  ## Runner cursor = app-db `:step`

  The runner cursor lives in app-db's `:step` slot, written by
  `runner.core`'s run-step event. `:step` churning every step IS the
  per-step App-db / Epoch delta the panels show. The deck reads as an
  ordinary re-frame2 app: app-db + events + subs, NO Reagent atom for step
  state.

  ## Test surface, not tutorial

  Per `feedback_testbeds_are_test_surfaces`: no deliberate bugs as
  anti-patterns, no teaching layers. The guard / not-found / redirect
  steps exercise the REAL routing surface — each is a feature being
  driven, not a buggy demo. `:watch` notes are guidance, not lessons.

  ## Test-free + self-contained

  Per rf2-8cevm this testbed carries no spec.cjs; regression coverage
  lives in the substrate contract tests + the Xray feature-matrix gate
  (`tools/xray/testbeds/feature_matrix/scenarios.cjs` — the
  `routes-epochs routing ladder` scenario, which drives the ladder via the
  runner's per-step RUN-THIS-STEP buttons). The routes / events / subs /
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
            ;; Shared testbed-config helper: derives the open-in-editor
            ;; project-root from the build env.
            [re-frame.testbed.config :as testbed-config]
            ;; The shared step-driver runner. This deck supplies a `steps`
            ;; vector + registers `:routes-epochs/run-step` via
            ;; `runner/reg-runner!`; the runner drives the ONE-button series
            ;; + the per-step RUN buttons off app-db `:step`.
            [runner.core :as runner])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; The inspected app frame — only the app frame is Xray-relevant.
;; ============================================================================

(def host-frame :rf/default)

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
  {:doc  "Landing page — the ladder's first navigation target."} "/")

(rf/reg-route :routes-epochs/articles
  {:doc  "Articles list — the nested-route PARENT (step #5's child sits
          under it, so the ROUTE TABLE draws the indented tree)."} "/articles")

(rf/reg-route :routes-epochs/article
  {:doc    "Article detail — carries a `:id` path param (step #3) and is
            NESTED under `:routes-epochs/articles` (step #5) so the
            ROUTE TABLE indents it and the current-row highlight walks the
            parent chain."
   :parent :routes-epochs/articles
   :params [:map [:id :string]]} "/articles/:id")

(rf/reg-route :routes-epochs/search
  {:doc   "Search — declares a `:query` schema so the query keys round-trip
           into the route slice (step #4)."
   :query [:map
           [:q {:optional true} :string]
           [:sort {:optional true} :string]]} "/search")

(rf/reg-route :routes-epochs/old-home
  {:doc      "A retired URL that REDIRECTS to `:routes-epochs/home` via its
              `:on-match` (step #6) — navigating here lands the slice on
              home, one cascade later."
   :on-match [[:routes-epochs/redirect-to-home]]} "/old-home")

(rf/reg-route :routes-epochs/profile
  {:doc      "A profile route whose `:on-match` LOADER writes app-db from
              the route params (step #8): transition runs :loading → :idle
              while the loader fills `:profile`."
   :params   [:map [:user :string]]
   :on-match [[:routes-epochs/load-profile]]} "/profile/:user")

(rf/reg-route :routes-epochs/settings
  {:doc       "Settings — GUARDED by a `:can-leave` gate (step #11). The
               gate refuses to leave while `:settings-dirty?` is set, so a
               navigation AWAY is blocked and `:rf/pending-navigation` fills."
   :can-leave :routes-epochs/can-leave-settings?} "/settings")

;; The runtime emits :rf.route/not-found for unmatched URLs (step #7).
(rf/reg-route :rf.route/not-found
  {:doc  "Fallback page for unmatched URLs — CURRENT ROUTE reads
          `:rf.route/not-found`; NAVIGATION THIS EPOCH's outcome chip
          reads `not-found`."} "/_404")

;; ============================================================================
;; APP-DB SEED
;; ============================================================================
;;
;; `:step` is the runner cursor — the index of the last-run step, written
;; by `runner.core`'s run-step event; its churn every step IS the per-step
;; App-db / Epoch delta. `:articles` feeds the params / nested-route
;; targets. `:profile` is the slot the route-driven loader (step #8) fills
;; from the route params. `:settings-dirty?` arms the `:can-leave` guard
;; (step #11).

(def initial-db
  {:step            nil
   :articles        [{:id "intro" :title "Intro to re-frame2 routing"}
                     {:id "nested" :title "Nested routes & the route tree"}]
   :profile         nil
   :settings-dirty? false})

(rf/reg-event :routes-epochs/reset
  {:doc "Re-seed app-db AND navigate home. Start clean."}
  (fn handler-reset [_ _ev]
    {:db initial-db
     :fx [[:dispatch [:rf.route/navigate :routes-epochs/home]]]}))

;; ============================================================================
;; A reusable navigate driver
;; ============================================================================
;;
;; The routing events themselves are FRAMEWORK events (`:rf.route/navigate`
;; etc.), so each rung is a thin deck-owned event-fx that dispatches the
;; routing event/fx. The per-step App-db / Epoch delta is the runner's
;; `:step` write on the parent run-step epoch; the Routing panel lights up
;; from the dispatched routing event.

;; A reusable navigate event-fx: every navigation rung routes through here.
(rf/reg-event :routes-epochs/go
  {:doc "Dispatch `:rf.route/navigate` with the supplied target / params /
         opts. The shared driver behind every navigation rung."}
  (fn handler-go [{:keys [db]} [_ target params opts]]
    {:db db
     :fx [[:dispatch [:rf.route/navigate target params (or opts {})]]]}))

;; ============================================================================
;; REDIRECT (#6) + ROUTE-DRIVEN APP-DB LOADER (#8)
;; ============================================================================

(rf/reg-event :routes-epochs/redirect-to-home
  {:doc "Step #6's redirect — fired as `:routes-epochs/old-home`'s
         `:on-match`. Re-navigates to home so the slice lands on a
         DIFFERENT route one cascade later."}
  (fn handler-redirect [{:keys [db]} _ev]
    {:db db
     :fx [[:dispatch [:rf.route/navigate :routes-epochs/home]]]}))

(rf/reg-event :routes-epochs/load-profile
  {:doc "Step #8's route-driven loader — fired as
         `:routes-epochs/profile`'s `:on-match`. Reads the route params
         from the route slice and writes `:profile` into app-db (the
         transition runs :loading → :idle around this loader).

         EP-0001 (rf2-vzld77 / rf2-tj6w9l): the route slice is durable
         routing RUNTIME-DB state at `[:rf.runtime/routing :current]`, no
         longer in app-db `:rf/runtime`. An event handler reads it off the
         `:rf.db/runtime` coeffect every event handler receives — the
         canonical `:on-match` loader idiom (mirrors the realworld
         example's `:profile/load`)."}
  (fn handler-load-profile [{:keys [db] rt :rf.db/runtime} _ev]
    (let [user (get-in rt [:rf.runtime/routing :current :params :user])]
      {:db (assoc db :profile {:user user :loaded-at-step (:step db)})})))

;; ============================================================================
;; GUARDED NAV (#10/#11) — a :can-leave gate
;; ============================================================================
;;
;; `:routes-epochs/settings` declares `:can-leave :routes-epochs/can-leave-
;; settings?`. The guard returns `false` (block) while `:settings-dirty?`
;; is set — so a navigation AWAY from settings is refused, the runtime
;; writes `:rf/pending-navigation`, and the Routing panel's NAVIGATION
;; THIS EPOCH outcome reads `blocked`. The guard contract accepts only
;; literal `true` / `false`, hence `(boolean ...)`.

(rf/reg-sub :routes-epochs/can-leave-settings?
  (fn [db _] (boolean (not (:settings-dirty? db)))))

(rf/reg-event :routes-epochs/enter-dirty-settings
  {:doc "Step #10 — navigate INTO settings AND arm the dirty flag, so
         the `:can-leave` guard will block the next attempt to leave."}
  (fn handler-enter-dirty [{:keys [db]} _ev]
    {:db (assoc db :settings-dirty? true)
     :fx [[:dispatch [:rf.route/navigate :routes-epochs/settings]]]}))

(rf/reg-event :routes-epochs/try-leave-settings
  {:doc "Step #11 — attempt to navigate home FROM dirty settings. The
         `:can-leave` guard refuses: the slice stays on settings, the
         outcome reads `blocked`, and `:rf/pending-navigation` fills."}
  (fn handler-try-leave [{:keys [db]} _ev]
    {:db db
     :fx [[:dispatch [:rf.route/navigate :routes-epochs/home]]]}))

;; ============================================================================
;; BACK / FORWARD HISTORY (#9)
;; ============================================================================
;;
;; A plain history step: dispatch `:rf.route/handle-url-change` for the
;; previous URL (what a popstate listener does on the browser Back
;; button), so NAVIGATION THIS EPOCH's FROM ──► TO reverses relative to
;; the forward navigation that preceded it.

(rf/reg-event :routes-epochs/back
  {:doc "Step #9 — emulate the browser Back button by handling a
         url-change back to the articles list (a popstate-style
         transition). NAVIGATION THIS EPOCH's FROM ──► TO reverses."}
  (fn handler-back [{:keys [db]} _ev]
    {:db db
     :fx [[:dispatch [:rf.route/handle-url-change "/articles"]]]}))

;; ============================================================================
;; SUBSCRIPTIONS — route reads (framework subs) + deck reads
;; ============================================================================
;;
;; The framework ships `:rf.route/id`, `:rf.route/params`, `:rf.route/query`,
;; `:rf.route/transition`, `:rf.route/chain` and `:rf/pending-navigation`.
;; The deck only needs one app-db read for its own status strip.

(rf/reg-sub :routes-epochs/profile  (fn [db _] (:profile db)))

;; ============================================================================
;; THE STEP VECTOR — code data (the single source of truth)
;; ============================================================================
;;
;; Each step: {:event [...] :watch "<what to look for>" :label "<short row
;; label>"}. The runner renders :watch per STEP; pressing Step (or a per-row
;; RUN button) dispatches `[:routes-epochs/run-step n]`, which sets app-db
;; `:step = n` (the per-step delta the panels show) and dispatches the
;; step's `:event` into the host-frame. Navigation cascades through
;; `:routes-epochs/go` (→ :rf.route/navigate); the redirect step (#6) fires a
;; SECOND navigation cascade via its route's `:on-match`. Manual stepping
;; needs no pacing — each cascade fires + renders while the operator watches.
;;
;; The 0-based step index maps to the 1-based rung vocabulary the
;; feature-matrix scenario uses: step index 0 = rung #1, step index N-1 =
;; rung #N. The scenario drives the runner's per-step RUN button at index
;; (rung - 1).

(def steps
  [;; -- Navigation basics — CURRENT ROUTE + NAVIGATION THIS EPOCH --
   {:label "navigate / push"
    :event [:routes-epochs/go :routes-epochs/home]
    :watch "CURRENT ROUTE → :home · NAVIGATION ──► home · outcome transitioned. The ROUTE TABLE paints the :to overlay on the home row."}
   {:label "replace (no history push)"
    :event [:routes-epochs/go :routes-epochs/home nil {:replace? true}]
    :watch "Same slice write via :replace? true (:rf.nav/replace-url, no history push) — the slice lands on :home without a back-stack entry."}

   ;; -- Params & query — the slice carries params / query --
   {:label "route params (/articles/:id)"
    :event [:routes-epochs/go :routes-epochs/article {:id "intro"}]
    :watch "CURRENT ROUTE → :article · params {:id \"intro\"} · NAVIGATION ──► :article · transitioned."}
   {:label "query params (?q&sort)"
    :event [:routes-epochs/go :routes-epochs/search nil {:query {:q "routing" :sort "asc"}}]
    :watch "CURRENT ROUTE → :search · query {:q \"routing\" :sort \"asc\"} (the :rf.route/query slice)."}

   ;; -- ROUTE TABLE — nested tree, redirect, fallback --
   {:label "nested route (under :articles)"
    :event [:routes-epochs/go :routes-epochs/article {:id "nested"}]
    :watch "ROUTE TABLE indents :article under :articles (deeper padding-left) · the destination :to overlay paints on the :article row this navigation epoch."}
   {:label "redirect (:on-match re-navigates)"
    :event [:routes-epochs/go :routes-epochs/old-home]
    :watch "Navigate :old-home → its :on-match re-navigates, landing the slice on :home one cascade later. Epoch: the two-navigation cascade."}
   {:label "not-found / fallback"
    :event [:routes-epochs/go {:url "/no-such-page"}]
    :watch "Unmatched URL → CURRENT ROUTE :rf.route/not-found · NAVIGATION ──► the registered :rf.route/not-found fallback."}

   ;; -- Loaders & transitions — :on-match drives app-db --
   {:label "route-driven app-db (:on-match loader)"
    :event [:routes-epochs/go :routes-epochs/profile {:user "ada"}]
    :watch "Navigate :profile → :on-match writes :profile from params · App-db gains :profile {:user \"ada\" ..} · transition :loading→:idle."}
   {:label "back / forward (popstate)"
    :event [:routes-epochs/back]
    :watch "Emulate Back → :articles · NAVIGATION THIS EPOCH's FROM ──► TO reverses relative to the forward navigation."}

   ;; -- Guarded navigation — :can-leave blocks --
   {:label "enter dirty settings (arm the guard)"
    :event [:routes-epochs/enter-dirty-settings]
    :watch "Navigate INTO :settings + arm :settings-dirty? so the :can-leave guard will block the next attempt to leave."}
   {:label "try to leave (blocked by :can-leave)"
    :event [:routes-epochs/try-leave-settings]
    :watch "Attempt → home FROM dirty settings · the guard refuses · CURRENT ROUTE STAYS :settings · outcome blocked · :rf/pending-navigation fills."}])

;; ============================================================================
;; RUNNER WIRING — register the deck's run-step event
;; ============================================================================

(runner/reg-runner! {:id         :routes-epochs/run-step
                     :steps      steps
                     :host-frame host-frame})

;; ============================================================================
;; VIEWS
;; ============================================================================

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
                 :max-width "820px"}}
   [:header {:style {:margin-bottom "0.5em"}}
    [:h2 {:data-testid "routes-epochs-title" :style {:margin 0}} "Routes-epochs"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "One frame, one routing ladder. One button (" [:strong "⏭ Step"] ") "
     "walks the rungs top to bottom (each row's number is also a "
     [:strong "RUN-THIS-STEP"] " button for random access). Each step sets "
     "the " [:strong ":step"] " cursor in app-db (so App-db / Epoch always "
     "show a delta) and exercises exactly one more routing feature. Read "
     "each step's " [:strong "Watch"] " note, then watch Xray's "
     [:strong "Routing"] " panel on the right — its three sections (Current "
     "route · Navigation this epoch · Route table) light up across the "
     "ladder. The runner cursor is " [:strong ":step"] " in app-db — driven "
     "by events + subs, no harness atom."]]
   [current-route-strip]
   [runner/runner {:run-step-event :routes-epochs/run-step
                   :steps          steps
                   :prefix         "routes-epochs"
                   :host-frame     host-frame}]])

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

;; The open-in-editor project-root is derived from the build environment,
;; not a hardcoded personal path. `re-frame.testbed.config` joins the
;; build-time repo-root goog-define with this testbed's tool-relative
;; subdir; `?project-root=<path>` still overrides per session. See that ns
;; for the cross-platform mechanism.
(defn- resolve-project-root []
  (testbed-config/resolve-project-root "tools/xray/testbeds"))

(defn ^:export run []
  ;; Configure Xray BEFORE `rf/init!` so the preload's auto-open reads the
  ;; right project-root on its first paint of any chip.
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; EP-0002 (rf2-9o48ih): the runtime never synthesises a frame from
  ;; absence — establish the host frame explicitly. URL ownership is now an
  ;; explicit declaration, so opt the host frame in via `{:url-bound? true}`
  ;; (otherwise `:rf.nav/push-url` no-ops and history-driven steps stall).
  (rf/reg-frame host-frame {:url-bound? true})
  ;; Seed app-db and pull the current URL into the route slice (what a
  ;; popstate / initial-load handler does). The host frame is the one
  ;; Xray reads. The browser History listener is installed so step #9's
  ;; back/forward emulation has somewhere to land. Boot dispatches run
  ;; under the host frame scope (the carried invariant).
  (rf/install-history-listener!)
  (rf/with-frame host-frame
    (rf/dispatch-sync [:routes-epochs/reset])
    (rf/dispatch-sync [:rf.route/handle-url-change (current-url)]))
  (rdc/render react-root [rf/frame-provider-existing {:frame host-frame} [root]]))
