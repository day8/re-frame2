(ns re-frame.story.ui.state.snapshot
  "Registry-snapshot leaf — single-call helper that builds a map of
  every Story-side artefact kind in one walk. Split from
  `re-frame.story.ui.state` per rf2-gcpon.

  The shell takes a fresh snapshot of the Story registrar on every
  render: variants / stories / workspaces / modes / decorators /
  panels / tags are what's currently registered, and the sidebar /
  control panel / workspace panes consume the snapshot. Lives in its
  own leaf so future memoisation (e.g. cache keyed on the registrar
  mutation-tick rf2-zrswb / rf2-c5nwl) is local."
  (:require [re-frame.story.registrar :as registrar]))

(defn registry-snapshot
  "Return a single map containing every Story-side artefact kind. Used
  by the shell's render fns to walk the registry without N atom-deref
  calls (and to support future memoisation if perf becomes an issue)."
  []
  {:stories      (registrar/registrations :story)
   :variants     (registrar/registrations :variant)
   :workspaces   (registrar/registrations :workspace)
   :modes        (registrar/registrations :mode)
   :decorators   (registrar/registrations :decorator)
   :story-panels (registrar/registrations :story-panel)
   :tags         (registrar/registrations :tag)})
