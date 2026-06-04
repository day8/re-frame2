(ns re-frame.routing-test
  "JVM smoke tests for re-frame.routing. The conformance fixtures cover
  the canonical surface (URL ↔ params, fragment, blocking, nav-token);
  these tests exercise the JVM-pipeline composition end-to-end and
  pin a few behaviours the conformance DSL doesn't express directly:

  - routing-bootstrap            — reg-route + dispatch :rf.route/navigate
                                   updates the :rf/route slice end-to-end
  - routing-pending-nav-protocol — full pause / continue / cancel
                                   walk-through (the conformance fixtures
                                   cover continue and cancel separately;
                                   this one drives the slot's full
                                   lifecycle from one test)
  - routing-nav-token-staleness  — two in-flight navigations; only the
                                   most recent token's result commits
  - routing-scroll-metadata-preserved — :scroll metadata on a route is
                                   queryable via handler-meta. Pins the
                                   registration round-trip contract.
  - routing-scroll-fx-emitted-on-navigate — every successful navigation
                                   emits :rf.nav/scroll with the resolved
                                   strategy / from / to / saved-pos /
                                   fragment args; :scroll false suppresses.
  - routing-nested-layout-parent-link — :parent metadata is preserved by
                                   reg-route so views / handler-meta-driven
                                   tools can render the layout chain. The
                                   :rf.route/chain sub is not yet built-in;
                                   this test asserts the registry-level
                                   contract.

  Per Spec 012 §URL changes are events, §Navigation blocking — pending-nav
  protocol, §Navigation tokens — stale-result suppression, §Scroll
  restoration, §Nested layouts."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            ;; rf2-k682: this test lives in the routing artefact's test
            ;; classpath, so requiring re-frame.routing here is the
            ;; primary trigger that loads the namespace and fires its
            ;; late-bind hook registrations + framework `:rf.route/*`
            ;; reg-sub installations. Without this require the
            ;; rf/reg-route call below would throw
            ;; :rf.error/routing-artefact-missing.
            [re-frame.routing :as routing]
            [re-frame.routing.match :as routing.match]
            [re-frame.routing.registry :as registry]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  ;; Framework events / fx (routing.cljc, ssr.cljc) are registered at
  ;; ns-load; clear-all! wiped them. Reload to resurrect.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (routing/reset-counters!)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- Spec 012 §Navigation is an event -------------------------------------

(deftest routing-bootstrap
  (testing "reg-route + dispatch :rf.route/navigate writes the :rf/route slice"
    ;; Per Spec 012 §Navigation is an event: dispatching :rf.route/navigate
    ;; with a route-id + params updates :rf/route.{id,params,query,...} and
    ;; emits a :rf.nav/push-url effect.
    (rf/reg-route :route/home    {:path "/"})
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]})
    ;; Capture the URL that lands at :rf.nav/push-url.
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate :route/article {:id "intro"}])
      (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
        (is (= :route/article (:id slice))
            "the :rf/route slice carries the navigation target")
        (is (= {:id "intro"} (:params slice))
            ":params from the navigate vector landed in the slice")
        (is (= :idle (:transition slice))
            "no :on-match → transition stays :idle"))
      (is (= ["/articles/intro"] @pushed)
          ":rf.nav/push-url received the unparsed URL"))))

;; ---- Spec 012 §Navigation blocking — pending-nav protocol ----------------

(deftest routing-pending-nav-protocol
  (testing "block via :can-leave; continue and cancel both clear the slot"
    ;; Per Spec 012 §Navigation blocking — pending-nav protocol: a route
    ;; declares :can-leave (sub-id → boolean). When the sub returns false,
    ;; :rf/url-requested writes :rf/pending-navigation and does not push.
    ;; :rf.route/continue clears the slot AND completes the navigation.
    ;; :rf.route/cancel clears the slot WITHOUT navigating.
    (rf/reg-route :editor/article
                  {:path      "/editor/articles/:id"
                   :params    [:map [:id :string]]
                   :can-leave :editor/can-leave?})
    (rf/reg-route :route/cart {:path "/cart"})

    (rf/reg-event-db :editor/dirty
                     (fn [db [_ v]] (assoc-in db [:editor :dirty?] v)))
    (rf/reg-sub :editor/can-leave?
                (fn [db _]
                  ;; "OK to leave" = NOT dirty.
                  (not (get-in db [:editor :dirty?]))))

    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))

    ;; 1. Land on the editor route. nav-token allocates; slice is set.
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (is (= :editor/article (get-in (rf/app-db-value :rf/default)
                                   [:rf/runtime :routing :current :id]))
        "initial nav landed on :editor/article")

    ;; 2. Dirty the form so :can-leave? returns false.
    (rf/dispatch-sync [:editor/dirty true])

    ;; 3. Try to leave. Guard rejects → pending slot is set; URL unchanged.
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (let [pending (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :pending-navigation])]
      (is (some? pending)
          ":rf/pending-navigation is populated on guard rejection")
      (is (= "/cart" (:requested-url pending))
          "the requested URL is captured for resume (rf2-b8ugt slot shape)")
      (is (= :editor/article (:rejecting-route pending))
          ":rejecting-route names the route whose guard ran")
      (is (= :editor/can-leave? (:rejecting-guard pending))
          ":rejecting-guard names the sub-id that rejected")
      (is (vector? (:requested-by-event pending))
          ":requested-by-event captures the original :rf/url-requested vector")
      (is (= :editor/article
             (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current :id]))
          "the :rf/route slice does NOT change when blocked"))

    ;; 4. CANCEL — slot clears; original route stays active.
    (rf/dispatch-sync [:rf.route/cancel "pn-1"])
    (is (nil? (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :pending-navigation]))
        "cancel clears :rf/pending-navigation")
    (is (= :editor/article
           (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current :id]))
        "cancel does NOT navigate")

    ;; 5. Now block again — and CONTINUE this time.
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (is (some? (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :pending-navigation]))
        "second blocked request reseats the slot")
    (rf/dispatch-sync [:rf.route/continue "pn-2"])
    (is (nil? (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :pending-navigation]))
        "continue clears :rf/pending-navigation")
    (is (= :route/cart (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current :id]))
        "continue completes the original navigation")))

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
                  {:path           "/search"
                   :query-retain   #{:theme :locale}})
    (rf/reg-route :route/cart
                  {:path         "/cart"
                   :query-retain #{:theme :locale}})
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))

      ;; 1. Land on /search with ?theme=dark&locale=en — the URL-driven
      ;;    path populates the slice via match-url + handle-url-change.
      (rf/dispatch-sync [:rf.route/transitioned "/search?theme=dark&locale=en"])
      (is (= {:theme "dark" :locale "en"}
             (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current :query]))
          "initial slice carries the URL's query keys")

      ;; 2. Navigate programmatically to :route/cart with NO query — the
      ;;    target's :query-retain must merge :theme + :locale through.
      (rf/dispatch-sync [:rf.route/navigate :route/cart])
      (let [last-url (last @pushed)]
        (is (re-find #"theme=dark" last-url)
            ":query-retain preserves :theme through programmatic nav")
        (is (re-find #"locale=en" last-url)
            ":query-retain preserves :locale through programmatic nav"))

      ;; 3. Caller-supplied query values WIN over retained values.
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/navigate :route/cart {} {:query {:theme "light"}}])
      (let [last-url (last @pushed)]
        (is (re-find #"theme=light" last-url)
            "caller-supplied :theme overrides retained value")
        (is (re-find #"locale=en" last-url)
            "other retained keys still carry through")))))

(deftest routing-query-retain-no-op-without-declaration
  (testing "routes without :query-retain do not inherit query keys"
    (rf/reg-route :route/search
                  {:path         "/search"
                   :query-retain #{:theme}})
    (rf/reg-route :route/cart
                  {:path "/cart"}) ;; no :query-retain
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/transitioned "/search?theme=dark"])
      (rf/dispatch-sync [:rf.route/navigate :route/cart])
      (is (= "/cart" (last @pushed))
          ":query-retain undeclared → no carry-through, URL stays bare"))))

;; ---- Spec 012 §Navigation tokens — stale-result suppression --------------

(deftest routing-nav-token-staleness
  (testing "two in-flight navigations: the older nav-token's result is suppressed"
    ;; Per Spec 012 §Navigation tokens — stale-result suppression: each
    ;; navigation allocates a fresh nav-token; async results carry the
    ;; token captured at request time; when a result arrives whose token
    ;; mismatches the current slice's :nav-token, the runtime suppresses
    ;; it and emits :rf.route.nav-token/stale-suppressed.
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]})
    (rf/reg-event-db :article/loaded
                     (fn [db [_ id payload]]
                       (assoc db :article {:id id :payload payload})))

    (let [traces (atom [])]
      (rf/register-listener! ::nav-token (fn [ev] (swap! traces conj ev)))

      ;; 1. Navigate to /articles/A. nav-token allocates → "nav-1".
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (is (= "nav-1" (get-in (rf/app-db-value :rf/default)
                             [:rf/runtime :routing :current :nav-token]))
          "first navigation got nav-1")

      ;; 2. Before A's response lands, navigate to /articles/B → "nav-2".
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      (is (= "nav-2" (get-in (rf/app-db-value :rf/default)
                             [:rf/runtime :routing :current :nav-token]))
          "second navigation advanced the epoch to nav-2")

      ;; 3. A's stale response carries "nav-1"; current is "nav-2";
      ;; the runtime suppresses [:article/loaded "A" "A-payload"].
      (rf/dispatch-sync [:rf.test/simulate-http-resolution
                         {:on-success-event   [:article/loaded "A" "A-payload"]
                          :carried-nav-token  "nav-1"}])

      ;; 4. B's response carries "nav-2"; matches current; commits.
      (rf/dispatch-sync [:rf.test/simulate-http-resolution
                         {:on-success-event   [:article/loaded "B" "B-payload"]
                          :carried-nav-token  "nav-2"}])

      (rf/unregister-listener! ::nav-token)

      (is (= {:id "B" :payload "B-payload"}
             (:article (rf/app-db-value :rf/default)))
          "only B's payload committed; A's was suppressed")

      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= "nav-1" (-> ev :tags :carried-token))
                       (= "nav-2" (-> ev :tags :current-token))
                       (= :article/loaded (-> ev :tags :rf.trace/event-id))))
                @traces)
          "expected :rf.route.nav-token/stale-suppressed trace for the A response"))))

(deftest with-nav-token-fx-suppresses-stale-do-and-commits-fresh
  (testing ":rf.route/with-nav-token fx: stale `:do` is suppressed; fresh `:do` runs"
    ;; Per Spec 012 §Navigation tokens §Threading: a user event handler
    ;; emits an `:fx` entry of the form
    ;;
    ;;   [:rf.route/with-nav-token {:do        [:dispatch [<ev> args...]]
    ;;                              :nav-token <captured-token>}]
    ;;
    ;; …and the runtime threads the carried token against the current
    ;; route slice's `:nav-token` (read from
    ;; `[:rf/runtime :routing :current :nav-token]`). Match → the inner
    ;; fx runs (canonically a
    ;; `:dispatch` to the success continuation). Mismatch → the inner fx
    ;; is suppressed and `:rf.route.nav-token/stale-suppressed` emits.
    ;;
    ;; This test pins both branches via the production fx (no use of
    ;; the test-only `:rf.test/simulate-http-resolution` event). The
    ;; `:article/loaded` continuation is the user-facing handler the
    ;; wrapped dispatch would commit through; we observe it via the
    ;; resulting app-db slice.
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]})
    (rf/reg-event-db :article/loaded
                     (fn [db [_ id payload]]
                       (assoc db :article {:id id :payload payload})))
    ;; Bridge event: a real :on-success handler. Carries the token it
    ;; captured at request time and re-emits an `:rf.route/with-nav-token`
    ;; fx entry. The runtime then either dispatches `[:article/loaded ...]`
    ;; (match) or suppresses (mismatch).
    (rf/reg-event-fx :article/loaded-via-nav-token
                     (fn [_ctx [_ {:keys [carried-token id payload]}]]
                       {:fx [[:rf.route/with-nav-token
                              {:do        [:dispatch [:article/loaded id payload]]
                               :nav-token carried-token}]]}))

    (let [traces (atom [])]
      (rf/register-listener! ::with-nav-token-fx
                             (fn [ev] (swap! traces conj ev)))

      ;; 1. Land on :route/article id="A" — nav-token allocates to "nav-1".
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (is (= "nav-1" (get-in (rf/app-db-value :rf/default)
                             [:rf/runtime :routing :current :nav-token]))
          "first navigation got nav-1")

      ;; 2. Before A's async :on-success lands, navigate to id="B" — "nav-2".
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      (is (= "nav-2" (get-in (rf/app-db-value :rf/default)
                             [:rf/runtime :routing :current :nav-token]))
          "second navigation advanced the epoch to nav-2")

      ;; 3. A's stale :on-success arrives carrying "nav-1" via the fx
      ;; wrapper. Current is "nav-2"; the inner :dispatch must be
      ;; suppressed and the trace must fire.
      (rf/dispatch-sync [:article/loaded-via-nav-token
                         {:carried-token "nav-1"
                          :id            "A"
                          :payload       "A-payload"}])

      ;; 4. B's fresh :on-success arrives carrying "nav-2"; matches
      ;; current; inner :dispatch fires; :article/loaded commits.
      (rf/dispatch-sync [:article/loaded-via-nav-token
                         {:carried-token "nav-2"
                          :id            "B"
                          :payload       "B-payload"}])

      (rf/unregister-listener! ::with-nav-token-fx)

      (is (= {:id "B" :payload "B-payload"}
             (:article (rf/app-db-value :rf/default)))
          "fresh :do ran end-to-end; stale :do was suppressed before commit")

      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= "nav-1" (-> ev :tags :carried-token))
                       (= "nav-2" (-> ev :tags :current-token))
                       (= :article/loaded (-> ev :tags :rf.trace/event-id))))
                @traces)
          "stale :do produced :rf.route.nav-token/stale-suppressed with the inner dispatch's event-id")

      ;; Negative: no spurious suppressed-trace for the fresh path.
      (is (= 1 (count (filter (fn [ev]
                                (= :rf.route.nav-token/stale-suppressed
                                   (:operation ev)))
                              @traces)))
          "exactly one stale-suppressed trace fired — the fresh :do did NOT trip the validation"))))

;; ---- Spec 012 §Navigation tokens step 2 — the `:nav-token` cofx ----------
;;
;; rf2-8fnwq: the spec promised an `:on-match`-reachable `:nav-token` cofx
;; (step 2 / step 4 "validating cofx") but no `reg-cofx :nav-token` was
;; registered. A handler that followed the spec — declared
;; `(inject-cofx :nav-token)` and read `{:keys [db nav-token]}` — threaded
;; `nil`, which mismatched the current token in `:rf.route/with-nav-token`
;; EVERY time, so the documented stale-suppression pattern silently ate
;; the result. These tests are the failing-before / passing-after guard.

(deftest nav-token-cofx-injects-the-live-token
  (testing "(inject-cofx :nav-token) injects the current slice token — not nil"
    ;; The minimal contract: a handler declaring the cofx sees the live
    ;; navigation epoch. Pre-fix this was nil (no reg-cofx :nav-token).
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [seen (atom :unset)]
      ;; An :on-match-reached handler that captures the injected token.
      (rf/reg-event-fx :article/capture-token
                       [(rf/inject-cofx :nav-token)]
                       (fn [{:keys [nav-token]} _]
                         (reset! seen nav-token)
                         {}))
      ;; Land on the route, then fire the on-match-style continuation.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (let [current (get-in (rf/app-db-value :rf/default)
                            [:rf/runtime :routing :current :nav-token])]
        (rf/dispatch-sync [:article/capture-token])
        (is (= current @seen)
            "the cofx injected the slice's live :nav-token (pre-fix: nil)")
        (is (some? @seen)
            "the injected token is non-nil (pre-fix the documented shape threaded nil)")))))

(deftest nav-token-cofx-drives-documented-stale-suppression
  (testing "the spec step-2/step-3 example runs: capture via cofx, thread via
            :rf.route/with-nav-token; stale → suppressed, fresh → applied"
    ;; This is the documented pattern end-to-end, using ONLY the public
    ;; surface (no :rf.test/simulate-http-resolution). The :on-match-reached
    ;; loader declares the cofx and captures `nav-token` live; we stash the
    ;; captured token so the test can replay the async completion AFTER a
    ;; superseding navigation (modelling a real out-of-order http race —
    ;; A's request started first but its response lands after B's). The
    ;; completion threads the captured token through :rf.route/with-nav-token,
    ;; which validates against the current slice: stale → suppressed,
    ;; fresh → applied.
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Terminal commit handler.
    (rf/reg-event-db :article/loaded
                     (fn [db [_ id payload]]
                       (assoc db :article {:id id :payload payload})))
    ;; The async completion: threads a captured token through the framework
    ;; fx, which validates against the current slice.
    (rf/reg-event-fx :article/completed
                     (fn [_ctx [_ {:keys [captured-token id payload]}]]
                       {:fx [[:rf.route/with-nav-token
                              {:do        [:dispatch [:article/loaded id payload]]
                               :nav-token captured-token}]]}))

    (let [traces   (atom [])
          captured (atom {})]
      (rf/register-listener! ::cofx-flow (fn [ev] (swap! traces conj ev)))
      ;; The :on-match-reached loader: declares the cofx, captures the live
      ;; token at scheduling time (exactly the documented step-2 shape).
      ;; The capture closes over `captured` so the test replays the
      ;; completion later, out of order.
      (rf/reg-event-fx :article/load
                       [(rf/inject-cofx :nav-token)]
                       (fn [{:keys [nav-token]} [_ id]]
                         (swap! captured assoc id nav-token)
                         {}))

      ;; 1. Navigate to A; the loader captures A's token via the cofx.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (rf/dispatch-sync [:article/load "A"])

      ;; 2. Navigate to B BEFORE A's response lands — fresh token captured.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      (rf/dispatch-sync [:article/load "B"])

      ;; 3. A's response lands LATE, carrying the stale cofx-captured token.
      (rf/dispatch-sync [:article/completed
                         {:captured-token (@captured "A") :id "A" :payload "A-payload"}])
      ;; 4. B's response lands, carrying the fresh cofx-captured token.
      (rf/dispatch-sync [:article/completed
                         {:captured-token (@captured "B") :id "B" :payload "B-payload"}])

      (rf/unregister-listener! ::cofx-flow)

      (is (not= (@captured "A") (@captured "B"))
          "the cofx injected DIFFERENT live tokens for the two navigations")
      (is (every? some? (vals @captured))
          "both captured tokens are non-nil (pre-fix the cofx threaded nil)")
      (is (= {:id "B" :payload "B-payload"}
             (:article (rf/app-db-value :rf/default)))
          "only B committed — A's stale completion was suppressed via the cofx-captured token")
      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= :article/loaded (-> ev :tags :rf.trace/event-id))))
                @traces)
          "A's stale completion produced :rf.route.nav-token/stale-suppressed")
      (is (= 1 (count (filter #(= :rf.route.nav-token/stale-suppressed
                                  (:operation %))
                              @traces)))
          "exactly one suppression — B's fresh completion applied cleanly"))))

;; ---- Spec 012 §Scroll restoration -----------------------------------------

(deftest routing-scroll-metadata-preserved
  (testing "the :scroll metadata key is enumerable via handler-meta"
    ;; Per Spec 012 §Scroll restoration: a route may declare a :scroll
    ;; strategy (:top / :restore / :preserve / map / false). Metadata is
    ;; round-tripped through registration so tooling can enumerate it.
    (rf/reg-route :route/home
                  {:path   "/"
                   :scroll :top})
    (rf/reg-route :route/article
                  {:path   "/articles/:id"
                   :params [:map [:id :string]]
                   :scroll :restore})
    (let [home-meta    (rf/handler-meta :route :route/home)
          article-meta (rf/handler-meta :route :route/article)]
      (is (= :top (:scroll home-meta))
          ":scroll metadata is preserved as-declared")
      (is (= :restore (:scroll article-meta))
          ":scroll metadata is preserved per-route"))))

(deftest routing-scroll-fx-emitted-on-navigate
  (testing ":rf.route/navigate emits :rf.nav/scroll with the resolved strategy"
    ;; Per Spec 012 §Scroll restoration: the runtime emits :rf.nav/scroll
    ;; on every successful navigation, with args
    ;; {:strategy :from :to :saved-pos :fragment}. Resolution order:
    ;;   1. :scroll in :rf.route/navigate's opts (per-call override)
    ;;   2. route metadata's :scroll
    ;;   3. implicit default (:top for forward navigation)
    ;; A :scroll value of `false` (opts or meta) suppresses the fx.
    (rf/reg-route :route/home    {:path "/"})
    (rf/reg-route :route/articles {:path "/articles"})
    (rf/reg-route :route/article  {:path   "/articles/:id"
                                   :params [:map [:id :string]]
                                   :scroll :restore})
    (rf/reg-route :route/profile  {:path   "/profile"
                                   :scroll false})

    (let [calls (atom [])]
      ;; Override the spec's :platforms #{:client} default for the JVM
      ;; test — re-register :rf.nav/scroll on both server+client so the
      ;; do-fx interpreter actually invokes our capture.
      (rf/reg-fx :rf.nav/scroll
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj args)))
      ;; :rf.nav/push-url is :platforms #{:client} by default; suppress on
      ;; the JVM the same way the other routing tests do.
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))

      ;; 1. Forward navigation to a route with no :scroll metadata —
      ;;    default :top.
      (rf/dispatch-sync [:rf.route/navigate :route/articles])
      (is (= 1 (count @calls)) "navigate emits exactly one :rf.nav/scroll fx")
      (let [a (first @calls)]
        (is (= :top (:strategy a))
            "default forward strategy is :top")
        (is (= {:id :route/articles} (:to a))
            ":to descriptor identifies the destination"))

      ;; 2. Navigate to a route with :scroll :restore — strategy carries.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate :route/article {:id "intro"}])
      (let [a (first @calls)]
        (is (= :restore (:strategy a))
            "route's :scroll :restore wins over the implicit :top default")
        (is (= {:id :route/article :params {:id "intro"}} (:to a))
            ":to carries id + params")
        (is (= {:id :route/articles} (:from a))
            ":from is the previous route slice"))

      ;; 3. Per-call :scroll override in opts trumps route metadata.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate :route/article {:id "two"}
                         {:scroll :preserve}])
      (is (= :preserve (-> @calls first :strategy))
          "opts :scroll wins over route metadata")

      ;; 4. :scroll false on the route suppresses the fx entirely.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate :route/profile])
      (is (empty? @calls)
          ":scroll false on the route suppresses the :rf.nav/scroll fx")

      ;; 5. :rf.route/transitioned (URL-driven) also emits the fx — default :top.
      ;; Land on "/" (route/home, no :scroll meta) so the NEXT step's
      ;; handle-url-change to "/articles" is a genuine navigation, not a
      ;; rule-3 identical no-op (Spec 012 §Per-route data loading rule 3).
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/transitioned "/"])
      (is (= :top (-> @calls first :strategy))
          ":rf.route/transitioned emits :rf.nav/scroll with default :top")

      ;; 6. :rf.route/handle-url-change (popstate / initial) defaults to
      ;;    :restore — the saved position trumps a forward-style :top.
      ;;    "/articles" differs from the current "/" slice → real nav.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/articles"])
      (is (= :restore (-> @calls first :strategy))
          ":rf.route/handle-url-change emits :rf.nav/scroll with default :restore")

      ;; 7. Fragment in URL is forwarded in the fx args.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/transitioned "/articles/intro#section-2"])
      (is (= "section-2" (-> @calls first :fragment))
          "fragment from URL flows into :rf.nav/scroll args"))))

;; ---- Spec 012 §Nested layouts ---------------------------------------------

(deftest routing-nested-layout-parent-link
  (testing ":parent metadata round-trips through reg-route"
    ;; Per Spec 012 §Nested layouts: a child route declares :parent
    ;; <route-id> so views can render the layout chain. The :rf.route/chain
    ;; sub is the runtime's enumeration entry point but is not yet
    ;; framework-registered; the registry-level contract — :parent is
    ;; enumerable via handler-meta — IS implemented and is what tooling
    ;; queries. This test pins that registry-level contract.
    (rf/reg-route :route/account             {:path "/account"})
    (rf/reg-route :route/account.settings    {:path   "/account/settings"
                                              :parent :route/account})
    (rf/reg-route :route/account.billing     {:path   "/account/billing"
                                              :parent :route/account})
    (let [settings-meta (rf/handler-meta :route :route/account.settings)
          billing-meta  (rf/handler-meta :route :route/account.billing)
          account-meta  (rf/handler-meta :route :route/account)]
      (is (= :route/account (:parent settings-meta))
          ":route/account.settings carries :parent :route/account")
      (is (= :route/account (:parent billing-meta))
          ":route/account.billing carries :parent :route/account")
      (is (nil? (:parent account-meta))
          "the parent route itself has no :parent (chain root)"))))

;; ---- Spec 012 §Scroll restoration — pure helpers (rf2-1aqz) --------------
;;
;; Per Spec 012 §Scroll restoration and routing.cljc:506/511, the scroll-
;; restoration helpers are pure: `lookup-scroll-position` reads from a
;; db value, `save-scroll-position` returns a db value with the saved
;; position assoc'd in. Per Spec 012 §Multi-frame routing the saved-
;; position map lives at `[:rf/runtime :routing :scroll-positions]` INSIDE each
;; frame's app-db (rf2-3ib8h + rf2-eguy4: a sibling key under
;; `[:rf/runtime :routing ...]`, not nested under the route slice) — so
;; per-frame isolation is achieved by routing the helpers through the
;; appropriate frame's db value.
;;
;; These tests pin the helper round-trip directly so a regression in
;; either fn surfaces without going through the navigate flow's scroll fx.

(deftest scroll-position-lookup-after-save
  (testing "save-scroll-position then lookup-scroll-position round-trips
            the saved [x y] for the same url"
    (let [db0 {}
          db1 (routing/save-scroll-position db0 "/articles"  [0 250])
          db2 (routing/save-scroll-position db1 "/dashboard" [10 800])]
      (is (= [0 250]  (routing/lookup-scroll-position db2 "/articles"))
          "saved position for /articles is retrievable")
      (is (= [10 800] (routing/lookup-scroll-position db2 "/dashboard"))
          "saved position for /dashboard is retrievable; URLs are isolated")
      (is (nil? (routing/lookup-scroll-position db2 "/unsaved"))
          "an unseen url returns nil — no false positives"))))

(deftest scroll-position-overwrites-on-resave
  (testing "save-scroll-position over an existing url replaces the saved value"
    (let [db1 (routing/save-scroll-position {}  "/page" [0 100])
          db2 (routing/save-scroll-position db1 "/page" [0 999])]
      (is (= [0 999] (routing/lookup-scroll-position db2 "/page"))
          "second save overwrites the first under the same url"))))

(deftest scroll-position-per-frame-isolation
  (testing "save-scroll-position is per-frame — the helpers thread through
            each frame's own db, so a position saved under :rf/default
            is invisible from another frame's db value"
    ;; Simulate two frames' independent app-dbs: each is its own map.
    ;; The helpers operate on db values, so isolation is achieved by
    ;; passing the right frame's db.
    (let [frame-A-db (routing/save-scroll-position {} "/shared-url" [0 250])
          frame-B-db (routing/save-scroll-position {} "/shared-url" [0 999])]
      (is (= [0 250] (routing/lookup-scroll-position frame-A-db "/shared-url"))
          "frame A's db carries A's saved position")
      (is (= [0 999] (routing/lookup-scroll-position frame-B-db "/shared-url"))
          "frame B's db carries B's saved position — values are not shared")
      (is (nil? (routing/lookup-scroll-position {} "/shared-url"))
          "a fresh db (third frame, never-saved) returns nil for the same url"))))

(deftest scroll-position-storage-shape
  (testing "save-scroll-position assoc's into [:rf/runtime :routing :scroll-positions <url>]"
    ;; Pin the storage shape. Tools and migrations inspect this path
    ;; directly; pinning here keeps the contract stable.
    (let [db1 (routing/save-scroll-position {} "/x" [5 50])]
      (is (= [5 50] (get-in db1 [:rf/runtime :routing :scroll-positions "/x"]))
          "the saved [x y] lives at [:rf/runtime :routing :scroll-positions <url>] in the db"))))

;; ---- rf2-z2k4k: LRU cap on scroll-positions -------------------------------
;;
;; Per audit A12: long sessions deep-linking through `/articles/:id`-style
;; routes can grow [:rf/runtime :routing :scroll-positions] unboundedly. The map is
;; LRU-bounded at `routing/scroll-positions-cap` (50). Re-saving a known
;; url promotes it to most-recent; saves past the cap evict the LRU entry.

(deftest scroll-position-lru-eviction-past-cap
  (testing "save-scroll-position evicts the least-recently-used url
            once the cap is exceeded; the cap is a soft upper bound, not
            a strict per-call limit"
    ;; Hammer 60 distinct urls. Cap is 50, so the first 10 should be gone
    ;; and the last 50 should remain — in insertion order.
    (let [db (reduce (fn [db i] (routing/save-scroll-position db (str "/u" i) [i i]))
                     {}
                     (range 60))
          positions (get-in db [:rf/runtime :routing :scroll-positions])]
      (is (= 50 (count positions))
          "exactly 50 entries remain — the cap holds")
      (is (every? nil? (map #(routing/lookup-scroll-position db (str "/u" %))
                            (range 10)))
          "the first 10 (LRU) urls are evicted")
      (is (every? some? (map #(routing/lookup-scroll-position db (str "/u" %))
                             (range 10 60)))
          "the most-recently-saved 50 urls all survive")))

  (testing "re-saving an existing url promotes it to most-recent — it survives
            an eviction wave that would otherwise drop it"
    ;; Insert 50 urls (fills cap). Promote /u0 by re-saving. Insert one more.
    ;; /u1 (now the LRU) should evict; /u0 should survive.
    (let [db0 (reduce (fn [db i] (routing/save-scroll-position db (str "/u" i) [i i]))
                      {}
                      (range 50))
          db1 (routing/save-scroll-position db0 "/u0" [999 999])  ;; promote
          db2 (routing/save-scroll-position db1 "/u50" [50 50])]  ;; force evict
      (is (= [999 999] (routing/lookup-scroll-position db2 "/u0"))
          "the re-saved url survives and carries its new value")
      (is (nil? (routing/lookup-scroll-position db2 "/u1"))
          "/u1 — the new LRU after the promotion — was evicted instead")
      (is (= 50 (count (get-in db2 [:rf/runtime :routing :scroll-positions])))
          "cap is still 50"))))

;; ---- rf2-hra3: route-url missing-required-param raises clear error -------
;;
;; Per test-coverage-review-2026-05-12 P3-14. Hardening: ensure
;; `route-url` doesn't silently emit a malformed URL when a required
;; path param is absent.

(deftest route-url-missing-required-path-param-throws
  (testing "route-url with a missing required :id path param raises
            :rf.error/missing-route-param"
    (rf/reg-route :route/article {:path "/articles/:id"})
    ;; No :id supplied — must throw the structured error.
    (let [ex (try
               (routing/route-url :route/article {})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "route-url with absent required param raises")
      (is (= ":rf.error/missing-route-param" (ex-message ex))
          "the structured error id is :rf.error/missing-route-param")
      (let [data (ex-data ex)]
        (is (= :id (:param data))
            "ex-data names the absent param")
        (is (= :route/article (:route-id data))
            "ex-data names the route-id"))))

  (testing "supplying nil for the param has the same shape as omitting it"
    (rf/reg-route :route/article2 {:path "/articles/:id"})
    (let [ex (try
               (routing/route-url :route/article2 {:id nil})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "nil :id behaves like an absent :id — same structured error")
      (is (= ":rf.error/missing-route-param" (ex-message ex)))))

  (testing "providing the param works — sanity check the happy path"
    (rf/reg-route :route/article3 {:path "/articles/:id"})
    (is (= "/articles/intro"
           (routing/route-url :route/article3 {:id "intro"}))
        "supplying the required param renders the URL"))

  (testing "splat params raise the same structured error when absent"
    (rf/reg-route :route/files {:path "/files/*path"})
    (let [ex (try
               (routing/route-url :route/files {})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "absent splat raises")
      (is (= ":rf.error/missing-route-param" (ex-message ex))
          "splat absence uses the same structured error id"))))

;; ---- rf2-6iam6: falsy path params (false / 0 / "") are legitimate values
;;
;; Per Spec 012 §Bidirectional URL ↔ params an absent or nil path param
;; raises :rf.error/missing-route-param; a present-but-falsy value is a
;; legitimate segment that must round-trip through url-encode. The pre-fix
;; `(or v throw)` form mis-classified false / 0 / "" as missing.

(deftest route-url-accepts-falsy-path-params
  (testing "route-url accepts false, 0, and empty-string path params —
            present-but-falsy is NOT the same as absent"
    (rf/reg-route :route/page {:path "/page/:flag"})
    (is (= "/page/false"
           (routing/route-url :route/page {:flag false}))
        "false renders as the literal segment \"false\"")
    (is (= "/page/0"
           (routing/route-url :route/page {:flag 0}))
        "0 renders as the literal segment \"0\""))

  (testing "empty-string path params encode to an empty segment (no throw)"
    (rf/reg-route :route/slug {:path "/articles/:slug"})
    ;; The encoded form of "" is "" — round-trippable, but renders as
    ;; "/articles/" which the caller is free to detect; the routing
    ;; helper does not pre-empt that decision.
    (is (= "/articles/"
           (routing/route-url :route/slug {:slug ""}))
        "\"\" path param renders as an empty segment, not a thrown error")))

(deftest route-url-no-such-route-throws
  (testing "route-url against an unregistered route id raises
            :rf.error/no-such-route"
    (let [ex (try
               (routing/route-url :route/no-such-route {})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= ":rf.error/no-such-route" (ex-message ex))
          ":rf.error/no-such-route is the structured error for an unregistered id"))))

;; ---- rf2-u1na: match-url malformed-input edge cases ----------------------
;;
;; Per test-coverage-review-2026-05-12 P3-13. Hardening for the URL parser.

(deftest match-url-empty-string
  (testing "match-url \"\" matches the root '/' route (the compiled regex
            is `^/?$`, so the leading slash is optional)"
    (rf/reg-route :route/home {:path "/"})
    ;; Pin the actual behaviour: the compiled regex treats the leading
    ;; slash as optional, so both "" and "/" match the root.
    (let [m (routing/match-url "")]
      (is (some? m)
          "empty string matches the root '/' route (leading slash optional)")
      (is (= :route/home (:route-id m))))))

(deftest match-url-missing-leading-slash
  (testing "URLs without a leading slash STILL match the corresponding
            route (the compiled regex is `^/?...`, leading slash optional).
            Documenting the actual lenient behaviour."
    (rf/reg-route :route/home {:path "/home"})
    (is (some? (routing/match-url "/home"))
        "the canonical /home matches")
    (let [m (routing/match-url "home")]
      (is (some? m)
          "no-leading-slash also matches — the compiled regex permits it")
      (is (= :route/home (:route-id m))))))

(deftest match-url-repeated-query-key-last-wins
  (testing "repeated query keys — last value wins (the parser's array-map
            reduce assoc's left-to-right, so a later key replaces an
            earlier one). rf2-5ifai: the route declares no :query
            vocabulary, so the key stays a string."
    (rf/reg-route :route/search {:path "/search"})
    (let [m (routing/match-url "/search?x=1&x=2")]
      (is (some? m) "the route matches")
      (is (= "2" (get-in m [:query "x"]))
          "repeated key — last value wins (left-to-right reduce)"))))

(deftest match-url-unterminated-query
  (testing "a query pair without an '=' value parses as an empty string.
            rf/2-5ifai: the route declares no :query vocabulary, so the
            key stays a string."
    (rf/reg-route :route/search {:path "/search"})
    (let [m (routing/match-url "/search?foo=")]
      (is (some? m) "the route still matches")
      (is (= "" (get-in m [:query "foo"]))
          "an unterminated `?foo=` parses to (get :query \"foo\") \"\""))))

(deftest match-url-trailing-slash-normalizes
  (testing "trailing-slash equivalence is implicit — /foo and /foo/
            resolve to the same route per Spec 012"
    (rf/reg-route :route/foo {:path "/foo"})
    (is (some? (routing/match-url "/foo"))
        "the canonical /foo matches")
    (let [canonical (routing/match-url "/foo")
          trailing  (routing/match-url "/foo/")]
      (is (= (:route-id canonical) (:route-id trailing))
          "/foo/ resolves to the same route-id as /foo")
      (is (= (:params canonical) (:params trailing))
          "/foo/ carries the same path params as /foo"))))

(deftest match-url-url-encoded-path-round-trip
  (testing "URL-encoded characters in the path round-trip with route-url"
    (rf/reg-route :route/articles {:path "/articles/:slug"})
    ;; A slug with characters that get percent-encoded.
    (let [slug   "hello world"
          built  (routing/route-url :route/articles {:slug slug})
          parsed (routing/match-url built)]
      (is (some? built)
          "route-url built a URL for the encoded slug")
      ;; The built URL must NOT contain a raw space.
      (is (not (clojure.string/includes? built " "))
          "the built URL contains no raw space — encoding was applied")
      (is (some? parsed) "match-url parses the built URL")
      ;; The decoded slug should round-trip back.
      (is (= {:slug slug}
             (:params parsed))
          "the slug round-trips through route-url → match-url"))))

;; ---- rf2-wbvme + rf2-4ic0f: malformed percent-encoding fails closed -------
;;
;; Per Spec 012 §Routing failure semantics. `URLDecoder/decode` (JVM) and
;; `decodeURIComponent` (CLJS) throw on malformed `%` sequences. Hostile
;; URLs, partner integrations with broken escaping, and back-button to a
;; malformed link must produce a route-miss (404 path), never a request-
;; handler crash.
;;
;; Contract (rf2-4ic0f): uniform fail-closed across path / query /
;; fragment — `match-url` returns nil regardless of which portion is
;; malformed. The runtime refuses any URL whose %-encoding cannot be
;; uniformly decoded.
;;
;; Reproducer from the security audit: `(routing/match-url "/search?x=%")`
;; resolves to nil (route-miss → `:rf.route/not-found` with
;; `:reason :malformed-url` at `:rf.route/transitioned`).

(deftest match-url-malformed-percent-in-path-is-route-miss
  (testing "a bare `%` in the path returns nil (route-miss), does not throw"
    (rf/reg-route :route/articles {:path "/articles/:slug"})
    (is (nil? (routing/match-url "/articles/%"))
        "/articles/% is a route-miss, not an exception")
    (is (nil? (routing/match-url "/articles/x%a"))
        "/articles/x%a (incomplete pair) is a route-miss")
    (is (nil? (routing/match-url "/articles/x%XX"))
        "/articles/x%XX (non-hex pair) is a route-miss"))
  (testing "bare-`%` URL with no path-pattern match also returns nil"
    ;; No route registered; even a malformed URL must not throw.
    (is (nil? (routing/match-url "/%"))
        "/% with no matching route is a route-miss, not an exception")))

(deftest match-url-malformed-percent-in-query-fails-closed
  (testing "rf2-4ic0f: malformed %-encoding in a query VALUE fails closed —
            the WHOLE URL is a route-miss, not just the bad pair"
    (rf/reg-route :route/search {:path "/search"})
    (is (nil? (routing/match-url "/search?x=%"))
        "single-pair malformed query → route-miss, no partial slice")
    (is (nil? (routing/match-url "/search?good=1&bad=%&also=2"))
        "good neighbours do NOT keep the URL routable when one pair is malformed"))
  (testing "rf2-4ic0f: malformed %-encoding in a query KEY fails closed"
    (rf/reg-route :route/search2 {:path "/search2"})
    (is (nil? (routing/match-url "/search2?%=v"))
        "malformed key → route-miss, not a dropped pair")
    (is (nil? (routing/match-url "/search2?ok=1&%=bad&also=2"))
        "bad-key with good neighbours still fails the whole URL")))

(deftest match-url-malformed-percent-in-fragment-fails-closed
  (testing "rf2-4ic0f: malformed %-encoding in the `#fragment` portion
            fails closed — `match-url` returns nil"
    (rf/reg-route :route/page {:path "/page"})
    (is (nil? (routing/match-url "/page#%"))
        "bare `%` in fragment → route-miss")
    (is (nil? (routing/match-url "/page#good%a"))
        "incomplete %-pair in fragment → route-miss"))
  (testing "well-formed and empty fragments are unaffected"
    (rf/reg-route :route/page2 {:path "/page2"})
    (let [m (routing/match-url "/page2#section-1")]
      (is (some? m) "well-formed fragment matches")
      (is (= "section-1" (:fragment m))
          "well-formed fragment surfaces decoded into the slice"))
    (let [m (routing/match-url "/page2#hello%20world")]
      (is (some? m) "well-formed %-encoded fragment matches")
      (is (= "hello world" (:fragment m))
          "well-formed %-encoded fragment is decoded into the slice"))
    (let [m (routing/match-url "/page2#")]
      (is (some? m) "bare-trailing-`#` URL matches")
      (is (= "" (:fragment m)) "bare `#` decodes to empty string"))))

(deftest malformed-url?-predicate-discriminates-decode-failures
  (testing "rf2-4ic0f: `malformed-url?` returns true for any URL whose
            %-encoding cannot be uniformly decoded; false otherwise.
            `:rf.route/transitioned` uses this to write `:reason :malformed-url`
            on the `:rf.route/not-found` slice."
    (is (false? (routing/malformed-url? "/")))
    (is (false? (routing/malformed-url? "/articles/intro")))
    (is (false? (routing/malformed-url? "/search?q=clojure&page=2")))
    (is (false? (routing/malformed-url? "/page#section-1")))
    (is (false? (routing/malformed-url? "/page#hello%20world"))
        "well-formed %-encoded fragment is not malformed")
    (is (false? (routing/malformed-url? "/page2#"))
        "bare-trailing-`#` (empty fragment) is not malformed")
    ;; Path
    (is (true? (routing/malformed-url? "/articles/%")))
    (is (true? (routing/malformed-url? "/articles/x%a")))
    ;; Query
    (is (true? (routing/malformed-url? "/search?x=%")))
    (is (true? (routing/malformed-url? "/search?good=1&bad=%")))
    (is (true? (routing/malformed-url? "/search?%=v")))
    ;; Fragment
    (is (true? (routing/malformed-url? "/page#%")))
    (is (true? (routing/malformed-url? "/page#good%a")))))

;; ---- rf2-070jt: match-url :fragment + route-url 4-arity round-trip --------
;;
;; Per Spec 012 §Bidirectional URL ↔ params and §Fragments §Programmatic
;; navigation with fragments. match-url surfaces the URL's `#fragment`
;; portion on its result map; route-url's 4-arity rebuilds the URL with
;; the fragment appended. The two are inverses: a URL parsed with
;; match-url and rebuilt with route-url's 4-arity recovers the original
;; (modulo route-id resolution).

(deftest match-url-returns-fragment-from-url
  (testing "match-url surfaces the URL's `#fragment` as :fragment"
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (let [m (routing/match-url "/docs/routing#scroll-restoration")]
      (is (some? m) "the route matches")
      (is (= :route/docs (:route-id m)))
      (is (= {:page "routing"} (:params m))
          "path params parsed as usual; fragment did not pollute :page")
      (is (= "scroll-restoration" (:fragment m))
          ":fragment carries the URL's #fragment portion"))))

(deftest match-url-fragment-absent-is-nil
  (testing "URLs without a #fragment yield :fragment nil"
    (rf/reg-route :route/home {:path "/home"})
    (let [m (routing/match-url "/home")]
      (is (some? m))
      (is (nil? (:fragment m))
          "absent #fragment → :fragment nil"))))

(deftest match-url-fragment-with-query
  (testing ":fragment is independent of the query string. rf2-5ifai: no
            :query vocabulary declared, so the key stays a string."
    (rf/reg-route :route/search {:path "/search"})
    (let [m (routing/match-url "/search?q=clojure#results")]
      (is (some? m))
      (is (= {"q" "clojure"} (:query m))
          "query parsed without the fragment polluting it")
      (is (= "results" (:fragment m))
          "fragment captured after the query string"))))

(deftest match-url-empty-fragment
  (testing "a bare trailing '#' yields :fragment \"\""
    (rf/reg-route :route/page {:path "/page"})
    (let [m (routing/match-url "/page#")]
      (is (some? m))
      (is (= "" (:fragment m))
          "URL ending with bare '#' yields empty-string fragment"))))

(deftest match-url-no-rank-in-result
  (testing "match-url result does NOT carry the internal :rank key"
    (rf/reg-route :route/home {:path "/"})
    (let [m (routing/match-url "/")]
      (is (some? m))
      (is (not (contains? m :rank))
          ":rank is internal routing-table state; not part of the
          documented match-url result shape"))))

(deftest route-url-4-arity-appends-fragment
  (testing "the 4-arity form appends `#fragment` when non-nil and non-empty"
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (is (= "/docs/routing#scroll-restoration"
           (routing/route-url :route/docs {:page "routing"} {} "scroll-restoration"))
        "fragment is appended after the path")
    (is (= "/docs/routing?lang=en#scroll-restoration"
           (routing/route-url :route/docs {:page "routing"} {:lang "en"} "scroll-restoration"))
        "fragment is appended after the query string"))

  (testing "nil and empty-string fragments are not appended"
    (rf/reg-route :route/docs2 {:path "/docs/:page"})
    (is (= "/docs/routing"
           (routing/route-url :route/docs2 {:page "routing"} {} nil))
        "nil fragment → no `#` suffix")
    (is (= "/docs/routing"
           (routing/route-url :route/docs2 {:page "routing"} {} ""))
        "empty-string fragment → no `#` suffix"))

  (testing "the 3-arity form delegates to the 4-arity with fragment nil"
    (rf/reg-route :route/docs3 {:path "/docs/:page"})
    (is (= "/docs/routing"
           (routing/route-url :route/docs3 {:page "routing"} {}))
        "3-arity produces the same URL as 4-arity with nil fragment"))

  (testing "the 2-arity form delegates the same way"
    (rf/reg-route :route/docs4 {:path "/docs/:page"})
    (is (= "/docs/routing"
           (routing/route-url :route/docs4 {:page "routing"}))
        "2-arity → no query, no fragment")))

;; ---- route-url query-string emission -------------------------------------
;;
;; Per Spec 012 §Bidirectional URL ↔ params. The qs builder
;; (routing.cljc:725-731) joins `(name k)=url-encode(v)` pairs with `&`.
;; Coverage elsewhere only hits the single-pair `{:lang "en"}` case
;; (route-url-4-arity-appends-fragment:918), which can't observe pair
;; ORDERING or query-VALUE percent-encoding. These are the two behaviours
;; the multi-pair `&`-join + per-value `url-encode` exist for.
(deftest route-url-query-string-emission
  (testing "multi-pair query: pairs joined with `&` in insertion order"
    (rf/reg-route :route/list {:path "/list"})
    (is (= "/list?a=1&b=2"
           (routing/route-url :route/list {} (array-map :a "1" :b "2")))
        "two query pairs join with `&`, preserving insertion order")
    (is (= "/list?x=1&y=2&z=3"
           (routing/route-url :route/list {} (array-map :x "1" :y "2" :z "3")))
        "three query pairs join with `&` in insertion order"))

  (testing "query VALUES are percent-encoded (encodeURIComponent semantics)"
    (rf/reg-route :route/search {:path "/search"})
    (is (= "/search?q=x%20y"
           (routing/route-url :route/search {} {:q "x y"}))
        "a space in a query value encodes to %20 (not '+')")
    (is (= "/search?a=1&b=x%20y"
           (routing/route-url :route/search {} (array-map :a "1" :b "x y")))
        "multi-pair ordering AND value %-encoding together")
    (is (= "/search?filter=a%26b%3Dc"
           (routing/route-url :route/search {} {:filter "a&b=c"}))
        "`&` and `=` in a value are encoded so they cannot inject extra pairs"))

  (testing "query KEYS are percent-encoded too"
    (rf/reg-route :route/k {:path "/k"})
    (is (= "/k?a%20b=v"
           (routing/route-url :route/k {} {(keyword "a b") "v"}))
        "a space in a query key encodes to %20")))

(deftest route-url-drops-nil-query-values-keeps-falsy
  (testing "rf2-ee38b.8: a nil-valued query key is ELIDED from the URL
            (not emitted as a bare `?key=`), while present-but-falsy
            values (false / 0 / \"\") round-trip"
    (rf/reg-route :route/list {:path "/list"})
    (is (= "/list"
           (routing/route-url :route/list {} {:page nil}))
        "a sole nil-valued query key is dropped → no query string at all")
    (is (= "/list?a=1"
           (routing/route-url :route/list {} (array-map :a "1" :b nil)))
        "nil-valued keys are dropped; the rest of the query survives")
    (is (= "/list?flag=false"
           (routing/route-url :route/list {} {:flag false}))
        "present-but-falsy `false` is a legitimate value and round-trips")
    (is (= "/list?n=0"
           (routing/route-url :route/list {} {:n 0}))
        "`0` round-trips (falsy, not absent)")
    (is (= "/list?s="
           (routing/route-url :route/list {} {:s ""}))
        "empty-string is a present value → `?s=` (distinct from nil/absent)")))

(deftest match-url-route-url-round-trip-with-fragment
  (testing "URL → match-url → route-url 4-arity → URL recovers the original
            (the full bidirectional contract including #fragment). rf2-5ifai:
            unknown query keys stay as strings; route-url accepts both
            keyword + string keys via `(name k)` so the round-trip holds."
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (let [original "/docs/routing?lang=en#scroll-restoration"
          parsed   (routing/match-url original)
          rebuilt  (routing/route-url (:route-id parsed)
                                      (:params parsed)
                                      (:query parsed)
                                      (:fragment parsed))]
      (is (= :route/docs (:route-id parsed)))
      (is (= {:page "routing"} (:params parsed)))
      (is (= {"lang" "en"}     (:query parsed)))
      (is (= "scroll-restoration" (:fragment parsed)))
      (is (= original rebuilt)
          "the rebuilt URL equals the original — fragment round-trips"))))

(deftest navigate-pushes-url-with-fragment
  (testing ":rf.route/navigate with :fragment opt pushes the URL with #fragment appended"
    ;; Per Spec 012 §Fragments §Programmatic navigation with fragments:
    ;; `[:rf.route/navigate :route/docs {:page "routing"} {:fragment "x"}]`
    ;; pushes "/docs/routing#x" via :rf.nav/push-url. The 4-arity route-url
    ;; is the canonical builder; the navigate handler routes opts → 4-arity.
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate
                         :route/docs
                         {:page "routing"}
                         {:fragment "scroll-restoration"}])
      (is (= ["/docs/routing#scroll-restoration"] @pushed)
          ":rf.nav/push-url received the URL WITH the appended #fragment"))))

(deftest navigate-url-form-preserves-fragment
  (testing ":rf.route/navigate with URL-string target preserves the URL's
            embedded #fragment in the pushed URL"
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate
                         {:url "/docs/routing#scroll-restoration"}])
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
;; `:rf.route/transitioned` path that `url-changed-unmatched-url-routes-to-
;; not-found` covers) was unpinned.

(deftest navigate-url-form-unmatched-routes-to-not-found
  (testing ":rf.route/navigate with an unmatched {:url ...} target lands on
            :rf.route/not-found carrying {:url url} in :params, and pushes
            the REQUESTED url (NOT the not-found route's literal /404) so
            the address bar keeps the URL the caller aimed at — consistent
            with the URL-driven not-found path (rf2-0zr2o, Option A)"
    (rf/reg-route :route/home {:path "/"})
    (rf/reg-route :rf.route/not-found {:path "/404"})
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate {:url "/no/such/path"}])
      (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
        (is (= :rf.route/not-found (:id slice))
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
    (rf/reg-route :route/home {:path "/"})
    (let [pushed (atom [])
          traces (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      ;; Land on home so the not-found commit displaces a known slice.
      (rf/dispatch-sync [:rf.route/navigate :route/home])
      (reset! pushed [])
      (rf/register-listener! ::nav-nf-warn (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate {:url "/no/such/path"}])
      (rf/unregister-listener! ::nav-nf-warn)
      (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
        (is (= :rf.route/not-found (:id slice))
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
    (rf/reg-route :route/home {:path "/"})
    ;; The not-found route's literal :path is /404; the fix must NOT push it.
    (rf/reg-route :rf.route/not-found {:path "/404"})
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))

      ;; ---- PROGRAMMATIC miss: pushes the requested url, not /404 ----
      (rf/dispatch-sync [:rf.route/navigate {:url "/no/such/path"}])
      (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
        (is (= :rf.route/not-found (:id slice))
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
      (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
        (is (= :rf.route/not-found (:id slice))
            "URL-driven miss also renders the not-found view")
        (is (= {:url "/another/miss"} (:params slice))
            "URL-driven miss carries the requested url in :params"))
      (is (empty? @pushed)
          "URL-driven miss emits NO push — the URL already changed via
           link/popstate, so the address bar already shows the requested url
           (this path is unchanged by rf2-0zr2o)"))))

;; ---- pending-nav protocol: continue / cancel with NO pending slot --------
;;
;; `pending-nav-continue-and-cancel-require-matching-id` covers a WRONG id
;; while a pending nav EXISTS. The complementary case — dispatching continue
;; / cancel when the slot is empty (a stray event, a double-cancel) — must be
;; a clean no-op: continue's `(and pending ...)` guard and cancel's
;; `(= pn-id <nil id>)` both fall through to `{}`.

(deftest continue-and-cancel-no-op-when-no-pending-nav
  (testing ":rf.route/cancel and :rf.route/continue are safe no-ops when
            :rf/pending-navigation is empty (no slot to resolve)"
    (rf/reg-route :route/home {:path "/"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Land on home; no navigation is pending.
    (rf/dispatch-sync [:rf.route/navigate :route/home])
    (is (nil? (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :pending-navigation]))
        "no pending navigation to begin with")
    (let [before (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
      ;; Stray cancel — nothing to clear.
      (rf/dispatch-sync [:rf.route/cancel "phantom-id"])
      (is (nil? (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :pending-navigation]))
          "cancel with no pending slot leaves the slot nil")
      (is (= before (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current]))
          "cancel with no pending slot does not perturb the route slice")
      ;; Stray continue — no original event to re-issue.
      (rf/dispatch-sync [:rf.route/continue "phantom-id"])
      (is (= before (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current]))
          "continue with no pending slot does not navigate")
      (is (= :route/home (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current :id]))
          "the active route stays put"))))

;; ---- scroll-strategy resolution precedence (resolve-scroll-strategy) -----
;;
;; `routing-scroll-fx-emitted-on-navigate` covers opts `:scroll :preserve`
;; winning over meta `:restore`, and meta `:scroll false` suppressing. The
;; UNcovered precedence edge is the asymmetric pair: an opts `:scroll` value
;; must win over a route whose meta declares `:scroll false`
;; (`(some? from-opts)` short-circuits BEFORE the `(false? from-meta)`
;; suppression branch), AND an opts `:scroll false` must suppress even when
;; the route's meta declares a concrete strategy. Map-form (host-extensible)
;; strategies must pass through verbatim.

(deftest scroll-strategy-opts-override-precedence
  (testing "opts :scroll value WINS over a route's :scroll false (the
            per-call override short-circuits before the meta-false
            suppression branch)"
    ;; Two distinct :scroll false routes so each assertion navigates to a
    ;; FRESH target — a second navigate to the same id/params would be a
    ;; Spec 012 rule-3 no-op (no scroll fx) and mask the precedence result.
    (rf/reg-route :route/silent  {:path   "/silent"
                                  :scroll false})
    (rf/reg-route :route/silent2 {:path   "/silent2"
                                  :scroll false})
    (let [calls (atom [])]
      (rf/reg-fx :rf.nav/scroll
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj args)))
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      ;; Without an opts override the route's :scroll false suppresses.
      (rf/dispatch-sync [:rf.route/navigate :route/silent])
      (is (empty? @calls)
          "baseline: route :scroll false suppresses the fx")
      ;; A per-call :scroll :top opt resurrects the fx on a fresh target
      ;; (override wins over the route's :scroll false).
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate :route/silent2 {} {:scroll :top}])
      (is (= :top (-> @calls first :strategy))
          "opts :scroll :top overrides the route's :scroll false → fx emits")))

  (testing "opts :scroll false suppresses even when the route declares a
            concrete :scroll strategy"
    (rf/reg-route :route/loud {:path   "/loud"
                               :scroll :restore})
    (let [calls (atom [])]
      (rf/reg-fx :rf.nav/scroll
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj args)))
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/navigate :route/loud {} {:scroll false}])
      (is (empty? @calls)
          "opts :scroll false suppresses despite the route's :scroll :restore")))

  (testing "map-form (host-extensible) scroll strategies pass through to the
            fx args verbatim — the resolver does not coerce or drop them"
    (rf/reg-route :route/custom {:path   "/custom"
                                 :scroll {:behavior :smooth :block :center}})
    (let [calls (atom [])]
      (rf/reg-fx :rf.nav/scroll
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj args)))
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/navigate :route/custom])
      (is (= {:behavior :smooth :block :center} (-> @calls first :strategy))
          "a map-form :scroll strategy flows into :rf.nav/scroll's :strategy arg unchanged"))))

;; ---- route-url ignores path-params not named by the pattern --------------
;;
;; route-url's emitter consumes only the `:`/`*` segments it walks in the
;; pattern. Extra keys in path-params (a common call-site over-supply, e.g.
;; passing the whole slice's :params back through) must be silently ignored,
;; not leaked into the URL. Pinned here so a future emitter refactor can't
;; start round-tripping stray keys into the path.

(deftest route-url-ignores-extra-path-params
  (testing "path-params keys not named by the route pattern are ignored"
    (rf/reg-route :route/article {:path "/articles/:id"})
    (is (= "/articles/intro"
           (routing/route-url :route/article {:id "intro" :stray "ignored" :extra 99}))
        "only the pattern's :id segment is emitted; :stray / :extra are dropped"))

  (testing "a route with no path params ignores any supplied path-params"
    (rf/reg-route :route/home {:path "/"})
    (is (= "/"
           (routing/route-url :route/home {:anything "here"}))
        "a param-less pattern emits its literal path regardless of supplied params")))

;; ---- rf2-ug2m1: schema validation in match-url + route-url ---------------
;;
;; Per Spec 012 §Bidirectional URL ↔ params and §Param validation at the
;; call site. Pre-fix match-url hardcoded :validation-failed? false and
;; route-url never validated; caller bugs (`{:id "not-a-uuid"}` against
;; `[:map [:id :uuid]]`) silently round-tripped as malformed URLs.

(defn- with-stub-validator
  "Install a tiny stub validator + explainer that interprets schemas as
  Clojure predicates `(fn [v] truthy?)`. Lets these tests assert the
  validation path without dragging in Malli / spec.alpha. Returns a
  cleanup fn for the caller to invoke."
  []
  (let [prev-v   @schemas/validator-fn
        prev-e   @schemas/explainer-fn
        validate (fn [schema value] (boolean (schema value)))
        explain  (fn [_schema value] {:reason :stub-explainer :value value})]
    (schemas/set-schema-fns! {:validate validate :explain explain})
    (fn [] (schemas/set-schema-fns! {:validate prev-v :explain prev-e}))))

(deftest match-url-flags-validation-failure
  (testing "match-url surfaces :validation-failed? + :validation-error
            when the route declares :params and the parsed value rejects"
    (let [restore (with-stub-validator)]
      (try
        ;; A schema that requires :id to be a non-empty string starting "a".
        (rf/reg-route :route/article
                      {:path   "/articles/:id"
                       :params (fn [{:keys [id]}] (clojure.string/starts-with? (or id "") "a"))})
        (let [m (routing/match-url "/articles/zoo")]
          (is (some? m) "the route still matches structurally")
          (is (true? (:validation-failed? m))
              ":validation-failed? flips when the schema rejects")
          (is (some? (:validation-error m))
              ":validation-error carries the explainer payload"))
        (let [m2 (routing/match-url "/articles/aardvark")]
          (is (false? (:validation-failed? m2))
              "a conforming value clears the flag")
          (is (nil? (:validation-error m2))
              "no error key when conformant"))
        (finally (restore))))))

(deftest route-url-throws-on-invalid-path-params
  (testing "route-url throws :rf.error/route-url-validation when
            path-params don't conform to the route's :params schema"
    (let [restore (with-stub-validator)]
      (try
        (rf/reg-route :route/article
                      {:path   "/articles/:id"
                       :params (fn [{:keys [id]}] (clojure.string/starts-with? (or id "") "a"))})
        (let [ex (try (routing/route-url :route/article {:id "zoo"})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex)
              "non-conformant path-params raise")
          (is (= ":rf.error/route-url-validation" (ex-message ex))
              "structured error id is :rf.error/route-url-validation")
          (let [data (ex-data ex)]
            (is (= :route/article (:route-id data)))
            (is (= :params (:slot data)))
            (is (= {:id "zoo"} (:value data)))
            (is (some? (:error data))
                "ex-data carries the explainer payload under :error")))
        ;; Conformant path-params round-trip happily.
        (is (= "/articles/aardvark"
               (routing/route-url :route/article {:id "aardvark"}))
            "conformant params still produce a URL")
        (finally (restore))))))

(deftest url-changed-validation-fail-routes-to-not-found-with-reason
  (testing ":rf.route/transitioned for a structurally-matched URL whose params
            fail schema validation routes to :rf.route/not-found with
            `:reason :validation` in :params (Spec 012 §Param validation)"
    (let [restore (with-stub-validator)]
      (try
        (rf/reg-route :route/article
                      {:path   "/articles/:id"
                       :params (fn [{:keys [id]}] (clojure.string/starts-with? (or id "") "a"))})
        (rf/reg-route :rf.route/not-found {:path "/404"})
        (rf/reg-fx :rf.nav/push-url
                   {:platforms #{:server :client}}
                   (fn [_ _] nil))
        (rf/dispatch-sync [:rf.route/transitioned "/articles/zoo"])
        (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
          (is (= :rf.route/not-found (:id slice))
              "validation failure routes to :rf.route/not-found")
          (is (= "/articles/zoo" (:url (:params slice)))
              "params carries the URL")
          (is (= :validation (:reason (:params slice)))
              "params carries :reason :validation (distinguishes from a no-match miss)"))
        (finally (restore))))))

(deftest route-url-throws-on-invalid-query-params
  (testing "route-url throws :rf.error/route-url-validation when
            query-params don't conform to the route's :query schema"
    (let [restore (with-stub-validator)]
      (try
        (rf/reg-route :route/search
                      {:path  "/search"
                       :query (fn [m] (and (string? (:q m))
                                           (pos? (count (:q m)))))})
        (let [ex (try (routing/route-url :route/search {} {:q ""})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex)
              "empty :q rejects against the query schema")
          (is (= ":rf.error/route-url-validation" (ex-message ex)))
          (is (= :query (:slot (ex-data ex)))))
        (let [ex (try (routing/route-url :route/search {} {})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex)
              "an empty query map still validates, so required query params reject")
          (is (= :query (:slot (ex-data ex)))))
        (finally (restore))))))

;; ---- rf2-b8ugt: :rf/pending-navigation full slot shape -------------------
;;
;; Per Spec 012 §Navigation blocking — pending-nav protocol and
;; Spec-Schemas.md §:rf/pending-navigation the slot carries
;; `{:id :requested-by-event :requested-url :reason :rejecting-route
;;   :rejecting-guard}`. Tools / dialogs read :rejecting-guard to render
;; meaningful "Discard changes on Editor?" prompts.

(deftest pending-navigation-slot-shape
  (testing ":rf/pending-navigation carries the full Spec-Schemas slot shape"
    (rf/reg-route :editor/article
                  {:path      "/editor/articles/:id"
                   :params    [:map [:id :string]]
                   :can-leave :editor/can-leave?})
    (rf/reg-route :route/cart {:path "/cart"})
    (rf/reg-event-db :editor/dirty (fn [db [_ v]] (assoc-in db [:editor :dirty?] v)))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (let [pending (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :pending-navigation])]
      (is (string? (:id pending))
          ":id is the opaque pending-nav id")
      (is (= "/cart" (:requested-url pending))
          ":requested-url carries the navigation target")
      (is (= :editor/article (:rejecting-route pending))
          ":rejecting-route names the active route at rejection time")
      (is (= :editor/can-leave? (:rejecting-guard pending))
          ":rejecting-guard names the rejecting sub-id (for tooling)")
      (is (= [:rf/url-requested {:url "/cart"}]
             (:requested-by-event pending))
          ":requested-by-event captures the original event vector"))))

(deftest navigation-blocked-trace-carries-rejecting-guard
  (testing ":rf.route/navigation-blocked trace carries :rejecting-guard
            so tooling can flag the rejecting sub-id"
    (rf/reg-route :editor/article
                  {:path      "/editor/articles/:id"
                   :params    [:map [:id :string]]
                   :can-leave :editor/can-leave?})
    (rf/reg-route :route/cart {:path "/cart"})
    (rf/reg-event-db :editor/dirty (fn [db [_ v]] (assoc-in db [:editor :dirty?] v)))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (let [traces (atom [])]
      (rf/register-listener! ::blocked (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
      (rf/unregister-listener! ::blocked)
      (is (some (fn [ev]
                  (and (= :rf.route/navigation-blocked (:operation ev))
                       (= :editor/can-leave? (-> ev :tags :rejecting-guard))))
                @traces)
          "navigation-blocked trace tags include :rejecting-guard"))))

;; ---- rf2-yursn: :rf.route/continue re-issues :rf/url-requested ----------
;;
;; Per Spec 012 §Navigation blocking — pending-nav protocol continue must
;; "re-issue the original navigation request, *bypassing* the leave guard".
;; Pre-fix dispatched :rf.route/transitioned + :rf.nav/push-url directly, skipping
;; the :rf/url-requested policy chain.

(deftest continue-re-issues-via-url-requested-with-bypass
  (testing ":rf.route/continue re-emits :rf/url-requested with
            :bypass-leave-guard? true, running the policy chain"
    (rf/reg-route :editor/article
                  {:path      "/editor/articles/:id"
                   :params    [:map [:id :string]]
                   :can-leave :editor/can-leave?})
    (rf/reg-route :route/cart {:path "/cart"})
    (rf/reg-event-db :editor/dirty (fn [db [_ v]] (assoc-in db [:editor :dirty?] v)))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Land on editor; dirty the form; try to leave; guard blocks.
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (is (some? (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :pending-navigation]))
        "guard rejection set the pending slot")
    ;; CONTINUE — the slot clears AND the navigation completes
    ;; even though :editor/dirty? remains true (bypass flag wins).
    (rf/dispatch-sync [:rf.route/continue "pn-1"])
    (is (nil? (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :pending-navigation]))
        "continue cleared the pending slot")
    (is (= :route/cart
           (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current :id]))
        "continue completed the navigation through :rf/url-requested → :rf.route/transitioned")
    (is (true? (get-in (rf/app-db-value :rf/default) [:editor :dirty?]))
        ":editor/dirty? remains true — bypass flag did NOT run the guard a second time")))

(deftest can-leave-query-vector-blocks-url-requested
  (testing "Spec-shaped :can-leave query vectors are subscribed directly"
    (rf/reg-route :editor/article
                  {:path      "/editor/articles/:id"
                   :params    [:map [:id :string]]
                   :can-leave [:editor/can-leave?]})
    (rf/reg-route :route/cart {:path "/cart"})
    (rf/reg-event-db :editor/dirty (fn [db [_ v]] (assoc-in db [:editor :dirty?] v)))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (let [pending (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :pending-navigation])]
      (is (some? pending)
          "query-vector guard returning false blocks the navigation")
      (is (= :editor/can-leave? (:rejecting-guard pending))
          "pending slot stores the guard id, not the whole query vector")
      (is (= [:editor/can-leave?] (:can-leave (rf/handler-meta :route :editor/article)))
          "route metadata preserves canonical query-vector semantics"))))

(deftest programmatic-navigate-runs-can-leave-guard
  (testing ":rf.route/navigate is guarded by the active route's :can-leave"
    (rf/reg-route :editor/article
                  {:path      "/editor/articles/:id"
                   :params    [:map [:id :string]]
                   :can-leave [:editor/can-leave?]})
    (rf/reg-route :route/cart {:path "/cart"})
    (rf/reg-event-db :editor/dirty (fn [db [_ v]] (assoc-in db [:editor :dirty?] v)))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
      (rf/dispatch-sync [:editor/dirty true])
      (rf/dispatch-sync [:rf.route/navigate :route/cart])
      (let [db (rf/app-db-value :rf/default)]
        (is (some? (get-in db [:rf/runtime :routing :pending-navigation]))
            "programmatic navigation sets pending-navigation when blocked")
        (is (= :editor/article (get-in db [:rf/runtime :routing :current :id]))
            "the active route does not change")
        (is (empty? @pushed)
            "pushState is not requested for a blocked programmatic nav")))))

(deftest handle-url-change-runs-can-leave-guard
  (testing ":rf.route/handle-url-change is guarded by the active route's :can-leave"
    (rf/reg-route :editor/article
                  {:path      "/editor/articles/:id"
                   :params    [:map [:id :string]]
                   :can-leave [:editor/can-leave?]})
    (rf/reg-route :route/cart {:path "/cart"})
    (rf/reg-event-db :editor/dirty (fn [db [_ v]] (assoc-in db [:editor :dirty?] v)))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (rf/dispatch-sync [:rf.route/handle-url-change "/cart"])
    (let [db (rf/app-db-value :rf/default)]
      (is (some? (get-in db [:rf/runtime :routing :pending-navigation]))
          "popstate/initial URL handling sets pending-navigation when blocked")
      (is (= :editor/article (get-in db [:rf/runtime :routing :current :id]))
          "the active route does not change while pending"))))

(deftest pending-nav-continue-and-cancel-require-matching-id
  (testing ":rf.route/continue and :rf.route/cancel ignore stale pending-nav ids"
    (rf/reg-route :editor/article
                  {:path      "/editor/articles/:id"
                   :params    [:map [:id :string]]
                   :can-leave [:editor/can-leave?]})
    (rf/reg-route :route/cart {:path "/cart"})
    (rf/reg-event-db :editor/dirty (fn [db [_ v]] (assoc-in db [:editor :dirty?] v)))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (let [pending-id (get-in (rf/app-db-value :rf/default)
                             [:rf/runtime :routing :pending-navigation :id])]
      (rf/dispatch-sync [:rf.route/cancel "stale-id"])
      (is (= pending-id (get-in (rf/app-db-value :rf/default)
                                [:rf/runtime :routing :pending-navigation :id]))
          "cancel with the wrong id leaves the pending navigation intact")
      (rf/dispatch-sync [:rf.route/continue "stale-id"])
      (is (= :editor/article (get-in (rf/app-db-value :rf/default)
                                     [:rf/runtime :routing :current :id]))
          "continue with the wrong id does not navigate")
      (is (= pending-id (get-in (rf/app-db-value :rf/default)
                                [:rf/runtime :routing :pending-navigation :id]))
          "continue with the wrong id leaves the pending navigation intact")
      (rf/dispatch-sync [:rf.route/cancel pending-id])
      (is (nil? (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :pending-navigation]))
          "cancel with the matching id clears the slot"))))

(deftest url-requested-classifies-external-before-push
  (testing ":rf/url-requested does not pushState or rewrite the route for external URLs"
    (rf/reg-route :route/home {:path "/"})
    (let [pushed (atom [])
          traces (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/transitioned "/"])
      (rf/register-listener! ::external-url (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf/url-requested {:url "https://example.invalid/cart"}])
      (rf/unregister-listener! ::external-url)
      (is (empty? @pushed)
          "external URL is classified before :rf.nav/push-url")
      (is (= :route/home (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current :id]))
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
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [traces (atom [])]
      (rf/register-listener! ::nav-token (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate
                         :route/docs
                         {:page "routing"}
                         {:fragment "scroll-restoration"}])
      (rf/unregister-listener! ::nav-token)
      (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
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
    (rf/reg-route :route/home {:path "/"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/navigate :route/home])
    (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
      (is (nil? (:fragment slice))
          ":fragment is nil when no opt supplied")
      (is (some? (:nav-token slice))
          ":nav-token is always allocated on navigation"))))

;; ---- rf2-d60go: :rf.route/handle-url-change writes the full slice shape -
;;
;; Per Spec 012 §The :rf/route slice and §URL changes are events the slice
;; carries `{:id :params :query :fragment :transition :error :nav-token}`
;; on every URL-driven write. Pre-fix this handler omitted :fragment and
;; :nav-token; the slice diverged in shape from the programmatic-nav path.

(deftest handle-url-change-writes-full-slice-shape
  (testing ":rf.route/handle-url-change writes :fragment and :nav-token
            into the slice (the seven-key canonical shape)"
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; URL with a fragment so we can assert :fragment is populated.
    (rf/dispatch-sync [:rf.route/handle-url-change
                       "/docs/routing#scroll-restoration"])
    (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
      (is (= :route/docs (:id slice))
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
    (rf/reg-route :route/home {:path "/"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [traces (atom [])]
      (rf/register-listener! ::handle-url-token
                             (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/handle-url-change "/"])
      (rf/unregister-listener! ::handle-url-token)
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

(deftest url-changed-unmatched-url-routes-to-not-found
  (testing ":rf.route/transitioned for an unmatched URL writes the
            :rf.route/not-found slice with {:url url} in :params"
    (rf/reg-route :route/home {:path "/"})
    (rf/reg-route :rf.route/not-found {:path "/404"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Land on home first so we have a previous slice to displace.
    (rf/dispatch-sync [:rf.route/transitioned "/"])
    (is (= :route/home (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current :id]))
        "initial nav landed on home")
    ;; Navigate to a URL that matches no registered route.
    (rf/dispatch-sync [:rf.route/transitioned "/this/does/not/exist"])
    (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
      (is (= :rf.route/not-found (:id slice))
          "unmatched URL → slice id becomes :rf.route/not-found")
      (is (= {:url "/this/does/not/exist"} (:params slice))
          "params carries the unmatched URL under :url")
      (is (= :idle (:transition slice))
          "no :on-match on not-found → transition is :idle")
      (is (some? (:nav-token slice))
          "a fresh nav-token is allocated even for not-found navigation"))))

(deftest url-changed-not-found-without-route-registered-warns
  (testing "when :rf.route/not-found is NOT registered, an unmatched URL
            still rewrites the slice AND emits :rf.warning/no-not-found-route"
    (rf/reg-route :route/home {:path "/"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [traces (atom [])]
      (rf/register-listener! ::no-not-found
                             (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/transitioned "/somewhere/unknown"])
      (rf/unregister-listener! ::no-not-found)
      (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
        (is (= :rf.route/not-found (:id slice))
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

(deftest url-changed-malformed-url-routes-to-not-found-with-reason
  (testing ":rf.route/transitioned for a malformed-%-encoded URL writes the
            :rf.route/not-found slice with `:reason :malformed-url`"
    (rf/reg-route :route/home    {:path "/"})
    (rf/reg-route :route/search  {:path "/search"})
    (rf/reg-route :rf.route/not-found {:path "/404"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Path: a bare `%` in a captured segment.
    (rf/dispatch-sync [:rf.route/transitioned "/articles/%"])
    (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
      (is (= :rf.route/not-found (:id slice))
          "malformed path → :rf.route/not-found")
      (is (= {:url "/articles/%" :reason :malformed-url} (:params slice))
          "params carries the URL AND `:reason :malformed-url`"))
    ;; Query value.
    (rf/dispatch-sync [:rf.route/transitioned "/search?x=%"])
    (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
      (is (= :rf.route/not-found (:id slice)) "malformed query value → not-found")
      (is (= :malformed-url (get-in slice [:params :reason]))
          "the malformed-URL reason is on the slice"))
    ;; Query key.
    (rf/dispatch-sync [:rf.route/transitioned "/search?%=v"])
    (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
      (is (= :rf.route/not-found (:id slice)) "malformed query key → not-found")
      (is (= :malformed-url (get-in slice [:params :reason]))))
    ;; Fragment.
    (rf/dispatch-sync [:rf.route/transitioned "/search#%"])
    (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
      (is (= :rf.route/not-found (:id slice)) "malformed fragment → not-found")
      (is (= :malformed-url (get-in slice [:params :reason]))))))

(deftest url-changed-malformed-url-emits-structured-trace
  (testing ":rf.route/transitioned emits :rf.warning/malformed-url alongside the
            standard :rf.error/no-such-handler when the URL is malformed"
    (rf/reg-route :route/home {:path "/"})
    (rf/reg-route :rf.route/not-found {:path "/404"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [traces (atom [])]
      (rf/register-listener! ::malformed-trace
                             (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/transitioned "/articles/%"])
      (rf/unregister-listener! ::malformed-trace)
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

(deftest url-changed-well-formed-url-does-not-emit-malformed-trace
  (testing "the regular happy path emits NO :rf.warning/malformed-url"
    (rf/reg-route :route/home    {:path "/"})
    (rf/reg-route :route/search  {:path "/search"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [traces (atom [])]
      (rf/register-listener! ::no-malformed-trace
                             (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/transitioned "/search?q=clojure"])
      (rf/unregister-listener! ::no-malformed-trace)
      (is (not-any? (fn [ev] (= :rf.warning/malformed-url (:operation ev)))
                    @traces)
          "well-formed URL → no malformed-URL trace"))))

;; ---- rf2-oaj2s: :transition computed from :on-match on URL-driven nav ----
;;
;; Per Spec 012 §URL changes are events the :transition slice key must be
;; :loading while :on-match drains, :idle when nothing to dispatch.
;; Pre-fix the URL-driven handler hardcoded :idle, so URL-driven loaders
;; never observed the :loading state.

(deftest url-changed-transition-loading-when-on-match-fires
  (testing ":rf.route/transitioned sets :transition :loading when the route
            declares :on-match events"
    (rf/reg-event-db :prefs/loaded (fn [db _] (assoc db :prefs/loaded? true)))
    (rf/reg-route :route/cart
                  {:path     "/cart"
                   :on-match [[:prefs/loaded]]})
    (rf/reg-route :route/home {:path "/"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Capture transition mid-drain via a custom interceptor that snapshots
    ;; the slice between the :db write and the :on-match dispatch. The
    ;; cleanest assertion is: a route WITHOUT :on-match observes :idle;
    ;; a route WITH :on-match observes :loading immediately after the
    ;; :db write — we read the slice between the synchronous slice-write
    ;; and the :on-match drain, using a manual two-step pattern.
    ;;
    ;; Simpler observation: register an :on-match handler that captures
    ;; the slice at its dispatch time. Per spec, the slice is written
    ;; FIRST and then :on-match dispatches — so the handler observes
    ;; the new slice's :transition.
    (let [observed (atom nil)]
      (rf/reg-event-db :prefs/loaded
                       (fn [db _]
                         (reset! observed
                                 (get-in db [:rf/runtime :routing :current :transition]))
                         (assoc db :prefs/loaded? true)))
      (rf/dispatch-sync [:rf.route/transitioned "/cart"])
      (is (= :loading @observed)
          ":on-match handler observed :transition :loading mid-drain"))
    ;; Route without :on-match → :idle.
    (rf/dispatch-sync [:rf.route/transitioned "/"])
    (is (= :idle (get-in (rf/app-db-value :rf/default)
                         [:rf/runtime :routing :current :transition]))
        "route with no :on-match — transition stays :idle")))

;; ---- rf2-k72qn: framework subs — fragment, chain, pending-navigation ------
;;
;; Per Spec 012 §Subscriptions the framework ships nine canonical subs over
;; the route slice and pending-nav slot. The six core ones
;; (:rf/route, :rf.route/{id,params,query,transition,error}) are pinned by
;; the bootstrap tests above; the three from rf2-k72qn close out the table.

(deftest sub-rf-route-fragment
  (testing ":rf.route/fragment reads the slice's :fragment"
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Land on a URL with a fragment — the slice carries :fragment "x"
    (rf/dispatch-sync [:rf.route/transitioned "/docs/routing#scroll-restoration"])
    (is (= "scroll-restoration"
           @(rf/subscribe [:rf.route/fragment]))
        ":rf.route/fragment returns the URL's #fragment")
    ;; Land on a URL with no fragment — sub returns nil
    (rf/dispatch-sync [:rf.route/transitioned "/docs/api"])
    (is (nil? @(rf/subscribe [:rf.route/fragment]))
        ":rf.route/fragment returns nil when the URL has no #fragment")))

(deftest sub-rf-route-chain
  (testing ":rf.route/chain returns the :parent-chain [parent-most ... current]"
    (rf/reg-route :route/account             {:path "/account"})
    (rf/reg-route :route/account.settings    {:path   "/account/settings"
                                              :parent :route/account})
    (rf/reg-route :route/account.profile     {:path   "/account/settings/profile"
                                              :parent :route/account.settings})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Land on the deepest leaf — chain walks up to the root.
    (rf/dispatch-sync [:rf.route/navigate :route/account.profile])
    (is (= [:route/account :route/account.settings :route/account.profile]
           @(rf/subscribe [:rf.route/chain]))
        ":rf.route/chain returns [root ... leaf]")
    ;; Mid-chain
    (rf/dispatch-sync [:rf.route/navigate :route/account.settings])
    (is (= [:route/account :route/account.settings]
           @(rf/subscribe [:rf.route/chain]))
        ":rf.route/chain returns the partial chain from the middle")
    ;; Root has a single-element chain
    (rf/dispatch-sync [:rf.route/navigate :route/account])
    (is (= [:route/account]
           @(rf/subscribe [:rf.route/chain]))
        ":rf.route/chain returns a single-element chain for the root")))

(deftest sub-rf-pending-navigation
  (testing ":rf/pending-navigation reads the pending-nav slot"
    (rf/reg-route :editor/article
                  {:path      "/editor/articles/:id"
                   :params    [:map [:id :string]]
                   :can-leave :editor/can-leave?})
    (rf/reg-route :route/cart {:path "/cart"})
    (rf/reg-event-db :editor/dirty (fn [db [_ v]] (assoc-in db [:editor :dirty?] v)))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; No pending nav yet
    (is (nil? @(rf/subscribe [:rf/pending-navigation]))
        ":rf/pending-navigation returns nil when no nav is pending")
    ;; Set up a pending nav via the can-leave guard
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (let [pending @(rf/subscribe [:rf/pending-navigation])]
      (is (some? pending)
          ":rf/pending-navigation populated after a guard rejection"))
    ;; Clear it
    (rf/dispatch-sync [:rf.route/cancel "pn-1"])
    (is (nil? @(rf/subscribe [:rf/pending-navigation]))
        ":rf/pending-navigation returns nil after :rf.route/cancel")))

;; ============================================================================
;; rf2-andwd — meaningful test gaps from the routing audit
;; ============================================================================
;;
;; Audit reference: ai/findings/refactor-audit-r2-routing-2026-05-14.md
;; Lens 5 T1-T8.

(deftest invalid-route-patterns-fail-at-registration
  (testing "non-canonical path patterns raise actionable errors at reg-route"
    (doseq [[route-id pattern]
            [[:route/no-leading-slash "cart"]
             [:route/splat-not-final "/files/*rest/more"]
             [:route/nested-optional "/a{/:b{/:c}?}?"]
             [:route/optional-not-slash-prefixed "/articles{:id}?"]]]
      (let [ex (try
                 (rf/reg-route route-id {:path pattern})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str pattern " should be rejected"))
        (is (= ":rf.error/invalid-route-pattern" (ex-message ex)))
        (is (= route-id (:route-id (ex-data ex))))
        (is (= pattern (:pattern (ex-data ex))))
        (is (some? (:reason (ex-data ex)))
            "ex-data includes an actionable reason"))))

  (testing "canonical optional groups and final splats still register"
    (is (= :route/articles
           (rf/reg-route :route/articles {:path "/articles{/:id}?"})))
    (is (= :route/files
           (rf/reg-route :route/files {:path "/files/*rest"}))))

  (testing "trailing slashes in registered patterns are canonicalized away"
    (rf/reg-route :route/cart {:path "/cart/"})
    (is (= "/cart" (:path (rf/handler-meta :route :route/cart))))
    (is (= "/cart" (routing/route-url :route/cart {})))))

;; ---- T1: :rf.warning/route-shadowed-by-equal-score warning ---------------

(deftest route-shadowed-by-equal-score-warning
  (testing "registering two routes with structurally-equal rank emits
            :rf.warning/route-shadowed-by-equal-score (Spec 012 §Route
            ranking algorithm rule 6)"
    ;; Two routes with the same shape — same number of static / named /
    ;; splat / optional segments — score equal under rules 1-5. Rule 6
    ;; (reg-index tiebreak) keeps matching deterministic, but the
    ;; warning surfaces the conflict for tooling.
    (let [traces (atom [])]
      (rf/register-listener! ::shadow (fn [ev] (swap! traces conj ev)))
      (rf/reg-route :route/a {:path "/x/:id"})
      (rf/reg-route :route/b {:path "/y/:slug"})
      (rf/unregister-listener! ::shadow)
      (is (some (fn [ev]
                  (and (= :rf.warning/route-shadowed-by-equal-score
                          (:operation ev))
                       (= :route/b (-> ev :tags :route-id))
                       (= :route/a (-> ev :tags :shadowed))))
                @traces)
          "second equal-rank registration emits the warning naming both routes"))))

;; ---- T2: :int / :keyword / :boolean query coercion ----------------------

(deftest query-coercion-vocabulary
  (testing "coerce-by-type-form honours :int / :boolean for query-string
            values. Per rf2-3k3o7 a bare `:keyword` type-form is
            unbounded — the value stays as a string rather than
            interning an attacker-controllable string as a Clojure
            keyword. The narrowing-via-enum path is exercised by
            `query-coercion-keyword-enum-allowlist` below."
    (rf/reg-route :route/search
                  {:path  "/search"
                   :query [:map
                           [:count    :int]
                           [:sort     :keyword]
                           [:archived :boolean]
                           [:plain    :string]]})
    (let [m (routing/match-url "/search?count=42&sort=desc&archived=true&plain=hello")]
      (is (= 42 (get-in m [:query :count]))
          ":int coerces to a Long")
      (is (= "desc" (get-in m [:query :sort]))
          "rf2-3k3o7: bare `:keyword` stays as string — the JVM keyword-
          interning DoS surface is closed by requiring an `[:enum ...]`
          allowlist for keyword values")
      (is (= true (get-in m [:query :archived]))
          ":boolean coerces \"true\" to true")
      (is (= "hello" (get-in m [:query :plain]))
          ":string / unknown type-form passes through unchanged")))

  (testing ":boolean \"false\" coerces to false; non-true/non-false
            strings pass through unchanged"
    (rf/reg-route :route/page {:path  "/p"
                               :query [:map [:flag :boolean]]})
    (is (false? (get-in (routing/match-url "/p?flag=false") [:query :flag]))
        "\"false\" coerces to false")
    (is (= "maybe" (get-in (routing/match-url "/p?flag=maybe") [:query :flag]))
        "non-vocabulary strings pass through unchanged"))

  (testing ":int on a non-numeric string passes through unchanged
            (no throw — graceful degradation)"
    (rf/reg-route :route/page2 {:path  "/p2"
                                :query [:map [:n :int]]})
    (is (= "abc" (get-in (routing/match-url "/p2?n=abc") [:query :n]))
        "non-numeric :int input is left as-is (no exception)"))

  ;; rf2-oyw04: strict + host-IDENTICAL :int coercion. The whole string
  ;; must be an integer literal (`^-?\d+$`) to coerce; otherwise it stays a
  ;; string on BOTH hosts. The predecessor diverged on partial-numeric
  ;; input: `Long/parseLong "12abc"` threw -> string passthrough (JVM),
  ;; while `js/parseInt "12abc" 10` -> 12 (CLJS) — a Spec 011 hydration-
  ;; mismatch hazard. Now both hosts agree. The cross-host conformance
  ;; vehicle is fixtures/routing-query-string-coercion.edn (run by both the
  ;; JVM and CLJS corpus harnesses); this is the artefact-local pin.
  (testing "rf2-oyw04: :int coerces only whole integer literals; partial-
            numeric and radix-prefixed input stays a string identically on
            JVM and CLJS"
    (rf/reg-route :route/page3 {:path  "/p3"
                                :query [:map [:page :int]]})
    (is (= 12 (get-in (routing/match-url "/p3?page=12") [:query :page]))
        "clean integer literal coerces to a Long")
    (is (= -7 (get-in (routing/match-url "/p3?page=-7") [:query :page]))
        "signed integer literal coerces")
    (is (= "12abc" (get-in (routing/match-url "/p3?page=12abc") [:query :page]))
        "partial-numeric input stays a STRING — the JVM/CLJS asymmetry
         rf2-oyw04 closes (was 12 on CLJS, \"12abc\" on JVM)")
    (is (= "0x10" (get-in (routing/match-url "/p3?page=0x10") [:query :page]))
        "radix-prefixed input stays a string on both hosts")
    (is (= " 12" (get-in (routing/match-url "/p3?page=%2012") [:query :page]))
        "leading-whitespace input stays a string on both hosts")))

;; ---- rf2-3k3o7: keyword-interning cap on query keys + values -------------
;;
;; Symmetric with rf2-wu1n5's `:rf.http/max-decoded-keys` cap on JSON
;; object keys. JVM keywords intern into a process-global, never-GC'd
;; table; an attacker-influenced URL stream with N-unique query keys (or
;; N-unique `:keyword`-typed values) would otherwise permanently burn
;; N slots on long-running SSR JVMs. Three defenses:
;;
;; 1. URL-level cap on number of unique query keys — overflow throws
;;    `:rf.error/route-too-many-keys` with `:limit` / `:count` ex-data.
;; 2. Selective keywording — only keys declared by the route's `:query`
;;    schema / `:query-defaults` are promoted to keyword keys. Unknown
;;    keys stay as **string** keys.
;; 3. `:keyword`-typed value gate — a bare `:keyword` type-form stays
;;    as a string; `[:enum :a :b ...]` is the bounded-allowlist path.

(deftest rf2-3k3o7-cap-on-unique-query-keys
  (testing "match-url rejects URLs whose query exceeds the
            `default-max-decoded-keys` cap with a structured ex-info"
    (rf/reg-route :route/search {:path "/search"})
    ;; A URL one over the cap trips the throw.
    (let [n      (inc routing/default-max-decoded-keys)
          q      (clojure.string/join "&" (map #(str "k" % "=v") (range n)))
          url    (str "/search?" q)
          thrown (try (routing/match-url url) ::no-throw
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo thrown)
          "over-cap URL throws ex-info")
      (let [data (ex-data thrown)]
        (is (= :rf.error/route-too-many-keys (:rf.error/id data)))
        (is (= 'rf/match-url (:where data)))
        (is (= routing/default-max-decoded-keys (:limit data)))
        (is (>= (:count data) routing/default-max-decoded-keys))))))

(deftest rf2-3k3o7-repeated-query-key-counts-once
  (testing "the cap follows unique-key semantics, not raw pair count.
            rf2-5ifai: no :query vocabulary declared, so the key stays
            a string."
    (rf/reg-route :route/search {:path "/search"})
    (let [n   (inc routing/default-max-decoded-keys)
          q   (clojure.string/join "&" (repeat n "q=v"))
          m   (routing/match-url (str "/search?" q))]
      (is (= :route/search (:route-id m)))
      (is (= {"q" "v"} (:query m))
          "many repeated pairs for one key stay under the unique-key cap"))))

(deftest rf2-3k3o7-under-cap-succeeds
  (testing "URLs at-or-under the cap parse successfully"
    (rf/reg-route :route/search {:path "/search"})
    ;; A URL with 100 unique keys is well under the cap.
    (let [n   100
          q   (clojure.string/join "&" (map #(str "k" % "=v") (range n)))
          url (str "/search?" q)
          m   (routing/match-url url)]
      (is (some? m) "under-cap URL parses without throwing")
      (is (= :route/search (:route-id m))))))

(deftest rf2-3k3o7-undeclared-query-keys-stay-as-strings
  (testing "query keys NOT declared by the route's `:query` schema or
            `:query-defaults` stay as **string** keys in the parsed
            :query map — no permanent keyword-table slot is burned on
            their behalf"
    (rf/reg-route :route/search
                  {:path  "/search"
                   :query [:map [:q :string]]})
    (let [m (routing/match-url "/search?q=clojure&unknown1=foo&unknown2=bar")]
      (is (some? m))
      (is (= "clojure" (get-in m [:query :q]))
          "declared :q (keyword key) is present and typed per schema")
      (is (= "foo" (get-in m [:query "unknown1"]))
          "undeclared `unknown1` is keyed by STRING, not keyword")
      (is (= "bar" (get-in m [:query "unknown2"]))
          "undeclared `unknown2` is keyed by STRING, not keyword")
      (is (not (contains? (:query m) :unknown1))
          "no `:unknown1` keyword in the result map")
      (is (not (contains? (:query m) :unknown2))
          "no `:unknown2` keyword in the result map"))))

;; ---- rf2-6t1xb: match-url DoS-guard throw FAILS CLOSED at the nav --------
;;     entry points (does NOT crash the event drain).
;;
;; rf2-3k3o7 added the keyword-interning cap: `match-url` THROWS
;; `:rf.error/route-too-many-keys` when a URL's unique query-key count
;; exceeds `default-max-decoded-keys`. The guard's documented intent
;; (registry.cljc §default-max-decoded-keys: "the URL is treated as a
;; route-miss") is a clean fail-closed to `:rf.route/not-found`. But the
;; throw, unhandled at the nav entry points, escaped the event handler
;; and CRASHED the drain — converting a memory-pressure DoS into a worse
;; drain-crash DoS. The fix wraps `match-url` in `match-url-fail-closed`
;; at BOTH nav entry points (url-change-fx + navigate `{:url ...}`), so a
;; throwing URL routes to `:rf.route/not-found` with a `:reason`
;; discriminator (`:too-many-keys`) instead of propagating.
;;
;; The pre-existing `rf2-3k3o7-cap-on-unique-query-keys` test asserts the
;; throw at the `match-url` API level; these tests drive an over-cap URL
;; through the actual nav EVENTS and assert the fail-closed routing the
;; docstring promises — the gap rf2-6t1xb identified.

(defn- over-cap-url
  "Build a `/search?...` URL one unique query-key OVER
  `default-max-decoded-keys` — the smallest URL that trips the
  keyword-interning DoS guard's throw."
  []
  (let [n (inc routing/default-max-decoded-keys)
        q (clojure.string/join "&" (map #(str "k" % "=v") (range n)))]
    (str "/search?" q)))

(deftest match-url-fail-closed-turns-cap-throw-into-nil-match
  (testing "rf2-6t1xb: `match-url-fail-closed` catches the DoS-cap throw
            and returns {:match nil :throw-reason :too-many-keys} rather
            than propagating — the fail-closed primitive the nav entry
            points build on"
    (rf/reg-route :route/search {:path "/search"})
    (let [{:keys [match throw-reason]}
          (registry/match-url-fail-closed (over-cap-url))]
      (is (nil? match) "an over-cap URL yields a NIL match (a route-miss)")
      (is (= :too-many-keys throw-reason)
          "the cap throw is discriminated as :too-many-keys"))
    (testing "a clean match passes through with no throw-reason"
      (let [{:keys [match throw-reason]}
            (registry/match-url-fail-closed "/search?q=clojure")]
        (is (= :route/search (:route-id match)))
        (is (nil? throw-reason))))
    (testing "a bare miss passes through as nil match, no throw-reason"
      (let [{:keys [match throw-reason]}
            (registry/match-url-fail-closed "/no/such/path")]
        (is (nil? match))
        (is (nil? throw-reason)
            "a bare miss is NOT a throw — no discriminator")))))

(deftest url-change-over-cap-url-fails-closed-not-found
  (testing "rf2-6t1xb: an over-cap URL arriving via `:rf.route/transitioned`
            routes to :rf.route/not-found with `:reason :too-many-keys`
            and does NOT throw out of the event drain"
    (rf/reg-route :route/search {:path "/search"})
    (rf/reg-route :rf.route/not-found {:path "/404"})
    (let [pushed (atom [])
          url    (over-cap-url)]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ u] (swap! pushed conj u)))
      ;; The whole point: this dispatch must NOT throw out of the drain.
      (is (= ::ok
             (try (rf/dispatch-sync [:rf.route/transitioned url]) ::ok
                  (catch Throwable _ ::threw)))
          "the over-cap URL drains cleanly — no throw escapes the handler
           (pre-fix this propagated :rf.error/route-too-many-keys)")
      (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
        (is (= :rf.route/not-found (:id slice))
            "over-cap URL fails closed to :rf.route/not-found")
        (is (= url (:url (:params slice)))
            ":params carries the REQUESTED (over-cap) url — preserved per rf2-0zr2o")
        (is (= :too-many-keys (:reason (:params slice)))
            ":reason :too-many-keys discriminates the DoS-cap miss from a
             bare miss / :malformed-url / :validation"))
      ;; URL-driven path emits no push (URL already changed via popstate).
      (is (empty? @pushed)
          "URL-driven not-found emits no push — address bar already shows
           the requested url (unchanged from the bare-miss path)"))))

(deftest navigate-url-form-over-cap-url-fails-closed-not-found
  (testing "rf2-6t1xb: an over-cap `{:url ...}` target via
            `:rf.route/navigate` routes to :rf.route/not-found with
            `:reason :too-many-keys`, pushes the REQUESTED url verbatim
            (rf2-0zr2o parity), and does NOT throw out of the drain"
    (rf/reg-route :route/search {:path "/search"})
    (rf/reg-route :rf.route/not-found {:path "/404"})
    (let [pushed (atom [])
          url    (over-cap-url)]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ u] (swap! pushed conj u)))
      (is (= ::ok
             (try (rf/dispatch-sync [:rf.route/navigate {:url url}]) ::ok
                  (catch Throwable _ ::threw)))
          "the over-cap {:url ...} target drains cleanly — no throw escapes
           (pre-fix this propagated :rf.error/route-too-many-keys)")
      (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
        (is (= :rf.route/not-found (:id slice))
            "over-cap URL-string target fails closed to :rf.route/not-found")
        (is (= url (:url (:params slice)))
            ":params carries the REQUESTED (over-cap) url — preserved per rf2-0zr2o")
        (is (= :too-many-keys (:reason (:params slice)))
            ":reason :too-many-keys discriminates the DoS-cap miss")
        (is (some? (:nav-token slice))
            "a fresh nav-token is allocated for the not-found navigation"))
      (is (= [url] @pushed)
          ":rf.nav/push-url pushed the REQUESTED (over-cap) url VERBATIM —
           NOT the not-found route's /404 (rf2-0zr2o address-bar parity)"))))

;; ---- rf2-5ifai: no :query vocabulary -> all string keys ------------------
;;
;; Per Spec 012 §Query strings and fragments and the rf2-tfgdv security
;; review: a route declaring NO query vocabulary at all (no `:query` /
;; `:query-defaults` / `:query-retain`) keeps every URL key as a string.
;; Authors who want keyword keys declare them via `:query` /
;; `:query-defaults` / `:query-retain` — author-named intent is the
;; trust boundary. Symmetrical to the rf2-3k3o7 value-side fix: hostile
;; URLs composed of N-unique keys would otherwise burn N permanent JVM
;; keyword slots, and a bare `(reg-route :route/x {:path "/x"})` is the
;; high-cardinality public-surface case where the DoS hits hardest.

(deftest rf2-5ifai-no-vocabulary-route-keeps-all-keys-as-strings
  (testing "rf2-5ifai: a route declaring NO :query vocabulary keeps
            every URL query key as a string. The legacy keyword-all
            fallback is gone (pre-alpha — no back-compat shim)."
    (rf/reg-route :route/bare {:path "/bare"})
    (let [m (routing/match-url "/bare?foo=1&bar=2&baz=3")]
      (is (some? m))
      (is (= {"foo" "1" "bar" "2" "baz" "3"} (:query m))
          "all URL keys remain strings — no keyword promotion at all")
      (doseq [k [:foo :bar :baz]]
        (is (not (contains? (:query m) k))
            (str "no `" k "` keyword in the result map")))))
  (testing "rf2-5ifai: even single-key URLs do not get a keyword promotion"
    (rf/reg-route :route/single {:path "/single"})
    (let [m (routing/match-url "/single?x=1")]
      (is (some? m))
      (is (= {"x" "1"} (:query m)))
      (is (not (contains? (:query m) :x))
          "single :x key stays a string — no special case for cardinality 1"))))

(deftest rf2-3k3o7-defaults-extend-declared-universe
  (testing "keys declared via `:query-defaults` (without a `:query`
            schema) widen the keyword universe — they get keyword
            keys; non-declared URL keys stay string-keyed"
    (rf/reg-route :route/list
                  {:path           "/list"
                   :query-defaults {:page 1 :sort "asc"}})
    (let [m (routing/match-url "/list?page=3&unknown=x")]
      (is (= "3" (get-in m [:query :page]))
          ":page from defaults → declared → keyword-keyed (no schema coerce → stays string)")
      (is (= "asc" (get-in m [:query :sort]))
          "absent :sort filled from defaults")
      (is (= "x" (get-in m [:query "unknown"]))
          "undeclared `unknown` stays string-keyed"))))

(deftest rf2-3k3o7-keyword-enum-allowlist
  (testing "a `[:enum :asc :desc]` schema constrains the keyword
            universe — values matching declared choices intern, others
            stay as strings (bounded by construction)"
    (rf/reg-route :route/sorted
                  {:path  "/items"
                   :query [:map [:sort [:enum :asc :desc]]]})
    (let [m1 (routing/match-url "/items?sort=asc")
          m2 (routing/match-url "/items?sort=desc")
          m3 (routing/match-url "/items?sort=hostile-value")]
      (is (= :asc  (get-in m1 [:query :sort]))
          "declared enum value `asc` interns to :asc")
      (is (= :desc (get-in m2 [:query :sort]))
          "declared enum value `desc` interns to :desc")
      (is (= "hostile-value" (get-in m3 [:query :sort]))
          "value outside the enum allowlist stays as string — no intern"))))

(deftest rf2-3k3o7-bare-keyword-stays-string
  (testing "rf2-3k3o7: bare `:keyword` type-form (no enum allowlist) is
            an unbounded-intern site — value stays as a string"
    (rf/reg-route :route/page
                  {:path  "/page"
                   :query [:map [:tag :keyword]]})
    (let [m (routing/match-url "/page?tag=arbitrary-value")]
      (is (= "arbitrary-value" (get-in m [:query :tag]))
          "bare :keyword preserves the URL value as a string — no
          unbounded intern site"))))

;; ---- T3: :query-defaults populates absent keys -------------------------

(deftest query-defaults-populates-absent-keys
  (testing ":query-defaults supplies values for absent query keys; URL-
            supplied values win on conflict (Spec 012 §Query-string
            coercion §Defaults)"
    (rf/reg-route :route/list
                  {:path           "/list"
                   :query-defaults {:page 1 :per-page 20 :sort "asc"}})
    (let [m (routing/match-url "/list")]
      (is (= 1     (get-in m [:query :page]))
          ":query-defaults populates :page when absent")
      (is (= 20    (get-in m [:query :per-page]))
          ":query-defaults populates :per-page when absent")
      (is (= "asc" (get-in m [:query :sort]))
          ":query-defaults populates :sort when absent"))
    (let [m (routing/match-url "/list?page=3&sort=desc")]
      (is (= "3"    (get-in m [:query :page]))
          "URL-supplied :page wins over default (note: no :query schema → string)")
      (is (= 20     (get-in m [:query :per-page]))
          "default still applied for the absent key")
      (is (= "desc" (get-in m [:query :sort]))
          "URL-supplied :sort wins over default"))))

;; ---- T4: route-url optional-group elision when inner params absent ----

(deftest route-url-optional-group-elision
  (testing "route-url with absent optional-group params elides the group
            (Spec 012 §Bidirectional URL ↔ params §Optional groups)"
    (rf/reg-route :route/articles
                  {:path "/articles{/:id}?"})
    (is (= "/articles"
           (routing/route-url :route/articles {}))
        "absent :id → optional group elides; bare /articles emits")
    (is (= "/articles/intro"
           (routing/route-url :route/articles {:id "intro"}))
        "present :id → optional group emits including the leading /"))

  (testing "deeper optional-group elision: an inner param's absence
            collapses the whole group (every? over inner-names)"
    (rf/reg-route :route/articles2
                  {:path "/articles{/:id/:slug}?"})
    (is (= "/articles"
           (routing/route-url :route/articles2 {}))
        "both absent → group elides")
    (is (= "/articles"
           (routing/route-url :route/articles2 {:id "intro"}))
        "ONE inner param absent → group still elides (every? requires all)")
    (is (= "/articles/intro/welcome"
           (routing/route-url :route/articles2 {:id "intro" :slug "welcome"}))
        "all inner params present → group emits")))

;; ---- T4b: match-url optional-group param is ABSENT, not nil-valued ------
;;
;; Regression for rf2-yejde. Per Spec 012 §Path-pattern grammar (Optional
;; segment group): "param present only if matched." The canonical example
;; route /articles/:id{/:slug}? declares :params with {:optional true} on
;; :slug. Malli {:optional true} governs KEY PRESENCE, not nil values, so a
;; present {:slug nil} is REJECTED. Pre-fix, match-against zipmap'd the
;; unmatched optional group as :slug nil → a legitimate /articles/<id> URL
;; failed :params validation and routed to not-found. The fix strips
;; nil-valued keys after the zipmap so the unmatched param is absent.
;;
;; The stub :params predicate below mirrors Malli {:optional true}: :slug,
;; *when present*, must be a non-nil string; an ABSENT :slug is fine.

(deftest match-url-optional-group-param-absent-not-nil
  (testing "an unmatched optional-group param is ABSENT from :params, not
            nil-valued, so a route carrying a {:optional true} :params
            schema still matches a URL that omits the optional segment
            (Spec 012 §Path-pattern grammar §Optional segment group)"
    (let [restore (with-stub-validator)
          ;; Mirrors [:map [:id :string] [:slug {:optional true} :string]]:
          ;; :id must be a non-nil string; :slug, when the KEY is present,
          ;; must be a non-nil string (a present nil rejects, as Malli does).
          slug-optional-schema
          (fn [{:keys [id] :as params}]
            (and (string? id)
                 (or (not (contains? params :slug))
                     (string? (:slug params)))))]
      (try
        (rf/reg-route :route/article-slug
                      {:path   "/articles/:id{/:slug}?"
                       :params slug-optional-schema})

        (testing "bare /articles/5 — optional :slug unmatched"
          (let [m (routing/match-url "/articles/5")]
            (is (some? m) "the route matches structurally")
            (is (= {:id "5"} (:params m))
                ":slug is ABSENT (not nil-valued) when the optional group is unmatched")
            (is (not (contains? (:params m) :slug))
                "the unmatched optional key is omitted entirely")
            (is (false? (:validation-failed? m))
                "a present {:slug nil} would reject the {:optional true} schema; an absent :slug validates cleanly")
            (is (nil? (:validation-error m))
                "no validation error for the absent optional param")))

        (testing "/articles/5/intro — optional :slug supplied"
          (let [m (routing/match-url "/articles/5/intro")]
            (is (some? m) "the route matches when the optional segment is present")
            (is (= {:id "5" :slug "intro"} (:params m))
                "the optional key is present with its captured value when supplied")
            (is (false? (:validation-failed? m))
                "the supplied :slug conforms")))
        (finally (restore))))))

;; ---- T5: splat /files/*rest matches multi-segment paths ----------------

(deftest splat-multi-segment-match
  (testing "splat /files/*rest matches /files/a/b/c with :params
            {:rest \"a/b/c\"} (Spec 012 §Bidirectional URL ↔ params §Splat)"
    (rf/reg-route :route/files {:path "/files/*rest"})
    (let [m (routing/match-url "/files/a/b/c")]
      (is (some? m)              "splat matches multi-segment path")
      (is (= :route/files (:route-id m)))
      (is (= "a/b/c" (get-in m [:params :rest]))
          "splat preserves literal '/' inside the captured value"))

    (testing "single-segment splat input"
      (is (= {:rest "only"}
             (:params (routing/match-url "/files/only")))
          "splat captures a single segment too"))

    (testing "splat round-trips through route-url"
      (let [built (routing/route-url :route/files {:rest "a/b/c"})]
        (is (= "/files/a/b/c" built)
            "route-url emits the splat segments preserving '/'")))))

;; ---- url-encode-splat per-segment encoding -------------------------------
;;
;; Per Spec 012 §Bidirectional URL ↔ params §Splat. `url-encode-splat`
;; (url.cljc:27-31) splits on `/`, encodes EACH segment with
;; encodeURIComponent semantics, and re-joins with literal `/`. The
;; ASCII-safe round-trip ("a/b/c") above can't observe the encode step
;; at all — it only proves `/` is preserved. This pins the actual reason
;; the per-segment join exists: a segment that needs encoding is encoded,
;; while the `/` separators between segments stay literal (NOT %2F).
(deftest splat-segment-percent-encoding
  (testing "a splat segment needing encoding is encoded; literal '/' is preserved"
    (rf/reg-route :route/files {:path "/files/*rest"})
    (is (= "/files/a/b%20c"
           (routing/route-url :route/files {:rest "a/b c"}))
        "the space inside a segment encodes to %20; the segment '/' stays literal")
    (is (= "/files/a%20b/c%20d"
           (routing/route-url :route/files {:rest "a b/c d"}))
        "every segment is encoded individually; both '/' separators stay literal")
    (is (= "/files/x%26y/z"
           (routing/route-url :route/files {:rest "x&y/z"}))
        "an `&` inside a segment is encoded so it cannot leak into a query"))

  (testing "splat encode round-trips back through match-url (decode is the inverse)"
    (rf/reg-route :route/files2 {:path "/files/*rest"})
    (let [built (routing/route-url :route/files2 {:rest "a/b c"})]
      (is (= "/files/a/b%20c" built))
      (is (= "a/b c" (get-in (routing/match-url built) [:params :rest]))
          "match-url decodes each segment back, recovering the original splat value"))))

;; ---- T6: :on-match dispatches AND :transition :loading observable ----

(deftest on-match-dispatches-and-loading-observable
  (testing ":rf.route/navigate fires every :on-match event in order, and
            :transition :loading is observable to the :on-match handlers
            themselves (Spec 012 §Per-route data loading)"
    (let [observed (atom [])]
      (rf/reg-event-db :load/a
                       (fn [db _]
                         (swap! observed conj [:a (get-in db [:rf/runtime :routing :current :transition])])
                         (assoc db :load/a-done? true)))
      (rf/reg-event-db :load/b
                       (fn [db _]
                         (swap! observed conj [:b (get-in db [:rf/runtime :routing :current :transition])])
                         (assoc db :load/b-done? true)))
      (rf/reg-route :route/dashboard
                    {:path     "/dashboard"
                     :on-match [[:load/a] [:load/b]]})
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/navigate :route/dashboard])

      (is (= [[:a :loading] [:b :loading]] @observed)
          "both :on-match events fired in declaration order; both observed :loading")
      (let [db (rf/app-db-value :rf/default)]
        (is (true? (:load/a-done? db)) "first :on-match handler ran")
        (is (true? (:load/b-done? db)) "second :on-match handler ran")
        (is (= :idle (get-in db [:rf/runtime :routing :current :transition]))
            ":transition settles back to :idle after the drain")))))

;; ---- T7: :rf.route/fragment-changed (fragment-only) trace event payload ----

(deftest fragment-only-url-change-trace-payload
  (testing "fragment-only URL change emits :rf.route/fragment-changed
            (rf2-cj9fn; pre-rename `:rf.route/fragment-changed`) with
            :prev-fragment / :next-fragment under :tags (Spec 012 §Fragments)"
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Land on /docs/routing first (no fragment).
    (rf/dispatch-sync [:rf.route/transitioned "/docs/routing"])
    (let [traces (atom [])]
      (rf/register-listener! ::frag-only (fn [ev] (swap! traces conj ev)))
      ;; Same path/query, different fragment → fragment-only nav.
      (rf/dispatch-sync [:rf.route/transitioned "/docs/routing#scroll-restoration"])
      ;; And again — prev→next this time.
      (rf/dispatch-sync [:rf.route/transitioned "/docs/routing#fragments"])
      (rf/unregister-listener! ::frag-only)
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
              "second transition: next-fragment is the new value"))))))

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
      (rf/reg-event-db :docs/load
                       (fn [db _]
                         (swap! on-match-calls inc)
                         db))
      (rf/reg-route :route/docs {:path     "/docs/:page"
                                 :on-match [[:docs/load]]})
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      ;; Land on /docs/routing via popstate (handle-url-change). This is
      ;; a full nav: allocates nav-1 and fires :on-match once.
      (rf/dispatch-sync [:rf.route/handle-url-change "/docs/routing"])
      (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
        (is (= :route/docs (:id slice)) "landed on /docs/routing")
        (is (= "nav-1" (:nav-token slice)) "first nav allocated nav-1")
        (is (= 1 @on-match-calls) ":on-match fired once on the full nav"))

      (let [traces (atom [])]
        (rf/register-listener! ::popstate-frag (fn [ev] (swap! traces conj ev)))
        ;; Back/Forward to the SAME page, only the #fragment differs.
        ;; This is the popstate path (handle-url-change), the regression
        ;; site: must short-circuit, NOT full-rewrite.
        (rf/dispatch-sync [:rf.route/handle-url-change "/docs/routing#section-2"])
        (rf/unregister-listener! ::popstate-frag)
        (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
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
            "fragment-only popstate emits NO :rf.route.nav-token/allocated")))))

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
      (rf/reg-event-db :cart/load (fn [db _] (swap! on-match-calls inc) db))
      (rf/reg-route :route/cart {:path "/cart" :on-match [[:cart/load]]})
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      ;; First nav: full rewrite, loader fires once, nav-1 allocated.
      (rf/dispatch-sync [:rf.route/transitioned "/cart"])
      (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
        (is (= "nav-1" (:nav-token slice)) "first nav allocates nav-1")
        (is (= 1 @on-match-calls) ":on-match fired once on the full nav"))
      (let [traces (atom [])]
        (rf/register-listener! ::identical (fn [ev] (swap! traces conj ev)))
        ;; Re-navigate to the SAME url — rule-3 no-op.
        (rf/dispatch-sync [:rf.route/transitioned "/cart"])
        (rf/unregister-listener! ::identical)
        (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
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
      (rf/reg-event-db :article/load (fn [db _] (swap! on-match-calls inc) db))
      (rf/reg-route :route/article
                    {:path "/articles/:id" :on-match [[:article/load]]})
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      (is (= 2 @on-match-calls)
          "same route-id, different :params re-fires :on-match (A then B)")
      (is (= "nav-2" (:nav-token (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])))
          "a changed-params nav DOES allocate a fresh nav-token"))))

(deftest identical-programmatic-renav-does-not-refire-on-match
  (testing "rf2-ee38b.8 / Spec 012 rule 3: a duplicate
            [:rf.route/navigate :route/cart] does NOT re-fire :on-match
            and does NOT allocate a new nav-token"
    (let [on-match-calls (atom 0)
          pushed         (atom 0)]
      (rf/reg-event-db :cart/load (fn [db _] (swap! on-match-calls inc) db))
      (rf/reg-route :route/cart {:path "/cart" :on-match [[:cart/load]]})
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] (swap! pushed inc)))
      (rf/dispatch-sync [:rf.route/navigate :route/cart])
      (is (= 1 @on-match-calls) ":on-match fired once on the first navigate")
      (is (= "nav-1" (:nav-token (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current]))))
      ;; Duplicate navigate to the same target — rule-3 no-op.
      (rf/dispatch-sync [:rf.route/navigate :route/cart])
      (is (= 1 @on-match-calls)
          "rule 3: :on-match did NOT re-fire on duplicate navigate")
      (is (= "nav-1" (:nav-token (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])))
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
    (let [restore (with-stub-validator)
          pushed  (atom [])]
      (try
        (rf/reg-route :route/home {:path "/"})
        (rf/reg-route :route/article
                      {:path   "/articles/:id"
                       :params (fn [{:keys [id]}]
                                 (clojure.string/starts-with? (or id "") "a"))})
        (rf/reg-fx :rf.nav/push-url
                   {:platforms #{:server :client}}
                   (fn [_ url] (swap! pushed conj url)))
        ;; Land somewhere valid first so we can prove the slice is left
        ;; untouched by the rejected navigation.
        (rf/dispatch-sync [:rf.route/navigate :route/article {:id "aardvark"}])
        (reset! pushed [])
        (let [before (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])
              traces (atom [])]
          (rf/register-listener! ::reject (fn [ev] (swap! traces conj ev)))
          ;; Caller bug: :id "zoo" violates the :params schema.
          (rf/dispatch-sync [:rf.route/navigate :route/article {:id "zoo"}])
          (rf/unregister-listener! ::reject)
          (let [after (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
            (is (= before after)
                "rejected navigation leaves the :rf/route slice UNCHANGED")
            (is (= :route/article (:id after))
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

;; ---- rf2-ee38b.8: :rf.route/navigation-blocked is a DISPATCHED event --
;;
;; Spec 012 §Navigation blocking §Default flow step 4d: the runtime
;; DISPATCHES [:rf.route/navigation-blocked pending-nav]. An app that
;; registers its own :rf.route/navigation-blocked handler must see it
;; fire. Pre-fix only the trace (step 4e) was emitted; no event dispatch.

(deftest navigation-blocked-is-dispatched-as-an-event
  (testing "rf2-ee38b.8: a :can-leave rejection DISPATCHES
            [:rf.route/navigation-blocked pending-nav] so an
            app-registered handler fires (Spec 012 §Default flow 4d)"
    (let [seen (atom nil)]
      (rf/reg-route :editor/article
                    {:path      "/editor/articles/:id"
                     :params    [:map [:id :string]]
                     :can-leave :editor/can-leave?})
      (rf/reg-route :route/cart {:path "/cart"})
      (rf/reg-event-db :editor/dirty (fn [db [_ v]] (assoc-in db [:editor :dirty?] v)))
      (rf/reg-sub :editor/can-leave?
                  (fn [db _] (not (get-in db [:editor :dirty?]))))
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      ;; App registers its OWN handler over the framework default no-op.
      (rf/reg-event-fx :rf.route/navigation-blocked
                       (fn [_ [_ pending-nav]]
                         (reset! seen pending-nav)
                         {}))
      (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
      (rf/dispatch-sync [:editor/dirty true])
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
      (is (some? @seen)
          "app-registered :rf.route/navigation-blocked handler fired")
      (is (= "/cart" (:requested-url @seen))
          "the dispatched event carried the pending-nav map as its arg")
      (is (= :editor/article (:rejecting-route @seen))
          "pending-nav names the rejecting route")
      (is (= :editor/can-leave? (:rejecting-guard @seen))
          "pending-nav names the rejecting guard sub-id"))))

(deftest can-leave-non-boolean-trace-tags-real-route-id
  (testing "rf2-ee38b.8: :rf.error/can-leave-non-boolean tags :route-id
            with the route-id KEYWORD, not the :path pattern string"
    (rf/reg-route :editor/article
                  {:path      "/editor/articles/:id"
                   :params    [:map [:id :string]]
                   :can-leave [:editor/leave?]})
    (rf/reg-route :route/cart {:path "/cart"})
    (rf/reg-event-db :editor/set-dirty
                     (fn [db [_ v]] (assoc-in db [:editor :dirty?] v)))
    ;; Polarity bug: returns a truthy non-boolean (42).
    (rf/reg-sub :editor/leave? (fn [db _] (get-in db [:editor :dirty?])))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/set-dirty 42])
    (let [traces (atom [])]
      (rf/register-listener! ::nb-id (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
      (rf/unregister-listener! ::nb-id)
      (let [nb (first (filter #(= :rf.error/can-leave-non-boolean (:operation %))
                              @traces))]
        (is (some? nb) ":rf.error/can-leave-non-boolean fired")
        (is (= :editor/article (-> nb :tags :route-id))
            ":route-id is the route-id KEYWORD, not the \"/editor/articles/:id\" path string")))))

;; ---- T8: :fragment in slice after URL-driven nav -----------------------

(deftest fragment-in-slice-after-url-driven-nav
  (testing ":fragment in URL flows into the slice on every URL-driven nav
            (Spec 012 §The :rf/route slice — :fragment row)"
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/docs/routing#scroll-restoration"])
    (is (= "scroll-restoration"
           (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current :fragment]))
        ":fragment from URL is written to slice")))

;; ============================================================================
;; rf2-ye7sh — :on-match :on-error trap + :transition :error
;; ============================================================================

(deftest on-match-error-flips-transition-to-error
  (testing "an :on-match event throw flips :transition :error and
            populates :rf.route/error (Spec 012 §Per-route error handling)"
    (rf/reg-event-db :load/throw
                     (fn [_db _]
                       (throw (ex-info "boom" {:reason :test}))))
    (rf/reg-route :route/dashboard
                  {:path     "/dashboard"
                   :on-match [[:load/throw]]})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/dashboard"])
    (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
      (is (= :error (:transition slice))
          ":transition flips to :error on :on-match throw")
      (is (some? (:error slice))
          ":rf.route/error is populated with the structured error map")
      (is (= :load/throw (:event-id (:error slice)))
          ":rf.route/error names the failing event-id")
      (is (= :rf.error/handler-exception (:operation (:error slice)))
          ":rf.route/error carries the Spec 009 error :operation"))))

(deftest on-match-error-dispatches-on-error-when-declared
  (testing "a route's :on-error event dispatches with the error context
            visible via (:error (get-in db [:rf/runtime :routing :current])) (Spec 012 §Per-route
            error handling)"
    (rf/reg-event-db :load/throw2
                     (fn [_db _]
                       (throw (ex-info "kaboom" {}))))
    (rf/reg-event-db :route/cart-load-failed
                     (fn [db _]
                       (let [err (get-in db [:rf/runtime :routing :current :error])]
                         (assoc db :handled-error err))))
    (rf/reg-route :route/cart
                  {:path     "/cart"
                   :on-match [[:load/throw2]]
                   :on-error [:route/cart-load-failed]})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/cart"])
    (let [db    (rf/app-db-value :rf/default)
          slice (get-in db [:rf/runtime :routing :current])]
      (is (= :error (:transition slice))
          ":transition still :error after :on-error ran")
      (is (some? (:handled-error db))
          ":on-error handler ran and read the error from the slice")
      (is (= :load/throw2 (:event-id (:handled-error db)))
          ":on-error handler saw the same structured error as :rf.route/error"))))

(deftest on-match-error-without-on-error-leaves-error-state
  (testing "a route without :on-error still flips :transition :error and
            populates :rf.route/error — views may render an error banner
            without an explicit :on-error policy (Spec 012 §Per-route
            error handling — last paragraph)"
    (rf/reg-event-db :load/throw3
                     (fn [_db _]
                       (throw (ex-info "x" {}))))
    (rf/reg-route :route/page
                  {:path     "/page"
                   :on-match [[:load/throw3]]})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/page"])
    (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])]
      (is (= :error (:transition slice))
          ":transition :error even without :on-error declared")
      (is (some? (:error slice))
          ":rf.route/error populated for the view to render"))))

(deftest on-match-error-keyword-on-error-wraps-as-vector
  (testing "an :on-error declared as a bare keyword (rather than a
            vector) dispatches as `[<kw>]` per the spec example"
    (rf/reg-event-db :load/throw4
                     (fn [_db _]
                       (throw (ex-info "y" {}))))
    (rf/reg-event-db :handle/error
                     (fn [db _]
                       (assoc db :handled? true)))
    (rf/reg-route :route/p
                  {:path     "/p"
                   :on-match [[:load/throw4]]
                   ;; bare keyword form
                   :on-error :handle/error})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/p"])
    (is (true? (:handled? (rf/app-db-value :rf/default)))
        "bare-keyword :on-error wraps as a vector and dispatches")))

;; ============================================================================
;; rf2-m78lu — :on-match exception attribution rides on the error map
;; ============================================================================

(deftest on-match-error-stamps-route-attribution
  (testing ":rf.route/on-match-id and :rf.route/on-match-frame are
            stamped on the structured error map dispatched into the
            slice's :error slot (Spec 012 §Per-route error handling
            and rf2-m78lu). Same pattern as the flow-attribution slot
            `:rf.flow/failed-id` (rf2-je5p8).
            Tools reading the error from `(:error (get-in db [:rf/runtime :routing :current]))` —
            outside the routing listener's discrimination context —
            can identify the throw as :on-match-attributed without
            re-running the listener logic."
    (rf/reg-event-db :load/throw-attribute
                     (fn [_db _]
                       (throw (ex-info "attributed-boom" {:why :test}))))
    (rf/reg-route :route/attributed
                  {:path     "/attributed"
                   :on-match [[:load/throw-attribute]]})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/attributed"])
    (let [slice (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])
          err   (:error slice)]
      (is (= :error (:transition slice))
          ":transition flips to :error on the attributed throw")
      (is (= :rf.error/handler-exception (:operation err))
          ":operation is the Spec 009 handler-exception id")
      (is (= :load/throw-attribute (:rf.route/on-match-id err))
          ":rf.route/on-match-id names the failing :on-match event-id")
      (is (= :rf/default (:rf.route/on-match-frame err))
          ":rf.route/on-match-frame names the dispatching frame"))))

;; ============================================================================
;; rf2-t1lxr / rf2-1ve9h — routing-internal dispatches stamp :source :router
;; ============================================================================

(deftest on-match-error-internal-dispatch-stamps-source-router
  (testing "the routing-internal `:rf.route.internal/on-match-error`
            dispatch stamps the closed-enum functional-origin axis
            `:source :router` on its envelope (visible on the
            `:rf.event/dispatched` trace) so Xray's L2 timeline + filter
            pills discriminate framework-origin events from user-origin
            events. Per rf2-t1lxr; per rf2-1ve9h `:source` is the single
            closed-enum functional-origin axis on the dispatch envelope,
            and `:source :router` is the routing discriminator."
    (rf/reg-event-db :load/throw-source
                     (fn [_db _]
                       (throw (ex-info "source-boom" {:why :test}))))
    (rf/reg-route :route/source-attributed
                  {:path     "/source-attributed"
                   :on-match [[:load/throw-source]]})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (let [traces (atom [])]
      (rf/register-listener! ::on-match-source
                             (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/transitioned "/source-attributed"])
      (rf/unregister-listener! ::on-match-source)
      (let [internal-dispatch
            (some (fn [ev]
                    (and (= :rf.event/dispatched (:operation ev))
                         (= :rf.route.internal/on-match-error
                            (-> ev :tags :rf.event/v first))
                         ev))
                  @traces)]
        (is (some? internal-dispatch)
            "the on-match-error listener dispatched :rf.route.internal/on-match-error")
        ;; `:source` is hoisted to a top-level slot on every trace event
        ;; (re-frame.trace/build-event — Spec 009 §Core fields hoist
        ;; contract), not stamped under `:tags`.
        (is (= :router (:source internal-dispatch))
            ":source :router rides on the routing-internal dispatch envelope")))))

;; ============================================================================
;; rf2-5pyyl — :can-leave non-boolean → BLOCK + :rf.error/can-leave-non-boolean
;; ============================================================================

(deftest can-leave-non-boolean-blocks-navigation
  (testing "a :can-leave sub that returns a non-boolean truthy value
            BLOCKS navigation and emits :rf.error/can-leave-non-boolean
            (rf2-5pyyl). Closed contract — blocking ensures the polarity
            bug (returning the dirty-flag value rather than (not dirty?))
            cannot silently strand form state."
    (rf/reg-route :editor/article
                  {:path      "/editor/articles/:id"
                   :params    [:map [:id :string]]
                   :can-leave [:editor/leave?]})
    (rf/reg-route :route/cart {:path "/cart"})
    (rf/reg-event-db :editor/set-dirty
                     (fn [db [_ v]] (assoc-in db [:editor :dirty?] v)))
    ;; Polarity bug: return the dirty-flag directly (truthy when dirty).
    ;; A truthy non-boolean must BLOCK nav (rf2-5pyyl).
    (rf/reg-sub :editor/leave?
                (fn [db _] (get-in db [:editor :dirty?])))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    ;; A non-truthy non-false value (`nil`) would also be a non-boolean
    ;; — but we want to specifically exercise the "truthy non-boolean"
    ;; polarity bug, so dirty the editor first.
    (rf/dispatch-sync [:editor/set-dirty 42])
    (let [traces (atom [])]
      (rf/register-listener! ::can-leave-nb (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
      (rf/unregister-listener! ::can-leave-nb)
      (let [db        (rf/app-db-value :rf/default)
            pending   (get-in db [:rf/runtime :routing :pending-navigation])
            nb-traces (filter #(= :rf.error/can-leave-non-boolean
                                   (:operation %))
                              @traces)]
        (is (= :editor/article (get-in db [:rf/runtime :routing :current :id]))
            "navigation BLOCKED — slice still on the source route")
        (is (some? pending)
            ":rf/pending-navigation slot is populated (block path)")
        (is (= :rf.error/can-leave-non-boolean
               (-> nb-traces first :operation))
            ":rf.error/can-leave-non-boolean trace fired (rf2-5pyyl)")
        (is (= 42 (-> nb-traces first :tags :value))
            "trace carries the offending non-boolean value")
        (is (= :blocked-navigation (-> nb-traces first :recovery))
            "trace hoists :recovery :blocked-navigation (Spec 009 §error contract)")))))

;; ============================================================================
;; rf2-dn26r — route lifecycle trace ops
;; ============================================================================

(deftest route-registered-trace-on-first-time-reg
  (testing ":rf.route/registered fires on FIRST-TIME reg-route (rf2-dn26r).
            Re-registration with the same id rides the cross-kind
            `:rf.registry/handler-replaced` trace; not re-emitted here.
            Mirrors the `:rf.flow/registered` symmetry."
    (let [traces (atom [])]
      (rf/register-listener! ::reg-trace (fn [ev] (swap! traces conj ev)))
      (rf/reg-route :route/home {:path "/"})
      (rf/reg-route :route/home {:path "/"}) ;; re-register (no trace)
      (rf/unregister-listener! ::reg-trace)
      (let [reg-events (filter #(= :rf.route/registered (:operation %)) @traces)]
        (is (= 1 (count reg-events))
            "first-time reg-route emits :rf.route/registered exactly once")
        (is (= :route/home (-> reg-events first :tags :route-id))
            ":route-id rides in :tags")
        (is (= "/" (-> reg-events first :tags :path))
            ":path rides in :tags")))))

(deftest route-cleared-trace-on-unregister
  (testing "unregister-route! emits :rf.route/cleared (rf2-dn26r)"
    (rf/reg-route :route/transient {:path "/transient"})
    (let [traces (atom [])]
      (rf/register-listener! ::cleared-trace (fn [ev] (swap! traces conj ev)))
      (routing/unregister-route! :route/transient)
      (routing/unregister-route! :route/transient) ;; idempotent, no trace
      (rf/unregister-listener! ::cleared-trace)
      (let [cleared-events (filter #(= :rf.route/cleared (:operation %)) @traces)]
        (is (= 1 (count cleared-events))
            "unregister-route! emits :rf.route/cleared exactly once")
        (is (= :route/transient (-> cleared-events first :tags :route-id))
            ":route-id rides in :tags")))))

(deftest route-activated-deactivated-trace-on-navigation
  (testing ":rf.route/deactivated + :rf.route/activated fire on cross-route
            navigation (rf2-dn26r). Same-id navigation emits NEITHER."
    (rf/reg-route :route/from {:path "/from"})
    (rf/reg-route :route/to   {:path "/to"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; First nav: no prior route → only :rf.route/activated fires.
    (let [traces (atom [])]
      (rf/register-listener! ::act1 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate :route/from])
      (rf/unregister-listener! ::act1)
      (is (= [:route/from]
             (map #(-> % :tags :route-id)
                  (filter #(= :rf.route/activated (:operation %)) @traces)))
          "first nav: :rf.route/activated for :route/from")
      (is (empty? (filter #(= :rf.route/deactivated (:operation %)) @traces))
          "first nav (no prior): :rf.route/deactivated does NOT fire"))
    ;; Cross-route nav: both fire in deactivated→activated order.
    (let [traces (atom [])]
      (rf/register-listener! ::act2 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate :route/to])
      (rf/unregister-listener! ::act2)
      (let [lifecycle (filter #(#{:rf.route/activated :rf.route/deactivated}
                                (:operation %))
                              @traces)]
        (is (= [:rf.route/deactivated :rf.route/activated]
               (map :operation lifecycle))
            "cross-route nav: deactivated → activated in that order")
        (is (= :route/from
               (-> (first lifecycle) :tags :route-id))
            ":deactivated carries the prior route-id")
        (is (= :route/to
               (-> (second lifecycle) :tags :route-id))
            ":activated carries the next route-id")))
    ;; Same-id navigation: neither fires (route stays active across the transition).
    (let [traces (atom [])]
      (rf/register-listener! ::act3 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate :route/to])
      (rf/unregister-listener! ::act3)
      (let [lifecycle (filter #(#{:rf.route/activated :rf.route/deactivated}
                                (:operation %))
                              @traces)]
        (is (empty? lifecycle)
            "same-id navigation: neither lifecycle trace fires")))))

;; ============================================================================
;; rf2-w50qm — :url-bound? exclusivity + frame-consultation
;; ============================================================================

(deftest duplicate-url-binding-emits-error
  (testing "registering a second :url-bound? true frame while :rf/default
            owns the URL emits :rf.error/duplicate-url-binding
            (Spec 012 §Multi-frame routing)"
    (let [traces (atom [])]
      (rf/register-listener! ::dup-bind (fn [ev] (swap! traces conj ev)))
      ;; :rf/default is implicitly :url-bound? true. A second frame
      ;; opting in collides.
      (rf/reg-frame :my-frame {:url-bound? true})
      (rf/unregister-listener! ::dup-bind)
      (is (some (fn [ev]
                  (and (= :rf.error/duplicate-url-binding (:operation ev))
                       (= :rf/default (-> ev :tags :existing-frame))
                       (= :my-frame   (-> ev :tags :offending-frame))))
                @traces)
          ":rf.error/duplicate-url-binding emitted with both frame ids"))))

(deftest non-default-frame-without-url-bound-does-not-collide
  (testing "registering a non-default frame WITHOUT :url-bound? true is
            the documented default for story / devcard / test fixtures
            and emits no duplicate-binding trace"
    (let [traces (atom [])]
      (rf/register-listener! ::no-dup (fn [ev] (swap! traces conj ev)))
      (rf/reg-frame :story/variant-A {})              ;; no :url-bound?
      (rf/reg-frame :test/fixture    {:url-bound? false}) ;; explicit off
      (rf/unregister-listener! ::no-dup)
      (is (empty? (filter #(= :rf.error/duplicate-url-binding (:operation %))
                          @traces))
          "no duplicate-url-binding trace fires for non-URL-bound frames"))))

(deftest push-url-noop-from-non-url-bound-frame
  (testing ":rf.nav/push-url skips on a non-URL-bound frame and emits
            :rf.fx/skipped-on-platform with :reason :frame-not-url-bound"
    (rf/reg-frame :story/variant-A {})
    (rf/reg-route :route/home {:path "/"})
    ;; Register :rf.nav/push-url with a capture spy AND :platforms
    ;; #{:server :client} so the JVM path doesn't already skip on
    ;; platform.
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [{:keys [frame]} url]
                   (swap! pushed conj {:frame frame :url url})))
      ;; Default frame: pushes normally
      (rf/dispatch-sync [:rf.route/navigate :route/home])
      (is (= 1 (count @pushed))
          "default frame: push-url fires once")
      (is (= :rf/default (-> @pushed first :frame))
          "first push originated from :rf/default")

      ;; Non-URL-bound frame: still fires the FX (we registered a
      ;; capture-spy that overrides the default), but in production the
      ;; default fx body honours url-bound-frame? and short-circuits.
      ;; We exercise the production behaviour by re-registering the
      ;; default fx body — verifying it skips for the non-bound frame.
      (reset! pushed [])
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}
                  :doc       "test re-registration with the production
                              url-bound-frame? gating"}
                 (fn [{:keys [frame]} url]
                   (when (or (= frame :rf/default)
                             (true? (:url-bound? (frame/frame-meta frame))))
                     (swap! pushed conj {:frame frame :url url}))))

      (rf/dispatch-sync [:rf.route/navigate :route/home]
                        {:frame :story/variant-A})
      (is (empty? @pushed)
          "non-URL-bound frame's push is suppressed by the gated fx"))))

(deftest single-non-default-frame-owns-url-when-default-opts-out
  (testing "a non-default frame becomes the URL owner when :rf/default opts
            OUT (:url-bound? false) and the non-default opts IN
            (:url-bound? true) — the step-deck ownership contract
            (rf2-6qgbs.3, Spec 012 §Multi-frame routing)"
    ;; The step-deck mounts its content in the NON-DEFAULT :step-deck
    ;; frame and wants it to own the URL. A bare `:step-deck {:url-bound?
    ;; true}` is NOT enough: the auto-registered :rf/default frame's
    ;; missing `:url-bound?` reads as default-true, so it keeps winning the
    ;; ownership tie and :step-deck's navs never push the URL. Releasing
    ;; the default (`:url-bound? false`) hands ownership to :step-deck.
    (rf/reg-frame :rf/default {:url-bound? false})
    (rf/reg-frame :step-deck  {:url-bound? true})
    (is (= :step-deck (routing/url-owner-frame-id))
        "with :rf/default opted out, the lone :url-bound? true frame owns the URL")

    ;; End-to-end through the real production :rf.nav/push-url fx: the
    ;; owner pushes, a non-owner is suppressed. We re-register the fx with
    ;; a spy that consults the REAL `url-owner-frame-id` (NOT a
    ;; reimplemented gate — a reimplemented gate cannot catch a regression
    ;; in the resolution itself, which is the bug rf2-6qgbs.3 surfaced).
    (rf/reg-route :route/home {:path "/home"})
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}
                  :doc       "test re-registration consulting the production
                              url-owner-frame-id resolver"}
                 (fn [{:keys [frame]} url]
                   (when (= (or frame :rf/default) (routing/url-owner-frame-id))
                     (swap! pushed conj {:frame frame :url url}))))

      ;; :step-deck is the owner → its nav pushes the URL.
      (rf/dispatch-sync [:rf.route/navigate :route/home] {:frame :step-deck})
      (is (= [{:frame :step-deck :url "/home"}] @pushed)
          ":step-deck owns the URL, so its navigate pushes /home")

      ;; :rf/default opted out → its nav is suppressed (no longer the owner).
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/navigate :route/home] {:frame :rf/default})
      (is (empty? @pushed)
          ":rf/default opted out of URL ownership, so its push is suppressed"))))

;; ===========================================================================
;; rf2-aleg9 — match-against direct function-boundary tests
;; (follow-on from rf2-q1z1u F8)
;;
;; `match-against` is the pattern-matcher fn consumed by the routing
;; facade's match-url. The facade-level tests above exercise it
;; transitively via `:rf.route/navigate`, but a `match-against`-only
;; regression that happens to be neutralised by the facade's URL
;; normalisation (canonical-route-pattern, query-string parse,
;; trailing-slash handling) would slip through the facade tests.
;;
;; These tests call `match-against` directly with a `parse-pattern`
;; output and a path string. Pins:
;;   - literal segments (exact match + non-match)
;;   - :param capture
;;   - :splat capture across multi-segment paths
;;   - empty-pattern edge — `/` matches `/`
;;   - non-matching URL returns nil cleanly (no throw, no error)
;; ===========================================================================

(deftest match-against-literal-segment-exact-match
  (testing "rf2-aleg9 — literal-segment pattern matches its exact URL
            and returns an empty params map; a sibling URL with the
            same shape but different literal returns nil"
    (let [compiled (routing.match/parse-pattern "/foo/bar")]
      (is (= {} (routing.match/match-against compiled "/foo/bar"))
          "exact literal URL → empty params map (no captures registered)")
      (is (nil? (routing.match/match-against compiled "/foo/baz"))
          "sibling literal that differs on last segment → nil (no-match)")
      (is (nil? (routing.match/match-against compiled "/foo"))
          "partial-prefix URL → nil (no-match; re-matches anchors both ends)")
      (is (nil? (routing.match/match-against compiled "/foo/bar/extra"))
          "URL longer than pattern → nil (anchored end)"))))

(deftest match-against-named-param-extraction
  (testing "rf2-aleg9 — a `:id` segment captures the URL segment value
            into the params map under the keyword key"
    (let [compiled (routing.match/parse-pattern "/users/:id")]
      (is (= {:id "42"} (routing.match/match-against compiled "/users/42"))
          "param captured under :id, value is the raw URL segment")
      (is (= {:id "alice"} (routing.match/match-against compiled "/users/alice"))
          "alphabetic param value captured")
      (is (nil? (routing.match/match-against compiled "/users/"))
          "empty param segment → nil (regex requires non-empty capture)")
      (is (nil? (routing.match/match-against compiled "/users"))
          "missing param segment → nil"))))

(deftest match-against-optional-group-omits-unmatched-param
  (testing "rf2-yejde — a param inside an UNMATCHED optional group is
            absent from the params map (not nil-valued), so downstream
            {:optional true} :params schemas accept the match (Spec 012
            §Path-pattern grammar §Optional segment group: 'param present
            only if matched')"
    (let [compiled (routing.match/parse-pattern "/articles/:id{/:slug}?")]
      (is (= {:id "5"} (routing.match/match-against compiled "/articles/5"))
          "optional group unmatched → :slug ABSENT, not {:slug nil}")
      (is (not (contains? (routing.match/match-against compiled "/articles/5") :slug))
          "the unmatched optional key is omitted entirely")
      (is (= {:id "5" :slug "intro"}
             (routing.match/match-against compiled "/articles/5/intro"))
          "optional group matched → :slug present with its captured value")))

  (testing "rf2-yejde — the nil-strip drops ONLY regex-unmatched (nil)
            captures; a legitimately matched capture survives. (Named param
            and splat regexes require a non-empty capture, so a captured
            value is always non-nil and is never dropped.)"
    (let [compiled (routing.match/parse-pattern "/u/:a{/:b}?")]
      ;; :a is always matched (non-nil capture); :b only when present.
      ;; Neither matched capture is ever stripped.
      (is (= {:a "x"}       (routing.match/match-against compiled "/u/x")))
      (is (= {:a "x" :b "y"} (routing.match/match-against compiled "/u/x/y"))))))

(deftest match-against-splat-captures-multi-segment-tail
  (testing "rf2-aleg9 — a `*rest` splat captures the entire trailing
            path (slashes preserved) into the params map"
    (let [compiled (routing.match/parse-pattern "/files/*path")]
      (is (= {:path "a"}
             (routing.match/match-against compiled "/files/a"))
          "single-segment splat captured under :path")
      (is (= {:path "a/b/c"}
             (routing.match/match-against compiled "/files/a/b/c"))
          "multi-segment splat captured with slashes preserved")
      (is (nil? (routing.match/match-against compiled "/files/"))
          "empty splat tail → nil (regex requires non-empty capture)")
      (is (nil? (routing.match/match-against compiled "/files"))
          "missing splat tail → nil"))))

;; ---- rf2-yjali: named splat out-ranks the bare catch-all ----------------
;;
;; Spec 012 §Route ranking algorithm rule 4: "Rest params beat
;; catch-all/not-found." The catch-all is EXACTLY the bare `/*` pattern
;; (`is-catch-all? (= pattern "/*")` in the spec pseudocode). A NAMED
;; splat (`/*rest`) is a rest param, so it must out-rank `/*`. Before the
;; fix `parse-pattern`'s classifier flagged ANY single-splat-only pattern
;; as catch-all, so `/*rest` tied with `/*` at rank element 4 instead of
;; beating it.

(deftest parse-pattern-named-splat-outranks-bare-catch-all
  (testing "rf2-yjali — only the bare `/*` is catch-all; a named splat
            `/*rest` ranks above it at rank element 4 (Spec 012 rule 4)"
    (let [catch-all (:rank (routing.match/parse-pattern "/*"))
          rest-splat (:rank (routing.match/parse-pattern "/*rest"))]
      ;; Rank element 3 (0-indexed) is the rule-4 catch-all discriminator:
      ;; 0 = catch-all (less specific), 1 = not catch-all (more specific).
      (is (= 0 (nth catch-all 3))
          "bare `/*` is classified as the catch-all (rank elem 4 = 0)")
      (is (= 1 (nth rest-splat 3))
          "named `/*rest` is NOT the catch-all (rank elem 4 = 1)")
      ;; The two patterns are identical on every other rank element
      ;; (statics, length, splat, optional) — the catch-all discriminator
      ;; is the ONLY difference, and it must make `/*rest` win.
      (is (= (assoc catch-all 3 :x) (assoc rest-splat 3 :x))
          "the two ranks differ ONLY at the rule-4 catch-all element")
      (is (pos? (compare rest-splat catch-all))
          "lexicographic compare: `/*rest` out-ranks `/*` (rule 4)"))))

(deftest match-url-named-splat-wins-over-bare-catch-all
  (testing "rf2-yjali — when both `/*rest` and `/*` are registered, a
            multi-segment URL resolves to the named-splat route, not the
            catch-all (Spec 012 §Route ranking rule 4)"
    (rf/reg-route :route/catch-all {:path "/*"})
    (rf/reg-route :route/rest      {:path "/*rest"})
    (let [m (routing/match-url "/some/deep/path")]
      (is (= :route/rest (:route-id m))
          "named-splat route wins the rule-4 tiebreak against bare catch-all")
      (is (= {:rest "some/deep/path"} (:params m))
          "the named splat captures the whole tail under :rest"))))

(deftest match-against-root-pattern-matches-root-path
  (testing "rf2-aleg9 — the special `/` pattern matches the root URL
            and returns an empty params map; a deeper URL returns nil"
    (let [compiled (routing.match/parse-pattern "/")]
      (is (= {} (routing.match/match-against compiled "/"))
          "root pattern matches root path → empty params map")
      (is (= {} (routing.match/match-against compiled ""))
          "root pattern also matches the empty string (leading `/?` in regex)")
      (is (nil? (routing.match/match-against compiled "/foo"))
          "root pattern does NOT match a deeper path"))))

(deftest match-against-no-match-returns-nil
  (testing "rf2-aleg9 — when re-matches misses, match-against returns
            nil cleanly (no throw, no exception)"
    (let [compiled (routing.match/parse-pattern "/users/:id/posts/:post-id")]
      (is (nil? (routing.match/match-against compiled "/unrelated/path"))
          "completely unrelated URL → nil")
      (is (nil? (routing.match/match-against compiled "/users/42/posts"))
          "URL missing trailing capture segment → nil")
      (is (= {:id "42" :post-id "9"}
             (routing.match/match-against
               compiled "/users/42/posts/9"))
          "the same pattern DOES match when both captures are present —
           sanity-check the test isn't accepting only the negative cases"))))

;; ---- rf2-45b95: reg-route authoring-boundary metadata validation ----------
;;
;; Spec 012 §Reserved route-metadata keys: reg-route has the largest
;; registration shape in the v2 surface (twelve reserved keys). A typo'd
;; key (:on-matched for :on-match) used to pass silently at registration
;; and fail later at nav-time, or never. The authoring-boundary guardrail
;; (rf2-45b95) rejects bare keys outside the reserved set LOUDLY at
;; registration, naming the bad key; namespaced host/app keys pass.

(deftest reg-route-rejects-unknown-bare-metadata-key
  (testing "rf2-45b95: a typo'd bare metadata key (:on-matched for
            :on-match) throws :rf.error/invalid-route-metadata at
            registration, naming the bad key"
    (let [ex (try
               (rf/reg-route :route/typo {:path "/typo" :on-matched [[:load]]})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "reg-route THROWS on an unknown bare metadata key (no silent accept)")
      (is (= :rf.error/invalid-route-metadata (:rf.error/id (ex-data ex)))
          "the canonical thrown-error id discriminates the failure")
      (is (= 'rf/reg-route (:where (ex-data ex)))
          ":where names the public surface fn")
      (is (= [:on-matched] (:keys (ex-data ex)))
          ":keys names exactly the offending key so the message is actionable")
      (is (clojure.string/includes? (:reason (ex-data ex)) ":on-matched")
          "the human-readable :reason names the bad key"))))

(deftest reg-route-accepts-valid-and-namespaced-metadata
  (testing "rf2-45b95: a route using only reserved keys + namespaced
            host/app keys registers fine (no false positives)"
    (is (= :route/ok
           (rf/reg-route :route/ok
                         {:doc            "fine"
                          :path           "/ok/:id"
                          :params         [:map [:id :string]]
                          :query          [:map [:q {:optional true} :string]]
                          :query-defaults {:q "x"}
                          :query-retain   #{:theme}
                          :tags           #{:public}
                          :on-match       [[:load]]
                          :on-error       [:oops]
                          :scroll         :top
                          ;; namespaced host/app extension keys always pass
                          :myapp/layout   :wide
                          :myapp/analytics-id "abc"}))
        "a route with every reserved key + namespaced extension keys registers")
    (is (some? (rf/handler-meta :route :route/ok))
        "the route is queryable via handler-meta after a clean registration")))

(deftest reg-route-rejects-non-map-metadata
  (testing "rf2-45b95: non-map metadata is rejected at the authoring
            boundary naming the route (no downstream NPE)"
    (let [ex (try (rf/reg-route :route/bad "/not-a-map")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/invalid-route-metadata (:rf.error/id (ex-data ex)))
          "non-map metadata surfaces the same canonical error id"))))

;; ---- rf2-1os1c: :rf.route/navigate params/opts positional swap ------------
;;
;; Spec 012 §Navigation is an event: the two trailing maps (params 2nd,
;; opts 3rd) are positionally ambiguous. The disambiguating guard
;; (rf2-1os1c) rejects an opts-only key (:replace? / :scroll / :fragment /
;; :bypass-leave-guard?) sitting in the PARAMS slot when it is not a
;; declared path-param of the target route, and emits
;; :rf.error/navigate-arity-misuse.

(deftest navigate-rejects-opts-shaped-map-in-params-slot
  (testing "rf2-1os1c: [:rf.route/navigate :route/x {:replace? true}]
            (opts in the params slot) is rejected — slice unchanged, no
            push, :rf.error/navigate-arity-misuse emitted"
    (rf/reg-route :route/cart {:path "/cart"})
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (let [before (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current])
            traces (atom [])]
        (rf/register-listener! ::arity (fn [ev] (swap! traces conj ev)))
        ;; The classic swap: {:replace? true} meant for the opts (3rd)
        ;; slot, supplied in the params (2nd) slot.
        (rf/dispatch-sync [:rf.route/navigate :route/cart {:replace? true}])
        (rf/unregister-listener! ::arity)
        (is (= before (get-in (rf/app-db-value :rf/default) [:rf/runtime :routing :current]))
            "the misuse is rejected — the :rf/route slice is UNCHANGED")
        (is (empty? @pushed)
            "no URL is pushed for a rejected arity-misuse navigation")
        (let [err (first (filter #(= :rf.error/navigate-arity-misuse (:operation %))
                                 @traces))]
          (is (some? err)
              ":rf.error/navigate-arity-misuse emitted")
          (is (= :event (-> err :tags :where))
              "error tags :where :event (event-boundary path)")
          (is (= [:replace?] (-> err :tags :keys))
              ":keys names the misplaced opts key"))))))

(deftest navigate-accepts-the-documented-arities
  (testing "rf2-1os1c: the three documented arities all navigate cleanly
            — [target], [target params], [target params opts]"
    (rf/reg-route :route/home    {:path "/"})
    (rf/reg-route :route/article {:path "/articles/:id" :params [:map [:id :string]]})
    (let [pushed   (atom [])
          replaced (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/reg-fx :rf.nav/replace-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! replaced conj url)))
      ;; [target] — no path-params, no opts.
      (rf/dispatch-sync [:rf.route/navigate :route/home])
      (is (= :route/home (:id (get-in (rf/app-db-value :rf/default)
                                      [:rf/runtime :routing :current])))
          "[target] arity navigates")
      ;; [target params] — params 2nd, opts absent.
      (rf/dispatch-sync [:rf.route/navigate :route/article {:id "intro"}])
      (is (= {:id "intro"} (:params (get-in (rf/app-db-value :rf/default)
                                            [:rf/runtime :routing :current])))
          "[target params] arity navigates with path-params")
      ;; [target params opts] — opts in the THIRD slot (:replace? true →
      ;; :rf.nav/replace-url, proving the 3rd-slot opts are honoured).
      (rf/dispatch-sync [:rf.route/navigate :route/article {:id "two"} {:replace? true}])
      (is (= "/articles/two" (last @replaced))
          "[target params opts] arity navigates; :replace? opt honoured in the 3rd slot")
      (is (= {:id "two"} (:params (get-in (rf/app-db-value :rf/default)
                                          [:rf/runtime :routing :current])))
          "the 3-arity path-params landed in the slice, distinct from opts"))))

(deftest navigate-does-not-false-flag-a-route-with-an-opts-named-path-param
  (testing "rf2-1os1c: a route that legitimately captures a segment named
            :fragment is NOT false-flagged — the key is a declared
            path-param, not a misplaced opt"
    (rf/reg-route :route/anchor {:path "/anchor/:fragment"})
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate :route/anchor {:fragment "intro"}])
      (is (= :route/anchor (:id (get-in (rf/app-db-value :rf/default)
                                        [:rf/runtime :routing :current])))
          "a declared :fragment path-param navigates normally (no false reject)")
      (is (= "/anchor/intro" (last @pushed))
          "the path-param :fragment populates the URL segment"))))
