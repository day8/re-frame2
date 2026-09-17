(ns re-frame.schemas.walker-literal-operand-fixtures
  "Shared, host-agnostic corpus for the operator-aware schema-opacity walk
  (rf2-3fc89f.12). Both the JVM parity test (`re-frame.schemas-walker-literal-
  operand-test`) and the CLJS parity test (`re-frame.schemas-walker-literal-
  operand-cljs-test`) read these vectors, so `schema-has-opaque-child?` is
  pinned to the SAME classification on both runtimes.

  Background — Spec 010 §The `:schema` value is opaque to re-frame: the opacity
  walk recurses a vector-form Malli schema's REAL child-schema positions to
  detect a nested opaque (compiled `m/schema`) value whose per-slot
  `:sensitive?` flag the pure-data walker cannot see. A Malli vector form is
  `[op props? & tail]`, but the tail is a child SCHEMA only for STRUCTURAL ops.
  For LITERAL / config ops the tail is DATA (`[:= 42]` holds a value, `[:enum
  1 2]` holds members, `[:> 10]` a comparator bound, `[:re \"x\"]` a pattern),
  and recursing into it false-flags an ordinary literal as an opaque compiled
  child — the rf2-3fc89f.12 bug. The walk is now operator-aware and projects
  the true child schemas per op.

  `m/schema` is `.cljc` and on both host classpaths (the schemas artefact deps
  on metosin/malli; `re-frame.schemas.malli` requires it), so the compiled-
  object opaque corpus loads identically on the JVM and under `:node-test`."
  (:require [malli.core :as m]))

(def not-opaque-forms
  "Fully walkable schemas whose only vector tails are literal/config DATA (`:=`
  value, `:enum` members, comparator bounds, `:re` pattern) or ordinary nested
  schemas — NONE nests an opaque value, so `schema-has-opaque-child?` MUST be
  false. The rf2-3fc89f.12 bug false-flagged every literal-operand form true."
  [;; bare literal / comparator / regex operands
   [:= 42] [:= "x"] [:= :kw] [:= 3.14]
   [:enum 1 2 3] [:enum "a" "b"] [:enum :x :y]
   [:> 10] [:>= 10] [:< 10] [:<= 10] [:not= 0]
   [:re "a.*z"]
   ;; literal operands nested in REAL structural positions
   [:cat [:= :demo/e] [:= 42]]                     ;; event-vector schema
   [:tuple [:= :k] [:enum 1 2]]
   [:vector [:enum :a :b]]
   [:set [:= 1]]
   [:map-of :string [:= 7]]
   [:map [:code [:enum 200 404]] [:pi [:= 3.14]] [:name :string]]
   [:multi {:dispatch :t} [:a [:map [:t :keyword] [:n [:= 1]]]]]
   [:orn [:lit [:= 42]] [:other :int]]
   ;; plain walkable forms (no literal operands, no opaque child)
   [:map [:id :int] [:name :string]]
   [:string] [:int {:min 0}]
   [:vector :string] [:tuple :int :string]
   [:and :int [:> 0]] [:or :int :string]
   [:maybe [:enum :a :b]]
   ;; rf2-3aafh — a `:map` ENTRY whose KEY is the keyword `:ref`. An entry
   ;; head is DATA (a map key), never an operator, so this must stay walkable
   ;; even though the explicit reference FORM `[:ref ::k]` now fails closed.
   ;; The shape is real: implementation/routing carries it in its own tests.
   [:map [:ref {:optional true} :string]]])

(def opaque-forms
  "Schemas that nest a GENUINELY opaque (compiled `m/schema`) value in a REAL
  child-schema position — must fail closed (true) so an invisible per-slot
  `:sensitive?` flag cannot hide behind an unintrospectable child. This is the
  fail-closed guarantee the operator-aware walk MUST preserve."
  [(m/schema [:string {:sensitive? true}])                  ;; root opaque
   [:map [:token (m/schema [:string {:sensitive? true}])]]  ;; :map slot tail
   [:map [:a :int] [:b (m/schema [:int])]]                  ;; later :map slot
   [:vector (m/schema [:int])]                              ;; container element
   [:set (m/schema [:int])]
   [:sequential (m/schema [:int])]
   [:cat [:= :e] (m/schema [:int])]                         ;; :cat element
   [:tuple (m/schema [:int]) :string]                       ;; :tuple element
   [:multi {:dispatch :t} [:a (m/schema [:map])]]           ;; :multi branch
   [:orn [:x (m/schema [:int])]]                            ;; :orn branch
   [:map-of (m/schema [:string]) :int]                      ;; :map-of key
   [:map-of :string (m/schema [:int])]                      ;; :map-of value
   [:and (m/schema [:int])]                                 ;; combinator child
   [:or :int (m/schema [:string])]
   [:maybe (m/schema [:int])]])

(def unknown-op-forms
  "Unclassified operator shapes — the walk cannot prove the tail is not a
  schema-bearing position, so it fails CLOSED (true), matching the fail-closed
  posture for genuinely unknown/unclassifiable operators."
  [[:my/custom-op [:string]]
   [:acme.registry/thing 1 2 3]])

(def local-registry-forms
  "Schemas carrying a Malli LOCAL `{:registry ...}` on their own props
  (rf2-amgtr). Every one is walkable at its root and reaches its referenced
  shapes only through keyword references the pure-data walk resolves nowhere,
  so each MUST fail CLOSED (true) — pre-fix they all walked to `{}` for both
  flags and classified NOT opaque, while Malli honoured the `:sensitive?`
  declared inside the registry.

  The `:registry` key is op-INDEPENDENT (Malli honours it on any vector form),
  so the classification keys on the PROPS rather than on the op — the `:map`
  and `:and` entries here are caught by the same branch as the `:schema` one,
  and the last entry pins that it also fails closed NESTED at depth."
  [[:schema {:registry {:fixture/user [:map [:pw {:sensitive? true} :string]]}}
    :fixture/user]
   [:map {:registry {:fixture/pw [:string {:sensitive? true}]}} [:pw :fixture/pw]]
   [:and {:registry {:fixture/pw [:string {:sensitive? true}]}} :fixture/pw]
   [:map [:auth [:schema {:registry {:fixture/user [:map [:pw {:sensitive? true} :string]]}}
                 :fixture/user]]]])

(def registry-ref-forms
  "Schemas carrying an EXPLICIT `[:ref …]` reference form (rf2-3aafh). A `:ref`
  names a schema held in some registry the pure-data walk never consults, so
  every per-slot `:sensitive?` / `:large?` flag on the referenced shape lives
  where the walk cannot reach it — exactly the `local-registry-forms` blind
  spot (rf2-amgtr) reached through the OTHER keyword-reference spelling. Each
  MUST fail CLOSED (true).

  Pre-fix, `:ref` sat in the walker's `opacity-literal-ops`, so every form here
  classified walkable-and-flag-free and its validation failure shipped the
  value VERBATIM — including on the always-on `:rf.error/cofx-value-invalid`
  production surface.

  The reference keywords resolve NOWHERE on purpose: the walker never looks
  them up, and the classification must not depend on whether they would
  resolve. That is the whole point — a reference the walk cannot follow is
  opaque whether or not its target exists.

  NOTE the contrast with the bare-keyword carve-out, which is untouched: a
  bare `::user` stays walkable because a registry reference cannot be told
  from a primitive (`:int` / `:string`). An explicit `[:ref …]` CAN be told,
  so it gets no such carve-out."
  [[:ref :fixture/user]                                  ;; root reference
   [:ref {:sensitive? true} :fixture/user]               ;; props on the ref
   [:map [:home [:ref :fixture/user]]]                   ;; :map slot tail
   [:vector [:ref :fixture/node]]                        ;; container element
   [:map [:billing [:ref :fixture/address]]
         [:shipping [:ref :fixture/address]]]            ;; twice, two slots
   [:cat [:= :demo/e] [:ref :fixture/user]]])            ;; event-vector tail
