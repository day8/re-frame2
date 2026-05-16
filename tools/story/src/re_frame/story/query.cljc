(ns re-frame.story.query
  "Public registry query API — the read-side surface over Story's
  registrar side-table.

  Per the rf2-l8eso Phase-2 facade thinning: the public symbols are
  re-exported from `re-frame.story`; users normally call them as
  `re-frame.story/registrations` etc. Story-internal consumers and
  read-only tools (e.g. story-mcp introspection) may require this
  ns directly to avoid pulling the canonical-installer chain.

  Every fn / def in this ns is a thin delegation to
  `re-frame.story.registrar` or `re-frame.story.schemas`; no mutation,
  no lifecycle / runtime / play state. Pure-data reads — JVM + CLJS
  portable, same shape across hosts."
  (:require [re-frame.story.registrar :as registrar]
            [re-frame.story.schemas   :as schemas]))

;; ---- per-kind registry query ---------------------------------------------

(defn registrations
  "Return the `{id → body}` map for `kind`, or `{}`. Stable shape across
  JVM and CLJS — same as `re-frame.registrar/registrations`. Use this in
  tooling that enumerates the registered Story artefacts.

  Mirror of the spec/001 §Public registrar query API for Story's
  side-table. The Story registry is logically a peer of the framework
  registrar — see IMPL-SPEC §1.1 + bd rf2-7ho2 for the design rationale."
  [kind]
  (registrar/registrations kind))

(defn handler-meta
  "Return the body for `(kind, id)`, or nil."
  [kind id]
  (registrar/handler-meta kind id))

(defn ids
  "Return the id set for `kind`."
  [kind]
  (registrar/ids kind))

(defn registered?
  "True iff `(kind, id)` is registered."
  [kind id]
  (registrar/registered? kind id))

(defn all-kinds-with-counts
  "{kind → count} — dev tooling overlay."
  []
  (registrar/all-kinds-with-counts))

;; ---- convenience lookups -------------------------------------------------

(defn variants-of
  "Return the set of variant ids whose parent is `story-id`."
  [story-id]
  (registrar/variants-of story-id))

(defn variants-by-story
  "Return a `{story-id #{variant-id ...}}` index built in one pass over
  the variant side-table — O(V), where V is the variant count. Stories
  with zero registered variants land in the result with an empty set.

  HOT PATH: agents tend to spam `list-stories` (story-mcp's most-called
  introspection tool); the single-pass index replaces the O(S × V)
  walk of calling `variants-of` per story (rf2-d3iso)."
  []
  (registrar/variants-by-story))

(defn variants-with-tags
  "Per IMPL-SPEC §3.2 — return the set of variant ids whose `:tags`
  intersects `query-tags`. The assertions/play surface leans on this;
  the render shell leans on this to compose the sidebar tree."
  [query-tags]
  (registrar/variants-with-tags query-tags))

(defn list-tags
  "Per IMPL-SPEC §7.4 — return the set of registered tag ids. Tools
  enumerate this set before assigning tags to a variant."
  []
  (ids :tag))

(defn list-modes
  "Per IMPL-SPEC §7.4 — return the set of registered mode ids."
  []
  (ids :mode))

(defn tags-by-axis
  "Per spec/001 §reg-tag — return the set of registered tag ids whose
  body's `:axis` equals `axis-kw` (e.g. `:status` / `:role` / `:team` /
  `:feature`). The sidebar tag-filter UI uses this to group registered
  tags into collapsible facet rows (rf2-v05qb SB9 parity). Returns the
  empty set if no tag carries that axis."
  [axis-kw]
  (registrar/tags-by-axis axis-kw))

(defn tags-without-axis
  "Per spec/001 §reg-tag — return the set of registered tag ids whose
  body carries no `:axis`. The sidebar renders these in a trailing
  un-grouped facet row."
  []
  (registrar/tags-without-axis))

(defn tags-default-excluded
  "Per spec/001 §reg-tag — return the set of registered tag ids whose
  body's `:default-filter` is `:exclude`. The sidebar tag-filter
  pre-excludes variants carrying any of these at boot (e.g.
  `:internal` / `:experimental`)."
  []
  (registrar/tags-default-excluded))

(def canonical-tags
  "Re-export of the seven canonical tag ids from spec/007 §Inclusion
  tags. Stable across hosts."
  schemas/canonical-tags)

(def canonical-axes
  "Re-export of the four canonical facet axes documented in spec/001
  §reg-tag — `:status`, `:role`, `:team`, `:feature` (rf2-7ncf9 SB9
  facet taxonomy). Stable across hosts."
  schemas/canonical-axes)

(def canonical-status-values
  "Re-export of the recommended `:status` axis vocabulary."
  schemas/canonical-status-values)

(def canonical-role-values
  "Re-export of the recommended `:role` axis vocabulary."
  schemas/canonical-role-values)

(defn tag->axis-index
  "Per spec/001 §reg-tag — return a `{tag-id → axis-kw}` map across
  every registered tag, in one O(T) pass. Tags without `:axis` map to
  `:re-frame.story.registrar/no-axis`. The sidebar's facet-grouped
  filter row + the `:tag-filter` AND-across-axes predicate (rf2-7ncf9)
  consume this."
  []
  (registrar/tag->axis-index))
