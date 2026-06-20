(ns re-frame.resources-from-db-scope-cljs-test
  "Named-resolver scope integration — `{:from-db <id>}` references at the
  route-entry / event-ensure / subscription-read sites (rf2-qiv160, EP-0016
  D3 slice 3, Spec 016 §Resolver references — `{:from-db <id>}` / §Route
  integration / §Subscription-side scope resolution).

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex; Shadow's `:node-test` build via the `cljs-test$`
  regex. Cross-host so the db-derived scope resolution behaves identically
  server- and client-side.

  What's under test (the slice's validation-plan items 9 + 10 + rf2-616xa6):

    1. a `{:from-db <id>}` resource-spec `:scope` resolves against app-db on
       EVENT ensure → the SAME scoped key a route entry or sub resolves;
    2. a `{:from-db <id>}` route-resource `:scope` resolves at route entry,
       BEFORE planning, and ensures + (on leave) releases the owner under
       the RESOLVED scope;
    3. a `{:from-db <id>}` subscription resolves against the frame app-db,
       reading the SAME entry the route ensured;
    4. FAIL-CLOSED nil — a resolver whose declared inputs are absent yields
       nil; an event raises `:rf.error/resource-scope-unresolved-reference`,
       a route surfaces a planning error, a sub raises
       `:rf.error/resource-sub-unresolved-scope` — never a silent global;
    5. rf2-616xa6 — a `{:from-db}` sub RE-KEYS reactively when the
       resolver's app-db inputs change mid-session (account switch): the
       sub re-points to the new scoped entry, and the view observes the new
       key's loading/idle state, not the stale entry."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting requires: register the resources + routing
   ;; events / subs and resources' late-bound :routing/* integration hooks.
   [re-frame.resources]
   [re-frame.resources.registry :as registry]
   [re-frame.resources.route :as route]
   [re-frame.resources.state :as state]
   [re-frame.resources.subs :as r-subs]
   [re-frame.resources.scope-registry :as scope-registry]
   [re-frame.resources.test-support]
   [re-frame.routing :as routing]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.registrar :as registrar]
   [re-frame.trace.tooling :as trace-tooling]
   [re-frame.test-support :as core-test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; ---- fixture --------------------------------------------------------------

(defn- init!
  "Per-test setup: a URL-owning default app frame, reset routing counters,
  re-publish the late-bound routing integration, register the named scope
  resolver + the session-scoped feed resource, and stub managed-HTTP +
  push-url so ensure / navigation are deterministic without a fetch /
  browser."
  []
  (rf/reg-frame :rf/default {:url-bound? true
                             :doc "from-db scope suite default app frame."})
  (routing/reset-counters!)
  (route/install-routing-integration!)
  (registrar/clear-kind! :resource-scope)
  (rf/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  ;; the named db-derived viewer-session resolver (EP-0016 D3 canonical form)
  (rf/reg-resource-scope :t/session
    {:inputs  {:username [:db [:auth :user :username]]}
     :resolve (fn [{:keys [username]} _ctx]
                (when username [:rf.scope/session {:username username}]))})
  ;; a session-scoped feed resource whose spec :scope is the {:from-db …} ref
  (rf/reg-resource :t/feed
    {:scope         {:from-db :t/session}
     :params-schema [:map [:page :int]]
     :tags          (fn [_p _v] #{[:feed]})}
    (fn [{:keys [page]} _ctx]
      {:request {:method :get :url "/feed" :params {:page page}}}))
  ;; an app event that writes the logged-in user (the resolver's db input)
  (rf/reg-event :t/login (fn [{:keys [db]} [_ username]] {:db (assoc-in db [:auth :user :username] username)}))
  (rf/reg-event :t/logout (fn [{:keys [db]} _] {:db (update db :auth dissoc :user)})))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (rf/runtime-db-value :rf/default))
;; rf2-9e0tyq — `:entries` is keyed on the byte `key-id`; return a vector-keyed
;; VIEW (re-keyed from each entry's `:resource/key`) so the assertions speak
;; scoped-key vectors. Semantics unchanged.
(defn- entries []
  (into {} (map (fn [[_k-id e]] [(:resource/key e) e]))
        (get-in (runtime-db) (state/entries-path))))
(defn- entry [scoped-key] (get-in (runtime-db) (state/entry-path scoped-key)))
(defn- slice [] (get-in (runtime-db) [:rf.runtime/routing :current]))

(defn- session-key
  "The scoped feed key for username `u`, page `page`."
  [u page]
  (state/scoped-resource-key [:rf.scope/session {:username u}] :t/feed {:page page}))

(defn- settle-loaded!
  "Drive the just-ensured entry at `scoped-key` to :loaded by dispatching
  the internal success reply for its current work."
  [scoped-key data]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key scoped-key
                        :work/id      (:current-work e)
                        :generation   (:generation e)
                        :data         data}])))

;; ===========================================================================
;; 1. {:from-db} on an EVENT ensure resolves against app-db
;; ===========================================================================

(deftest event-ensure-from-db-spec-policy
  (rf/dispatch-sync [:t/login "jake"])
  (testing "ensure of a {:from-db} spec-policy resource resolves the session
            scoped key from app-db (no payload :scope needed)"
    (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page 1}
                                            :owner [:lease :x 1]}])
    (is (some? (entry (session-key "jake" 1)))
        "the entry lives under the db-derived session scope")
    (is (= #{(session-key "jake" 1)} (set (keys (entries))))
        "exactly one entry, under the resolved scope — never a global key")))

(deftest event-ensure-from-db-payload-reference
  (rf/dispatch-sync [:t/login "abel"])
  ;; a from-caller resource read with an explicit {:from-db …} PAYLOAD scope
  (rf/reg-resource :t/notes
    {:scope         :rf.scope/from-caller
     :params-schema [:map]}
    (fn [_p _ctx] {:request {:method :get :url "/notes"}}))
  (testing "a {:from-db …} payload :scope resolves against app-db at use time"
    (rf/dispatch-sync [:rf.resource/ensure {:resource :t/notes :params {}
                                            :scope {:from-db :t/session}
                                            :owner [:lease :n 1]}])
    (is (some? (entry (state/scoped-resource-key
                        [:rf.scope/session {:username "abel"}] :t/notes {}))))))

(deftest event-ensure-from-db-nil-fails-closed
  (testing "a {:from-db} reference resolving nil (no logged-in user) FAILS
            CLOSED on an event — `resolve-scope-for-event` throws
            `:rf.error/resource-scope-unresolved-reference`, never a silent
            global read"
    ;; no logged-in user — the resolver's :inputs are absent against `{}`
    (let [spec (rf/resource-meta :t/feed)]
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"resource-scope-unresolved-reference"
            (registry/resolve-scope-for-event
              :t/feed spec {:db {}} 'test)))))
  (testing "dispatched through the event the failure is caught by the runtime
            error path — NO entry is created under any scope (fail-closed)"
    (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page 1}}])
    (is (empty? (entries)) "no entry created under any scope (fail-closed)")))

;; ===========================================================================
;; 2. {:from-db} on a ROUTE-RESOURCE :scope (resolved at route entry)
;; ===========================================================================

(deftest route-entry-from-db-scope-resolves-and-ensures
  (rf/dispatch-sync [:t/login "jake"])
  (rf/reg-route :t/home
    {:resources [{:resource :t/feed :params (fn [_] {:page 1})
                  :scope {:from-db :t/session} :blocking? true}]} "/")
  (testing "route entry resolves the {:from-db} route-resource scope against
            app-db BEFORE planning, ensuring under the session scope"
    (rf/dispatch-sync [:rf.route/navigate :t/home])
    (let [k (session-key "jake" 1)]
      (is (some? (entry k)) "the route ensured the feed under the resolved session scope")
      (let [owner [:route :t/home (:nav-token (slice))]]
        (is (contains? (:active-owners (entry k)) owner)
            "the route owner is attached under the RESOLVED scope")))))

(deftest route-leave-releases-owner-under-resolved-scope
  (rf/dispatch-sync [:t/login "jake"])
  (rf/reg-route :t/home
    {:resources [{:resource :t/feed :params (fn [_] {:page 1})
                  :scope {:from-db :t/session}}]} "/")
  (rf/reg-route :t/other {} "/other")
  (rf/dispatch-sync [:rf.route/navigate :t/home])
  (let [k     (session-key "jake" 1)
        owner [:route :t/home (:nav-token (slice))]]
    (is (contains? (:active-owners (entry k)) owner) "owner attached on entry")
    (testing "route leave releases the owner acquired under the RESOLVED scope"
      (rf/dispatch-sync [:rf.route/navigate :t/other])
      (is (not (contains? (:active-owners (entry k)) owner))
          "the prior route owner (resolved-scope key) is released on leave"))))

(deftest route-entry-from-db-nil-is-a-planning-error
  ;; no login — the route-resource {:from-db} scope resolves nil
  (rf/reg-route :t/home
    {:resources [{:resource :t/feed :params (fn [_] {:page 1})
                  :scope {:from-db :t/session}}]} "/")
  (testing "a {:from-db} route scope resolving nil surfaces as a route
            PLANNING error on the slice, never a silent global ensure"
    (rf/dispatch-sync [:rf.route/navigate :t/home])
    (is (= :rf.error/resource-route-plan (:rf.error/id (:error (slice))))
        "the route slice carries the planning error")
    (is (empty? (entries)) "no entry ensured under any scope (fail-closed)")))

;; ===========================================================================
;; 3. {:from-db} on a SUBSCRIPTION read (against frame app-db)
;; ===========================================================================

(deftest sub-from-db-reads-the-same-entry-the-event-ensured
  (rf/dispatch-sync [:t/login "jake"])
  (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page 1}
                                          :owner [:lease :s 1]}])
  (settle-loaded! (session-key "jake" 1) {:articles [:a :b]})
  (testing "a {:from-db} spec-policy sub resolves the same session key and
            reads the loaded entry (no payload :scope needed)"
    (let [q {:resource :t/feed :params {:page 1}}]
      (is (= :loaded (:status @(rf/subscribe [:rf.resource/state q]))))
      (is (= {:articles [:a :b]} @(rf/subscribe [:rf.resource/data q]))))))

(deftest sub-from-db-nil-raises-unresolved-scope
  (testing "a {:from-db} sub whose resolver yields nil raises the sub-side
            fail-closed diagnostic — never a silent :idle / global read.
            (Asserted at the resolution boundary `resolve-scoped-key`, the
            same level the from-caller sub-unresolved test asserts at; a sub
            body throw is otherwise routed to the runtime error path.)"
    ;; no logged-in user — the {:from-db} spec policy resolves nil against `{}`
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-sub-unresolved-scope"
          (r-subs/resolve-scoped-key {:resource :t/feed :params {:page 1}} {})))))

;; ===========================================================================
;; 4. rf2-616xa6 — mid-session input change RE-KEYS the live sub
;; ===========================================================================

(deftest sub-re-keys-on-mid-session-account-switch
  (rf/dispatch-sync [:t/login "jake"])
  ;; ensure + load jake's feed
  (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page 1}
                                          :owner [:lease :s 1]}])
  (settle-loaded! (session-key "jake" 1) {:for "jake"})
  (let [q   {:resource :t/feed :params {:page 1}}
        sub (rf/subscribe [:rf.resource/state q])]
    (testing "the live sub reads jake's loaded entry"
      (is (= :loaded (:status @sub)))
      (is (= {:for "jake"} (:data @sub))))
    (testing "switching the logged-in user (the resolver's app-db input)
              re-keys the SAME sub to the new scope — it no longer reads
              jake's entry; the new key has no entry yet (idle empty-state),
              so the view sees the new principal's loading/idle, NOT the
              stale data (rf2-616xa6)"
      (rf/dispatch-sync [:t/login "abel"])
      (is (= :idle (:status @sub)) "re-pointed to abel's (un-ensured) key")
      (is (nil? (:data @sub)) "abel's key has no data — never jake's stale data")
      (is (false? (:has-data? @sub))))
    (testing "ensuring + loading abel's feed makes the SAME sub read the new
              entry — the re-pointing is fully reactive"
      (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page 1}
                                              :owner [:lease :s 2]}])
      (settle-loaded! (session-key "abel" 1) {:for "abel"})
      (is (= :loaded (:status @sub)))
      (is (= {:for "abel"} (:data @sub)) "the sub now reads abel's data"))))

;; ===========================================================================
;; 5. rf2-mfnc5i — {:from-db} on a CLEAR-SCOPE payload resolves at use time;
;;    a nil-resolving reference is a loud warning + fail-closed (never a
;;    silent no-op). Slice-3 wired event/route/sub/remove but missed
;;    clear-scope. Spec 016 §clear-scope is causal.
;; ===========================================================================

(defn- record-clear-scope-warnings!
  "Run `body-fn` with a trace listener installed; return the vector of every
  `:rf.warning/resource-clear-scope-unresolved` event emitted during it (in
  capture order). The listener is unregistered in a `finally`."
  [body-fn]
  (let [seen (atom [])
        k    ::clear-scope-recorder]
    (trace-tooling/register-listener!
      k (fn [ev]
          (when (= :rf.warning/resource-clear-scope-unresolved (:operation ev))
            (swap! seen conj ev))))
    (try (body-fn)
         (finally (trace-tooling/unregister-listener! k)))
    @seen))

(deftest clear-scope-from-db-resolves-and-clears-the-resolved-scope
  (rf/dispatch-sync [:t/login "jake"])
  ;; ensure + load jake's feed under the db-derived session scope
  (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page 1}
                                          :owner [:lease :c 1]}])
  (settle-loaded! (session-key "jake" 1) {:for "jake"})
  (is (some? (entry (session-key "jake" 1))) "jake's entry loaded")
  (testing "a {:from-db …} clear-scope payload RESOLVES against app-db at use
            time and clears the resolved session scope (never the literal
            reference map, which would match nothing — the silent no-op the
            spec prohibits)"
    (rf/dispatch-sync [:rf.resource/clear-scope
                       {:scope {:from-db :t/session} :cause :logout}])
    (is (nil? (entry (session-key "jake" 1)))
        "jake's session-scoped entry was cleared via the resolved {:from-db} scope")
    (is (empty? (entries)) "no entry survives the resolved-scope clear")))

(deftest clear-scope-from-db-nil-warns-and-clears-nothing
  ;; jake is logged in with a loaded entry; we clear-scope with a {:from-db}
  ;; reference AFTER logout (the resolver's :inputs are gone → resolves nil).
  (rf/dispatch-sync [:t/login "jake"])
  (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page 1}
                                          :owner [:lease :c 1]}])
  (settle-loaded! (session-key "jake" 1) {:for "jake"})
  (rf/dispatch-sync [:t/logout])
  (testing "rf2-mfnc5i / Spec 016 §clear-scope is causal — a {:from-db …}
            clear-scope reference that resolves NIL (no logged-in user) emits a
            loud :rf.warning/resource-clear-scope-unresolved diagnostic and
            clears NOTHING — never a silent no-op, never a fall-through to
            clearing global"
    (let [warns (record-clear-scope-warnings!
                  (fn []
                    (rf/dispatch-sync [:rf.resource/clear-scope
                                       {:scope {:from-db :t/session}
                                        :cause :logout}])))]
      (is (= 1 (count warns)) "exactly one unresolved warning fired")
      (let [w (first warns)]
        (is (= :rf.warning/resource-clear-scope-unresolved (:operation w)))
        (is (= :t/session (-> w :tags :from-db)) "names the unresolved resolver id"))
      ;; jake's entry is UNTOUCHED — the nil-resolving reference cleared nothing
      ;; (the entry would only be reachable under the resolved session scope,
      ;; which is exactly what failed to resolve here)
      (is (some? (entry (session-key "jake" 1)))
          "the nil-resolving clear-scope cleared NOTHING (fail-closed, not silent)"))))

;; ===========================================================================
;; rf2-fi6tda.4 finding 2 — pin the runtime :rf.resource/scope-resolved trace
;; ===========================================================================

(defn- record-scope-resolved!
  "Run `body-fn`; return the vector of every :rf.resource/scope-resolved trace
  event emitted during it (the named-resolver resolution evidence)."
  [body-fn]
  (let [seen (atom [])
        k    ::scope-resolved-recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= :rf.resource/scope-resolved (:operation ev)) (swap! seen conj ev))))
    (try (body-fn) (finally (trace-tooling/unregister-listener! k)))
    @seen))

(deftest scope-resolved-trace-emitted-on-event-ensure-with-full-shape
  ;; rf2-fi6tda.4 finding 2: a {:from-db :t/session} resolution on event ensure
  ;; emits a :rf.resource/scope-resolved row carrying :resource-id, :kind, the
  ;; declared :inputs, the resolved :input-values, :whole-db?, the resolved
  ;; :scope, and :resolved-nil? — pinned against the RUNTIME, not a synthetic
  ;; row (so a regression that stops emitting or mis-shapes it fails here).
  (rf/dispatch-sync [:t/login "jake"])
  (let [rows (record-scope-resolved!
               (fn []
                 (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page 1}
                                                         :owner [:lease :sr 1]}])))
        row  (some (fn [ev] (when (= :t/session (:resource-id (:tags ev))) (:tags ev))) rows)]
    (testing "a scope-resolved row was emitted for the :t/session resolver"
      (is (some? row)))
    (testing "the row carries the full resolution evidence shape"
      (is (= :t/session (:resource-id row)))
      (is (= :resource-scope (:kind row)))
      (is (= [:username] (:inputs row)) "the declared input NAMES")
      (is (= {:username "jake"} (:input-values row)) "the resolved input VALUES")
      (is (= [:rf.scope/session {:username "jake"}] (:scope row)) "the resolved scope")
      (is (false? (:resolved-nil? row))))))

(deftest scope-resolved-trace-records-resolved-nil-on-absent-input
  ;; the fail-closed nil-resolution evidence: when the resolver's :inputs are
  ;; absent (no logged-in user), the row carries :resolved-nil? true + a nil
  ;; :scope (the use site interprets it — never an implicit global).
  (rf/reg-resource :t/notes
    {:scope         :rf.scope/from-caller
     :params-schema [:map]}
    (fn [_p _ctx] {:request {:method :get :url "/notes"}}))
  (let [rows (record-scope-resolved!
               (fn []
                 ;; no :t/login — the resolver's :username input is absent → nil.
                 ;; the event ensure fails closed (a throw the loop surfaces), but
                 ;; the scope-resolved trace fires FIRST, carrying the nil evidence.
                 (try
                   (rf/dispatch-sync [:rf.resource/ensure {:resource :t/notes :params {}
                                                           :scope {:from-db :t/session}
                                                           :owner [:lease :sn 1]}])
                   (catch #?(:clj Throwable :cljs :default) _ nil))))
        row  (some (fn [ev] (when (= :t/session (:resource-id (:tags ev))) (:tags ev))) rows)]
    (testing "the resolution emitted a :resolved-nil? true row with a nil :scope"
      (is (some? row))
      (is (true? (:resolved-nil? row)))
      (is (nil? (:scope row))))))

;; ===========================================================================
;; rf2-ru73k6 F3 — passive reads advertised as pure emit NO scope-resolved
;; trace, while causal boundaries still do
;; ===========================================================================

(deftest pure-resolve-resource-scope-emits-no-trace
  ;; `rf/resolve-resource-scope` is advertised PURE (the logout/account-switch
  ;; idiom resolves the old scope from the cofx db). A pure read must not
  ;; mutate observability state, so it emits NO :rf.resource/scope-resolved
  ;; row — it does not route through the traced resolver core.
  (rf/dispatch-sync [:t/login "jake"])
  (testing "the helper still resolves the concrete scope correctly"
    (let [rows (record-scope-resolved!
                 (fn []
                   (is (= [:rf.scope/session {:username "jake"}]
                          (rf/resolve-resource-scope {:auth {:user {:username "jake"}}}
                                                     :t/session)))
                   ;; and a nil-resolving db still fails closed to nil
                   (is (nil? (rf/resolve-resource-scope {} :t/session)))))]
      (testing "but emits NO :rf.resource/scope-resolved trace (a pure read)"
        (is (zero? (count (filter #(= :t/session (:resource-id (:tags %))) rows)))
            (str "expected no scope-resolved rows from the pure helper; got "
                 (pr-str (mapv :tags rows))))))))

(deftest pure-subscription-key-resolution-emits-no-trace
  ;; Subscription key resolution (`resolve-scoped-key` → `resolve-scope-for-sub`
  ;; → the sub-side {:from-db …} resolution) is a PASSIVE read advertised pure;
  ;; a sub re-keys on every frame-state change, so it MUST resolve trace-free
  ;; (never flood the trace bus with a row per re-render).
  (rf/dispatch-sync [:t/login "jake"])
  (testing "resolving a {:from-db} sub key emits NO scope-resolved row"
    (let [rows (record-scope-resolved!
                 (fn []
                   (is (= (session-key "jake" 1)
                          (r-subs/resolve-scoped-key {:resource :t/feed :params {:page 1}}
                                                     {:auth {:user {:username "jake"}}})))))]
      (is (zero? (count (filter #(= :t/session (:resource-id (:tags %))) rows)))
          (str "expected no scope-resolved rows from sub resolution; got "
               (pr-str (mapv :tags rows)))))))

(deftest causal-boundary-resolution-still-emits-trace
  ;; The other half of the split: the CAUSAL boundaries keep their inspectable
  ;; evidence. An event ensure with a {:from-db} scope DOES emit a
  ;; scope-resolved row (so the split is surgical, not a blanket removal).
  (rf/dispatch-sync [:t/login "jake"])
  (let [rows (record-scope-resolved!
               (fn []
                 (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page 1}
                                                         :owner [:lease :c 1]}])))]
    (testing "the causal event-ensure resolution STILL emits a scope-resolved row"
      (is (pos? (count (filter #(= :t/session (:resource-id (:tags %))) rows)))
          "the traced causal boundary kept its evidence"))))

;; ===========================================================================
;; rf2-84l82t (EP-0015) — OFF-BOX egress projection of the
;; :rf.resource/scope-resolved trace row's resolver-owned values.
;; ===========================================================================

(deftest scope-resolved-off-box-egress-redacts-resolver-values
  ;; The resolved :input-values (raw db reads) and :scope (the derived identity
  ;; tuple embedding them) are owner-local values the generic value-path trace
  ;; egress walk cannot classify. project-scope-resolved-egress FAILS CLOSED:
  ;; a db-reading resolver not explicitly declassified redacts both slots +
  ;; stamps :sensitive? true, preserving the structural attribution slots.
  (testing "the default (:inherit) :t/session resolver redacts its values off-box"
    (let [tags {:resource-id  :t/session
                :kind         :resource-scope
                :inputs       [:username]
                :input-values {:username "jake"}
                :whole-db?    false
                :scope        [:rf.scope/session {:username "jake"}]
                :resolved-nil? false}
          proj (scope-registry/project-scope-resolved-egress tags)]
      (is (= :rf/redacted (:input-values proj)) "raw db reads redacted")
      (is (= :rf/redacted (:scope proj)) "derived identity tuple redacted")
      (is (true? (:sensitive? proj)) "row stamped sensitive for the egress gate")
      (testing "the structural attribution slots ride verbatim"
        (is (= :t/session (:resource-id proj)))
        (is (= :resource-scope (:kind proj)))
        (is (= [:username] (:inputs proj)) "the declared input NAMES survive")
        (is (false? (:resolved-nil? proj))))
      (testing "no raw secret survives the projection"
        (let [secret? (fn secret? [v]
                        (cond (= v "jake") true
                              (map? v)  (boolean (or (some secret? (keys v))
                                                     (some secret? (vals v))))
                              (coll? v) (boolean (some secret? v))
                              :else     false))]
          (is (not (secret? proj))))))))

(deftest scope-resolved-off-box-egress-public-resolver-rides-verbatim
  ;; A resolver that explicitly DECLASSIFIES (:rf.egress/public) asserts its
  ;; derived scope is safe to surface — the trusted, enumerable audit surface.
  (rf/reg-resource-scope :t/public-locale
    {:inputs  {:locale [:db [:i18n :locale]]}
     :rf.egress/output-sensitivity :rf.egress/public
     :resolve (fn [{:keys [locale]} _] (when locale [:rf.scope/locale {:locale locale}]))})
  (testing "an explicitly :rf.egress/public resolver's values ride verbatim"
    (let [tags {:resource-id  :t/public-locale
                :kind         :resource-scope
                :inputs       [:locale]
                :input-values {:locale "en"}
                :scope        [:rf.scope/locale {:locale "en"}]
                :resolved-nil? false}
          proj (scope-registry/project-scope-resolved-egress tags)]
      (is (= {:locale "en"} (:input-values proj)))
      (is (= [:rf.scope/locale {:locale "en"}] (:scope proj)))
      (is (not (:sensitive? proj))))))

(deftest scope-resolver-egress-sensitive-fails-closed-on-unregistered
  ;; An unregistered resolver id → sensitive (fail-closed: no spec proves safe).
  (testing "the predicate fails closed for an unknown resolver"
    (is (true? (scope-registry/scope-resolver-egress-sensitive? :t/never-registered))))
  (testing "the predicate is sensitive for a db-reading :inherit resolver"
    (is (true? (scope-registry/scope-resolver-egress-sensitive? :t/session))))
  (testing "the predicate is NOT sensitive for an explicitly :public resolver"
    (rf/reg-resource-scope :t/public2
      {:inputs  {:locale [:db [:i18n :locale]]}
       :rf.egress/output-sensitivity :rf.egress/public
       :resolve (fn [{:keys [locale]} _] (when locale [:rf.scope/locale {:locale locale}]))})
    (is (false? (scope-registry/scope-resolver-egress-sensitive? :t/public2)))))
