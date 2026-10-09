(ns re-frame.machine-root-region-source-test
  "A machine root's and each parallel region body's own `:entry` / `:exit`
  carry their inline source, and every map node of the root and region trees
  carries its `:source-coords`, at the paths a tool reads them from. The
  production arm of the macro's `debug-enabled?` gate co-locates none of it,
  so each test asserts both postures.

  A map node's `:source-coords` needs reader positions on map literals, which
  the CLJS reader attaches and Clojure's LispReader does not, so the coord test
  reads its literal with tools.reader's indexing reader."
  (:require [clojure.test :refer [deftest is use-fixtures]]
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

(defn- machine-spec [machine-id]
  (:rf/machine (rf/handler-meta {:source :store :kind :event :id machine-id})))

(defn- source-keys-anywhere
  "The `:source-code` / `:source-coords` keys found on any map at any depth of
  `spec`."
  [spec]
  (into #{}
        (comp (filter map?)
              (mapcat keys)
              (filter #{:source-code :source-coords}))
        (tree-seq coll? seq spec)))

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
    (is (fn? (get-in spec [:regions :r1 :entry])) "an inline slot keeps its bare fn")
    (if rf.interop/debug-enabled?
      (let [expected {[]                                    {:entry "(fn [_] {:data {:at :root-in}})"}
                      [:regions :r1]                        {:entry "(fn [_] {:data {:at :r1-in}})"
                                                             :exit  "(fn [_] {:data {:at :r1-out}})"}
                      [:regions :r2]                        nil
                      [:regions :r1 :states :x]             {:entry "(fn [_] {:data {:at :x-in}})"}
                      [:regions :r1 :states :x :on :go]     {:guard  "(fn [_] true)"
                                                             :action "(fn [_] {:data {:at :x-go}})"}
                      [:regions :r1 :states :y :states :y1] {:exit "(fn [_] {:data {:at :y1-out}})"}
                      [:regions :r2 :states :p]             {:entry "(fn [_] {:data {:at :p-in}})"}}]
        (is (= expected (into {} (for [path (keys expected)]
                                   [path (get-in spec (conj path :source-code))])))))
      (is (= #{} (source-keys-anywhere spec))
          "the production arm co-locates no source"))))

(def ^:private coords-literal
  "A parallel machine whose map nodes open on known lines: the root on line 2,
  the region bodies on lines 4 and 9."
  (str "(rf/reg-machine :root-src/parallel-coords\n"
       "  {:type    :parallel\n"
       "   :entry   (fn [_] nil)\n"
       "   :regions {:r1 {:initial :x\n"
       "                  :entry   (fn [_] nil)\n"
       "                  :states  {:x {:on {:go {:target :y\n"
       "                                          :action (fn [_] nil)}}}\n"
       "                            :y {}}}\n"
       "             :r2 {:initial :p\n"
       "                  :states  {:p {}}}}})\n"))

(defn- register-read!
  "Read `text` with an indexing reader, which puts `:line` / `:column` on map
  literals as the CLJS reader does, and evaluate it in this namespace."
  [text]
  (binding [*ns* (the-ns 're-frame.machine-root-region-source-test)]
    (eval (reader/read (reader-types/indexing-push-back-reader text)))))

(deftest root-and-region-map-nodes-carry-coords
  (register-read! coords-literal)
  (let [spec (machine-spec :root-src/parallel-coords)]
    (if rf.interop/debug-enabled?
      (let [expected {[]                                2
                      [:regions :r1]                    4
                      [:regions :r2]                    9
                      [:regions :r1 :states :x]         6
                      [:regions :r1 :states :x :on :go] 6
                      [:regions :r1 :states :y]         8
                      [:regions :r2 :states :p]         10}]
        (is (= 're-frame.machine-root-region-source-test
               (get-in spec [:source-coords :ns])))
        (is (= expected (into {} (for [path (keys expected)]
                                   [path (get-in spec (conj path :source-coords :line))])))))
      (is (= #{} (source-keys-anywhere spec))
          "the production arm co-locates no coord"))))
