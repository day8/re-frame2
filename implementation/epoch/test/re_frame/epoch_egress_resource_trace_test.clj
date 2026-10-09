(ns re-frame.epoch-egress-resource-trace-test
  "Off-box egress of the resource / mutation trace family through an epoch
  record (EP-0015): the family-row projector the epoch tool-pair reaches through
  the late-bound `:resources/project-resource-trace-egress` hook, and the
  fx-carrier projector it reaches through `:resources/project-fx-args-egress`.
  The projectors' token contracts (content-free sensitive tokens, the `:large?`
  digest, the cursor) are pinned beside them in the resources suite; this
  namespace pins what reaches them through an epoch record, and the real
  drives that produce those records.

  resources is a TEST-ONLY dep: production epoch finds the hooks nil and passes
  the rows through."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.epoch :as rf.epoch]
            [re-frame.frame :as rf.frame]
            ;; `fx/reg-fx`, the plain fn: the `rf/reg-fx` macro would stamp this
            ;; ns as a second provenance under one fx id, and the frame's next
            ;; default-image reprojection would die on `:rf.error/image-duplicate-id`.
            [re-frame.fx :as rf.fx]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]
            ;; load-bearing: publishes the :resources/* late-bind egress hooks.
            [re-frame.resources]
            ;; load-bearing: `ensure` lowers into managed HTTP, which fails
            ;; closed with `:rf.error/http-artefact-missing` without this ns.
            [re-frame.http.managed]
            [re-frame.schemas]))

(def ^:private secret "topsecret-PII")
(def ^:private plain-slug "welcome")
(def ^:private real-owner [:app :reader 1])

(def ^:private reset-runtime-fixture
  "Held by name so `in-an-isolated-runtime` can run several drives inside one
  deftest under exactly the runtime every other test here gets."
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf/make-frame {:id :test/rt})
                (rf/reg-resource :secret/article
                  {:scope         :rf.scope/global
                   :sensitive?    true
                   :params-schema [:map [:auth-token :string]]}
                  (fn [_ _] {:request {:method :get :url "/x"}}))
                ;; An entry does not inherit :redact from its scope resolver's
                ;; inputs (EP-0025): the OWNER declares :sensitive?.
                (rf/reg-resource-scope :rt/session
                  {:inputs {:username [:db [:auth :user :username]]}}
                  (fn [{:keys [username]} _]
                    (when username [:rf.scope/session {:username username}])))
                (rf/reg-resource :derived/profile
                  {:scope         {:from-db :rt/session}
                   :sensitive?    true
                   :params-schema [:map [:slug :string]]}
                  (fn [_ _] {:request {:method :get :url "/z"}}))
                (rf/reg-resource :plain/article
                  {:scope         :rf.scope/global
                   :params-schema [:map [:slug :string]]}
                  (fn [_ _] {:request {:method :get :url "/a"}}))
                ;; No coarse claim, only PROJECTION-RELATIVE slots, so its key
                ;; and reply classify `:serialize` and only the declarations
                ;; can project the reply: `:email` redacts, `:avatar` elides,
                ;; `:display-name` rides.
                (rf/reg-resource :declared/profile
                  {:scope         :rf.scope/global
                   :sensitive     [[:data :email]]
                   :large         [[:data :avatar]]
                   :params-schema [:map [:slug :string]]}
                  (fn [_ _] {:request {:method :get :url "/c"}}))
                (rf/reg-resource :declared/params-owner
                  {:scope         :rf.scope/global
                   :sensitive     [[:params :account]]
                   :params-schema [:map [:account :string] [:slug :string]]}
                  (fn [_ _] {:request {:method :get :url "/d"}}))
                ;; The same declaration on an INFINITE FEED, whose reply `:value`
                ;; is the merged item list, so `[:data :email]` names a field
                ;; of each item.
                (rf/reg-resource :declared/feed
                  {:scope           :rf.scope/global
                   :infinite        true
                   :next-page-param (fn [_last _all] nil)
                   :page->items     :items
                   :sensitive       [[:data :email]]
                   :large           [[:data :avatar]]
                   :params-schema   [:map [:filter :keyword]]}
                  (fn [_ _] {:request {:method :get :url "/e"}}))
                ;; A :sensitive? owner whose legal params are NOT a map, so its
                ;; key is recognised by the registry rather than by a map at
                ;; position 2.
                (rf/reg-resource :secret/vector-params
                  {:scope         :rf.scope/global
                   :sensitive?    true
                   :params-schema [:vector :string]}
                  (fn [_ _] {:request {:method :get :url "/g"}}))
                (rf/reg-resource :plain/vector-params
                  {:scope         :rf.scope/global
                   :params-schema [:vector :string]}
                  (fn [_ _] {:request {:method :get :url "/h"}}))
                (rf/reg-resource :plain/profile
                  {:scope         {:from-db :rt/session}
                   :params-schema [:map [:slug :string]]}
                  (fn [_ _] {:request {:method :get :url "/b"}})))}))

(use-fixtures :each reset-runtime-fixture)

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- contains-secret? [v]
  (boolean
    (cond
      (string? v)  (.contains ^String v "topsecret")
      (map? v)     (or (some contains-secret? (keys v)) (some contains-secret? (vals v)))
      (coll? v)    (some contains-secret? v)
      :else        false)))

(defn- secret-leak-paths
  "Every path in `x` whose leaf string carries the secret, with the value, so a
  failure names the slot that leaked."
  [x]
  (let [found (atom [])
        walk  (fn walk [path v]
                (cond
                  (string? v) (when (.contains ^String v "topsecret")
                                (swap! found conj [path v]))
                  (map? v)    (doseq [[k vv] v]
                                (walk (conj path k) k)
                                (walk (conj path k) vv))
                  (coll? v)   (doseq [[i vv] (map-indexed vector v)]
                                (walk (conj path i) vv))))]
    (walk [] x)
    @found))

(defn- sk
  "A scoped key `[scope resource-id params]`, the identity the rows copy."
  [scope resource-id params]
  (rf.resources.state/scoped-resource-key scope resource-id params))

(defn- redacted-component? [c]
  (and (map? c) (contains? c :rf/redacted)))

(defn- event [operation tags]
  {:op-type :rf.event :operation operation :tags tags})

(defn- handled-row
  "An `:rf.fx/handled` row carrying `args` under `:rf.fx/args`."
  [fx-id args]
  (event :rf.fx/handled
         {:rf.frame/id :test/rt :frame :test/rt :rf.fx/id fx-id :rf.fx/args args}))

(defn- record-with [trace-events]
  {:kind          :rf/epoch-record
   :epoch-id      1
   :frame         :test/rt
   :committed-at  0
   :event-id      :go
   :trigger-event [:go]
   :db-before     {}
   :db-after      {}
   :outcome       :ok
   :trace-events  trace-events
   :sub-runs      []
   :renders       []
   :effects       []})

(defn- projected-tags
  "The tags of the first trace row of `record`, projected off-box."
  ([record] (projected-tags record nil))
  ([record opts]
   (-> (rf/project-egress record opts) :trace-events first :tags)))

;; ===========================================================================
;; family rows — the projector reached by operation namespace
;; ===========================================================================

(deftest off-box-redacts-rollback-dispositions-owner-sensitive-scope
  (testing "a rollback disposition row's :resource/key tokenizes its OWNER's
            scope and params; the resource-id and the boolean facts ride"
    (let [k   (sk [:rf.scope/session {:username secret}] :derived/profile {:slug "me"})
          row (-> (projected-tags
                    (record-with
                      [(event :rf.mutation/optimistic-rolled-back
                              {:rf.frame/id :test/rt :mutation :m/upd :instance 2
                               :dispositions [{:resource/key k :restored true :conflict false}]})]))
                  :dispositions first)
          [pscope rid pparams] (:resource/key row)]
      (is (every? redacted-component? [pscope pparams]))
      (is (= [:derived/profile true false] [rid (:restored row) (:conflict row)])))))

(def ^:private optimistic-params
  "Deliberately not the secret: only the cached `:data` carries it, so a hit can
  only be the entry snapshot."
  {:auth-token "u1"})

(defn- drive-optimistic-commit!
  "Drive a REAL optimistic write to an accepted `:ok` over the `:sensitive?`
  `:secret/article` entry, first loaded with the secret in its `:data`. Returns
  every trace row the drive put on the bus."
  []
  (rf/configure! {:epoch-history {:trace-events-keep 80}})
  (let [captured (atom nil)
        rows     (atom [])
        k        ::optimistic-commit-recorder]
    (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
    (rf/reg-mutation :m/rename
      {:scope         :rf.scope/global
       :params-schema [:map [:auth-token :string]]
       :optimistic    (fn [params]
                        {{:resource :secret/article :params params
                          :scope    :rf.scope/global}
                         (fn [data] (assoc data :nick "new"))})}
      (fn [_ _] {:request {:method :put :url "/x"}}))
    (rf.trace.tooling/register-listener! k (fn [ev] (swap! rows conj ev)))
    (try
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :secret/article :params optimistic-params
                          :owner    real-owner}]
                        {:frame :test/rt})
      (rf/dispatch-sync (conj (:on-success @captured)
                              {:status :ok :value {:ssn secret :nick "old"}})
                        {:frame :test/rt})
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :m/rename :params optimistic-params
                          :instance :rename-1}]
                        {:frame :test/rt})
      (rf/dispatch-sync (conj (:on-success @captured) {:status :ok :value {:saved true}})
                        {:frame :test/rt})
      (finally (rf.trace.tooling/unregister-listener! k)))
    @rows))

(deftest real-optimistic-commit-ships-no-entry-snapshot-off-box
  (testing "an optimistic write that COMMITS over a :sensitive? entry records its
            touched key on :rf.mutation/succeeded with no pre-apply entry
            snapshot, whose `:data` would carry the secret"
    (let [succeeded (->> (drive-optimistic-commit!)
                         (filter #(= :rf.mutation/succeeded (:operation %)))
                         first
                         :tags)]
      (is (= 1 (count (get-in succeeded [:patch-summary :rollback])))
          "FIXTURE — the optimistic write committed one touched key")
      (is (empty? (secret-leak-paths succeeded))))))

(deftest off-box-disposition-row-fails-closed-on-non-key-slots
  (testing "a disposition row's slots other than :resource/key take the
            unknown-slot rule: a map (a stray entry snapshot) tokenizes, the
            scalar facts ride"
    (let [k   (sk :rf.scope/global :secret/article optimistic-params)
          row (-> (projected-tags
                    (record-with
                      [(event :rf.mutation/succeeded
                              {:rf.frame/id :test/rt :mutation :m/rename :instance 1
                               :patch-summary
                               {:rollback [{:resource/key     k
                                            :revision         3
                                            :applied-revision 4
                                            :forward          :patch
                                            :before           {:resource/key k
                                                               :data {:ssn secret}}}]}})]))
                  (get-in [:patch-summary :rollback])
                  first)]
      (is (redacted-component? (:before row)))
      (is (= [3 4 :patch] [(:revision row) (:applied-revision row) (:forward row)])))))

(deftest trusted-local-include-sensitive-keeps-raw-keys
  (testing "the trusted-local :rf.egress/include-sensitive? opt-in lifts the
            family-row projection (the local-raw boundary)"
    (let [k (sk :rf.scope/global :secret/article {:auth-token secret})]
      (is (= k (:resource/key
                 (projected-tags
                   (record-with [(event :rf.resource/cache-hit
                                        {:rf.frame/id :test/rt :resource/key k})])
                   {:rf.egress/include-sensitive? true})))))))

(deftest off-box-fail-closed-on-unknown-map-slot
  (testing "a map under a slot no projector clause names is tokenized, so a
            future slot cannot leak app data"
    (is (redacted-component?
          (:future-detail
            (projected-tags
              (record-with [(event :rf.resource/failed
                                   {:rf.frame/id   :test/rt
                                    :generation    5
                                    :future-detail {:hidden secret}})])))))))

;; The projector reads scoped keys by SHAPE, so a key under a slot nobody named
;; (`:blocking` / `:identities` on a route plan, the key embedded in every
;; resource `:work/id`) projects through its owner exactly as a named slot's
;; key does. A map at position 2 proves a key; for an owner whose legal params
;; are not a map, the resource registry does.

(def ^:private vector-secret (str secret "-vector"))
(def ^:private vector-params [vector-secret])

(deftest unnamed-slot-projects-identically-to-named-slot
  (testing "the same :sensitive? keys — map and non-map params — project
            identically under a NAMED slot (:matched) and an UNNAMED one
            (:blocking); each resource-id survives and nothing raw egresses"
    (let [ks        [(sk :rf.scope/global :secret/article {:auth-token secret})
                     (sk :rf.scope/global :secret/vector-params vector-params)]
          projected (rf/project-egress
                      (record-with [(event :rf.resource/route-plan
                                           {:rf.frame/id :test/rt
                                            :matched     ks
                                            :blocking    ks
                                            :removed     1})]))
          tags      (:tags (first (:trace-events projected)))]
      (is (= (:matched tags) (:blocking tags)))
      (is (= [:secret/article :secret/vector-params] (mapv second (:blocking tags))))
      (is (= [] (secret-leak-paths projected)))
      (is (= 1 (:removed tags))
          "a route plan's :removed is an INT count under a key-vector slot name — it rides")
      (is (true? (:sensitive? tags))))))

(deftest off-box-redacts-non-map-param-key-embedded-in-resource-work-id
  (testing "the scoped key embedded in a resource :work/id — non-map params,
            recognised through the registry — projects exactly as the row's
            own :resource/key"
    (let [k         (sk :rf.scope/global :secret/vector-params vector-params)
          projected (rf/project-egress
                      (record-with [(event :rf.resource/work-started
                                           {:rf.frame/id  :test/rt
                                            :resource/key k
                                            :generation   3
                                            :work/id      (rf.resources.work-ledger/resource-work-id k 3)})]))
          tags      (:tags (first (:trace-events projected)))]
      (is (= [:rf.work/resource (:resource/key tags) 3] (:work/id tags)))
      (is (= [] (secret-leak-paths projected))))))

(deftest off-box-keeps-plain-owner-identity-partition-verbatim
  (testing "a PLAIN owner's keys — map and non-map params — ride an unnamed
            slot verbatim and stamp nothing"
    (let [ks   [(sk :rf.scope/global :plain/article {:slug plain-slug})
                (sk :rf.scope/global :plain/vector-params ["welcome"])]
          tags (projected-tags
                 (record-with [(event :rf.resource/route-plan
                                      {:rf.frame/id :test/rt :identities ks})]))]
      (is (= ks (:identities tags)))
      (is (not (:sensitive? tags))))))

(deftest off-box-keeps-structural-three-vectors-verbatim-under-unnamed-slots
  (testing "the family's structural vectors wear a scoped key's skeleton but name
            no RESOURCE (a mutation id is in another registrar), so a view path,
            a mutation attribution triple and a three-id route branch ride
            verbatim; a set-valued tag stays a set; nothing is stamped"
    (let [tags {:rf.frame/id :test/rt
                :owner       [:app :l 1]
                :cause       [:mutation :m/save 7]
                :branch      [:r/root :r/article :r/comments]
                :tags        #{:tag/articles :tag/feed}}]
      (is (= tags (projected-tags (record-with [(event :rf.resource/route-plan tags)])))))))

;; ---------------------------------------------------------------------------
;; the FREE `:scope` tag — classified by shape on every family row, since the
;; sibling projector runs on `:rf.resource/scope-resolved` only
;; ---------------------------------------------------------------------------

(def ^:private session-scope [:rf.scope/session {:username secret}])
(def ^:private plain-session-scope [:rf.scope/session {:username "alice"}])

(deftest off-box-keeps-global-scope-and-plain-owner-key-verbatim
  (testing "a :rf.scope/global free scope is a scalar and rides; a PLAIN owner's
            :resource/key rides; nothing is stamped"
    (let [tags {:rf.frame/id  :test/rt
                :resource/key (sk :rf.scope/global :plain/article {:slug plain-slug})
                :scope        :rf.scope/global
                :decision     :refetch}]
      (is (= tags (projected-tags (record-with [(event :rf.resource/refetch-decision tags)])))))))

(deftest off-box-plain-owner-free-scope-map-fails-closed-key-rides-verbatim
  (testing "a free :scope names no owner, so its identity map fails closed even
            beside a PLAIN owner's key — the tier rides and the row is stamped —
            while that key and its :matched copy ride verbatim"
    (let [k    (sk plain-session-scope :plain/profile {:slug "me"})
          tags (projected-tags
                 (record-with [(event :rf.resource/invalidated
                                      {:rf.frame/id  :test/rt
                                       :scope        plain-session-scope
                                       :resource/key k
                                       :matched      [k]})]))
          [tier identity-map] (:scope tags)]
      (is (= :rf.scope/session tier))
      (is (redacted-component? identity-map))
      (is (= [k [k]] [(:resource/key tags) (:matched tags)]))
      (is (true? (:sensitive? tags))))))

;; ---------------------------------------------------------------------------
;; the wiring is reached from a REAL cascade
;; ---------------------------------------------------------------------------

(defn- family-row? [ev]
  (boolean (some-> (:operation ev) namespace #{"rf.resource" "rf.mutation"})))

(defn- drive-real-cascade!
  "Two REAL `ensure`s under one owner — the `:sensitive?` resource with the
  secret in its params, the plain one beside it — then a `release-owner`.
  Returns every trace row the cascade put on the bus. Managed HTTP is a no-op."
  []
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (let [rows (atom [])
        k    ::real-cascade-recorder]
    (rf.trace.tooling/register-listener! k (fn [ev] (swap! rows conj ev)))
    (try
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :secret/article
                          :params   {:auth-token secret}
                          :owner    real-owner}]
                        {:frame :test/rt})
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :plain/article
                          :params   {:slug plain-slug}
                          :owner    real-owner}]
                        {:frame :test/rt})
      (rf/dispatch-sync [:rf.resource/release-owner {:owner real-owner}]
                        {:frame :test/rt})
      (finally (rf.trace.tooling/unregister-listener! k)))
    @rows))

(deftest real-cascade-lands-family-rows-in-the-settled-epoch-record
  (testing "every family row a REAL ensure / release-owner cascade emits reaches
            a settled record's :trace-events, and project-egress over those
            records redacts the :sensitive? owner while the plain owner's key
            rides verbatim"
    (let [bus-family (filterv family-row? (drive-real-cascade!))
          records    (rf/epoch-history :test/rt)
          proj-rows  (filterv family-row? (mapcat (comp :trace-events rf/project-egress) records))]
      (is (contains-secret? bus-family)
          "FIXTURE — the cascade emitted family rows carrying the raw secret")
      (is (= (frequencies (map :operation bus-family))
             (frequencies (map :operation (filter family-row? (mapcat :trace-events records))))))
      (is (not (contains-secret? proj-rows)))
      (is (some #(= [:rf.scope/global :plain/article {:slug plain-slug}]
                    (:resource/key (:tags %)))
                proj-rows)))))

;; ===========================================================================
;; fx carriers — the projector reached by SLOT (`:rf.fx/args`, `:rf.event/fx`)
;; ===========================================================================

(defn- project-carrier-egress
  "Project at the trusted-local `:rf.egress/include-fx-args? true` posture, the
  one posture in which the carriers' bytes reach a wire at all. At the off-box
  default both carriers fail closed whole, leaving nothing for the family's
  owner discrimination to act on; that default is pinned by
  `forwarder-fx-args-tag-carriers-fail-closed` in
  `re-frame.epoch-mcp-egress-conformance-test`."
  ([record] (project-carrier-egress record nil))
  ([record opts]
   (rf/project-egress record (merge {:rf.egress/include-fx-args? true} opts))))

(defn- carrier-leak-paths
  "`secret-leak-paths` over a carrier-posture projection, minus the structured
  `:effects`, whose args the same opt-in deliberately lifts."
  [projected]
  (secret-leak-paths (dissoc projected :effects)))

(defn- carrier-walk
  "Every value inside `record`'s `:rf.fx/args` / `:rf.event/fx` carriers that
  `pick` returns non-nil for, walking maps (entries) and collections."
  [record pick]
  (let [found (atom [])
        walk  (fn walk [v]
                (when-some [x (pick v)] (swap! found conj x))
                (cond
                  (map? v)  (run! walk (vals v))
                  (coll? v) (run! walk v)))]
    (doseq [tags (map :tags (:trace-events record))
            slot [:rf.fx/args :rf.event/fx]
            :when (contains? tags slot)]
      (walk (get tags slot)))
    @found))

(defn- carrier-scopes
  "Every value under a `:scope` key inside the carriers, found by walking so
  no assertion encodes the cascade's fx order."
  [record]
  (carrier-walk record #(when (and (map? %) (contains? % :scope)) (:scope %))))

(defn- carrier-replies
  "Every resource-family continuation reply (read or mutation) inside the
  carriers, found by the reply's own `:rf.reply/work-kind` marker."
  [record]
  (carrier-walk record #(when (and (map? %) (#{:resource :mutation} (:rf.reply/work-kind %))) %)))

(defn- tokenized-scope?
  "A resolved `[tier {identity}]` scope that egressed correctly: tier verbatim,
  identity tokenized."
  [s]
  (and (vector? s) (= 2 (count s))
       (= :rf.scope/session (first s))
       (redacted-component? (second s))))

(defn- classify-session-identity!
  "Write the session identity into the frame's app-db, classified `:sensitive`
  there, so the app-db axis can never be what a scan catches."
  []
  (rf.frame/swap-runtime-db! :test/rt
    (fn [rt] (rf.elision/apply-classification-effects
               rt {:sensitive [[:auth :user :username]]})))
  (rf.frame/swap-frame-db! :test/rt assoc-in [:auth :user :username] secret))

(deftest real-session-scoped-ensure-leaks-no-identity-into-fx-carriers
  (testing "a REAL ensure of a :sensitive? {:from-db} resource plants the
            resolved scope in its transport continuation payloads on both fx
            carriers; projected, every one keeps its tier and tokenizes its
            identity, and nothing raw survives"
    (rf/configure! {:epoch-history {:trace-events-keep 50}})
    (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
    (classify-session-identity!)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :derived/profile :params {:slug "me"} :owner real-owner}]
                      {:frame :test/rt})
    (let [raw        (last (rf/epoch-history :test/rt))
          raw-scopes (carrier-scopes raw)
          projected  (project-carrier-egress raw)]
      (is (and (seq raw-scopes) (every? #{session-scope} raw-scopes))
          "FIXTURE — the carriers carry the raw resolved scope")
      (is (= (count raw-scopes) (count (filter tokenized-scope? (carrier-scopes projected)))))
      (is (= [] (carrier-leak-paths projected))))))

(defn- managed-args
  "The `:rf.http/managed` args `transport.http/build-managed-args` builds for
  one ensure."
  [scoped-key scope]
  (let [work-id [:rf.work/resource scoped-key 1]
        payload {:work/id      work-id
                 :resource/key scoped-key
                 :scope        scope
                 :generation   1
                 :rf.frame/id  :test/rt}]
    {:request    {:method :get :url "/z"}
     :request-id [:rf.req :test/rt work-id]
     :on-success [:rf.resource.internal/succeeded payload]
     :on-failure [:rf.resource.internal/failed payload]}))

(deftest fx-carrier-keeps-plain-request-map-and-global-scope-verbatim
  (testing "a PLAIN owner's managed-HTTP args — request map, scoped keys,
            :rf.scope/global scope — ride both carriers byte-identical and stamp
            nothing: the carrier projector is not a wholesale map tokenizer"
    (let [args   (managed-args (sk :rf.scope/global :plain/article {:slug plain-slug})
                               :rf.scope/global)
          record (record-with
                   [(handled-row :rf.http/managed args)
                    (event :rf.fx/do-fx
                           {:rf.frame/id :test/rt :frame :test/rt
                            :rf.event/fx [[:rf.resource/commit-generation {:value 1}]
                                          [:rf.http/managed args]]})])]
      (is (= (:trace-events record) (:trace-events (project-carrier-egress record)))))))

;; ---------------------------------------------------------------------------
;; the READ continuation reply — `:value` is the decoded response body
;; ---------------------------------------------------------------------------
;;
;; A read with a call-site `:reply-to` appends its reply map to the target event,
;; dispatched through `[:dispatch <ev>]`, so it rides both carriers. Its
;; `:value` / `:params` are the OWNER's data: they tokenize iff the owner makes a
;; coarse claim, and only inside a map carrying the canonical reply marker,
;; because `:value` / `:params` are also words the fx family uses for its own
;; data. A `:serialize` owner's projection-relative declarations project the
;; reply per path instead.

(def ^:private reply-params {:slug secret})
(def ^:private reply-value {:email (str secret "@example.com")})
(def ^:private read-reply-target [:app/read-loaded])

(defn- record-carrying-reply
  "The record whose carriers carry a read reply with the given `:cache-hit?`."
  [records cache-hit?]
  (first (filter (fn [r] (some #(= cache-hit? (:cache-hit? %)) (carrier-replies r)))
                 records)))

(defn- read-reply
  "The continuation reply `events/read-continuation-reply` builds."
  [scoped-key scope value]
  (let [[_ resource-id params] scoped-key]
    {:status               :ok
     :value                value
     :rf.reply/work-id     [:rf.work/resource scoped-key 1]
     :rf.reply/work-kind   :resource
     :rf.reply/work-status :completed
     :rf.frame/id          :test/rt
     :completed-at         0
     :correlation          {:scope scope :generation 1
                            :rf.reply/resource-key scoped-key}
     :resource             resource-id
     :params               params
     :scope                scope
     :resource/key         scoped-key
     :cache-hit?           false}))

(defn- reply-carrier-record
  "A record carrying one reply on both carriers. The effect vector also carries
  `[:rf.resource/commit-generation {:value 1}]`, a FOREIGN `:value` with no
  reply marker, which must ride."
  [reply]
  (let [ev (conj read-reply-target reply)]
    (record-with
      [(handled-row :dispatch ev)
       (event :rf.fx/do-fx
              {:rf.frame/id :test/rt :frame :test/rt
               :rf.event/fx [[:rf.resource/commit-generation {:value 1}]
                             [:dispatch ev]]})])))

(defn- drive-reply-to-read!
  "Drive a REAL ensure of `resource-id` carrying a `:reply-to`, replay `value`
  through the runtime's own internal reply event, then drive a SECOND ensure
  that finds the entry fresh — so the records carry both continuation paths:
  the async settle (`:cache-hit? false`) and the fresh-skip dispatch
  (`:cache-hit? true`)."
  [resource-id params value]
  (rf/configure! {:epoch-history {:trace-events-keep 80}})
  (let [captured (atom nil)]
    (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
    (rf/reg-event :app/read-loaded (fn [_ _ev] {}))
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource resource-id :params params
                        :owner    real-owner  :reply-to read-reply-target}]
                      {:frame :test/rt})
    (rf/dispatch-sync (conj (:on-success @captured) {:status :ok :value value})
                      {:frame :test/rt})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource resource-id :params params
                        :owner    [:app :reader 2] :reply-to read-reply-target}]
                      {:frame :test/rt})
    (rf/epoch-history :test/rt)))

(deftest fx-carrier-reply-tokens-carry-no-enumerable-content
  (testing "a redacting owner's reply :value and :params tokenize on the
            carrier, and the token is content-free: two distinct reads'
            replies are indistinguishable off-box"
    (let [proj (fn [params value]
                 (-> (reply-carrier-record
                       (read-reply (sk session-scope :derived/profile params) session-scope value))
                     project-carrier-egress
                     carrier-replies
                     first
                     (select-keys [:value :params])))
          r1   (proj reply-params reply-value)]
      (is (every? redacted-component? (vals r1)))
      (is (= r1 (proj {:slug (str secret "-2")} {:email "other@example.com"}))))))

(deftest trusted-local-include-sensitive-keeps-raw-fx-carrier-reply
  (testing "with :rf.egress/include-sensitive? (beside the fx-args opt-in) both
            carriers keep the raw reply — a local tool debugging a workflow
            reads its `:reply-to` body"
    (let [record (reply-carrier-record
                   (read-reply (sk session-scope :derived/profile reply-params)
                               session-scope reply-value))]
      (is (= (:trace-events record)
             (:trace-events (project-carrier-egress record {:rf.egress/include-sensitive? true})))))))

;; Each carrier arm fires only on PROOF the resource runtime planted the value —
;; its reserved keyword namespace, its `[:rf.work/resource …]` work-id head, the
;; canonical reply marker, or the resource registry — never on a local cue that
;; ordinary fx data hits by coincidence.

(deftest fx-carrier-leaves-foreign-lookalike-vectors-verbatim
  (testing "scoped-key SHAPE is not proof inside a foreign carrier: an app
            3-vector naming no registered resource rides and stamps nothing;
            a registered owner's key projects from any slot (the REGISTRY
            proof), and an unregistered key under a `resource`-namespaced key
            fails closed (the NAMED proof)"
    (let [custom-args {:rows     [[:opaque :app/not-a-resource {:account-id 42}]]
                       :dispatch [:app/save :user {:name "alice"}]}
          [custom audit cancel]
          (->> (record-with
                 [(handled-row :app/custom custom-args)
                  (handled-row :app/audit
                               {:app/anything [(sk :rf.scope/global :secret/article {:auth-token secret})]})
                  (handled-row :rf.resource/cancel-timers
                               {:frame-id      :test/rt
                                :resource/keys [[:rf.scope/global :cleared/article {:auth-token secret}]]})])
               project-carrier-egress
               :trace-events
               (map :tags))]
      (is (= custom-args (:rf.fx/args custom)))
      (is (not (:sensitive? custom)))
      (let [[pscope _ pparams] (first (:app/anything (:rf.fx/args audit)))]
        (is (every? redacted-component? [pscope pparams]) "the registry proof"))
      (let [[pscope _ pparams] (first (:resource/keys (:rf.fx/args cancel)))]
        (is (every? redacted-component? [pscope pparams]) "the named proof")))))

(deftest fx-carrier-leaves-app-owned-scope-maps-verbatim
  (testing "an app's own :scope map rides and stamps nothing; a :scope in a
            runtime-built payload fails closed even with no :resource/key beside
            it — the mutation execute payload, proved by its work-id"
    (let [tags-of  (fn [args]
                     (projected-tags (record-with [(handled-row :rf.http/managed args)])
                                     {:rf.egress/include-fx-args? true}))
          app-args {:request {:method :post :scope {:tenant "alice"} :body {:x 1}}}
          app-tags (tags-of app-args)]
      (is (= app-args (:rf.fx/args app-tags)))
      (is (not (:sensitive? app-tags)))
      (is (tokenized-scope?
            (get-in (tags-of {:on-success [:rf.mutation.internal/succeeded
                                           {:instance-id :m/save-1
                                            :mutation-id :m/save
                                            :work/id     [:rf.work/resource [:rf.mutation :m/save-1] 3]
                                            :scope       session-scope
                                            :generation  3}]})
                    [:rf.fx/args :on-success 1 :scope]))))))

(deftest fx-carrier-reply-payload-needs-the-canonical-reply-marker
  (testing "a sibling :resource/key says whose data a map would be, not that it
            is a reply: an app map carrying a genuine sensitive key beside its
            own :value / :params keeps them, while the key tokenizes"
    (let [args (:rf.fx/args
                 (projected-tags
                   (record-with [(handled-row :app/custom
                                              {:resource/key (sk session-scope :derived/profile reply-params)
                                               :value        {:public true}
                                               :params       {:format :csv}})])
                   {:rf.egress/include-fx-args? true}))]
      (is (= [{:public true} {:format :csv}] [(:value args) (:params args)]))
      (is (redacted-component? (first (:resource/key args)))))))

;; ---------------------------------------------------------------------------
;; the reply of an owner that makes NO coarse claim and declares paths instead
;; ---------------------------------------------------------------------------

(def ^:private declared-reply-params
  "Plain: `:declared/profile` declares nothing under `:params`."
  {:slug plain-slug})

(def ^:private declared-reply-value
  "Three fields, three outcomes: `:email` redacts, `:avatar` elides,
  `:display-name` rides."
  {:email        (str secret "@example.com")
   :avatar       "0123456789abcdef"
   :display-name "Ada"})

(deftest real-declared-reply-to-read-leaks-no-declared-slot-into-fx-carriers
  (testing "a REAL reply-to read of an owner whose only claim is a
            projection-relative declaration: on both continuation paths and
            both carriers the declared body slots move, while the undeclared
            sibling, the params and the `:serialize` key ride"
    (let [records (drive-reply-to-read! :declared/profile declared-reply-params declared-reply-value)]
      (doseq [cache-hit? [false true]]
        (testing (if cache-hit? "fresh-skip cache hit" "async settle")
          (let [raw       (record-carrying-reply records cache-hit?)
                projected (project-carrier-egress raw)
                replies   (carrier-replies projected)]
            (is (seq (secret-leak-paths raw)) "FIXTURE — the unprojected record leaks")
            (is (= [] (carrier-leak-paths projected)))
            (is (seq replies))
            (doseq [r replies]
              (is (= :rf/redacted (:email (:value r))))
              (is (rf.elision/marker? (:avatar (:value r))))
              (is (= ["Ada"
                      declared-reply-params
                      [:rf.scope/global :declared/profile declared-reply-params]]
                     [(:display-name (:value r)) (:params r) (:resource/key r)])))))))))

(deftest fx-carrier-declared-reply-params-redact-through-the-same-declaration
  (testing "a `:params`-rooted declaration redacts the reply's :params slot per
            path and leaves the body alone"
    (let [params {:account "acct-9911" :slug plain-slug}
          r      (-> (reply-carrier-record
                       (read-reply (sk :rf.scope/global :declared/params-owner params)
                                   :rf.scope/global {:ok true}))
                     project-carrier-egress
                     carrier-replies
                     first)]
      (is (= [{:account :rf/redacted :slug plain-slug} {:ok true}]
             [(:params r) (:value r)])))))

(deftest fx-carrier-declared-arm-leaves-the-fx-familys-own-value-verbatim
  (testing "a map with :value but no reply marker is not a reply, whatever owner
            its neighbouring key names, so no declaration reaches it"
    (let [unmarked {:resource/key (sk :rf.scope/global :declared/profile declared-reply-params)
                    :value        declared-reply-value}
          tags     (projected-tags (record-with [(handled-row :app/custom unmarked)])
                                   {:rf.egress/include-fx-args? true})]
      (is (= unmarked (:rf.fx/args tags)))
      (is (not (:sensitive? tags))))))

(deftest fx-carrier-index-free-fork-does-not-reach-an-undeclared-nested-slot
  (testing "a feed's `[:data :email]` names a field of each ITEM: it redacts one
            index down, and an `:email` one NAMED slot deeper rides"
    (let [nested (str secret "-nested@example.com")
          item   (-> (reply-carrier-record
                       (read-reply (sk :rf.scope/global :declared/feed {:filter :recent})
                                   :rf.scope/global
                                   [{:email        (str secret "-top@example.com")
                                     :display-name "Ada"
                                     :meta         {:email nested}}]))
                     project-carrier-egress
                     carrier-replies
                     first
                     :value
                     first)]
      (is (= {:email :rf/redacted :display-name "Ada" :meta {:email nested}} item)))))

;; ---------------------------------------------------------------------------
;; NON-MAP canonical params on the carriers
;; ---------------------------------------------------------------------------

(deftest fx-carrier-named-slot-fails-closed-for-an-unregistered-non-map-params-owner
  (testing "a key under the family's own `:resource/key` whose owner was cleared
            or hot-reloaded away fails closed on the carrier, whatever its
            params shape; its resource-id still rides"
    (let [gone      [:rf.scope/global :gone/vector-params [vector-secret]]
          projected (project-carrier-egress
                      (reply-carrier-record
                        (assoc (read-reply gone :rf.scope/global {:ok true})
                               :resource :gone/vector-params)))]
      (is (= [:gone/vector-params :gone/vector-params]
             (mapv #(second (:resource/key %)) (carrier-replies projected))))
      (is (= [] (secret-leak-paths projected))))))

(deftest real-vector-params-reply-to-read-leaks-nothing-into-fx-carriers
  (testing "a REAL reply-to read of a :sensitive? owner with NON-MAP params
            carries the raw params at zero paths of its projected trace
            carriers, on both continuation paths"
    (let [records (drive-reply-to-read! :secret/vector-params vector-params
                                        {:email (str vector-secret "@example.com")})
          tags-of (fn [r] (mapv :tags (:trace-events r)))]
      (doseq [cache-hit? [false true]]
        (testing (if cache-hit? "fresh-skip cache hit" "async settle")
          (let [raw (record-carrying-reply records cache-hit?)]
            (is (seq (secret-leak-paths (tags-of raw))) "FIXTURE — the raw carriers leak")
            (is (= [] (secret-leak-paths (tags-of (project-carrier-egress raw)))))))))))

;; ---------------------------------------------------------------------------
;; the FAILURE envelope under `:error`
;; ---------------------------------------------------------------------------
;;
;; `:error` is tokenized UNCONDITIONALLY inside a family reply marker — no owner
;; read — because the family's own `:rf.resource/failed` row tokenizes the same
;; envelope regardless of owner, and the two carriers of one envelope must
;; agree. The marker spans reads AND mutations: a mutation redacts its own
;; `:value` / `:params` at the source, but no declaration can reach `:error`.

(def ^:private failure-envelope
  "A 422 envelope echoing the submitted secret in `:body` and `:detail`."
  {:kind      :rf.http/http-4xx
   :status    422
   :body      {:email (str secret "@example.com")}
   :body-text (str "email " secret " is already registered")
   :detail    {:errors [{:field :email :value secret}]}})

(def ^:private abort-envelope
  "Lowered to `:status :cancelled`, still under `:error` — `:status` is not the
  gate."
  {:kind :rf.http/aborted :reason :user-abort :detail {:draft {:email secret}}})

(defn- both-carriers-of
  "A record carrying BOTH copies of one envelope: the family's own
  `:rf.resource/failed` row and the reply's two fx carriers."
  [scoped-key reply]
  (let [ev (conj read-reply-target reply)]
    (record-with
      [(event :rf.resource/failed
              {:rf.frame/id :test/rt :resource/key scoped-key
               :work/id [:rf.work/resource scoped-key 1] :generation 1
               :error (:error reply)})
       (handled-row :dispatch ev)
       (event :rf.fx/do-fx
              {:rf.frame/id :test/rt :frame :test/rt
               :rf.event/fx [[:rf.resource/commit-generation {:value 1}]
                             [:dispatch ev]]})])))

(defn- plain-failure-record
  "The failure reply of a PLAIN owner — the case an owner-conditional `:error`
  arm would split — on both carriers and its family row."
  []
  (let [k (sk :rf.scope/global :plain/article {:slug plain-slug})]
    (both-carriers-of k (-> (read-reply k :rf.scope/global nil)
                            (dissoc :value)
                            (assoc :status :error
                                   :error failure-envelope
                                   :rf.reply/work-status :failed)))))

(deftest fx-carrier-error-envelope-agrees-with-the-family-row
  (testing "the ROW copy and the CARRIER copies of one envelope project to the
            SAME token, over a PLAIN owner, and every row carrying it is stamped"
    (let [projected (project-carrier-egress (plain-failure-record))
          row-error (:error (:tags (first (:trace-events projected))))]
      (is (redacted-component? row-error))
      (is (= [row-error row-error] (mapv :error (carrier-replies projected))))
      (is (every? #(true? (:sensitive? (:tags %))) (:trace-events projected))))))

(deftest fx-carrier-error-projection-is-idempotent
  (testing "an already-projected record re-projects to itself; a token is not
            re-digested"
    (let [once (project-carrier-egress (plain-failure-record))]
      (is (= once (project-carrier-egress once))))))

(deftest fx-carrier-leaves-a-non-family-error-verbatim
  (testing ":error is the family's only inside its OWN reply marker: an fx map
            with no marker, and an HTTP-family reply (`:rf.reply/work-kind
            :http` — the marker is enumerated, never `some?`), ride
            byte-identical and stamp nothing"
    (let [foreign {:error {:message "boom" :detail {:slug plain-slug}}}
          record  (record-with
                    [(event :rf.fx/do-fx
                            {:rf.frame/id :test/rt :frame :test/rt
                             :rf.event/fx [[:rf.error/report foreign]
                                           [:dispatch [:app/oops foreign]]]})
                     (handled-row :dispatch
                                  [:app/http-done {:status               :error
                                                   :rf.reply/work-kind   :http
                                                   :rf.reply/work-status :failed
                                                   :error                failure-envelope}])])]
      (is (= (:trace-events record) (:trace-events (project-carrier-egress record)))))))

;; ===========================================================================
;; REAL continuation settles, swept whole for the canary
;; ===========================================================================
;;
;; One real drive per reply arm the assembled tests above cannot reach from a
;; producer: a coarse owner's read success under a session scope (its `:value`,
;; `:params`, `:scope`, `:correlation`, key and work-id all carry identity), and
;; a mutation ABORT (its `:error` under `:status :cancelled`, and its
;; correlation scope, which no resource-namespaced key proves). Each sweep is the
;; whole projected record, so whatever slot a reply gains is seen.

(defn- in-an-isolated-runtime
  "Run `body!` under a fresh runtime, assertions included: `project-egress`
  classifies through the LIVE frame, and a projection taken after the
  fixture's teardown fails closed and redacts everything."
  [body!]
  (reset-runtime-fixture body!))

(defn- drive-mutation-abort!
  "Drive a REAL `[:rf.mutation/execute … :reply-to …]` under a concrete scope
  and settle it with an abort. `:m/save` declares `[:params :slug]` sensitive —
  the only spelling by which a mutation claims its params — so its `:params`
  carry the canary and are cleaned at the source."
  []
  (rf/configure! {:epoch-history {:trace-events-keep 80}})
  (let [captured (atom nil)]
    (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
    (rf/reg-event :app/save-replied (fn [_ _ev] {}))
    (rf/reg-mutation :m/save
      {:sensitive     [[:params :slug]]
       :params-schema [:map [:slug :string]]}
      (fn [_ _] {:request {:method :put :url "/a"}}))
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params reply-params
                        :scope    session-scope
                        :instance :mf1 :reply-to [:app/save-replied]}]
                      {:frame :test/rt})
    (rf/dispatch-sync (conj (:on-failure @captured) {:status :error :error abort-envelope})
                      {:frame :test/rt})
    (first (filter #(seq (carrier-replies %)) (rf/epoch-history :test/rt)))))

(def ^:private continuation-settle-drives
  {:rf.resource.internal/succeeded
   {:drive  #(do (classify-session-identity!)
                 (record-carrying-reply (drive-reply-to-read! :derived/profile reply-params reply-value)
                                        false))
    :expect {:status :ok :rf.reply/work-kind :resource}}
   :rf.mutation.internal/failed
   {:drive  drive-mutation-abort!
    :expect {:status :cancelled :rf.reply/work-kind :mutation}}})

(deftest continuation-settle-paths-survive-the-canary
  (doseq [[settle-id {:keys [drive expect]}] continuation-settle-drives]
    (testing settle-id
      (in-an-isolated-runtime
        (fn []
          (let [raw     (drive)
                replies (carrier-replies raw)]
            (is (= settle-id (:event-id raw)) "the drive settled the branch it is filed under")
            (is (and (seq replies)
                     (every? #(= expect (select-keys % (keys expect))) replies))
                "FIXTURE — the reply rides a carrier, on the named branch")
            (is (seq (secret-leak-paths raw)) "FIXTURE — the unprojected record leaks")
            (is (= [] (carrier-leak-paths (project-carrier-egress raw))))))))))
