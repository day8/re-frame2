(ns re-frame.schemas.test-fixture
  "Shared `:each` reset fixture for schemas-artefact JVM tests
  (rf2-of0hs, dup-of rf2-amco8).

  Pre-extraction the same 7-12-line `reset-runtime` body was duplicated
  across the JVM test files (`schemas_test.clj`,
  `schemas_sensitive_test.clj`, `schemas_conformance_test.clj`, …).
  Each copy reset the same registrar / frame / flows / schemas /
  boundary-warn slate and called `(rf/init! plain-atom/adapter)`.
  Drifting copies invited the cross-test-bleed that the per-frame
  side-tables exist to prevent.

  The canonical reset is here; test namespaces call
  `(use-fixtures :each tf/reset-runtime)` and inherit a uniform reset
  semantics. The deliberately narrower
  `schemas/printer_seam_test.clj` fixture (validator-only) stays
  separate by design.

  ## What gets reset

  - `(registrar/clear-all!)`
  - `(reset! frame/frames {})`
  - `(flows/reset-flows!)`
  - `(reset! schemas/schemas-by-frame {})`
  - `(schemas/reset-schema-validator!)` — restores Malli defaults
    per rf2-froe so a test that mutates the pluggable validator
    surface doesn't poison sibling tests.
  - `(schemas/clear-validator-unavailable-warned!)` — clears the
    rf2-fq7d2 `:rf.warning/schema-validator-unavailable` one-shot
    so test cases that exercise the unbound-validator path each
    start from a clean slate.
  - `(schemas/clear-walker-opaque-warned!)` — clears the rf2-jsokn
    `:rf.warning/schema-walker-opaque` one-shot so test cases that
    exercise the non-vector schema-registration path each start from
    a clean slate.
  - `(schemas/clear-edn-print-cache!)` /
    `(schemas/clear-sensitive-paths-cache!)` — reset the printer +
    sensitive-walker memo caches (rf2-17sqc). The memos are
    process-lifetime caches bounded by the registered-schema
    cardinality (schemas register once at boot); tests that register
    many distinct fresh schemas (`schemas_concurrency_stress_test`)
    would otherwise grow the caches unbounded across the suite. Clearing
    them `:each` keeps the suite's steady-state memory bounded and pins
    the boot-once invariant at the test seam — not with a bounded LRU.
  - `(registrar/clear-warning-caches!)` — clears the registrar's
    per-(kind, id) warn-once caches (missing-doc, registration-collision)
    so each test sees a clean suppression slate.
  - `(rf/init! plain-atom/adapter)` — installs the schemas-artefact
    JVM tests' standard substrate."
  (:require [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime
  "The canonical `:each` fixture for schemas-artefact JVM tests."
  [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (reset! schemas/schemas-by-frame {})
  (schemas/reset-schema-validator!)
  (schemas/clear-validator-unavailable-warned!)
  (schemas/clear-walker-opaque-warned!)
  (schemas/clear-edn-print-cache!)
  (schemas/clear-sensitive-paths-cache!)
  (registrar/clear-warning-caches!)
  (rf/init! plain-atom/adapter)
  (test-fn))
