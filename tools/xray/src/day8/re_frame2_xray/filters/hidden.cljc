(ns day8.re-frame2-xray.filters.hidden
  "Pure derivation for the events-ribbon 'N events hidden by filters'
  message.

  ## Why

  The L2 event list reads `:rf.xray/filtered-cascades` (filtered) while
  the raw stream lives on `:rf.xray/cascades`. When filters suppress
  rows — for example a persisted `:machine` IN-pill from
  `re-frame2.xray.filters.v1` in localStorage — the indicator makes the
  suppression visible so the list never reads as a broken tool.

  This ns is the pure data primitive behind the affordance: given the
  raw + filtered visible-cascade counts and the active filter state,
  it answers 'is anything hidden, how many, and what is the cause?'.

  ## Frame is a view SCOPE, not a filter

  The picker-selected frame is a single, defaulted VIEW SCOPE — it is
  NOT part of the filter chain conceptually. It is therefore NEVER
  counted as 'hidden', never listed as a cause, and never reset by
  Clear Filters. The caller computes the raw/filtered counts WITHIN the
  selected frame so switching frames does not inflate the count; this
  ns only models pill + mute suppression.

  ## The count contract

  The hidden count is computed against the SAME visible-row set the L2
  list renders — i.e. both the raw and filtered vectors must already be
  passed through the list's `l2-cascade-visible?` predicate by the
  caller (the `:rf.xray/hidden-by-filters` sub does this). That keeps
  the message's N consistent with the rows the user actually sees:
  the `:ungrouped` bucket never inflates the count, and the
  filtered-to-empty / filtered-to-one cases both report the true
  suppressed total.

  Pure data; JVM-runnable. Tests in
  `tools/xray/test/.../filters/hidden_test.cljc`."
  (:require [day8.re-frame2-xray.filters.typed-predicates :as typed]))

(defn hidden-count
  "How many visible cascades the active filters / frame-pin / mutes are
  suppressing: `(max 0 (- raw-visible-count filtered-visible-count))`.

  Both counts MUST be taken over the list's visible-row set (post
  `l2-cascade-visible?`) so N matches the rows the user sees. Clamped
  at zero so a transient count skew (filtered briefly larger than raw
  mid-recompute) never renders a negative."
  [raw-visible-count filtered-visible-count]
  (max 0 (- raw-visible-count filtered-visible-count)))

(defn pills-present?
  "True iff the `:active-filters` map carries at least one IN or OUT
  pill. nil / `{:in [] :out []}` → false."
  [{:keys [in out]}]
  (boolean (or (seq in) (seq out))))

(defn mutes-present?
  "True iff at least one event-id is muted. nil / `#{}` → false."
  [muted]
  (boolean (seq muted)))

(defn any-filter-active?
  "True iff ANY suppressing FILTER is active: IN/OUT pills or a non-empty
  mute set. This is the predicate behind 'is there anything a Clear
  Filters click would reset?'. It is independent of the hidden COUNT — a
  pill can be active yet hide nothing (it happens to match every current
  cascade); the count is what gates the message's render, this predicate
  is what 'Clear Filters' acts on.

  The frame is a view SCOPE, not a filter — it is NOT part
  of this predicate, so a frame selection alone never shows the events-
  ribbon action cluster and Clear Filters never resets the frame."
  [{:keys [filters muted]}]
  (boolean
    (or (pills-present? filters)
        (mutes-present? muted))))

(defn indicator-visible?
  "Should the 'N hidden by filters' indicator render? True iff the
  hidden count is positive — i.e. the filtered visible set is strictly
  smaller than the raw visible set. Covers the filtered-to-empty and
  filtered-to-one cases (both have a positive hidden count whenever raw
  had more rows). A hidden count of zero hides the indicator even when
  a filter is technically active but currently suppressing nothing."
  [hidden]
  (pos? hidden))

(defn pill-summaries
  "Flatten the active IN/OUT pills into a render-ready vector of
  `{:mode :in|:out :label <str> :glyph <str-or-nil>}` so the indicator
  can list the offending pills as the visible cause. Order: IN pills
  then OUT pills, preserving bucket order. Pure data — the view wraps
  each entry in chrome."
  [{:keys [in out]}]
  (into
    (mapv (fn [p] {:mode  :in
                   :label (typed/pill-label p)
                   :glyph (typed/pill-glyph p)})
          (or in []))
    (mapv (fn [p] {:mode  :out
                   :label (typed/pill-label p)
                   :glyph (typed/pill-glyph p)})
          (or out []))))

(defn summary
  "The full message model for the events ribbon. Pure — takes the raw +
  filtered visible counts (both already scoped to the selected frame by
  the caller) and the active filter state, returns the map the view
  renders against:

      {:hidden          <int>     ; suppressed visible-row count
       :raw-count       <int>
       :filtered-count  <int>
       :visible?        <bool>    ; should the N-hidden message render?
       :any-active?     <bool>    ; would Clear Filters reset anything?
       :pills           [{…} …]   ; IN/OUT pill summaries
       :muted-count     <int>}    ; muted event-ids

  `state` is `{:filters {:in [] :out []} :muted #{}}`. The
  frame is a view scope, NOT a filter — it is excluded from this model
  entirely (no `:frame` key, never a cause, never counted as hidden)."
  [raw-visible-count filtered-visible-count state]
  (let [hidden (hidden-count raw-visible-count filtered-visible-count)]
    {:hidden         hidden
     :raw-count      raw-visible-count
     :filtered-count filtered-visible-count
     :visible?       (indicator-visible? hidden)
     :any-active?    (any-filter-active? state)
     :pills          (pill-summaries (:filters state))
     :muted-count    (count (or (:muted state) #{}))}))
