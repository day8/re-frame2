(ns re-frame.on-error-cljs-test
  "The corpus-wide `register-error-listener!` registry, the always-on error
  observability surface: a listener receives one tight record per
  `:rf.error/*` event, a throwing listener breaks neither dispatch nor its
  siblings, and the record attributes the failing component and its
  source-coord.

  Posture-independent: every record assertion reads the always-on `:errors`
  stream, so this namespace runs under `-Dre-frame.debug=false`
  (`scripts/test-core-prod-gate.sh`) as the production witness, as well as in
  the ordinary JVM and `:node-test` suites. The one `:trace` read, the
  install-atomicity test's \"no `:rf.event/db-changed`\" check, receives
  nothing under the gate, where only its app-db check bites. An assertion that
  holds only in dev belongs in `re-frame.interceptor-test`, not here."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     ;; The listener registry is a `defonce`; clear it so no listener leaks
     ;; into the next test.
     :init-fn rf.error-emit/clear-error-listeners!}))

(defn- recording-listener!
  "Register a listener that conjs every always-on record onto a fresh atom,
  and return the atom."
  []
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! :test/recorder #(swap! seen conj %))
    seen))

(defn- first-of
  "The first record in `seen` whose `:error` is `category`."
  [category seen]
  (some #(when (= category (:error %)) %) @seen))

(deftest error-listener-fires-on-handler-exception
  (testing "a handler throw yields exactly one record with the tight key set;
            a macro-registered handler's record also carries its :source-coord"
    (let [seen (recording-listener!)]
      (rf/reg-event :err/throw (fn [_ _] (throw (ex-info "kaboom" {}))))
      (rf/dispatch-sync [:err/throw])
      (is (= 1 (count @seen)))
      (let [{sc :source-coord ms :elapsed-ms :as r} (first @seen)]
        (is (= {:error :rf.error/handler-exception :event [:err/throw]
                :event-id :err/throw :frame :rf/default}
               (select-keys r [:error :event :event-id :frame])))
        (is (= #{:error :event :event-id :frame :time :exception :elapsed-ms :source-coord}
               (set (keys r))))
        (is (some? (:exception r)))
        (is (number? (:time r)))
        (is (integer? ms) ":elapsed-ms is an integer on every platform")
        (is (not (neg? ms)))
        (is (and (symbol? (:ns sc)) (integer? (:line sc)) (string? (:file sc))))))))

(deftest error-listener-exception-is-swallowed
  (testing "a throwing listener breaks neither dispatch nor a later sibling"
    (rf.error-emit/register-error-listener! :test/throws
      (fn [_] (throw (ex-info "listener went boom" {}))))
    (let [seen (recording-listener!)]
      (rf/reg-event :err/throw2 (fn [_ _] (throw (ex-info "handler kaboom" {}))))
      (is (nil? (rf/dispatch-sync [:err/throw2])))
      (is (= 1 (count @seen))))))

(deftest listener-fires-on-frame-destroyed-dispatch
  (testing "dispatch and dispatch-sync into an unknown frame recover (no-op)
            and emit one :rf.error/frame-destroyed record. Its :event fails
            closed, since no frame policy is reachable, and it carries no :op:
            an ordinary address-directed dispatch names no realm"
    (doseq [[label dispatch!] [["dispatch"      #(rf/dispatch [:whatever] {:frame :gone/frame})]
                               ["dispatch-sync" #(rf/dispatch-sync [:whatever] {:frame :gone/frame})]]]
      (testing label
        (let [seen (recording-listener!)]
          (is (nil? (dispatch!)))
          (is (= [{:error :rf.error/frame-destroyed :frame :gone/frame
                   :event :rf/redacted :event-id :whatever}]
                 (mapv #(select-keys % [:error :frame :event :event-id :op]) @seen))))))))

(deftest listener-fires-on-no-such-handler
  (testing "dispatching an unregistered id returns normally and emits exactly
            one :rf.error/no-such-handler record"
    (let [seen (recording-listener!)]
      (rf/dispatch-sync [:no/handler-here])
      (is (= [{:error :rf.error/no-such-handler :event [:no/handler-here]
               :event-id :no/handler-here :frame :rf/default}]
             (mapv #(select-keys % [:error :event :event-id :frame]) @seen))))))

(deftest listener-fires-on-no-such-sub
  (let [seen (recording-listener!)]
    (is (nil? (rf/subscribe-once [:no/such-sub-here] {:frame :rf/default})))
    (is (= {:event-id :no/such-sub-here :event [:no/such-sub-here] :frame :rf/default}
           (select-keys (first-of :rf.error/no-such-sub seen) [:event-id :event :frame])))))

(deftest listener-fires-on-compute-sub-exception
  (testing "a sub body throwing on the pure compute-sub path recovers to nil
            and still reaches the always-on listener"
    (let [seen (recording-listener!)]
      (rf/reg-sub :kjf3m/throwing (fn [_db _q] (throw (ex-info "compute-boom" {}))))
      (is (nil? (rf/compute-sub [:kjf3m/throwing] {})))
      (is (some? (:exception (first-of :rf.error/sub-exception seen)))))))

(deftest fx-handler-exception-recovery-is-isolated-siblings-fire
  (testing "a throwing fx is skipped: the fx on either side still fire, and the
            :db installed before the :fx walk is not rolled back"
    (let [fired (atom [])]
      (rf/reg-fx :goum9x/ok-a (fn [_ _] (swap! fired conj :a)))
      (rf/reg-fx :goum9x/boom (fn [_ _] (throw (ex-info "boom" {}))))
      (rf/reg-fx :goum9x/ok-b (fn [_ _] (swap! fired conj :b)))
      (rf/reg-event :goum9x/mixed
                    (fn [{:keys [db]} _]
                      {:db (assoc db :committed? true)
                       :fx [[:goum9x/ok-a] [:goum9x/boom] [:goum9x/ok-b]]}))
      (rf/dispatch-sync [:goum9x/mixed])
      (is (= [:a :b] @fired))
      (is (true? (:committed? (rf/app-db-value :rf/default)))))))

(deftest listener-fires-on-override-fallthrough
  (testing "an :fx-overrides redirect to an unregistered fx-id falls back to the
            registered fx, and the record names the dispatching frame"
    (let [seen  (recording-listener!)
          fired (atom false)]
      (rf/reg-fx :goum9x/real-fx (fn [_ _] (reset! fired true)))
      (rf/reg-event :goum9x/run-bad-override (fn [_ _] {:fx [[:goum9x/real-fx]]}))
      (let [f (rf.frame/make-anon-frame-record! {})]
        (rf/dispatch-sync [:goum9x/run-bad-override]
                          {:frame        f
                           :fx-overrides {:goum9x/real-fx :goum9x/not-registered}})
        (is (true? @fired))
        (is (= f (:frame (first-of :rf.error/override-fallthrough seen))))))))

(deftest interceptor-exception-record-attributes-the-failing-interceptor-and-phase
  (testing "an interceptor throw in either phase names the interceptor in
            :failing-id beside the event's :event-id. `:phase` is not lifted
            onto the record, so :reason is its only production-visible phase
            discriminator"
    (doseq [[phase icpt event-id] [[:before :mlh1h/boom-before :mlh1h/before-throws]
                                   [:after  :mlh1h/boom-after  :mlh1h/after-throws]]]
      (testing (name phase)
        (rf/reg-interceptor icpt {phase (fn [_ctx] (throw (ex-info "boom" {})))})
        (rf/reg-event event-id {:interceptors [icpt]} (fn [{:keys [db]} _] {:db db}))
        (let [seen (recording-listener!)]
          (rf/dispatch-sync [event-id])
          (let [r (first-of :rf.error/interceptor-exception seen)]
            (is (= {:event-id event-id :failing-id icpt}
                   (select-keys r [:event-id :failing-id])))
            (is (re-find (re-pattern (str "`" (name phase) "` phase")) (:reason r)))))))))

(deftest sub-exception-record-carries-sub-source-coord
  (testing "an input-fn throw and a body throw each recover to nil; the record
            carries the sub id and the sub's own source-coord, resolved under
            [:sub id] because the sub categories carry a sub id in :event-id"
    (rf/reg-sub :bxud9v/input-throws
                {:inputs (fn [_q] (throw (ex-info "input-fn-boom" {})))}
                (fn [_in _q] :unreachable))
    (rf/reg-sub :bxud9v/body-throws (fn [_db _q] (throw (ex-info "body-boom" {}))))
    (doseq [[category sub-id] [[:rf.error/sub-input-fn-exception :bxud9v/input-throws]
                               [:rf.error/sub-exception          :bxud9v/body-throws]]]
      (testing (name category)
        (let [seen (recording-listener!)]
          (is (nil? (rf/subscribe-once [sub-id] {:frame :rf/default})))
          (let [{sc :source-coord :as r} (first-of category seen)]
            (is (= sub-id (:event-id r)))
            (is (some? (:exception r)))
            (is (and (symbol? (:ns sc)) (integer? (:line sc)) (string? (:file sc))))))))))

;; ============================================================================
;; :rf.error/frame-destroyed is realm-ambiguous: an event id and a sub id may
;; share a keyword, since they live in separate registries. Every seam that
;; knows its realm stamps the public `:op` onto the record, and the source-coord
;; lookup pivots on it — `:dispatch` / `:dispatch-sync` under `[:event id]`,
;; `:subscribe` under `[:sub id]` — so the realm-blind `[:sub]`-then-`[:event]`
;; fallback can never take the other realm's coord.
;; ============================================================================

(def ^:private event-coord
  {:ns 're-frame.on-error-cljs-test.collide-events :file "collide_events.cljc" :line 11})

(def ^:private sub-coord
  {:ns 're-frame.on-error-cljs-test.collide-subs :file "collide_subs.cljc" :line 22})

(defn- seed-coords!
  "Forget every error coord, then remember `event-coord` / `sub-coord` for `id`
  in each realm named in `realms`."
  [id realms]
  (rf.source-coords/forget-error-coords!)
  (when (:event realms) (rf.source-coords/remember-error-coords! :event id event-coord))
  (when (:sub realms) (rf.source-coords/remember-error-coords! :sub id sub-coord)))

(defn- precheck-superseded-records
  "Pin a `capture-frame` api to a live frame, destroy it and reseat a same-id
  successor, then run the captured `op` with `[id]`: the capture's own
  pre-check sees its pin gone. Returns the always-on records the op emitted."
  [op id realms]
  (let [fid :xlvt/target]
    (rf/make-frame {:id fid :doc "capture target A"})
    (let [h (rf/capture-frame fid)]
      (rf/destroy-frame! fid)
      (rf/make-frame {:id fid :doc "same-id successor B"})
      (seed-coords! id realms)
      (let [seen (recording-listener!)]
        ((get h op) [id])
        @seen))))

(defn- late-superseded-records
  "Pin a `capture-frame` api to live frame A, then run the captured `op` with
  `[id]` while a one-shot interposition on `frame-incarnation-live?` destroys A
  and reseats a same-id successor B at the capture's pre-check: the pre-check
  sees A live, so the op reaches the router's late A→B fence. Returns the
  always-on records the op emitted."
  [op id realms]
  (let [fid :a2x2w/target]
    (rf/make-frame {:id fid :doc "capture target A"})
    (let [h       (rf/capture-frame fid)
          a-token (rf.frame/frame-incarnation-token fid)
          real    rf.frame/frame-incarnation-live?
          fired   (atom false)]
      (seed-coords! id realms)
      (let [seen (recording-listener!)]
        (with-redefs [rf.frame/frame-incarnation-live?
                      (fn [id* token]
                        (let [live? (real id* token)]
                          (when (and (not @fired) (= id* fid) (identical? token a-token) live?)
                            ;; Set before the swap, so the destroy and create
                            ;; take the real liveness path.
                            (reset! fired true)
                            (rf/destroy-frame! fid)
                            (rf/make-frame {:id fid :doc "same-id successor B"}))
                          live?))]
          ((get h op) [id]))
        @seen))))

(def ^:private realm-coord-rows
  "`[label op realms expected]`: both collision directions and both absent-coord
  directions. `::absent` means the record must OMIT `:source-coord` rather than
  take the other realm's coord."
  [["dispatch, id is both an event and a sub"      :dispatch      #{:event :sub} event-coord]
   ["dispatch-sync, id is both an event and a sub" :dispatch-sync #{:event :sub} event-coord]
   ["subscribe, id is both an event and a sub"     :subscribe     #{:event :sub} sub-coord]
   ["subscribe, id is only an event"               :subscribe     #{:event}      ::absent]
   ["dispatch, id is only a sub"                   :dispatch      #{:sub}        ::absent]])

(deftest stale-captured-op-resolves-the-coord-of-its-own-realm
  (testing "a stale captured op, rejected at the capture's pre-check or at the
            router's late A→B fence, emits exactly one :rf.error/frame-destroyed
            record carrying its :op; its :source-coord names the op's own
            realm, or is absent when that realm has no coord"
    (doseq [[seam records-for] [["pre-check"  precheck-superseded-records]
                                ["late fence" late-superseded-records]]
            [label op realms expected] realm-coord-rows]
      (testing (str seam ", " label)
        (let [records (records-for op :audit/collide realms)
              r       (first records)]
          (is (= 1 (count records)))
          (is (= {:error :rf.error/frame-destroyed :op op} (select-keys r [:error :op])))
          (if (= ::absent expected)
            (is (not (contains? r :source-coord)))
            (is (= expected (:source-coord r)))))))))

(deftest ordinary-subscribe-frame-destroyed-stamps-op-egresses-raw-resolves-sub-coord
  (testing "an ordinary subscribe into a missing frame stamps :op :subscribe,
            egresses the query vector raw (a query vector is identity, not
            payload) and resolves :source-coord under [:sub id] even when the id
            also names an event"
    (seed-coords! :alk8a/collide #{:event :sub})
    (let [seen (recording-listener!)]
      (is (nil? (rf/subscribe-once [:alk8a/collide] {:frame :alk8a/gone-frame})))
      (is (= [{:error :rf.error/frame-destroyed :frame :alk8a/gone-frame :op :subscribe
               :event [:alk8a/collide] :source-coord sub-coord}]
             (mapv #(select-keys % [:error :frame :op :event :source-coord]) @seen))))))

(deftest interceptor-after-throw-leaves-app-db-unchanged-no-db-changed
  (testing "an interceptor :after throw aborts the event before the :db install,
            although the handler produced a :db: app-db is unchanged and no
            :rf.event/db-changed is traced"
    (let [traces (atom [])]
      (rf/reg-interceptor :test/boom-after
                          {:after (fn [_ctx] (throw (ex-info "after boom" {})))})
      (rf/reg-event :seed (fn [_ _] {:db {:seeded 1}}))
      (rf/reg-event :writes-then-after-throws
                    {:interceptors [:test/boom-after]}
                    (fn [{:keys [db]} _] {:db (assoc db :written 99)}))
      (rf/dispatch-sync [:seed])
      (rf/register-listener! :trace ::rec #(swap! traces conj %))
      (try
        (rf/dispatch-sync [:writes-then-after-throws])
        (finally (rf/unregister-listener! :trace ::rec)))
      (is (= {:seeded 1} (rf/app-db-value :rf/default)))
      (is (not-any? #(= :rf.event/db-changed (:operation %)) @traces)))))
