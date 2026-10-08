(ns re-frame.sub-cycle-cljs-test
  "A declared-input dependency cycle in the sub graph fails LOUD with a
  structured `:rf.error/sub-cycle` and recovers to nil, rather than blowing the
  host stack. A reaction is cached only after its inputs resolve, and the pure
  `compute-sub` memo only after the body runs, so the guard tracks the
  per-thread build stack (reactive) and the per-call memo (`compute-sub`) and
  detects the re-entry. Rides `npm run test:cljs` and `clojure -M:test`.

  The GUARD is production behaviour; its REPORT is not. `emit-sub-cycle!` is a
  bare dev `trace/emit-error!` with no always-on leg, so every row reading the
  event sits in a `(when rf.interop/debug-enabled? ...)` arm. Recovery, the
  cache miss, the released input ref and the acyclic diamond run under
  `scripts/test-core-prod-gate.sh`. The diamond's empty-event rows sit in the
  arm, because under the gate the stream is empty for every graph."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core           :as rf]
            [re-frame.frame          :as rf.frame]
            [re-frame.interop        :as rf.interop]
            [re-frame.subs           :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support   :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private fid :rf/default)

(defn- capture-sub-cycles
  "Run `thunk` with a `:trace` listener recording every `:rf.error/sub-cycle`
  event; return `[thunk-result recorded-events]`."
  [thunk]
  (let [recorded (atom [])]
    (rf/register-listener! :trace ::rec
      (fn [ev] (when (= :rf.error/sub-cycle (:operation ev))
                 (swap! recorded conj ev))))
    (try
      (let [result (thunk)]
        [result @recorded])
      (finally
        (rf/unregister-listener! :trace ::rec)))))

(defn- register-cyclic-subs! []
  (rf/reg-sub :a {:inputs [[:b]]} (fn [[b] _] b))
  (rf/reg-sub :b {:inputs [[:a]]} (fn [[a] _] a)))

(defn- cache [] @(:sub-cache (rf.frame/frame fid)))

(deftest reactive-two-node-cycle-emits-structured-error-and-recovers-to-nil
  (register-cyclic-subs!)
  (let [[reaction events] (capture-sub-cycles #(rf.subs/subscribe [:a] {:frame fid}))]
    (is (nil? (deref reaction)))
    (when rf.interop/debug-enabled?
      ;; The cycle path is the closing-repeat sub-id chain.
      (is (= [1 :error :subscribe [:a :b :a]]
             (let [{:keys [op-type tags]} (first events)]
               [(count events) op-type (:where tags) (:cycle tags)]))))))

(deftest reactive-cycle-recovery-is-not-cached
  ;; Neither node is cached, so a later subscribe re-detects rather than being
  ;; handed a broken cached reaction.
  (register-cyclic-subs!)
  (let [[_ events] (capture-sub-cycles
                     (fn []
                       (rf.subs/subscribe [:a] {:frame fid})
                       (rf.subs/subscribe [:a] {:frame fid})))]
    (is (= [nil nil] [(get (cache) [:a]) (get (cache) [:b])]))
    (when rf.interop/debug-enabled?
      (is (= 2 (count events)) "each subscribe re-emits"))))

(deftest reactive-multi-input-cycle-releases-earlier-input-refs
  ;; :multi's first input :leaf is acquired before its second input :cyc
  ;; cycles back to :multi. The abandoned build never wires its on-dispose,
  ;; so the unwind must release :leaf or it leaks one ref.
  (rf/reg-sub :leaf (fn [db _] (:leaf db 7)))
  (rf/reg-sub :cyc {:inputs [[:multi]]} (fn [[m] _] m))
  (rf/reg-sub :multi {:inputs [[:leaf] [:cyc]]} (fn [[l c] _] [l c]))
  (let [[reaction events] (capture-sub-cycles #(rf.subs/subscribe [:multi] {:frame fid}))]
    (is (= [nil 0] [(deref reaction) (get-in (cache) [[:leaf] :ref-count] 0)]))
    (when rf.interop/debug-enabled?
      (is (= [1 [:multi :cyc :multi]] [(count events) (:cycle (:tags (first events)))])))))

(deftest compute-sub-two-node-cycle-emits-structured-error-and-returns-nil
  (register-cyclic-subs!)
  (let [[v events] (capture-sub-cycles #(rf/compute-sub [:a] {}))]
    (is (nil? v))
    (when rf.interop/debug-enabled?
      (is (= [1 :compute-sub [:a :b :a]]
             (let [{:keys [tags]} (first events)]
               [(count events) (:where tags) (:cycle tags)]))))))

(deftest acyclic-diamond-does-not-trip-the-cycle-guard
  ;; :c over :a and :b, both over :root: the guard fires on genuine cycles only.
  (rf/reg-sub :root (fn [db _] (:root db 41)))
  (rf/reg-sub :a {:inputs [[:root]]} (fn [[r] _] (inc r)))
  (rf/reg-sub :b {:inputs [[:root]]} (fn [[r] _] (dec r)))
  (rf/reg-sub :c {:inputs [[:a] [:b]]} (fn [[a b] _] [a b]))
  (let [[reaction reactive-events] (capture-sub-cycles #(rf.subs/subscribe [:c] {:frame fid}))
        [v compute-events]         (capture-sub-cycles #(rf/compute-sub [:c] {:root 41}))]
    (is (= [[42 40] [42 40]] [(deref reaction) v]))
    (when rf.interop/debug-enabled?
      (is (= [[] []] [reactive-events compute-events])))))
