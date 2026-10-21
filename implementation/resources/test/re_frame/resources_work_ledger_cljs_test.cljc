(ns re-frame.resources-work-ledger-cljs-test
  "Resource attempts on their entries (Spec 016 §In-flight attempts): the live
  attempt under the entry's `:work`, host handles in a side table, terminal
  settles into a bounded `:recent-work` tail, opportunistic abort, mandatory
  stale suppression, and a reply finding its entry through the attempt it
  holds."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as rf.frame]
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

(defn- attempt
  "Attempt `work-id` in the frame, live or as its terminal summary."
  ([work-id] (attempt :rf/default work-id))
  ([frame-id work-id]
   (rf.resources.work-ledger/attempt-record (runtime-db frame-id) work-id)))

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
                     {:resource/key k :work/id wid :generation (nth wid 2) :error error}]
                    opts))

;; ---- the live attempt ---------------------------------------------------------

(deftest ensure-writes-work-record
  (rf/reg-resource :wl/article (article-spec) article-spec-request)
  (let [k (gkey :wl/article)]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :wl/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:route :r 1]
                        :cause [:route-entry :route/article 1]}])
    (let [wid (:current-work (entry k))
          r   (:work (entry k))]
      (is (= [:rf.work/resource :wl/article 1] wid)
          "the work id names the resource id and generation, never the scoped key")
      (is (= [:rf.req :rf/default [:rf.work/resource :wl/article 1]]
             (:request-id @last-managed-args))
          "the transport request id keeps its frame")
      (is (= {:work/id wid :work/kind :resource
              :generation 1 :status :running :owners #{[:route :r 1]}
              :causes [[:route-entry :route/article 1]]}
             (select-keys r [:work/id :work/kind :generation :status :owners :causes])))
      (is (not-any? #(contains? r %) [:resource/key :work/frame])
          "the attempt names neither key nor frame: its entry and frame do")
      (is (rf.resources.work-ledger/serializable-record? r) "no host handles in the attempt"))))

(deftest work-record-started-at-deadline-at-from-token-time-ms
  ;; :started-at is the triggering token's :time-ms and :deadline-at adds the
  ;; :timeout-ms policy; no ambient clock read.
  (rf/reg-resource :wlt/article (article-spec {:timeout-ms 5000}) article-spec-request)
  (rf/reg-resource :wlnt/article (article-spec) article-spec-request)
  (ensure! :wlt/article [:route :r 1] {:rf.cofx {:rf/time-ms 1781078400123}})
  (ensure! :wlnt/article [:route :r 1] {:rf.cofx {:rf/time-ms 1781079000000}})
  (is (= [1781078400123 (+ 1781078400123 5000)]
         ((juxt :started-at :deadline-at) (:work (entry (gkey :wlt/article))))))
  (is (nil? (:deadline-at (:work (entry (gkey :wlnt/article))))) "no policy, no deadline"))

;; ---- terminal settles -------------------------------------------------------

(deftest each-settle-closes-its-row-and-clears-its-handle
  (doseq [[rid settle! expected]
          [[:sc/article #(rf/dispatch-sync [:rf.resource.internal/succeeded
                                             {:resource/key %1 :work/id %2 :generation 1 :data {:title "W"}}])
            {:status :completed}]
           [:fa/article #(fail! %1 %2 {:kind :rf.http/http-5xx :http-status 503} {:rf.cofx {:rf/time-ms 1781649764112}})
            {:status :failed :outcome {:error {:kind :rf.http/http-5xx :http-status 503} :completed-at 1781649764112}}]
           [:ab/article #(fail! %1 %2 {:kind :rf.http/aborted :reason :aborted} {:rf.cofx {:rf/time-ms 1781649764112}})
            {:status :cancelled :outcome {:reason :aborted :completed-at 1781649764112}}]]]
    (rf/reg-resource rid (article-spec) article-spec-request)
    (let [k (gkey rid)]
      (ensure! rid [:app rid 1])
      (let [wid (:current-work (entry k))]
        (settle! k wid)
        (is (nil? (:work (entry k))) (str rid " leaves no live attempt"))
        (is (= [wid expected]
               [(:work/id (first (:recent-work (entry k))))
                (select-keys (first (:recent-work (entry k))) (keys expected))])
            (str rid " heads the terminal tail"))
        (is (nil? (rf.resources.work-ledger/get-handle :rf/default wid)) (str rid))))))

;; ---- supersession, dedupe, owners -------------------------------------------

(deftest supersession-aborts-and-marks-the-old-row-suppressed
  (rf/reg-resource :ss/article (article-spec) article-spec-request)
  (let [k (gkey :ss/article)]
    (ensure! :ss/article [:app :ss 1])
    (let [wid1 (:current-work (entry k))]
      (rf/dispatch-sync [:rf.resource/refetch {:resource :ss/article :scope :rf.scope/global
                                               :params {:slug "w"}}])
      (is (contains? (set @aborts) (req wid1)) "a best-effort abort for the old work")
      (is (= [:suppressed :superseded] ((juxt :status (comp :reason :outcome)) (attempt wid1))))
      (fail! k wid1 {:kind :rf.http/aborted :reason :aborted} nil)
      (is (= [:suppressed 2] [(:status (attempt wid1)) (:generation (entry k))])
          "its late aborted reply stays :suppressed, never :cancelled"))))

(deftest dedupe-joins-existing-record
  (rf/reg-resource :dd/article (article-spec) article-spec-request)
  (let [k (gkey :dd/article)]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :dd/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:route :r 1]
                                            :cause [:route-entry :r 1]}])
    (rf/dispatch-sync [:rf.resource/ensure {:resource :dd/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:app :x 2]
                                            :cause [:event :open]}])
    (is (= [1 nil] [(:generation (entry k)) (:recent-work (entry k))])
        "no new attempt, and none displaced")
    (is (= {:owners #{[:route :r 1] [:app :x 2]} :causes [[:route-entry :r 1] [:event :open]]}
           (select-keys (:work (entry k)) [:owners :causes])))))

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
        (is (= #{a b} (:owners (attempt wid))))
        (rf/dispatch-sync [:rf.resource/release-owner {:owner a}])
        (is (not (contains? (set @aborts) (req wid))))
        (is (= [#{b} #{b}] [(:active-owners (entry k)) (:owners (attempt wid))]))
        (settle!)
        (is (= [:loaded :completed] [(:status (entry k)) (:status (attempt wid))]))))
    (testing "a refetch carrying one owner keeps the other; the last release aborts once"
      (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner a)])
      (rf/dispatch-sync [:rf.resource/refetch (assoc q :owner a :cause [:user :refresh])])
      (let [wid (:current-work (entry k))]
        (is (= #{a b} (:owners (attempt wid))))
        (rf/dispatch-sync [:rf.resource/release-owner {:owner a}])
        (is (not (contains? (set @aborts) (req wid))))
        (rf/dispatch-sync [:rf.resource/release-owner {:owner b}])
        (is (= 1 (count (filter #{(req wid)} @aborts))))
        (is (= :abort-requested (:status (attempt wid))))))))

;; ---- removal ----------------------------------------------------------------
;; A clear-scope or remove cancellation is a completion: the entry leaves with
;; its attempts, so the :rf.resource/removed trace carries the causal
;; :completed-at and the aborted work id.

(deftest clear-scope-aborts-in-flight-work-and-traces-its-completion
  (rf/reg-resource :cst/article (article-spec {:scope {:from-db :t/caller-scope}}) article-spec-request)
  (let [scope-a {:user "a"}
        ka      (rf.resources.state/scoped-resource-key scope-a :cst/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :cst/article :scope scope-a
                                            :params {:slug "w"} :owner [:app :a 1]}])
    (let [wid  (:current-work (entry ka))
          _    (is (some? (attempt wid)) "precondition: the entry holds the attempt")
          rows (removed-traces
                 #(rf/dispatch-sync [:rf.resource/clear-scope {:scope scope-a :cause :logout}]
                                    {:rf.cofx {:rf/time-ms 1781649764222}}))]
      (is (contains? (set @aborts) (req wid)))
      (is (= [{:completed-at 1781649764222 :reason :clear-scope :aborted [wid]}]
             (mapv #(select-keys (:tags %) [:completed-at :reason :aborted]) rows)))
      (is (nil? (attempt wid)) "the attempt left with its entry"))))

(deftest remove-aborts-in-flight-work-and-traces-its-completion
  (rf/reg-resource :rmt/article (article-spec) article-spec-request)
  (let [k (gkey :rmt/article)]
    (ensure! :rmt/article [:app :rm 1])
    (let [wid  (:current-work (entry k))
          _    (is (some? (attempt wid)) "precondition: the entry holds the attempt")
          rows (removed-traces
                 #(rf/dispatch-sync [:rf.resource/remove {:resource :rmt/article :scope :rf.scope/global
                                                          :params {:slug "w"}}]
                                    {:rf.cofx {:rf/time-ms 1781649764333}}))]
      (is (contains? (set @aborts) (req wid)))
      (is (= [{:completed-at 1781649764333 :reason :remove :aborted [wid]}]
             (mapv #(select-keys (:tags %) [:completed-at :reason :aborted]) rows)))
      (is (nil? (attempt wid))))))

(deftest repeated-failing-settles-keep-the-ledger-bounded
  ;; The tail is trimmed on every terminal settle, not only a success, so a
  ;; key that never succeeds (a polled failing endpoint) keeps a bounded tail.
  (rf/reg-resource :fl/article (article-spec) article-spec-request)
  (let [q     {:resource :fl/article :scope :rf.scope/global :params {:slug "w"}}
        k     (gkey :fl/article)
        fail! #(rf/dispatch-sync
                 (conj (:on-failure @last-managed-args)
                       {:status :error
                        :error  {:kind :rf.http/server-error :http-status 500}}))]
    (is (> 12 rf.resources.work-ledger/recent-work-limit) "precondition: more attempts than the tail")
    (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner [:app :fl 1])])
    (fail!)
    (dotimes [_ 11]
      (rf/dispatch-sync [:rf.resource/refetch (assoc q :cause [:user :retry])])
      (fail!))
    (is (= (repeat rf.resources.work-ledger/recent-work-limit :failed)
           (map :status (:recent-work (entry k)))))
    (is (= [12 11 10] (map :generation (:recent-work (entry k)))) "newest first")))

(deftest removal-takes-the-terminal-tail-with-the-entry
  ;; A settled attempt survives only as its entry's summary, so removing the
  ;; entry leaves nothing behind to grow.
  (rf/reg-resource :bk/article (article-spec) article-spec-request)
  (let [q {:resource :bk/article :scope :rf.scope/global :params {:slug "w"}}
        k (gkey :bk/article)]
    (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner [:app :bk 1])])
    (let [wid (:current-work (entry k))]
      (rf/dispatch-sync (conj (:on-success @last-managed-args) {:status :ok :value {:title "W"}}))
      (is (= :completed (:status (attempt wid))) "precondition: the settled attempt is in the tail")
      (rf/dispatch-sync [:rf.resource/remove q])
      (is (nil? (attempt wid))))))

(deftest cross-frame-request-id-does-not-collide
  ;; Two frames' work-ids collide when both run on the same recorded
  ;; allocation, so the process-global transport request-id must be
  ;; frame-qualified.
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
                        {:frame f
                         :rf.cofx {:rf.resource/generation-allocation {:generation 1 :counter 1}}}))
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
      (is (= :running (:status (:work (entry fb k)))))
      (is (not= :loaded (:status (entry fb k)))))))

;; ---- a reply finds its entry through the attempt it holds -------------------
;; The internal reply payload names only the attempt (work id + generation +
;; frame); the receiving frame's entry holding that attempt is the one place
;; that says which scoped key it serves.

(deftest a-reply-resolves-its-entry-through-the-ledger-row
  (rf/reg-resource :rk/article (article-spec) article-spec-request)
  (let [k (gkey :rk/article)]
    (ensure! :rk/article [:app :rk 1])
    (let [wid (:current-work (entry k))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:work/id wid :generation 1 :rf.frame/id :rf/default}
                         {:status :ok :value {:title "W"}}])
      (is (= [:loaded {:title "W"}] [(:status (entry k)) (:data (entry k))])
          "a payload with no key settles the entry holding its attempt")
      (is (= :completed (:status (attempt wid)))))))

(deftest a-reply-whose-ledger-row-is-gone-is-suppressed
  ;; The entry still points at the attempt but no longer holds it, so the reply
  ;; has nothing to find its entry through — and a key the payload names
  ;; itself is not trusted.
  (rf/reg-resource :rk/article (article-spec) article-spec-request)
  (let [k (gkey :rk/article)]
    (ensure! :rk/article [:app :rk 1])
    (let [wid        (:current-work (entry k))
          suppressed (atom [])]
      (rf.frame/replace-runtime-db!
        :rf/default
        (update-in (runtime-db) (rf.resources.state/entry-path k) dissoc :work))
      (rf/register-listener! :trace ::stale
                             (fn [ev] (when (= :rf.resource/stale-suppressed (:operation ev))
                                        (swap! suppressed conj ev))))
      (try
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:work/id wid :resource/key k :scope :rf.scope/global
                            :generation 1 :rf.frame/id :rf/default}
                           {:status :ok :value {:title "W"}}])
        (finally (rf/unregister-listener! :trace ::stale)))
      (is (= [:loading wid nil] [(:status (entry k)) (:current-work (entry k)) (:data (entry k))])
          "the reply never settles the entry")
      (is (= 1 (count @suppressed)) "it is recorded as a suppressed stale reply"))))

(deftest two-live-attempts-of-one-resource-settle-their-own-entries
  ;; Same resource, different params, both in flight in one frame: the work ids
  ;; differ by generation, and each reply settles only the entry holding it.
  (rf/reg-resource :rk/article (article-spec) article-spec-request)
  (let [ka      (rf.resources.state/scoped-resource-key :rf.scope/global :rk/article {:slug "a"})
        kb      (rf.resources.state/scoped-resource-key :rf.scope/global :rk/article {:slug "b"})
        replies (atom {})]
    (doseq [slug ["a" "b"]]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :rk/article :scope :rf.scope/global
                                              :params {:slug slug} :owner [:app :rk slug]}])
      (swap! replies assoc slug (:on-success @last-managed-args)))
    (let [wa (:current-work (entry ka))
          wb (:current-work (entry kb))]
      (is (not= wa wb))
      (is (= [:rk/article :rk/article] [(second wa) (second wb)]))
      (rf/dispatch-sync (conj (get @replies "b") {:status :ok :value {:title "B"}}))
      (is (= [:loading :loaded {:title "B"}]
             [(:status (entry ka)) (:status (entry kb)) (:data (entry kb))])
          "b's reply settles b alone")
      (rf/dispatch-sync (conj (get @replies "a") {:status :ok :value {:title "A"}}))
      (is (= [{:title "A"} {:title "B"}] [(:data (entry ka)) (:data (entry kb))])))))

;; ---- the owner-side attempt fns ------------------------------------------------
;; Pinned against a reference model: the tail is the newest settled attempts,
;; newest first, never longer than `recent-work-limit`, and a live attempt is
;; never in it.

(defn- record-for [generation]
  (rf.resources.work-ledger/work-record
    {:work-id    (rf.resources.work-ledger/resource-work-id :wl/r generation)
     :generation generation :transport :rf.http/managed :started-at generation}))

(deftest start-attempt-settles-a-displaced-live-attempt-as-superseded
  (let [owner (-> {:resource/id :wl/r}
                  (rf.resources.work-ledger/start-attempt (record-for 1))
                  (rf.resources.work-ledger/start-attempt (record-for 2)))]
    (is (= [:rf.work/resource :wl/r 2] (:work/id (:work owner))))
    (is (= [{:work/id [:rf.work/resource :wl/r 1] :status :suppressed
             :outcome {:reason :superseded :by [:rf.work/resource :wl/r 2]}}]
           (mapv #(select-keys % [:work/id :status :outcome]) (:recent-work owner))))
    (is (= [[:rf.work/resource :wl/r 2] [[:suppressed {:reason :aborted}]]]
           (let [o (rf.resources.work-ledger/settle-attempt
                     owner [:rf.work/resource :wl/r 1] :suppressed {:reason :aborted})]
             [(:work/id (:work o)) (mapv (juxt :status :outcome) (:recent-work o))]))
        "a late settle of a tail attempt re-marks it in place, leaving the live one")
    (is (= owner (rf.resources.work-ledger/settle-attempt
                   owner [:rf.work/resource :wl/r 9] :completed {}))
        "an attempt the owner does not hold settles nothing")))

(deftest recent-work-matches-a-reference-under-random-attempts
  ;; A deterministic LCG, so the sequence is identical on every host.
  (let [seed    (atom 88172645)
        nextint (fn [n]
                  (let [x (-> (* @seed 1103515245) (+ 12345) (bit-and 0x7fffffff))]
                    (reset! seed x)
                    (mod x n)))
        limit   rf.resources.work-ledger/recent-work-limit
        drift   (loop [step 0, gen 0, owner {:resource/id :wl/r}, settled '(), drift []]
                  (if (= step 500)
                    drift
                    (let [live     (:work owner)
                          start?   (or (nil? live) (zero? (nextint 2)))
                          g        (inc gen)
                          owner'   (if start?
                                     (rf.resources.work-ledger/start-attempt owner (record-for g))
                                     (rf.resources.work-ledger/settle-attempt
                                       owner (:work/id live)
                                       (nth [:completed :failed :cancelled] (nextint 3)) {}))
                          settled' (if live (conj settled (:work/id live)) settled)
                          expected (take limit settled')
                          ok?      (and (= expected (map :work/id (:recent-work owner')))
                                        (not-any? #(= (:work/id (:work owner')) (:work/id %))
                                                  (:recent-work owner')))]
                      (recur (inc step) (if start? g gen) owner' settled'
                             (cond-> drift (not ok?) (conj step))))))]
    (is (= [] drift))))
