(ns re-frame.routing-plan-seam-test
  "Focused tests for the ONE resolved-target / route-plan seam
  `re-frame.routing.resolve` (EP-0037 R0b).

  Pins the ResolvedTarget fact shape, the route-plan every door builds
  (`:source` / `:cause` / `:target` / `:branch` / `:leaf-plan`), the
  parent-to-leaf branch derivation (shared with the `:rf.route/chain` sub),
  the behaviour-preserving leaf resource plan (the route's `:on-match`
  loaders), and the R0 diagnostic projection. Per Spec 012 §The one planning
  pipeline and §Resolved target and the plan diagnostic projection.

  The pure-constructor tests below prove the seam's SHAPE. The door-wiring
  tests at the foot prove the doors actually reach it — that the link door and
  the commit hop resolve one URL to ONE target, and that the URL-change door
  reports which of its three sub-doors fired. A test that only loops
  `resolver/causes` against the pure constructor cannot fail when a door passes
  the wrong cause or resolves its own target."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.routing :as routing]
            [re-frame.routing.resolve :as resolver]
            [re-frame.routing.subs :as routing-subs]
            [re-frame.routing.url-change :as url-change]
            [re-frame.routing-test-support :as rts]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rts/reset-runtime)

;; ---- ResolvedTarget: facts, not intent ------------------------------------

(deftest resolved-target-reflects-facts-verbatim
  (testing "the ResolvedTarget carries the resolved FACTS (facts say :route-id, intent says :to)"
    (is (= {:route-id :route/article
            :params   {:slug "routing-as-data"}
            :query    {:tab "comments"}
            :fragment "reply-42"
            :url      "/articles/routing-as-data?tab=comments#reply-42"}
           (resolver/resolved-target
             {:route-id :route/article
              :params   {:slug "routing-as-data"}
              :query    {:tab "comments"}
              :fragment "reply-42"
              :url      "/articles/routing-as-data?tab=comments#reply-42"}))))
  (testing "resolved-target never re-normalises — an empty :query stays {} exactly as the door resolved it"
    (is (= {} (:query (resolver/resolved-target {:route-id :route/home :params {} :query {}}))))))

;; ---- branch + leaf plan ---------------------------------------------------

(deftest branch-of-is-the-parent-to-leaf-chain
  (routing/reg-route :route/dashboard {} "/dashboard")
  (routing/reg-route :route/reports {:parent :route/dashboard} "/dashboard/reports")
  (routing/reg-route :route/report {:parent :route/reports} "/dashboard/reports/:id")
  (testing "the plan branch is [parent-most … leaf], shared with the :rf.route/chain sub reduction"
    (is (= [:route/dashboard :route/reports :route/report]
           (resolver/branch-of :route/report)))
    (is (= (routing-subs/chain-from-meta :route/report)
           (resolver/branch-of :route/report))
        "branch-of and the chain sub can never disagree — one :parent walk")))

(deftest leaf-plan-of-is-the-behaviour-preserving-on-match-loader
  (routing/reg-route :route/article
    {:on-match [[:article/load] [:comments/load]]} "/articles/:slug")
  (routing/reg-route :route/home {} "/")
  (testing "the leaf plan is the route's :on-match loader vector (the loaders that already fire)"
    (is (= [[:article/load] [:comments/load]] (resolver/leaf-plan-of :route/article))))
  (testing "a route with no :on-match has an empty leaf plan"
    (is (= [] (resolver/leaf-plan-of :route/home))))
  (testing "an unregistered / not-found target has an empty leaf plan"
    (is (= [] (resolver/leaf-plan-of :rf.route/not-found)))))

;; ---- the route plan every door builds -------------------------------------

(deftest route-plan-carries-source-cause-target-branch-leaf-plan
  (routing/reg-route :route/section {} "/section")
  (routing/reg-route :route/article
    {:parent :route/section :on-match [[:article/load]]} "/section/:slug")
  (let [target (resolver/resolved-target
                 {:route-id :route/article :params {:slug "x"} :query {}
                  :fragment nil :url "/section/x"})
        plan   (resolver/route-plan {:source {:to :route/article :params {:slug "x"}}
                                     :cause  :navigate
                                     :target target})]
    (testing "the plan carries the caller's source and cause"
      (is (= {:to :route/article :params {:slug "x"}} (:source plan)))
      (is (= :navigate (:cause plan))))
    (testing "the plan's :target IS the ResolvedTarget the door commits (load-bearing, not a copy)"
      (is (= target (:target plan))))
    (testing "the plan derives the parent-to-leaf branch and the leaf resource plan from the target"
      (is (= [:route/section :route/article] (:branch plan)))
      (is (= [[:article/load]] (:leaf-plan plan))))))

(deftest route-plan-is-cause-parametric-across-the-doors
  (routing/reg-route :route/home {} "/")
  (let [target (resolver/resolved-target {:route-id :route/home :params {} :query {} :url "/"})]
    (testing "every door builds the plan through the same fn, differing ONLY in cause"
      (doseq [cause resolver/causes]
        (let [plan (resolver/route-plan {:source {:url "/"} :cause cause :target target})]
          (is (= cause (:cause plan)))
          (is (= target (:target plan)))
          (is (= [:route/home] (:branch plan))))))))

;; ---- the R0 diagnostic projection -----------------------------------------

(deftest plan-projection-exposes-only-the-r0-keys
  (routing/reg-route :route/home {} "/")
  (let [plan (resolver/route-plan
               {:source {:url "/"} :cause :popstate
                :target (resolver/resolved-target {:route-id :route/home :params {} :query {} :url "/"})})
        proj (resolver/plan-projection plan)]
    (testing "the projection is exactly the R0 keys — the minimum needed to prove the shared spine"
      (is (= #{:source :cause :target :branch :leaf-plan} (set (keys proj))))
      (is (= (set resolver/r0-projection-keys) (set (keys proj)))))
    (testing "the projection is a pure view of the plan the doors already build"
      (is (= (select-keys plan resolver/r0-projection-keys) proj)))))

;; ---- the projection as trace tags (mayor ruling on rf2-kqxe6.3) ------------
;;
;; The projection was unreachable from an executed navigation: only a tool
;; holding a plan VALUE could read it. The ruled fix is one trace per door
;; commit branch — carrying the URL through the EXISTING `redact-url-tag` path
;; and the params / query KEY SETS rather than their values, because a trace tag
;; is an egress surface the route's `:sensitive` classification (lowered against
;; runtime-db slice PATHS) cannot reach.

(deftest plan-trace-tags-carries-the-projection-without-the-carriers
  (routing/reg-route :route/section {} "/section")
  (routing/reg-route :route/article
    {:parent :route/section :on-match [[:article/load] [:comments/load 7]]}
    "/section/:slug")
  (let [plan (resolver/route-plan
               {:cause  :navigate
                :source {:to :route/article :params {:slug "x"} :query {:invite "SECRET100"}}
                :target (resolver/resolved-target
                          {:route-id :route/article
                           :params   {:slug "x"}
                           :query    {:invite "SECRET100" :tab :comments}
                           :fragment "reply-42"
                           :url      "/section/x?invite=SECRET100&tab=comments#reply-42"})})
        tags (resolver/plan-trace-tags plan)]
    (testing "the non-carrier halves of the projection ride WHOLE"
      (is (= :navigate (:cause tags)))
      (is (= :route/article (:route-id tags)))
      (is (= [:route/section :route/article] (:branch tags)))
      (is (= [:article/load :comments/load] (:leaf-plan-ids tags))
          "the leaf plan's event IDs — not their argument positions"))
    (testing "the URL rides the EXISTING redact-url-tag path — path kept, query
              VALUES and the whole #fragment redacted. No second redaction route
              for the same datum."
      (is (= "/section/x?invite=rf/redacted&tab=rf/redacted#rf/redacted"
             (:url tags))))
    (testing "params / query contribute KEY SETS, not values — that :invite was
              bound is diagnostic; that invite=SECRET100 is the leak"
      (is (= [:slug] (:param-keys tags)))
      (is (= [:invite :tab] (:query-keys tags)))
      (is (not (contains? tags :params)))
      (is (not (contains? tags :query))))
    (testing "and NOTHING in the tag map reproduces a carrier value — not the
              query value, not the fragment, not the caller's :source address"
      (is (not (re-find #"SECRET100" (pr-str tags))))
      (is (not (re-find #"reply-42" (pr-str tags))))
      (is (not (contains? tags :source))))
    (testing "a param-less / query-less target yields empty key sets, never nil"
      (let [bare (resolver/plan-trace-tags
                   (resolver/route-plan
                     {:cause :initial :source {:url "/section"}
                      :target (resolver/resolved-target
                                {:route-id :route/section :params {} :query {}
                                 :url "/section"})}))]
        (is (= [] (:param-keys bare)))
        (is (= [] (:query-keys bare)))
        (is (= [] (:leaf-plan-ids bare)))
        (is (= "/section" (:url bare)) "a bare path is not a carrier — verbatim")))))

(defn- quiet-nav-fx!
  "No-op the host navigation fx so a JVM navigation commits without reaching a
  browser-only handler."
  []
  (doseq [fx-id [:rf.nav/push-url :rf.nav/replace-url
                 :rf.nav/capture-scroll :rf.nav/scroll
                 :rf.server/set-status]]
    (fx/reg-fx fx-id {:platforms #{:server :client}} (fn [_ _] nil))))

(defmacro ^:private planned
  "The `:rf.route/planned` traces emitted while `body` runs."
  [& body]
  `(with-trace-recorder! [traces# {:pred #(= :rf.route/planned (:operation %))}]
     ~@body
     @traces#))

(deftest every-door-commit-branch-emits-one-plan-trace
  (routing/reg-route :route/home {} "/")
  (routing/reg-route :route/article {:on-match [[:article/load]]} "/articles/:slug")
  (quiet-nav-fx!)
  (testing "the PROGRAMMATIC door — the projection is now reachable from an
            executed navigation, which is the whole completeness obligation"
    (let [ts (planned (rf/dispatch-sync [:rf.route/navigate {:to :route/article
                                                             :params {:slug "a"}}]))]
      (is (= 1 (count ts)) "exactly one plan trace per commit")
      (let [{:keys [cause route-id branch leaf-plan-ids frame]} (:tags (first ts))]
        (is (= :navigate cause))
        (is (= :route/article route-id))
        (is (= [:route/article] branch))
        (is (= [:article/load] leaf-plan-ids))
        (is (= :rf/default frame)
            "frame-stamped — epoch capture admits only frame-tagged traces"))))
  (testing "the URL-driven door reports which of its four sub-doors fired"
    (doseq [[cause dispatch] {:link     [:rf.route/transitioned "/articles/b"]
                              :popstate [:rf.route/handle-url-change "/articles/c"
                                         {:rf.route/cause :popstate}]
                              :initial  [:rf.route/handle-url-change "/articles/d"]}]
      (let [ts (planned (rf/dispatch-sync dispatch))]
        (is (= 1 (count ts)) (str cause " emitted exactly one plan trace"))
        (is (= cause (:cause (:tags (first ts))))
            (str "a door that hardcoded one cause for four sub-doors fails here"))
        (is (= :rf/default (:frame (:tags (first ts))))))))
  (testing "the SSR feed reports :ssr off the frame's :platform"
    (let [f  (frame/make-anon-frame-record! {:platform :server})
          ts (planned (rf/dispatch-sync [:rf.route/handle-url-change "/articles/e"]
                                        {:frame f}))]
      (is (= 1 (count ts)))
      (is (= :ssr (:cause (:tags (first ts)))))
      (is (= f (:frame (:tags (first ts))))
          "attributed to the SERVER frame, not the ambient :rf/default")))
  (testing "the NON-commit branches emit none — an exact no-op and a
            fragment-only anchor change are not plan commits"
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/f"])
    (is (empty? (planned (rf/dispatch-sync [:rf.route/handle-url-change "/articles/f"])))
        "an exact no-op plans nothing")
    (is (empty? (planned (rf/dispatch-sync [:rf.route/handle-url-change "/articles/f#anchor"])))
        "a fragment-only transition plans nothing (no nav-token, no re-plan)")))

(deftest an-executed-navigations-plan-trace-is-not-a-carrier
  (routing/reg-route :route/home {} "/")
  (routing/reg-route :route/invite {} "/invite/:id")
  (quiet-nav-fx!)
  (testing "a real navigation carrying a secret in its query and fragment emits a
            plan trace that reproduces NEITHER — the projection became reachable
            without becoming a carrier"
    (let [ts (planned
               (rf/dispatch-sync [:rf.route/navigate
                                  {:to       :route/invite
                                   :params   {:id "acct-42"}
                                   :query    {:invite "SECRET100"}
                                   :fragment "tok-99"}]))
          tags (:tags (first ts))]
      (is (= 1 (count ts)))
      (is (= [:id] (:param-keys tags)))
      (is (= [:invite] (:query-keys tags)))
      (is (not (re-find #"SECRET100" (pr-str tags))))
      (is (not (re-find #"tok-99" (pr-str tags))))
      (is (= "/invite/acct-42?invite=rf/redacted#rf/redacted" (:url tags))
          "the structured PATH survives — it is what a consumer branches on"))))

;; ---- the URL -> ResolvedTarget extraction (ONE definition) ----------------

(deftest url-resolution-normalises-every-fallback-to-the-canonical-target
  (routing/reg-route :route/article {} "/articles/:slug")
  (routing/reg-route :route/typed {:params [:map [:id :int]]} "/typed/:id")
  (testing "a match resolves to the matched route's facts"
    (is (= {:route-id :route/article :params {:slug "x"} :query {}
            :fragment nil :url "/articles/x"}
           (:target (resolver/url-resolution "/articles/x")))))
  (testing "a bare miss normalises to the reserved :rf.route/not-found target"
    (let [{:keys [target fallback? matched?]} (resolver/url-resolution "/no-such-thing")]
      (is (= {:route-id :rf.route/not-found
              :params   {:url "/no-such-thing"}
              :query    {}
              :fragment nil
              :url      "/no-such-thing"}
             target)
          "the reserved route-id, the {:url …} params vocabulary, and an EMPTIED query")
      (is fallback?)
      (is (not matched?))))
  (testing "a validation fail is a MATCH that still normalises to not-found, with :reason"
    (let [{:keys [target validation-fail? matched?]} (resolver/url-resolution "/typed/not-an-int")]
      (is (= :rf.route/not-found (:route-id target)))
      (is (= {:url "/typed/not-an-int" :reason :validation} (:params target)))
      (is validation-fail?)
      (is matched? "the pattern matched — the SCHEMA rejected it")))
  (testing "a malformed URL carries :reason :malformed-url and NO fragment"
    (let [{:keys [target malformed?]} (resolver/url-resolution "/articles/%zz#frag")]
      (is (= :rf.route/not-found (:route-id target)))
      (is (= :malformed-url (:reason (:params target))))
      (is (nil? (:fragment target)) "the fragment may itself be the decode-fail site")
      (is malformed?)))
  (testing "target-of-url is url-resolution's :target — one definition, two arities of need"
    (is (= (:target (resolver/url-resolution "/no-such-thing"))
           (resolver/target-of-url "/no-such-thing")))))

;; ===========================================================================
;; Door wiring — the doors REACH the seam
;; ===========================================================================

(defn- rdb [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- current-id [] (get-in (rdb) [:rf.runtime/routing :current :route-id]))

(defn- register-denying-not-found!
  "A `:home` route plus a registered `:rf.route/not-found` whose `:can-enter`
  DENIES. Returns `[guard-calls denials pushed]` — the guard-invocation
  counter, the captured `:rf.route/entry-denied` payloads, and the URLs handed
  to `:rf.nav/push-url`."
  []
  (let [calls  (atom 0)
        seen   (atom [])
        pushed (atom [])]
    (rf/reg-route :home {} "/home")
    (rf/reg-route :rf.route/not-found {:can-enter [:deny/not-found]} "/not-found")
    (rf/reg-sub :deny/not-found (fn [_ _] (swap! calls inc) false))
    (rf/reg-event :rf.route/entry-denied (fn [_ [_ d]] (swap! seen conj d) {}))
    (fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
               (fn [_ url] (swap! pushed conj url)))
    (fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
    [calls seen pushed]))

(deftest link-door-decides-the-same-target-the-commit-hop-would-commit
  (testing "a dead LINK resolves through the shared seam, so the reserved
            :rf.route/not-found route's :can-enter is consulted — the link door
            used to decide against a target with a nil :route-id, find no
            guard, and let the second hop commit the denied route"
    (let [[calls seen _] (register-denying-not-found!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
      (reset! calls 0) (reset! seen [])
      (rf/dispatch-sync [:rf.route/url-requested {:url "/missing-link"}])
      (is (= 1 @calls) ":can-enter on :rf.route/not-found evaluated exactly once")
      (is (= 1 (count @seen)) "one :rf.route/entry-denied")
      (is (= :home (current-id)) "the denial is TERMINAL — the slice did not move")))
  (testing "and the equivalent PROGRAMMATIC door agrees — the two doors were
            the pair the audit caught disagreeing"
    (let [[calls seen _] (register-denying-not-found!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
      (reset! calls 0) (reset! seen [])
      (rf/dispatch-sync [:rf.route/navigate {:url "/missing-programmatic"}])
      (is (= 1 @calls))
      (is (= 1 (count @seen)))
      (is (= :home (current-id))))))

(deftest same-unmatched-link-is-an-exact-no-op
  (testing "clicking the link for the ALREADY-ACTIVE not-found URL is an exact
            no-op (Spec 012 §Per-route data loading rule 3): the link door sees
            the no-op only because it resolves the same canonical target the
            slice carries, so no history entry is pushed"
    (rf/reg-route :home {} "/home")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
      ;; Land on the not-found slice for /same-miss.
      (rf/dispatch-sync [:rf.route/handle-url-change "/same-miss"])
      (is (= :rf.route/not-found (current-id)))
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/url-requested {:url "/same-miss"}])
      (is (empty? @pushed)
          "no :rf.nav/push-url — the exact no-op terminated before history moved")
      (is (= :rf.route/not-found (current-id))))))

;; ---- the URL-change door reports WHICH of its three sub-doors fired --------

(deftest url-change-cause-resolves-the-true-sub-door
  (testing "the framework listener's :rf.route/cause rider wins"
    (is (= :popstate (url-change/url-change-cause :rf/default {:rf.route/cause :popstate})))
    (is (= :initial  (url-change/url-change-cause :rf/default {:rf.route/cause :initial}))))
  (testing "a rider outside the closed cause set cannot invent a sixth cause"
    (is (= :initial (url-change/url-change-cause :rf/default {:rf.route/cause :not-a-cause})))
    (is (= :initial (url-change/url-change-cause :rf/default {:rf.route/cause "popstate"}))))
  (testing "no rider on a CLIENT frame is the initial / direct-URL feed"
    (is (= :initial (url-change/url-change-cause :rf/default {})))
    (is (= :initial (url-change/url-change-cause :rf/default nil))))
  (testing "no rider on a SERVER frame is the SSR request-URL feed"
    (is (= :ssr (url-change/url-change-cause
                  (frame/make-anon-frame-record! {:platform :server}) {})))))

(deftest executed-url-change-navigation-carries-its-true-cause
  (testing "the cause the DOOR passes is observable on the denial payload, so a
            door that hardcodes one cause for three sub-doors fails here"
    (let [[_ seen _] (register-denying-not-found!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/home"])

      (reset! seen [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/miss-a" {:rf.route/cause :popstate}])
      (is (= [:popstate] (mapv :cause @seen)) "Back/Forward reports :popstate")

      (reset! seen [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/miss-b" {:rf.route/cause :initial}])
      (is (= [:initial] (mapv :cause @seen)) "the initial URL sync reports :initial")

      (reset! seen [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/miss-c"])
      (is (= [:initial] (mapv :cause @seen))
          "a bare client-frame dispatch is an initial feed, NOT :popstate")))
  (testing "the SSR feed — dispatched by the app's own :initial-events, so it
            carries no rider — reports :ssr off the frame's :platform"
    (let [[_ seen _] (register-denying-not-found!)
          f          (frame/make-anon-frame-record! {:platform :server})]
      (fx/reg-fx :rf.server/set-status {:platforms #{:server :client}} (fn [_ _] nil))
      (reset! seen [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/miss-ssr"] {:frame f})
      (is (= [:ssr] (mapv :cause @seen)))))
  (testing "the forward link/push door still reports :link"
    (let [[_ seen _] (register-denying-not-found!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
      (reset! seen [])
      (rf/dispatch-sync [:rf.route/transitioned "/miss-link"])
      (is (= [:link] (mapv :cause @seen))))))
