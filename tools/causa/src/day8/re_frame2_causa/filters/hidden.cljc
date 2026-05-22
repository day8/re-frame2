(ns day8.re-frame2-causa.filters.hidden
  "Pure derivation for the L2 'N events hidden by filters' indicator
  (rf2-jvghz, defect #1).

  ## Why

  After the epoch-per-event merges, Causa's L2 event list could look
  broken on reload: persisted filters (a `:machine` IN-pill under
  `re-frame2.causa.filters.v1`, a frame pin under
  `re-frame2.causa.frame-switcher.v1`) survived in localStorage and
  silently suppressed cascade rows with NO visible indicator. The L2
  list reads `:rf.causa/filtered-cascades` (filtered) while the raw
  stream lives on `:rf.causa/cascades`; when filters suppressed rows
  the list was indistinguishable from a broken tool.

  This ns is the pure data primitive behind the affordance: given the
  raw + filtered visible-cascade counts and the active filter state,
  it answers 'is anything hidden, how many, and what is the cause?'.

  ## The count contract

  The hidden count is computed against the SAME visible-row set the L2
  list renders — i.e. both the raw and filtered vectors must already be
  passed through the list's `l2-cascade-visible?` predicate by the
  caller (the `:rf.causa/hidden-by-filters` sub does this). That keeps
  the indicator's N consistent with the rows the user actually sees:
  the `:ungrouped` bucket never inflates the count, and the
  filtered-to-empty / filtered-to-one cases both report the true
  suppressed total.

  Pure data; JVM-runnable. Tests in
  `tools/causa/test/.../filters/hidden_test.cljc`."
  (:require [day8.re-frame2-causa.filters.typed-predicates :as typed]))

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

(defn frame-pinned?
  "True iff a frame pin is active — i.e. the picker has selected a
  specific frame so `:rf.causa/filtered-cascades` restricts to it.
  nil means no frame filter."
  [frame-id]
  (some? frame-id))

(defn mutes-present?
  "True iff at least one event-id is muted. nil / `#{}` → false."
  [muted]
  (boolean (seq muted)))

(defn any-filter-active?
  "True iff ANY suppressing surface is active: IN/OUT pills, a frame
  pin, or a non-empty mute set. This is the predicate behind 'is there
  anything a Clear-filters click would reset?'. It is independent of
  the hidden COUNT — a pill can be active yet hide nothing (it happens
  to match every current cascade); the count is what gates the
  indicator's render, this predicate is what 'Clear filters' acts on."
  [{:keys [filters frame muted]}]
  (boolean
    (or (pills-present? filters)
        (frame-pinned? frame)
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
  "The full indicator model for the L2 list. Pure — takes the raw +
  filtered visible counts and the active filter state, returns the map
  the view renders against:

      {:hidden          <int>     ; suppressed visible-row count
       :raw-count       <int>
       :filtered-count  <int>
       :visible?        <bool>    ; should the indicator render?
       :any-active?     <bool>    ; would Clear-filters reset anything?
       :pills           [{…} …]   ; IN/OUT pill summaries
       :frame           <kw|nil>  ; pinned frame, nil = unpinned
       :muted-count     <int>}    ; muted event-ids

  `state` is `{:filters {:in [] :out []} :frame <kw|nil> :muted #{}}`."
  [raw-visible-count filtered-visible-count state]
  (let [hidden (hidden-count raw-visible-count filtered-visible-count)]
    {:hidden         hidden
     :raw-count      raw-visible-count
     :filtered-count filtered-visible-count
     :visible?       (indicator-visible? hidden)
     :any-active?    (any-filter-active? state)
     :pills          (pill-summaries (:filters state))
     :frame          (:frame state)
     :muted-count    (count (or (:muted state) #{}))}))
