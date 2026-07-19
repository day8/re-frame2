(ns re-frame.ui.digest-probe.warm-watch.leaf
  "A leaf declaring source that no view consumes, so the runner can MOVE, RENAME,
  and DELETE it through real file edits without breaking any consumer's compile.

  * MOVE (same namespace, new file): the runner relocates this file to the
    fixture's second source-path root, keeping the namespace `…warm-watch.leaf`.
    Because Shadow's `:build-sources` membership — and hence re-frame.ui's
    eviction unit — is keyed by the declaring NAMESPACE, not the resource/file,
    :probe-leaf's ownership survives the file move unchanged.

  * RENAME (namespace changes): the runner renames this source to
    `…warm-watch.leaf-renamed` and updates the client's `:require`. The old
    namespace leaves `:build-sources`, the new one enters, and :probe-leaf is
    re-owned by the renamed namespace — the saved-source graph delta.

  * DELETE: the runner removes the (renamed) file and its client `:require`. The
    namespace leaves `:build-sources` with no successor, so :probe-leaf is evicted
    from the accepted manifest with no page reload."
  (:require [re-frame.ui :as ui]))

(ui/custom-element :probe-leaf {:properties #{:flag}})
