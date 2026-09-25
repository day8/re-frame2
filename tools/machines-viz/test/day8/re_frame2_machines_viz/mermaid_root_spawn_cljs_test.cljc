(ns day8.re-frame2-machines-viz.mermaid-root-spawn-cljs-test
  "Mermaid draws the machine root's `:spawn` child's completions the way it
  draws a spawning state's.

  A root `:spawn` child lives as long as the machine. Its `:on-error` and a
  transition-shaped `:on-done` are transitions the engine takes at the root,
  where a keyword names a top-level state. The chart draws them from its
  machine-root chip; Mermaid draws them from the `root fallback` node, the
  machine root the root's own `:on` leaves from, with the `✗ error` /
  `✓ done` labels a spawning state's edges carry. A parallel root's targets are
  region-qualified, as its `:on`'s are. An action-only completion has no arrow
  and is noted where the root's action-only `:on` is noted."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.mermaid :as mermaid]))

(defn- mermaid-body [definition]
  (mermaid/emit definition {:fenced? false :header-comment? false}))

(defn- alias-id
  "The Mermaid id the body declares for the state labelled `label`, or nil."
  [body label]
  (second (re-find (re-pattern (str "state \"" label "\" as (\\S+)")) body)))

(defn- edges-from
  "The `<target> : <label>` of every edge line leaving `source-id`."
  [body source-id]
  (let [prefix (str "  " source-id " --> ")]
    (->> (str/split-lines body)
         (filter #(str/starts-with? % prefix))
         (map #(subs % (count prefix)))
         set)))

(def ^:private completions {:machine-id :m :on-done :b :on-error [:b]})

(deftest flat-root-spawn-completions-leave-the-root-node
  (testing "the root's :spawn :on-done / :on-error leave the root node with the
            labels a spawning state's edges carry"
    (let [body    (mermaid-body {:initial :a :spawn completions :states {:a {} :b {}}})
          root-id (alias-id body "root fallback")
          state   (mermaid-body {:initial :a :states {:a {:spawn completions} :b {}}})]
      (is (= #{"b : ✓ done" "b : ✗ error"} (edges-from body root-id)))
      (is (= (edges-from state "a") (edges-from body root-id))
          "the root's edges read as the spawning state's do")
      (is (= 1 (count (re-seq #"state \"root fallback\"" body)))
          "the root node is declared once"))))

(deftest parallel-root-spawn-completions-are-region-qualified
  (testing "a parallel root's :spawn completions land on the region-qualified
            state, one edge per region a multi-region target names"
    (let [regions {:r {:initial :a :states {:a {} :b {}}}
                   :s {:initial :x :states {:x {} :y {}}}}
          single  (mermaid-body {:type    :parallel
                                 :spawn   {:machine-id :m :on-done {:target [:r :b]} :on-error [:r :b]}
                                 :regions regions})
          multi   (mermaid-body {:type    :parallel
                                 :spawn   {:machine-id :m :on-error {:target [[:r :b] [:s :y]]}}
                                 :regions regions})]
      (is (= #{"r__b : ✓ done" "r__b : ✗ error"}
             (edges-from single (alias-id single "root fallback"))))
      (is (= #{"r__b : ✗ error" "s__y : ✗ error"}
             (edges-from multi (alias-id multi "root fallback")))))))

(deftest root-spawn-action-only-completions-are-noted
  (testing "an action-only root completion is noted on the node the root's
            action-only :on is noted on"
    (let [spawn    {:machine-id :m :on-error {:action :log}}
          flat     (mermaid-body {:initial :a :spawn spawn :states {:a {}}})
          flat-id  (alias-id flat "root fallback")
          parallel (mermaid-body {:type :parallel :spawn spawn
                                  :regions {:r {:initial :a :states {:a {}}}}})
          par-id   (alias-id parallel "parallel root")]
      (is (some? flat-id) "the flat root node is declared for its note")
      (is (str/includes? flat (str "  note right of " flat-id "\n    ✗ error / log\n  end note")))
      (is (str/includes? parallel (str "  note right of " par-id "\n    ✗ error / log\n  end note"))))))

(deftest root-spawn-without-completion-draws-nothing
  (testing "a root :spawn with no completion transition, or only the :data fold,
            draws no edge and declares no root node"
    (is (nil? (alias-id (mermaid-body {:initial :a :spawn {:machine-id :m} :states {:a {}}})
                        "root fallback")))
    (is (nil? (alias-id (mermaid-body {:initial :a
                                       :spawn   {:machine-id :m :on-done (fn [{:keys [data]}] data)}
                                       :states  {:a {}}})
                        "root fallback")))))
