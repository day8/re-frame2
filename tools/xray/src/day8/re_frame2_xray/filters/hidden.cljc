(ns day8.re-frame2-xray.filters.hidden
  "Pure derivation for the events-ribbon 'N events hidden by filters'
  message.

  ## Why

  The L2 event list reads `:rf.xray/filtered-event-bundles` (filtered) while
  the raw stream lives on `:rf.xray/event-bundles`. When filters suppress
  rows — for example a `:machine` IN-pill narrowing the list to one
  machine — the indicator makes the suppression visible so the list
  never reads as a broken tool.

  This ns is the pure data primitive behind the affordance: given the
  raw + filtered visible-event-bundle counts, it answers 'is anything
  hidden, and how many?'.

  ## Frame is a view SCOPE, not a filter

  The picker-selected frame is a single, defaulted VIEW SCOPE — it is
  NOT part of the filter chain conceptually. It is therefore NEVER
  counted as 'hidden', never listed as a cause, and never touched by
  removing filters. The caller computes the raw/filtered counts WITHIN
  the selected frame so switching frames does not inflate the count;
  this ns only models pill + mute suppression.

  ## The count contract

  The hidden count is computed against the SAME visible-row set the L2
  list renders — i.e. both the raw and filtered vectors must already be
  passed through the list's `l2-event-bundle-visible?` predicate by the
  caller (the `:rf.xray/hidden-by-filters` sub does this). That keeps
  the message's N consistent with the rows the user actually sees:
  the `:ungrouped` bucket never inflates the count, and the
  filtered-to-empty / filtered-to-one cases both report the true
  suppressed total.

  Pure data; JVM-runnable. Tests in
  `tools/xray/test/.../filters/hidden_cljs_test.cljc`.")

(defn hidden-count
  "How many visible event-bundles the active filters / mutes are
  suppressing: `(max 0 (- raw-visible-count filtered-visible-count))`.

  Both counts MUST be taken over the list's visible-row set (post
  `l2-event-bundle-visible?`) so N matches the rows the user sees. Clamped
  at zero so a transient count skew (filtered briefly larger than raw
  mid-recompute) never renders a negative."
  [raw-visible-count filtered-visible-count]
  (max 0 (- raw-visible-count filtered-visible-count)))

(defn indicator-visible?
  "Should the 'N hidden by filters' indicator render? True iff the
  hidden count is positive — i.e. the filtered visible set is strictly
  smaller than the raw visible set. Covers the filtered-to-empty and
  filtered-to-one cases (both have a positive hidden count whenever raw
  had more rows). A hidden count of zero hides the indicator even when
  a filter is technically active but currently suppressing nothing."
  [hidden]
  (pos? hidden))

(defn summary
  "The message model for the events ribbon. Pure — takes the raw +
  filtered visible counts (both already scoped to the selected frame by
  the caller) and returns the map the view renders against:

      {:hidden   <int>     ; suppressed visible-row count
       :visible? <bool>}   ; should the N-hidden message render?

  The frame is a view scope, NOT a filter — it is excluded from this
  model entirely (never counted as hidden)."
  [raw-visible-count filtered-visible-count]
  (let [hidden (hidden-count raw-visible-count filtered-visible-count)]
    {:hidden   hidden
     :visible? (indicator-visible? hidden)}))
