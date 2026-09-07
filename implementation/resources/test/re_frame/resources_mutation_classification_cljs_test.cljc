(ns re-frame.resources-mutation-classification-cljs-test
  "rf2-825mzj (agb5jk item 3) — a mutation OWNER's projection-relative
  `:sensitive` / `:large` declaration governs the mutation ENVELOPE's egress, so
  a `:sensitive [[:params :password]]` param does NOT ship raw in the durable
  instance's egress projection nor on the completion CONTINUATION echo, while the
  causal write (the `:request` handler) and the success-path `:invalidates` /
  `:patches` still read the RAW value.

  The gap this closes (verified by probe on the pre-fix tree): the mutation
  registry SUPPORTED the owner declaration, but the mutation projections IGNORED
  it — the durable instance `:params`, the continuation reply `:params`, and the
  `elide-wire-value` egress walk over `:rf.runtime/mutations` all rode the raw
  value. The fix lowers each live instance's declaration into the per-frame
  elision registry under `:source :mutation` (the resource-entry lowering peer)
  and redacts the resources-constructed continuation reply from the same owner
  declaration.

  CLJC so the JVM run (`clojure -M:test`, the load-bearing gate) exercises it
  and the CLJS node run does too; the schemas artefact is a test-only dep so the
  shared walker hooks are bound."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.classification :as rf.classification]
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.elision :as rf.elision]
   [re-frame.late-bind :as rf.late-bind]
   [re-frame.privacy :as rf.privacy]
   [re-frame.resources.classification :as rf.resources.classification]
   [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
   [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
   ;; load-bearing side-effecting requires: register the :rf.mutation/* events +
   ;; subs + the generation cofx/fx + bind the shared walker hooks.
   [re-frame.resources]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

;; ---- capturing transport (records the lowered request args) ----------------

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture [f]
  (reset! last-managed-args nil)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

;; A UNIQUE sentinel so a leak anywhere in the projected surface is unambiguous.
(def ^:private PW "PW-SENTINEL-7f3a91")

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- reg-secret-mutation!
  "Register `:m/secret` — a mutation classifying its `:password` param
  :sensitive projection-relative to the instance."
  ([] (reg-secret-mutation! {}))
  ([overrides]
   (rf/clear :mutation :m/secret)
   (rf/reg-mutation :m/secret
     (merge {:params-schema [:map [:slug :string] [:password {:optional true} [:maybe :string]]]
             :sensitive     [[:params :password]]}
            overrides)
     (fn [{:keys [slug password]} _]
       {:request {:method :put :url (str "/x/" slug) :body {:slug slug :password password}}}))))

;; A runtime-db carrying ONE mutation instance under its byte key-id, mirroring
;; the durable `:rf.runtime/mutations` shape the runtime mints.
(defn- runtime-db-with-instance [instance-id inst]
  {rf.resources.mutation-runtime/mutations-key {(rf.resources.mutation-runtime/instance-key-id instance-id) inst}})

;; ===========================================================================
;; 1. reconcile-mutation-registry — lowers a live instance's owner declaration
;;    into the per-frame elision registry at the ABSOLUTE instance path.
;; ===========================================================================

(deftest reconcile-lowers-instance-params-declaration
  (reg-secret-mutation!)
  (testing "a live mutation instance's owner :params-rooted :sensitive
            declaration is LOWERED into the elision registry under
            :source :mutation, rooted at the instance's absolute runtime-db path"
    (let [k-id (rf.resources.mutation-runtime/instance-key-id :i1)
          rdb  (runtime-db-with-instance
                 :i1 (rf.resources.mutation-runtime/empty-instance :m/secret :i1 {:params {:slug "w" :password PW}}))
          out  (rf.resources.classification/reconcile-mutation-registry rdb rf.resources.mutation-registry/mutation-meta)
          sens (get-in out [:rf.runtime/elision :sensitive-declarations])]
      (is (= #{{:source :mutation}}
             (get sens [:rf.runtime/mutations k-id :params :password]))
          "the :params :password decl is lowered at the absolute instance path"))))

(deftest reconcile-mutation-is-idempotent
  (reg-secret-mutation!)
  (testing "re-running the mutation reconcile over its own output is a no-op"
    (let [rdb   (runtime-db-with-instance
                  :i1 (rf.resources.mutation-runtime/empty-instance :m/secret :i1 {:params {:slug "w" :password PW}}))
          once  (rf.resources.classification/reconcile-mutation-registry rdb rf.resources.mutation-registry/mutation-meta)
          twice (rf.resources.classification/reconcile-mutation-registry once rf.resources.mutation-registry/mutation-meta)]
      (is (= once twice) "mutation reconciliation is idempotent"))))

(deftest reconcile-mutation-self-drops-cleared-instance
  (reg-secret-mutation!)
  (testing "a cleared instance's :source :mutation declaration vanishes on the
            next reconcile (the per-instance teardown — no separate drop hook)"
    (let [rdb   (runtime-db-with-instance
                  :i1 (rf.resources.mutation-runtime/empty-instance :m/secret :i1 {:params {:slug "w" :password PW}}))
          live  (rf.resources.classification/reconcile-mutation-registry rdb rf.resources.mutation-registry/mutation-meta)
          ;; drop the instance, keep the carried registry, reconcile again.
          cleared (-> live
                      (assoc rf.resources.mutation-runtime/mutations-key {})
                      (rf.resources.classification/reconcile-mutation-registry rf.resources.mutation-registry/mutation-meta))]
      (is (seq (get-in live [:rf.runtime/elision :sensitive-declarations]))
          "the live instance lowered a declaration")
      (is (empty? (get-in cleared [:rf.runtime/elision :sensitive-declarations]))
          "the cleared instance's declaration is dropped"))))

(deftest reconcile-mutation-preserves-foreign-owner
  (reg-secret-mutation!)
  (testing "a non-mutation-sourced registry entry (:source :resource / :effect)
            rides untouched through the mutation reconcile (multi-owner union)"
    (let [rdb (-> (runtime-db-with-instance
                    :i1 (rf.resources.mutation-runtime/empty-instance :m/secret :i1 {:params {:slug "w" :password PW}}))
                  (assoc-in [:rf.runtime/elision :sensitive-declarations [:app :token]]
                            #{{:source :effect}}))
          out (rf.resources.classification/reconcile-mutation-registry rdb rf.resources.mutation-registry/mutation-meta)]
      (is (= #{{:source :effect}}
             (get-in out [:rf.runtime/elision :sensitive-declarations [:app :token]]))
          "the :source :effect entry survives the mutation reconcile"))))

(deftest reconcile-mutation-no-classification-no-registry
  (rf/clear :mutation :m/plain)
  (rf/reg-mutation :m/plain {:params-schema [:map [:slug :string]]}
    (fn [_ _] {:request {:method :get :url "/x"}}))
  (testing "a mutation that declares no classification lowers nothing"
    (let [rdb (runtime-db-with-instance
                :i1 (rf.resources.mutation-runtime/empty-instance :m/plain :i1 {:params {:slug "w"}}))
          out (rf.resources.classification/reconcile-mutation-registry rdb rf.resources.mutation-registry/mutation-meta)]
      (is (not (contains? out :rf.runtime/elision))
          "no :rf.runtime/elision key when nothing classifies"))))

;; ===========================================================================
;; 2. redact-continuation-reply — derives the reply redaction from the owner.
;; ===========================================================================

(deftest redact-continuation-reply-redacts-owner-param
  (reg-secret-mutation!)
  (testing "the owner-declared :params :password is redacted on the continuation
            reply map; the non-sensitive sibling rides verbatim"
    (let [reply {:status :ok :value {:ok true}
                 :params {:slug "w" :password PW} :scope :rf.scope/global}
          out   (rf.resources.classification/redact-continuation-reply reply (rf.resources.mutation-registry/mutation-meta :m/secret))]
      (is (= rf.privacy/redacted-sentinel (get-in out [:params :password]))
          "the sensitive param is redacted on the reply")
      (is (= "w" (get-in out [:params :slug])) "the non-sensitive param rides verbatim")
      (is (= {:ok true} (:value out)) "the result value rides verbatim")
      (is (not (str/includes? (pr-str out) PW)) "no raw sentinel rides on the reply"))))

(deftest redact-continuation-reply-unclassified-rides-verbatim
  (rf/clear :mutation :m/plain)
  (rf/reg-mutation :m/plain {:params-schema [:map [:slug :string]]}
    (fn [_ _] {:request {:method :get :url "/x"}}))
  (testing "a mutation that declares no classification rides the reply UNCHANGED"
    (let [reply {:status :ok :params {:slug "w" :password PW}}]
      (is (= reply (rf.resources.classification/redact-continuation-reply reply (rf.resources.mutation-registry/mutation-meta :m/plain)))
          "no declaration → the reply is unchanged"))))

;; ===========================================================================
;; 3. END-TO-END — drive a real classified mutation through execute + reply.
;; ===========================================================================

(deftest execute-lowers-instance-and-redacts-egress-and-continuation
  (reg-secret-mutation!)
  (let [replied (atom nil)
        traces  (atom [])]
    (rf/reg-event :m/replied (fn [_ [_ reply]] (reset! replied reply) {}))
    (rf.trace.tooling/register-listener! ::rec (fn [ev] (swap! traces conj ev)))
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/secret
                        :params   {:slug "w" :password PW}
                        :instance :i1
                        :reply-to [:m/replied]}])
    ;; the causal write handler runs on the RAW params BEFORE the instance mints.
    (testing "the :request handler received the RAW password (causal write intact)"
      (is (= PW (get-in @last-managed-args [:request :body :password]))))
    ;; reply success to settle the instance + fire the continuation.
    (rf/dispatch-sync (conj (:on-success @last-managed-args) {:status :ok :value {:ok true}}))
    (rf.trace.tooling/unregister-listener! ::rec)

    (let [rdb  (runtime-db)
          k-id (rf.resources.mutation-runtime/instance-key-id :i1)
          inst (get-in rdb (rf.resources.mutation-runtime/instance-path :i1))]
      (testing "the durable instance keeps the RAW params (success-path fns read them)"
        (is (= PW (get-in inst [:params :password]))
            "the durable instance :password is NOT destroyed"))
      (testing "the owner declaration is LOWERED into the frame elision registry"
        (is (= #{{:source :mutation}}
               (get (rf.elision/sensitive-declarations :rf/default)
                    [:rf.runtime/mutations k-id :params :password]))
            "the instance :params :password decl is in the per-frame registry"))
      (testing "the off-box egress walk over the instance REDACTS :password"
        (let [proj (rf/elide-wire-value inst {:frame :rf/default
                                              :path [:rf.runtime/mutations k-id]
                                              :rf.egress/profile :rf.egress/off-box-tool})]
          (is (= rf.privacy/redacted-sentinel (get-in proj [:params :password]))
              "the instance :password is redacted at egress")
          (is (= "w" (get-in proj [:params :slug])) "the non-sensitive :slug rides verbatim")
          (is (not (str/includes? (pr-str proj) PW))
              "no raw sentinel rides anywhere on the projected instance"))))

    (testing "the continuation reply redacts :password but keeps the result + slug"
      (is (= rf.privacy/redacted-sentinel (get-in @replied [:params :password]))
          "the continuation reply :password is redacted")
      (is (= "w" (get-in @replied [:params :slug])) "the reply :slug rides verbatim")
      (is (= {:ok true} (:value @replied)) "the reply :value (result) rides verbatim")
      (is (not (str/includes? (pr-str @replied) PW)) "no raw sentinel rides on the reply"))

    (testing "no :rf.mutation/* trace row carries the raw sentinel (params never
              ride the mutation trace family)"
      (doseq [ev @traces]
        (when (and (keyword? (:operation ev))
                   (= "rf.mutation" (namespace (:operation ev))))
          (is (not (str/includes? (pr-str (:tags ev)) PW))
              (str (:operation ev) " must not carry the raw sentinel")))))

    (testing "rf2-3ej3xu — the execute event's OWN :rf.event/v trace slot
              redacts the owner-declared param (the gap: the dispatched-event
              trace is projected by the CORE event chokepoint, which knew only
              the event REGISTRATION's static classification — nothing, for
              :rf.mutation/execute — so the trusted-local :include-event-args?
              opt-in path rode the raw payload)"
      (let [vs (->> @traces
                    (keep #(get-in % [:tags :rf.event/v]))
                    (filter #(and (vector? %) (= :rf.mutation/execute (first %)))))]
        (is (seq vs) "the execute dispatched-event trace surfaced")
        (doseq [v vs]
          (is (= rf.privacy/redacted-sentinel (get-in v [1 :params :password]))
              "the owner-declared :params :password redacts at :rf.event/v")
          (is (= "w" (get-in v [1 :params :slug]))
              "the non-sensitive :slug rides verbatim at :rf.event/v"))))

    (testing "the FAILURE continuation echo redacts :password too (an accepted
              :error reply also dispatches :reply-to)"
      (reset! last-managed-args nil)
      (reset! replied nil)
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :m/secret
                          :params   {:slug "w" :password PW}
                          :instance :i2
                          :reply-to [:m/replied]}])
      (rf/dispatch-sync (conj (:on-failure @last-managed-args)
                              {:status :error :error {:status 422 :body "nope"}}))
      (is (= rf.privacy/redacted-sentinel (get-in @replied [:params :password]))
          "the failure continuation reply :password is redacted")
      (is (= "w" (get-in @replied [:params :slug])) "the failure reply :slug rides verbatim")
      (is (not (str/includes? (pr-str @replied) PW))
          "no raw sentinel rides on the failure reply"))

    (testing "clear DROPS the lowered instance declaration (self-dropping)"
      (rf/dispatch-sync [:rf.mutation/clear {:instance :i1}])
      (is (empty? (get (rf.elision/sensitive-declarations :rf/default)
                       [:rf.runtime/mutations (rf.resources.mutation-runtime/instance-key-id :i1) :params :password]))
          "the cleared instance's declaration is gone from the registry"))))

;; ===========================================================================
;; 4. rf2-3ej3xu — the [:rf.mutation/execute …] event-payload projection.
;;    The execute payload names its owner INSIDE the args (:mutation), so the
;;    core event-vector chokepoint defers to the resources-published
;;    :resources/project-execute-event-args hook — the event peer of
;;    :http/project-managed-fx-args. Deterministic teeth on the projector +
;;    the core chokepoint; the live acceptance rides the section-3 drive.
;; ===========================================================================

(deftest project-execute-event-args-redacts-owner-param
  (reg-secret-mutation!)
  (testing "the owner-declared :params :password redacts on the execute args;
            the non-sensitive sibling and the structural :mutation id ride"
    (let [args {:mutation :m/secret :params {:slug "w" :password PW} :instance :i9}
          out  (rf.resources.classification/project-execute-event-args args rf.resources.mutation-registry/mutation-meta)]
      (is (= rf.privacy/redacted-sentinel (get-in out [:params :password]))
          "the sensitive param is redacted on the execute payload")
      (is (= "w" (get-in out [:params :slug])) "the non-sensitive param rides verbatim")
      (is (= :m/secret (:mutation out)) "the owner id survives (attribution)")
      (is (not (str/includes? (pr-str out) PW)) "no raw sentinel rides the payload"))))

(deftest project-execute-event-args-scope-rooted-decl
  (rf/clear :mutation :m/scoped)
  (rf/reg-mutation :m/scoped
    {:params-schema [:map [:slug :string]]
     :sensitive     [[:scope :tenant]]}
    (fn [_ _] {:request {:method :get :url "/x"}}))
  (testing "a :scope-rooted decl redacts the execute payload's sibling :scope
            slot (Spec 016 clause 4 — params, scopes, and data carry the same
            classification)"
    (let [out (rf.resources.classification/project-execute-event-args
                {:mutation :m/scoped :params {:slug "w"}
                 :scope    {:tenant PW :region "r"}}
                rf.resources.mutation-registry/mutation-meta)]
      (is (= rf.privacy/redacted-sentinel (get-in out [:scope :tenant]))
          "the :scope-rooted decl bites the payload's :scope")
      (is (= "r" (get-in out [:scope :region])) "the non-sensitive scope field rides"))))

(deftest project-execute-event-args-data-rooted-skipped
  (rf/clear :mutation :m/data-classified)
  (rf/reg-mutation :m/data-classified
    {:params-schema [:map [:slug :string]]
     :sensitive     [[:data :token]]}
    (fn [_ _] {:request {:method :get :url "/x"}}))
  (testing "a :data-rooted decl names the not-yet-existing RESULT projection —
            the execute payload rides UNCHANGED (reference-preserved, no
            phantom slot)"
    (let [args {:mutation :m/data-classified :params {:slug "w"}}]
      (is (identical? args (rf.resources.classification/project-execute-event-args
                             args rf.resources.mutation-registry/mutation-meta))))))

(deftest project-execute-event-args-fail-open
  (testing "an unregistered :mutation id / a non-map payload rides UNCHANGED
            (the EP-0025 fail-open — no registration to read a declaration off)"
    (rf/clear :mutation :m/ghost)
    (let [args {:mutation :m/ghost :params {:password PW}}]
      (is (identical? args (rf.resources.classification/project-execute-event-args
                             args rf.resources.mutation-registry/mutation-meta))))
    (is (= :not-a-map (rf.resources.classification/project-execute-event-args
                        :not-a-map rf.resources.mutation-registry/mutation-meta)))))

(deftest project-execute-event-args-reply-to-rides-target-classification
  (reg-secret-mutation!)
  (rf/reg-event :m/reply-target
    {:sensitive [[:cb-secret]]}
    (fn [{:keys [db]} _] {:db db}))
  (testing "a payload-carrying :reply-to address rides the TARGET event
            registration's own classification — the same composition the
            managed-HTTP :on-success / :on-failure addresses get"
    (let [out (rf.resources.classification/project-execute-event-args
                {:mutation :m/secret
                 :params   {:slug "w" :password PW}
                 :reply-to [:m/reply-target {:cb-secret PW :tag "t"}]}
                rf.resources.mutation-registry/mutation-meta)]
      (is (= rf.privacy/redacted-sentinel (get-in out [:reply-to 1 :cb-secret]))
          "the reply-to target's declared path redacts")
      (is (= "t" (get-in out [:reply-to 1 :tag])) "the non-secret tag rides")
      (is (not (str/includes? (pr-str out) PW)) "no raw sentinel anywhere"))))

(deftest execute-event-args-hook-is-published
  (testing "re-frame.resources publishes the hook the core event-vector
            chokepoint consults (load-time anchor)"
    (is (fn? (rf.late-bind/get-fn :resources/project-execute-event-args)))))

(deftest core-event-chokepoint-projects-execute-payload
  (reg-secret-mutation!)
  (testing "re-frame.classification/redact-event-by-registration (the single
            event-vector chokepoint — also the ALWAYS-ON :rf.observe/* egress
            redactor) consults the resources hook for [:rf.mutation/execute …]"
    (let [v (rf.classification/redact-event-by-registration
              [:rf.mutation/execute {:mutation :m/secret
                                     :params   {:slug "w" :password PW}}])]
      (is (= rf.privacy/redacted-sentinel (get-in v [1 :params :password]))
          "the owner-declared param redacts through the core chokepoint")
      (is (= "w" (get-in v [1 :params :slug])) "the non-sensitive param rides")
      (is (= :rf.mutation/execute (first v)) "the event id survives"))))

(deftest core-trace-slots-project-execute-payload
  (reg-secret-mutation!)
  (testing "the :rf.event/v dispatched-event slot AND a nested
            [:dispatch [:rf.mutation/execute …]] fx entry both redact through
            the same chokepoint (deterministic projector teeth on hand-built
            trace shapes, mirroring fx_aggregate_classification)"
    (let [payload {:mutation :m/secret :params {:slug "w" :password PW}}
          disp    (rf.classification/project-trace-event
                    {:operation :rf.event/dispatched
                     :tags {:frame       :rf/default
                            :rf.event/v [:rf.mutation/execute payload]}})
          agg     (rf.classification/project-trace-event
                    {:operation :rf.fx/do-fx
                     :tags {:frame        :rf/default
                            :rf.event/fx [[:dispatch [:rf.mutation/execute payload]]]}})]
      (is (= rf.privacy/redacted-sentinel
             (get-in disp [:tags :rf.event/v 1 :params :password]))
          ":rf.event/v redacts the owner-declared param")
      (is (= "w" (get-in disp [:tags :rf.event/v 1 :params :slug]))
          ":rf.event/v keeps the non-sensitive sibling")
      (is (= rf.privacy/redacted-sentinel
             (get-in agg [:tags :rf.event/fx 0 1 1 :params :password]))
          "the nested :dispatch fx entry inherits the same projection")
      (is (not (str/includes? (pr-str [disp agg]) PW))
          "no raw sentinel rides either projected shape"))))
