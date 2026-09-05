(ns re-frame2-pair-mcp.tools.trace-window
  "Tool: trace-window — epochs in the last N ms.

  Cursor pagination: the response is bounded at `:limit`
  items (default 50). When more remain, `:next-cursor` is non-nil. The
  cursor encodes a sticky `:until-ms` so subsequent pages see the same
  window the first call did — fresh epochs landing during pagination
  don't sneak in mid-iteration.

  Post-eval shrink pipeline lives in
  `re-frame2-pair-mcp.tools.wire-pipeline`. This tool body
  builds the eval form, awaits the runtime response, and routes the
  epoch vector through `run-wire-pipeline` with `:kind :epoch-vector`.

  ## Empty-result advisory

  When the response would carry `:count 0` but the frame's
  per-frame epoch-history is non-empty, an `:advisory` rides on
  the envelope distinguishing \"nothing happened\" from \"events exist
  but fell outside the time window\". Without it the operator can't
  tell these apart and may misread \"empty window\" as
  \"the MCP isn't capturing my events\". The advisory names the
  history count and points to `snapshot` as the historical-inspection
  surface.

  ## Operating frame

  The read is frame-targeted, so it resolves the operating frame —
  the cursor's sticky `:frame` or the caller's `frame` as the tier-1
  override, then session pin, then sole app frame — BEFORE it touches
  the ring, and REFUSES at nil with `:ambiguous-frame` (see
  `re-frame2-pair-mcp.tools.frame-resolve`). It does not read frame nil
  and report the empty ring that comes back as `:count 0`, which says
  \"nothing happened\" in the same voice as a genuinely quiet window.
  The empty-result advisory above could not catch that: it read the
  SAME implicit-frame history, so both sides came back empty together
  (rf2-yo4s)."
  (:require [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.frame-resolve :as fr]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.cursor :as cursor]
            [re-frame2-pair-mcp.tools.epoch-egress :as egress]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]))

(defn trace-window-tool [conn raw-args]
  (let [ms        (or (wire/arg raw-args :ms) 1000)
        build-id  (wire/arg-build conn raw-args)
        ;; Coerce `:frame` via the colon-tolerant
        ;; `->frame-keyword` (the shared `fresh-keyword` path), not a
        ;; raw `(keyword ...)`. A documented `:rf/default`
        ;; / `:foo` frame-id passed with its leading colon must resolve
        ;; rather than minting the malformed `::rf/default`, which matches
        ;; no frame. Same coercion `dispatch` uses.
        frame     (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        ;; The `--allow-sensitive-reads` boot gate
        ;; forces `:include-sensitive false` when OFF (the default), via
        ;; the single intention-naming predicate `raw-state-allowed?`
        ;; (positive sense — true when operator opted in at launch).
        ;; The pull-mode epoch ring egresses full epoch records carrying
        ;; `:db-before` / `:db-after` (and `:trigger-event` /
        ;; `:trace-events`) app-db snapshots, any of which can hold a
        ;; declared-sensitive / declared-large slot. Each egressed record
        ;; routes through
        ;; `re-frame.core/projected-record` — the framework's SINGLE
        ;; normative off-box-egress emission site (Security.md §Epoch
        ;; privacy posture; core.cljc names the hand-walk an anti-pattern
        ;; "one missed `mapv projected-record` away from a leak").
        ;; `incl?` (gate-ON + explicit `:include-sensitive
        ;; true`) does NOT bypass projection. It is threaded as the
        ;; `{:include-sensitive? true}` egress opt INTO `projected-record`,
        ;; lifting ONLY the app-db sensitive axis; fx-args / runtime-db /
        ;; large slots / `:redact-fn` stay fail-closed. Every egressed page
        ;; is ALWAYS projected.
        incl?     (if (raw-state/raw-state-allowed?)
                    (args/parse-bool-arg raw-args :include-sensitive)
                    false)
        mode      (args/parse-epochs-mode (wire/arg raw-args :epochs-mode))
        dedup?    (args/parse-bool-arg raw-args :dedup)
        limit     (cursor/parse-limit-arg (wire/arg raw-args :limit))
        cursor-in (cursor/decode-cursor (wire/arg raw-args :cursor))]
    (if (= cursor-in ::cursor/malformed)
      (js/Promise.resolve
        (cursor/cursor-stale-result "trace-window" {:requested-id nil}))
      (let [after-id  (:after-id cursor-in)
            ;; Sticky window upper-bound: encoded on the first call so
            ;; subsequent pages see the SAME window the first call did.
            until-ms  (or (:until-ms cursor-in) (js/Date.now))
            ;; Sticky ms — agent's first-call value drives all pages.
            window-ms (or (:ms cursor-in) ms)
            cutoff-ms (- until-ms window-ms)
            sticky-frame (or (:frame cursor-in) frame)
            ;; Server-side slice: pull the history, drop up-to-cursor,
            ;; filter to the window, take `limit`. The runtime ships
            ;; only what the page needs — not the whole history.
            ;;
            ;; Against the RESOLVED frame id, never an implicit read:
            ;; `(epoch-history)` defaults to `(current-frame)`, which is
            ;; nil under two-plus app frames with no pin, and
            ;; `rf/epoch-history` answers `[]` for an unknown frame
            ;; without erroring — an ambiguity delivered as an empty
            ;; ring (rf2-yo4s). `with-resolved-frame` below refuses
            ;; before this call is ever reached.
            history-call (fr/frame-sym-call 'epoch-history)
            ;; The egress slice (`page`) is the ONLY vector
            ;; that crosses the wire, so projection happens HERE, after
            ;; the cursor `:limit` cap, before the records ship. Each
            ;; record ALWAYS routes through `re-frame.core/projected-record`
            ;; (which reads `:frame` off the record itself and elides all
            ;; four payload slots). `incl?` threads
            ;; `{:include-sensitive? true}` INTO the projection (app-db
            ;; sensitive axis only); it does NOT disable projection.
            page-src       (str "(vec (take " limit " filtered))")
            slice-src (ef/emit
                   (ef/rt-let
                     ['hist      (ef/rt-raw (str "(vec " (ef/emit history-call) ")"))
                      'after-id  after-id
                      'aged-out? (ef/rt-raw
                                   "(and after-id (not-any? #(= after-id (:epoch-id %)) hist))")
                      'sliced    (ef/rt-raw
                                   (str "(if aged-out? []"
                                        "  (if after-id"
                                        "    (vec (rest (drop-while #(not= after-id (:epoch-id %)) hist)))"
                                        "    hist))"))
                      'filtered  (ef/rt-raw
                                   (str "(filterv #(and (>= (or (:committed-at %) 0) " cutoff-ms ")"
                                        "                (<= (or (:committed-at %) 0) " until-ms "))"
                                        " sliced)"))
                      ;; `:page` is the egress slice, projected
                      ;; for off-box egress via `projected-record` (each
                      ;; record's `:db-before` / `:db-after` /
                      ;; `:trigger-event` / `:trace-events` slots elide
                      ;; server-side). `:epoch-id` is a bookkeeping slot
                      ;; the projection preserves, so `next-id`'s
                      ;; `(:epoch-id (last page))` still resolves.
                      'page      (ef/rt-raw (egress/project-page-src page-src incl?))
                      'next-id   (ef/rt-raw
                                   "(when (< (count page) (count filtered)) (:epoch-id (last page)))")]
                     (ef/rt-raw
                       (str "{:epochs page"
                            " :id-aged-out? aged-out?"
                            " :requested-id after-id"
                            " :head-id (some-> hist peek :epoch-id)"
                            " :next-id next-id"
                            " :history-count (count hist)"
                            " :remaining (max 0 (- (count filtered) (count page)))}"))))
            ;; Resolve once, refuse at nil, THEN slice. The sticky frame
            ;; is the tier-1 override, so a cursor's frame outranks the
            ;; session pin exactly as page 1 did.
            form (fr/with-resolved-frame :trace-window sticky-frame slice-src)]
        (probe/eval-after-runtime!
          conn build-id form :trace-failed
          (fn [v]
            (if (fr/refusal? v)
              ;; An unanswerable read is an isError envelope carrying the
              ;; candidate frames and the recovery — never `:count 0`,
              ;; and never a `:next-cursor`, which would hand the agent a
              ;; continuation for a page that was never read.
              (wire/err-text v)
              (let [v          (if (map? v) v {})
                    aged-out?  (:id-aged-out? v)
                    raw-epochs (vec (:epochs v))]
                (if aged-out?
                  (cursor/cursor-stale-result "trace-window"
                                              {:requested-id (:requested-id v)
                                               :head-id      (:head-id v)})
                  (let [{:keys [value indicators]}
                        (wp/run-wire-pipeline raw-epochs
                                              {:kind   :epoch-vector
                                               :incl?  incl?
                                               :mode   mode
                                               :dedup? dedup?})
                        {:keys [dropped elided count]} indicators
                        next-id     (:next-id v)
                        next-cursor (cursor/encode-cursor
                                      (when next-id
                                        {:v        1
                                         :after-id next-id
                                         :ms       window-ms
                                         :until-ms until-ms
                                         :frame    sticky-frame}))
                        has-more?     (some? next-cursor)
                        remaining     (or (:remaining v) 0)
                        history-count (or (:history-count v) 0)
                        ;; Advisory fires only when the window
                        ;; surfaced ZERO epochs but the per-frame
                        ;; history holds events that just fell
                        ;; outside the time slice. Two ratchets:
                        ;;   - count = 0 (the slot operators read
                        ;;     to decide \"nothing happened\")
                        ;;   - history-count > 0 (the underlying
                        ;;     ring isn't empty — events exist).
                        ;;
                        ;; Both ratchets read the SAME frame's history,
                        ;; which is why this could never catch the
                        ;; ambiguous-frame case: a nil frame emptied
                        ;; both sides together, so the advisory stayed
                        ;; silent on the one reading that most needed
                        ;; explaining (rf2-yo4s). The refusal above is
                        ;; what covers it; this advisory keeps its own
                        ;; narrower job.
                        advisory      (when (and (zero? count)
                                                 (pos? history-count))
                                        {:reason            :window-excludes-history
                                         :frame             sticky-frame
                                         :epochs-in-history history-count
                                         :window-ms         window-ms
                                         :hint              (str history-count
                                                                 " epochs exist in the per-frame "
                                                                 "history but none fell inside the "
                                                                 "last " window-ms "ms window. "
                                                                 "Widen :ms (e.g. 60000), or use "
                                                                 "`snapshot :include [:epochs]` to "
                                                                 "inspect the full history.")})
                        envelope      (cond-> {:ok?                 true
                                               :window-ms           window-ms
                                               :until-ms            until-ms
                                               :count               count
                                               :limit               limit
                                               :epochs-mode         mode
                                               :dedup               dedup?
                                               :epochs              value
                                               :has-more?           has-more?
                                               :estimated-remaining remaining
                                               :next-cursor         next-cursor}
                                        advisory (assoc :advisory advisory))]
                    (wire/ok-text (wire/with-indicators
                                    envelope
                                    {:dropped dropped :elided elided}))))))))))))
