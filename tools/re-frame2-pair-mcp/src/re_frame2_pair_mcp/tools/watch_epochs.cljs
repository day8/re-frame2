(ns re-frame2-pair-mcp.tools.watch-epochs
  "Tool: watch-epochs — pull-mode polling with predicate filter.

  MCP tools aren't streaming — we return one bundle of matches per
  call. Callers
  that want a tight loop call us repeatedly with the same `since-id`.

  Cursor pagination: a single poll's matches vector is
  bounded by `:limit` (default 50), then cut to the longest prefix that
  fits `max-tokens` (`cap/fit-page`), which stamps `:truncated-by
  :max-tokens`. When more matches remain in the
  current ring, `:next-cursor` rides the response — the agent calls
  back with the cursor to consume the remainder. The cursor is the
  opaque sibling of the `:since-id` arg; both can be used, but
  `:cursor` takes precedence (it carries sticky pred/frame state).

  Post-eval shrink pipeline lives in
  `re-frame2-pair-mcp.tools.wire-pipeline`. This tool body
  builds the eval form, awaits the runtime response, and routes the
  matches vector through `run-wire-pipeline` with `:kind :epoch-vector`.

  ## Empty-result advisory

  When the response would carry `:count 0` but the frame's per-frame
  epoch-history is non-empty, an `:advisory` rides on the envelope
  distinguishing two cases:

    - `:no-events-since-id` — caller passed `:since-id` and no new
      epochs landed after it. Calmly says \"nothing new\".
    - `:pred-excludes-history` — the predicate filtered every epoch
      out. Says the history exists; the pred is the gate.

  ## Operating frame

  The poll is frame-targeted, so it resolves the operating frame — the
  cursor's sticky `:frame` or the caller's `frame` as the tier-1
  override, then session pin, then sole app frame — BEFORE it touches
  the ring, and REFUSES at nil with `:ambiguous-frame` (see
  `re-frame2-pair-mcp.tools.frame-resolve`). It does not read frame nil
  and relay the empty ring that comes back, which would tell the agent
  TWO falsehoods at once: `:count 0` (\"nothing matched\") and, because
  a cursor id cannot be found in an empty history, `:id-aged-out? true`
  (\"your cursor fell out of the ring\") — a live cursor declared dead.

  The resolved id then rides BACK out of the eval and into
  `:next-cursor`, so a cursor owns the frame it is iterating from page
  1 — not from page 2, which is all a cursor built out of the caller's
  ARGUMENTS can manage. Page 1 normally names no frame, so such a
  cursor would store nil and page 2 would re-resolve against whatever
  the session says by then. That would reach the SAME dead-cursor
  falsehood by a second route, and one the frame refusal cannot cover,
  because the new frame resolves perfectly well — it is simply not the
  ring the agent was reading."
  (:require [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.cap :as cap]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.frame-resolve :as fr]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.cursor :as cursor]
            [re-frame2-pair-mcp.tools.epoch-egress :as egress]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame.mcp-base.cap :as rf.mcp-base.cap]))

(def ^:private keyword-valued-pred-keys
  "The `pred` keys `epoch-matches?` compares against keywords with `=`."
  #{:event-id :effects :sub-ran :origin :frame})

(defn- parse-pred-arg
  "The `pred` arg as the map `epoch-matches?` reads. An EDN string reads as
  EDN, as watch-until's does. A JSON object carries no keywords, so a
  string under a keyword-valued key mints one (colon-tolerant, id grammar
  only — `\":cart/checkout\"` is `:cart/checkout`), and `:touches-path`
  parses as a path; every other value rides as sent."
  [p]
  (cond
    (map? p)    p
    (string? p) (let [v (args/read-edn-text (args/edn-text p))] (when (map? v) v))
    (object? p) (let [m (reduce-kv
                          (fn [m k v]
                            (assoc m k (if (and (contains? keyword-valued-pred-keys k) (string? v))
                                         (or (args/->id-keyword v) v)
                                         v)))
                          {} (js->clj p :keywordize-keys true))]
                  (cond-> m
                    (contains? m :touches-path)
                    (assoc :touches-path (args/parse-path-arg (wire/arg p :touches-path)))))
    :else       nil))

(def ^:private timing-comparison-re
  "The comparison strings the preload's `re-frame2-pair.pure/parse-timing-pred`
  parses; it also takes a bare number."
  #"\s*(>=|<=|>|<|=)?\s*(-?\d+(?:\.\d+)?)\s*")

(defn- timing-refusal
  "The `:rf.mcp/invalid-arg` refusal for a `pred` whose `:timing-ms` the
  runtime cannot parse, else nil. Such a predicate matches no epoch, so
  without the refusal a typo (`\">100ms\"`) reads as a quiet ring."
  [pred]
  (let [t (:timing-ms pred)]
    (when-not (or (nil? t)
                  (number? t)
                  (and (string? t) (re-matches timing-comparison-re t)))
      (rf.mcp-base.cap/invalid-arg-marker
        :pred t
        (str "pred :timing-ms is a number of ms (meaning >= N) or a comparison string "
             "with no unit: \">100\", \">=100\", \"<200\", \"<=50\", \"=42\".")))))

(defn watch-epochs-tool [conn raw-args]
  (let [build-id  (wire/arg-build conn raw-args)
        ;; Colon-tolerant `:frame` coercion (the shared
        ;; `fresh-keyword-checked` path) so a `:rf/default`-with-colon frame-id
        ;; resolves instead of minting the malformed `::rf/default`.
        ;; Same coercion `dispatch` uses.
        frame     (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        ;; `:since-id` is typed string on the wire, and
        ;; the reference runtime's epoch ids are INTEGERS that
        ;; `epochs-since` finds with `=`. Passed raw, a schema-conforming
        ;; `"47"` would never equal `47`, so every resume-by-id would read
        ;; as a false `:rf.mcp/cursor-stale`. Parse it exactly as `restore-epoch`
        ;; parses its `epoch-id` (EDN, `args/read-edn-arg`; the value rides
        ;; quoted below): `"47"` reads as `47`, a keyword or string id
        ;; still round-trips. A non-string (a JSON number) is already the
        ;; id; a blank string is the same as absent.
        since-raw (wire/arg raw-args :since-id)
        [since-tag since-id] (if (string? since-raw)
                               (let [r (args/read-edn-arg since-raw ::blank :invalid-since-id)]
                                 (if (= [:err ::blank] r) [:ok nil] r))
                               [:ok since-raw])
        ;; The `--allow-sensitive-reads` boot gate
        ;; forces `:include-sensitive false` when OFF (the default), via
        ;; the single intention-naming predicate `raw-state-allowed?`
        ;; (positive sense — true when operator opted in at launch).
        ;; Pull-mode `watch-epochs` egresses full epoch records carrying
        ;; `:db-before` / `:db-after` (and `:trigger-event` /
        ;; `:trace-events`) app-db snapshots. Each egressed record routes
        ;; through `re-frame.core/project-egress` — the framework's
        ;; single normative record-level egress door (Security.md
        ;; §Epoch privacy posture) — on the strength of the record's
        ;; stamped `:kind :rf/epoch-record`, which
        ;; `epoch_egress/project-page-src` checks first (GUARD G3).
        ;; `incl?` (gate-ON + explicit `:include-sensitive
        ;; true`) does NOT bypass projection. It is threaded as the
        ;; `{:rf.egress/include-sensitive? true}` egress opt INTO `project-egress`,
        ;; lifting ONLY the app-db sensitive axis; the orthogonal fx-args /
        ;; runtime-db / large axes stay fail-closed (Security.md §Off-box
        ;; egress). Every egressed page is ALWAYS projected.
        incl?     (if (raw-state/raw-state-allowed?)
                    (args/parse-bool-arg raw-args :include-sensitive)
                    false)
        mode      (args/parse-epochs-mode (wire/arg raw-args :epochs-mode))
        dedup?    (args/parse-bool-arg raw-args :dedup)
        limit     (cursor/parse-limit-arg (wire/arg raw-args :limit))
        pred-arg  (parse-pred-arg (wire/arg raw-args :pred))
        cursor-in (cursor/decode-cursor (wire/arg raw-args :cursor))
        ;; `pred`'s keys are minted into keywords by
        ;; `:keywordize-keys` and PRINTED into the poll form; a key without
        ;; keyword grammar would print as code, so it is refused, as is a
        ;; `:timing-ms` the runtime cannot parse.
        pred-refusal (or (args/invalid-key-refusal :pred pred-arg)
                         (timing-refusal pred-arg))]
    (cond
      (some? pred-refusal)
      (js/Promise.resolve (wire/err-text pred-refusal))

      (= :err since-tag)
      (js/Promise.resolve
        (wire/err-text {:ok?      false
                        :reason   :invalid-since-id
                        :since-id since-raw
                        :hint     (str "since-id is parsed as EDN, like restore-epoch's epoch-id — "
                                       "pass back the :head-id a previous poll returned, e.g. "
                                       "\"47\" for the integer id 47.")}))

      (= cursor-in ::cursor/malformed)
      (js/Promise.resolve
        (cursor/cursor-stale-result "watch-epochs" {:requested-id nil}))

      :else
      (let [;; Cursor's :after-id overrides bare :since-id when both
            ;; are supplied. Both shapes share semantics; cursor wins
            ;; so the agent's continuation flow stays consistent.
            effective-after (or (:after-id cursor-in) since-id)
            sticky-frame    (or (:frame cursor-in) frame)
            ;; Sticky `:pred`, mirroring the
            ;; `:frame`/`:after-id` pattern above and trace-window's
            ;; sticky `:ms`/`:until-ms`. The cursor encodes the
            ;; first-call predicate; a continuation that passes back
            ;; ONLY `:cursor` (the documented opaque-cursor flow)
            ;; restores it here. Without it, page 2+ would fall back to
            ;; `(or pred-map {})` = `{}` = MATCH-ALL — every epoch
            ;; after the watermark would return unfiltered, with an
            ;; envelope identical to a correctly-filtered page so the
            ;; agent couldn't detect the drop.
            sticky-pred     (or (:pred cursor-in) pred-arg)
            ;; Both ring reads go against the RESOLVED frame id, never
            ;; an implicit one: `(epochs-since id)` and
            ;; `(epoch-history)` each default to `(current-frame)`,
            ;; which is nil under two-plus app frames with no pin, and
            ;; `rf.epoch/epoch-history` answers `[]` for an unknown frame
            ;; without erroring. `epochs-since` then cannot find the
            ;; caller's id in that empty history and reports
            ;; `:id-aged-out? true` — so the ambiguity would arrive as a
            ;; quiet poll AND a dead cursor.
            ;; The id is caller data (the `:since-id` arg, or a
            ;; caller-supplied cursor's EDN `:after-id`), so it rides
            ;; QUOTED — a list, symbol, or emitter-shaped vector is data,
            ;; never source (the provenance rule in `eval-form`). An
            ;; absent id stays the literal `nil`.
            epochs-since-call (fr/frame-sym-call 'epochs-since
                                                 (when (some? effective-after)
                                                   (ef/rt-quote effective-after)))
            ;; The predicate is caller data — a JSON object on page 1, the
            ;; cursor's EDN on page 2+ — so it rides QUOTED: a list or
            ;; symbol a crafted cursor carries is compared as data, never
            ;; evaluated (the provenance rule in `eval-form`).
            matches-form (str "(filterv #"
                              (ef/emit (ef/rt-call 'epoch-matches?
                                                   (ef/rt-quote (or sticky-pred {}))
                                                   (ef/rt-raw "%")))
                              " (:epochs r))")
            history-call (fr/frame-sym-call 'epoch-history)
            ;; The `:pred` filter runs on the RAW records
            ;; server-side (never egressed); the capped `:page` is the
            ;; egress slice, ALWAYS projected via `project-egress` for
            ;; off-box egress. `incl?` threads
            ;; `{:rf.egress/include-sensitive? true}` INTO the projection (app-db
            ;; sensitive axis only), it does NOT disable projection.
            page-src       (str "(vec (take " limit " matches))")
            poll-src (ef/emit
                   (ef/rt-let
                     ['r             epochs-since-call
                      'matches       (ef/rt-raw matches-form)
                      'page          (ef/rt-raw (egress/project-page-src page-src incl?))
                      'next-id       (ef/rt-raw
                                       "(when (< (count page) (count matches)) (:epoch-id (last page)))")
                      ;; history-count surfaces the
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
                            ;; The id this poll was actually read
                            ;; against, so `:next-cursor` can own it —
                            ;; see `read-frame` below.
                            " " fr/resolved-frame-entry
                            " :remaining (max 0 (- (count matches) (count page)))}"))))
            ;; Resolve once, refuse at nil, THEN poll. The sticky frame
            ;; is the tier-1 override, so a cursor's frame outranks the
            ;; session pin exactly as page 1 did.
            form (fr/with-resolved-frame :watch-epochs sticky-frame poll-src)]
        (probe/eval-after-runtime!
          conn build-id form :watch-failed
          (fn [v]
            (if (fr/refusal? v)
              ;; An unanswerable poll is an isError envelope carrying the
              ;; candidate frames and the recovery. Ahead of the
              ;; cursor-stale branch on purpose: an ambiguous frame reads
              ;; an empty ring, in which the caller's live cursor id is
              ;; simply not found, so the poll would otherwise report the
              ;; cursor DEAD and send the agent back to page 1 of the
              ;; wrong frame.
              (wire/err-text v)
              (let [v          (if (map? v) v {})
                    aged-out?  (:id-aged-out? v)
                    ;; The frame this poll was READ against, not the one
                    ;; it was asked for — the two differ on page 1,
                    ;; where the caller names no frame and the id comes
                    ;; from the session pin or the sole app frame.
                    ;; Stamping the asked-for nil into `:next-cursor`
                    ;; would let a later pin change move the ring under
                    ;; the agent, and the poll would then declare the
                    ;; live cursor aged out — the very falsehood this
                    ;; tool's frame refusal exists to stop.
                    read-frame (or (fr/resolved-frame v) sticky-frame)]
                (if (and aged-out? (some? effective-after))
                  (cursor/cursor-stale-result "watch-epochs"
                                              {:requested-id (or (:requested-id v) effective-after)
                                               :head-id      (:head-id v)})
                  (let [matches       (vec (:matches v))
                        n             (count matches)
                        cap           (cap/page-cap raw-args)
                        remaining     (or (:remaining v) 0)
                        history-count (or (:history-count v) 0)
                        since-count   (or (:since-count v) 0)
                        ;; The result for a page of the first `k` matches.
                        ;; `cap/fit-page` asks for the whole page first and
                        ;; cuts it only when that overflows `max-tokens`.
                        page-result
                        (fn page-result [k]
                          (let [cut?        (< k n)
                                {:keys [value indicators]}
                                (wp/run-wire-pipeline (subvec matches 0 k)
                                                      {:kind   :epoch-vector
                                                       :mode   mode
                                                       :dedup? dedup?})
                                {elided :elided shown :count} indicators
                                next-id     (cursor/page-continuation-id
                                              matches k effective-after (:next-id v))
                                next-cursor (cursor/encode-cursor
                                              (when (some? next-id)
                                                {:v        1
                                                 :after-id next-id
                                                 :ms       nil
                                                 :until-ms nil
                                                 ;; The cursor OWNS its frame
                                                 ;; from page 1 onward, so a
                                                 ;; pin changed mid-pagination
                                                 ;; cannot move the ring under
                                                 ;; the agent.
                                                 :frame    read-frame
                                                 ;; Carry the sticky
                                                 ;; predicate so page 2+ keeps
                                                 ;; filtering. nil when no pred was
                                                 ;; supplied (round-trips losslessly
                                                 ;; through the base64-of-EDN codec).
                                                 :pred     sticky-pred}))
                                ;; Three empty-result explainers, picked by
                                ;; the runtime data:
                                ;;
                                ;;   - the cap cut the page to nothing
                                ;;     → the first match alone overflows;
                                ;;     name the size, not the predicate.
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
                                  (and cut? (zero? k))
                                  (cursor/cut-advisory
                                    read-frame
                                    (cap/sum-payload-tokens (page-result 1))
                                    cap
                                    (some? next-cursor))

                                  (and (zero? shown)
                                       (zero? since-count)
                                       (pos? history-count))
                                  {:reason            :no-events-since-id
                                   ;; The frame the count came from — the
                                   ;; resolved one, never the asked-for nil.
                                   :frame             read-frame
                                   :epochs-in-history history-count
                                   :requested-id      effective-after
                                   :hint              (str "Per-frame history holds "
                                                           history-count
                                                           " epochs but none have landed since the "
                                                           "supplied :since-id. Dispatch an event "
                                                           "to advance the head, or omit :since-id "
                                                           "to see the full ring.")}

                                  (and (zero? shown)
                                       (pos? since-count))
                                  {:reason            :pred-excludes-history
                                   ;; The frame the count came from — the
                                   ;; resolved one, never the asked-for nil.
                                   :frame             read-frame
                                   :epochs-in-history history-count
                                   :epochs-since-id   since-count
                                   :hint              (str since-count
                                                           " epochs landed since the requested id but "
                                                           "the :pred filter excluded all of them. "
                                                           "Drop / widen :pred, or use trace-window for "
                                                           "an unfiltered view.")})
                                base        (cond-> {:ok?          true
                                                     :head-id      (:head-id v)
                                                     :id-aged-out? (boolean aged-out?)}
                                              (:requested-id v) (assoc :requested-id (:requested-id v))
                                              advisory          (assoc :advisory advisory)
                                              cut?              (assoc :truncated-by :max-tokens))]
                            (wire/ok-text (wire/with-indicators
                                            (assoc base
                                                   :matches             value
                                                   :limit               limit
                                                   :count               shown
                                                   :epochs-mode         mode
                                                   :dedup               dedup?
                                                   :has-more?           (or cut? (some? next-cursor))
                                                   :estimated-remaining (+ remaining (- n k))
                                                   :next-cursor         next-cursor)
                                            {:elided elided}))))]
                    (cap/fit-page n cap page-result)))))))))))
