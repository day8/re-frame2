(ns day8.re-frame2-causa.popover.causality-graph-cljs-test
  "Pure-data tests for the Causality popover graph helpers (rf2-dqnuu).

  ## `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `panels/causality_graph_cljs_test.cljc`:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

  1. **build-payload** — projects an enriched-cascade vector + a
     focused dispatch-id into the `{:focused :ancestors :descendants
     :nodes :edges :truncated? :empty?}` shape the popover view
     consumes. Roots / orphans / aged-out focus all surface as
     documented.

  2. **ancestor-chain** — caps at `ancestor-cap`; cycle-safe; surfaces
     root-first ordering.

  3. **descendants-tree** — caps per-level breadth at
     `descendants-breadth-cap`; tracks the clipped count under the
     `:truncated` map so the caller can surface the disclosure.

  4. **->elk-graph** — projects the payload into the ELK JSON shape;
     direction switches between LR (RIGHT) and TB (DOWN)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.popover.causality-graph-helpers :as h]
            [day8.re-frame2-causa.panels.causality-graph-helpers   :as panel-h]))

;; ---- fixture builders ---------------------------------------------------

(defn- mk-cascade
  "Construct a minimal cascade record with the event-trace stitched in
  as `enrich-cascades` would. `parent` may be nil for a root."
  ([id event] (mk-cascade id event nil))
  ([id event parent]
   {:dispatch-id id
    :event       event
    :handler     nil
    :fx          nil
    :effects     []
    :subs        []
    :renders     []
    :other       []
    :event-trace {:op-type   :event
                  :operation :event/dispatched
                  :tags      (cond-> {:dispatch-id id :origin :app}
                               parent (assoc :parent-dispatch-id parent))}}))

(def fixture-cascades
  ;; A small lineage:
  ;;   1 (root) → 2 → 3 (focused) → 4
  ;;                              → 5
  ;;            → 6
  [(mk-cascade 1 [:app/init])
   (mk-cascade 2 [:user/login] 1)
   (mk-cascade 3 [:cart/restored] 2)
   (mk-cascade 4 [:order/submit] 3)
   (mk-cascade 5 [:notify] 3)
   (mk-cascade 6 [:analytics] 1)])

;; ---- build-payload — empty + happy path --------------------------------

(deftest build-payload-empty-when-no-focus
  (testing "nil focused-id → empty payload"
    (let [p (h/build-payload fixture-cascades nil)]
      (is (true? (:empty? p)))
      (is (nil?  (:focused p)))
      (is (= [] (:ancestors p)))
      (is (= [] (:nodes p)))
      (is (= [] (:edges p))))))

(deftest build-payload-empty-when-focused-id-not-in-cascades
  (testing "focused-id missing from the cascade list → empty payload"
    (let [p (h/build-payload fixture-cascades 9999)]
      (is (true? (:empty? p)))
      (is (nil?  (:focused p))))))

(deftest build-payload-roots-the-chain
  (testing "ancestor chain is root-first (root cause → focused parent)"
    (let [p   (h/build-payload fixture-cascades 3)
          ids (mapv :dispatch-id (:ancestors p))]
      (is (= [1 2] ids)
          "two ancestors: root #1, then #2 — in chronological
           (root-first) order"))))

(deftest build-payload-focused-is-singleton
  (testing "focused node is a single record carrying the :focused role"
    (let [p (h/build-payload fixture-cascades 3)
          f (:focused p)]
      (is (some? f))
      (is (= 3 (:dispatch-id f)))
      (is (= :focused (:role f)))
      (is (= [:cart/restored] (:event f))))))

(deftest build-payload-descendants-flatten
  (testing "descendants flatten level-by-level under the :nodes vector"
    (let [p   (h/build-payload fixture-cascades 3)
          ids (->> (get-in p [:descendants :nodes])
                   (map :dispatch-id)
                   set)]
      (is (= #{4 5} ids)
          "two direct children of #3: #4 and #5"))))

(deftest build-payload-all-nodes-in-edges
  (testing ":nodes contains every projected role (ancestors + focused +
            descendants); :edges contains parent→child for each ancestor
            link AND each focused→descendant link"
    (let [p (h/build-payload fixture-cascades 3)
          node-ids (set (map :dispatch-id (:nodes p)))]
      (is (= #{1 2 3 4 5} node-ids))
      (is (contains? (set (:edges p)) [1 2]))
      (is (contains? (set (:edges p)) [2 3]))
      (is (contains? (set (:edges p)) [3 4]))
      (is (contains? (set (:edges p)) [3 5])))))

(deftest build-payload-orphan-focus-renders-with-descendants
  (testing "focusing on a true root surfaces no ancestors but the full
            descendants tree"
    (let [p (h/build-payload fixture-cascades 1)]
      (is (= [] (:ancestors p)))
      (is (some? (:focused p)))
      (is (= 1 (-> p :focused :dispatch-id)))
      (is (pos? (count (get-in p [:descendants :nodes]))))
      (is (= #{2 6} (->> (get-in p [:descendants :levels])
                         first
                         set))
          "level-0 children are #2 and #6"))))

;; ---- ancestor-chain caps -------------------------------------------------

(deftest ancestor-chain-respects-cap
  (testing "ancestor-chain caps the walk at `ancestor-cap` records"
    ;; Build a deep chain — 12 ancestors of #100.
    (let [n     12
          chain (vec (for [i (range 1 (inc n))]
                       (mk-cascade i [:e i] (when (> i 1) (dec i)))))
          ;; focused-id = n+1 with parent = n
          full  (conj chain (mk-cascade (inc n) [:focus] n))
          pmap  (#'h/parent-by-id full)
          got   (h/ancestor-chain pmap (inc n))]
      (is (= h/ancestor-cap (count got))
          "only `ancestor-cap` records surface; deeper chain is clipped")
      (is (= (vec (take h/ancestor-cap (range (- n h/ancestor-cap -1) (inc n))))
             got)
          "the closest `ancestor-cap` ancestors are kept (root-first)"))))

(deftest ancestor-chain-cycle-safe
  (testing "a parent-cycle (defensive — never seen in production) doesn't
            crash the walker"
    (let [pmap {:a :b :b :a}
          got  (h/ancestor-chain pmap :a)]
      (is (vector? got)))))

(deftest build-payload-truncated-flag-set-when-chain-long
  (testing ":truncated? :ancestors? flips true when the chain exceeds
            the cap"
    (let [n     12
          chain (vec (for [i (range 1 (inc n))]
                       (mk-cascade i [:e i] (when (> i 1) (dec i)))))
          full  (conj chain (mk-cascade (inc n) [:focus] n))
          p     (h/build-payload full (inc n))]
      (is (true? (get-in p [:truncated? :ancestors?]))))))

;; ---- descendants-tree caps ----------------------------------------------

(deftest descendants-tree-breadth-capped
  (testing "descendants-tree caps each level's per-parent breadth at
            `descendants-breadth-cap`"
    (let [n          (+ h/descendants-breadth-cap 3)
          ;; one root #0 with N children
          fixture    (into [(mk-cascade 0 [:root])]
                           (for [i (range 1 (inc n))]
                             (mk-cascade i [:child i] 0)))
          cmap       (#'h/children-by-parent fixture)
          tree       (h/descendants-tree cmap 0)
          level-0    (first (:levels tree))]
      (is (= h/descendants-breadth-cap (count level-0))
          "only `descendants-breadth-cap` children surface")
      (is (= 3 (get-in tree [:truncated 0]))
          "the truncated count records the clip: 3 children dropped"))))

(deftest descendants-tree-empty-when-no-children
  (testing "a terminal cascade returns no levels"
    (let [tree (h/descendants-tree {} 42)]
      (is (= [] (:levels tree))))))

;; ---- ELK projection ------------------------------------------------------

(deftest ->elk-graph-direction-switches-on-axis
  (testing "->elk-graph emits the right `elk.direction` per axis"
    (let [p   (h/build-payload fixture-cascades 3)
          lr  (h/->elk-graph p :lr)
          tb  (h/->elk-graph p :tb)]
      (is (= "RIGHT" (get-in lr [:layoutOptions "elk.direction"])))
      (is (= "DOWN"  (get-in tb [:layoutOptions "elk.direction"]))))))

(deftest ->elk-graph-one-child-per-node
  (testing "every node in the payload becomes one ELK :children entry"
    (let [p     (h/build-payload fixture-cascades 3)
          graph (h/->elk-graph p :tb)]
      (is (= (count (:nodes p)) (count (:children graph)))))))

(deftest ->elk-graph-edges-mirror-payload
  (testing "every edge in the payload becomes one ELK :edges entry,
            with source/target reading the from→to ids as strings"
    (let [p     (h/build-payload fixture-cascades 3)
          graph (h/->elk-graph p :tb)]
      (is (= (count (:edges p)) (count (:edges graph))))
      (let [first-e  (first (:edges graph))]
        (is (string? (:id first-e)))
        (is (vector? (:sources first-e)))
        (is (vector? (:targets first-e)))))))
