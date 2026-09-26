(ns re-frame.routing-auth-guard-matrix-test
  "Executable integration matrix pinning the canonical cross-cutting auth-guard
  INTERCEPTOR recipe — the one for a policy that genuinely is not about routes
  (a maintenance-mode lockout, a feature flag over a whole section), which lives
  in docs/routing/how-to/require-sign-in-on-a-route.md §A policy that is not
  about routes and is cross-referenced from docs/core/how-to/add-auth.md
  §Appendix and spec/012-Routing.md §Redirects and guards.

  It is NOT the route-auth recipe. Route auth is `:can-enter` metadata plus a
  `:rf.route/entry-denied` handler, evaluated in the one planning pipeline every
  door already funnels through — no normaliser, nothing to enumerate, and both
  RealWorld examples spell it that way. What this
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
  Its `matched-id`, `nav-target` and `:before` are the recipe's, verbatim. The
  doc is not read at test time, so a change to the recipe must be copied here
  by hand.

  A `:rf.route/navigate` branch that normalises a `{:url ...}` target as a
  route id UNCONDITIONALLY would let a map target fall through as a route id:
  `handler-meta` on a MAP returns nil, `:requires-auth` is missed, and the
  protected route is entered — a fail-OPEN hole on the raw-URL
  escape hatch.

  An IN-PLACE request (no `:to` / `:url`, patching the
  current route's query — a tab switch, `?page=2`) must resolve against the
  CURRENT route slice, not a target the request names. A guard that fails to do
  so stands aside exactly where it is most dangerous: a session that expires
  WHILE the user sits on a `:requires-auth` route can navigate in place (a query
  change, a tab switch) straight past the guard. The guard mirrors the runtime:
  it resolves an in-place request from the CURRENT route slice
  (`[:rf.runtime/routing :current]`, carried in the `:rf.db/runtime` coeffect)
  before reading the tags, so the guard sees the protected route and fails
  CLOSED.

  This suite guards BOTH holes: a `:rf.route/navigate` branch that trusts the
  request's named target
  flips the raw-URL rows AND the in-place rows from gated to open."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

;; ---------------------------------------------------------------------------
;; The canonical guard — the recipe's `matched-id`, `nav-target` and `:before`,
;; verbatim. Registered as `:app/auth-guard` below and driven directly (its
;; `:before`) AND end-to-end through the frame. `nav-target` reduces a
;; navigation event to {:id <route-id> :params <map>} (or nil); `current` is
;; the current route slice, so an in-place request resolves against it exactly
;; as the runtime does.
;; ---------------------------------------------------------------------------

(defn- matched-id
  "The route a URL matches as {:id :params}, or nil when nothing matches or the
   params fail their schema (the runtime sends those to not-found)."
  [url]
  (when-let [{:keys [route-id params validation-failed?]} (rf.routing/match-url url)]
    (when-not validation-failed?
      {:id route-id :params (or params {})})))

(defn- nav-target
  "Reduce a navigation event to {:id :params}, or nil. `current` is the route
   slice, needed for an in-place navigate, whose target is the current route."
  [[ev-id a] current]
  (case ev-id
    :rf.route/navigate
    (let [{:keys [to url params]} a]
      (cond
        to  {:id to :params (or params {})}
        url (matched-id url)
        ;; in-place: a query or fragment edit on the current route
        (and (nil? params)
             (or (contains? a :query) (contains? a :query-merge) (contains? a :fragment)))
        {:id (:route-id current) :params (or (:params current) {})}
        :else nil))     ;; malformed: the runtime rejects it with :rf.error/navigate-bad-request

    :rf.route/url-requested     (matched-id (:url a))
    :rf.route/handle-url-change (matched-id a)
    nil))

(defn- auth-guard-before
  "The recipe's `:before`. Reads the current route slice from the
  `:rf.db/runtime` coeffect so an in-place request resolves to the route the user
  is already on. Signed-out navigation toward a `:requires-auth` route is
  skipped (so the protected route never commits and its `:on-match` loaders
  never fire) and redirected to login."
  [ctx]
  (if-let [{:keys [id]} (nav-target (get-in ctx [:coeffects :event])
                                    (get-in ctx [:coeffects :rf.db/runtime
                                                 :rf.runtime/routing :current]))]
    (let [route-meta  (rf/handler-meta {:source :store :kind :route :id id})
          needs-auth? (contains? (:tags route-meta) :requires-auth)
          signed-in?  (some? (get-in ctx [:coeffects :db :auth/user]))]
      (if (and needs-auth? (not signed-in?))
        (-> ctx
            (assoc :rf/skip-handler? true)
            (assoc-in [:effects :fx]
                      [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]]))
        ctx))
    ctx))

(defn- register! []
  (rf/reg-route :app/home     {} "/")
  (rf/reg-route :app/login    {} "/login")
  (rf/reg-route :app/settings {:tags #{:requires-auth}} "/settings")
  (rf.fx/reg-fx :rf.nav/push-url    {:platforms #{:server :client}} (fn [_ _] nil))
  (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/reg-interceptor :app/auth-guard
    {:doc "Redirect signed-out readers away from :requires-auth routes."}
    {:before auth-guard-before}))

;; ---- Helpers: drive the REGISTERED guard's :before over a real ctx ---------

(defn- ctx-for
  "The `:before` ctx the runtime hands the guard for `event`: signed out unless
  `user` is supplied, carrying `slice` as the current route slice in the
  reserved `:rf.db/runtime` coeffect (the exact seam navigate-handler reads)."
  ([event]            (ctx-for event nil nil))
  ([event slice]      (ctx-for event slice nil))
  ([event slice user] {:coeffects {:event         event
                                   :db            (if user {:auth/user user} {})
                                   :rf.db/runtime {:rf.runtime/routing {:current slice}}}}))

(defn- skipped? [ctx] (true? (:rf/skip-handler? (auth-guard-before ctx))))
(defn- redirect [ctx] (get-in (auth-guard-before ctx) [:effects :fx]))

(def ^:private login-redirect [[:dispatch [:rf.route/navigate {:to :app/login :replace? true}]]])

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

    (testing "raw-URL {:url ...} :rf.route/navigate"
      (is (skipped? (ctx-for [:rf.route/navigate {:url "/settings"}]))
          "a {:url ...} navigate to a protected route is skipped")
      (is (skipped? (ctx-for [:rf.route/navigate {:url "/settings?tab=x"}]))
          "a query string on the raw-URL target still resolves + gates")
      (let [ctx (ctx-for [:rf.route/navigate {:url "/"}])]
        (is (= ctx (auth-guard-before ctx))
            "a {:url ...} navigate to a public route is delivered normally")))

    (testing "in-place :rf.route/navigate (no :to / :url)"
      (is (skipped? (ctx-for [:rf.route/navigate {:query-merge {:tab "x"}}]
                             settings-slice))
          "a signed-out self-nav from a protected route MUST be gated — reading
           handler-meta on the request's named target keyword would see no tags
           and open")
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

    (testing "route-link click (:rf.route/url-requested, whose payload is {:url ...})"
      (is (skipped? (ctx-for [:rf.route/url-requested {:url "/settings"}]))
          "a link click whose href resolves to a protected route is gated")
      (is (= login-redirect (redirect (ctx-for [:rf.route/url-requested {:url "/settings"}])))
          "and redirected to login")
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
  (testing "end-to-end — guard attached to the URL-owning frame: a
            signed-out self-nav from a protected route is SKIPPED, so its query
            change never commits to the route slice (the protected route and its
            loaders do not commit)"
    (register!)
    (rf/reg-event :test/sign-in  (fn [{:keys [db]} _] {:db (assoc db :auth/user {:id 1})}))
    (rf/reg-event :test/sign-out (fn [{:keys [db]} _] {:db (dissoc db :auth/user)}))
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
      ;; `:app/settings` declares no query vocabulary, so
      ;; `:tab` commits the way the URL spells it — a string key.
      (is (= "x" (get-in cur [:query "tab"]))
          "and applies only the query change")
      (is (contains? (:tags (rf/handler-meta {:source :store :kind :route :id (:route-id cur)})) :requires-auth)
          "resolving the in-place request to the current route-id surfaces the
           :requires-auth tag the guard reads to fail closed"))))
