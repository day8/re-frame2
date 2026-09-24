(ns day8.re-frame2-xray.panels.reactive-flow-graph-cljs-test
  "Pure-data tests for the reactive-flow graph layout (rf2-ad7zx.6 ·
  spec/021 §3.2 · Figma `ViewsPanel`).

  Covers `shared-sub-set` (the shared-subscription detector) and `layout`
  (the node + edge geometry the Views panel renders as inline SVG).
  JVM-runnable — no re-frame frame, no browser."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.panels.reactive-flow-graph :as g]))

;; ---- shared-sub-set ----------------------------------------------------

(deftest shared-sub-set-detects-multi-reader-subs
  (testing "a sub read by ≥2 views is in the shared set; a 0/1-reader sub
            is not"
    (let [subs [{:sub-id :a :readers [:v1 :v2]}
                {:sub-id :b :readers [:v1]}
                {:sub-id :c :readers []}
                {:sub-id :d :readers [:v1 :v2 :v3]}]]
      (is (= #{:a :d} (g/shared-sub-set subs))))))

(deftest shared-sub-set-nil-safe
  (is (= #{} (g/shared-sub-set [])))
  (is (= #{} (g/shared-sub-set [{:sub-id :x}]))))

;; ---- layout: empty -----------------------------------------------------

(deftest layout-empty-when-no-cascade
  (testing "no subs + no views → :empty? true"
    (let [out (g/layout {})]
      (is (:empty? out))
      (is (= [] (-> out :nodes :l1)))
      (is (= [] (-> out :nodes :l2)))
      (is (= [] (-> out :nodes :view)))
      (is (= [] (:edges out))))))

(deftest layout-empty-with-only-unmount-rows
  (testing "view-rows that are all unmounts don't populate the graph"
    (let [out (g/layout {:view-rows [{:view-id :v :action :unmount}]})]
      (is (:empty? out))
      (is (= [] (-> out :nodes :view))))))

;; ---- layout: nodes -----------------------------------------------------

(deftest layout-builds-app-db-source-node
  (testing "app-db source node sits at the left edge with stable geometry"
    (let [out (g/layout {:level-1-subs [{:sub-id :a :changed? true}]})]
      (is (number? (-> out :appdb :x)))
      (is (number? (-> out :appdb :y)))
      (is (= g/node-h (-> out :appdb :h))))))

(deftest layout-columns-are-ordered-left-to-right
  (testing "app-db < L1 < L2 < view in x"
    (let [out (g/layout {:level-1-subs [{:sub-id :l1 :changed? true}]
                         :level-2-subs [{:sub-id :l2 :changed? true :inputs [:l1]}]
                         :view-rows    [{:view-id :v :action :rerender}]})
          l1x (-> out :nodes :l1 first :x)
          l2x (-> out :nodes :l2 first :x)
          vx  (-> out :nodes :view first :x)]
      (is (< (-> out :appdb :x) l1x))
      (is (< l1x l2x))
      (is (< l2x vx)))))

(deftest layout-preserves-changed-flags-on-nodes
  (testing "node :changed? mirrors the input row"
    (let [out (g/layout {:level-1-subs [{:sub-id :hot :changed? true}
                                        {:sub-id :cold :changed? false}]})
          nodes (-> out :nodes :l1)]
      (is (true? (:changed? (first nodes))))
      (is (false? (:changed? (second nodes)))))))

(deftest layout-view-node-carries-cause-and-timing
  (testing "rf2-8wrzz.1 — the view node threads :triggered-by + :elapsed-ms"
    (let [out (g/layout {:view-rows [{:view-id :v :action :rerender
                                      :triggered-by :sub/x :elapsed-ms 1.5}]})
          vn  (-> out :nodes :view first)]
      (is (= :sub/x (:triggered-by vn)))
      (is (= 1.5 (:elapsed-ms vn)))
      (is (= :rerender (:action vn))))))

(deftest layout-marks-shared-sub-count
  (testing "a sub read by ≥2 views carries :shared-count"
    (let [out (g/layout {:level-1-subs [{:sub-id :s :changed? true
                                         :readers [:v1 :v2]}]})
          n   (-> out :nodes :l1 first)]
      (is (= 2 (:shared-count n))))))

(deftest layout-no-shared-count-for-single-reader
  (let [out (g/layout {:level-1-subs [{:sub-id :s :changed? true :readers [:v1]}]})]
    (is (nil? (:shared-count (-> out :nodes :l1 first))))))

;; ---- layout: edges -----------------------------------------------------

(deftest layout-app-db-fans-out-to-each-level-1
  (testing "one app-db → L1 edge per Level-1 sub (plain fan-out)"
    (let [out (g/layout {:level-1-subs [{:sub-id :a :changed? true}
                                        {:sub-id :b :changed? false}]})
          appdb-edges (filter #(= :appdb-l1 (:kind %)) (:edges out))]
      (is (= 2 (count appdb-edges)))
      (is (every? #(= :appdb (:from-id %)) appdb-edges)))))

(deftest layout-app-db-edge-changed-tracks-target-sub
  (testing "the app-db→L1 edge :changed? mirrors the target sub's state
            (changed propagates, unchanged is cut)"
    (let [out (g/layout {:level-1-subs [{:sub-id :a :changed? true}
                                        {:sub-id :b :changed? false}]})
          by-to (into {} (map (juxt :to-id identity))
                      (filter #(= :appdb-l1 (:kind %)) (:edges out)))]
      (is (true? (:changed? (get by-to :a))))
      (is (false? (:changed? (get by-to :b)))))))

(deftest layout-level-2-edges-wire-from-input-subs
  (testing "a Level-2 sub draws an edge from each of its input subs;
            edge :changed? tracks the UPSTREAM input"
    (let [out (g/layout {:level-1-subs [{:sub-id :in :changed? true}]
                         :level-2-subs [{:sub-id :derived :changed? true
                                         :inputs [:in]}]})
          sub-sub (filter #(= :sub-sub (:kind %)) (:edges out))]
      (is (= 1 (count sub-sub)))
      (is (= :in (:from-id (first sub-sub))))
      (is (= :derived (:to-id (first sub-sub))))
      (is (true? (:changed? (first sub-sub)))))))

(deftest layout-sub-view-edges-from-readers
  (testing "a sub draws an edge to each view in its :readers; a shared
            sub fans out to N view edges"
    (let [out (g/layout {:level-1-subs [{:sub-id :s :changed? true
                                         :readers [:v1 :v2]}]
                         :view-rows    [{:view-id :v1 :action :rerender}
                                        {:view-id :v2 :action :rerender}]})
          sub-view (filter #(= :sub-view (:kind %)) (:edges out))]
      (is (= 2 (count sub-view)))
      (is (= #{:v1 :v2} (set (map :to-id sub-view)))))))

(deftest layout-edges-have-numeric-endpoints
  (testing "every edge carries numeric x1/y1/x2/y2 so the SVG paints"
    (let [out (g/layout {:level-1-subs [{:sub-id :a :changed? true :readers [:v]}]
                         :view-rows    [{:view-id :v :action :rerender}]})]
      (is (seq (:edges out)))
      (is (every? (fn [e] (every? number? [(:x1 e) (:y1 e) (:x2 e) (:y2 e)]))
                  (:edges out))))))

;; ---- layout: instances (rf2-3x7nj.24.3) -------------------------------
;;
;; The canonical list shape: N instances of one view, each reading its own
;; cell of one parametric sub. Keying nodes by registration id kept only the
;; LAST instance per id, so every sub→view edge landed on the last view box,
;; the other N-1 floated with no incoming edge, and React got N siblings with
;; one key. The fixture is the shape the panel projection produces — one sub
;; row per `:sub-runs` query-v, one view row per `:rf.view/rendered` op with
;; its render-key and read-set.

(def ^:private todo-list
  {:level-1-subs [{:sub-id :todo/by-id :query-v [:todo/by-id 1] :changed? true
                   :readers [:app/todo-row]}
                  {:sub-id :todo/by-id :query-v [:todo/by-id 2] :changed? false
                   :readers [:app/todo-row]}
                  {:sub-id :todo/by-id :query-v [:todo/by-id 3] :changed? false
                   :readers [:app/todo-row]}]
   :view-rows    [{:view-id :app/todo-row :render-key [:app/todo-row 11]
                   :action :rerender :deref-subs [[:todo/by-id 1]]}
                  {:view-id :app/todo-row :render-key [:app/todo-row 12]
                   :action :rerender :deref-subs [[:todo/by-id 2]]}
                  {:view-id :app/todo-row :render-key [:app/todo-row 13]
                   :action :rerender :deref-subs [[:todo/by-id 3]]}]})

(deftest layout-instances-carry-distinct-keys
  (testing "each instance is its own node with its own React key; the
            registration id still labels, links and slugs it"
    (let [out   (g/layout todo-list)
          l1    (-> out :nodes :l1)
          views (-> out :nodes :view)]
      (is (= (mapv pr-str [[:todo/by-id 1] [:todo/by-id 2] [:todo/by-id 3]])
             (mapv :key l1))
          "three sub instances, each keyed by its query-v")
      (is (= (mapv pr-str [[:app/todo-row 11] [:app/todo-row 12] [:app/todo-row 13]])
             (mapv :key views))
          "three view instances, each keyed by its render-key")
      (is (every? #(= :todo/by-id (:id %)) l1) ":id stays the registration id")
      (is (= ["[:todo/by-id 1]" "[:todo/by-id 2]" "[:todo/by-id 3]"] (mapv :label l1))
          "a parameterized instance is labelled by its query-v")))
  (testing "one identity seen twice (a query-v run twice) still gets two
            sibling keys"
    (let [l1 (-> (g/layout {:level-1-subs [{:sub-id :a :query-v [:a] :changed? true}
                                           {:sub-id :a :query-v [:a] :changed? false}]})
                 :nodes :l1)]
      (is (= 2 (count (distinct (map :key l1))))))))

(deftest layout-routes-each-sub-instance-to-its-own-view-instance
  (testing "the sub→view edge joins the sub instance to the view instance
            whose read-set holds its query-v — one edge per view box"
    (let [out      (g/layout todo-list)
          sub-view (filter #(= :sub-view (:kind %)) (:edges out))
          by-key   (into {} (map (juxt :key identity)) (-> out :nodes :view))
          centre   (fn [n] (+ (:y n) (/ (:h n) 2.0)))]
      (is (= 3 (count sub-view)))
      (is (= #{[(pr-str [:todo/by-id 1]) (pr-str [:app/todo-row 11])]
               [(pr-str [:todo/by-id 2]) (pr-str [:app/todo-row 12])]
               [(pr-str [:todo/by-id 3]) (pr-str [:app/todo-row 13])]}
             (set (map (juxt :from-key :to-key) sub-view)))
          "instance i drives view instance i")
      (is (= (set (map centre (-> out :nodes :view)))
             (set (map :y2 sub-view)))
          "every view box receives an edge — none floats")
      (is (every? #(= (:y2 %) (centre (get by-key (:to-key %)))) sub-view)
          "each edge ends on the box it names"))))

(deftest layout-level-2-edge-starts-at-the-declared-input-instance
  (testing "a Level-2 row carrying its declared input query-vs draws its
            input edge from THAT instance, not from the last instance of
            the input's registration"
    (let [out (g/layout {:level-1-subs [{:sub-id :todo/by-id :query-v [:todo/by-id 1] :changed? true}
                                        {:sub-id :todo/by-id :query-v [:todo/by-id 2] :changed? false}]
                         :level-2-subs [{:sub-id :todo/first-title :query-v [:todo/first-title]
                                         :changed? true :inputs [:todo/by-id]
                                         :input-query-vs [[:todo/by-id 1]]}]})
          sub-sub (filter #(= :sub-sub (:kind %)) (:edges out))]
      (is (= [(pr-str [:todo/by-id 1])] (mapv :from-key sub-sub)))
      (is (= [:todo/by-id] (mapv :from-id sub-sub))
          ":from-id stays the registration id"))))

(deftest layout-width-and-height-positive
  (let [out (g/layout {:level-1-subs [{:sub-id :a :changed? true}]
                       :view-rows    [{:view-id :v :action :rerender}]})]
    (is (pos? (:width out)))
    (is (pos? (:height out)))))
