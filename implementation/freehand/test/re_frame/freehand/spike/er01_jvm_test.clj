(ns re-frame.freehand.spike.er01-jvm-test
  "SPIKE SCAFFOLDING — ER-01. Deleted before this bead's PR."
  (:require [clojure.pprint :as pp]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.spike.er01.cliff :as cliff]
            [re-frame.freehand.spike.er01.compiled :as arm-c]
            [re-frame.freehand.spike.er01.interpreted :as arm-i]
            [re-frame.freehand.spike.er01.vdollar :as arm-d]
            [re-frame.freehand.spike.er01.vdollar-keyed :as arm-dk]
            [re-frame.freehand.tree :as tree]))

(def arm-namespaces
  #{"re-frame.freehand.spike.er01.interpreted"
    "re-frame.freehand.spike.er01.compiled"
    "re-frame.freehand.spike.er01.vdollar"
    "re-frame.freehand.spike.er01.vdollar-keyed"})

(defn mode-neutral [t]
  (walk/postwalk
    (fn [x]
      (if (and (keyword? x) (contains? arm-namespaces (namespace x)))
        (keyword "re-frame.freehand.spike.er01" (name x))
        x))
    t))

(defn fixture [rows cols]
  {:cols cols
   :rows (mapv (fn [i] {:id (str "r" i) :index i}) (range rows))})

(defn node-count [t] (count (tree-seq map? :children t)))

(deftest three-arms-render-the-same-tree
  (doseq [[rows cols] [[1 1] [3 4] [40 8] [200 8]]]
    (let [f (fixture rows cols)
          i (mode-neutral (tree/render [arm-i/table f]))
          c (mode-neutral (tree/render [arm-c/table f]))
          d (mode-neutral (tree/render [arm-d/table f]))
          k (mode-neutral (tree/render [arm-dk/table f]))]
      (testing (str rows "x" cols)
        (is (= i c) "interpreted vs compiled")
        (is (= i d) "interpreted vs $")
        (is (= i k) "interpreted vs $ + keyed-run")))))

;; ---------------------------------------------------------------------------
;; Measurement — allocation is the headline, wall clock is a direction
;; ---------------------------------------------------------------------------

(defn- render-arm [view f]
  (let [a0 (m/allocated-bytes)
        t0 (m/now-ms)
        t  (tree/render [view f])
        t1 (m/now-ms)
        a1 (m/allocated-bytes)]
    {:tree t :ms (- t1 t0) :bytes (- a1 a0)}))

(defn measure
  "Interleaved arms, warm-up then System/gc immediately before each timed
  window, `samples` measured iterations."
  [{:keys [rows cols warmup samples]}]
  (let [f    (fixture rows cols)
        arms [[:interpreted arm-i/table] [:compiled arm-c/table]
              [:dollar arm-d/table] [:dollar+keyed arm-dk/table]]]
    (dotimes [_ warmup] (doseq [[_ v] arms] (render-arm v f)))
    (let [acc (reduce
                (fn [acc _]
                  (reduce (fn [acc [id v]]
                            (System/gc)
                            (let [r (render-arm v f)]
                              (-> acc
                                  (update-in [id :ms] conj (:ms r))
                                  (update-in [id :bytes] conj (:bytes r))
                                  (assoc-in [id :nodes] (node-count (:tree r))))))
                          acc arms))
                {} (range samples))]
      (into {}
            (map (fn [[id {:keys [ms bytes nodes]}]]
                   [id {:nodes nodes
                        :ms    (m/summarise ms)
                        :bytes (m/summarise bytes)}]))
            acc))))

(deftest ^:er01 report
  (let [cases [{:rows 40 :cols 8 :warmup 200 :samples 60}
               {:rows 200 :cols 8 :warmup 100 :samples 40}]]
    (doseq [{:keys [rows cols] :as c} cases]
      (let [r (measure c)]
        (println)
        (println (str ";; ER-01 " rows "x" cols "  nodes=" (:nodes (:interpreted r))
                      "  warmup=" (:warmup c) " samples=" (:samples c)))
        (doseq [id [:interpreted :compiled :dollar :dollar+keyed]]
          (let [bs (:bytes (get r id))]
            (println (format ";;   %-13s bytes min %,10.0f p50 %,10.0f max %,10.0f (spread %5.2f%%)  ms p50 %7.4f p95 %7.4f"
                             (name id)
                             (double (:min bs)) (double (:p50 bs)) (double (:max bs))
                             (* 100.0 (/ (- (double (:max bs)) (double (:min bs)))
                                         (max 1.0 (double (:p50 bs)))))
                             (double (:p50 (:ms (get r id))))
                             (double (:p95 (:ms (get r id))))))))
        (let [b #(double (:p50 (:bytes (get r %))))]
          (println (format ";;   bytes vs interpreted: compiled %.3f   $ %.3f   $+keyed %.3f"
                           (/ (b :compiled) (b :interpreted))
                           (/ (b :dollar) (b :interpreted))
                           (/ (b :dollar+keyed) (b :interpreted))))
          (println (format ";;   $+keyed vs compiled: %.3f" (/ (b :dollar+keyed) (b :compiled)))))))
    (is true)))

;; ---------------------------------------------------------------------------
;; D010's value-vs-syntax cliff — the authoring-friction axis
;; ---------------------------------------------------------------------------

(deftest ^:er01 the-cliff
  (testing "the helper-returning-markup body renders identically in arm I and arm $"
    (let [f (fixture 3 4)
          ;; the two declarations have different NAMES, so the boundary
          ;; view-id is the one thing they cannot share — drop it and
          ;; compare everything else verbatim.
          n #(walk/postwalk (fn [x] (if (map? x) (dissoc x :view-id) x)) %)]
      (is (= (n (tree/render [cliff/interpreted-table f]))
             (n (tree/render [cliff/dollar-table f]))))))
  (testing "the compiled twin refuses at build time"
    (println)
    (println ";; ER-01 cliff — {:compiled true} over a helper-returning-markup cell:")
    (pp/pprint cliff/compiled-refusal)
    (is (false? (:compiled? cliff/compiled-refusal)))))

;; ---------------------------------------------------------------------------
;; The evidence plane — what each arm's declaration makes statically knowable
;; ---------------------------------------------------------------------------

(deftest ^:er01 evidence-plane
  (println)
  (println ";; ER-01 manifests")
  (doseq [[id view] [[:interpreted arm-i/table] [:compiled arm-c/table] [:dollar arm-d/table]]]
    (let [mf (v/manifest view)]
      (println (format ";;   %-12s manifest %s  view-cell %s"
                       (name id)
                       (if mf "present" "ABSENT")
                       (pr-str (:view-cell mf))))))
  (is (some? (v/manifest arm-c/table)) "compiled has a manifest")
  (is (nil? (v/manifest arm-i/table)) "interpreted has none")
  (is (nil? (v/manifest arm-d/table)) "$ has none"))

(deftest ^:er01 the-two-front-ends-are-disjoint
  (println)
  (println ";; ER-01 — {:compiled true} over a $ body:")
  (pp/pprint cliff/compiled-over-dollar)
  (is (false? (:compiled? cliff/compiled-over-dollar))
      "a $ body cannot be compiled — the fork is exclusive, not a dial"))
