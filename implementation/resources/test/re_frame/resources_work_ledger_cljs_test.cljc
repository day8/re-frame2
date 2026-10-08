(ns re-frame.resources-work-ledger-cljs-test
  "The resource work ledger (Spec 016 §Frame work ledger): one serializable
  record per attempt keyed by work id, host handles in a side table, terminal
  settles, opportunistic abort, mandatory stale suppression, a bounded
  terminal tail per key, and the key -> work inverse index."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))
(def ^:private aborts (atom []))

(defn- capturing-transport-fixture [f]
  (reset! last-managed-args nil)
  (reset! aborts [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx request-id] (swap! aborts conj request-id) nil))
  (rf/reg-resource-scope :t/caller-scope
    {:inputs {:scope [:db [:t/scope]]}}
    (fn [{:keys [scope]} _ctx] scope))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db
  ([] (runtime-db :rf/default))
  ([frame-id] (:rf.db/runtime (rf/frame-state-value frame-id))))

(defn- entry
  ([scoped-key] (entry :rf/default scoped-key))
  ([frame-id scoped-key]
   (get-in (runtime-db frame-id) (rf.resources.state/entry-path scoped-key))))

(defn- record
  ([work-id] (record :rf/default work-id))
  ([frame-id work-id]
   (rf.resources.work-ledger/get-record (runtime-db frame-id) work-id)))

(defn- bucket
  "This key's bucket in the ledger's key -> work inverse index, or nil."
  [scoped-key]
  (get-in (runtime-db)
          [rf.resources.work-ledger/work-ledger-by-key-key
           (rf.resources.state/key-id scoped-key)]))

(defn- rows-for
  "Every ledger row linked to `scoped-key`, by a full scan rather than the index."
  [scoped-key]
  (into {} (filter (fn [[_wid-id r]] (= scoped-key (:resource/key r))))
        (get-in (runtime-db) [:rf.runtime/work-ledger])))

(defn- removed-traces
  "Run `f`; return the :rf.resource/removed trace events it emitted."
  [f]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::removed (fn [ev] (when (= :rf.resource/removed (:operation ev))
                                                       (swap! seen conj ev))))
    (try (f) (finally (rf/unregister-listener! :trace ::removed)))
    @seen))

(defn- req
  "The frame-qualified transport request-id an abort carries for `work-id`."
  [work-id]
  (rf.resources.work-ledger/managed-request-id :rf/default work-id))

(defn- article-spec
  ([] (article-spec {}))
  ([overrides]
   (merge {:scope         :rf.scope/global
           :params-schema [:map [:slug :string]]
           :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
          overrides)))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

(defn- gkey [rid] (rf.resources.state/scoped-resource-key :rf.scope/global rid {:slug "w"}))

(defn- ensure!
  ([rid owner] (ensure! rid owner nil))
  ([rid owner opts]
   (rf/dispatch-sync [:rf.resource/ensure {:resource rid :scope :rf.scope/global
                                           :params {:slug "w"} :owner owner}]
                     opts)))

(defn- fail! [k wid error opts]
  (rf/dispatch-sync [:rf.resource.internal/failed
                     {:resource/key k :work/id wid :generation 1 :error error}]
                    opts))

;; ---- records ----------------------------------------------------------------

(deftest ensure-writes-work-record
  (rf/reg-resource :wl/article (article-spec) article-spec-request)
  (let [k (gkey :wl/article)]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :wl/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:route :r 1]
                        :cause [:route-entry :route/article 1]}])
    (let [wid (:current-work (entry k))
          r   (record wid)]
      (is (= [:rf.work/resource k 1] wid))
      (is (= {:work/id wid :work/kind :resource :work/frame :rf/default :resource/key k
              :generation 1 :status :running :owners #{[:route :r 1]}
              :causes [[:route-entry :route/article 1]]}
             (select-keys r [:work/id :work/kind :work/frame :resource/key :generation
                             :status :owners :causes])))
      (is (rf.resources.work-ledger/serializable-record? r) "no host handles in the record"))))

(deftest work-record-started-at-deadline-at-from-token-time-ms
  ;; :started-at is the triggering token's :time-ms and :deadline-at adds the
  ;; :timeout-ms policy; no ambient clock read.
  (rf/reg-resource :wlt/article (article-spec {:timeout-ms 5000}) article-spec-request)
  (rf/reg-resource :wlnt/article (article-spec) article-spec-request)
  (ensure! :wlt/article [:route :r 1] {:rf.cofx {:rf/time-ms 1781078400123}})
  (ensure! :wlnt/article [:route :r 1] {:rf.cofx {:rf/time-ms 1781079000000}})
  (is (= [1781078400123 (+ 1781078400123 5000)]
         ((juxt :started-at :deadline-at) (record (:current-work (entry (gkey :wlt/article)))))))
  (is (nil? (:deadline-at (record (:current-work (entry (gkey :wlnt/article)))))) "no policy, no deadline"))

;; ---- terminal settles -------------------------------------------------------

(deftest succeeded-settles-the-row-completed-and-clears-its-handle
  (rf/reg-resource :sc/article (article-spec) article-spec-request)
  (let [k (gkey :sc/article)]
    (ensure! :sc/article [:app :sc 1])
    (let [wid (:current-work (entry k))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key k :work/id wid :generation 1 :data {:title "W"}}])
      (is (= :completed (:status (record wid))))
      (is (nil? (rf.resources.work-ledger/get-handle :rf/default wid))))))

(deftest failed-settles-record-terminal
  (rf/reg-resource :fa/article (article-spec) article-spec-request)
  (let [k (gkey :fa/article)]
    (ensure! :fa/article [:app :fa 1])
    (let [wid (:current-work (entry k))]
      (fail! k wid {:kind :rf.http/http-5xx :status 503} {:rf.cofx {:rf/time-ms 1781649764112}})
      (is (= {:status :failed
              :outcome {:error {:kind :rf.http/http-5xx :status 503} :completed-at 1781649764112}}
             (select-keys (record wid) [:status :outcome])))
      (is (nil? (rf.resources.work-ledger/get-handle :rf/default wid))))))

(deftest aborted-settles-record-cancelled
  (rf/reg-resource :ab/article (article-spec) article-spec-request)
  (let [k (gkey :ab/article)]
    (ensure! :ab/article [:app :ab 1])
    (let [wid (:current-work (entry k))]
      (fail! k wid {:kind :rf.http/aborted :reason :aborted} {:rf.cofx {:rf/time-ms 1781649764112}})
      (is (= {:status :cancelled :outcome {:reason :aborted :completed-at 1781649764112}}
             (select-keys (record wid) [:status :outcome])))
      (is (nil? (rf.resources.work-ledger/get-handle :rf/default wid))))))

(deftest stale-aborted-reply-suppressed-not-cancelled
  ;; Stale validation wins over the natural cancellation status.
  (rf/reg-resource :sa/article (article-spec) article-spec-request)
  (let [k (gkey :sa/article)]
    (ensure! :sa/article [:app :sa 1])
    (let [wid1 (:current-work (entry k))]
      (rf/dispatch-sync [:rf.resource/refetch {:resource :sa/article :scope :rf.scope/global
                                               :params {:slug "w"}}])
      (fail! k wid1 {:kind :rf.http/aborted :reason :aborted} nil)
      (is (= :suppressed (:status (record wid1))))
      (is (= 2 (:generation (entry k)))))))

(deftest cross-frame-aborted-reply-rejected
  ;; An abort reply stamped with another frame's id never settles the
  ;; receiving frame's entry, even at the same work-id and generation.
  (rf/reg-resource :cfa/article (article-spec) article-spec-request)
  (let [fa :cfa/frame-a
        fb :cfa/frame-b
        k  (gkey :cfa/article)]
    (rf/make-frame {:id fa :doc "frame A"})
    (rf/make-frame {:id fb :doc "frame B"})
    (rf/dispatch-sync [:rf.resource/ensure {:resource :cfa/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:app :b 1]}]
                      {:frame fb})
    (let [wid-b  (:current-work (entry fb k))
          before (entry fb k)]
      (rf/dispatch-sync [:rf.resource.internal/failed
                         {:resource/key k :work/id wid-b :generation 1
                          :rf.frame/id fa
                          :error {:kind :rf.http/aborted :reason :aborted}}]
                        {:frame fb})
      (is (= [(:status before) wid-b] ((juxt :status :current-work) (entry fb k))))
      (is (not= :cancelled (:status (record fb wid-b)))))))

;; ---- supersession, dedupe, owners -------------------------------------------

(deftest supersession-aborts-and-marks-the-old-row-suppressed
  (rf/reg-resource :ss/article (article-spec) article-spec-request)
  (let [k (gkey :ss/article)]
    (ensure! :ss/article [:app :ss 1])
    (let [wid1 (:current-work (entry k))]
      (rf/dispatch-sync [:rf.resource/refetch {:resource :ss/article :scope :rf.scope/global
                                               :params {:slug "w"}}])
      (is (contains? (set @aborts) (req wid1)) "a best-effort abort for the old work")
      (is (= [:suppressed :superseded] ((juxt :status (comp :reason :outcome)) (record wid1)))))))

(deftest dedupe-joins-existing-record
  (rf/reg-resource :dd/article (article-spec) article-spec-request)
  (let [k (gkey :dd/article)]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :dd/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:route :r 1]
                                            :cause [:route-entry :r 1]}])
    (rf/dispatch-sync [:rf.resource/ensure {:resource :dd/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:app :x 2]
                                            :cause [:event :open]}])
    (is (= 1 (count (rows-for k))) "no new attempt")
    (is (= {:owners #{[:route :r 1] [:app :x 2]} :causes [[:route-entry :r 1] [:event :open]]}
           (select-keys (record (:current-work (entry k))) [:owners :causes])))))

(deftest new-attempt-inherits-the-entrys-held-owners
  ;; A new attempt starts from the entry's :active-owners, so releasing one
  ;; held owner never aborts work another still needs (Spec 016 §Race).
  (rf/reg-resource :ri/article (article-spec) article-spec-request)
  (let [q       {:resource :ri/article :scope :rf.scope/global :params {:slug "w"}}
        k       (gkey :ri/article)
        a       [:route :r 1]
        b       [:app :x 2]
        settle! #(rf/dispatch-sync (conj (:on-success @last-managed-args)
                                         {:status :ok :value {:title "W"}}))]
    (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner a)])
    (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner b)])
    (settle!)
    (testing "an ownerless refetch inherits both held owners; releasing one does not abort it"
      (rf/dispatch-sync [:rf.resource/refetch (assoc q :cause :focus)])
      (let [wid (:current-work (entry k))]
        (is (= #{a b} (:owners (record wid))))
        (rf/dispatch-sync [:rf.resource/release-owner {:owner a}])
        (is (not (contains? (set @aborts) (req wid))))
        (is (= [#{b} #{b}] [(:active-owners (entry k)) (:owners (record wid))]))
        (settle!)
        (is (= [:loaded :completed] [(:status (entry k)) (:status (record wid))]))))
    (testing "a refetch carrying one owner keeps the other; the last release aborts once"
      (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner a)])
      (rf/dispatch-sync [:rf.resource/refetch (assoc q :owner a :cause [:user :refresh])])
      (let [wid (:current-work (entry k))]
        (is (= #{a b} (:owners (record wid))))
        (rf/dispatch-sync [:rf.resource/release-owner {:owner a}])
        (is (not (contains? (set @aborts) (req wid))))
        (rf/dispatch-sync [:rf.resource/release-owner {:owner b}])
        (is (= 1 (count (filter #{(req wid)} @aborts))))
        (is (= :abort-requested (:status (record wid))))))))

;; ---- removal ----------------------------------------------------------------
;; A clear-scope or remove cancellation is a completion: the key's rows are
;; dropped with the entry, so the :rf.resource/removed trace carries the causal
;; :completed-at and the aborted work id.

(deftest clear-scope-aborts-in-flight-work-and-traces-its-completion
  (rf/reg-resource :cst/article (article-spec {:scope {:from-db :t/caller-scope}}) article-spec-request)
  (let [scope-a {:user "a"}
        ka      (rf.resources.state/scoped-resource-key scope-a :cst/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :cst/article :scope scope-a
                                            :params {:slug "w"} :owner [:app :a 1]}])
    (let [wid  (:current-work (entry ka))
          _    (is (seq (rows-for ka)) "precondition: the key holds a row")
          rows (removed-traces
                 #(rf/dispatch-sync [:rf.resource/clear-scope {:scope scope-a :cause :logout}]
                                    {:rf.cofx {:rf/time-ms 1781649764222}}))]
      (is (contains? (set @aborts) (req wid)))
      (is (= [{:completed-at 1781649764222 :reason :clear-scope :aborted [wid]}]
             (mapv #(select-keys (:tags %) [:completed-at :reason :aborted]) rows)))
      (is (empty? (rows-for ka)) "the key's rows are dropped, not left as a tail"))))

(deftest remove-aborts-in-flight-work-and-traces-its-completion
  (rf/reg-resource :rmt/article (article-spec) article-spec-request)
  (let [k (gkey :rmt/article)]
    (ensure! :rmt/article [:app :rm 1])
    (let [wid  (:current-work (entry k))
          _    (is (seq (rows-for k)) "precondition: the key holds a row")
          rows (removed-traces
                 #(rf/dispatch-sync [:rf.resource/remove {:resource :rmt/article :scope :rf.scope/global
                                                          :params {:slug "w"}}]
                                    {:rf.cofx {:rf/time-ms 1781649764333}}))]
      (is (contains? (set @aborts) (req wid)))
      (is (= [{:completed-at 1781649764333 :reason :remove :aborted [wid]}]
             (mapv #(select-keys (:tags %) [:completed-at :reason :aborted]) rows)))
      (is (empty? (rows-for k))))))

(deftest repeated-failing-settles-keep-the-ledger-bounded
  ;; Pruning happens on every terminal settle, not only a success, so a key
  ;; that never succeeds (a polled failing endpoint) keeps a bounded tail.
  (rf/reg-resource :fl/article (article-spec) article-spec-request)
  (let [q     {:resource :fl/article :scope :rf.scope/global :params {:slug "w"}}
        k     (gkey :fl/article)
        fail! #(rf/dispatch-sync
                 (conj (:on-failure @last-managed-args)
                       {:status :error
                        :error  {:kind :rf.http/server-error :status 500}}))]
    (is (> 12 rf.resources.work-ledger/default-terminal-tail) "precondition: more attempts than the tail")
    (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner [:app :fl 1])])
    (fail!)
    (dotimes [_ 11]
      (rf/dispatch-sync [:rf.resource/refetch (assoc q :cause [:user :retry])])
      (fail!))
    (is (= (repeat rf.resources.work-ledger/default-terminal-tail :failed)
           (map (comp :status val) (rows-for k))))))

(deftest removal-drops-the-keys-inverse-index-bucket
  ;; A row census misses a leaked bucket. The index is built lazily by the
  ;; first prune, so the key is settled first to give it a bucket to lose.
  (rf/reg-resource :bk/article (article-spec) article-spec-request)
  (let [q {:resource :bk/article :scope :rf.scope/global :params {:slug "w"}}
        k (gkey :bk/article)]
    (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner [:app :bk 1])])
    (rf/dispatch-sync (conj (:on-success @last-managed-args) {:status :ok :value {:title "W"}}))
    (is (seq (bucket k)) "precondition: the settled key has a bucket")
    (rf/dispatch-sync [:rf.resource/remove q])
    (is (empty? (rows-for k)))
    (is (nil? (bucket k)))))

(deftest cross-frame-request-id-does-not-collide
  ;; The frame-local work-ids collide across frames, so the process-global
  ;; transport request-id must be frame-qualified.
  (rf/reg-resource :xf/article (article-spec) article-spec-request)
  (let [all-args (atom [])
        fa :xf/frame-a
        fb :xf/frame-b
        k  (gkey :xf/article)]
    (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! all-args conj args) nil))
    (rf/make-frame {:id fa :doc "frame A"})
    (rf/make-frame {:id fb :doc "frame B"})
    (doseq [[f owner] [[fa [:app :a 1]] [fb [:app :b 1]]]]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :xf/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner owner}]
                        {:frame f}))
    (let [wid (:current-work (entry fa k))]
      (is (= [wid wid] [(:current-work (entry fa k)) (:current-work (entry fb k))])
          "precondition: the bare work-ids collide")
      (is (= [(rf.resources.work-ledger/managed-request-id fa wid)
              (rf.resources.work-ledger/managed-request-id fb wid)]
             (mapv :request-id @all-args)))
      (is (apply distinct? (mapv :request-id @all-args)))
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key k :work/id wid :generation 1
                          :rf.frame/id fa :data {:title "A"}}]
                        {:frame fa})
      (is (= {:title "A"} (:data (entry fa k))))
      (is (= :running (:status (record fb wid))))
      (is (not= :loaded (:status (entry fb k)))))))

;; ---- the key -> work inverse index ------------------------------------------
;; The prune visits only the settling key's rows through the index; these pin
;; it against a full-scan reference, and the index against a full rebuild.

(defn- reference-prune-full-scan
  "Index-free oracle: drop the key's terminal rows beyond the `keep-tail`
  newest by :started-at."
  [runtime-db resource-key keep-tail]
  (let [rk-id    (rf.resources.state/key-id resource-key)
        drop-ids (->> (:rf.runtime/work-ledger runtime-db)
                      (filter (fn [[_ r]] (and (= rk-id (rf.resources.state/key-id (:resource/key r)))
                                               (rf.resources.work-ledger/terminal? (:status r)))))
                      (sort-by (fn [[_ r]] (or (:started-at r) 0)) >)
                      (drop keep-tail)
                      (map key))]
    (update runtime-db :rf.runtime/work-ledger (fn [l] (reduce dissoc l drop-ids)))))

(defn- record-for [scoped-key generation status started-at]
  {:work/id      [:rf.work/resource scoped-key generation]
   :work/kind    :resource
   :resource/key scoped-key
   :generation   generation
   :status       status
   :started-at   started-at})

(defn- index-drift [rdb]
  (when (not= (-> rdb rf.resources.work-ledger/recompute-ledger-index :rf.runtime/work-ledger-by-key)
              (:rf.runtime/work-ledger-by-key rdb))
    rdb))

(deftest prune-terminal-for-key-matches-full-scan-reference
  (let [ka  (rf.resources.state/scoped-resource-key :rf.scope/global :wl/a {:id 1})
        kb  (rf.resources.state/scoped-resource-key :rf.scope/global :wl/b {:id 2})
        rdb (reduce (fn [rdb r] (rf.resources.work-ledger/put-record rdb (:work/id r) r))
                    {}
                    [(record-for ka 1 :completed 100)
                     (record-for ka 2 :failed    300)
                     (record-for ka 3 :completed 200)
                     (record-for ka 4 :cancelled 500)
                     (record-for ka 5 :suppressed 400)
                     (record-for ka 6 :running   600)
                     (record-for kb 1 :completed 50)
                     (record-for kb 2 :failed    70)])]
    (doseq [keep-tail [0 1 3 10]]
      (let [new-rdb (rf.resources.work-ledger/prune-terminal-for-key rdb ka keep-tail)]
        (is (= (:rf.runtime/work-ledger (reference-prune-full-scan rdb ka keep-tail))
               (:rf.runtime/work-ledger new-rdb))
            (str "tail " keep-tail))
        (is (nil? (index-drift new-rdb)) (str "tail " keep-tail))))))

(deftest ledger-inverse-index-equals-full-rebuild-under-random-mutation
  ;; A deterministic LCG, so the sequence is identical on every host.
  (let [seed    (atom 88172645)
        nextint (fn [n]
                  (let [x (-> (* @seed 1103515245) (+ 12345) (bit-and 0x7fffffff))]
                    (reset! seed x)
                    (mod x n)))
        keys'   (mapv #(rf.resources.state/scoped-resource-key :rf.scope/global :wl/r {:id %})
                      (range 5))
        drift   (loop [step 0
                       rdb  (rf.resources.work-ledger/recompute-ledger-index {:rf.runtime/work-ledger {}})
                       gen  0
                       drift []]
                  (if (= step 500)
                    drift
                    (let [op   (nextint 2)
                          k    (nth keys' (nextint (count keys')))
                          rdb' (case op
                                 0 (let [g (inc gen)
                                         r (record-for k g
                                                       (nth [:running :completed :failed :cancelled]
                                                            (nextint 4))
                                                       (nextint 1000))]
                                     (rf.resources.work-ledger/put-record rdb [:rf.work/resource k g] r))
                                 1 (rf.resources.work-ledger/prune-terminal-for-key rdb k (nextint 3)))]
                      (recur (inc step) rdb' (if (zero? op) (inc gen) gen)
                             (cond-> drift (index-drift rdb') (conj [step op]))))))]
    (is (= [] drift))))
