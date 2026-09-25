(ns day8.re-frame2-machines-viz.chart.root-projection-cljs-test
  "The chart projects the machine ROOT and each parallel REGION body the way
  it projects a state, for the slots the runtime reads on them.

  - The root's `:entry` / `:exit` / `:tags` ride the ROOT-CONTAINER frame,
    and a region body's ride its REGION container — the nodes that stand for
    them — in exactly the fields a state node carries, `:rf.cofx/requires`
    included.
  - The root's `:spawn` child draws the ✓ done / ✗ error completion edges a
    spawning state draws, from the machine-root chip, which is where every
    root-level transition leaves from."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]))

(defn- root-container [graph]
  (first (filter :root-container? (:nodes graph))))

(defn- node-with-id [graph id]
  (first (filter #(= id (:id %)) (:nodes graph))))

(def ^:private lifecycle-actions
  {:hello {:rf.cofx/requires [:rf/time-ms] :fn (fn [_ctx] nil)}
   :bye   (fn [_ctx] nil)
   :r-in  {:rf.cofx/requires [:rf/uuid] :fn (fn [_ctx] nil)}})

(def ^:private lifecycle-keys [:entry :exit :tags :entry-requires :exit-requires])

;; ---- the root's :entry / :exit / :tags ------------------------------------

(deftest root-container-carries-the-root-lifecycle
  (testing "a flat root's :entry / :exit / :tags ride the root-container frame
            in the fields a state declaring the same three slots carries"
    (let [m     {:initial :a
                 :entry   :hello
                 :exit    :bye
                 :tags    #{:busy}
                 :actions lifecycle-actions
                 :states  {:a    {:tags #{:a-tag}}
                           :twin {:entry :hello :exit :bye :tags #{:busy}}}}
          graph (layout/project-definition m)
          root  (root-container graph)
          twin  (node-with-id graph (layout/node-id [:twin]))]
      (is (= "hello" (:entry root)))
      (is (= "bye" (:exit root)))
      (is (= #{:busy} (:tags root)))
      (is (= ["rf/time-ms"] (:entry-requires root))
          "the root's :entry resolves its declared requirements")
      (is (= (select-keys twin lifecycle-keys) (select-keys root lifecycle-keys))
          "the root projects its lifecycle exactly as a state does")
      (is (= #{:a-tag} (:tags (node-with-id graph (layout/node-id [:a]))))
          "a state keeps its own tags; the root's stay on the root")))

  (testing "a parallel root's :entry / :exit / :tags ride the frame too"
    (let [m     {:type    :parallel
                 :entry   :hello
                 :exit    :bye
                 :tags    #{:busy}
                 :actions lifecycle-actions
                 :regions {:r {:initial :a :states {:a {}}}}}
          root  (root-container (layout/project-definition m))]
      (is (= "hello" (:entry root)))
      (is (= "bye" (:exit root)))
      (is (= #{:busy} (:tags root)))
      (is (= ["rf/time-ms"] (:entry-requires root))))))

(deftest root-container-without-lifecycle-carries-none
  (testing "a root declaring no lifecycle slot projects none, as a bare state does"
    (let [root (root-container (layout/project-definition {:initial :a :states {:a {}}}))]
      (is (some? root))
      (is (not (contains? root :entry)))
      (is (not (contains? root :exit)))
      (is (= #{} (:tags root))))))

(deftest root-lifecycle-reaches-the-renderer-data
  (testing "the frame's xyflow :data carries the root lifecycle in the keys a
            state node's :data carries it"
    (let [parsed (layout/project-definition
                   {:initial :a :entry :hello :exit :bye :tags #{:ui/busy}
                    :actions lifecycle-actions :states {:a {}}})
          graph  (projection/xyflow-graph parsed {} {})
          frame  (node-with-id graph layout/root-container-id)]
      (is (= "hello" (:entry (:data frame))))
      (is (= "bye" (:exit (:data frame))))
      (is (= ["ui/busy"] (:tags (:data frame))))
      (is (= ["rf/time-ms"] (:entryRequires (:data frame)))))))

;; ---- a region body's :entry / :exit / :tags --------------------------------

(deftest region-container-carries-the-region-lifecycle
  (testing "a region body's :entry / :exit / :tags ride its region container"
    (let [m      {:type    :parallel
                  :actions lifecycle-actions
                  :regions {:r {:initial :a :entry :r-in :exit :bye :tags #{:r-tag}
                                :states  {:a {}}}
                            :s {:initial :x :states {:x {}}}}}
          graph  (layout/project-definition m)
          r      (node-with-id graph (layout/region-node-id :r))
          s      (node-with-id graph (layout/region-node-id :s))]
      (is (= "r-in" (:entry r)))
      (is (= "bye" (:exit r)))
      (is (= #{:r-tag} (:tags r)))
      (is (= ["rf/uuid"] (:entry-requires r)))
      (is (not (contains? s :entry)) "a region declaring none carries none")
      (is (= #{} (:tags s)))))

  (testing "a region container reads its OWN body, never a state inside the
            region that shares the region's name"
    (let [m (layout/project-definition
              {:type    :parallel
               :actions lifecycle-actions
               :regions {:r {:initial :r :states {:r {:entry :hello :tags #{:inner}}}}}})
          r (node-with-id m (layout/region-node-id :r))]
      (is (not (contains? r :entry)))
      (is (nil? (:entry-requires r)))
      (is (= #{} (:tags r))))))

;; ---- the root's :spawn child ----------------------------------------------

(def ^:private flat-root-spawn
  {:initial :a
   :spawn   {:machine-id :m :on-done :b :on-error [:b]}
   :states  {:a {} :b {}}})

(defn- spawn-edges [graph]
  (filter #(#{:rf.machine.spawn/done :rf.machine.spawn/error} (:event %)) (:edges graph)))

(deftest flat-root-spawn-draws-its-completion-edges
  (testing "the root's :spawn :on-done / :on-error leave the machine-root chip
            and land on the top-level state a keyword target names"
    (let [graph (layout/project-definition flat-root-spawn)
          by-ev (into {} (map (juxt :event identity)) (spawn-edges graph))
          done  (by-ev :rf.machine.spawn/done)
          err   (by-ev :rf.machine.spawn/error)]
      (is (= 2 (count (spawn-edges graph))))
      (is (= layout/machine-root-id (:source done) (:source err)))
      (is (= (layout/node-id [:b]) (:target done) (:target err)))
      (is (= "✓ done" (:event-label done)))
      (is (= "✗ error" (:event-label err)))
      (is (true? (:on-done? done)))
      (is (true? (:on-error? err)))
      (is (some :machine-root? (:nodes graph)) "the chip the edges leave from is drawn")))

  (testing "they carry the flags and labels a spawning state's edges carry"
    (let [state  (layout/project-definition
                   {:initial :a
                    :states  {:a {:spawn {:machine-id :m :on-done :b :on-error [:b]}} :b {}}})
          shape  (fn [g] (set (map #(select-keys % [:event :on-done? :on-error? :event-label :to-path])
                                   (spawn-edges g))))]
      (is (= (shape state) (shape (layout/project-definition flat-root-spawn)))))))

(deftest root-spawn-action-only-and-fold-forms
  (testing "an action-only :on-error self-anchors on the machine-root chip, and a
            fn :on-done is the :data fold and draws nothing"
    (let [graph (layout/project-definition
                  {:initial :a
                   :spawn   {:machine-id :m :on-done (fn [{:keys [data]}] data)
                             :on-error {:action :log}}
                   :states  {:a {}}})
          [e & more] (spawn-edges graph)]
      (is (nil? more) "one edge: the fold draws none")
      (is (= :rf.machine.spawn/error (:event e)))
      (is (true? (:internal? e)))
      (is (= layout/machine-root-id (:source e) (:target e))))))

(deftest root-spawn-without-completions-draws-nothing
  (testing "a root :spawn with no completion transition draws no edge and no chip"
    (let [graph (layout/project-definition {:initial :a :spawn {:machine-id :m} :states {:a {}}})]
      (is (empty? (spawn-edges graph)))
      (is (not-any? :machine-root? (:nodes graph))))))

(deftest parallel-root-spawn-draws-region-qualified-edges
  (testing "a parallel root's :spawn completions carry region-qualified targets,
            as its root :on does, and land on the region-scoped state"
    (let [graph (layout/project-definition
                  {:type    :parallel
                   :spawn   {:machine-id :m
                             :on-done    {:target [:r :b]}
                             :on-error   {:target [[:r :b] [:s :y]]}}
                   :regions {:r {:initial :a :states {:a {} :b {}}}
                             :s {:initial :x :states {:x {} :y {}}}}})
          edges (spawn-edges graph)]
      (is (= #{[:rf.machine.spawn/done (layout/region-scoped-id :r [:b])]
               [:rf.machine.spawn/error (layout/region-scoped-id :r [:b])]
               [:rf.machine.spawn/error (layout/region-scoped-id :s [:y])]}
             (set (map (juxt :event :target) edges))))
      (is (every? #(= layout/machine-root-id (:source %)) edges))
      (is (some :machine-root? (:nodes graph))))))
