(ns re-frame.routing-plan-seam-test
  "Focused tests for the ONE resolved-target / route-plan seam
  `re-frame.routing.resolve` (EP-0037 R0).

  Pins the ResolvedTarget fact shape, the route-plan every door builds
  (`:source` / `:cause` / `:target` / `:branch` / `:leaf-plan`), the leaf
  resource plan (the route's `:on-match` loaders), and the plan diagnostic
  projection. Per Spec 012 §The one planning pipeline and §Resolved target and
  the plan diagnostic projection.

  The pure-constructor tests prove the seam's SHAPE. The door-wiring tests
  prove the doors actually reach it — that the link door and the commit hop
  resolve one URL to ONE target, and that the URL-change door reports which of
  its sub-doors fired. A test that only loops causes through the pure
  constructor cannot fail when a door passes the wrong cause or resolves its
  own target.

  ## Posture split

  The SEAM ITSELF is production-real and carries no posture guard: every pure
  constructor test and every door-wiring test runs in the ordinary
  `clojure -M:test` suite AND in `scripts/test-routing-prod-gate.sh` (the
  `-Dre-frame.debug=false` lane).

  What IS dev-only is the `:rf.route/planned` TRACE — the one bus the plan
  diagnostic projection rides — and the two `:rf.warning/*` fail-closed
  advisories. All three go through `trace/emit!` / `trace/emit-error!`, gated on
  `rf.interop/debug-enabled?` and read once at load time, so their assertions
  sit inside `(when rf.interop/debug-enabled? …)` dev-instrumentation arms.
  Under the gate the trace ring is empty, so a NEGATIVE read there (an empty
  `planned` result, a `not-any?`, a `re-find` over `(pr-str nil)`) would pass
  vacuously.

  PRODUCTION WITNESSES stand beside those arms. The projection is a PURE
  function (`rf.routing.resolve/plan-trace-tags`, pinned posture-independently
  below), so the redaction the trace relies on is checkable with no gate
  between the call and the verdict. The commits themselves are runtime-db
  facts: the slice moves, the `:on-match` leaf plan really dispatches, the
  nav-token really does NOT move on a no-op, and the `:routing/on-route-entry`
  late-bind hook — a FN, not a trace — really does receive the fail-loud
  `:branch-error` the activation composes over."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.events :as rf.routing.events]
            [re-frame.routing.link :as rf.routing.link]
            [re-frame.routing.registry :as rf.routing.registry]
            [re-frame.routing.resolve :as rf.routing.resolve]
            [re-frame.routing.url-change :as rf.routing.url-change]
            [re-frame.routing-test-support :as rf.routing-test-support]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

;; ---- the normalisations every door applies ---------------------------------
;;
;; `:query` and `:fragment` are the two fields the seam RESOLVES rather than
;; reflects, and their rules serve one law: a resolved target must describe a
;; place its own canonical URL can spell. `route-url` elides a nil-valued query
;; key and emits no trailing `#` for an empty fragment, so a target that keeps
;; either one commits a slice the address bar contradicts. The rules live at
;; the seam rather than in one door because `[:rf.route/prefetch …]` reaches
;; this seam directly, so a door-local rule would miss it.

(deftest resolved-target-drops-nil-valued-query-keys
  (rf.routing/reg-route :route/page
    {:query-defaults {:tab :overview}} "/p/:slug")
  (rf.routing/reg-route :route/plain {} "/plain/:slug")
  (testing "a nil value is the caller spelling ABSENCE; `:route/plain` declares
            no query vocabulary, so the survivor is spelled as the URL spells it"
    (is (= {"keep" "y"}
           (:query (rf.routing.resolve/resolved-target {:route-id :route/plain
                                                        :params   {:slug "x"}
                                                        :query    {:keep "y" :drop nil}})))))
  (testing "the strip runs BEFORE the defaults fill, so a declared default still
            lands on a key the caller nilled out"
    (is (= {:tab :overview}
           (:query (rf.routing.resolve/resolved-target {:route-id :route/page
                                                        :params   {:slug "x"}
                                                        :query    {:tab nil}})))))
  (testing "a query holding no nil is returned IDENTICALLY — the URL doors'
            query arrives in canonical key order and rebuilding it would throw
            that order away"
    (let [q (array-map "b" "2" "a" "1")]
      (is (identical? q (:query (rf.routing.resolve/resolved-target {:route-id :route/plain
                                                                     :params   {:slug "x"}
                                                                     :query    q})))))))

(deftest resolved-target-collapses-an-empty-fragment-to-nil
  (rf.routing/reg-route :route/page {} "/p/:slug")
  ;; "" is truthy, so an un-normalised empty fragment would make the slice say
  ;; :fragment "" while route-url emits /p/x with no trailing #.
  (is (nil? (:fragment (rf.routing.resolve/resolved-target {:route-id :route/page
                                                            :params   {:slug "x"}
                                                            :fragment ""})))))

(deftest a-bare-trailing-hash-url-resolves-to-no-fragment
  ;; `match-url` reports `:fragment ""` for a bare trailing `#` (its own
  ;; contract, pinned in the registry suite); the RESOLVED TARGET built from it
  ;; says nil, so `/page` and `/page#` are one target and moving between them
  ;; is the exact no-op rule 3 describes, not an in-page anchor change.
  (rf.routing/reg-route :route/page {} "/page")
  (is (= (dissoc (rf.routing.resolve/target-of-url "/page")  :url)
         (dissoc (rf.routing.resolve/target-of-url "/page#") :url))
      "both spellings resolve to one target, differing only in the requested
       :url each preserves verbatim"))

;; ---- the ONE place `:query-defaults` are filled ---------------------------
;;
;; Spec 012 defines a ResolvedTarget as planner output "after matching,
;; defaults, and validation", and this is the single function every door
;; shapes its target through. Were only `match-url` to fill defaults, the
;; named-address doors would resolve a different target than the URL doors
;; for one destination.

(deftest resolved-target-fills-the-routes-declared-query-defaults
  (rf.routing/reg-route :route/page
    {:params         [:map [:slug :string]]
     :query          [:map [:tab {:optional true} [:enum :overview :comments]]]
     :query-defaults {:tab :overview}}
    "/p/:slug")
  (testing "an absent declared-default key is filled, and an explicit value WINS
            — the fill is membership-only, never a value transform"
    (are [query expected]
         (= expected (:query (rf.routing.resolve/resolved-target {:route-id :route/page
                                                                  :params   {:slug "x"}
                                                                  :query    query})))
      {}              {:tab :overview}
      {:tab :comments} {:tab :comments})))

;; A schema may REQUIRE a key the route also defaults. `route-url` is the
;; emission boundary every named-address door shares, so it validates the
;; FILLED query — otherwise `route-link`, `link-model` and prefetch would reject
;; the very address `{:to …}` navigates to.

(deftest a-required-defaulted-query-key-resolves-the-same-through-every-door
  (rf.routing/reg-route :r/list {:query [:map [:page :int]] :query-defaults {:page 1}} "/list")
  (rf.routing/reg-route :r/elsewhere {} "/elsewhere")
  (let [pushed (atom [])
        warmed (atom [])]
    (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ url] (swap! pushed conj url)))
    (rf.late-bind/set-fn! :routing/on-route-prefetch
                          (fn [plan] (swap! warmed conj (:query plan)) {:warmed 1 :fx []}))
    (try
      (testing "every door accepts the address that omits the key, and agrees on
                \"/list\" and {:page 1}"
        (is (= "/list" (rf.routing/route-url {:to :r/list})))
        (is (= "/list" (:href (rf.routing.link/link-model {:to :r/list} :rf/default))))
        (is (= [:a {:href "/list"} "List"] (rf.routing/route-link-render-ssr {:to :r/list} "List")))
        (rf/dispatch-sync [:rf.route/prefetch {:to :r/list}])
        (is (= [{:page 1}] @warmed) "prefetch reached the warm plan with the filled query")
        (rf/dispatch-sync [:rf.route/navigate {:to :r/elsewhere}])
        (rf/dispatch-sync [:rf.route/navigate {:to :r/list}])
        (is (= [:r/list {:page 1} "/list"]
               [(get-in (rf/frame-state-value :rf/default) [:rf.db/runtime :rf.runtime/routing :current :route-id])
                (get-in (rf/frame-state-value :rf/default) [:rf.db/runtime :rf.runtime/routing :current :query])
                (peek @pushed)]))
        (is (= {:route-id :r/list :query {:page 1} :validation-failed? false}
               (select-keys (rf.routing/match-url "/list") [:route-id :query :validation-failed?]))))
      (testing "an override is spelled, a value at its default is not, and an
                invalid value still throws"
        (is (= "/list?page=3" (rf.routing/route-url {:to :r/list :query {:page 3}})))
        (is (= "/list" (rf.routing/route-url {:to :r/list :query {:page 1}})))
        (is (= [:rf.error/route-url-validation :query]
               (try (rf.routing/route-url {:to :r/list :query {:page "x"}})
                    nil
                    (catch Exception e ((juxt :rf.error/id :slot) (ex-data e)))))))
      (finally (rf.late-bind/set-fn! :routing/on-route-prefetch nil)))))

;; ---- the route plan every door builds -------------------------------------

(deftest route-plan-carries-source-cause-target-branch-leaf-plan
  (rf.routing/reg-route :route/section {} "/section")
  (rf.routing/reg-route :route/article
    {:parent :route/section :on-match [[:article/load]]} "/section/:slug")
  (let [target (rf.routing.resolve/resolved-target
                 {:route-id :route/article :params {:slug "x"} :query {}
                  :fragment nil :url "/section/x"})]
    ;; The plan carries the caller's source and cause, the ResolvedTarget the
    ;; door commits, the parent-to-leaf branch, the walk's contributors (the
    ;; value commit-navigation hands the resource plan) and the leaf plan.
    (is (= {:source              {:to :route/article :params {:slug "x"}}
            :cause               :navigate
            :target              target
            :branch              [:route/section :route/article]
            :branch-contributors (mapv (fn [id] {:route-id id :route-meta (rf.registrar/lookup :route id)})
                                       [:route/section :route/article])
            :leaf-plan           [[:article/load]]}
           (rf.routing.resolve/route-plan {:source {:to :route/article :params {:slug "x"}}
                                           :cause  :navigate
                                           :target target})))))

;; ---- the projection as trace tags -----------------------------------------
;;
;; One trace per door commit branch makes the projection reachable from an
;; executed navigation. It carries the URL through the shared `redact-url-tag`
;; path and the params / query KEY SETS rather than their values, because a
;; trace tag is an egress surface the route's `:sensitive` classification
;; (lowered against runtime-db slice PATHS) cannot reach.

(deftest plan-trace-tags-carries-the-projection-without-the-carriers
  (rf.routing/reg-route :route/section {} "/section")
  (rf.routing/reg-route :route/article
    {:parent :route/section :on-match [[:article/load] [:comments/load 7]]}
    "/section/:slug")
  (testing "identifiers ride whole; the URL keeps its path with query VALUES and
            the #fragment redacted; params / query contribute KEY SETS; the
            caller's :source is not carried"
    (is (= {:cause         :navigate
            :route-id      :route/article
            :url           "/section/x?invite=rf/redacted&tab=rf/redacted#rf/redacted"
            :param-keys    [:slug]
            ;; A bare route's query keys are URL strings.
            :query-keys    ["invite" "tab"]
            :branch        [:route/section :route/article]
            :leaf-plan-ids [:article/load :comments/load]}
           (rf.routing.resolve/plan-trace-tags
             (rf.routing.resolve/route-plan
               {:cause  :navigate
                :source {:to :route/article :params {:slug "x"} :query {:invite "SECRET100"}}
                :target (rf.routing.resolve/resolved-target
                          {:route-id :route/article
                           :params   {:slug "x"}
                           :query    {:invite "SECRET100" :tab :comments}
                           :fragment "reply-42"
                           :url      "/section/x?invite=SECRET100&tab=comments#reply-42"})})))))
  (testing "a param-less / query-less target yields empty key sets, never nil,
            and a bare path rides verbatim"
    (is (= {:cause :initial :route-id :route/section :url "/section"
            :param-keys [] :query-keys [] :branch [:route/section] :leaf-plan-ids []}
           (rf.routing.resolve/plan-trace-tags
             (rf.routing.resolve/route-plan
               {:cause :initial :source {:url "/section"}
                :target (rf.routing.resolve/resolved-target
                          {:route-id :route/section :params {} :query {}
                           :url "/section"})}))))))

;; ---- the runtime-db facts a door commit leaves behind ---------------------

(defn- rdb [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- nav-slice [] (get-in (rdb) [:rf.runtime/routing :current]))
(defn- current-id [] (:route-id (nav-slice)))

(defn- with-route-entry-spy!
  "Install a spy on the `:routing/on-route-entry` late-bind hook — the FN
  `commit-navigation` hands the resolved `:branch` (contributors) and
  `:branch-error` to — run `f`, restore the prior binding, and return the
  recorded contexts. A late-bound fn is not a trace, so the activation's copy
  of the fail-loud walk arrives under `-Dre-frame.debug=false` exactly as it
  does in dev."
  [f]
  (let [seen  (atom [])
        prior (rf.late-bind/get-fn :routing/on-route-entry)]
    (rf.late-bind/set-fn! :routing/on-route-entry (fn [ctx] (swap! seen conj ctx) {}))
    (try (f)
         (finally (rf.late-bind/set-fn! :routing/on-route-entry prior)))
    @seen))

(defn- quiet-nav-fx!
  "No-op the host navigation fx so a JVM navigation commits without reaching a
  browser-only handler."
  []
  (doseq [fx-id [:rf.nav/push-url :rf.nav/replace-url
                 :rf.nav/capture-scroll :rf.nav/scroll
                 :rf.server/set-status]]
    (rf.fx/reg-fx fx-id {:platforms #{:server :client}} (fn [_ _] nil))))

(defmacro ^:private planned
  "The `:rf.route/planned` traces emitted while `body` runs."
  [& body]
  `(with-trace-recorder! [traces# {:pred #(= :rf.route/planned (:operation %))}]
     ~@body
     @traces#))

(defn- cause+frame [ts] (mapv (comp (juxt :cause :frame) :tags) ts))

(deftest every-door-commit-branch-emits-one-plan-trace
  (rf.routing/reg-route :route/home {} "/")
  (rf.routing/reg-route :route/article {:on-match [[:article/load]]} "/articles/:slug")
  (quiet-nav-fx!)
  ;; Registering the leaf loader turns "which ids ride the tag" into "which
  ;; loaders actually dispatched" — the same claim with no bus in between.
  (let [loaded (atom [])]
    (rf/reg-event :article/load
                  (fn [{:keys [db]} _]
                    (swap! loaded conj (:slug (:params (nav-slice))))
                    {:db db}))

    (testing "the PROGRAMMATIC door commits, runs the leaf plan it names, and
              emits exactly one plan trace"
      (let [ts (planned (rf/dispatch-sync [:rf.route/navigate {:to :route/article
                                                               :params {:slug "a"}}]))]
        (is (= [:route/article {:slug "a"}] ((juxt :route-id :params) (nav-slice))))
        (is (= ["a"] @loaded) "the :leaf-plan the projection names really dispatched")
        ;; Dev-instrumentation arm (see ns docstring). `:frame` is load-bearing:
        ;; epoch capture admits only frame-tagged traces.
        (when rf.interop/debug-enabled?
          (is (= [{:cause :navigate :route-id :route/article :branch [:route/article]
                   :leaf-plan-ids [:article/load] :frame :rf/default}]
                 (mapv #(select-keys (:tags %) [:cause :route-id :branch :leaf-plan-ids :frame]) ts))))))
    (testing "the URL-driven door reports which of its sub-doors fired"
      ;; A VECTOR of triples: the slug pairs positionally with the cause.
      (doseq [[cause dispatch slug] [[:link     [:rf.route/handle-url-change "/articles/b" {:rf.route/cause :link}] "b"]
                                     [:popstate [:rf.route/handle-url-change "/articles/c"
                                                 {:rf.route/cause :popstate}] "c"]
                                     [:initial  [:rf.route/handle-url-change "/articles/d"] "d"]]]
        (let [ts (planned (rf/dispatch-sync dispatch))]
          ;; Which CAUSE each reports has its own always-on witness in
          ;; `executed-url-change-navigation-carries-its-true-cause`.
          (is (= [{:slug slug} slug] [(:params (nav-slice)) (last @loaded)])
              (str cause " committed /articles/" slug " and re-ran the leaf plan"))
          ;; Dev-instrumentation arm (see ns docstring).
          (when rf.interop/debug-enabled?
            (is (= [[cause :rf/default]] (cause+frame ts))
                "a door that hardcoded one cause for its sub-doors fails here")))))
    (testing "the SSR feed reports :ssr off the frame's :platform"
      (let [f  (rf.frame/make-anon-frame-record! {:platform :server})
            ts (planned (rf/dispatch-sync [:rf.route/handle-url-change "/articles/e"]
                                          {:frame f}))]
        ;; SEMANTIC, posture-independent: the server frame's own slice moved
        ;; and the ambient `:rf/default` one did not.
        (is (= {:slug "e"}
               (get-in (:rf.db/runtime (rf/frame-state-value f))
                       [:rf.runtime/routing :current :params]))
            "the SSR door committed into the SERVER frame")
        (is (= {:slug "d"} (:params (nav-slice)))
            "…and left the ambient :rf/default frame's slice alone")
        ;; Dev-instrumentation arm (see ns docstring).
        (when rf.interop/debug-enabled?
          (is (= [[:ssr f]] (cause+frame ts))))))
    (testing "the NON-commit branches emit none — an exact no-op and a
              fragment-only anchor change are not plan commits"
      (rf/dispatch-sync [:rf.route/handle-url-change "/articles/f"])
      (let [token (:nav-token (nav-slice))
            loads (count @loaded)]
        ;; SEMANTIC, posture-independent: what a plan commit WOULD leave behind
        ;; is a fresh nav-token and a re-fired leaf plan, so that is what the
        ;; always-on legs deny.
        (let [ts (planned (rf/dispatch-sync [:rf.route/handle-url-change "/articles/f"]))]
          (is (= [token loads] [(:nav-token (nav-slice)) (count @loaded)])
              "an exact no-op allocates no fresh nav-token and re-fires no loader")
          (when rf.interop/debug-enabled?
            (is (empty? ts) "an exact no-op plans nothing")))
        (let [ts (planned (rf/dispatch-sync [:rf.route/handle-url-change "/articles/f#anchor"]))]
          (is (= ["anchor" token loads] [(:fragment (nav-slice)) (:nav-token (nav-slice)) (count @loaded)])
              "the fragment-only change DID land, with no fresh nav-token and no
               leaf-plan re-fire")
          (when rf.interop/debug-enabled?
            (is (empty? ts)
                "a fragment-only transition plans nothing (no nav-token, no re-plan)")))))))

(deftest an-executed-navigations-plan-trace-is-not-a-carrier
  (rf.routing/reg-route :route/home {} "/")
  (rf.routing/reg-route :route/invite {} "/invite/:id")
  (quiet-nav-fx!)
  (testing "a real navigation carrying a secret in its query and fragment emits a
            plan trace that reproduces NEITHER — the projection is reachable
            without being a carrier"
    (let [ts   (planned (rf/dispatch-sync [:rf.route/navigate {:to       :route/invite
                                                               :params   {:id "acct-42"}
                                                               :query    {:invite "SECRET100"}
                                                               :fragment "tok-99"}]))
          tags (:tags (first ts))]
      ;; SEMANTIC, posture-independent: the navigation committed, and IN
      ;; PROCESS the carriers ride raw — redaction is an egress rule, not a
      ;; storage rule. The projection itself is pinned posture-independently in
      ;; `plan-trace-tags-carries-the-projection-without-the-carriers`.
      (is (= {:route-id :route/invite :query {"invite" "SECRET100"} :fragment "tok-99"}
             (select-keys (nav-slice) [:route-id :query :fragment])))
      ;; Dev-instrumentation arm (see ns docstring): the guarantees read off the
      ;; bus that actually carried them.
      (when rf.interop/debug-enabled?
        (is (= 1 (count ts)))
        ;; `:route/invite` is bare, so its query keys are strings.
        (is (= {:param-keys [:id] :query-keys ["invite"]
                :url        "/invite/acct-42?invite=rf/redacted#rf/redacted"}
               (select-keys tags [:param-keys :query-keys :url])))
        (is (not (re-find #"SECRET100|tok-99" (pr-str tags))))))))

(deftest planned-traces-branch-agrees-with-the-activation-rf2-cqyq2
  (rf.routing/reg-route :route/home {} "/")
  (rf.routing/reg-route :route/leaf {:parent :route/nowhere} "/leaf")
  (quiet-nav-fx!)
  (testing "a navigation to a route whose :parent is unregistered plans an
            EMPTY branch plus the fail-loud error the activation aborts on —
            never a plausible two-segment branch naming a route that does not
            exist"
    (let [entries (atom [])
          ts      (planned
                    (reset! entries
                            (with-route-entry-spy!
                              #(rf/dispatch-sync [:rf.route/navigate {:to :route/leaf}]))))
          tags    (:tags (first ts))]
      ;; SEMANTIC, posture-independent: the activation's copy arrives through
      ;; the `:routing/on-route-entry` late-bind hook — a fn, not a trace.
      (is (= 1 (count @entries)) "the activation ran its route-entry plan once")
      (is (= [] (:branch (first @entries)))
          "the activation composes over NO branch")
      (is (= {:kind :unknown-parent :route-id* :route/nowhere}
             (select-keys (:branch-error (first @entries)) [:kind :route-id*]))
          "…and carries the fail-loud error itself")
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= 1 (count ts)))
        (is (= [] (:branch tags)))
        (is (= (select-keys (:branch-error (rf.routing.events/resolve-branch :route/leaf))
                            [:kind :route-id*])
               (:branch-error tags))
            "the trace's failure signal IS the activation's"))))
  (testing "a well-formed branch carries no :branch-error at all — the tag is
            a failure signal, not a slot that is always present"
    (rf.routing/reg-route :route/shell {} "/shell")
    (rf.routing/reg-route :route/child {:parent :route/shell} "/shell/child")
    (let [entries (atom [])
          tags    (:tags (first (planned
                                  (reset! entries
                                          (with-route-entry-spy!
                                            #(rf/dispatch-sync
                                               [:rf.route/navigate {:to :route/child}]))))))]
      (is (= [:route/shell :route/child] (mapv :route-id (:branch (first @entries))))
          "the activation composes over the parent-to-leaf branch")
      (is (nil? (:branch-error (first @entries)))
          "…and the well-formed walk really produced no error to report")
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= [:route/shell :route/child] (:branch tags)))
        (is (not (contains? tags :branch-error))))))
  (testing "a parent CYCLE is reported, and the :branch-error tag carries only
            registration-time identifiers — never the meta-bearing :chain"
    (rf.routing/reg-route :route/ping {:parent :route/pong} "/ping")
    (rf.routing/reg-route :route/pong {:parent :route/ping} "/pong")
    (rf/dispatch-sync [:rf.route/handle-url-change "/"])
    (let [entries (atom [])
          tags    (:tags (first (planned
                                  (reset! entries
                                          (with-route-entry-spy!
                                            #(rf/dispatch-sync
                                               [:rf.route/navigate {:to :route/ping}]))))))]
      (is (= {:kind :parent-cycle :route-id* :route/ping}
             (select-keys (:branch-error (first @entries)) [:kind :route-id*]))
          "the activation is handed the cycle, not a silently truncated chain")
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= {:kind :parent-cycle :route-id* :route/ping} (:branch-error tags)))))))

;; ---- the URL -> ResolvedTarget extraction (ONE definition) ----------------

(deftest url-resolution-normalises-every-fallback-to-the-canonical-target
  (rf.routing/reg-route :route/article {} "/articles/:slug")
  (rf.routing/reg-route :route/typed {:params [:map [:id :int]]} "/typed/:id")
  (testing "a match resolves to the matched route's facts"
    (is (= {:route-id :route/article :params {:slug "x"} :query {}
            :fragment nil :url "/articles/x"}
           (:target (rf.routing.resolve/url-resolution "/articles/x")))))
  (testing "a bare miss normalises to the reserved :rf.route/not-found target:
            the reserved route-id, the {:url …} params vocabulary, and an
            EMPTIED query"
    (is (= {:target    {:route-id :rf.route/not-found
                        :params   {:url "/no-such-thing"}
                        :query    {}
                        :fragment nil
                        :url      "/no-such-thing"}
            :fallback? true
            :matched?  false}
           (select-keys (rf.routing.resolve/url-resolution "/no-such-thing")
                        [:target :fallback? :matched?]))))
  (testing "a validation fail is a MATCH (the SCHEMA rejected it) that still
            normalises to not-found, with :reason"
    (let [r (rf.routing.resolve/url-resolution "/typed/not-an-int")]
      (is (= [:rf.route/not-found {:url "/typed/not-an-int" :reason :validation} true true]
             [(:route-id (:target r)) (:params (:target r)) (:validation-fail? r) (:matched? r)]))))
  (testing "a malformed URL carries :reason :malformed-url and NO fragment — the
            fragment may itself be the decode-fail site"
    (let [r (rf.routing.resolve/url-resolution "/articles/%zz#frag")]
      (is (= [:rf.route/not-found :malformed-url nil true]
             [(:route-id (:target r)) (:reason (:params (:target r))) (:fragment (:target r))
              (:malformed? r)])))))

;; ===========================================================================
;; Door wiring — the doors REACH the seam
;; ===========================================================================

(defn- register-denying-not-found!
  "A `:home` route plus a registered `:rf.route/not-found` whose `:can-enter`
  DENIES, and no-op nav fx. Returns `[guard-calls denials]` — the
  guard-invocation counter and the captured `:rf.route/entry-denied` payloads."
  []
  (let [calls (atom 0)
        seen  (atom [])]
    (rf/reg-route :home {} "/home")
    (rf/reg-route :rf.route/not-found {:can-enter [:deny/not-found]} "/not-found")
    (rf/reg-sub :deny/not-found (fn [_ _] (swap! calls inc) false))
    (rf/reg-event :rf.route/entry-denied (fn [_ [_ d]] (swap! seen conj d) {}))
    (quiet-nav-fx!)
    [calls seen]))

(deftest link-door-decides-the-same-target-the-commit-hop-would-commit
  (testing "a dead LINK resolves through the shared seam, so the reserved
            :rf.route/not-found route's :can-enter is consulted exactly once —
            a link door deciding against a target with a nil :route-id would
            find no guard and let the second hop commit the denied route — and
            the denial is TERMINAL; the equivalent PROGRAMMATIC door agrees"
    (doseq [dispatch [[:rf.route/url-requested {:url "/missing-link"}]
                      [:rf.route/navigate {:url "/missing-programmatic"}]]]
      (let [[calls seen] (register-denying-not-found!)]
        (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
        (reset! calls 0) (reset! seen [])
        (rf/dispatch-sync dispatch)
        (is (= [1 1 :home] [@calls (count @seen) (current-id)]) (pr-str dispatch))))))

(deftest same-unmatched-link-is-an-exact-no-op
  (testing "clicking the link for the ALREADY-ACTIVE not-found URL is an exact
            no-op (Spec 012 §Per-route data loading rule 3): the link door sees
            the no-op only because it resolves the same canonical target the
            slice carries, so no history entry is pushed"
    (rf/reg-route :home {} "/home")
    (let [pushed (atom [])]
      (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/handle-url-change "/same-miss"])
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/url-requested {:url "/same-miss"}])
      (is (empty? @pushed)
          "no :rf.nav/push-url — the exact no-op terminated before history moved")
      (is (= :rf.route/not-found (current-id))))))

;; ---- both URL-bearing doors share ONE reason vocabulary --------------------
;;
;; Both doors resolve through the shared extraction, so the not-found params
;; and the fail-closed warnings are identical. A programmatic `{:url …}` door
;; resolving its own URL would never run the `malformed-url?` scan, so the one
;; door Spec 012 documents as taking user-supplied URLs would lose the EP-0015
;; malformed-URL diagnostic exactly where malformed input arrives.

(defn- door-fallback
  "Drive ONE not-found navigation and report what the two surfaces a consumer
  reads actually say: the slice's `:route-id` + `:params`, and the fail-closed
  warning operations the drain emitted."
  [dispatch]
  (with-trace-recorder! [traces {:pred #(contains? #{:rf.warning/malformed-url
                                                     :rf.warning/no-not-found-route}
                                                   (:operation %))}]
    (rf/dispatch-sync dispatch)
    {:route-id (current-id)
     :params   (get-in (rdb) [:rf.runtime/routing :current :params])
     :warnings (mapv :operation @traces)}))

(deftest both-url-bearing-doors-stamp-the-same-not-found-reason
  (rf.routing/reg-route :route/home {} "/home")
  (rf.routing/reg-route :route/typed {:params [:map [:id :int]]} "/typed/:id")
  (rf.routing/reg-route :rf.route/not-found {} "/not-found")
  (quiet-nav-fx!)
  (testing "a BARE miss — no :reason on either door"
    (let [url-driven   (door-fallback [:rf.route/handle-url-change "/miss-a"])
          programmatic (door-fallback [:rf.route/navigate {:url "/miss-b"}])]
      (is (= :rf.route/not-found (:route-id url-driven) (:route-id programmatic)))
      (is (= {:url "/miss-a"} (:params url-driven)))
      (is (= {:url "/miss-b"} (:params programmatic)))
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= [] (:warnings url-driven) (:warnings programmatic))
            "a well-formed miss is not a malformed URL"))))
  (testing "a MALFORMED percent-encoding — both doors stamp :malformed-url, so
            a per-route error UI branching on :reason (Spec 012) and the
            EP-0015 malformed-URL diagnostic both reach the one door that
            takes user-supplied URLs."
    (let [url-driven   (door-fallback [:rf.route/handle-url-change "/miss-a/%zz"])
          programmatic (door-fallback [:rf.route/navigate {:url "/miss-b/%zz"}])]
      (is (= :rf.route/not-found (:route-id url-driven) (:route-id programmatic)))
      (is (= :malformed-url (:reason (:params url-driven)) (:reason (:params programmatic))))
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= [:rf.warning/malformed-url] (:warnings url-driven) (:warnings programmatic))
            "EP-0015's malformed-URL diagnostic fires on BOTH doors"))))
  (testing "a match-url THROW — both doors stamp :match-error through the
            shared extraction"
    (with-redefs [rf.routing.registry/match-url
                  (fn [_] (throw (ex-info "simulated hostile-URL parse failure" {})))]
      (let [url-driven   (door-fallback [:rf.route/handle-url-change "/throw-a"])
            programmatic (door-fallback [:rf.route/navigate {:url "/throw-b"}])]
        (is (= :rf.route/not-found (:route-id url-driven) (:route-id programmatic)))
        (is (= {:url "/throw-a" :reason :match-error} (:params url-driven)))
        (is (= {:url "/throw-b" :reason :match-error} (:params programmatic)))
        ;; Dev-instrumentation arm (see ns docstring).
        (when rf.interop/debug-enabled?
          (is (= [:rf.warning/malformed-url] (:warnings url-driven) (:warnings programmatic)))))))
  (testing "a VALIDATION miss is the SPECIFIED asymmetry, not a defect: Spec 012's
            resolve-target table and §Validation-error surfacing specify
            URL-driven-routes-to-not-found vs programmatic-caller-bug-rejects."
    (let [url-driven (door-fallback [:rf.route/handle-url-change "/typed/not-an-int"])]
      (is (= :rf.route/not-found (:route-id url-driven)))
      (is (= {:url "/typed/not-an-int" :reason :validation} (:params url-driven))))
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (rf/dispatch-sync [:rf.route/navigate {:url "/typed/also-not-an-int"}])
    (is (= :route/home (current-id))
        "the programmatic door REJECTS a validation miss — slice unchanged")))

;; ---- the URL-change door reports WHICH of its sub-doors fired --------------

(deftest url-change-cause-resolves-the-true-sub-door
  (testing "a rider outside the closed cause set cannot invent another cause"
    (are [rider] (= :initial (rf.routing.url-change/url-change-cause :rf/default {:rf.route/cause rider}))
      :not-a-cause
      "popstate")))

(deftest executed-url-change-navigation-carries-its-true-cause
  (testing "the cause the DOOR passes is observable on the denial payload, so a
            door that hardcodes one cause for its sub-doors fails here"
    (let [[_ seen] (register-denying-not-found!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
      (doseq [[url opts expected] [["/miss-a"    {:rf.route/cause :popstate} :popstate]
                                   ["/miss-b"    {:rf.route/cause :initial}  :initial]
                                   ;; a bare client-frame dispatch is an initial feed
                                   ["/miss-c"    nil                         :initial]
                                   ["/miss-link" {:rf.route/cause :link}     :link]]]
        (reset! seen [])
        (rf/dispatch-sync (cond-> [:rf.route/handle-url-change url] opts (conj opts)))
        (is (= [expected] (mapv :cause @seen)) url))))
  (testing "the SSR feed — dispatched by the app's own :initial-events, so it
            carries no rider — reports :ssr off the frame's :platform"
    (let [[_ seen] (register-denying-not-found!)
          f        (rf.frame/make-anon-frame-record! {:platform :server})]
      (rf/dispatch-sync [:rf.route/handle-url-change "/miss-ssr"] {:frame f})
      (is (= [:ssr] (mapv :cause @seen))))))
