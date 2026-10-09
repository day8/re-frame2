(ns day8.re-frame2-machines-viz.chart.context-redaction-wiring-cljs-test
  "EP-0015 export safety: `chart.projection/xyflow-graph` WIRES the
  Context-band redaction into the root-container's `:data {:context}` rows,
  which the SVG / PNG / clipboard exporters serialise. A wiring regression —
  raw by default, a dropped classification, a bypassed `redact-context` —
  leaks silently past every value-free DOM test, so it is pinned here at
  the cheap projection layer; `export-dom-cljs-test` covers the SVG end to
  end."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart.context-redaction :as ctx]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]))

(def ^:private flat-machine
  {:initial :a :states {:a {:on {:go :b}} :b {}}})

(defn- context-rows
  "The root-container's `[key-string value-display-string]` rows — what the
  band paints and an export serialises. nil when no band was fed."
  [graph]
  (let [root (first (filter #(= layout/root-container-id (:id %)) (:nodes graph)))]
    (:context (:data root))))

(defn- all-row-strings [graph]
  (mapcat identity (context-rows graph)))

(defn- value-for
  "The display string the band paints for context key `k`."
  [graph k]
  (let [target (str (symbol k))]
    (some (fn [[ks v]] (when (= ks target) v)) (context-rows graph))))

(defn- recipe-graph
  "The API.md recipe end to end: derive the classification from `machine`,
  feed `live-data` as the live band, project."
  [machine live-data]
  (let [{:keys [sensitive large]} (ctx/derive-classification machine)]
    (projection/xyflow-graph
      (layout/project-definition machine) {}
      {:context-band           live-data
       :context-band-inferred? false
       :context-band-sensitive sensitive
       :context-band-large     large})))

(deftest documented-recipe-redacts-a-machine-declared-secret
  (testing "a machine declaring `:sensitive [[:data :token]]` gets that slot
            redacted in the band — and so in every image export — while an
            undeclared sibling still renders"
    (let [secret "sk-live-SECRET-123"
          graph  (recipe-graph {:initial   :idle
                                :sensitive [[:data :token]]
                                :data      {:token nil :user nil}
                                :states    {:idle {}}}
                               (array-map :token secret :user "ann"))]
      (is (not (some #(str/includes? % secret) (all-row-strings graph)))
          "the declared secret appears in NO display row")
      (is (str/includes? (value-for graph :token) ":rf/redacted"))
      (is (= "\"ann\"" (value-for graph :user))))))

(deftest documented-recipe-whole-data-covers-keys-first-written-at-runtime
  (testing "a machine declaring its WHOLE :data sensitive, whose initial :data
            is empty, later writes a token: it appears in NO display row"
    (let [secret "secret-at-runtime"
          graph  (recipe-graph {:initial :idle :sensitive [[:data]] :data {} :states {:idle {}}}
                               (array-map :token secret))]
      (is (not (some #(str/includes? % secret) (all-row-strings graph))))
      (is (str/includes? (value-for graph :token) ":rf/redacted"))))
  (testing "a whole-data :large elides a runtime-only value to the content-free
            marker"
    (let [payload "LARGE-RUNTIME-PAYLOAD-xyzzy"
          graph   (recipe-graph {:initial :idle :large [[:data]] :data {} :states {:idle {}}}
                                (array-map :blob payload))]
      (is (not (some #(str/includes? % payload) (all-row-strings graph))))
      (is (str/includes? (value-for graph :blob) ":rf.size/large-elided")))))

(deftest xyflow-graph-context-band-raw-opts-out-of-redaction
  (testing ":context-band-raw? true is the explicit trusted-local opt-in: a
            slot classified sensitive passes its raw value through"
    (let [secret "card-4111-1111-1111-1111"
          graph  (projection/xyflow-graph
                   (layout/project-definition flat-machine) {}
                   {:context-band           (array-map :card secret)
                    :context-band-sensitive #{:card}
                    :context-band-raw?      true})]
      (is (str/includes? (value-for graph :card) secret)))))

(deftest xyflow-graph-no-context-band-emits-no-context
  (testing "with no :context-band fed, the root-container carries no :context
            rows — nothing to serialise into an export"
    (is (nil? (context-rows (projection/xyflow-graph
                              (layout/project-definition flat-machine) {} {}))))))
