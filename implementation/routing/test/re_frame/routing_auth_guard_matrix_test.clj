(ns re-frame.routing-auth-guard-matrix-test
  "Executable integration matrix pinning the canonical cross-cutting auth-guard
  INTERCEPTOR recipe — the one for a policy that genuinely is not about routes
  (a maintenance-mode lockout, a feature flag over a whole section), which lives
  in docs/routing/how-to/require-sign-in-on-a-route.md §Appendix and is
  cross-referenced from docs/core/how-to/add-auth.md §Appendix and
  spec/012-Routing.md §Cross-cutting guards.

  It is NOT the route-auth recipe. Route auth is `:can-enter` metadata plus a
  `:rf.route/entry-denied` handler, evaluated in the one planning pipeline every
  door already funnels through — no normaliser, nothing to enumerate, and both
  RealWorld examples spell it that way (rf2-k85nd retired the last interceptor
  spelling from examples/real-apps/realworld_resources/routing.cljs). What this
  suite pins is why: an interceptor must cover every door ITSELF, and the matrix
  below is the enumeration that proves how easily one is missed.

  The matrix runs across ALL FOUR navigation entry doors — route-id
  `:rf.route/navigate`, the raw-URL `{:url ...}` navigate escape hatch, a
  `route-link` click (`:rf.route/url-requested`), and a URL-bar / popstate /
  deep-link (`:rf.route/handle-url-change`) — AND all THREE navigate request
  forms: a route-id destination (`:to`), the `{:url ...}` escape hatch, and an
  in-place request (no `:to` / `:url`, patching the current route's query).

  The guard body below is the SINGLE executable seam the suite drives — it is
  registered as `:app/auth-guard` and the test runs THAT interceptor (its
  `:before`, and end-to-end through the frame), not a divorced boolean copy.
  It is kept byte-faithful to the shipped recipe, so a drift between the doc
  recipe and this pin trips a test.

  rf2-e9k3dr — the recipe's `:rf.route/navigate` branch first normalised a
  `{:url ...}` target as a route id UNCONDITIONALLY; a map target fell through
  as a route id, `handler-meta` on a MAP returned nil, `:requires-auth` was
  missed, and the protected route was entered — a fail-OPEN hole on the raw-URL
  escape hatch.

  rf2-yp3ip / rf2-vwwvp — an IN-PLACE request (no `:to` / `:url`, patching the
  current route's query — a tab switch, `?page=2`) must resolve against the
  CURRENT route slice, not a target the request names. A guard that fails to do
  so stands aside exactly where it is most dangerous: a session that expires
  WHILE the user sits on a `:requires-auth` route can navigate in place (a query
  change, a tab switch) straight past the guard. The fix mirrors the runtime:
  resolve an in-place request from the CURRENT route slice
  (`[:rf.runtime/routing :current]`, carried in the `:rf.db/runtime` coeffect)
  before reading the tags, so the guard sees the protected route and fails
  CLOSED.

  This suite is the failing-before / passing-after guard for BOTH holes:
  reverting the `:rf.route/navigate` branch to trust the request's named target
  flips the raw-URL rows AND the in-place rows from gated to open."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

;; ---------------------------------------------------------------------------
;; The canonical guard — byte-faithful to the shipped recipe. Registered as
;; `:app/auth-guard` below and driven directly (its `:before`) AND end-to-end
;; through the frame. `nav-target` normalises ANY navigation event to
;; {:id <route-id> :params <map>} (or nil); `current` is the current route
;; slice, so an in-place request resolves against it exactly
;; as the runtime does.
;; ---------------------------------------------------------------------------

(defn- nav-target [[ev-id a _b] current]
  (case ev-id
    :rf.route/navigate
    (let [{:keys [to url params]} a]                       ;; a is the flat request map
      (cond
        to  {:id to :params (or params {})}               ;; route-id destination
        url (when-let [{:keys [route-id params]} (rf.routing/match-url url)]  ;; {:url ...} escape hatch
              {:id route-id :params (or params {})})
        :else                                             ;; in-place — stays on the current route
        {:id (:route-id current) :params (or (:params current) {})}))

    :rf.route/url-requested
    (let [{:keys [to params url]} a]
      (cond
        to  {:id to :params (or params {})}
        url (when-let [{:keys [route-id params]} (rf.routing/match-url url)]
              {:id route-id :params (or params {})})))

    :rf.route/handle-url-change
    (when-let [{:keys [route-id params]} (rf.routing/match-url a)]
      {:id route-id :params (or params {})})

    nil))

(defn- auth-guard-before
  "The canonical guard `:before`. Reads the current route slice from the
  `:rf.db/runtime` coeffect so an in-place request resolves to the route the user
  is already on. Signed-out navigation toward a `:requires-auth` route is
  skipped (so the protected route never commits and its `:on-match` loaders
  never fire) and redirected to login."
  [ctx]
  (if-let [{:keys [id]} (nav-target (get-in ctx [:coeffects :event])
                                    (get-in ctx [:coeffects :rf.db/runtime
                                                 :rf.runtime/routing :current]))]
    (let [needs-auth? (contains? (:tags (rf/handler-meta {:source :store :kind :route :id id})) :requires-auth)
          signed-in?  (some? (get-in ctx [:coeffects :db :auth :user]))]
      (if (and needs-auth? (not signed-in?))
        (-> ctx
            (assoc :rf/skip-handler? true)                ;; protected route never commits
            (assoc-in [:effects :fx]
                      [[:dispatch [:rf.route/navigate {:to :app/login}]]]))
        ctx))
    ctx))                                                 ;; not a navigation ⇒ pass through

(defn- register! []
  (rf/reg-route :app/home     {} "/")
  (rf/reg-route :app/login    {} "/login")
  (rf/reg-route :app/settings {:tags #{:requires-auth}} "/settings")
  (rf.fx/reg-fx :rf.nav/push-url    {:platforms #{:server :client}} (fn [_ _] nil))
  (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/reg-interceptor :app/auth-guard
    {:doc "Redirect signed-out users away from :requires-auth routes."}
    {:before auth-guard-before}))

;; ---- Helpers: drive the REGISTERED guard's :before over a real ctx ---------

(defn- ctx-for
  "The `:before` ctx the runtime hands the guard for `event`: signed out unless
  `user` is supplied, carrying `slice` as the current route slice in the
  reserved `:rf.db/runtime` coeffect (the exact seam navigate-handler reads)."
  ([event]            (ctx-for event nil nil))
  ([event slice]      (ctx-for event slice nil))
  ([event slice user] {:coeffects {:event         event
                                   :db            (if user {:auth {:user user}} {})
                                   :rf.db/runtime {:rf.runtime/routing {:current slice}}}}))

(defn- skipped? [ctx] (true? (:rf/skip-handler? (auth-guard-before ctx))))
(defn- redirect [ctx] (get-in (auth-guard-before ctx) [:effects :fx]))

(def ^:private login-redirect [[:dispatch [:rf.route/navigate {:to :app/login}]]])

(defn- real-slice-on
  "Run a real (guard-free) navigation and return the resulting runtime-db route
  slice — the exact shape the runtime hands the guard as `:rf.db/runtime`. Used
  to feed the in-place rows a slice the RUNTIME produced, not a hand-rolled
  one, so the pin proves the guard resolves self the same way the runtime does."
  [event]
  (rf/dispatch-sync event)
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          [:rf.runtime/routing :current]))

;; ---------------------------------------------------------------------------
;; The matrix: protected fails CLOSED (skip + redirect) on every door + target
;; form; public is delivered normally (ctx untouched).
;; ---------------------------------------------------------------------------

(deftest auth-guard-matrix-fails-closed-across-all-doors
  (register!)
  (let [settings-slice (real-slice-on [:rf.route/navigate {:to :app/settings}])
        home-slice     (real-slice-on [:rf.route/navigate {:to :app/home}])]

    (testing "route-id :rf.route/navigate"
      (is (skipped? (ctx-for [:rf.route/navigate {:to :app/settings}]))
          "route-id navigate to a :requires-auth route is skipped")
      (is (= login-redirect (redirect (ctx-for [:rf.route/navigate {:to :app/settings}])))
          "and redirected to login")
      (let [ctx (ctx-for [:rf.route/navigate {:to :app/home}])]
        (is (= ctx (auth-guard-before ctx))
            "public route-id navigate passes through untouched (normal delivery)")))

    (testing "raw-URL {:url ...} :rf.route/navigate — the rf2-e9k3dr fix"
      (is (skipped? (ctx-for [:rf.route/navigate {:url "/settings"}]))
          "a {:url ...} navigate to a protected route is skipped")
      (is (skipped? (ctx-for [:rf.route/navigate {:url "/settings?tab=x"}]))
          "a query string on the raw-URL target still resolves + gates")
      (let [ctx (ctx-for [:rf.route/navigate {:url "/"}])]
        (is (= ctx (auth-guard-before ctx))
            "a {:url ...} navigate to a public route is delivered normally")))

    (testing "in-place :rf.route/navigate (no :to / :url) — the rf2-yp3ip fix"
      (is (skipped? (ctx-for [:rf.route/navigate {:query-merge {:tab "x"}}]
                             settings-slice))
          "FAILING-BEFORE: a signed-out self-nav from a protected route MUST be
           gated — the old branch read handler-meta on the request's named target
           keyword, saw no tags, and opened")
      (is (= login-redirect
             (redirect (ctx-for [:rf.route/navigate {:query-merge {:tab "x"}}]
                                settings-slice)))
          "and redirected to login")
      (let [ctx (ctx-for [:rf.route/navigate {:query-merge {:page 2}}]
                         home-slice)]
        (is (= ctx (auth-guard-before ctx))
            "a self-nav from a PUBLIC route is delivered normally"))
      (is (let [ctx (ctx-for [:rf.route/navigate {:query-merge {:tab "x"}}]
                             settings-slice {:id 1})]
            (= ctx (auth-guard-before ctx)))
          "a self-nav from a protected route while SIGNED IN is delivered normally"))

    (testing "route-link click (:rf.route/url-requested) — both :to and :url"
      (is (skipped? (ctx-for [:rf.route/url-requested {:url "/settings"}]))
          "a link click whose href resolves to a protected route is gated")
      (is (skipped? (ctx-for [:rf.route/url-requested {:to :app/settings}]))
          "a :to link click to a protected route is gated")
      (let [ctx (ctx-for [:rf.route/url-requested {:url "/"}])]
        (is (= ctx (auth-guard-before ctx))
            "a link click to a public route is delivered normally")))

    (testing "URL-bar / popstate / deep-link (:rf.route/handle-url-change)"
      (is (skipped? (ctx-for [:rf.route/handle-url-change "/settings"]))
          "pasting / reloading a protected URL is gated")
      (let [ctx (ctx-for [:rf.route/handle-url-change "/"])]
        (is (= ctx (auth-guard-before ctx))
            "the home URL is delivered normally")))

    (testing "unresolvable + non-navigation events stand aside"
      (is (not (skipped? (ctx-for [:rf.route/navigate {:url "/no/such/path"}])))
          "a garbage raw-URL navigate is a non-match — the runtime routes it to
           :rf.route/not-found, which is not protected")
      (is (not (skipped? (ctx-for [:rf.route/handle-url-change "/no/such/path"])))
          "a garbage URL-bar entry is a non-match")
      (let [ctx (ctx-for [:some/other-event 1 2])]
        (is (= ctx (auth-guard-before ctx))
            "an ordinary event is not a navigation — the guard stands aside")))))

;; ---- End-to-end: guard on the frame; the protected route does NOT commit ---

(deftest end-to-end-protected-self-nav-does-not-commit
  (testing "rf2-yp3ip end-to-end — guard attached to the URL-owning frame: a
            signed-out self-nav from a protected route is SKIPPED, so its query
            change never commits to the route slice (the protected route and its
            loaders do not commit)"
    (register!)
    (rf/reg-event :test/sign-in  (fn [{:keys [db]} _] {:db (assoc-in db [:auth :user] {:id 1})}))
    (rf/reg-event :test/sign-out (fn [{:keys [db]} _] {:db (update db :auth dissoc :user)}))
    ;; Attach the canonical guard to the URL-owning frame — it now runs :before
    ;; every navigation entry event.
    (rf/make-frame {:id :rf/default :url-bound? true :interceptors [:app/auth-guard]})

    ;; Signed in, land on the protected route (guard lets us through).
    (rf/dispatch-sync [:test/sign-in])
    (rf/dispatch-sync [:rf.route/navigate {:to :app/settings}])
    (is (= :app/settings (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                 [:rf.runtime/routing :current :route-id]))
        "signed in, the guard admits the protected route")

    ;; Session expires; a self-nav (query change) must NOT commit.
    (rf/dispatch-sync [:test/sign-out])
    (rf/dispatch-sync [:rf.route/navigate {:query-merge {:tab "secret"}}])
    (let [cur (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                      [:rf.runtime/routing :current])]
      (is (not= "secret" (get-in cur [:query :tab]))
          "signed out, the guard skips the self-nav handler — the query change
           never commits (fail CLOSED); the runtime would otherwise have applied
           it in place on the protected route"))))

;; ---- The recipe mirrors the SHIPPED runtime (verify against navigate.cljc) --

(deftest runtime-resolves-raw-url-navigate-to-the-protected-route
  (testing "the shipped :rf.route/navigate resolves a {:url ...} target through
            match-url onto the real route-id — WHY the recipe must normalise the
            same way rather than trust the target to be a keyword"
    (register!)
    (rf/dispatch-sync [:rf.route/navigate {:url "/settings"}])
    (is (= :app/settings
           (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                   [:rf.runtime/routing :current :route-id]))
        "raw-URL navigate lands the slice on the protected route-id")
    (is (= :app/settings (:route-id (rf.routing/match-url "/settings")))
        "match-url hands the recipe the same route-id the runtime used")))

(deftest runtime-resolves-self-to-the-current-protected-route
  (testing "the shipped :rf.route/navigate resolves an IN-PLACE request against
            the CURRENT route slice — WHY the guard must read the current slice
            the same way, or it fails open on a query-only change"
    (register!)
    ;; The runtime holds the route-id fixed at the current (protected) route and
    ;; applies only the query change — the operation the guard must recognise.
    (rf/dispatch-sync [:rf.route/navigate {:to :app/settings}])
    (rf/dispatch-sync [:rf.route/navigate {:query-merge {:tab "x"}}])
    (let [cur (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                      [:rf.runtime/routing :current])]
      (is (= :app/settings (:route-id cur))
          "in-place nav holds the route-id fixed at the protected route")
      (is (= "x" (get-in cur [:query :tab]))
          "and applies only the query change")
      (is (contains? (:tags (rf/handler-meta {:source :store :kind :route :id (:route-id cur)})) :requires-auth)
          "resolving the in-place request to the current route-id surfaces the
           :requires-auth tag the guard reads to fail closed"))))
