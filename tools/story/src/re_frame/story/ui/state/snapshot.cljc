(ns re-frame.story.ui.state.snapshot
  "Registry-snapshot leaf — single-call helper that builds a map of
  every Story-side artefact kind in one walk. Split from
  `re-frame.story.ui.state`.

  The shell takes a fresh snapshot of the Story registrar on every
  render: variants / stories / workspaces / modes / decorators /
  panels / tags are what's currently registered, and the sidebar /
  control panel / workspace panes consume the snapshot. Lives in its
  own leaf so future memoisation (e.g. cache keyed on the registrar
  mutation-tick) is local."
  (:require [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.tags      :as rf.story.tags]))

(defn registry-snapshot
  "Return a single map containing every Story-side artefact kind. Used
  by the shell's render fns to walk the registry without N atom-deref
  calls (and to support future memoisation if perf becomes an issue).

  The `:variants` slot carries EFFECTIVE tags: each variant body's `:tags`
  is resolved through the shared `re-frame.story.tags` resolver
  (`:extends`/story inheritance + `:!x` removal markers) before it reaches
  the sidebar tree, tag chips, tag filter, testable selection, and search —
  so a `:!x` marker never leaks as a chip or facet and inherited tags surface
  uniformly. This is the SINGLE UI-side resolution point; every downstream
  consumer reads the projected `:tags` verbatim."
  []
  (let [variants (rf.story.registrar/registrations :variant)
        stories  (rf.story.registrar/registrations :story)]
    {:stories      stories
     :variants     (rf.story.tags/resolve-body-tags variants {:story stories})
     :workspaces   (rf.story.registrar/registrations :workspace)
     :modes        (rf.story.registrar/registrations :mode)
     :decorators   (rf.story.registrar/registrations :decorator)
     :story-panels (rf.story.registrar/registrations :story-panel)
     :tags         (rf.story.registrar/registrations :tag)}))
