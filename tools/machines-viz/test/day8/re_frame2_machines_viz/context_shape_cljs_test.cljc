(ns day8.re-frame2-machines-viz.context-shape-cljs-test
  "Pure-data tests for the declared-over-inferred static Context-shape
  derivation (rf2-3q4k5b · EP-0005). Pins the two tiers:

    - DECLARED — a `:data-schema` (Malli `[:map …]`) yields an AUTHORITATIVE
      `{key → type-caption}` shape with `:inferred? false` (the chart drops
      the `inferred from :data` badge).
    - INFERRED — no schema → the one-sample shape from initial `:data` with
      `:inferred? true` (rf2-5tz9p's behaviour, unchanged).

  The `_cljs_test` naming follows the tools/machines-viz/test convention —
  Cognitect's runner picks it up via `.*-test$`; Shadow's `:node-test` build
  via `cljs-test$`."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-machines-viz.context-shape :as cs]))

;; ---- inferred (no schema) ----------------------------------------------

(deftest inferred-shape-from-data-sample
  (testing "rf2-5tz9p — with no `:data-schema`, the shape is INFERRED from one
            sample of initial `:data` and flagged `:inferred? true`."
    (let [definition {:initial :idle
                      :data    {:opened-count 0 :held-open? false :trail []
                                :note "x" :tag :a :nested {} :many #{}}
                      :states  {:idle {}}}
          result     (cs/static-context-shape definition)]
      (is (= {:opened-count "number"
              :held-open?   "boolean"
              :trail        "vector"
              :note         "string"
              :tag          "keyword"
              :nested       "map"
              :many         "set"}
             (:shape result))
          "each key maps to its sampled value's type caption (shape, not value)")
      (is (true? (:inferred? result))
          "no schema → inferred? true (the chart keeps the inferred badge)"))))

(deftest nil-when-no-schema-and-no-data
  (testing "rf2-vcnvj — neither a `:data-schema` nor a map `:data` → nil so the
            Context panel stays hidden."
    (is (nil? (cs/static-context-shape {:initial :a :states {:a {}}})))
    (is (nil? (cs/static-context-shape {:initial :a :data nil :states {:a {}}})))
    (is (nil? (cs/static-context-shape nil)))))

;; ---- declared (authoritative, from :data-schema) -----------------------

(deftest declared-shape-is-authoritative-not-inferred
  (testing "rf2-3q4k5b — a `:data-schema` (Malli `[:map …]`) yields the
            AUTHORITATIVE shape off the SCHEMA (not the `:data` sample) and is
            flagged `:inferred? false` so the chart drops the inferred badge."
    (let [definition {:initial     :anon
                      ;; The sampled :data is deliberately PARTIAL/misleading
                      ;; (no :token key, :retries nil) — the declared schema
                      ;; must win, proving authority over the sample.
                      :data        {:retries nil}
                      :data-schema [:map
                                    [:retries :int]
                                    [:token {:optional true} [:maybe :string]]
                                    [:roles [:vector :keyword]]
                                    [:active :boolean]]
                      :states      {:anon {}}}
          result     (cs/static-context-shape definition)]
      (is (= {:retries "number"
              :token   "string?"
              :roles   "vector"
              :active  "boolean"}
             (:shape result))
          "shape is read off the SCHEMA's :map entries (authoritative), with
           :maybe → optional caption and per-entry props skipped")
      (is (false? (:inferred? result))
          "declared schema → inferred? false (the chart drops the inferred
           badge and shows `declared`)"))))

(deftest declared-shape-handles-keyword-and-collection-heads
  (testing "rf2-3q4k5b — the Malli KEYWORD type forms + structural heads map
            onto the inferred caption vocabulary so a declared shape reads
            consistently with an inferred one."
    (let [result (cs/static-context-shape
                   {:initial :s
                    :data-schema [:map
                                  [:n :int]
                                  [:name :string]
                                  [:bag [:set :keyword]]
                                  [:lookup [:map-of :keyword :int]]
                                  [:kind [:enum :a :b :c]]]
                    :states {:s {}}})]
      (is (= {:n      "number"
              :name   "string"
              :bag    "set"
              :lookup "map"
              :kind   "keyword"}
             (:shape result)))
      (is (false? (:inferred? result))))))

(deftest declared-shape-handles-quoted-predicate-symbols
  (testing "rf2-3q4k5b — a `:data-schema` whose entries carry QUOTED predicate
            SYMBOLS (`'pos-int?` / `'string?`) is introspectable as plain data
            and maps onto the caption vocabulary. A bare predicate FUNCTION
            (unquoted, resolved to a var) carries no portable name, so it
            falls back to `value`."
    (let [result (cs/static-context-shape
                   {:initial :s
                    :data-schema [:map
                                  ['n 'pos-int?]
                                  ['name 'string?]]
                    :states {:s {}}})]
      (is (= {'n "number" 'name "string"} (:shape result))
          "quoted predicate symbols map onto number / string captions"))))

(deftest non-map-schema-falls-back-to-inferred
  (testing "rf2-3q4k5b — a non-`:map` `:data-schema` (validates :data as a
            whole, no per-key table) yields no declared shape; the derivation
            falls back to the inferred `:data` sample."
    (let [result (cs/static-context-shape
                   {:initial     :s
                    :data        {:n 1}
                    :data-schema [:and [:map [:n :int]] [:fn 'some?]]
                    :states      {:s {}}})]
      ;; The top-level head is :and, not :map → no per-key declared table.
      (is (= {:n "number"} (:shape result)))
      (is (true? (:inferred? result))
          "non-:map schema → fall back to inferred from the :data sample"))))
