(ns re-frame.sub-cache-test
  "The per-frame sub-cache (Spec 006 §Cache shape, §Reference counting and
  disposal): one slot per query, ref-counted, evicted IN-TICK when the last
  subscriber drops, with the on-dispose callback releasing the slot's declared
  inputs so layer-2+ disposal cascades.

  Assertions outside a `(when rf.interop/debug-enabled? …)` arm hold under the
  production gate (`scripts/test-core-prod-gate.sh`) too; the arms observe the
  dev `:trace` stream, which the gate elides by design."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; `init!` does not synthesise `:rf/default` and ambient reads need a
  ;; carried frame (EP-0002), so register it and pin it as the scope.
  (rf.frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- ref-counts
  "query-v -> :ref-count for every slot in `:rf/default`'s sub-cache."
  []
  (into {} (map (fn [[k v]] [k (:ref-count v)])) @(:sub-cache (rf.frame/frame :rf/default))))

(defn- entry [query-v]
  (get @(:sub-cache (rf.frame/frame :rf/default)) query-v))

(defn- seed-n! []
  (rf/reg-event :seed (fn [_ _] {:db {:n 7}}))
  (rf/reg-sub :n (fn [db _] (:n db)))
  (rf/dispatch-sync [:seed]))

(deftest cache-entry-shape-matches-spec-006
  ;; Spec 006 §Cache shape advertises EXACTLY these keys; the value lives on
  ;; the reaction, never in the entry.
  (rf/reg-event :init (fn [_ _] {:db {:a 2 :b 3}}))
  (rf/reg-sub :a (fn [db _] (:a db)))
  (rf/reg-sub :b (fn [db _] (:b db)))
  (rf/reg-sub :sum {:inputs [[:a] [:b]]} (fn [[a b] _] (+ a b)))
  (rf/dispatch-sync [:init])
  (let [r (rf/subscribe [:sum])]
    (is (= {:reaction r :inputs [[:a] [:b]] :ref-count 1} (entry [:sum])))
    (is (= {:inputs [] :ref-count 1} (dissoc (entry [:a]) :reaction)))))

(deftest sub-cache-ref-counting
  (seed-n!)
  (rf/unsubscribe [:n])
  (is (= {} (ref-counts)) "an unsubscribe with no slot is a no-op")
  (is (identical? (rf/subscribe [:n]) (rf/subscribe [:n])) "a cache hit shares one slot")
  (is (= {[:n] 2} (ref-counts)))
  (rf/unsubscribe [:n])
  (is (= {[:n] 1} (ref-counts)))
  (rf/unsubscribe [:n])
  (is (= {} (ref-counts)) "the slot is evicted when the count reaches zero")
  (rf/unsubscribe [:n])
  (is (= {} (ref-counts)) "unsubscribe past zero is idempotent"))

(deftest subscribe-once-opts-map-binds-the-named-frame
  ;; The opts-map call shape parallel to `subscribe` (EP-0024). With no ambient
  ;; frame the opts map is the only thing naming the target; under the
  ;; fixture's scope, opts without `:frame` read the ambient frame.
  (rf/make-frame {:id :bfadc6/left :doc "left frame"})
  (rf/make-frame {:id :bfadc6/right :doc "right frame"})
  (rf/reg-event :seed-v (fn [_ [_ v]] {:db {:v v}}))
  (rf/reg-sub :v (fn [db _] (:v db)))
  (rf/dispatch-sync [:seed-v :left-value]  {:frame :bfadc6/left})
  (rf/dispatch-sync [:seed-v :right-value] {:frame :bfadc6/right})
  (rf/dispatch-sync [:seed-v :default-value])
  (is (= [:left-value :right-value :default-value]
         [(binding [rf.frame/*current-frame* nil] (rf/subscribe-once [:v] {:frame :bfadc6/left}))
          (binding [rf.frame/*current-frame* nil] (rf/subscribe-once [:v] {:frame :bfadc6/right}))
          (rf/subscribe-once [:v] {})])))

(deftest subscribe-before-register-does-not-cache
  ;; Spec 006: a no-such-sub miss yields nil and is NOT cached, so a later
  ;; registration (boot order, lazy-loaded namespace) is observed.
  (rf/reg-event :init (fn [_ _] {:db {:n 7}}))
  (rf/dispatch-sync [:init])
  (let [miss              @(rf/subscribe [:my-sub])
        cached-after-miss (contains? (ref-counts) [:my-sub])]
    (rf/reg-sub :my-sub (fn [db _] (:n db)))
    (is (= [nil false 7] [miss cached-after-miss @(rf/subscribe [:my-sub])]))))

(deftest clear-sub-id-leaves-cache-intact
  ;; Clearing a registration is registry-only; `clear-sub-cache!` is the
  ;; eviction half of the pair.
  (seed-n!)
  (rf/subscribe [:n])
  (rf/clear :sub :n)
  (is (= {[:n] 1} (ref-counts)) "the slot survives clearing the registration")
  (is (nil? (rf.registrar/lookup :sub :n)) "the registration is gone")
  (rf/clear-sub-cache! :rf/default)
  (is (= {} (ref-counts))))

(deftest unsubscribe-if-reaction-no-ops-against-a-successor-entry
  ;; `unsubscribe-if-reaction` is the release for a holder whose reference can
  ;; outlive its slot (the React-hook spine's provisional acquisition, Spec 006
  ;; §Render-phase provisional acquisition and commit adoption). Hot reload
  ;; evicts the slot and takes the holder's +1 with it, so the late release
  ;; must not steal the successor's reference.
  (seed-n!)
  (let [stale (rf/subscribe [:n])]
    (rf/reg-sub :n (fn [db _] (* 10 (:n db))))
    (let [successor (rf/subscribe [:n])]
      (rf.subs/unsubscribe-if-reaction :rf/default [:n] stale)
      (is (= [70 {[:n] 1}] [@successor (ref-counts)])))))

(deftest layer-2-disposal-respects-shared-inputs
  ;; Two parents share input :a, and :ab has two subscribers: inputs are
  ;; acquired only on the cache-miss build, and each eviction releases them.
  (rf/reg-event :init (fn [_ _] {:db {:a 2 :b 3 :c 4}}))
  (rf/reg-sub :a (fn [db _] (:a db)))
  (rf/reg-sub :b (fn [db _] (:b db)))
  (rf/reg-sub :c (fn [db _] (:c db)))
  (rf/reg-sub :ab {:inputs [[:a] [:b]]} (fn [[a b] _] (+ a b)))
  (rf/reg-sub :ac {:inputs [[:a] [:c]]} (fn [[a c] _] (+ a c)))
  (rf/dispatch-sync [:init])
  (rf/subscribe [:ab])
  (rf/subscribe [:ab])
  (rf/subscribe [:ac])
  (is (= {[:ab] 2 [:ac] 1 [:a] 2 [:b] 1 [:c] 1} (ref-counts)))
  (rf/unsubscribe [:ab])
  (is (= {[:ab] 1 [:ac] 1 [:a] 2 [:b] 1 [:c] 1} (ref-counts)) "a live parent keeps its inputs")
  (rf/unsubscribe [:ab])
  (is (= {[:ac] 1 [:a] 1 [:c] 1} (ref-counts)) "the shared input dropped by exactly one")
  (rf/unsubscribe [:ac])
  (is (= {} (ref-counts))))

(deftest layer-3-disposal-cascades-through-chain
  ;; Each evicted layer's own on-dispose must run, so the release reaches past
  ;; the first input.
  (rf/reg-event :init (fn [_ _] {:db {:a 2}}))
  (rf/reg-sub :a (fn [db _] (:a db)))
  (rf/reg-sub :a*2 {:inputs [[:a]]}   (fn [[a] _] (* 2 a)))
  (rf/reg-sub :a*4 {:inputs [[:a*2]]} (fn [[a2] _] (* 2 a2)))
  (rf/dispatch-sync [:init])
  (is (= 8 @(rf/subscribe [:a*4])))
  (rf/unsubscribe [:a*4])
  (is (= {} (ref-counts))))

(deftest layer-2-input-refs-released-when-frame-destroyed-mid-build
  ;; `compute-and-cache!` subscribes a layer-2 build's inputs BEFORE it
  ;; re-resolves the frame to read `:sub-cache`. If the frame is gone at that
  ;; read, the parent is returned uncached and never dispose-wired, so the
  ;; inputs must be released on that path or leak. Redefining `rf.frame/frame`
  ;; to answer nil once both inputs are cached reproduces the seam.
  (rf/reg-event :init (fn [_ _] {:db {:a 2 :b 3}}))
  (rf/reg-sub :a (fn [db _] (:a db)))
  (rf/reg-sub :b (fn [db _] (:b db)))
  (rf/reg-sub :sum {:inputs [[:a] [:b]]} (fn [[a b] _] (+ a b)))
  (rf/dispatch-sync [:init])
  (let [real-frame rf.frame/frame
        tripped?   (atom false)]
    (with-redefs [rf.frame/frame
                  (fn [id]
                    (if (and (= id :rf/default)
                             (not @tripped?)
                             (let [c @(:sub-cache (real-frame :rf/default))]
                               (and (contains? c [:a]) (contains? c [:b]))))
                      (do (reset! tripped? true) nil)
                      (real-frame id)))]
      (rf/subscribe [:sum]))
    (is (= [true {}] [@tripped? (ref-counts)]))))

(deftest no-such-sub-trace-tags-match-spec-009
  ;; Spec 009 §Error catalogue's `:rf.error/no-such-sub` tags; there is no
  ;; `:rf.sub/query-v` tag.
  (rf/reg-event :init (fn [_ _] {:db {:n 7}}))
  (rf/dispatch-sync [:init])
  (let [traces (atom [])]
    (rf/register-listener! :trace ::no-such (fn [ev] (swap! traces conj ev)))
    @(rf/subscribe [:missing/sub])
    (rf/unregister-listener! :trace ::no-such)
    (when rf.interop/debug-enabled?
      (is (= [{:rf.sub/id :missing/sub :unresolved-input [:missing/sub] :resolved-inputs []
               :frame :rf/default}]
             (for [ev @traces :when (= :rf.error/no-such-sub (:operation ev))]
               (select-keys (:tags ev) [:rf.sub/id :unresolved-input :resolved-inputs :frame
                                        :rf.sub/query-v])))))))

(deftest sub-hot-reload-invalidates-cache
  ;; A pinned slot would still answer 7 if re-registration did not evict it.
  (seed-n!)
  (rf/subscribe [:n])
  (let [traces (atom [])]
    (rf/register-listener! :trace ::hot-reload (fn [ev] (swap! traces conj ev)))
    (rf/reg-sub :n (fn [db _] (* 10 (:n db))))
    (rf/unregister-listener! :trace ::hot-reload)
    (is (= 70 (rf/subscribe-once [:n])))
    (when rf.interop/debug-enabled?
      (is (some #(= {:operation :rf.registry/handler-replaced :op-type :rf.registry
                     :kind :sub :id :n}
                    (assoc (select-keys % [:operation :op-type])
                           :kind (:kind (:tags %)) :id (:id (:tags %))))
                @traces)))))

(deftest subscribe-frameless-read-raises-no-frame-context
  ;; EP-0002: with no carried frame the ambient read surfaces raise rather than
  ;; read an invented `:rf/default`.
  (seed-n!)
  (binding [rf.frame/*current-frame* nil]
    (is (= (repeat 4 :rf.error/no-frame-context)
           (for [thunk [#(rf/subscribe [:n]) #(rf/subscribe-once [:n])
                        #(rf/unsubscribe [:n]) #(rf/clear-sub-cache!)]]
             (try (thunk) nil
                  (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))))))

;; A frame OBJECT normalizes to its runnable-id ADDRESS through
;; `rf.frame/frame-target->id` on every operation, so release reaches the slot
;; subscribe made; `(rf.frame/frame <object>)` would miss and leak it.

(defn- object-cache-keys [frame-obj]
  (set (keys @(:sub-cache (rf.frame/frame (rf.frame/frame-target->id frame-obj))))))

(defn- make-n-frame
  "A no-id frame OBJECT (gensym runnable-id) running an inline image with a
  layer-1 `:n` sub, its app-db seeded with `seed-db` through `:initial-events`."
  [{:keys [seed-db] :as opts}]
  (rf.live-frame/make-frame
    (merge {:images [(rf/image {:id :ts3fuk/img
                                :registrations
                                {:reg-sub   [[:n {:doc "n"} (fn [db _] (:n db))]]
                                 :reg-event [[:ts3fuk/seed {:doc "seed"} (fn [_ [_ new-db]] {:db new-db})]]}})]
            :initial-events (when (some? seed-db) [[:ts3fuk/seed seed-db]])}
           (dissoc opts :seed-db))))

(deftest unsubscribe-object-target-tears-down-the-entry
  (let [frame-obj (make-n-frame {:seed-db {:n 7}})]
    (is (= 7 @(rf/subscribe [:n] {:frame frame-obj})))
    (rf/unsubscribe frame-obj [:n])
    (is (= #{} (object-cache-keys frame-obj)) "unsubscribe released the object-target slot")
    (is (= 7 (rf/subscribe-once [:n] {:frame frame-obj})))
    (is (= #{} (object-cache-keys frame-obj)) "subscribe-once released it in-tick")
    (rf/destroy-frame! frame-obj)))
