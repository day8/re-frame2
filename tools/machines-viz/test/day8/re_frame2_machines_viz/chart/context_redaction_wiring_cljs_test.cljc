(ns day8.re-frame2-machines-viz.chart.context-redaction-wiring-cljs-test
  "EP-0015 export-safety: the Context-band redaction WIRING through
  `chart.projection/xyflow-graph` (rf2-vbo66r).

  `context-redaction-cljs-test` pins the redact/display/classification
  helpers in isolation, and `chart-dom-cljs-test` renders the value-FREE
  static context shape (so redaction is a no-op there). But nothing
  verified that `xyflow-graph` actually WIRES the redaction: that it

    (a) applies redaction BY DEFAULT to a live `:context-band` (a
        `:context-band-sensitive` slot → `:rf/redacted`);
    (b) THREADS the `:context-band-sensitive` / `:context-band-large`
        sets into the projection (not a hardcoded / empty classification);
    (c) honours the `:context-band-raw?` trusted-local opt-in that skips
        redaction;
    (d) emits the resulting content into the root-container's
        `:data {:context}` display rows.

  This is the EP-0015 export-safety property (EP-0015 §96-110, §985-989):
  a host feeding a live machine `:data` map into the band must not leak a
  schema-marked secret / large slot into the SVG / PNG / clipboard export
  (`export/chart-as-svg` clones the live viewport DOM, so whatever lands in
  `:data {:context}` is serialised). A wiring regression — raw-by-default,
  a dropped classification, a bypassed `redact-context` — leaks silently
  and passes every value-free DOM test. These pins make it LOUD at the
  cheap JVM projection layer (the browser `export-dom-cljs-test` covers the
  end-to-end SVG counterpart).

  Pure `.cljc` → the JVM corpus + the `cljs-test$` node-test build pin it."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]))

;; ---------------------------------------------------------------------------
;; fixtures / helpers

(def ^:private flat-machine
  "A minimal flat machine so `project-definition` mints the synthetic
  root-container frame whose header paints the Context band."
  {:initial :a :states {:a {:on {:go :b}} :b {}}})

(defn- context-rows
  "The root-container's projected `:data {:context}` — the vector of
  `[key-string value-display-string]` rows the band header paints (and the
  SVG/PNG exporter serialises). nil when no band was fed."
  [graph]
  (let [root (first (filter #(= layout/root-container-id (:id %)) (:nodes graph)))]
    (:context (:data root))))

(defn- all-row-strings
  "Every string across every `:data {:context}` row — the full surface a
  secret could leak into."
  [graph]
  (mapcat identity (context-rows graph)))

(defn- value-for
  "The display string the band paints for context key `k` (a keyword) —
  matched against its fully-qualified string form, the shape the projector
  emits."
  [graph k]
  (let [target (str (symbol k))]
    (some (fn [[ks v]] (when (= ks target) v)) (context-rows graph))))

;; ---------------------------------------------------------------------------
;; (a) redaction is applied BY DEFAULT to a live context-band

(deftest xyflow-graph-redacts-sensitive-context-by-default
  (testing "rf2-vbo66r — a live :context-band with a :context-band-sensitive
            slot is redacted BY DEFAULT (no :context-band-raw?): the sensitive
            row projects to the :rf/redacted sentinel and the secret VALUE
            appears in NO display row, while a non-sensitive slot renders"
    (let [secret "card-4111-1111-1111-1111"
          parsed (layout/project-definition flat-machine)
          graph  (projection/xyflow-graph
                   parsed {}
                   {:context-band            (array-map :card secret :count 7)
                    :context-band-inferred?  false
                    :context-band-sensitive  #{:card}})]
      (is (some? (context-rows graph)) "the band produced context rows")
      (is (not (some #(str/includes? % secret) (all-row-strings graph)))
          "the secret VALUE must not appear in ANY display row (export-safety)")
      (is (str/includes? (value-for graph :card) ":rf/redacted")
          "the sensitive :card slot shows the content-free redacted sentinel")
      (is (= "7" (value-for graph :count))
          "the non-sensitive :count slot still renders its value"))))

;; ---------------------------------------------------------------------------
;; (b) the sensitive / large sets are actually THREADED into the projection

(deftest xyflow-graph-threads-sensitive-set-not-hardcoded
  (testing "rf2-vbo66r — the SAME secret value is redacted ONLY when its key
            is in :context-band-sensitive; with an empty set the projector
            passes it through. Proves the set is genuinely threaded into
            redact-context (not a hardcoded classification)"
    (let [secret "sk-live-abc123"
          band   (array-map :key secret)
          parsed (layout/project-definition flat-machine)
          in-set (projection/xyflow-graph
                   parsed {} {:context-band band :context-band-sensitive #{:key}})
          no-set (projection/xyflow-graph
                   parsed {} {:context-band band :context-band-sensitive #{}})]
      (is (str/includes? (value-for in-set :key) ":rf/redacted")
          "key ∈ sensitive set → redacted")
      (is (not (some #(str/includes? % secret) (all-row-strings in-set)))
          "…and the secret is absent")
      (is (str/includes? (value-for no-set :key) secret)
          "key ∉ sensitive set → passes through (so the set was truly threaded)"))))

(deftest xyflow-graph-threads-large-set-into-redaction
  (testing "rf2-vbo66r — a :context-band-large slot elides to the canonical
            content-FREE :rf.size/large-elided marker; the value's content
            never reaches a display row"
    (let [payload "SENSITIVE-BLOB-CONTENT-xyzzy"
          parsed  (layout/project-definition flat-machine)
          graph   (projection/xyflow-graph
                    parsed {}
                    {:context-band       (array-map :blob payload)
                     :context-band-large #{:blob}})]
      (is (str/includes? (value-for graph :blob) ":rf.size/large-elided")
          "the large slot shows the size-only elision marker")
      (is (not (some #(str/includes? % payload) (all-row-strings graph)))
          "the large value's content never reaches a display row"))))

;; ---------------------------------------------------------------------------
;; (c) :context-band-raw? true is the explicit trusted-local opt-out

(deftest xyflow-graph-context-band-raw-opts-out-of-redaction
  (testing "rf2-vbo66r — :context-band-raw? true is the explicit
            trusted-local opt-in that SKIPS redaction: the SAME sensitive
            slot that redacts by default now passes its raw value through"
    (let [secret "card-4111-1111-1111-1111"
          band   (array-map :card secret)
          parsed (layout/project-definition flat-machine)
          ;; identical band + sensitive classification; only :context-band-raw? differs
          redacted (projection/xyflow-graph
                     parsed {} {:context-band band :context-band-sensitive #{:card}})
          raw      (projection/xyflow-graph
                     parsed {} {:context-band band :context-band-sensitive #{:card}
                                :context-band-raw? true})]
      (is (not (some #(str/includes? % secret) (all-row-strings redacted)))
          "default (raw? absent) → secret redacted away")
      (is (str/includes? (value-for raw :card) secret)
          "raw? true → the raw value passes through UNREDACTED (opt-out honoured)"))))

;; ---------------------------------------------------------------------------
;; (d) an unclassified band passes through unchanged; no band → no :context

(deftest xyflow-graph-unclassified-context-passes-through
  (testing "rf2-vbo66r — the default-on redaction never clobbers an
            UNclassified live value (the production value-free shape is a
            no-op under redaction)"
    (let [parsed (layout/project-definition flat-machine)
          graph  (projection/xyflow-graph
                   parsed {}
                   {:context-band (array-map :name "Alice" :count 3)})]
      (is (= "\"Alice\"" (value-for graph :name)) "ordinary string renders via pr-str")
      (is (= "3" (value-for graph :count))        "ordinary number renders"))))

(deftest xyflow-graph-no-context-band-emits-no-context
  (testing "rf2-vbo66r — with no :context-band fed, the root-container
            carries no :context rows (the band gates on presence)"
    (let [parsed (layout/project-definition flat-machine)
          graph  (projection/xyflow-graph parsed {} {})]
      (is (nil? (context-rows graph))
          "no band → no :data {:context} (nothing to serialise into an export)"))))
