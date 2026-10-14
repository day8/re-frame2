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
      (is (= {:entry "hello" :exit "bye" :tags #{:busy} :entry-requires ["rf/time-ms"]}
             (select-keys twin [:entry :exit :tags :entry-requires])))
      (is (= (select-keys twin lifecycle-keys) (select-keys root lifecycle-keys))
          "the root projects its lifecycle exactly as a state does")
      (is (= #{:a-tag} (:tags (node-with-id graph (layout/node-id [:a]))))
          "a state keeps its own tags; the root's stay on the root"))))

(deftest root-lifecycle-reaches-the-renderer-data
  (testing "the frame's xyflow :data carries the root lifecycle in the keys a
            state node's :data carries it"
    (let [parsed (layout/project-definition
                   {:initial :a :entry :hello :exit :bye :tags #{:ui/busy}
                    :actions lifecycle-actions :states {:a {}}})
          graph  (projection/xyflow-graph parsed {} {})
          frame  (node-with-id graph layout/root-container-id)]
      (is (= {:entry "hello" :exit "bye" :tags ["ui/busy"] :entryRequires ["rf/time-ms"]}
             (select-keys (:data frame) [:entry :exit :tags :entryRequires]))))))

;; ---- a region body's :entry / :exit / :tags --------------------------------

(deftest region-container-carries-the-region-lifecycle
  (testing "a region body's :entry / :exit / :tags ride its region container"
    (let [m      {:type    :parallel
                  :actions lifecycle-actions
                  :regions {:r {:initial :a :entry :r-in :exit :bye :tags #{:r-tag}
                                :states  {:a {}}}}}
          r      (node-with-id (layout/project-definition m) (layout/region-node-id :r))]
      (is (= {:entry "r-in" :exit "bye" :tags #{:r-tag} :entry-requires ["rf/uuid"]}
             (select-keys r [:entry :exit :tags :entry-requires])))))

  (testing "a region container reads its OWN body, never a state inside the
            region that shares the region's name"
    (let [m (layout/project-definition
              {:type    :parallel
               :actions lifecycle-actions
               :regions {:r {:initial :r :states {:r {:entry :hello :tags #{:inner}}}}}})
          r (node-with-id m (layout/region-node-id :r))]
      (is (= [false nil #{}] [(contains? r :entry) (:entry-requires r) (:tags r)])))))

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
    (let [graph (layout/project-definition flat-root-spawn)]
      (is (= [[layout/machine-root-id (layout/node-id [:b])]
              [layout/machine-root-id (layout/node-id [:b])]]
             (map (juxt :source :target) (spawn-edges graph))))
      (is (some :machine-root? (:nodes graph)) "the chip the edges leave from is drawn")))

  (testing "they carry the flags and labels a spawning state's edges carry"
    (let [state  (layout/project-definition
                   {:initial :a
                    :states  {:a {:spawn {:machine-id :m :on-done :b :on-error [:b]}} :b {}}})
          shape  (fn [g] (set (map #(select-keys % [:event :on-done? :on-error? :event-label :to-path])
                                   (spawn-edges g))))]
      (is (= (shape state) (shape (layout/project-definition flat-root-spawn)))))))

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
          mr    layout/machine-root-id]
      (is (= #{[:rf.machine.spawn/done mr (layout/region-scoped-id :r [:b])]
               [:rf.machine.spawn/error mr (layout/region-scoped-id :r [:b])]
               [:rf.machine.spawn/error mr (layout/region-scoped-id :s [:y])]}
             (set (map (juxt :event :source :target) (spawn-edges graph)))))
      (is (some :machine-root? (:nodes graph))))))
