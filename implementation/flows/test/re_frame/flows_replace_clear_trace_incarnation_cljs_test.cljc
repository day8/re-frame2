(ns re-frame.flows-replace-clear-trace-incarnation-cljs-test
  "Exact-incarnation fence for the replacement and clear lifecycle traces
  through the trace-emit callback pipeline, and the per-frame decision behind
  replacement evidence, on both hosts.

  A direct `reg-flow` replacement emits `:rf.registry/handler-replaced`, and
  `clear-flow` emits `:rf.flow/cleared` from inside its serialized section,
  each under a continuation predicate bound to A's pinned incarnation: a
  listener that destroys A and publishes a same-id B mid-fan-out stands, and
  every later listener is suppressed. A declares no output marks, so the
  listener fan-out is the only callback seam. Listeners fan out in insertion
  order, so the destroyer is registered first."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- fence-witness!
  "Register a destroyer that, on the first `op` event, destroys frame `id` and
  publishes a same-id B, then an observer recording every later `op` event's
  tags. Returns `[destroyer-hits observed]`."
  [id op]
  (let [hits (atom 0) observed (atom [])]
    (rf.trace.tooling/register-listener!
      ::destroyer
      (fn [ev]
        (when (and (= op (:operation ev)) (= 1 (swap! hits inc)))
          (rf.frame/destroy-frame! id)
          (rf/make-frame {:id id}))))
    (rf.trace.tooling/register-listener!
      ::observer
      (fn [ev] (when (= op (:operation ev)) (swap! observed conj (:tags ev)))))
    [hits observed]))

(defn- unregister-witness! []
  (rf.trace.tooling/unregister-listener! ::destroyer)
  (rf.trace.tooling/unregister-listener! ::observer))

(defn- reg! [id flow-id derive-fn]
  (rf/reg-flow flow-id {:frame id :inputs [[:n]] :output-path [flow-id]} derive-fn))

(deftest reg-flow-replacement-trace-listener-loss-fences-subsequent-listeners
  (let [id :flow.replace.fence/subject]
    (rf/make-frame {:id id})
    (reg! id :a identity)
    (let [[hits observed] (fence-witness! id :rf.registry/handler-replaced)]
      (try
        (is (= :a (reg! id :a (fn [n] n))))
        (is (= 1 @hits) "the already-entered delivery stands")
        (is (= [] @observed) "no later listener receives A's stale replacement")
        ;; The fence does not poison the successor's own replacement.
        (reg! id :b identity)
        (reg! id :b (fn [n] n))
        (is (= [[:b id]] (mapv (juxt :id :frame) @observed)))
        (finally
          (unregister-witness!))))))

(deftest clear-flow-trace-listener-loss-fences-subsequent-listeners
  (let [id :flow.cleared.fence/subject]
    (rf/make-frame {:id id})
    (reg! id :a identity)
    (let [[hits observed] (fence-witness! id :rf.flow/cleared)]
      (try
        (is (= :a (rf/clear :flow :a {:frame id})))
        (is (= 1 @hits) "the already-entered delivery stands")
        (is (= [] @observed) "no later listener receives A's stale clear")
        ;; The fence does not poison the successor, whose own clear carries
        ;; its own payload.
        (reg! id :b identity)
        (rf/clear :flow :b {:frame id})
        (is (= [{:flow-id :b :path [:b] :frame id}] @observed))
        (finally
          (unregister-witness!))))))

(deftest reg-flow-replacement-evidence-is-per-frame-cross-host
  ;; Each frame decides replacement evidence from its own prior definition: the
  ;; same replacement in two frames emits once per frame, attributed to it, and
  ;; an identical reload emits nothing in either.
  (let [seen (atom [])
        f1   (fn [n] n)
        f2   (fn [n] n)]
    (rf.trace.tooling/register-listener!
      ::repl-recorder
      (fn [ev]
        (when (= :rf.registry/handler-replaced (:operation ev))
          (swap! seen conj (:tags ev)))))
    (try
      (rf/make-frame {:id :left})
      (rf/make-frame {:id :right})
      (doseq [f [f1 f2 f2] frame [:left :right]]
        (rf/reg-flow :shared {:frame frame :inputs [[:n]] :output-path [:out]} f))
      (is (= [{:kind :flow :id :shared :frame :left :different-fn? true}
              {:kind :flow :id :shared :frame :right :different-fn? true}]
             @seen))
      (finally
        (rf.trace.tooling/unregister-listener! ::repl-recorder)))))
