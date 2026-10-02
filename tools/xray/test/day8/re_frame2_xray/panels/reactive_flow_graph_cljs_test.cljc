(ns day8.re-frame2-xray.panels.reactive-flow-graph-cljs-test
  "Pure-data tests for the reactive-flow graph layout (spec/021 §3.2 ·
  Figma `ViewsPanel`).

  Covers `layout` (the node + edge geometry the Views panel renders as
  inline SVG), including the shared-subscription detector `shared-sub-set`
  through the `:shared-count` it stamps on each node.
  JVM-runnable — no re-frame frame, no browser."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test    :refer-macros [are deftest is testing]])
            [day8.re-frame2-xray.panels.reactive-flow-graph :as g]))

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

(deftest layout-view-node-carries-cause-and-timing
  (testing "the view node threads :triggered-by + :elapsed-ms"
    (let [out (g/layout {:view-rows [{:view-id :v :action :rerender
                                      :triggered-by :sub/x :elapsed-ms 1.5}]})
          vn  (-> out :nodes :view first)]
      (is (= :sub/x (:triggered-by vn)))
      (is (= 1.5 (:elapsed-ms vn)))
      (is (= :rerender (:action vn))))))

(deftest layout-marks-shared-count-only-on-multi-reader-subs
  (testing "a sub read by two or more views carries :shared-count, its
            reader count; a sub with no, empty or single readers carries none"
    (are [readers expected]
         (= expected
            (let [out (g/layout {:level-1-subs [(cond-> {:sub-id :s :changed? true}
                                                  (some? readers) (assoc :readers readers))]})]
              (:shared-count (-> out :nodes :l1 first))))
      nil           nil
      []            nil
      [:v1]         nil
      [:v1 :v2]     2
      [:v1 :v2 :v3] 3)))

;; ---- layout: edges -----------------------------------------------------

(deftest layout-app-db-edge-changed-tracks-target-sub
  (testing "app-db fans out one edge to each Level-1 sub, in sub order, and
            each edge's :changed? mirrors its target sub's state (changed
            propagates, unchanged is cut)"
    (let [out (g/layout {:level-1-subs [{:sub-id :a :changed? true}
                                        {:sub-id :b :changed? false}]})]
      (is (= [{:from-id :appdb :to-id :a :changed? true}
              {:from-id :appdb :to-id :b :changed? false}]
             (->> (:edges out)
                  (filter #(= :appdb-l1 (:kind %)))
                  (mapv #(select-keys % [:from-id :to-id :changed?]))))))))

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

;; ---- layout: instances ------------------------------------------------
;;
;; The canonical list shape: N instances of one view, each reading its own
;; cell of one parametric sub. Keying nodes by registration id would keep
;; only the LAST instance per id, so every sub→view edge would land on the
;; last view box, the other N-1 would float with no incoming edge, and React
;; would get N siblings with one key. The fixture is the shape the panel projection produces — one sub
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
            registration id labels, links and slugs it"
    (let [out   (g/layout todo-list)
          l1    (-> out :nodes :l1)
          views (-> out :nodes :view)]
      (is (= (mapv pr-str [[:todo/by-id 1] [:todo/by-id 2] [:todo/by-id 3]])
             (mapv :key l1))
          "three sub instances, each keyed by its query-v")
      (is (= (mapv pr-str [[:app/todo-row 11] [:app/todo-row 12] [:app/todo-row 13]])
             (mapv :key views))
          "three view instances, each keyed by its render-key")
      (is (every? #(= :todo/by-id (:id %)) l1) ":id is the registration id")
      (is (= ["[:todo/by-id 1]" "[:todo/by-id 2]" "[:todo/by-id 3]"] (mapv :label l1))
          "a parameterized instance is labelled by its query-v")))
  (testing "one identity seen twice (a query-v run twice) gets two
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
          ":from-id is the registration id"))))

(deftest layout-width-and-height-positive
  (let [out (g/layout {:level-1-subs [{:sub-id :a :changed? true}]
                       :view-rows    [{:view-id :v :action :rerender}]})]
    (is (pos? (:width out)))
    (is (pos? (:height out)))))

;; ---- labels fit their boxes --------------------------------------------

(deftest every-node-label-fits-its-box
  ;; SVG text does not clip to its <rect>, so a label wider than its node
  ;; paints across its neighbours. Measured independently of the layout:
  ;; 11px names and 9px meta lines at the mono stack's 0.6em advance,
  ;; inside a 3px inset each side. The ids are the standard-epochs
  ;; testbed's own, which overflowed their boxes.
  (let [advance 0.6
        fits?   (fn [s w font-size] (<= (* (count s) advance font-size) (- w 6)))
        out     (g/layout
                  {:level-1-subs [{:sub-id :cart/total :changed? true}
                                  {:sub-id :standard-epochs/greater-than?
                                   :query-v [:standard-epochs/greater-than? 5]
                                   :changed? true}]
                   :level-2-subs [{:sub-id :standard-epochs/chain-labelled
                                   :inputs [:cart/total] :changed? true}]
                   :view-rows    [{:view-id :standard-epochs.core/diamond-display
                                   :action :rerender}
                                  {:view-id :standard-epochs.core/child-a
                                   :action :rerender}]})
        nodes   (concat (-> out :nodes :l1) (-> out :nodes :l2) (-> out :nodes :view))]
    (is (= 5 (count nodes)))
    (doseq [n nodes
            :let [shown (or (:display-label n) (:label n))]]
      (is (fits? shown (:w n) 11)
          (str (pr-str shown) " fits its " (:w n) "px box")))

    (testing "a label that fits is shown whole; a longer one keeps its tail"
      (let [by-id (into {} (map (juxt :id identity)) nodes)]
        (is (= ":cart/total" (:display-label (by-id :cart/total))))
        (is (= "…s/chain-labelled"
               (:display-label (by-id :standard-epochs/chain-labelled))))
        (is (= ":standard-epochs.core/child-a"
               (:display-label (by-id :standard-epochs.core/child-a))))))

    (testing "a view's cause/timing line fits, whole when it is short"
      (let [line "(rerendered)  ← :cart/total · 2ms"]
        (is (fits? (g/fit-line line g/view-node-w 9) g/view-node-w 9))
        (is (= line (g/fit-line line g/view-node-w 9)))
        (is (= "(rerendered)  ← :standard-epochs/c…"
               (g/fit-line "(rerendered)  ← :standard-epochs/chain-labelled · 0.3ms"
                           g/view-node-w 9)))))))
