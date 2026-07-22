(ns re-frame.freehand.bench.b1
  "B1 — the same finite template, interpreted versus compiled.

  D021's first required workload: *one boundary renders a finite
  10³–10⁴-node template*, compared under the
  `:interpreted-vs-compiled` baseline, publishing p50/p95 self time,
  per-node cost and allocation, and GATED on equal output.

  ## The two arms are the same template, and that is proven twice

  A timing comparison between two arms that are not the same template is
  a comparison of two templates. So the claim is held down from both
  ends: [[re-frame.freehand.bench.b1.compiled]] is its interpreted twin
  plus `{:compiled true}` and nothing else — read off the SOURCE by
  `compiled-source-delta-jvm-test` — and every measured iteration renders
  both arms and requires their structural output to be EQUAL, modulo the
  view id, which names the namespace a declaration lives in and is the
  one thing two files cannot share.

  ## What gates and what does not

  D021's split is the harness's, not this workload's ([[re-frame.freehand.bench.measure]]
  makes it a total function of the observable), and B1 sits on the exact
  line the ruling draws:

    - `:B1/structural-parity` and `:B1/node-count` observe a `:flag` and
      a `:count`. They are deterministic properties, they gate, and a
      violation exits non-zero. Equal output is B1's whole correctness
      claim: a lowering that were faster because it produced something
      else would be measuring the wrong thing.
    - every duration and every byte reading is a `:distribution`, is
      published as evidence, and has no route to the exit code however
      far it moves. B1 is not allowed to conclude that compiled must win
      — the numbers are the input to that judgement, not the judgement.

  ## The DOM half of the equal-output claim is not yet runnable

  D021's B1 row asks for equal tree AND equal DOM. The structural half is
  gated here on both hosts. The browser half cannot be run yet: the
  compiled tier's React lowering does not exist — `re-frame.freehand.react`
  refuses a compiled declaration by name with
  `:rf.error/view-lowering-unavailable` — so there is no compiled DOM to
  compare an interpreted one against. The workload takes the structural
  gate now and the DOM row joins it with that lowering, rather than the
  measurement waiting for it.

  ## A small case beside the stress case

  D021: *\"Include at least one small realistic case beside each stress
  case so optimization is not tuned only to synthetic extremes.\"* Hence
  two registered workloads over one template and one `:run`, differing
  only in the `:rows` fixture parameter — sizes are fixture parameters,
  not language constants.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [clojure.walk :as walk]
            [re-frame.freehand.bench :as bench]
            [re-frame.freehand.bench.b1.compiled :as compiled]
            [re-frame.freehand.bench.b1.interpreted :as interpreted]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.tree :as tree]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The template's shape, as arithmetic
;; ---------------------------------------------------------------------------

(defn expected-nodes
  "The exact node count the template renders for `rows`.

  Stated as arithmetic rather than measured, so `:B1/node-count` is a
  gate against a WRITTEN expectation instead of against whatever the walk
  happened to produce — a gate that expects what it saw is not a gate.

  A node is anything `(tree-seq map? :children t)` yields, which is the
  tree's whole traversal API and includes TEXT: text is a string rather
  than a map, so it is not descended into, but the walk built it and the
  per-node denominator has to count it. Five for the skeleton — the
  boundary, its section, the heading and the heading's text, and the list
  — then seven per row: the row element, its three children, and their
  three texts."
  [rows]
  (+ 5 (* 7 rows)))

(defn- node-count
  [t]
  (count (tree-seq map? :children t)))

;; ---------------------------------------------------------------------------
;; Equal output
;; ---------------------------------------------------------------------------

(def ^:private arm-namespaces
  "The two arms' declaration namespaces. A view id names the namespace a
  declaration LIVES in, and two views of one name cannot share one, so
  this is the single unavoidable difference between the arms — and it is
  a difference living in another file makes, not one promotion makes."
  #{"re-frame.freehand.bench.b1.interpreted"
    "re-frame.freehand.bench.b1.compiled"})

(defn mode-neutral
  "Rewrite an arm's view ids onto one neutral namespace, so the two trees
  are compared on everything EXCEPT the fact that they were declared in
  different files. Nothing else is normalised: tags, attributes,
  children, text, keys and ordering are compared verbatim."
  [t]
  (walk/postwalk
    (fn [x]
      (if (and (keyword? x) (contains? arm-namespaces (namespace x)))
        (keyword "re-frame.freehand.bench.b1" (name x))
        x))
    t))

;; ---------------------------------------------------------------------------
;; One iteration
;; ---------------------------------------------------------------------------

(defn- render-arm
  "Render `view` over `rows`, answering the tree, the self time, and the
  bytes allocated.

  The two clock readings bracket the render and NOTHING else — the
  allocation readings sit outside them, so the cost of taking them is not
  billed to the walk. `:run`'s own end-to-end cost, including the
  equality check below, is the harness's `:bench/iteration-ms`, which is
  the outer bound this self time sits inside."
  [view rows]
  (let [a0     (m/allocated-bytes)
        t0     (m/now-ms)
        result (tree/render [view {:rows rows}])
        t1     (m/now-ms)
        a1     (m/allocated-bytes)]
    {:tree result :ms (- t1 t0) :bytes (- a1 a0)}))

(defn observe
  "Run one B1 iteration over `fixture` and answer its observations.

  Both arms render EVERY iteration, in the same order, so neither is
  measured in a state the other never sees, and the parity check is over
  the trees this iteration actually produced rather than a sample taken
  once at the start."
  [{:keys [rows]}]
  (let [i (render-arm interpreted/template rows)
        c (render-arm compiled/template rows)
        n (node-count (:tree i))]
    {:B1/node-count                  n
     :B1/structural-parity           (= (mode-neutral (:tree i)) (mode-neutral (:tree c)))
     :B1/interpreted-self-ms         (:ms i)
     :B1/compiled-self-ms            (:ms c)
     :B1/interpreted-per-node-ms     (/ (:ms i) n)
     :B1/compiled-per-node-ms        (/ (:ms c) n)
     :B1/interpreted-allocated-bytes (:bytes i)
     :B1/compiled-allocated-bytes    (:bytes c)}))

;; ---------------------------------------------------------------------------
;; The workloads
;; ---------------------------------------------------------------------------

(defn workload
  "Build the B1 workload for `rows`, under `id` and `sampling`.

  One `:run` and one template behind every B1 result: the stress case and
  the small case differ in a fixture parameter and in nothing else, so a
  reader comparing them is comparing sizes rather than benchmarks."
  [{:keys [id doc rows sampling]}]
  {:id       id
   :doc      doc
   :fixture  {:rows rows :nodes (expected-nodes rows)}
   :baseline {:kind      :interpreted-vs-compiled
              :reference {:arm :interpreted}}
   :sampling sampling
   :measurements
   [{:id :B1/node-count
     :doc "the exact node count the template renders — the denominator of per-node cost"
     :observable :count
     :expect (expected-nodes rows)}
    {:id :B1/structural-parity
     :doc "the interpreted and compiled arms produced the same structural tree"
     :observable :flag
     :expect true}
    {:id :B1/interpreted-self-ms
     :doc "interpreted self time for one render of the template"
     :observable :duration-ms}
    {:id :B1/compiled-self-ms
     :doc "compiled self time for one render of the template"
     :observable :duration-ms}
    {:id :B1/interpreted-per-node-ms
     :doc "interpreted self time divided by the node count — cost per node"
     :observable :duration-ms}
    {:id :B1/compiled-per-node-ms
     :doc "compiled self time divided by the node count — cost per node"
     :observable :duration-ms}
    {:id :B1/interpreted-allocated-bytes
     :doc "bytes allocated by one interpreted render, as the host counts them"
     :observable :bytes}
    {:id :B1/compiled-allocated-bytes
     :doc "bytes allocated by one compiled render, as the host counts them"
     :observable :bytes}]
   :run observe})

(def workloads
  "B1's registered workloads: the 10³-node stress case D021 names, and the
  small realistic case it requires beside it."
  [(workload {:id       :B1/finite-template
              :doc      "one boundary renders a finite 10^3-node template, interpreted and compiled"
              :rows     250 ; 1755 nodes — inside D021's 10^3-10^4 band
              :sampling {:warmup 5 :samples 40}})
   (workload {:id       :B1/small-template
              :doc      "the same template at a realistic small size — the control on the stress case"
              :rows     6
              :sampling {:warmup 5 :samples 40}})])

(def registered
  "Registering at load: requiring this namespace puts B1 in the standing
  evidence suite `clojure -M:bench` runs."
  (mapv bench/register! workloads))
