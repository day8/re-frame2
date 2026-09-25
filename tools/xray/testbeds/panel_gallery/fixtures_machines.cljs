(ns panel-gallery.fixtures-machines
  "Pure fixture builders for the Xray Machines tab gallery.

  The Machines panel reads from `:rf.xray/machine-inspector-data`, a
  composite over `:rf.xray/registered-machines`,
  `:rf.xray/machine-snapshots`, `:rf.xray/machine-definitions`,
  `:rf.xray/trace-buffer`, `:rf.xray/selected-machine-id`, and
  `:rf.xray/target-frame`.

  Each of registered-machines / machine-snapshots / machine-
  definitions has a TEST-ONLY override slot the panel reads if
  present:

    - `:rf.xray/set-registered-machines-override-for-test`
    - `:rf.xray/set-machine-snapshots-override-for-test`
    - `:rf.xray/set-machine-definitions-override-for-test`

  These exist precisely for gallery / test fixtures — the production
  path reads through the `:rf/machine?` filter + the `:rf/machine` registrar projection +
  the runtime-db's `[:rf.runtime/machines :snapshots]` subtree. Variant
  `:setup` dispatch the override events to seed each slot.")

;; ---- transition-trace buffers ------------------------------------------

(defn no-transitions-buffer
  "Empty trace buffer — the variant seeds no machine activity."
  []
  [])
