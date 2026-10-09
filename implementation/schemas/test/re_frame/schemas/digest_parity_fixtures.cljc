(ns re-frame.schemas.digest-parity-fixtures
  "Shared fixtures for the JVM and CLJS app-schemas-digest parity tests.

  Spec 010 §Digest algorithm makes the digest byte-identical across runtimes,
  so both runtimes pin the SAME literal for each fixture: the literal is the
  cross-host comparison point, and a runtime whose pipeline drifts fails its
  own test. Each literal is `\"sha256:\"` + 16 lowercase hex over the
  line-sorted `<path-key> <sha256-hex>\\n` concatenation, where the path key is
  the CEDN-1 `canonical-bytes` of the path (`v[k::n]`), not `pr-str`."
  (:require [re-frame.schemas.digest]))

(def compute-digest-var
  "The private digest pipeline, reached through its var on both runtimes."
  #'re-frame.schemas.digest/compute-digest)

(defn compute-digest
  "Digest a `path->schema` map to its `\"sha256:\" + 16-hex` wire form."
  [path->schema]
  (compute-digest-var path->schema))

;; Empty (no lines), one keyword schema, and many: three nested paths whose
;; insertion order differs from the sorted line order, carrying a props map
;; whose key order differs from the canonical one. The whole-number double
;; prints `1.0` on the JVM and `1` on CLJS, so the canonicaliser emits the
;; integer it denotes; the CLJS reader collapses the `1.0` literal to 1 before
;; the fixture is built, which is the divergence the literal reconciles.
(def empty-set
  {:label    "empty-set"
   :input    {}
   :expected "sha256:e3b0c44298fc1c14"})

(def all-fixtures
  [empty-set
   {:label    "single-prim"
    :input    {[:n] :int}
    :expected "sha256:e7939756d704eaab"}
   {:label    "nested-paths"
    :input    {[:app :settings :theme] [:enum :light :dark]
               [:app :user :name]      :string
               [:app :user :age]       [:int {:min 0 :max 150}]}
    :expected "sha256:65d46c3f0b855ab3"}
   {:label    "whole-number-double"
    :input    {[:n] [:int {:min 1.0 :max 10.0}]}
    :expected "sha256:256998dfe0d8dc71"}])

;; A bare predicate is canonicalised to a token derived from the host's own
;; NAME for the function, never its address-bearing host print. The hosts name
;; it differently (and `:advanced` munges the CLJS name), so a fn-bearing
;; schema is process-stable per host and has no shared literal.
(def fn-bearing-schema [:map [:n pos-int?]])

(def fn-bearing-other-schema
  "The same shape under a different predicate, which must digest differently."
  [:map [:n neg-int?]])
