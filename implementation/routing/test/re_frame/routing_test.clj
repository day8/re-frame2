(ns re-frame.routing-test
  "JVM smoke tests for re-frame.routing. The conformance fixtures cover
  the canonical surface (URL ↔ params, fragment, blocking, nav-token);
  these tests exercise the JVM-pipeline composition end-to-end and
  pin a few behaviours the conformance DSL doesn't express directly:

  - routing-bootstrap            — reg-route + dispatch :rf.route/navigate
                                   updates the :route slice end-to-end
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
            [re-frame.routing]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init!)
  ;; Framework events / fx (routing.cljc, ssr.cljc) are registered at
  ;; ns-load; clear-all! wiped them. Reload to resurrect.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  ((requiring-resolve 're-frame.routing/reset-counters!))
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- Spec 012 §Navigation is an event -------------------------------------

(deftest routing-bootstrap
  (testing "reg-route + dispatch :rf.route/navigate writes the :route slice"
    ;; Per Spec 012 §Navigation is an event: dispatching :rf.route/navigate
    ;; with a route-id + params updates :route.{id,params,query,...} and
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
      (let [slice (:route (rf/get-frame-db :rf/default))]
        (is (= :route/article (:id slice))
            "the :route slice carries the navigation target")
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
    (rf/dispatch-sync [:rf/url-changed "/editor/articles/A"])
    (is (= :editor/article (get-in (rf/get-frame-db :rf/default)
                                   [:route :id]))
        "initial nav landed on :editor/article")

    ;; 2. Dirty the form so :can-leave? returns false.
    (rf/dispatch-sync [:editor/dirty true])

    ;; 3. Try to leave. Guard rejects → pending slot is set; URL unchanged.
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (let [pending (:rf/pending-navigation (rf/get-frame-db :rf/default))]
      (is (some? pending)
          ":rf/pending-navigation is populated on guard rejection")
      (is (= "/cart" (get-in pending [:request :url]))
          "the requested URL is captured for resume")
      (is (= :editor/article
             (get-in (rf/get-frame-db :rf/default) [:route :id]))
          "the :route slice does NOT change when blocked"))

    ;; 4. CANCEL — slot clears; original route stays active.
    (rf/dispatch-sync [:rf.route/cancel "pn-1"])
    (is (nil? (:rf/pending-navigation (rf/get-frame-db :rf/default)))
        "cancel clears :rf/pending-navigation")
    (is (= :editor/article
           (get-in (rf/get-frame-db :rf/default) [:route :id]))
        "cancel does NOT navigate")

    ;; 5. Now block again — and CONTINUE this time.
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (is (some? (:rf/pending-navigation (rf/get-frame-db :rf/default)))
        "second blocked request reseats the slot")
    (rf/dispatch-sync [:rf.route/continue "pn-2"])
    (is (nil? (:rf/pending-navigation (rf/get-frame-db :rf/default)))
        "continue clears :rf/pending-navigation")
    (is (= :route/cart (get-in (rf/get-frame-db :rf/default) [:route :id]))
        "continue completes the original navigation")))

;; ---- Spec 012 §Navigation tokens — stale-result suppression --------------

(deftest routing-nav-token-staleness
  (testing "two in-flight navigations: the older nav-token's result is suppressed"
    ;; Per Spec 012 §Navigation tokens — stale-result suppression: each
    ;; navigation allocates a fresh nav-token; async results carry the
    ;; token captured at request time; when a result arrives whose token
    ;; mismatches the current slice's :nav-token, the runtime suppresses
    ;; it and emits :route.nav-token/stale-suppressed.
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]})
    (rf/reg-event-db :article/loaded
                     (fn [db [_ id payload]]
                       (assoc db :article {:id id :payload payload})))

    (let [traces (atom [])]
      (rf/register-trace-cb! ::nav-token (fn [ev] (swap! traces conj ev)))

      ;; 1. Navigate to /articles/A. nav-token allocates → "nav-1".
      (rf/dispatch-sync [:rf/url-changed "/articles/A"])
      (is (= "nav-1" (get-in (rf/get-frame-db :rf/default)
                             [:route :nav-token]))
          "first navigation got nav-1")

      ;; 2. Before A's response lands, navigate to /articles/B → "nav-2".
      (rf/dispatch-sync [:rf/url-changed "/articles/B"])
      (is (= "nav-2" (get-in (rf/get-frame-db :rf/default)
                             [:route :nav-token]))
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

      (rf/remove-trace-cb! ::nav-token)

      (is (= {:id "B" :payload "B-payload"}
             (:article (rf/get-frame-db :rf/default)))
          "only B's payload committed; A's was suppressed")

      (is (some (fn [ev]
                  (and (= :route.nav-token/stale-suppressed (:operation ev))
                       (= "nav-1" (-> ev :tags :carried-token))
                       (= "nav-2" (-> ev :tags :current-token))
                       (= :article/loaded (-> ev :tags :event-id))))
                @traces)
          "expected :route.nav-token/stale-suppressed trace for the A response"))))

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

      ;; 5. :rf/url-changed (URL-driven) also emits the fx — default :top.
      (reset! calls [])
      (rf/dispatch-sync [:rf/url-changed "/articles"])
      (is (= :top (-> @calls first :strategy))
          ":rf/url-changed emits :rf.nav/scroll with default :top")

      ;; 6. :rf.route/handle-url-change (popstate / initial) defaults to
      ;;    :restore — the saved position trumps a forward-style :top.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/articles"])
      (is (= :restore (-> @calls first :strategy))
          ":rf.route/handle-url-change emits :rf.nav/scroll with default :restore")

      ;; 7. Fragment in URL is forwarded in the fx args.
      (reset! calls [])
      (rf/dispatch-sync [:rf/url-changed "/articles/intro#section-2"])
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
