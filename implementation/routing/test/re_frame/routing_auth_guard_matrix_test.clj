(ns re-frame.routing-auth-guard-matrix-test
  "Executable matrix pinning the canonical auth-guard recipe
  (docs/routing/how-to/require-sign-in-on-a-route.md, docs/core/how-to/add-auth.md,
  spec/012-Routing.md §Cross-cutting guards) across ALL FOUR navigation entry
  points: route-id `:rf.route/navigate`, the raw-URL `{:url ...}` navigate
  escape hatch, a `route-link` click (`:rf.route/url-requested`), and a URL-bar /
  popstate / deep-link (`:rf.route/handle-url-change`).

  rf2-e9k3dr — the recipe's `:rf.route/navigate` branch normalised its target as a
  route id UNCONDITIONALLY. But `:rf.route/navigate` also accepts a `{:url \"/settings\"}`
  target (the runtime resolves it through `match-url` — see
  re_frame/routing/navigate.cljc:294), so a map target fell through as a route id:
  `handler-meta` on a MAP returns nil, `:requires-auth` is missed, and the protected
  route is entered — a fail-OPEN security hole on the most common escape-hatch path.

  The recipe now mirrors the runtime — map targets normalise through `match-url`
  before the tag read. This suite is the failing-before / passing-after guard: revert
  the `:rf.route/navigate` branch to `{:id a :params (or b {})}` and the raw-URL rows
  below flip from `true`/gated to `false`/open."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.routing :as routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

;; The canonical recipe's normaliser — the FIXED form (rf2-e9k3dr). Kept
;; byte-faithful to the shipped docs so a drift between the doc recipe and this
;; pin trips a test. `:rf.route/navigate` now branches on the target shape: a
;; `{:url ...}` map is resolved through `match-url` exactly as the runtime does;
;; a keyword is a route id, unchanged.
(defn- nav-target [[ev-id a b]]
  (case ev-id
    :rf.route/navigate
    (if (map? a)                                       ;; {:url ...} escape-hatch target
      (when-let [{:keys [route-id params]} (routing/match-url (:url a))]
        {:id route-id :params (or params {})})
      {:id a :params (or b {})})                        ;; route-id target — unchanged

    :rf.route/url-requested
    (let [{:keys [to params url]} a]
      (cond
        to  {:id to :params (or params {})}
        url (when-let [{:keys [route-id params]} (routing/match-url url)]
              {:id route-id :params (or params {})})))

    :rf.route/handle-url-change
    (when-let [{:keys [route-id params]} (routing/match-url a)]
      {:id route-id :params (or params {})})

    nil))

(defn- guard-decision
  "The guard's protected-path decision for `event`, signed out:
   nil   — not a resolvable navigation (guard stands aside),
   true  — a `:requires-auth` route was targeted (guard redirects — fail CLOSED),
   false — a public route was targeted (guard allows)."
  [event]
  (when-let [{:keys [id]} (nav-target event)]
    (contains? (:tags (rf/handler-meta :route id)) :requires-auth)))

(defn- register-routes! []
  (rf/reg-route :app/home     {} "/")
  (rf/reg-route :app/login    {} "/login")
  (rf/reg-route :app/settings {:tags #{:requires-auth}} "/settings")
  (fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil)))

;; ---- The matrix: protected fails CLOSED on every door ---------------------

(deftest auth-guard-matrix-fails-closed-across-all-doors
  (register-routes!)

  (testing "route-id :rf.route/navigate — protected gated, public allowed"
    (is (true?  (guard-decision [:rf.route/navigate :app/settings]))
        "route-id navigate to a :requires-auth route is gated")
    (is (false? (guard-decision [:rf.route/navigate :app/home]))
        "route-id navigate to a public route is allowed"))

  (testing "raw-URL {:url ...} :rf.route/navigate — the rf2-e9k3dr fix"
    (is (true?  (guard-decision [:rf.route/navigate {:url "/settings"}]))
        "FAILING-BEFORE: a {:url ...} navigate to a protected route MUST be gated
         — the old branch read handler-meta on the URL map, saw no tags, opened")
    (is (false? (guard-decision [:rf.route/navigate {:url "/"}]))
        "a {:url ...} navigate to a public route is allowed")
    (is (true?  (guard-decision [:rf.route/navigate {:url "/settings?tab=x"}]))
        "query string on the raw-URL target still resolves + gates"))

  (testing "route-link click (:rf.route/url-requested) — both :to and :url forms"
    (is (true?  (guard-decision [:rf.route/url-requested {:url "/settings"}]))
        "a link click whose href resolves to a protected route is gated")
    (is (true?  (guard-decision [:rf.route/url-requested {:to :app/settings}]))
        "a :to link click to a protected route is gated")
    (is (false? (guard-decision [:rf.route/url-requested {:url "/"}]))
        "a link click to a public route is allowed"))

  (testing "URL-bar / popstate / deep-link (:rf.route/handle-url-change)"
    (is (true?  (guard-decision [:rf.route/handle-url-change "/settings"]))
        "pasting / reloading a protected URL is gated")
    (is (false? (guard-decision [:rf.route/handle-url-change "/"]))
        "the home URL is allowed"))

  (testing "unresolvable + non-navigation events short-circuit the guard"
    (is (nil? (guard-decision [:rf.route/navigate {:url "/no/such/path"}]))
        "a garbage raw-URL navigate is a non-match — the runtime routes it to
         :rf.route/not-found, which is not a protected route")
    (is (nil? (guard-decision [:rf.route/handle-url-change "/no/such/path"]))
        "a garbage URL-bar entry is a non-match")
    (is (nil? (guard-decision [:some/other-event 1 2]))
        "an ordinary event is not a navigation — the guard stands aside")))

;; ---- The recipe mirrors the SHIPPED runtime (verify against navigate.cljc) --

(deftest runtime-resolves-raw-url-navigate-to-the-protected-route
  (testing "the shipped :rf.route/navigate resolves a {:url ...} target through
            match-url onto the real route-id — which is exactly WHY the recipe
            must normalise the same way rather than trust the target to be a keyword"
    (register-routes!)
    (rf/dispatch-sync [:rf.route/navigate {:url "/settings"}])
    (is (= :app/settings
           (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                   [:rf.runtime/routing :current :route-id]))
        "raw-URL navigate lands the slice on the protected route-id")
    (is (= :app/settings (:route-id (routing/match-url "/settings")))
        "match-url hands the recipe the same route-id the runtime used")))
