(ns re-frame.machine-root-region-source-test
  "A machine root's own `:entry` / `:exit` and a parallel machine's
  `:regions` tree carry the source the `reg-machine` / `defmachine` walk
  co-locates, at the paths a tool reads for them:

  - the spec root carries its own `:source-coords`, and its inline `:entry` /
    `:exit` source under `:source-code` — the root is the enclosing node of
    the `[:entry]` / `[:exit]` key Xray's Epoch panel builds for a root's own
    lifecycle row;
  - each region body carries the same at `[:regions <region>]`, the
    enclosing node of `[:regions <region> :entry]` / `… :exit]`;
  - every map node of a region's `:states` tree carries the stamps a node of
    the root's `:states` tree does, at `[:regions <region> :states …]`.

  A named-action reference keeps its link on its `:actions` entry and adds no
  inline source. The production arm of the macro's `debug-enabled?` gate
  co-locates none of it, so each deftest asserts presence in the dev posture
  and absence in the production posture, and runs in both the default lane
  and the production-gate lane.

  Inline-fn `:source-code` is the `pr-str` of the fn literal, which a plain
  JVM load captures. A MAP node's `:source-coords` needs reader positions on
  map literals, which the CLJS reader attaches and Clojure's LispReader does
  not, so the coord cases read their literal with `clojure.tools.reader`'s
  indexing reader — which attaches them as the CLJS reader does — and
  evaluate it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            ;; Loading the machines artefact installs the late-bind hooks
            ;; `reg-machine` registers through.
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- machine-spec
  "The registered spec of `machine-id`, read through the registrar query and
  its `:rf/machine` projection."
  [machine-id]
  (:rf/machine (rf/handler-meta {:source :store :kind :event :id machine-id})))

(defn- source-keys-anywhere
  "The `:source-code` / `:source-coords` keys found on any map at any depth of
  `spec`. Empty when nothing was co-located."
  [spec]
  (into #{}
        (comp (filter map?)
              (mapcat keys)
              (filter #{:source-code :source-coords}))
        (tree-seq coll? seq spec)))

;; ---- inline `:source-code` -------------------------------------------------

(deftest flat-root-inline-lifecycle-source
  (rf/reg-machine :root-src/flat
    {:initial :a
     :entry   (fn [_] {:data {:at :root-in}})
     :exit    (fn [_] {:data {:at :root-out}})
     :states  {:a {:entry (fn [_] {:data {:at :a-in}})}}})
  (let [spec (machine-spec :root-src/flat)]
    (is (fn? (:entry spec)) "the root's :entry slot keeps its bare fn")
    (is (fn? (:exit spec)) "the root's :exit slot keeps its bare fn")
    (if rf.interop/debug-enabled?
      (do
        (is (= {:entry "(fn [_] {:data {:at :root-in}})"
                :exit  "(fn [_] {:data {:at :root-out}})"}
               (:source-code spec))
            "the root's inline :entry / :exit source rides the spec root")
        (is (= {:entry "(fn [_] {:data {:at :a-in}})"}
               (get-in spec [:states :a :source-code]))
            "a state's inline :entry source stays on its state-node"))
      (is (= #{} (source-keys-anywhere spec))
          "the production arm co-locates no source"))))

(deftest parallel-regions-inline-source
  (rf/reg-machine :root-src/parallel
    {:type    :parallel
     :entry   (fn [_] {:data {:at :root-in}})
     :regions {:r1 {:initial :x
                    :entry   (fn [_] {:data {:at :r1-in}})
                    :exit    (fn [_] {:data {:at :r1-out}})
                    :states  {:x {:entry (fn [_] {:data {:at :x-in}})
                                  :on    {:go {:target :y
                                               :guard  (fn [_] true)
                                               :action (fn [_] {:data {:at :x-go}})}}}
                              :y {:initial :y1
                                  :states  {:y1 {:exit (fn [_] {:data {:at :y1-out}})}}}}}
               :r2 {:initial :p
                    :states  {:p {:entry (fn [_] {:data {:at :p-in}})}}}}})
  (let [spec (machine-spec :root-src/parallel)]
    (is (fn? (get-in spec [:regions :r1 :entry])) "a region body's :entry slot keeps its bare fn")
    (if rf.interop/debug-enabled?
      (do
        (is (= {:entry "(fn [_] {:data {:at :root-in}})"} (:source-code spec))
            "a parallel root's inline :entry source rides the spec root")
        (is (= {:entry "(fn [_] {:data {:at :r1-in}})"
                :exit  "(fn [_] {:data {:at :r1-out}})"}
               (get-in spec [:regions :r1 :source-code]))
            "a region body's inline :entry / :exit source rides the region body")
        (is (nil? (get-in spec [:regions :r2 :source-code]))
            "a region body with no inline lifecycle carries no :source-code")
        (is (= {:entry "(fn [_] {:data {:at :x-in}})"}
               (get-in spec [:regions :r1 :states :x :source-code]))
            "a state inside a region")
        (is (= {:guard  "(fn [_] true)"
                :action "(fn [_] {:data {:at :x-go}})"}
               (get-in spec [:regions :r1 :states :x :on :go :source-code]))
            "a transition's inline :guard / :action inside a region")
        (is (= {:exit "(fn [_] {:data {:at :y1-out}})"}
               (get-in spec [:regions :r1 :states :y :states :y1 :source-code]))
            "a nested state inside a region")
        (is (= {:entry "(fn [_] {:data {:at :p-in}})"}
               (get-in spec [:regions :r2 :states :p :source-code]))
            "a state inside a second region"))
      (is (= #{} (source-keys-anywhere spec))
          "the production arm co-locates no source"))))

(rf/defmachine defined-parallel
  {:type    :parallel
   :entry   (fn [_] {:data {:at :def-root-in}})
   :regions {:r1 {:initial :x
                  :exit    (fn [_] {:data {:at :def-r1-out}})
                  :states  {:x {:entry (fn [_] {:data {:at :def-x-in}})}}}}})

(deftest defmachine-carries-root-and-region-source
  (rf/reg-machine :root-src/defined defined-parallel)
  (let [spec (machine-spec :root-src/defined)]
    (if rf.interop/debug-enabled?
      (do
        (is (= {:entry "(fn [_] {:data {:at :def-root-in}})"} (:source-code spec))
            "the root's source travels with the def'd value")
        (is (= {:exit "(fn [_] {:data {:at :def-r1-out}})"}
               (get-in spec [:regions :r1 :source-code]))
            "the region body's source travels with the def'd value")
        (is (= {:entry "(fn [_] {:data {:at :def-x-in}})"}
               (get-in spec [:regions :r1 :states :x :source-code]))
            "a region state's source travels with the def'd value"))
      (is (= #{} (source-keys-anywhere spec))
          "the production arm co-locates no source"))))

(deftest named-root-lifecycle-keeps-its-action-link
  (rf/reg-machine :root-src/named
    {:initial :a
     :entry   :root-in
     :actions {:root-in (fn [_] {:data {:at :named-root-in}})}
     :states  {:a {}}})
  (let [spec (machine-spec :root-src/named)]
    (is (= :root-in (:entry spec)) "a keyword reference stays a keyword")
    (if rf.interop/debug-enabled?
      (do
        (is (= "(fn [_] {:data {:at :named-root-in}})"
               (get-in spec [:actions :root-in :source-code]))
            "the named action carries its own source, the [:actions :root-in] link")
        (is (nil? (get-in spec [:source-code :entry]))
            "a keyword reference adds no inline source at the root"))
      (is (= #{} (source-keys-anywhere spec))
          "the production arm co-locates no source"))))

;; ---- map-node `:source-coords` ---------------------------------------------

(def ^:private coords-literal
  "Two machine literals whose map nodes open on known lines: a parallel
  machine (root line 2, region bodies lines 4 and 9) and a flat one (root
  line 12)."
  (str "(rf/reg-machine :root-src/parallel-coords\n"
       "  {:type    :parallel\n"
       "   :entry   (fn [_] nil)\n"
       "   :regions {:r1 {:initial :x\n"
       "                  :entry   (fn [_] nil)\n"
       "                  :states  {:x {:on {:go {:target :y\n"
       "                                          :action (fn [_] nil)}}}\n"
       "                            :y {}}}\n"
       "             :r2 {:initial :p\n"
       "                  :states  {:p {}}}}})\n"
       "(rf/reg-machine :root-src/flat-coords\n"
       "  {:initial :a\n"
       "   :entry   (fn [_] nil)\n"
       "   :states  {:a {}}})\n"))

(defn- register-read!
  "Read every form in `text` with an indexing reader, which puts `:line` /
  `:column` on map literals as the CLJS reader does, and evaluate each in
  this namespace."
  [text]
  (binding [*ns* (the-ns 're-frame.machine-root-region-source-test)]
    (let [rdr (reader-types/indexing-push-back-reader text)]
      (loop []
        (let [form (reader/read {:eof ::eof} rdr)]
          (when-not (= ::eof form)
            (eval form)
            (recur)))))))

(deftest root-and-region-map-nodes-carry-coords
  (register-read! coords-literal)
  (let [parallel (machine-spec :root-src/parallel-coords)
        flat     (machine-spec :root-src/flat-coords)
        line     (fn [spec path] (get-in spec (conj path :source-coords :line)))]
    (if rf.interop/debug-enabled?
      (do
        (testing "the root map carries its own coord, the enclosing node of [:entry] / [:exit]"
          (is (= 2 (line parallel [])))
          (is (= 12 (line flat [])))
          (is (= 're-frame.machine-root-region-source-test
                 (get-in parallel [:source-coords :ns]))))
        (testing "a region body carries its own coord, the enclosing node of [:regions <region> :entry]"
          (is (= 4 (line parallel [:regions :r1])))
          (is (= 9 (line parallel [:regions :r2]))))
        (testing "the map nodes of a region's :states tree carry their own coords"
          (is (= 6 (line parallel [:regions :r1 :states :x])))
          (is (= 6 (line parallel [:regions :r1 :states :x :on :go])))
          (is (= 8 (line parallel [:regions :r1 :states :y])))
          (is (= 10 (line parallel [:regions :r2 :states :p])))))
      (do
        (is (= #{} (source-keys-anywhere parallel))
            "the production arm co-locates no coord")
        (is (= #{} (source-keys-anywhere flat))
            "the production arm co-locates no coord")))))
