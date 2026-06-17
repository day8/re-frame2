(ns re-frame2-pair-mcp.tools.watch-epochs
  "Tool: watch-epochs — pull-mode polling with predicate filter.

  The bash version streams via repeated `emit`s on stdout. MCP tools
  aren't streaming — we return one bundle of matches per call. Callers
  that want a tight loop call us repeatedly with the same `since-id`.

  Cursor pagination (rf2-kbqq3): a single poll's matches vector is
  bounded by `:limit` (default 50). When more matches remain in the
  current ring, `:next-cursor` rides the response — the agent calls
  back with the cursor to consume the remainder. The cursor is the
  opaque sibling of the `:since-id` arg; both can be used, but
  `:cursor` takes precedence (it carries sticky pred/frame state).

  Post-eval shrink pipeline lives in
  `re-frame2-pair-mcp.tools.wire-pipeline` (rf2-ae8ie). This tool body
  builds the eval form, awaits the runtime response, and routes the
  matches vector through `run-wire-pipeline` with `:kind :epoch-vector`.

  ## Empty-result advisory (rf2-fb4hn)

  When the response would carry `:count 0` but the frame's per-frame
  epoch-history is non-empty, an `:advisory` rides on the envelope
  distinguishing two cases:

    - `:no-events-since-id` — caller passed `:since-id` and no new
      epochs landed after it. Calmly says \"nothing new\".
    - `:pred-excludes-history` — the predicate filtered every epoch
      out. Says the history exists; the pred is the gate."
  (:require [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.cursor :as cursor]
            [re-frame2-pair-mcp.tools.dedup :as dedup]
            [re-frame2-pair-mcp.tools.epoch-egress :as egress]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]))

(defn watch-epochs-tool [conn raw-args]
  (let [build-id  (wire/arg-build conn raw-args)
        ;; rf2-lbm21 — colon-tolerant `:frame` coercion (the shared
        ;; `fresh-keyword` path) so a `:rf/default`-with-colon frame-id
        ;; resolves instead of minting the malformed `::rf/default`.
        ;; Same fix rf2-ldfnx applied to dispatch.
        frame     (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        since-id  (wire/arg raw-args :since-id)
        ;; rf2-c2dtu / rf2-p1qli — the `--allow-sensitive-reads` boot gate
        ;; forces `:include-sensitive false` when OFF (the default), via
        ;; the single intention-naming predicate `raw-state-allowed?`
        ;; (positive sense — true when operator opted in at launch).
        ;; rf2-6wvh5 — Pull-mode
        ;; `watch-epochs` egresses full epoch records carrying
        ;; `:db-before` / `:db-after` (and `:trigger-event` /
        ;; `:trace-events`) app-db snapshots; before rf2-6wvh5 they rode
        ;; the wire verbatim. The fix routes each egressed record through
        ;; `re-frame.core/projected-record` — the framework's single
        ;; normative off-box-egress emission site (Security.md §Epoch
        ;; privacy posture).
        ;; rf2-m9duxl — `incl?` (gate-ON + explicit `:include-sensitive
        ;; true`) NO LONGER bypasses projection. It is threaded as the
        ;; `{:include-sensitive? true}` egress opt INTO `projected-record`,
        ;; lifting ONLY the app-db sensitive axis; the orthogonal fx-args /
        ;; runtime-db / large axes and the app `:redact-fn` stay
        ;; fail-closed (Security.md §Off-box egress). Every egressed page
        ;; is ALWAYS projected.
        incl?     (if (raw-state/raw-state-allowed?)
                    (args/parse-bool-arg raw-args :include-sensitive)
                    false)
        mode      (dedup/parse-epochs-mode (wire/arg raw-args :epochs-mode))
        dedup?    (args/parse-bool-arg raw-args :dedup)
        limit     (cursor/parse-limit-arg (wire/arg raw-args :limit))
        pred-arg  (when-let [p (wire/arg raw-args :pred)] (js->clj p :keywordize-keys true))
        cursor-in (cursor/decode-cursor (wire/arg raw-args :cursor))]
    (if (= cursor-in ::cursor/malformed)
      (js/Promise.resolve
        (cursor/cursor-stale-result "watch-epochs" {:requested-id nil}))
      (let [;; Cursor's :after-id overrides bare :since-id when both
            ;; are supplied. Both shapes share semantics; cursor wins
            ;; so the agent's continuation flow stays consistent.
            effective-after (or (:after-id cursor-in) since-id)
            sticky-frame    (or (:frame cursor-in) frame)
            ;; rf2-mb17rj — sticky `:pred`, mirroring the
            ;; `:frame`/`:after-id` pattern above and trace-window's
            ;; sticky `:ms`/`:until-ms`. The cursor encodes the
            ;; first-call predicate; a continuation that passes back
            ;; ONLY `:cursor` (the documented opaque-cursor flow)
            ;; restores it here. Without this, page 2+ fell back to
            ;; `(or pred-map {})` = `{}` = MATCH-ALL — every epoch
            ;; after the watermark returned unfiltered, with an
            ;; envelope identical to a correctly-filtered page so the
            ;; agent couldn't detect the drop.
            sticky-pred     (or (:pred cursor-in) pred-arg)
            epochs-since-call (if sticky-frame
                                (ef/rt-call 'epochs-since effective-after sticky-frame)
                                (ef/rt-call 'epochs-since effective-after))
            matches-form (str "(filterv #"
                              (ef/emit (ef/rt-call 'epoch-matches?
                                                   (or sticky-pred {})
                                                   (ef/rt-raw "%")))
                              " (:epochs r))")
            history-call (if sticky-frame
                           (ef/rt-call 'epoch-history sticky-frame)
                           (ef/rt-call 'epoch-history))
            ;; rf2-6wvh5 — the `:pred` filter runs on the RAW records
            ;; server-side (never egressed); the capped `:page` is the
            ;; egress slice, ALWAYS projected via `projected-record` for
            ;; off-box egress. rf2-m9duxl — `incl?` threads
            ;; `{:include-sensitive? true}` INTO the projection (app-db
            ;; sensitive axis only), it does NOT disable projection.
            page-src       (str "(vec (take " limit " matches))")
            form (ef/emit
                   (ef/rt-let
                     ['r             epochs-since-call
                      'matches       (ef/rt-raw matches-form)
                      'page          (ef/rt-raw (egress/project-page-src page-src incl?))
                      'next-id       (ef/rt-raw
                                       "(when (< (count page) (count matches)) (:epoch-id (last page)))")
                      ;; rf2-fb4hn: history-count surfaces the
                      ;; per-frame ring depth so the tool can advise
                      ;; on the \"empty matches but non-empty history\"
                      ;; case the operator typically misreads as
                      ;; pre-attach invisibility.
                      'history-count (ef/rt-raw (str "(count " (ef/emit history-call) ")"))
                      'since-count   (ef/rt-raw "(count (:epochs r))")]
                     (ef/rt-raw
                       (str "{:matches page"
                            " :id-aged-out? (:id-aged-out? r)"
                            " :requested-id (:requested-id r)"
                            " :head-id (:head-id r)"
                            " :next-id next-id"
                            " :history-count history-count"
                            " :since-count since-count"
                            " :remaining (max 0 (- (count matches) (count page)))}"))))]
        (probe/eval-after-runtime!
          conn build-id form :watch-failed
          (fn [v]
            (let [v          (if (map? v) v {})
                  aged-out?  (:id-aged-out? v)]
              (if (and aged-out? (some? effective-after))
                (cursor/cursor-stale-result "watch-epochs"
                                            {:requested-id (or (:requested-id v) effective-after)
                                             :head-id      (:head-id v)})
                (let [matches (vec (:matches v))
                      {:keys [value indicators]}
                      (wp/run-wire-pipeline matches
                                            {:kind   :epoch-vector
                                             :incl?  incl?
                                             :mode   mode
                                             :dedup? dedup?})
                      {:keys [dropped elided count]} indicators
                      next-id       (:next-id v)
                      next-cursor   (cursor/encode-cursor
                                      (when next-id
                                        {:v        1
                                         :after-id next-id
                                         :ms       nil
                                         :until-ms nil
                                         :frame    sticky-frame
                                         ;; rf2-mb17rj — carry the sticky
                                         ;; predicate so page 2+ keeps
                                         ;; filtering. nil when no pred was
                                         ;; supplied (round-trips losslessly
                                         ;; through the base64-of-EDN codec).
                                         :pred     sticky-pred}))
                      remaining     (or (:remaining v) 0)
                      history-count (or (:history-count v) 0)
                      since-count   (or (:since-count v) 0)
                      ;; rf2-fb4hn: two distinct empty-result
                      ;; explainers, picked by the runtime data:
                      ;;
                      ;;   - since-count = 0 + history-count > 0
                      ;;     → caller's :since-id is at (or past)
                      ;;     the head; calmly say \"nothing new
                      ;;     yet\".
                      ;;
                      ;;   - since-count > 0 + count = 0
                      ;;     → events landed since the id but the
                      ;;     :pred filtered them all out — point
                      ;;     at the predicate, not the buffer.
                      advisory
                      (cond
                        (and (zero? count)
                             (zero? since-count)
                             (pos? history-count))
                        {:reason            :no-events-since-id
                         :frame             sticky-frame
                         :epochs-in-history history-count
                         :requested-id      effective-after
                         :hint              (str "Per-frame history holds "
                                                 history-count
                                                 " epochs but none have landed since the "
                                                 "supplied :since-id. Dispatch an event "
                                                 "to advance the head, or omit :since-id "
                                                 "to see the full ring.")}

                        (and (zero? count)
                             (pos? since-count))
                        {:reason            :pred-excludes-history
                         :frame             sticky-frame
                         :epochs-in-history history-count
                         :epochs-since-id   since-count
                         :hint              (str since-count
                                                 " epochs landed since the requested id but "
                                                 "the :pred filter excluded all of them. "
                                                 "Drop / widen :pred, or use trace-window for "
                                                 "an unfiltered view.")})
                      base          (cond-> {:ok?          true
                                             :head-id      (:head-id v)
                                             :id-aged-out? (boolean aged-out?)}
                                      (:requested-id v) (assoc :requested-id (:requested-id v))
                                      advisory          (assoc :advisory advisory))]
                  (wire/ok-text (wire/with-indicators
                                  (assoc base
                                         :matches             value
                                         :limit               limit
                                         :count               count
                                         :epochs-mode         mode
                                         :dedup               dedup?
                                         :has-more?           (some? next-cursor)
                                         :estimated-remaining remaining
                                         :next-cursor         next-cursor)
                                  {:dropped dropped :elided elided})))))))))))
