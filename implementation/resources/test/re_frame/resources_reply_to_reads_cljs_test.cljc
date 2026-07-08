(ns re-frame.resources-reply-to-reads-cljs-test
  "Read completion continuations — call-site `:reply-to` on `:rf.resource/ensure`
  / `:rf.resource/refetch` (rf2-p1yri7, EP-0016 D1 extended to reads; Spec 016
  §Read completion continuations).

  A read the runtime causes now gets the SAME acceptance-keyed, exactly-once,
  stale-suppressed completion continuation a mutation does. These JVM+CLJS unit
  tests pin the load-bearing semantics:

    1. accepted fetch — an ensure with `:reply-to` fires the target ONCE on the
       accepted success reply, carrying the canonical reply map PLUS the
       top-level read facts (resource id, params, scope, `:resource/key`,
       `:cache-hit? false`) and the work identity;
    2. fresh-skip CACHE HIT — a fresh `:loaded` entry serves the cached value and
       the continuation dispatches IMMEDIATELY, `:cache-hit? true`, no reply
       needed;
    3. JOIN-IN-FLIGHT — two ensures sharing one in-flight work record each supply
       their own `:reply-to`; the ONE accepted terminal reply fans out to BOTH
       targets exactly once;
    4. STALE / superseded — a reply that no longer correlates with the live entry
       (a forced-refetch supersession) NEVER fires the continuation;
    5. failure — an accepted terminal failure fires the continuation with
       `:status :error` (so a machine learns the read it caused failed);
    6. the `:rf.resource/replied` trace mirrors `:rf.mutation/replied`.

  Harness: the `:rf.http/managed` fx is overridden with a capturing stub; the
  reply is replayed explicitly via `reply-success!` / `reply-failure!` (the real
  3-element internal reply event the live transport produces), so the reply
  handlers + the accepted-reply fan-out run end-to-end. The continuation targets
  are ordinary app events recording the appended reply."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting requires: register the :rf.resource/* events +
   ;; subs + the generation cofx/fx these tests dispatch.
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.resources.work-ledger :as work-ledger]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves); the
   ;; actual fetch is overridden by the capturing reply stub below.
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   [re-frame.trace.tooling :as trace-tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport + continuation-recording fixture ------------------

(def ^:private last-managed-args (atom nil))
(def ^:private replied (atom []))

(defn- capturing-fixture [f]
  (reset! last-managed-args nil)
  (reset! replied [])
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  ;; capture host-side timer arming so the fresh-skip / success handlers'
  ;; emission does not fire a real wall-clock timer.
  (rf/reg-fx :rf.resource/schedule-timers (fn [_ctx _args] nil))
  ;; the continuation target — an ordinary app event recording the FULL event
  ;; vector (so static-arg preservation + the appended reply are both
  ;; observable). Every test's `:reply-to` names it (distinguished by a static
  ;; tag arg where a test fans out to two targets).
  (rf/reg-event :test/read-replied (fn [_ event] (swap! replied conj event) {}))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  capturing-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (state/entry-path scoped-key)))

(defn- reply-success!
  ([args data] (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})))
  ([args data opts] (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data}) opts)))

(defn- reply-failure!
  [args failure] (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure})))

(defn- article-spec
  ([] (article-spec {}))
  ([overrides]
   (merge {:scope         :rf.scope/global
           :params-schema [:map [:slug :string]]
           :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
          overrides)))

(def ^:private article-request
  (fn [{:keys [slug]} _ctx] {:request {:method :get :url (str "/api/articles/" slug)}}))

(defn- record-replied-traces!
  "Return the vector of every `:rf.resource/replied` trace emitted while running
  `body-fn`."
  [body-fn]
  (let [seen (atom [])
        k    ::replied-trace-recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= :rf.resource/replied (:operation ev))
                   (swap! seen conj ev))))
    (try (body-fn)
         (finally (trace-tooling/unregister-listener! k)))
    @seen))

;; ===========================================================================
;; 1. Accepted fetch — the continuation fires once, carrying the read facts
;; ===========================================================================

(deftest reply-to-fires-on-accepted-fetch-and-carries-read-facts
  (rf/reg-resource :rr/article (article-spec) article-request)
  (let [rkey (state/scoped-resource-key :rf.scope/global :rr/article {:slug "w"})
        completed-at 1781078400777]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :rr/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:view :a]
                        :reply-to [:test/read-replied]}])
    (testing "no continuation before the read settles"
      (is (= 0 (count @replied))))
    (reply-success! @last-managed-args {:title "Welcome"}
                    {:rf.cofx {:rf/time-ms completed-at}})
    (testing "the continuation fired exactly once on the accepted reply"
      (is (= 1 (count @replied))))
    (testing "the appended reply map carries the canonical fields + read facts"
      (let [[ev-id reply] (first @replied)]
        (is (= :test/read-replied ev-id))
        (is (= :ok (:status reply)))
        (is (= {:title "Welcome"} (:value reply)) "decoded result rides as :value")
        (is (= :rr/article (:resource reply)))
        (is (= {:slug "w"} (:params reply)))
        (is (= :rf.scope/global (:scope reply)))
        (is (= rkey (:resource/key reply)))
        (is (false? (:cache-hit? reply)) "a fetched settle is not a cache hit")
        (is (= :resource (:work/kind reply)))
        (is (some? (:work/id reply)))
        (is (= :rf/default (:rf.frame/id reply)))
        (is (= completed-at (:completed-at reply)) "EP-0010 causal completion time")))))

;; ===========================================================================
;; 2. Fresh-skip cache hit — the continuation dispatches IMMEDIATELY
;; ===========================================================================

(deftest reply-to-cache-hit-dispatches-immediately
  (rf/reg-resource :rr/article (article-spec) article-request)
  (let [rkey (state/scoped-resource-key :rf.scope/global :rr/article {:slug "w"})]
    ;; first: load + settle so the entry is fresh :loaded (no :stale-after-ms →
    ;; never time-stale → a later ensure is a fresh-skip cache hit).
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :rr/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:view :a]}])
    (reply-success! @last-managed-args {:title "Cached"})
    (reset! last-managed-args nil)
    (is (= :loaded (:status (entry rkey))) "entry is settled :loaded")
    ;; second ensure — a fresh-skip cache hit with a call-site :reply-to
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :rr/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:view :b]
                        :reply-to [:test/read-replied]}])
    (testing "the continuation dispatched IMMEDIATELY — no reply/fetch needed"
      (is (nil? @last-managed-args) "no new transport request was lowered")
      (is (= 1 (count @replied))))
    (testing "the immediate reply is :ok, :cache-hit? true, cached value as :value"
      (let [[_ reply] (first @replied)]
        (is (= :ok (:status reply)))
        (is (true? (:cache-hit? reply)))
        (is (= {:title "Cached"} (:value reply)))
        (is (= rkey (:resource/key reply)))
        (is (some? (:work/id reply)) "cache-hit derives a work id from the entry generation")))))

;; ===========================================================================
;; 3. Join-in-flight — one accepted reply fans out to every joined target once
;; ===========================================================================

(deftest reply-to-join-in-flight-fans-out-exactly-once
  (rf/reg-resource :rr/article (article-spec) article-request)
  (let [rkey (state/scoped-resource-key :rf.scope/global :rr/article {:slug "w"})]
    ;; first ensure kicks off the load (owner A, target tagged :a)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :rr/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:view :a]
                        :reply-to [:test/read-replied :a]}])
    (let [args @last-managed-args
          wid  (:current-work (entry rkey))]
      ;; second ensure JOINS the in-flight work (owner B, target tagged :b) —
      ;; same scoped key, not force-new → dedupe/join, no new request.
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :rr/article :scope :rf.scope/global
                          :params {:slug "w"} :owner [:view :b]
                          :reply-to [:test/read-replied :b]}])
      (testing "the second ensure joined (no new transport request)"
        (is (= args @last-managed-args) "still the same in-flight args"))
      (testing "both targets recorded on the ONE shared work record"
        (let [rec (work-ledger/get-record (runtime-db) wid)]
          (is (= 2 (count (:reply-targets rec))))))
      ;; ONE success reply settles the shared work
      (reply-success! args {:title "Shared"})
      (testing "the reply fanned out to BOTH targets exactly once each"
        (is (= 2 (count @replied)))
        (let [tags (set (map second @replied))]
          (is (= #{:a :b} tags) "both :a and :b fired"))
        (doseq [[_ tag reply] @replied]
          (is (contains? #{:a :b} tag) "static call-site arg preserved before the reply")
          (is (= :ok (:status reply)))
          (is (= {:title "Shared"} (:value reply))))))))

;; ===========================================================================
;; 4. Stale / superseded — the continuation is NEVER delivered
;; ===========================================================================

(deftest reply-to-stale-superseded-never-delivered
  (rf/reg-resource :rr/article (article-spec) article-request)
  (let [rkey (state/scoped-resource-key :rf.scope/global :rr/article {:slug "w"})]
    ;; gen-1 ensure carries a :reply-to
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :rr/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:view :a]
                        :reply-to [:test/read-replied]}])
    (let [gen1-args @last-managed-args]
      ;; a forced refetch supersedes gen-1 with gen-2 (no :reply-to on gen-2)
      (rf/dispatch-sync [:rf.resource/refetch
                         {:resource :rr/article :scope :rf.scope/global
                          :params {:slug "w"}}])
      (is (= 2 (:generation (entry rkey))) "gen-2 is now the live work")
      (testing "the STALE gen-1 reply NEVER fires the continuation (suppression)"
        (reply-success! gen1-args {:title "stale"})
        (is (= 0 (count @replied)) "a superseded reply delivers nothing"))
      (testing "the live gen-2 reply (which carried no :reply-to) fires nothing either"
        (reply-success! @last-managed-args {:title "fresh"})
        (is (= 0 (count @replied)))))))

;; ===========================================================================
;; 5. Failure — an accepted terminal failure fires the continuation (:error)
;; ===========================================================================

(deftest reply-to-fires-on-accepted-failure
  (rf/reg-resource :rr/article (article-spec) article-request)
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :rr/article :scope :rf.scope/global
                      :params {:slug "w"} :owner [:view :a]
                      :reply-to [:test/read-replied]}])
  (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 503})
  (testing "the continuation fired once with :status :error and the envelope"
    (is (= 1 (count @replied)))
    (let [[_ reply] (first @replied)]
      (is (= :error (:status reply)))
      (is (= {:kind :rf.http/http-5xx :status 503} (:error reply)))
      (is (false? (:cache-hit? reply))))))

;; ===========================================================================
;; 6. Trace — :rf.resource/replied mirrors :rf.mutation/replied
;; ===========================================================================

(deftest replied-trace-emitted-for-accepted-not-for-stale
  (rf/reg-resource :rr/article (article-spec) article-request)
  (testing "an accepted reply emits :rf.resource/replied carrying the targets + status"
    (let [traces (record-replied-traces!
                   (fn []
                     (rf/dispatch-sync [:rf.resource/ensure
                                        {:resource :rr/article :scope :rf.scope/global
                                         :params {:slug "w"} :owner [:view :a]
                                         :reply-to [:test/read-replied]}])
                     (reply-success! @last-managed-args {:title "Welcome"})))]
      (is (= 1 (count traces)))
      (let [tags (:tags (first traces))]
        (is (= :ok (:status tags)))
        (is (false? (:cache-hit? tags)))
        (is (seq (:targets tags))))))
  (testing "a stale/superseded reply emits NO :rf.resource/replied"
    (reset! replied [])
    (let [traces (record-replied-traces!
                   (fn []
                     (rf/dispatch-sync [:rf.resource/ensure
                                        {:resource :rr/article :scope :rf.scope/global
                                         :params {:slug "s"} :owner [:view :b]
                                         :reply-to [:test/read-replied]}])
                     (let [gen1 @last-managed-args]
                       (rf/dispatch-sync [:rf.resource/refetch
                                          {:resource :rr/article :scope :rf.scope/global
                                           :params {:slug "s"}}])
                       (reply-success! gen1 {:title "stale"}))))]
      (is (= 0 (count traces)) "suppressed reply emits no replied trace"))))
