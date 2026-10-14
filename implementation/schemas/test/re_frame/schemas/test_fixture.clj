(ns re-frame.schemas.test-fixture
  "Shared `:each` reset fixture for the schemas-artefact JVM tests: one reset
  of the registrar, frames, flows, schema registry, schema fns and warn-once
  caches, so no per-file copy can drift and let state bleed between tests.
  `schemas/printer_seam_test.clj` keeps a narrower fixture of its own.

  `reg-app-schema` needs an established frame scope and `init!` creates no
  `:rf/default`, so the fixture ensures that frame and binds
  `*current-frame*` to it; an explicit `{:frame ...}` overrides the binding."
  (:require [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime
  "The canonical `:each` fixture for schemas-artefact JVM tests."
  [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
  (rf.schemas/clear-validator-unavailable-warned!)
  (rf.schemas/clear-walker-opaque-warned!)
  (rf.registrar/clear-warning-caches!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (binding [rf.frame/*current-frame* :rf/default]
    (test-fn)))
