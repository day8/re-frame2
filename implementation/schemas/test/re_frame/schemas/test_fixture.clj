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
  - `(reset! flows/flows {})`
  - `(reset! schemas/schemas-by-frame {})`
  - `(schemas/reset-schema-validator!)` — restores Malli defaults
    per rf2-froe so a test that mutates the pluggable validator
    surface doesn't poison sibling tests.
  - `(spec/clear-boundary-warned-handler-ids!)` — clears the
    rf2-r2uh boundary-interceptor warn-once cache so each test
    sees a clean suppression slate.
  - `(rf/init! plain-atom/adapter)` — installs the schemas-artefact
    JVM tests' standard substrate."
  (:require [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.spec :as spec]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime
  "The canonical `:each` fixture for schemas-artefact JVM tests."
  [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (schemas/reset-schema-validator!)
  (spec/clear-boundary-warned-handler-ids!)
  (rf/init! plain-atom/adapter)
  (test-fn))
