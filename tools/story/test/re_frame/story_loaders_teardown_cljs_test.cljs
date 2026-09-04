(ns re-frame.story-loaders-teardown-cljs-test
  "CLJS unit tests for variant-body `:loaders-teardown` slot (rf2-lqs0b).

  The `:loaders-teardown` slot is the symmetric counterpart of `:loaders`
  on the variant body itself: a vector of event vectors dispatch-synced
  into the variant frame on `destroy-variant!` to clean up long-lived
  fx (websocket subscription, polling interval, geolocation watcher)
  that a `:loaders` event opened. Per `002-Runtime.md` §Loader teardown
  contract — Pattern #3.

  Test surface (minimum-viable contract):

  - **schema** — `:loaders-teardown` is an optional vector of event
    vectors on the Variant schema.
  - **fires on destroy** — declared events dispatch-sync into the
    variant frame on `destroy-variant!`.
  - **declared order** — within `:loaders-teardown` events fire in
    declared order (symmetric with `:loaders`).
  - **ordering vs decorator teardown** — `:loaders-teardown` fires
    BEFORE the `:frame-setup` decorator `:teardown` walk (loader-installed
    narrower state cleans up before decorator-installed wider state).
  - **exception caught** — a throwing event does not abort
    `destroy-frame!`; the walk continues, the rest of teardown runs.
  - **assertion record** — a throw projects an `:rf.error/exception`
    record with `:phase :phase-loaders-teardown` into the variant
    frame's `[:rf.story/assertions]`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.frames     :as rf.story.frames]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.schemas    :as rf.story.schemas]
            [re-frame.subs             :as rf.subs]
            [malli.core                :as m]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  ;; Re-register the framework `:rf/machine` sub after the registrar clear.
  ;; EP-0001 (rf2-vzld77 / rf2-ixb0bq): a runtime-db sub reading
  ;; [:rf.runtime/machines :snapshots <id>], NOT the retired app-db
  ;; `:rf/runtime` path — mirror `re-frame.machines`.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (reset! rf.story.frames/stub-call-log {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ===========================================================================
;; SCHEMA — `:loaders-teardown` is an optional vector of event vectors
;; ===========================================================================

(deftest schema-accepts-loaders-teardown
  (testing ":loaders-teardown is an optional vector of event vectors on
            the Variant schema"
    (is (m/validate rf.story.schemas/Variant
                    {:loaders          [[:ws/open]]
                     :loaders-teardown [[:ws/close]]
                     :setup           []}))
    (is (m/validate rf.story.schemas/Variant
                    {:loaders-teardown [[:cleanup]]
                     :setup           []}))
    (is (m/validate rf.story.schemas/Variant
                    {:setup []}))
    (is (m/validate rf.story.schemas/Variant
                    {:loaders-teardown []
                     :setup           []})
        "an empty vector is structurally valid (no-op teardown)")))

(deftest schema-rejects-non-vector-loaders-teardown
  (testing ":loaders-teardown must be a vector of event vectors —
            anything else fails the schema"
    (is (not (m/validate rf.story.schemas/Variant
                         {:loaders-teardown :not-a-vector
                          :setup           []})))
    (is (not (m/validate rf.story.schemas/Variant
                         {:loaders-teardown [:not-a-vector-of-vectors]
                          :setup           []})))))

;; ===========================================================================
;; FIRES — declared events dispatch-sync into the variant frame on destroy
;; ===========================================================================

(deftest loaders-teardown-events-fire-on-destroy
  (testing ":loaders-teardown events fire exactly once on destroy-variant!"
    (let [fired (atom [])]
      (rf/reg-event :ws/open  (fn [{:keys [db]} _] {:db (assoc db :ws? :open)}))
      (rf/reg-event :ws/close (fn [{:keys [db]} _] (swap! fired conj :ws/close) {:db db}))
      (rf.story/reg-variant :story.lt.fires/v
        {:loaders          [[:ws/open]]
         :loaders-teardown [[:ws/close]]
         :setup           []})
      (let [p (rf.story/run-variant :story.lt.fires/v)]
        (async done
          (-> p
              (rf.story.async/then
                (fn [_]
                  (is (= [] @fired)
                      "teardown has NOT fired yet — variant is still live")
                  (rf.story/destroy-variant! :story.lt.fires/v)
                  (is (= [:ws/close] @fired)
                      "teardown fired exactly once on destroy")
                  (done)))))))))

(deftest loaders-teardown-events-fire-in-declared-order
  (testing "within `:loaders-teardown` events fire in DECLARED order —
            symmetric with `:loaders`"
    (let [fired (atom [])]
      (rf/reg-event :step/one   (fn [{:keys [db]} _] (swap! fired conj :one) {:db db}))
      (rf/reg-event :step/two   (fn [{:keys [db]} _] (swap! fired conj :two) {:db db}))
      (rf/reg-event :step/three (fn [{:keys [db]} _] (swap! fired conj :three) {:db db}))
      (rf.story/reg-variant :story.lt.order/v
        {:loaders-teardown [[:step/one] [:step/two] [:step/three]]
         :setup           []})
      (let [p (rf.story/run-variant :story.lt.order/v)]
        (async done
          (-> p
              (rf.story.async/then
                (fn [_]
                  (rf.story/destroy-variant! :story.lt.order/v)
                  (is (= [:one :two :three] @fired)
                      "declared order — symmetric with :loaders")
                  (done)))))))))

;; ===========================================================================
;; ORDERING — `:loaders-teardown` fires BEFORE `:frame-setup` decorator
;; `:teardown` walk (variant-body innermost cleanup, decorator outermost
;; cleanup)
;; ===========================================================================

(deftest loaders-teardown-fires-before-decorator-teardown
  (testing ":loaders-teardown (variant body) fires BEFORE decorator
            :teardown — narrower (loader-installed) cleanup runs before
            wider (decorator-installed) cleanup. Per 002-Runtime.md
            §Loader teardown contract step ordering."
    (let [fired (atom [])]
      (rf/reg-event :dec/teardown
        (fn [{:keys [db]} _] (swap! fired conj :decorator) {:db db}))
      (rf/reg-event :dec/init    (fn [{:keys [db]} _] {:db db}))
      (rf/reg-event :lt/cleanup
        (fn [{:keys [db]} _] (swap! fired conj :loaders-teardown) {:db db}))
      (rf.story/reg-decorator :outer-dec
        {:kind     :frame-setup
         :init     [[:dec/init]]
         :teardown [[:dec/teardown]]})
      (rf.story/reg-variant :story.lt.order2/v
        {:decorators       [[:outer-dec]]
         :loaders-teardown [[:lt/cleanup]]
         :setup           []})
      (let [p (rf.story/run-variant :story.lt.order2/v)]
        (async done
          (-> p
              (rf.story.async/then
                (fn [_]
                  (rf.story/destroy-variant! :story.lt.order2/v)
                  (is (= [:loaders-teardown :decorator] @fired)
                      "loaders-teardown runs first; decorator :teardown runs after")
                  (done)))))))))

;; ===========================================================================
;; EXCEPTION HANDLING — a throwing event does not abort destroy
;; ===========================================================================

(deftest throwing-loaders-teardown-event-does-not-abort-destroy
  (testing "a `:loaders-teardown` event that throws is caught —
            destroy-frame! still runs to completion. Walk never aborts
            (002-Runtime.md §Loader teardown contract)."
    (rf/reg-event :boom/cleanup
      (fn [_ _] (throw (ex-info "loader-teardown boom" {:why :test}))))
    (rf.story/reg-variant :story.lt.boom/v
      {:loaders-teardown [[:boom/cleanup]]
       :setup           []})
    (let [p (rf.story/run-variant :story.lt.boom/v)]
      (async done
        (-> p
            (rf.story.async/then
              (fn [_]
                (is (nil? (rf.story/destroy-variant! :story.lt.boom/v))
                    "destroy-variant! returns nil — exception caught")
                (is (not (contains? (rf.story/variant-frames)
                                    :story.lt.boom/v))
                    "frame is destroyed despite the throw")
                (done))))))))

(deftest throwing-loaders-teardown-continues-walk
  (testing "a throw in one `:loaders-teardown` event does NOT skip
            subsequent events in the vector — the walk continues
            (symmetric with the `:teardown` decorator walk)"
    (let [fired (atom [])]
      (rf/reg-event :step/before
        (fn [{:keys [db]} _] (swap! fired conj :before) {:db db}))
      (rf/reg-event :step/boom
        (fn [_ _] (throw (ex-info "boom" {}))))
      (rf/reg-event :step/after
        (fn [{:keys [db]} _] (swap! fired conj :after) {:db db}))
      (rf.story/reg-variant :story.lt.continue/v
        {:loaders-teardown [[:step/before] [:step/boom] [:step/after]]
         :setup           []})
      (let [p (rf.story/run-variant :story.lt.continue/v)]
        (async done
          (-> p
              (rf.story.async/then
                (fn [_]
                  (rf.story/destroy-variant! :story.lt.continue/v)
                  (is (= [:before :after] @fired)
                      "the walk continues past the throw")
                  (done)))))))))

;; ===========================================================================
;; ASSERTION RECORD — throws project :rf.error/exception with phase tag
;; ===========================================================================

(deftest throwing-loaders-teardown-records-assertion
  (testing "a `:loaders-teardown` event that throws is projected as
            `:rf.error/exception` with `:phase :phase-loaders-teardown`
            into the variant frame's [:rf.story/assertions]. Captured
            via a `:frame-setup` decorator probe whose `:teardown` runs
            LAST (after `:loaders-teardown`) — by then the assertion
            record has already landed."
    (let [captured (atom nil)]
      (rf/reg-event :boom/cleanup
        (fn [_ _] (throw (ex-info "lt boom" {:why :test}))))
      (rf/reg-event ::probe-init  (fn [{:keys [db]} _] {:db db}))
      (rf/reg-event ::probe-snapshot
        (fn [{:keys [db]} _]
          (reset! captured (:rf.story/assertions db))
          {:db db}))
      (rf.story/reg-decorator :lt-probe
        {:kind     :frame-setup
         :init     [[::probe-init]]
         :teardown [[::probe-snapshot]]})
      (rf.story/reg-variant :story.lt.record/v
        {:decorators       [[:lt-probe]]
         :loaders-teardown [[:boom/cleanup]]
         :setup           []})
      (let [p (rf.story/run-variant :story.lt.record/v)]
        (async done
          (-> p
              (rf.story.async/then
                (fn [_]
                  (rf.story/destroy-variant! :story.lt.record/v)
                  (let [asserts @captured
                        err     (first (filter
                                         #(= :rf.error/exception (:assertion %))
                                         asserts))]
                    (is (some? err)
                        "an `:rf.error/exception` record was projected")
                    (is (= :phase-loaders-teardown (:phase err))
                        ":phase :phase-loaders-teardown on the record")
                    (is (false? (:passed? err))
                        ":passed? false — error records never pass")
                    (is (= [:boom/cleanup] (:event err))
                        ":event slot carries the throwing event vector")
                    (is (= :story.lt.record/v (:variant-id err))
                        ":variant-id carries the variant id")
                    (is (= {:why :test} (:data (:error err)))
                        ":error :data carries the ex-info data map"))
                  (done)))))))))

;; ===========================================================================
;; UNAFFECTED SHAPES — variants without `:loaders-teardown` work as before
;; ===========================================================================

(deftest variant-without-loaders-teardown-still-tears-down
  (testing "a variant declaring no `:loaders-teardown` slot still
            tears down cleanly — destroy! is a no-op for the new step"
    (rf/reg-event :seed/init
      (fn [{:keys [db]} _] {:db (assoc db :seeded? true)}))
    (rf.story/reg-variant :story.lt.none/v
      {:setup [[:seed/init]]})
    (let [p (rf.story/run-variant :story.lt.none/v)]
      (async done
        (-> p
            (rf.story.async/then
              (fn [_]
                (is (nil? (rf.story/destroy-variant! :story.lt.none/v))
                    "destroy returns nil — no-op for variants without
                     :loaders-teardown")
                (is (not (contains? (rf.story/variant-frames)
                                    :story.lt.none/v))
                    "frame is destroyed")
                (done))))))))
