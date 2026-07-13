(ns re-frame.ui.digest-probe.build-hook
  "Test-only lifecycle probe for the real Shadow watch transaction fixture."
  (:require [clojure.java.io :as io]
            [re-frame.ui.compiler.build :as build]))

(def ^:private target-dir (io/file "target" "ui-digest-probe"))
(def ^:private fail-marker (io/file target-dir "fail-after-compile"))
(def ^:private accepted-file (io/file target-dir "accepted-digest.txt"))
(def ^:private prepare-file (io/file target-dir "prepare-snapshots.tsv"))

(defn hook
  {:shadow.build/stages #{:compile-prepare :flush}}
  [{build-id :shadow.build/build-id
    stage :shadow.build/stage
    :as build-state}]
  (when (= :ui-digest-probe-lazy build-id)
    (case stage
      :compile-prepare
      ;; The production UI hook is ordered first in :build-defaults. Recording
      ;; its incoming accepted scalar/version directly proves that a later
      ;; failed candidate was discarded by Shadow's retained state, rather
      ;; than merely observing that this test hook skipped its flush write.
      (let [{:keys [version digest]} (build/accepted-snapshot build-state)]
        (io/make-parents prepare-file)
        (spit prepare-file (str version "\t" digest "\n") :append true))

      :flush
      (do
        ;; Shadow's target flush has already run and the re-frame.ui
        ;; compile-finish candidate is in this functional state. This failure
        ;; is deliberately downstream of both.
        (when (.exists fail-marker)
          (throw (ex-info "intentional digest-probe failure after compile-finish"
                          {:build-id build-id :fixture :downstream-failure})))
        (let [{:keys [version digest]} (build/accepted-snapshot build-state)]
          (io/make-parents accepted-file)
          (spit accepted-file (str version "\t" digest))))

      nil))
  build-state)
