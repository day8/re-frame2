(ns re-frame.routing-navigation-test
  "Navigation-planning tests for re-frame.routing — the programmatic
  `:rf.route/navigate` and URL-driven `:rf.route/transitioned` /
  `:rf.route/handle-url-change` entry points (fragment handling,
  not-found fallback, fail-closed hostile-URL handling, no-op /
  fragment-only short-circuits, address-bar parity, arity misuse). Split
  from routing_test.clj per rf2-u8qe7y finding 3."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.identity :as identity]
            [re-frame.late-bind :as late-bind]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

;; ---- Spec 012 §Navigation is an event -------------------------------------

(deftest routing-bootstrap
  (testing "reg-route + dispatch :rf.route/navigate writes the :rf/route slice"
    ;; Per Spec 012 §Navigation is an event: dispatching :rf.route/navigate
    ;; with a route-id + params updates :rf/route.{id,params,query,...} and
    ;; emits a :rf.nav/push-url effect.
    (rf/reg-route :route/home    {} "/")
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    ;; Capture the URL that lands at :rf.nav/push-url.
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "intro"}}])
      (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
        (is (= :route/article (:route-id slice))
            "the :rf/route slice carries the navigation target")
        (is (= {:id "intro"} (:params slice))
            ":params from the navigate vector landed in the slice")
        (is (= :idle (:transition slice))
            "no :on-match → transition stays :idle"))
      (is (= ["/articles/intro"] @pushed)
          ":rf.nav/push-url received the unparsed URL"))))

;; ---- Spec 012 §Query strings and fragments — :query-retain ---------------

(deftest routing-query-retain-carries-keys-across-navigations
  (testing ":query-retain on the target carries declared keys from the current slice"
    ;; Per Spec 012 §Query strings and fragments: `:query-retain` names a
    ;; set of query keys that survive subsequent :rf.route/navigate
    ;; dispatches even when the caller doesn't supply them — useful for
    ;; theme / locale / debug. The retained values come from the current
    ;; :rf.route/query slice; caller-supplied values win on conflict
    ;; (rf2-u8t3s).
    (rf/reg-route :route/search
                  {:query-retain   #{:theme :locale}} "/search")
    (rf/reg-route :route/cart
                  {:query-retain #{:theme :locale}} "/cart")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))

      ;; 1. Land on /search with ?theme=dark&locale=en — the URL-driven
      ;;    path populates the slice via match-url + handle-url-change.
      (rf/dispatch-sync [:rf.route/transitioned "/search?theme=dark&locale=en"])
      (is (= {:theme "dark" :locale "en"}
             (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current :query]))
          "initial slice carries the URL's query keys")

      ;; 2. Navigate programmatically to :route/cart with NO query — the
      ;;    target's :query-retain must merge :theme + :locale through.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/cart}])
      (let [last-url (last @pushed)]
        (is (re-find #"theme=dark" last-url)
            ":query-retain preserves :theme through programmatic nav")
        (is (re-find #"locale=en" last-url)
            ":query-retain preserves :locale through programmatic nav"))

      ;; 3. Caller-supplied query values WIN over retained values.
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/navigate {:to :route/cart :query {:theme "light"}}])
      (let [last-url (last @pushed)]
        (is (re-find #"theme=light" last-url)
            "caller-supplied :theme overrides retained value")
        (is (re-find #"locale=en" last-url)
            "other retained keys still carry through")))))

(deftest routing-query-retain-no-op-without-declaration
  (testing "routes without :query-retain do not inherit query keys"
    (rf/reg-route :route/search
                  {:query-retain #{:theme}} "/search")
    (rf/reg-route :route/cart
                  {} "/cart") ;; no :query-retain
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/transitioned "/search?theme=dark"])
      (rf/dispatch-sync [:rf.route/navigate {:to :route/cart}])
      (is (= "/cart" (last @pushed))
          ":query-retain undeclared → no carry-through, URL stays bare"))))

;; ---- rf2-gxq7z1: nil-valued :query opt must not leak into the slice -------
;;
;; `route-url` ELIDES a nil-valued query key from the URL (and validates the
;; elided map), so `[:rf.route/navigate route {} {:query {:sort nil}}]` pushes
;; a bare `/search`. The plain `:query` opt used to write `{:sort nil}` VERBATIM
;; into the slice (the nil-strip only ran on the `:query-merge` branch), so the
;; slice diverged from the URL: a later URL-driven nav to the same `/search`
;; yields `:query {}`, `{:sort nil}` != `{}`, and the rule-3 no-op failed —
;; `:on-match` spuriously re-fired. The strip now runs on every branch.

(deftest navigate-nil-query-value-does-not-leak-into-slice-rf2-gxq7z1
  (testing "a nil-valued :query opt is elided from the slice (matching the
            pushed URL), so a later URL-driven nav to the same URL is a rule-3
            no-op — no :on-match re-fire, no fresh nav-token"
    (let [fires (atom 0)]
      (rf/reg-event :route/search-loaded (fn [{:keys [db]} _] (swap! fires inc) {:db db}))
      (rf/reg-route :route/search
                    {:query    [:map [:sort {:optional true} :string]]
                     :on-match [[:route/search-loaded]]}
                    "/search")
      ;; Programmatic nav with an explicit nil :sort — route-url elides it from
      ;; the URL, so the slice must NOT carry {:sort nil}.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/search :query {:sort nil}}])
      (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                          [:rf.runtime/routing :current])]
        (is (= {} (:query slice))
            "the slice :query has NO :sort key — matches the pushed /search URL")
        (is (not (contains? (:query slice) :sort))
            "no leaked nil-valued :sort key")
        (let [fires-after-nav @fires
              token-after-nav (:nav-token slice)]
          (is (= 1 fires-after-nav) ":on-match fired once on the initial nav")
          ;; URL-driven nav to the SAME URL: identical-route-target? compares
          ;; slice-query {} vs match-query {} — equal → rule-3 no-op. With the
          ;; {:sort nil} leak this comparison was {:sort nil} vs {} → NOT equal
          ;; → a spurious re-fire + fresh token.
          (rf/dispatch-sync [:rf.route/transitioned "/search"])
          (let [slice' (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                               [:rf.runtime/routing :current])]
            (is (= fires-after-nav @fires)
                ":on-match did NOT re-fire — slice query matched the URL query")
            (is (= token-after-nav (:nav-token slice'))
                "the nav-token is unchanged — a genuine rule-3 no-op")))))))

(deftest navigate-pushes-url-with-fragment
  (testing ":rf.route/navigate with :fragment opt pushes the URL with #fragment appended"
    ;; Per Spec 012 §Fragments §Programmatic navigation with fragments:
    ;; `[:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "x"}]`
    ;; pushes "/docs/routing#x" via :rf.nav/push-url. The 4-arity route-url
    ;; is the canonical builder; the navigate handler routes opts → 4-arity.
    (rf/reg-route :route/docs {} "/docs/:page")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "scroll-restoration"}])
      (is (= ["/docs/routing#scroll-restoration"] @pushed)
          ":rf.nav/push-url received the URL WITH the appended #fragment"))))

(deftest navigate-url-form-preserves-fragment
  (testing ":rf.route/navigate with URL-string target preserves the URL's
            embedded #fragment in the pushed URL"
    (rf/reg-route :route/docs {} "/docs/:page")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate {:url "/docs/routing#scroll-restoration"}])
      (is (= ["/docs/routing#scroll-restoration"] @pushed)
          "fragment in the URL-string target round-trips through the push"))))

;; ============================================================================
;; rf2-ynjts.11 — coverage & rigour pass: genuine gaps in the navigate /
;; pending-nav / scroll-strategy / route-url surfaces. Each test below pins a
;; documented branch the existing suite exercises only obliquely (or not at
;; all). Behaviour-asserting + deterministic; no src behaviour changed.
;; ============================================================================

;; ---- navigate URL-string target that matches NO route --------------------
;;
;; navigate.cljc's `{:url ...}` target branch resolves an unmatched URL to
;; `(or (:route-id match) :rf.route/not-found)` with `:params {:url url}`.
;; `navigate-url-form-preserves-fragment` (above) only covers the MATCHING
;; URL-string case; the not-found fallback through the programmatic
;; URL-string entry point (distinct from the URL-driven
;; `:rf.route/transitioned` path that `transitioned-unmatched-url-routes-to-
;; not-found` covers) was unpinned.

(deftest navigate-url-form-unmatched-routes-to-not-found
  (testing ":rf.route/navigate with an unmatched {:url ...} target lands on
            :rf.route/not-found carrying {:url url} in :params, and pushes
            the REQUESTED url (NOT the not-found route's literal /404) so
            the address bar keeps the URL the caller aimed at — consistent
            with the URL-driven not-found path (rf2-0zr2o, Option A)"
    (rf/reg-route :route/home {} "/")
    (rf/reg-route :rf.route/not-found {} "/404")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate {:url "/no/such/path"}])
      (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
        (is (= :rf.route/not-found (:route-id slice))
            "unmatched URL-string target → :rf.route/not-found slice")
        (is (= {:url "/no/such/path"} (:params slice))
            ":params carries the unmatched URL under :url")
        (is (some? (:nav-token slice))
            "a fresh nav-token is allocated for the not-found navigation"))
      (is (= ["/no/such/path"] @pushed)
          ":rf.nav/push-url pushed the REQUESTED url — the address bar keeps
           /no/such/path, NOT the not-found route's fabricated /404"))))

(deftest navigate-url-form-unmatched-without-not-found-route-commits-and-warns
  (testing "unmatched {:url ...} target with NO :rf.route/not-found route
            registered still COMMITS the not-found slice, pushes the
            REQUESTED url, and emits :rf.warning/no-not-found-route — it does
            NOT reject. Mirrors the URL-driven path (url-change-fx), which
            warns + commits rather than throwing. Pre-rf2-0zr2o this branch
            called route-url on the unregistered :rf.route/not-found id,
            which threw :no-such-route and rejected; pushing the requested
            url verbatim removes that route-url call entirely (Option A)."
    ;; NB: kept a SEPARATE deftest from the registered-not-found case above
    ;; — the per-deftest fixture gives this a clean registrar with NO
    ;; :rf.route/not-found, which is exactly the condition under test.
    (rf/reg-route :route/home {} "/")
    (let [pushed (atom [])
          traces (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      ;; Land on home so the not-found commit displaces a known slice.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
      (reset! pushed [])
      (rf/register-listener! :trace ::nav-nf-warn (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate {:url "/no/such/path"}])
      (rf/unregister-listener! :trace ::nav-nf-warn)
      (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
        (is (= :rf.route/not-found (:route-id slice))
            "slice commits to :rf.route/not-found even with no such route
             registered — consistent with the URL-driven path")
        (is (= {:url "/no/such/path"} (:params slice))
            ":params carries the unmatched URL under :url"))
      (is (= ["/no/such/path"] @pushed)
          ":rf.nav/push-url pushed the REQUESTED url verbatim — NOT a
           fabricated /404, and no rejection")
      (is (some (fn [ev] (= :rf.warning/no-not-found-route (:operation ev)))
                @traces)
          ":rf.warning/no-not-found-route fires when no 404 route is
           registered — same signal as the URL-driven path")
      (is (not-any? (fn [ev]
                      (= :rf.error/schema-validation-failure (:operation ev)))
                    @traces)
          "no schema-validation-failure error — the unmatched path no longer
           rejects via a route-url throw"))))

;; ---- rf2-0zr2o: programmatic-miss and URL-driven-miss agree on the URL ----
;;
;; The two not-found entry points must keep the same URL in the address bar.
;; PROGRAMMATIC `navigate {:url <miss>}` pushes the REQUESTED url (Option A);
;; URL-DRIVEN `:rf.route/transitioned <miss>` emits NO push (the URL already
;; changed via link/popstate). This regression pins both halves in one test
;; so a future refactor cannot drift the programmatic path back to /404 or
;; sneak a push onto the URL-driven path.

(deftest not-found-address-bar-parity-programmatic-vs-url-driven
  (testing "programmatic navigate to an unmatched url pushes the REQUESTED
            url (not /404); the URL-driven not-found path stays unchanged
            (emits no push) — both keep the requested url in the address bar
            (rf2-0zr2o)"
    (rf/reg-route :route/home {} "/")
    ;; The not-found route's literal :path is /404; the fix must NOT push it.
    (rf/reg-route :rf.route/not-found {} "/404")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))

      ;; ---- PROGRAMMATIC miss: pushes the requested url, not /404 ----
      (rf/dispatch-sync [:rf.route/navigate {:url "/no/such/path"}])
      (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
        (is (= :rf.route/not-found (:route-id slice))
            "programmatic miss renders the not-found view (slice id)")
        (is (= {:url "/no/such/path"} (:params slice))
            "programmatic miss carries the requested url in :params"))
      (is (= ["/no/such/path"] @pushed)
          "programmatic miss pushes the REQUESTED url — NOT the not-found
           route's literal /404")
      (is (not-any? #(= "/404" %) @pushed)
          "/404 is never pushed — the address bar is not rewritten to the
           not-found route's own path")

      ;; ---- URL-DRIVEN miss: unchanged — emits no push at all ----
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/transitioned "/another/miss"])
      (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
        (is (= :rf.route/not-found (:route-id slice))
            "URL-driven miss also renders the not-found view")
        (is (= {:url "/another/miss"} (:params slice))
            "URL-driven miss carries the requested url in :params"))
      (is (empty? @pushed)
          "URL-driven miss emits NO push — the URL already changed via
           link/popstate, so the address bar already shows the requested url
           (this path is unchanged by rf2-0zr2o)"))))

(deftest transitioned-validation-fail-routes-to-not-found-with-reason
  (testing ":rf.route/transitioned for a structurally-matched URL whose params
            fail schema validation routes to :rf.route/not-found with
            `:reason :validation` in :params (Spec 012 §Param validation)"
    (let [restore (rts/with-stub-validator)]
      (try
        (rf/reg-route :route/article
                      {:params (fn [{:keys [id]}] (clojure.string/starts-with? (or id "") "a"))} "/articles/:id")
        (rf/reg-route :rf.route/not-found {} "/404")
        (fx/reg-fx :rf.nav/push-url
                   {:platforms #{:server :client}}
                   (fn [_ _] nil))
        (rf/dispatch-sync [:rf.route/transitioned "/articles/zoo"])
        (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
          (is (= :rf.route/not-found (:route-id slice))
              "validation failure routes to :rf.route/not-found")
          (is (= "/articles/zoo" (:url (:params slice)))
              "params carries the URL")
          (is (= :validation (:reason (:params slice)))
              "params carries :reason :validation (distinguishes from a no-match miss)"))
        (finally (restore))))))

(deftest url-requested-classifies-external-before-push
  (testing ":rf.route/url-requested does not pushState or rewrite the route for external URLs"
    (rf/reg-route :route/home {} "/")
    (let [pushed (atom [])
          traces (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/transitioned "/"])
      (rf/register-listener! :trace ::external-url (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/url-requested {:url "https://example.invalid/cart"}])
      (rf/unregister-listener! :trace ::external-url)
      (is (empty? @pushed)
          "external URL is classified before :rf.nav/push-url")
      (is (= :route/home (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current :route-id]))
          "external URL does not become an app not-found route")
      (is (some #(= :rf.route/external-url-requested (:operation %)) @traces)
          "external classification is observable in the trace stream"))))

;; ---- rf2-zlr9k: :rf.route/navigate writes fragment + nav-token + trace --
;;
;; Per Spec 012 §Navigation is an event and §Navigation tokens programmatic
;; navigation allocates a fresh nav-token, writes :fragment + :nav-token into
;; the slice, and emits :rf.route.nav-token/allocated as the cascade begins.

(deftest navigate-writes-fragment-and-nav-token
  (testing ":rf.route/navigate writes :fragment + :nav-token into the slice
            and emits :rf.route.nav-token/allocated"
    (rf/reg-route :route/docs {} "/docs/:page")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::nav-token (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "scroll-restoration"}])
      (rf/unregister-listener! :trace ::nav-token)
      (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
        (is (= "scroll-restoration" (:fragment slice))
            ":fragment is assoc'd into the slice (pre-fix: missing)")
        (is (some? (:nav-token slice))
            ":nav-token is allocated (pre-fix: missing)"))
      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/allocated (:operation ev))
                       (= :route/docs (-> ev :tags :route-id))))
                @traces)
          ":rf.route.nav-token/allocated trace fires (pre-fix: never)"))))

(deftest navigate-no-fragment-still-allocates-nav-token
  (testing ":rf.route/navigate without a :fragment opt still writes :nav-token"
    (rf/reg-route :route/home {} "/")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
    (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
      (is (nil? (:fragment slice))
          ":fragment is nil when no opt supplied")
      (is (some? (:nav-token slice))
          ":nav-token is always allocated on navigation"))))

;; ---- rf2-d60go: :rf.route/handle-url-change writes the full slice shape -
;;
;; Per Spec 012 §The :rf/route slice and §URL changes are events the slice
;; carries `{:route-id :params :query :fragment :transition :error :nav-token}`
;; on every URL-driven write. Pre-fix this handler omitted :fragment and
;; :nav-token; the slice diverged in shape from the programmatic-nav path.

(deftest handle-url-change-writes-full-slice-shape
  (testing ":rf.route/handle-url-change writes :fragment and :nav-token
            into the slice (the seven-key canonical shape)"
    (rf/reg-route :route/docs {} "/docs/:page")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; URL with a fragment so we can assert :fragment is populated.
    (rf/dispatch-sync [:rf.route/handle-url-change
                       "/docs/routing#scroll-restoration"])
    (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
      (is (= :route/docs (:route-id slice))
          "slice id is the matched route")
      (is (= {:page "routing"} (:params slice))
          "params from the matched route")
      (is (= "scroll-restoration" (:fragment slice))
          ":fragment now populates the slice (pre-fix: missing)")
      (is (= :idle (:transition slice))
          "no :on-match → :transition :idle")
      (is (nil? (:error slice))
          ":error is nil on a clean nav")
      (is (some? (:nav-token slice))
          ":nav-token is allocated on every URL-driven nav (pre-fix: missing)"))))

(deftest handle-url-change-allocates-nav-token-trace
  (testing ":rf.route/handle-url-change emits :rf.route.nav-token/allocated"
    (rf/reg-route :route/home {} "/")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::handle-url-token
                             (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/handle-url-change "/"])
      (rf/unregister-listener! :trace ::handle-url-token)
      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/allocated (:operation ev))
                       (= :route/home (-> ev :tags :route-id))
                       (string? (-> ev :tags :nav-token))))
                @traces)
          ":rf.route.nav-token/allocated trace fires with the route-id and a fresh token"))))

;; ---- rf2-h4r9n: unmatched URL writes :rf.route/not-found slice -----------
;;
;; Per Spec 012 §Route-not-found an unmatched URL routes to
;; `:rf.route/not-found` with `{:url url}` in :params. The slice MUST be
;; rewritten so the view tree's `case` over `:rf.route/id` renders the 404
;; page; leaving the previous slice intact (pre-fix) showed the previous
;; route's UI through a navigation to a nonexistent URL.

(deftest transitioned-unmatched-url-routes-to-not-found
  (testing ":rf.route/transitioned for an unmatched URL writes the
            :rf.route/not-found slice with {:url url} in :params"
    (rf/reg-route :route/home {} "/")
    (rf/reg-route :rf.route/not-found {} "/404")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Land on home first so we have a previous slice to displace.
    (rf/dispatch-sync [:rf.route/transitioned "/"])
    (is (= :route/home (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current :route-id]))
        "initial nav landed on home")
    ;; Navigate to a URL that matches no registered route.
    (rf/dispatch-sync [:rf.route/transitioned "/this/does/not/exist"])
    (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
      (is (= :rf.route/not-found (:route-id slice))
          "unmatched URL → slice id becomes :rf.route/not-found")
      (is (= {:url "/this/does/not/exist"} (:params slice))
          "params carries the unmatched URL under :url")
      (is (= :idle (:transition slice))
          "no :on-match on not-found → transition is :idle")
      (is (some? (:nav-token slice))
          "a fresh nav-token is allocated even for not-found navigation"))))

(deftest transitioned-not-found-without-route-registered-warns
  (testing "when :rf.route/not-found is NOT registered, an unmatched URL
            still rewrites the slice AND emits :rf.warning/no-not-found-route"
    (rf/reg-route :route/home {} "/")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::no-not-found
                             (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/transitioned "/somewhere/unknown"])
      (rf/unregister-listener! :trace ::no-not-found)
      (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
        (is (= :rf.route/not-found (:route-id slice))
            "slice still rewrites to :rf.route/not-found"))
      (is (some (fn [ev]
                  (= :rf.warning/no-not-found-route (:operation ev)))
                @traces)
          ":rf.warning/no-not-found-route trace fires when no 404 route is registered"))))

;; ---- rf2-4ic0f: malformed URL fail-closed at :rf.route/transitioned -------------
;;
;; Per Spec 012 §Routing failure semantics §Malformed percent-encoding. A
;; URL whose %-encoding is malformed anywhere (path captures, query
;; key/value, or fragment) routes to :rf.route/not-found with `:reason
;; :malformed-url` on the slice's :params, and emits the structured
;; :rf.warning/malformed-url trace alongside the standard
;; :rf.error/no-such-handler. The discriminator distinguishes malformed
;; URLs from bare misses ({:url url}) and validation failures
;; ({:url url :reason :validation}).

(deftest transitioned-malformed-url-routes-to-not-found-with-reason
  (testing ":rf.route/transitioned for a malformed-%-encoded URL writes the
            :rf.route/not-found slice with `:reason :malformed-url`"
    (rf/reg-route :route/home    {} "/")
    (rf/reg-route :route/search  {} "/search")
    (rf/reg-route :rf.route/not-found {} "/404")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Path: a bare `%` in a captured segment.
    (rf/dispatch-sync [:rf.route/transitioned "/articles/%"])
    (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
      (is (= :rf.route/not-found (:route-id slice))
          "malformed path → :rf.route/not-found")
      (is (= {:url "/articles/%" :reason :malformed-url} (:params slice))
          "params carries the URL AND `:reason :malformed-url`"))
    ;; Query value.
    (rf/dispatch-sync [:rf.route/transitioned "/search?x=%"])
    (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
      (is (= :rf.route/not-found (:route-id slice)) "malformed query value → not-found")
      (is (= :malformed-url (get-in slice [:params :reason]))
          "the malformed-URL reason is on the slice"))
    ;; Query key.
    (rf/dispatch-sync [:rf.route/transitioned "/search?%=v"])
    (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
      (is (= :rf.route/not-found (:route-id slice)) "malformed query key → not-found")
      (is (= :malformed-url (get-in slice [:params :reason]))))
    ;; Fragment.
    (rf/dispatch-sync [:rf.route/transitioned "/search#%"])
    (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
      (is (= :rf.route/not-found (:route-id slice)) "malformed fragment → not-found")
      (is (= :malformed-url (get-in slice [:params :reason]))))))

(deftest transitioned-malformed-url-emits-structured-trace
  (testing ":rf.route/transitioned emits :rf.warning/malformed-url alongside the
            standard :rf.error/no-such-handler when the URL is malformed"
    (rf/reg-route :route/home {} "/")
    (rf/reg-route :rf.route/not-found {} "/404")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::malformed-trace
                             (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/transitioned "/articles/%"])
      (rf/unregister-listener! :trace ::malformed-trace)
      (is (some (fn [ev]
                  (and (= :rf.warning/malformed-url (:operation ev))
                       (= "/articles/%" (-> ev :tags :url))))
                @traces)
          ":rf.warning/malformed-url trace carries the offending URL")
      (is (some (fn [ev]
                  (and (= :rf.error/no-such-handler (:operation ev))
                       (= :route (-> ev :tags :kind))
                       (= :malformed-url (-> ev :tags :reason))))
                @traces)
          ":rf.error/no-such-handler carries `:reason :malformed-url`"))))

(deftest transitioned-forward-nav-traces-carry-frame-rf2-w3qgc
  (testing "rf2-w3qgc: forward URL-driven nav (`:rf.route/transitioned`)
            threads the active `frame` through `url-change-fx`, so the
            route-miss / malformed-url / no-not-found diagnostics carry
            `:frame` — consistent with the popstate/SSR sibling
            (`:rf.route/handle-url-change`) and the programmatic
            `:rf.route/navigate {:url ...}` path. Spec 009 requires `:frame`
            on `:rf.error/no-such-handler {:kind :route}` and
            `:rf.warning/no-not-found-route`."
    (rf/make-frame {:id :rf/default})
    (rf/make-frame {:id :route/owner})
    (rf/reg-route :route/home {} "/")
    ;; No :rf.route/not-found registered → the bare-miss path also emits
    ;; :rf.warning/no-not-found-route.
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))

    (testing "non-default frame: malformed URL → frame-tagged traces"
      (let [traces (atom [])]
        (rf/register-listener! :trace ::w3qgc-malformed
                               (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:rf.route/transitioned "/articles/%"] {:frame :route/owner})
        (rf/unregister-listener! :trace ::w3qgc-malformed)
        (is (some (fn [ev]
                    (and (= :rf.warning/malformed-url (:operation ev))
                         (= "/articles/%" (-> ev :tags :url))
                         (= :route/owner (-> ev :tags :frame))))
                  @traces)
            ":rf.warning/malformed-url carries :frame :route/owner on forward nav")
        (is (some (fn [ev]
                    (and (= :rf.error/no-such-handler (:operation ev))
                         (= :route (-> ev :tags :kind))
                         (= :route/owner (-> ev :tags :frame))))
                  @traces)
            ":rf.error/no-such-handler {:kind :route} carries :frame :route/owner")))

    (testing "non-default frame: bare miss with no 404 route → frame-tagged
              :rf.warning/no-not-found-route + :rf.error/no-such-handler"
      (let [traces (atom [])]
        (rf/register-listener! :trace ::w3qgc-nonotfound
                               (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:rf.route/transitioned "/no/such/route"] {:frame :route/owner})
        (rf/unregister-listener! :trace ::w3qgc-nonotfound)
        (is (some (fn [ev]
                    (and (= :rf.warning/no-not-found-route (:operation ev))
                         (= :route/owner (-> ev :tags :frame))))
                  @traces)
            ":rf.warning/no-not-found-route carries :frame :route/owner")
        (is (some (fn [ev]
                    (and (= :rf.error/no-such-handler (:operation ev))
                         (= :route/owner (-> ev :tags :frame))))
                  @traces)
            ":rf.error/no-such-handler carries :frame :route/owner")))

    (testing "default-frame forward nav STILL tags :rf/default (regression guard)"
      (let [traces (atom [])]
        (rf/register-listener! :trace ::w3qgc-default
                               (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:rf.route/transitioned "/articles/%"] {:frame :rf/default})
        (rf/unregister-listener! :trace ::w3qgc-default)
        (is (some (fn [ev]
                    (and (= :rf.error/no-such-handler (:operation ev))
                         (= :rf/default (-> ev :tags :frame))))
                  @traces)
            "default-frame dispatch tags :rf/default (not nil) — matches the
             popstate/SSR sibling and the programmatic path")))))

(deftest transitioned-well-formed-url-does-not-emit-malformed-trace
  (testing "the regular happy path emits NO :rf.warning/malformed-url"
    (rf/reg-route :route/home    {} "/")
    (rf/reg-route :route/search  {} "/search")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [traces (atom [])]
      (rf/register-listener! :trace ::no-malformed-trace
                             (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/transitioned "/search?q=clojure"])
      (rf/unregister-listener! :trace ::no-malformed-trace)
      (is (not-any? (fn [ev] (= :rf.warning/malformed-url (:operation ev)))
                    @traces)
          "well-formed URL → no malformed-URL trace"))))

;; ---- EP-0037 R1: :on-match is fire-and-forget, never drives readiness ----
;;
;; Per Spec 012 §Per-route data loading, `:on-match` never sets
;; `:rf.route/transition`. Route readiness is the resource-derived projection;
;; a route with no `:resources` is `:idle` throughout, including while its
;; `:on-match` events dispatch.

(deftest transitioned-on-match-does-not-drive-loading
  (testing ":rf.route/transitioned does NOT set :transition :loading for a
            route's :on-match — readiness is the resource projection (EP-0037 R1)"
    (rf/reg-event :prefs/loaded (fn [{:keys [db]} _] {:db (assoc db :prefs/loaded? true)}))
    (rf/reg-route :route/cart
                  {:on-match [[:prefs/loaded]]} "/cart")
    (rf/reg-route :route/home {} "/")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; The slice is written FIRST and then :on-match dispatches, so the
    ;; handler observes the new slice's :transition. Under R1 that is :idle
    ;; (no :resources → no blocking requirement), never :loading.
    (let [observed (atom :unset)]
      ;; EP-0001 (rf2-vzld77): the route slice is durable routing runtime-db
      ;; state — the :on-match observer reads :transition off :rf.db/runtime.
      (rf/reg-event :prefs/loaded
                       (fn [{:keys [db] rt :rf.db/runtime} _]
                         (reset! observed
                                 (get-in rt [:rf.runtime/routing :current :transition]))
                         {:db (assoc db :prefs/loaded? true)}))
      (rf/dispatch-sync [:rf.route/transitioned "/cart"])
      (is (= :idle @observed)
          ":on-match handler observed :transition :idle (never :loading)"))
    ;; Route without :on-match → :idle.
    (rf/dispatch-sync [:rf.route/transitioned "/"])
    (is (= :idle (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                         [:rf.runtime/routing :current :transition]))
        "route with no :on-match — transition stays :idle")))

;; ---- :on-match dispatches in order, fire-and-forget, readiness stays :idle ----

(deftest on-match-dispatches-fire-and-forget-idle
  (testing ":rf.route/navigate fires every :on-match event in order (fire-and-
            forget); the transition observed by the handlers and after the drain
            is :idle — :on-match never drives readiness (EP-0037 R1)"
    (let [observed (atom [])]
      ;; EP-0001 (rf2-vzld77): the route slice is durable routing runtime-db
      ;; state, so the :on-match observers read `:transition` off the
      ;; `:rf.db/runtime` coeffect.
      (rf/reg-event :load/a
                       (fn [{:keys [db] rt :rf.db/runtime} _]
                         (swap! observed conj [:a (get-in rt [:rf.runtime/routing :current :transition])])
                         {:db (assoc db :load/a-done? true)}))
      (rf/reg-event :load/b
                       (fn [{:keys [db] rt :rf.db/runtime} _]
                         (swap! observed conj [:b (get-in rt [:rf.runtime/routing :current :transition])])
                         {:db (assoc db :load/b-done? true)}))
      (rf/reg-route :route/dashboard
                    {:on-match [[:load/a] [:load/b]]} "/dashboard")
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/dashboard}])

      (is (= [[:a :idle] [:b :idle]] @observed)
          "both :on-match events fired in declaration order; both observed :idle")
      (let [db (rf/app-db-value :rf/default)
            rt (:rf.db/runtime (rf/frame-state-value :rf/default))]
        (is (true? (:load/a-done? db)) "first :on-match handler ran")
        (is (true? (:load/b-done? db)) "second :on-match handler ran")
        (is (= :idle (get-in rt [:rf.runtime/routing :current :transition]))
            ":transition remains :idle after the fire-and-forget drain")))))

;; ---- T7: :rf.route/fragment-changed (fragment-only) trace event payload ----

(deftest fragment-only-url-change-trace-payload
  (testing "fragment-only URL change emits :rf.route/fragment-changed
            (rf2-cj9fn; pre-rename `:rf.route/fragment-changed`) with
            :prev-fragment / :next-fragment under :tags (Spec 012 §Fragments)"
    (rf/reg-route :route/docs {} "/docs/:page")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Land on /docs/routing first (no fragment).
    (rf/dispatch-sync [:rf.route/transitioned "/docs/routing"])
    (let [traces (atom [])]
      (rf/register-listener! :trace ::frag-only (fn [ev] (swap! traces conj ev)))
      ;; Same path/query, different fragment → fragment-only nav.
      (rf/dispatch-sync [:rf.route/transitioned "/docs/routing#scroll-restoration"])
      ;; And again — prev→next this time.
      (rf/dispatch-sync [:rf.route/transitioned "/docs/routing#fragments"])
      (rf/unregister-listener! :trace ::frag-only)
      (let [frag-events (filter #(= :rf.route/fragment-changed (:operation %)) @traces)]
        (is (= 2 (count frag-events))
            "two fragment-only changes emit two :rf.route/fragment-changed traces")
        (let [first-ev  (first  frag-events)
              second-ev (second frag-events)]
          (is (= :route/docs (-> first-ev :tags :route-id))
              "first trace tags :route-id")
          (is (nil? (-> first-ev :tags :prev-fragment))
              "first transition: prev-fragment is nil (no fragment before)")
          (is (= "scroll-restoration" (-> first-ev :tags :next-fragment))
              "first transition: next-fragment is the new value")
          (is (= "scroll-restoration" (-> second-ev :tags :prev-fragment))
              "second transition: prev-fragment is the previous value")
          (is (= "fragments" (-> second-ev :tags :next-fragment))
              "second transition: next-fragment is the new value")
          ;; rf2-n0851k: the fragment-only trace must carry the frame
          ;; stamp under :tags :frame (Spec 012 §Multi-frame routing /
          ;; Spec 009). Without it the trace is dropped from epoch/Xray
          ;; capture (which buffers only frame-tagged events) and bypasses
          ;; the frame-level trace-disable gate. The forward-nav
          ;; (:rf.route/transitioned) path runs on the :rf/default frame.
          (is (= :rf/default (-> first-ev :tags :frame))
              "rf2-n0851k: forward-nav fragment-only trace is frame-attributed")
          (is (= :rf/default (-> second-ev :tags :frame))
              "rf2-n0851k: second fragment-only trace is frame-attributed too"))))))

;; ---- rf2-8oxj6: popstate honours the fragment-only rule ----------------
;;
;; Spec 012 §Fragments rules 3-4: a fragment-only URL change MUST NOT
;; allocate a new nav-token and MUST NOT re-fire :on-match. Back/Forward
;; (popstate) is wired through :rf.route/handle-url-change. Before the
;; fix the fragment-only short-circuit lived ONLY in
;; :rf.route/transitioned, so popstate to a same-page #fragment took the
;; full-rewrite path → fresh nav-token + :on-match re-fire (the exact
;; data-refetch thrash the rule forbids). The branch now lives in the
;; shared `url-change-fx`, so BOTH events honour it.

(deftest popstate-fragment-only-change-no-token-no-on-match-refire
  (testing "rf2-8oxj6: :rf.route/handle-url-change (popstate) to a URL
            differing ONLY in its #fragment does NOT allocate a new
            nav-token and does NOT re-fire :on-match (Spec 012 §Fragments
            rules 3-4)"
    (let [on-match-calls (atom 0)]
      (rf/reg-event :docs/load
                       (fn [{:keys [db]} _]
                         (swap! on-match-calls inc)
                         {:db db}))
      (rf/reg-route :route/docs {:on-match [[:docs/load]]} "/docs/:page")
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      ;; Land on /docs/routing via popstate (handle-url-change). This is
      ;; a full nav: allocates nav-1 and fires :on-match once.
      (rf/dispatch-sync [:rf.route/handle-url-change "/docs/routing"])
      (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
        (is (= :route/docs (:route-id slice)) "landed on /docs/routing")
        (is (= "nav-1" (:nav-token slice)) "first nav allocated nav-1")
        (is (= 1 @on-match-calls) ":on-match fired once on the full nav"))

      (let [traces (atom [])]
        (rf/register-listener! :trace ::popstate-frag (fn [ev] (swap! traces conj ev)))
        ;; Back/Forward to the SAME page, only the #fragment differs.
        ;; This is the popstate path (handle-url-change), the regression
        ;; site: must short-circuit, NOT full-rewrite.
        (rf/dispatch-sync [:rf.route/handle-url-change "/docs/routing#section-2"])
        (rf/unregister-listener! :trace ::popstate-frag)
        (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
          (is (= "section-2" (:fragment slice))
              "fragment-only change updates :fragment")
          (is (= "nav-1" (:nav-token slice))
              "rule 3: no NEW nav-token allocated on fragment-only popstate")
          (is (= 1 @on-match-calls)
              "rule 4: :on-match did NOT re-fire on fragment-only popstate"))
        ;; The fragment-only branch emits :rf.route/fragment-changed and
        ;; NEVER a :rf.route.nav-token/allocated on the same drain.
        (is (some #(= :rf.route/fragment-changed (:operation %)) @traces)
            "fragment-only popstate emits :rf.route/fragment-changed")
        (is (not-any? #(= :rf.route.nav-token/allocated (:operation %)) @traces)
            "fragment-only popstate emits NO :rf.route.nav-token/allocated")
        ;; rf2-n0851k: the popstate (handle-url-change) fragment-only trace
        ;; carries the frame stamp too — same contract as the forward-nav
        ;; path, so epoch/Xray capture and the frame trace-disable gate
        ;; cover popstate fragment-only changes.
        (is (= :rf/default
               (some->> @traces
                        (filter #(= :rf.route/fragment-changed (:operation %)))
                        first :tags :frame))
            "rf2-n0851k: popstate fragment-only trace is frame-attributed")))))

;; ============================================================================
;; rf2-ee38b.8 — Spec 012 §Per-route data loading rule 3:
;; identical-param re-navigation does NOT re-fire :on-match
;; ============================================================================
;;
;; Same-route-id navigations with IDENTICAL params/query (and identical
;; fragment) are a no-op re-navigation — the runtime compares the
;; prospective slice against the current one and skips the :on-match
;; re-fire + nav-token allocation. Re-firing loaders on a redundant
;; navigation (clicking the already-active link, popstate to the current
;; URL, a duplicate [:rf.route/navigate ...]) is the data-refetch thrash
;; the rule forbids. Sibling of the fragment-only short-circuit
;; (rf2-8oxj6); this is the stricter "nothing at all changed" case.

(deftest identical-url-renav-does-not-refire-on-match
  (testing "rf2-ee38b.8 / Spec 012 rule 3: a URL-driven re-navigation to
            the SAME url (same id/params/query/fragment) does NOT re-fire
            :on-match and does NOT allocate a new nav-token"
    (let [on-match-calls (atom 0)]
      (rf/reg-event :cart/load (fn [{:keys [db]} _] (swap! on-match-calls inc) {:db db}))
      (rf/reg-route :route/cart {:on-match [[:cart/load]]} "/cart")
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      ;; First nav: full rewrite, loader fires once, nav-1 allocated.
      (rf/dispatch-sync [:rf.route/transitioned "/cart"])
      (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
        (is (= "nav-1" (:nav-token slice)) "first nav allocates nav-1")
        (is (= 1 @on-match-calls) ":on-match fired once on the full nav"))
      (let [traces (atom [])]
        (rf/register-listener! :trace ::identical (fn [ev] (swap! traces conj ev)))
        ;; Re-navigate to the SAME url — rule-3 no-op.
        (rf/dispatch-sync [:rf.route/transitioned "/cart"])
        (rf/unregister-listener! :trace ::identical)
        (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
          (is (= "nav-1" (:nav-token slice))
              "rule 3: no NEW nav-token on identical re-navigation")
          (is (= 1 @on-match-calls)
              "rule 3: :on-match did NOT re-fire on identical re-navigation"))
        (is (not-any? #(= :rf.route.nav-token/allocated (:operation %)) @traces)
            "identical re-navigation emits NO :rf.route.nav-token/allocated")))))

(deftest changed-params-still-refires-on-match
  (testing "rf2-ee38b.8 / Spec 012 rule 3: CHANGED params DO re-fire
            :on-match (the no-op skip must not over-trigger)"
    (let [on-match-calls (atom 0)]
      (rf/reg-event :article/load (fn [{:keys [db]} _] (swap! on-match-calls inc) {:db db}))
      (rf/reg-route :route/article
                    {:on-match [[:article/load]]} "/articles/:id")
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      (is (= 2 @on-match-calls)
          "same route-id, different :params re-fires :on-match (A then B)")
      (is (= "nav-2" (:nav-token (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
          "a changed-params nav DOES allocate a fresh nav-token"))))

(deftest identical-programmatic-renav-does-not-refire-on-match
  (testing "rf2-ee38b.8 / Spec 012 rule 3: a duplicate
            [:rf.route/navigate {:to :route/cart}] does NOT re-fire :on-match
            and does NOT allocate a new nav-token"
    (let [on-match-calls (atom 0)
          pushed         (atom 0)]
      (rf/reg-event :cart/load (fn [{:keys [db]} _] (swap! on-match-calls inc) {:db db}))
      (rf/reg-route :route/cart {:on-match [[:cart/load]]} "/cart")
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] (swap! pushed inc)))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/cart}])
      (is (= 1 @on-match-calls) ":on-match fired once on the first navigate")
      (is (= "nav-1" (:nav-token (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current]))))
      ;; Duplicate navigate to the same target — rule-3 no-op.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/cart}])
      (is (= 1 @on-match-calls)
          "rule 3: :on-match did NOT re-fire on duplicate navigate")
      (is (= "nav-1" (:nav-token (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
          "rule 3: no new nav-token on duplicate navigate")
      (is (= 1 @pushed)
          "rule 3: no second :rf.nav/push-url on the no-op navigate"))))

;; ---- rf2-ee38b.8: programmatic navigate validation failure REJECTS ----
;;
;; Spec 012 §Param validation at the call site: the event-boundary path
;; [:rf.route/navigate ...] runs the route's :params/:query schema before
;; transitioning; on failure the navigation is REJECTED — the :rf/route
;; slice does not change, no URL is pushed — and the runtime emits
;; :rf.error/schema-validation-failure (:where :event). Pre-fix the
;; handler recovered the URL to "/" and PROCEEDED to write the slice +
;; push "/", mutating the slice into an invalid state on a caller bug.

(deftest navigate-validation-failure-rejects-and-leaves-slice-unchanged
  (testing "rf2-ee38b.8: [:rf.route/navigate] with params that fail the
            route's :params schema rejects — slice unchanged, no push,
            :rf.error/schema-validation-failure (:where :event) emitted"
    (let [restore (rts/with-stub-validator)
          pushed  (atom [])]
      (try
        (rf/reg-route :route/home {} "/")
        (rf/reg-route :route/article
                      {:params (fn [{:keys [id]}]
                                 (clojure.string/starts-with? (or id "") "a"))} "/articles/:id")
        (fx/reg-fx :rf.nav/push-url
                   {:platforms #{:server :client}}
                   (fn [_ url] (swap! pushed conj url)))
        ;; Land somewhere valid first so we can prove the slice is left
        ;; untouched by the rejected navigation.
        (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "aardvark"}}])
        (reset! pushed [])
        (let [before (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])
              traces (atom [])]
          (rf/register-listener! :trace ::reject (fn [ev] (swap! traces conj ev)))
          ;; Caller bug: :id "zoo" violates the :params schema.
          (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "zoo"}}])
          (rf/unregister-listener! :trace ::reject)
          (let [after (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
            (is (= before after)
                "rejected navigation leaves the :rf/route slice UNCHANGED")
            (is (= :route/article (:route-id after))
                "slice still on the previously-valid route (not desynced)")
            (is (empty? @pushed)
                "rejected navigation pushes NO URL (no recovery to \"/\")")
            (let [err (first (filter #(= :rf.error/schema-validation-failure
                                         (:operation %))
                                     @traces))]
              (is (some? err)
                  ":rf.error/schema-validation-failure emitted on reject")
              (is (= :event (-> err :tags :where))
                  "error tags :where :event (event-boundary path)"))))
        (finally (restore))))))

;; ---- T8: :fragment in slice after URL-driven nav -----------------------

(deftest fragment-in-slice-after-url-driven-nav
  (testing ":fragment in URL flows into the slice on every URL-driven nav
            (Spec 012 §The :rf/route slice — :fragment row)"
    (rf/reg-route :route/docs {} "/docs/:page")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/docs/routing#scroll-restoration"])
    (is (= "scroll-restoration"
           (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current :fragment]))
        ":fragment from URL is written to slice")))

;; ============================================================================
;; rf2-3bv8o — external-url? fails CLOSED on the JVM / no-browser-origin
;; path: ambiguous or absolute URLs are classed external (no in-app push),
;; closing the open-redirect bypass surface (leading whitespace before a
;; scheme, backslash authorities, embedded control chars). Consistent with
;; the routing fail-closed posture (rf2-6t1xb).
;; ============================================================================

(deftest url-requested-fails-closed-on-ambiguous-urls-jvm
  (testing ":rf.route/url-requested on the JVM (no browser origin) classes
            ambiguous / bypass-shaped URLs as EXTERNAL — no :rf.nav/push-url,
            no slice rewrite — and only PROVABLY same-origin rooted paths
            push through. rf2-3bv8o."
    (rf/reg-route :route/home {} "/")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/transitioned "/"])
      (testing "bypass-shaped / absolute / ambiguous URLs do NOT push"
        (doseq [hostile ["https://evil.invalid/cart"   ;; scheme
                         "//evil.invalid/cart"          ;; protocol-relative
                         "/\\evil.invalid"              ;; backslash authority
                         " /cart"                       ;; leading-space scheme-anchor bypass
                         "\thttps://evil.invalid"       ;; embedded tab (browser-stripped)
                         "javascript:alert(1)"          ;; non-http scheme
                         "mailto:a@b.c"
                         "cart"                          ;; bare relative segment (not rooted)
                         ""]]
          (reset! pushed [])
          (rf/dispatch-sync [:rf.route/url-requested {:url hostile}])
          (is (empty? @pushed)
              (str "fail-closed: ambiguous/absolute URL " (pr-str hostile)
                   " classed external, not pushed"))
          (is (= :route/home (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                     [:rf.runtime/routing :current :route-id]))
              (str "fail-closed: " (pr-str hostile)
                   " did not rewrite the active route"))))
      (testing "provably same-origin rooted paths DO push through"
        (doseq [safe ["/cart" "/a/b/c" "/cart?q=1" "/cart#frag" "?q=1" "#frag"]]
          (reset! pushed [])
          (rf/dispatch-sync [:rf.route/url-requested {:url safe}])
          (is (= [safe] @pushed)
              (str "safe same-origin reference " (pr-str safe)
                   " pushes through verbatim")))))))

;; ============================================================================
;; rf2-cylse.4 — :rf.route/navigate {:url ...} gates through the SAME
;; fail-closed open-redirect classifier as :rf.route/url-requested. Before this
;; fix the programmatic {:url ...} sink pushed the URL VERBATIM with no
;; external/safe-url gate — every rf2-3bv8o bypass vector escaped. This is
;; the rf2-3bv8o matrix re-run through the navigate {:url} sink.
;; ============================================================================

(deftest navigate-url-target-fails-closed-on-ambiguous-urls-jvm
  (testing ":rf.route/navigate {:url <hostile>} on the JVM (no browser
            origin) classes ambiguous / bypass-shaped URLs as EXTERNAL —
            no :rf.nav/push-url, no slice rewrite — IDENTICALLY to the
            :rf.route/url-requested link-click path. rf2-cylse.4."
    (rf/reg-route :route/home {} "/")
    (rf/reg-route :route/cart {} "/cart")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      ;; Land on a known route first so we can prove the slice does not move.
      (rf/dispatch-sync [:rf.route/transitioned "/cart"])
      (is (= :route/cart (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                 [:rf.runtime/routing :current :route-id]))
          "preconditon: active route is :route/cart")
      (testing "every rf2-3bv8o bypass vector fails closed through navigate {:url}"
        (doseq [hostile ["https://evil.invalid/phish"   ;; absolute cross-origin
                         "//evil.invalid/phish"          ;; protocol-relative
                         "/\\evil.invalid"               ;; backslash authority
                         " /cart"                        ;; leading-space scheme-anchor bypass
                         "\thttps://evil.invalid"        ;; embedded tab (browser-stripped)
                         "javascript:alert(1)"           ;; non-http scheme
                         "https://good.com@evil.com/x"   ;; userinfo confusion
                         "mailto:a@b.c"
                         "cart"]]                          ;; bare relative segment (not rooted)
          (reset! pushed [])
          (rf/dispatch-sync [:rf.route/navigate {:url hostile}])
          (is (empty? @pushed)
              (str "fail-closed: navigate {:url " (pr-str hostile)
                   "} classed external, not pushed"))
          (is (= :route/cart (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                     [:rf.runtime/routing :current :route-id]))
              (str "fail-closed: navigate {:url " (pr-str hostile)
                   "} did not rewrite the active route"))))
      (testing "provably same-origin rooted paths DO push through navigate {:url}"
        ;; Each safe nav is preceded by landing on a DIFFERENT route so the
        ;; rule-3 identical-nav no-op never masks the push under test. `/`
        ;; resolves to :route/home, the rest to :route/cart.
        (doseq [[safe land-elsewhere] [["/cart"      "/"]
                                       ["/"          "/cart"]
                                       ["/cart?q=1"  "/"]
                                       ["/cart#frag" "/"]]]
          (rf/dispatch-sync [:rf.route/transitioned land-elsewhere])
          (reset! pushed [])
          (rf/dispatch-sync [:rf.route/navigate {:url safe}])
          (is (seq @pushed)
              (str "safe same-origin reference " (pr-str safe)
                   " pushes through navigate {:url}")))))))

;; ============================================================================
;; rf2-zmcq6 (CODE half) — :rf.route/navigate {:fragment ""} must agree
;; with URL-driven nav: an empty-string fragment is normalized to nil at
;; the navigate boundary, so the pushed URL (no trailing #) and the route
;; slice (:fragment nil) agree, exactly as a URL-driven nav to the same URL.
;; ============================================================================

(deftest navigate-empty-string-fragment-normalized
  (testing "rf2-zmcq6: programmatic navigation with {:fragment \"\"} pushes
            the fragment-less URL AND writes :fragment nil to the slice —
            agreeing with URL-driven nav (was: slice carried :fragment \"\"
            while the URL had no #, a slice/URL divergence)"
    (rf/reg-route :route/docs {} "/docs/:page")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))

      ;; URL-driven baseline: navigate to /docs/routing from the URL.
      (rf/dispatch-sync [:rf.route/transitioned "/docs/routing"])
      (let [url-driven-frag (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                    [:rf.runtime/routing :current :fragment])]
        (is (nil? url-driven-frag) "URL-driven nav to /docs/routing yields :fragment nil")

        ;; Now reach the SAME URL programmatically with {:fragment ""}.
        (reset! pushed [])
        (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "guide"} :fragment ""}])
        (let [slice-frag (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                 [:rf.runtime/routing :current :fragment])]
          (is (= ["/docs/guide"] @pushed)
              "the pushed URL carries NO trailing # for an empty-string fragment")
          (is (nil? slice-frag)
              "the slice :fragment is nil (normalized from \"\"), matching URL-driven nav")))

      (testing "a non-empty programmatic fragment still works (regression guard)"
        (reset! pushed [])
        (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "api"} :fragment "section"}])
        (is (= ["/docs/api#section"] @pushed) "non-empty fragment appends #section")
        (is (= "section" (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                 [:rf.runtime/routing :current :fragment]))
            "the slice carries the non-empty fragment")))))

;; ---- The flat request-map grammar (rf2-vwwvp) -----------------------------
;;
;; Spec 012 §Navigation is an event: `:rf.route/navigate` carries ONE flat
;; request map — no positional arity, no reserved-target form. Address keys
;; (:to / :url / :params / :query / :fragment), policy keys (:replace? /
;; :scroll / :bypass-guards?), and the in-place edit key :query-merge. The
;; always-on structural gate is exercised in the dedicated gate suite below;
;; here we pin that the address forms navigate cleanly.

(deftest navigate-accepts-the-request-map-forms
  (testing "rf2-vwwvp: the request-map forms all navigate cleanly —
            {:to}, {:to :params}, {:to :params :replace?}"
    (rf/reg-route :route/home    {} "/")
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (let [pushed   (atom [])
          replaced (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (fx/reg-fx :rf.nav/replace-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! replaced conj url)))
      ;; [target] — no path-params, no opts.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
      (is (= :route/home (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                            [:rf.runtime/routing :current])))
          "[target] arity navigates")
      ;; [target params] — params 2nd, opts absent.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "intro"}}])
      (is (= {:id "intro"} (:params (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                            [:rf.runtime/routing :current])))
          "[target params] arity navigates with path-params")
      ;; [target params opts] — opts in the THIRD slot (:replace? true →
      ;; :rf.nav/replace-url, proving the 3rd-slot opts are honoured).
      (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "two"} :replace? true}])
      (is (= "/articles/two" (last @replaced))
          "[target params opts] arity navigates; :replace? opt honoured in the 3rd slot")
      (is (= {:id "two"} (:params (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                          [:rf.runtime/routing :current])))
          "the 3-arity path-params landed in the slice, distinct from opts"))))

(deftest navigate-does-not-false-flag-a-route-with-an-opts-named-path-param
  (testing "rf2-1os1c: a route that legitimately captures a segment named
            :fragment is NOT false-flagged — the key is a declared
            path-param, not a misplaced opt"
    (rf/reg-route :route/anchor {} "/anchor/:fragment")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/anchor :params {:fragment "intro"}}])
      (is (= :route/anchor (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                              [:rf.runtime/routing :current])))
          "a declared :fragment path-param navigates normally (no false reject)")
      (is (= "/anchor/intro" (last @pushed))
          "the path-param :fragment populates the URL segment"))))

;; ---- rf2-dbmj6x: lifecycle / nav-token traces carry :frame ----------------
;;
;; The finding: `commit-navigation` emitted `:rf.route.nav-token/allocated`,
;; `:rf.route/activated`, and `:rf.route/deactivated` with only `{:route-id
;; …}` — no `:frame`. Because `re-frame.epoch.capture/capture-event!` admits
;; ONLY frame-tagged traces (capture.cljc §168-221) and the frame-level
;; trace-disable gate (`re-frame.trace/emit!` §397-398) keys off `:tags
;; :frame`, those frame-known traces silently dropped from Xray / epoch
;; history AND leaked past a `:rf.trace/frame-no-emit?` tool frame in a
;; multi-frame app. The carried frame stamp (validated at the nav handler
;; top via `frame/require-frame-stamp!`) is now threaded into
;; `commit-navigation` and stamped on all three. These tests use a
;; NON-DEFAULT frame so a regression that re-hardcodes `:rf/default` (or
;; drops the tag) fails here.

(deftest commit-traces-carry-frame-programmatic-rf2-dbmj6x
  (testing "rf2-dbmj6x: programmatic `:rf.route/navigate` stamps :frame on
            :rf.route.nav-token/allocated, :rf.route/activated, and
            :rf.route/deactivated for a non-default frame"
    (rf/make-frame {:id :rf/default})
    (rf/make-frame {:id :route/owner})
    (rf/reg-route :route/from {} "/from")
    (rf/reg-route :route/to   {} "/to")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; First nav (no prior route): only nav-token-allocated + activated fire.
    (let [traces (atom [])]
      (rf/register-listener! :trace ::dbmj6x-nav1 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/from}] {:frame :route/owner})
      (rf/unregister-listener! :trace ::dbmj6x-nav1)
      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/allocated (:operation ev))
                       (= :route/from (-> ev :tags :route-id))
                       (= :route/owner (-> ev :tags :frame))))
                @traces)
          ":rf.route.nav-token/allocated carries :frame :route/owner (pre-fix: absent)")
      (is (some (fn [ev]
                  (and (= :rf.route/activated (:operation ev))
                       (= :route/from (-> ev :tags :route-id))
                       (= :route/owner (-> ev :tags :frame))))
                @traces)
          ":rf.route/activated carries :frame :route/owner (pre-fix: absent)"))
    ;; Cross-route nav: deactivated + activated both carry the frame.
    (let [traces (atom [])]
      (rf/register-listener! :trace ::dbmj6x-nav2 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/to}] {:frame :route/owner})
      (rf/unregister-listener! :trace ::dbmj6x-nav2)
      (is (some (fn [ev]
                  (and (= :rf.route/deactivated (:operation ev))
                       (= :route/from (-> ev :tags :route-id))
                       (= :route/owner (-> ev :tags :frame))))
                @traces)
          ":rf.route/deactivated carries :frame :route/owner (pre-fix: absent)")
      (is (some (fn [ev]
                  (and (= :rf.route/activated (:operation ev))
                       (= :route/to (-> ev :tags :route-id))
                       (= :route/owner (-> ev :tags :frame))))
                @traces)
          ":rf.route/activated (cross-route) carries :frame :route/owner"))))

(deftest commit-traces-carry-frame-url-driven-rf2-dbmj6x
  (testing "rf2-dbmj6x: URL-driven `:rf.route/transitioned` /
            `:rf.route/handle-url-change` stamp :frame on the lifecycle /
            nav-token traces for a non-default frame"
    (rf/make-frame {:id :rf/default})
    (rf/make-frame {:id :route/owner})
    (rf/reg-route :route/from {} "/from")
    (rf/reg-route :route/to   {} "/to")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; :rf.route/transitioned (forward nav).
    (let [traces (atom [])]
      (rf/register-listener! :trace ::dbmj6x-url1 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/transitioned "/from"] {:frame :route/owner})
      (rf/unregister-listener! :trace ::dbmj6x-url1)
      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/allocated (:operation ev))
                       (= :route/owner (-> ev :tags :frame))))
                @traces)
          "transitioned: :rf.route.nav-token/allocated carries :frame :route/owner")
      (is (some (fn [ev]
                  (and (= :rf.route/activated (:operation ev))
                       (= :route/owner (-> ev :tags :frame))))
                @traces)
          "transitioned: :rf.route/activated carries :frame :route/owner"))
    ;; :rf.route/handle-url-change (popstate / SSR) cross-route → deactivated.
    (let [traces (atom [])]
      (rf/register-listener! :trace ::dbmj6x-url2 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/handle-url-change "/to"] {:frame :route/owner})
      (rf/unregister-listener! :trace ::dbmj6x-url2)
      (is (some (fn [ev]
                  (and (= :rf.route/deactivated (:operation ev))
                       (= :route/from (-> ev :tags :route-id))
                       (= :route/owner (-> ev :tags :frame))))
                @traces)
          "handle-url-change: :rf.route/deactivated carries :frame :route/owner")
      (is (some (fn [ev]
                  (and (= :rf.route/activated (:operation ev))
                       (= :route/to (-> ev :tags :route-id))
                       (= :route/owner (-> ev :tags :frame))))
                @traces)
          "handle-url-change: :rf.route/activated carries :frame :route/owner"))))

;; ---- rf2-dbmj6x regression: trace-disabled frame suppresses commit traces -
;;
;; The frame-level trace-disable gate (`re-frame.trace/emit!` §397-398) keys
;; off `:tags :frame`: an event tagged with a `:rf.trace/frame-no-emit?`
;; frame is suppressed before any envelope is built. PRE-FIX the lifecycle /
;; nav-token traces carried no `:frame`, so a navigation driven from a tool
;; frame (Xray's `:rf/xray`) LEAKED those events into the very ring the
;; inspector reads — the bug the gate exists to prevent. This proves the
;; events now carry `:frame` (and therefore also reach epoch capture, the
;; sibling frame-tag admission gate): suppression from a no-emit frame and
;; epoch-capture admission are the SAME `:tags :frame` condition.

(deftest commit-traces-suppressed-from-trace-disabled-frame-rf2-dbmj6x
  (testing "rf2-dbmj6x: lifecycle / nav-token traces emitted from a
            `:rf.trace/frame-no-emit?` frame are suppressed (proving they
            now carry :frame); the same dispatch from an emitting frame
            still produces them"
    (rf/make-frame {:id :rf/default})
    ;; A tool / inspector frame whose own reactivity must not flood the ring.
    (rf/make-frame {:id :rf/tool :rf.trace/frame-no-emit? true})
    (rf/reg-route :route/from {} "/from")
    (rf/reg-route :route/to   {} "/to")
    (fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Seed a prior route in the tool frame so a cross-route nav would emit
    ;; deactivated + activated (both must be suppressed).
    (rf/dispatch-sync [:rf.route/navigate {:to :route/from}] {:frame :rf/tool})
    (let [tool-traces (atom [])]
      (rf/register-listener! :trace ::dbmj6x-tool (fn [ev] (swap! tool-traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/to}] {:frame :rf/tool})
      (rf/unregister-listener! :trace ::dbmj6x-tool)
      (is (empty? (filter #(#{:rf.route.nav-token/allocated
                              :rf.route/activated
                              :rf.route/deactivated}
                            (:operation %))
                          @tool-traces))
          "no lifecycle / nav-token trace leaks from a :rf.trace/frame-no-emit?
           frame (pre-fix the unframed emits leaked past the gate)"))
    ;; Control: the identical events DO fire from an ordinary emitting frame.
    (rf/dispatch-sync [:rf.route/navigate {:to :route/from}] {:frame :rf/default})
    (let [app-traces (atom [])]
      (rf/register-listener! :trace ::dbmj6x-app (fn [ev] (swap! app-traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/to}] {:frame :rf/default})
      (rf/unregister-listener! :trace ::dbmj6x-app)
      (is (some #(= :rf.route.nav-token/allocated (:operation %)) @app-traces)
          "control: :rf.route.nav-token/allocated DOES fire from an emitting frame")
      (is (some #(= :rf.route/activated (:operation %)) @app-traces)
          "control: :rf.route/activated DOES fire from an emitting frame")
      (is (some #(= :rf.route/deactivated (:operation %)) @app-traces)
          "control: :rf.route/deactivated DOES fire from an emitting frame"))))

;; ---- rf2-vwwvp — Spec 012 §In-place navigation + §The :query-merge key -----

(defn- nav-slice
  "The current route slice for the default frame."
  []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current]))

(deftest routing-in-place-target-stays-on-current-route
  (testing "an in-place request patches the CURRENT route's query, holding id + path-params"
    ;; Per Spec 012 §In-place navigation. An in-place request (no :to / :url)
    ;; holds the route (path) fixed and changes only the query; a wholesale
    ;; :query replaces the current query.
    (rf/reg-route :route/article
                  {:params [:map [:id :string]]
                   :query  [:map [:tab {:optional true} :string]]}
                  "/articles/:id")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      ;; Land on /articles/intro?tab=notes.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/intro?tab=notes"])
      (is (= :route/article (:route-id (nav-slice))))
      (is (= {:id "intro"} (:params (nav-slice))))
      ;; In-place nav changing only the query.
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/navigate {:query {:tab "history"}}])
      (is (= :route/article (:route-id (nav-slice)))
          "in-place keeps the current route-id")
      (is (= {:id "intro"} (:params (nav-slice)))
          "in-place keeps the current path-params (path held fixed)")
      (is (= {:tab "history"} (:query (nav-slice)))
          "the query changed to the supplied :query")
      (is (= ["/articles/intro?tab=history"] @pushed)
          "the pushed URL keeps the path and carries the new query"))))

(deftest routing-query-merge-folds-into-current-query
  (testing ":query-merge folds deltas into the CURRENT query, keeping the rest"
    (rf/reg-route :route/search
                  {:query [:map [:q {:optional true} :string]
                                [:page {:optional true} :int]
                                [:sort {:optional true} :string]]}
                  "/search")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      ;; Land on /search?q=clojure&page=1&sort=recent.
      (rf/dispatch-sync [:rf.route/transitioned "/search?q=clojure&page=1&sort=recent"])
      (is (= {:q "clojure" :page 1 :sort "recent"} (:query (nav-slice))))
      ;; Merge :page 2 — q + sort ride along.
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/navigate {:query-merge {:page 2}}])
      (is (= {:q "clojure" :page 2 :sort "recent"} (:query (nav-slice)))
          ":query-merge changes :page, keeps :q + :sort from the current query")
      (let [url (last @pushed)]
        (is (re-find #"page=2" url) "the new :page is in the URL")
        (is (re-find #"q=clojure" url) "the untouched :q rides along")
        (is (re-find #"sort=recent" url) "the untouched :sort rides along")))))

(deftest routing-query-merge-nil-removes-a-key
  (testing "a nil :query-merge value REMOVES a key from the slice AND the URL"
    ;; Per Spec 012 §The :query-merge opt: a nil value drops a key, matching
    ;; route-url's query nil-elision. The slice must be clean too (no {:sort
    ;; nil}) so a self-nav to the same query is a genuine no-op.
    (rf/reg-route :route/search
                  {:query [:map [:q {:optional true} :string]
                                [:sort {:optional true} :string]]}
                  "/search")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/transitioned "/search?q=clojure&sort=recent"])
      (reset! pushed [])
      ;; Remove :sort via a nil value.
      (rf/dispatch-sync [:rf.route/navigate {:query-merge {:sort nil}}])
      (is (= {:q "clojure"} (:query (nav-slice)))
          ":sort is removed from the slice (no {:sort nil} residue)")
      (is (not (contains? (:query (nav-slice)) :sort))
          ":sort key is absent, not nil-valued")
      (let [url (last @pushed)]
        (is (re-find #"q=clojure" url) ":q survives")
        (is (not (re-find #"sort" url)) ":sort is gone from the URL")))))

(deftest routing-query-merge-preserves-falsy-values
  (testing "a present-but-falsy :query-merge value survives (only nil removes)"
    ;; false / 0 / "" are legitimate values, same as route-url.
    (rf/reg-route :route/search
                  {:query [:map [:page {:optional true} :int]
                                [:flag {:optional true} :string]]}
                  "/search")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/transitioned "/search?page=1"])
      (reset! pushed [])
      ;; page 0 is falsy-but-legitimate; flag "" is an explicit empty value.
      (rf/dispatch-sync [:rf.route/navigate {:query-merge {:page 0 :flag ""}}])
      (is (= 0 (:page (:query (nav-slice))))
          "page=0 (falsy) survives the merge")
      (is (= "" (:flag (:query (nav-slice))))
          "flag=\"\" (empty string) survives the merge")
      (is (re-find #"page=0" (last @pushed))
          "page=0 is emitted in the URL"))))

(deftest routing-query-merge-caller-delta-wins
  (testing "an explicit :query-merge delta overrides the current query value"
    (rf/reg-route :route/search
                  {:query [:map [:page {:optional true} :int]]}
                  "/search")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/transitioned "/search?page=5"])
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/navigate {:query-merge {:page 6}}])
      (is (= 6 (:page (:query (nav-slice))))
          "the :query-merge delta wins over the current :page=5"))))

(deftest routing-query-merge-with-destination-rejects
  (testing ":query-merge requires an in-place request — a destination target is rejected"
    ;; Per Spec 012 §Validity rules rule 4: :query-merge requires an IN-PLACE
    ;; request (no :to / :url). Cross-route query carry is DELETED — carrying
    ;; state into another route's query is that route's :query-retain policy,
    ;; not the caller's imperative :query-merge. A :query-merge beside :to is a
    ;; structural error: the gate rejects it with :rf.error/navigate-bad-request
    ;; (:reason :query-merge-in-place-only), slice unchanged, no push.
    (rf/reg-route :route/a
                  {:query [:map [:theme {:optional true} :string]]} "/a")
    (rf/reg-route :route/b
                  {:query [:map [:theme {:optional true} :string]
                                [:page {:optional true} :int]]} "/b")
    (let [pushed (atom [])
          errors (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/transitioned "/a?theme=dark"])
      (reset! pushed [])
      (rf/register-listener! :trace ::qm-reject
                             (fn [ev] (when (= :error (:op-type ev))
                                        (swap! errors conj ev))))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/b :query-merge {:page 3}}])
      (rf/unregister-listener! :trace ::qm-reject)
      (is (= :route/a (:route-id (nav-slice))) "the destination nav is rejected; slice unchanged")
      (is (empty? @pushed) "no URL is pushed")
      (let [err (first (filter #(= :rf.error/navigate-bad-request (:operation %)) @errors))]
        (is (some? err) ":rf.error/navigate-bad-request emitted")
        (is (= :query-merge-in-place-only (-> err :tags :reason))
            ":reason names the query-merge-on-destination violation")))))

(deftest routing-in-place-before-first-nav-rejects
  (testing "an in-place request before any navigation fails closed (no current route)"
    ;; Per Spec 012 §Validity rules rule 6: an in-place request dispatched
    ;; before any current route exists rejects loud — there is nothing to
    ;; patch. The structural gate emits :rf.error/navigate-bad-request
    ;; (:reason :no-current-route), slice unchanged, no push.
    (let [pushed  (atom [])
          errors  (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/register-listener! :trace ::inplace-reject
                             (fn [ev] (when (= :error (:op-type ev))
                                        (swap! errors conj ev))))
      (rf/dispatch-sync [:rf.route/navigate {:query-merge {:page 1}}])
      (rf/unregister-listener! :trace ::inplace-reject)
      (is (empty? @pushed)
          "no URL is pushed for an in-place nav with no current route")
      (is (nil? (:route-id (nav-slice)))
          "the route slice stays empty (unchanged)")
      (let [err (first (filter #(= :rf.error/navigate-bad-request (:operation %)) @errors))]
        (is (some? err) ":rf.error/navigate-bad-request emitted")
        (is (= :no-current-route (-> err :tags :reason))
            ":reason names the no-current-route violation")))))

(deftest routing-in-place-query-merge-no-op-when-unchanged
  (testing "in-place :query-merge to the SAME query is a rule-3 no-op"
    ;; Per Spec 012 §Per-route data loading rule 3: a navigation whose
    ;; resolved id/params/query/fragment match the current slice exactly is a
    ;; no-op — no fresh nav-token, no push. Eliding nil-valued merge keys from
    ;; the SLICE (not just the URL) is what makes this hold.
    (rf/reg-route :route/search
                  {:query [:map [:page {:optional true} :int]]} "/search")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/transitioned "/search?page=2"])
      (let [token-before (:nav-token (nav-slice))]
        (reset! pushed [])
        ;; Merge :page 2 while already on page 2 → no-op.
        (rf/dispatch-sync [:rf.route/navigate {:query-merge {:page 2}}])
        (is (empty? @pushed)
            "a self-nav to the identical query pushes no URL (rule-3 no-op)")
        (is (= token-before (:nav-token (nav-slice)))
            "the nav-token is unchanged (no fresh epoch)")))))

;; ============================================================================
;; rf2-k4exp1 — programmatic fragment-only navigate short-circuit
;; ============================================================================
;;
;; Spec 012 §Fragments rules 3-4 / §Programmatic navigation with fragments.
;; A `:rf.route/navigate` whose resolved target differs from the current slice
;; ONLY in its `#fragment` (same route-id/params/query) must take the SAME
;; short-circuit the URL-driven doors take: update `:fragment`, emit ONE
;; `:rf.route/fragment-changed`, drive history+scroll via effects, and do NOT
;; call `commit-navigation` — no fresh nav-token, no `:on-match` re-fire, no
;; resource re-plan. Before the fix the programmatic door took the full commit
;; path (fresh token, every loader re-fired, stale-suppressing in-flight work
;; for the route you were already on).

(defn- reg-nav-fxs-capturing!
  "Register the four routing history/scroll fxs (`:platforms #{:server
  :client}` so they route on the JVM) with capturing handlers. Returns a map
  of atoms: `:push` / `:replace` (URL vectors), `:scroll` (args vectors), and
  `:order` (an ordered `[fx-id args]` log across all four)."
  []
  (let [pushed   (atom [])
        replaced (atom [])
        scrolled (atom [])
        order    (atom [])]
    (fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
               (fn [_ url]  (swap! pushed conj url)   (swap! order conj [:rf.nav/push-url url])))
    (fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}}
               (fn [_ url]  (swap! replaced conj url) (swap! order conj [:rf.nav/replace-url url])))
    (fx/reg-fx :rf.nav/scroll {:platforms #{:server :client}}
               (fn [_ args] (swap! scrolled conj args) (swap! order conj [:rf.nav/scroll args])))
    (fx/reg-fx :rf.nav/capture-scroll {:platforms #{:server :client}}
               (fn [_ args] (swap! order conj [:rf.nav/capture-scroll args])))
    {:push pushed :replace replaced :scroll scrolled :order order}))

(deftest navigate-fragment-only-short-circuits-no-refire-no-token-rf2-k4exp1
  (testing "rf2-k4exp1: a programmatic `:rf.route/navigate` changing ONLY the
            fragment updates `:fragment`, emits one `:rf.route/fragment-changed`,
            preserves the slice byte-for-byte except `:fragment`, does NOT
            re-fire `:on-match`, and does NOT allocate a new `:nav-token`"
    (let [on-match-calls (atom 0)]
      (rf/reg-event :docs/load (fn [{:keys [db]} _] (swap! on-match-calls inc) {:db db}))
      (rf/reg-route :route/docs {:on-match [[:docs/load]]} "/docs/:page")
      (let [fxs (reg-nav-fxs-capturing!)]
        ;; Full nav → loader fires once, nav-1, pushes /docs/routing#a.
        (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}])
        (is (= 1 @on-match-calls) ":on-match fired once on the full nav")
        (let [slice-before (nav-slice)]
          (is (= "nav-1"  (:nav-token slice-before)) "first nav allocated nav-1")
          (is (= "a"      (:fragment slice-before)) "fragment is #a after the full nav")
          (is (= ["/docs/routing#a"] @(:push fxs)) "full nav pushed /docs/routing#a")

          (let [traces (atom [])]
            (rf/register-listener! :trace ::k4exp1-frag (fn [ev] (swap! traces conj ev)))
            ;; Fragment-only nav: same route/params/query, #a → #b.
            (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "b"}])
            (rf/unregister-listener! :trace ::k4exp1-frag)

            (let [slice-after (nav-slice)]
              ;; TEETH 1 — no re-fire.
              (is (= 1 @on-match-calls)
                  "rule 4: :on-match did NOT re-fire on the fragment-only nav")
              ;; TEETH 2 — token unchanged (no new allocation).
              (is (= "nav-1" (:nav-token slice-after))
                  "rule 3: no NEW nav-token on the fragment-only nav (still nav-1)")
              ;; TEETH 3 — only :fragment changed; everything else byte-for-byte.
              (is (= "b" (:fragment slice-after)) ":fragment updated to #b")
              (is (= (assoc slice-before :fragment "b") slice-after)
                  "the slice is byte-for-byte identical except :fragment — :transition
                   / :error / :nav-token / :route-id / :params / :query preserved")
              ;; TEETH 4 — one fragment-changed, no allocation trace.
              (let [frag (filter #(= :rf.route/fragment-changed (:operation %)) @traces)
                    tags (-> frag first :tags)]
                (is (= 1 (count frag))
                    "exactly one :rf.route/fragment-changed emitted")
                (is (= :route/docs (:route-id tags))      "fragment-changed carries :route-id")
                (is (= "a" (:prev-fragment tags))         "fragment-changed carries :prev-fragment")
                (is (= "b" (:next-fragment tags))         "fragment-changed carries :next-fragment")
                (is (= :rf/default (:frame tags))         "fragment-changed carries :frame (rf2-n0851k parity)"))
              (is (not-any? #(= :rf.route.nav-token/allocated (:operation %)) @traces)
                  "NO :rf.route.nav-token/allocated on the fragment-only nav")
              (is (not-any? #(= :rf.route/activated (:operation %)) @traces)
                  "NO :rf.route/activated on the fragment-only nav (route stays active)")
              ;; History: a push for the new fragment URL.
              (is (= ["/docs/routing#a" "/docs/routing#b"] @(:push fxs))
                  "the fragment-only nav pushed /docs/routing#b via :rf.nav/push-url")
              (is (empty? @(:replace fxs))
                  "no :rf.nav/replace-url on the default (push) door"))))))))

(deftest navigate-fragment-only-effect-ordering-rf2-k4exp1
  (testing "rf2-k4exp1: the fragment-only nav's effects are ordered
            capture-scroll → push-url → scroll (state-first: the :fragment
            write commits before the ordered history/scroll effects)"
    (rf/reg-route :route/docs {} "/docs/:page")
    (let [fxs (reg-nav-fxs-capturing!)]
      (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}])
      (reset! (:order fxs) [])
      (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "b"}])
      (let [ids (mapv first @(:order fxs))]
        (is (= [:rf.nav/capture-scroll :rf.nav/push-url :rf.nav/scroll] ids)
            "ordered: capture the leaving scroll, push the new URL, then scroll")
        ;; The scroll fx targets the NEW fragment with the default :top strategy.
        (let [scroll-args (-> @(:scroll fxs) last)]
          (is (= :top (:strategy scroll-args)) "default forward strategy is :top")
          (is (= "b"  (:fragment scroll-args)) "scroll targets the new #b fragment"))
        ;; capture-scroll keyed on the LEAVING url (/docs/routing#a).
        (is (= {:url "/docs/routing#a"}
               (-> (filter #(= :rf.nav/capture-scroll (first %)) @(:order fxs)) first second))
            "capture-scroll saves the position of the route being left (#a)")))))

(deftest navigate-fragment-only-replace-uses-replace-url-rf2-k4exp1
  (testing "rf2-k4exp1: `{:replace? true}` routes the fragment-only nav through
            :rf.nav/replace-url (NOT push) — still no re-fire, still nav-1"
    (let [on-match-calls (atom 0)]
      (rf/reg-event :docs/load (fn [{:keys [db]} _] (swap! on-match-calls inc) {:db db}))
      (rf/reg-route :route/docs {:on-match [[:docs/load]]} "/docs/:page")
      (let [fxs (reg-nav-fxs-capturing!)]
        (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}])
        (reset! (:push fxs) [])
        (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "b" :replace? true}])
        (is (= "b"    (:fragment (nav-slice))) "fragment updated to #b")
        (is (= "nav-1" (:nav-token (nav-slice))) "no new nav-token")
        (is (= 1 @on-match-calls) ":on-match did NOT re-fire")
        (is (= ["/docs/routing#b"] @(:replace fxs))
            "{:replace? true} routed :rf.nav/replace-url with the new fragment URL")
        (is (empty? @(:push fxs))
            "no :rf.nav/push-url on the replace door")))))

(deftest navigate-fragment-only-scroll-false-suppresses-scroll-rf2-k4exp1
  (testing "rf2-k4exp1: `{:scroll false}` on a fragment-only nav suppresses the
            :rf.nav/scroll effect but still updates :fragment and pushes the URL"
    (rf/reg-route :route/docs {} "/docs/:page")
    (let [fxs (reg-nav-fxs-capturing!)]
      (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}])
      (reset! (:scroll fxs) [])
      (reset! (:push fxs) [])
      (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "b" :scroll false}])
      (is (= "b" (:fragment (nav-slice))) ":fragment still updates with :scroll false")
      (is (= ["/docs/routing#b"] @(:push fxs)) "the URL is still pushed")
      (is (empty? @(:scroll fxs))
          ":scroll false suppressed the :rf.nav/scroll effect"))))

(deftest navigate-fragment-only-clearing-fragment-rf2-k4exp1
  (testing "rf2-k4exp1: navigating with NO fragment from a fragment'd route
            CLEARS the fragment (writes nil, pushes the fragment-less URL) and
            is still a fragment-only short-circuit (no re-fire, no new token)"
    (let [on-match-calls (atom 0)]
      (rf/reg-event :docs/load (fn [{:keys [db]} _] (swap! on-match-calls inc) {:db db}))
      (rf/reg-route :route/docs {:on-match [[:docs/load]]} "/docs/:page")
      (let [fxs (reg-nav-fxs-capturing!)]
        (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}])
        (reset! (:push fxs) [])
        ;; No :fragment opt → normalises to nil; #a → nil differs → fragment-only.
        (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"}}])
        (is (nil? (:fragment (nav-slice))) "fragment cleared to nil")
        (is (= "nav-1" (:nav-token (nav-slice))) "clearing a fragment is fragment-only — no new token")
        (is (= 1 @on-match-calls) "clearing a fragment does NOT re-fire :on-match")
        (is (= ["/docs/routing"] @(:push fxs))
            "the fragment-less URL is pushed")))))

(deftest navigate-identical-target-with-replace-stays-noop-rf2-k4exp1
  (testing "rf2-k4exp1: an EXACTLY identical target (same fragment too) stays the
            complete no-op even with {:replace? true} — no history rewrite, no
            fragment-changed, no token change (identical wins over fragment-only)"
    (rf/reg-route :route/docs {} "/docs/:page")
    (let [fxs (reg-nav-fxs-capturing!)]
      (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}])
      (let [token-before (:nav-token (nav-slice))
            traces       (atom [])]
        (reset! (:push fxs) [])
        (reset! (:replace fxs) [])
        (rf/register-listener! :trace ::k4exp1-identical (fn [ev] (swap! traces conj ev)))
        ;; Same route/params/query AND same fragment #a, with :replace? true.
        (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a" :replace? true}])
        (rf/unregister-listener! :trace ::k4exp1-identical)
        (is (= token-before (:nav-token (nav-slice))) "identical target: token unchanged")
        (is (empty? @(:push fxs))    "identical target: no push")
        (is (empty? @(:replace fxs)) "identical target: no replace even with :replace? true")
        (is (not-any? #(= :rf.route/fragment-changed (:operation %)) @traces)
            "identical target emits NO :rf.route/fragment-changed (it is a complete no-op)")))))

(deftest navigate-fragment-only-does-not-replan-resources-rf2-k4exp1
  (testing "rf2-k4exp1: a fragment-only nav does NOT invoke the route-entry
            resource plan (no ensure / release / re-plan) and leaves the owner
            nav-token unchanged — proven via the shared `:routing/on-route-entry`
            late-bind hook `commit-navigation` calls (the fragment-only branch
            never enters `commit-navigation`)"
    (rf/reg-route :route/docs {:on-match [[:docs/load]]} "/docs/:page")
    (rf/reg-event :docs/load (fn [{:keys [db]} _] {:db db}))
    (let [fxs        (reg-nav-fxs-capturing!)
          plan-calls (atom [])
          prior      (late-bind/get-fn :routing/on-route-entry)]
      ;; Spy the route-entry hook the Resources artefact publishes; return an
      ;; empty plan (no blocking, no fx, no error) so commit-navigation proceeds.
      (late-bind/set-fn! :routing/on-route-entry
                         (fn [ctx] (swap! plan-calls conj ctx) {}))
      (try
        ;; Full nav → the route-entry plan runs once.
        (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}])
        (is (= 1 (count @plan-calls))
            "the route-entry resource plan ran once on the full nav")
        (let [token-after-full (:nav-token (nav-slice))]
          ;; Fragment-only nav → the plan is NOT invoked again; owner token stays.
          (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "b"}])
          (is (= 1 (count @plan-calls))
              "the fragment-only nav did NOT re-run the route-entry plan — no
               ensure/release/re-plan")
          (is (= token-after-full (:nav-token (nav-slice)))
              "the owner nav-token is unchanged, so an in-flight resource owner
               keyed on it stays valid"))
        (finally
          ;; Restore the prior hook binding (nil in the resources-free routing
          ;; suite) so this spy never leaks into a sibling test.
          (late-bind/set-fn! :routing/on-route-entry prior))))))

;; ============================================================================
;; rf2-p1aipi — URL-driven fragment-only path emits the resolved scroll-fx
;; ============================================================================
;;
;; Spec 012 §Fragments rules 3-4 / §Scroll restoration. The URL-driven
;; fragment-only doors — `:rf.route/transitioned` (forward nav, default scroll
;; `:top`) and `:rf.route/handle-url-change` (popstate / Back-Forward, default
;; `:restore`) — short-circuit the full commit (no fresh nav-token, no
;; `:on-match` re-fire). But before rf2-p1aipi they DROPPED the resolved
;; scroll-fx: `fragment-only-fx` emitted only the scroll CAPTURE. So a user
;; clicking a `#section` link, or Back-Forward to a fragment, computed WHERE to
;; scroll and then never scrolled — `pushState`/popstate do not scroll to a
;; fragment natively. The programmatic door (`fragment-only-nav-fx`, rf2-k4exp1)
;; already emits `:rf.nav/scroll`; this mirrors that emission onto the URL-driven
;; door (capture → scroll; NO push — the address bar already changed).

(defn- reg-scroll-fxs-capturing!
  "Register the URL-driven fragment door's scroll fxs (`:rf.nav/scroll` +
  `:rf.nav/capture-scroll`) plus `:rf.nav/push-url` (so an unexpected push would
  be observable) with capturing handlers on both server+client so they route on
  the JVM. Returns a map of atoms: `:scroll` (args vectors), `:capture` (args
  vectors), `:push` (URL vectors), and `:order` (an ordered `[fx-id args]` log)."
  []
  (let [scrolled (atom [])
        captured (atom [])
        pushed   (atom [])
        order    (atom [])]
    (fx/reg-fx :rf.nav/scroll {:platforms #{:server :client}}
               (fn [_ args] (swap! scrolled conj args) (swap! order conj [:rf.nav/scroll args])))
    (fx/reg-fx :rf.nav/capture-scroll {:platforms #{:server :client}}
               (fn [_ args] (swap! captured conj args) (swap! order conj [:rf.nav/capture-scroll args])))
    (fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
               (fn [_ url]  (swap! pushed conj url)   (swap! order conj [:rf.nav/push-url url])))
    {:scroll scrolled :capture captured :push pushed :order order}))

(deftest url-driven-fragment-only-emits-scroll-rf2-p1aipi
  (testing "rf2-p1aipi: a URL-driven fragment-only change (:rf.route/transitioned,
            forward nav) emits the resolved :rf.nav/scroll (default :top, targeting
            the new fragment), captures the leaving position FIRST, and pushes NO
            URL — mirroring the programmatic fragment-only door (rf2-k4exp1). Before
            the fix the scroll-fx was DROPPED (only capture rode along)."
    (let [on-match-calls (atom 0)]
      (rf/reg-event :docs/load (fn [{:keys [db]} _] (swap! on-match-calls inc) {:db db}))
      (rf/reg-route :route/docs {:on-match [[:docs/load]]} "/docs/:page")
      (let [fxs (reg-scroll-fxs-capturing!)]
        ;; Full nav to /docs/routing#a → loader fires once, allocates nav-1.
        (rf/dispatch-sync [:rf.route/transitioned "/docs/routing#a"])
        (is (= 1 @on-match-calls) ":on-match fired once on the full nav")
        (is (= "nav-1" (:nav-token (nav-slice))) "first nav allocated nav-1")
        (reset! (:scroll fxs) [])
        (reset! (:capture fxs) [])
        (reset! (:order fxs) [])
        ;; Fragment-only forward nav: #a → #b (same route-id/params/query).
        (rf/dispatch-sync [:rf.route/transitioned "/docs/routing#b"])
        ;; The fragment-only short-circuit still holds …
        (is (= "b" (:fragment (nav-slice))) "fragment updated to #b")
        (is (= "nav-1" (:nav-token (nav-slice))) "rule 3: no NEW nav-token")
        (is (= 1 @on-match-calls) "rule 4: :on-match did NOT re-fire")
        ;; … AND the resolved scroll-fx is now emitted (the rf2-p1aipi fix).
        ;; TEETH — this is exactly the effect the URL-driven door used to drop.
        (is (= 1 (count @(:scroll fxs)))
            "TEETH: the URL-driven fragment-only nav emits exactly one :rf.nav/scroll
             (was DROPPED before rf2-p1aipi)")
        (let [args (first @(:scroll fxs))]
          (is (= :top (:strategy args)) "forward-nav default scroll strategy is :top")
          (is (= "b"  (:fragment args)) ":rf.nav/scroll targets the new #b fragment"))
        ;; Ordering: capture the leaving position, THEN scroll. No push — the
        ;; address bar already changed (URL-driven doors never drive the URL).
        (is (= [:rf.nav/capture-scroll :rf.nav/scroll] (mapv first @(:order fxs)))
            "ordered: capture the leaving scroll, then scroll to the new fragment")
        (is (= {:url "/docs/routing#a"} (first @(:capture fxs)))
            "capture-scroll saves the position of the route being left (#a)")
        (is (empty? @(:push fxs))
            "URL-driven door pushes NO URL (the address bar already changed)")))))

(deftest url-driven-popstate-fragment-only-emits-restore-scroll-rf2-p1aipi
  (testing "rf2-p1aipi: a popstate fragment-only change (:rf.route/handle-url-change)
            emits :rf.nav/scroll with the :restore default (Back-Forward restores
            the saved position), still short-circuiting (no new token / no re-fire)."
    (let [on-match-calls (atom 0)]
      (rf/reg-event :docs/load (fn [{:keys [db]} _] (swap! on-match-calls inc) {:db db}))
      (rf/reg-route :route/docs {:on-match [[:docs/load]]} "/docs/:page")
      (let [fxs (reg-scroll-fxs-capturing!)]
        (rf/dispatch-sync [:rf.route/handle-url-change "/docs/routing#a"])
        (is (= 1 @on-match-calls) ":on-match fired once on the full popstate nav")
        (reset! (:scroll fxs) [])
        ;; Back/Forward to the SAME page, only the #fragment differs.
        (rf/dispatch-sync [:rf.route/handle-url-change "/docs/routing#b"])
        (is (= "b" (:fragment (nav-slice))) "fragment updated to #b")
        (is (= "nav-1" (:nav-token (nav-slice))) "rule 3: no NEW nav-token on popstate fragment-only")
        (is (= 1 @on-match-calls) "rule 4: :on-match did NOT re-fire on popstate fragment-only")
        (is (= 1 (count @(:scroll fxs)))
            "TEETH: the popstate fragment-only nav emits exactly one :rf.nav/scroll
             (was DROPPED before rf2-p1aipi)")
        (is (= :restore (:strategy (first @(:scroll fxs))))
            "popstate default scroll strategy is :restore")
        (is (empty? @(:push fxs))
            "URL-driven popstate door pushes NO URL")))))

(deftest url-driven-fragment-only-scroll-false-suppresses-rf2-p1aipi
  (testing "rf2-p1aipi: `:scroll false` on the route meta suppresses the
            :rf.nav/scroll effect on a URL-driven fragment-only change, but still
            updates :fragment (mirrors the programmatic :scroll-false suppression)."
    (rf/reg-route :route/docs {:scroll false} "/docs/:page")
    (let [fxs (reg-scroll-fxs-capturing!)]
      (rf/dispatch-sync [:rf.route/transitioned "/docs/routing#a"])
      (reset! (:scroll fxs) [])
      (rf/dispatch-sync [:rf.route/transitioned "/docs/routing#b"])
      (is (= "b" (:fragment (nav-slice))) ":fragment still updates with :scroll false")
      (is (empty? @(:scroll fxs))
          ":scroll false suppressed the :rf.nav/scroll effect on the URL-driven
           fragment-only door"))))

;; ============================================================================
;; rf2-vwwvp — the always-on structural gate (Spec 012 §Validity rules)
;; ============================================================================
;;
;; The gate rejects a malformed request map BEFORE any guard runs, emitting
;; :rf.error/navigate-bad-request (:where :event) with a :reason discriminator
;; and :keys. The slice is unchanged, no URL is pushed. red->green per rule.

(defn- gate-reject
  "Dispatch `request` as `[:rf.route/navigate request]` from a signed-in slice
  on `:route/gate-a` and return the first :rf.error/navigate-bad-request trace
  (or nil). Records whether any URL was pushed under `pushed`."
  [request pushed]
  (let [errors (atom [])]
    (rf/register-listener! :trace ::gate
                           (fn [ev] (when (= :error (:op-type ev))
                                      (swap! errors conj ev))))
    (rf/dispatch-sync [:rf.route/navigate request])
    (rf/unregister-listener! :trace ::gate)
    (first (filter #(= :rf.error/navigate-bad-request (:operation %)) @errors))))

(deftest navigate-structural-gate-rejects-malformed-requests
  (rf/reg-route :route/gate-a {:query [:map [:q {:optional true} :string]]} "/gate-a")
  (rf/reg-route :route/gate-b {} "/gate-b")
  (let [pushed (atom [])
        slice  (fn [] (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                              [:rf.runtime/routing :current]))]
    (fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
               (fn [_ url] (swap! pushed conj url)))
    ;; Establish a current route so in-place requests have something to patch.
    (rf/dispatch-sync [:rf.route/navigate {:to :route/gate-a}])
    (reset! pushed [])

    (testing "empty map rejects (:no-destination-or-change)"
      (let [err (gate-reject {} pushed)]
        (is (= :no-destination-or-change (-> err :tags :reason)))
        (is (= :event (-> err :tags :where)))
        (is (empty? @pushed))))

    (testing "pure-policy map rejects (:no-destination-or-change)"
      (is (= :no-destination-or-change (-> (gate-reject {:replace? true} pushed) :tags :reason)))
      (is (empty? @pushed)))

    (testing ":to + :url are mutually exclusive"
      (is (= :to-url-exclusive (-> (gate-reject {:to :route/gate-b :url "/gate-b"} pushed) :tags :reason))))

    (testing ":url excludes :params / :query / :query-merge"
      (is (= :url-excludes-address (-> (gate-reject {:url "/gate-b" :query {:q "x"}} pushed) :tags :reason))))

    (testing ":query + :query-merge are mutually exclusive"
      (is (= :query-exclusive (-> (gate-reject {:query {:q "x"} :query-merge {:q "y"}} pushed) :tags :reason))))

    (testing ":query-merge on a destination request rejects"
      (is (= :query-merge-in-place-only
             (-> (gate-reject {:to :route/gate-b :query-merge {:q "x"}} pushed) :tags :reason))))

    (testing "unknown keys reject (namespaced INCLUDED)"
      (is (= :unknown-keys (-> (gate-reject {:to :route/gate-b :bogus 1} pushed) :tags :reason)))
      (let [err (gate-reject {:to :route/gate-b :my-app/replace? true} pushed)]
        (is (= :unknown-keys (-> err :tags :reason)))
        (is (= [:my-app/replace?] (-> err :tags :keys))
            "a namespaced unknown key fails as loudly as a bare typo")))

    (is (= :route/gate-a (:route-id (slice)))
        "every rejected request left the slice on the original route (unchanged)")
    (is (empty? @pushed)
        "no rejected request pushed a URL")))

(deftest navigate-internal-enter-attempts-rider-is-stripped-before-gate
  (testing "the runtime resume rider :rf.route/enter-attempts is stripped before
            the gate (it is not an unknown key)"
    (rf/reg-route :route/rider {} "/rider")
    (let [errors (atom [])]
      (fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
      (rf/register-listener! :trace ::rider
                             (fn [ev] (when (= :error (:op-type ev))
                                        (swap! errors conj ev))))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/rider :rf.route/enter-attempts 2}])
      (rf/unregister-listener! :trace ::rider)
      (is (= :route/rider (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                             [:rf.runtime/routing :current])))
          "the request navigates cleanly despite carrying the internal rider")
      (is (empty? (filter #(= :rf.error/navigate-bad-request (:operation %)) @errors))
          "no navigate-bad-request — the rider is stripped, not treated as unknown"))))

;; ============================================================================
;; rf2-0zsvw — explicit :fragment override on an UNMATCHED raw-URL navigate
;; ============================================================================
;;
;; PR #6581 promised that `:url` + `:fragment` is legal and the explicit
;; request fragment overrides the fragment embedded in the raw URL. That held
;; when the URL matched a route (route-url rebuilds the address), but the
;; UNMATCHED shortcut pushed the raw url verbatim — the address bar kept `#old`
;; while the slice carried `#new`, and the guard/pending target disagreed. The
;; effective requested URL now carries the explicit fragment (an explicit nil
;; clears it), and one URL feeds the slice, the push, and the not-found :params.

(deftest navigate-unmatched-url-honours-explicit-fragment-override
  (testing "an unmatched {:url raw :fragment value} navigate rebuilds ONE
            effective URL — raw path/query with the explicit fragment — and
            feeds it to the not-found :params, the slice, and the push"
    (rf/reg-route :route/home {} "/")
    (rf/reg-route :rf.route/not-found {} "/404")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      ;; ---- override: explicit :fragment replaces the embedded one ----
      (rf/dispatch-sync [:rf.route/navigate {:url "/no/such/path#old" :fragment "new"}])
      (let [slice (nav-slice)]
        (is (= :rf.route/not-found (:route-id slice))
            "unmatched URL-string target → :rf.route/not-found slice")
        (is (= "new" (:fragment slice))
            "slice :fragment is the EXPLICIT override, not the embedded #old")
        (is (= {:url "/no/such/path#new"} (:params slice))
            "not-found :params carries the effective URL (embedded #old replaced)"))
      (is (= ["/no/such/path#new"] @pushed)
          "the PUSHED url carries the explicit fragment (pre-fix: /no/such/path#old)")

      ;; ---- clear: explicit nil fragment drops the embedded fragment ----
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/navigate {:url "/no/such/path#old" :fragment nil}])
      (let [slice (nav-slice)]
        (is (nil? (:fragment slice))
            "an explicit nil fragment clears the embedded #old in the slice")
        (is (= {:url "/no/such/path"} (:params slice))
            "not-found :params carries the fragment-cleared effective URL"))
      (is (= ["/no/such/path"] @pushed)
          "the PUSHED url has NO fragment when the explicit fragment is nil"))))

(deftest navigate-unmatched-url-without-explicit-fragment-keeps-embedded
  (testing "an unmatched {:url raw} navigate with NO explicit :fragment pushes
            the raw url verbatim (existing not-found behaviour is unchanged)"
    (rf/reg-route :route/home {} "/")
    (rf/reg-route :rf.route/not-found {} "/404")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate {:url "/no/such/path#keep"}])
      (is (= ["/no/such/path#keep"] @pushed)
          "no explicit :fragment → the raw url (with its embedded fragment) rides verbatim")
      (is (= {:url "/no/such/path#keep"} (:params (nav-slice)))
          "not-found :params carries the raw url verbatim"))))

;; ============================================================================
;; rf2-oq0ld — exact + total navigate map-boundary validation
;; ============================================================================
;;
;; Malformed / half-migrated shapes that previously escaped the request gate:
;;   - `:params` beside an in-place key (silently ignored — params is never
;;     accepted in-place; changing params requires a destination);
;;   - a non-map payload (reached `dissoc` → raw host throw);
;;   - a THIRD event element (a positional opts map left over from the deleted
;;     [target opts] split — silently dropped while navigation proceeded);
;;   - a heterogeneous unknown-key set (reached a plain `sort` → compare throw).
;; Each now rejects LOUD through :rf.error/navigate-bad-request, slice unchanged.

(deftest navigate-params-in-place-without-destination-rejects
  (testing ":params without a destination (:to / :url) rejects — a request
            carrying :params beside an in-place key no longer silently drops
            the params (rf2-oq0ld)"
    (rf/reg-route :route/items {:params [:map [:id :string]]} "/items/:id")
    (let [pushed (atom [])
          errors (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      ;; Land on /items/old so an in-place patch has a current route.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/items :params {:id "old"}}])
      (reset! pushed [])
      (rf/register-listener! :trace ::params-reject
                             (fn [ev] (when (= :error (:op-type ev))
                                        (swap! errors conj ev))))
      ;; The reproduction: :params beside an in-place :fragment. Pre-fix this
      ;; emitted NO error and pushed /items/old#x with :params silently ignored.
      (rf/dispatch-sync [:rf.route/navigate {:params {:id "new"} :fragment "x"}])
      (rf/unregister-listener! :trace ::params-reject)
      (is (= {:id "old"} (:params (nav-slice)))
          "the slice params are UNCHANGED — the supplied :params did not sneak in")
      (is (empty? @pushed) "no URL is pushed for the rejected request")
      (let [err (first (filter #(= :rf.error/navigate-bad-request (:operation %)) @errors))]
        (is (some? err) ":rf.error/navigate-bad-request emitted")
        (is (= :params-requires-destination (-> err :tags :reason))
            ":reason names the params-without-destination violation")
        (is (= [:params] (-> err :tags :keys))
            ":keys names the offending :params key")))))

(deftest navigate-non-map-payload-and-extra-element-reject
  (testing "a non-map payload and a third event element reject LOUD rather than
            throwing a raw host exception / silently dropping the extra element
            (rf2-oq0ld)"
    (rf/reg-route :route/home {} "/")
    (rf/reg-route :route/dest {} "/dest")
    (let [pushed (atom [])
          errors (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (fx/reg-fx :rf.nav/replace-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      ;; Establish a current route so the slice-unchanged assertion is meaningful.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
      (reset! pushed [])
      (rf/register-listener! :trace ::shape-reject
                             (fn [ev] (when (= :error (:op-type ev))
                                        (swap! errors conj ev))))

      (testing "non-map payload rejects with :request-not-a-map (no raw throw)"
        (reset! errors [])
        ;; Pre-fix this reached `(dissoc \"/dest\" …)` and threw a host exception.
        (rf/dispatch-sync [:rf.route/navigate "/dest"])
        (let [err (first (filter #(= :rf.error/navigate-bad-request (:operation %)) @errors))]
          (is (some? err) ":rf.error/navigate-bad-request emitted for a non-map payload")
          (is (= :request-not-a-map (-> err :tags :reason)))))

      (testing "extra event element rejects with :bad-event-arity (not dropped)"
        (reset! errors [])
        ;; Pre-fix this navigated to :route/dest while dropping {:replace? true}.
        (rf/dispatch-sync [:rf.route/navigate {:to :route/dest} {:replace? true}])
        (let [err (first (filter #(= :rf.error/navigate-bad-request (:operation %)) @errors))]
          (is (some? err) ":rf.error/navigate-bad-request emitted for a 3-element event")
          (is (= :bad-event-arity (-> err :tags :reason)))))

      (rf/unregister-listener! :trace ::shape-reject)
      (is (= :route/home (:route-id (nav-slice)))
          "every malformed-shape request left the slice on the original route")
      (is (empty? @pushed) "no malformed-shape request pushed a URL"))))

(deftest navigate-heterogeneous-unknown-keys-report-totally
  (testing "a request carrying MIXED-KIND unknown keys (keyword / string /
            number) reports :unknown-keys in total canonical order rather than
            throwing a raw compare exception (rf2-oq0ld)"
    (rf/reg-route :route/gate {} "/gate")
    (let [pushed (atom [])
          errors (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/gate}])
      (reset! pushed [])
      (rf/register-listener! :trace ::hetero
                             (fn [ev] (when (= :error (:op-type ev))
                                        (swap! errors conj ev))))
      ;; :a/b, "s", and 3 are all unknown keys of DIFFERENT kinds — a plain
      ;; `(sort #{:a/b "s" 3})` throws a ClassCastException on the JVM.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/gate :a/b 1 "s" 2 3 4}])
      (rf/unregister-listener! :trace ::hetero)
      (let [err (first (filter #(= :rf.error/navigate-bad-request (:operation %)) @errors))]
        (is (some? err) ":rf.error/navigate-bad-request emitted (no raw compare throw)")
        (is (= :unknown-keys (-> err :tags :reason)))
        (is (= (vec (sort-by identity/canonical-bytes #{:a/b "s" 3}))
               (-> err :tags :keys))
            ":keys are the heterogeneous unknown keys in total canonical order"))
      (is (empty? @pushed) "no URL is pushed for the rejected request"))))
