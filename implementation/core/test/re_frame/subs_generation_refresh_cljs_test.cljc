(ns re-frame.subs-generation-refresh-cljs-test
  "A frame's cached subscriptions REFRESH when its resolved image generation
  changes, with no `clear-sub-cache!` (EP-0023 §Hot Reload).

  Both generation writers — `make-frame` against an existing `:id`, and the
  `reg-*` reprojection swap — preserve the frame's `:sub-cache` atom, and a
  cache HIT never compares its entry against the current generation. So
  `invalidate-subs-for-generation-change!` diffs the two generations and evicts
  the `:added` / `:changed` / `:removed` sub ids plus their declared-input
  dependent closure from THAT frame's cache; `:retained` entries keep their
  identity and ref-counts.

  `.cljc`, posture-independent: runs under `clojure -M:test`, the production
  gate and `npm run test:cljs`."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.image :as rf.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  ;; Destroy first: `init!` handed a DIFFERENT adapter than the seated one
  ;; raises, and this ns shares the node bundle with suites that seat Reagent,
  ;; UIx and SSR.
  (rf/destroy-adapter!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- sub-image
  "An image of inline layer-1 subs, each `[id value]` returning `value`."
  [image-id & id-values]
  (rf.image/image {:id            image-id
                   :registrations {:reg-sub (vec (for [[id v] (partition 2 id-values)]
                                                   [id (fn [_db _q] v)]))}}))

(defn- install!
  "Create, or on a repeat call SURGICALLY REPLACE the generation of, a frame
  sealed from `images` alone (the empty descriptor pool keeps the live source
  store out)."
  [frame-id & images]
  (rf.live-frame/make-frame {:id frame-id :images (vec images)} []))

(defn- read-sub [frame-id query-v]
  @(rf.subs/subscribe query-v {:frame frame-id}))

(defn- cached? [frame-id query-v]
  (contains? @(:sub-cache (rf.frame/frame frame-id)) query-v))

(deftest same-id-remake-refreshes-a-changed-inline-sub
  (install! :gen/frame (sub-image :gen/v1 :gen/value 1))
  (let [before (read-sub :gen/frame [:gen/value])]
    (install! :gen/frame (sub-image :gen/v2 :gen/value 2))
    (is (= [1 2] [before (read-sub :gen/frame [:gen/value])]))))

(deftest same-id-remake-refreshes-a-changed-declared-input-parent
  ;; The parent's declared-input closure must be evicted too, or the cached
  ;; parent keeps the generation-1 child reaction by closure.
  (let [image (fn [id child-value]
                (rf.image/image
                  {:id            id
                   :registrations {:reg-sub [[:gen/child (fn [_db _q] child-value)]
                                             [:gen/parent
                                              {:inputs [[:gen/child]]}
                                              (fn [[c] _q] (* 10 c))]]}}))]
    (install! :gen/pframe (image :gen/p1 1))
    (let [before (read-sub :gen/pframe [:gen/parent])]
      (install! :gen/pframe (image :gen/p2 5))
      (is (= [10 50] [before (read-sub :gen/pframe [:gen/parent])])))))

(deftest first-registered-late-input-reaches-a-cached-parent
  ;; A missing declared input resolves to a nil-yielding reaction and the miss
  ;; is not cached, but the PARENT is. First-registering the input dirties the
  ;; default-image frame's projection, and the `:added` sub must evict that
  ;; parent.
  (rf/reg-sub :gen/parent-late {:inputs [[:gen/late]]} (fn [[x] _q] x))
  (rf.live-frame/make-frame {:id :gen/lframe})
  (let [before (read-sub :gen/lframe [:gen/parent-late])]
    (rf/reg-sub :gen/late (fn [_db _q] 7))
    (is (= [nil 7] [before (read-sub :gen/lframe [:gen/parent-late])]))))

(deftest unchanged-entries-keep-identity-and-ref-counts
  ;; `:gen/stable` comes from ONE shared image value carried into both
  ;; compositions, so its descriptor is identical across the swap and the diff
  ;; classes it `:retained`. (The same body declared in two separate images
  ;; differs on `:rf.provenance/image`.)
  (let [shared (sub-image :gen/shared :gen/stable :stable)]
    (install! :gen/sframe shared (sub-image :gen/s1 :gen/moving :before))
    (let [stable (rf.subs/subscribe [:gen/stable] {:frame :gen/sframe})]
      ;; A second holder, so the ref-count is observably 2.
      (rf.subs/subscribe [:gen/stable] {:frame :gen/sframe})
      (read-sub :gen/sframe [:gen/moving])
      (install! :gen/sframe shared (sub-image :gen/s2 :gen/moving :after))
      (is (= [2 true false :after]
             [(get-in @(:sub-cache (rf.frame/frame :gen/sframe)) [[:gen/stable] :ref-count])
              (identical? stable (rf.subs/subscribe [:gen/stable] {:frame :gen/sframe}))
              (cached? :gen/sframe [:gen/moving])
              (read-sub :gen/sframe [:gen/moving])])))))

(deftest other-frames-are-untouched-by-a-generation-change
  (install! :gen/a (sub-image :gen/a1 :gen/value 1))
  (install! :gen/b (sub-image :gen/b1 :gen/value 100))
  (let [b1 (rf.subs/subscribe [:gen/value] {:frame :gen/b})]
    (install! :gen/a (sub-image :gen/a2 :gen/value 2))
    (is (= [true 100 2]
           [(identical? b1 (rf.subs/subscribe [:gen/value] {:frame :gen/b})) @b1
            (read-sub :gen/a [:gen/value])]))))

(deftest a-removed-sub-is-evicted-and-recovers-as-a-miss
  (install! :gen/rframe (sub-image :gen/r1 :gen/kept :kept :gen/gone :here))
  (read-sub :gen/rframe [:gen/gone])
  (install! :gen/rframe (sub-image :gen/r2 :gen/kept :kept))
  (is (= [false nil] [(cached? :gen/rframe [:gen/gone]) (read-sub :gen/rframe [:gen/gone])])))
