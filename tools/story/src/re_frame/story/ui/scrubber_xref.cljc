(ns re-frame.story.ui.scrubber-xref
  "Pure data helpers for the trace × scrubber cross-reference (rf2-sxwvf).

  The trace panel and the scrubber both live in CLJS-land — they touch
  Reagent ratoms and the DOM. But the cross-reference *logic* — given
  a cascade-record vector + a `cap` event-id, what's the visible subset?
  given an epoch record, what's the cascade-id that produced it? — is
  pure data → data. Splitting that logic out into `.cljc` so it runs
  under the JVM unit-test target (`clojure -M:test`) is required by
  the standing rule `feedback_jvm_interop_must_work.md`.

  The corresponding ratom-touching / DOM-touching code stays in
  `re-frame.story.ui.scrubber` (the `selections` defonce + the `panel`
  fn) and `re-frame.story.ui.trace` (the `panel` fn that derefs the
  selection ratom + renders the highlighted row).

  See `tools/story/spec/012-Trace-Scrubber-Cross-Ref.md` for the
  normative contract.")

;; ---- cascade-id resolution (epoch → cascade) -----------------------------

(defn cascade-id-from-trace-events
  "Walk an epoch record's `:trace-events` for the first `:dispatch-id`
  tag and return it. Pure data: epoch record → cascade-id (or nil).

  Per Spec 009 §Dispatch correlation (rf2-g6ih4) the framework stamps
  `:tags :dispatch-id` on every in-drain event. The projection picks
  the first one available so the cascade-id resolves even when the
  cascade-root `:event/dispatched` event was evicted from the buffer
  before the epoch settled."
  [epoch-record]
  (some (fn [ev]
          (or (get-in ev [:tags :dispatch-id])
              (get-in ev [:tags :parent-dispatch-id])))
        (:trace-events epoch-record)))

(defn cascade-id-for-epoch
  "Given a `history` vector and an `epoch-id`, return the cascade
  `:dispatch-id` that produced that epoch, or nil. Pure data: history
  + id → cascade-id-or-nil.

  Returns nil when:
    - `epoch-id` is nil (no scrub in flight);
    - the epoch is no longer in `history` (evicted by the ring buffer's
      depth cap);
    - the epoch carries no dispatch-id-bearing trace events (synthetic
      epochs from `reset-frame-db!` have empty `:trace-events`)."
  [history epoch-id]
  (when epoch-id
    (some (fn [r] (when (= epoch-id (:epoch-id r))
                    (cascade-id-from-trace-events r)))
          history)))

(defn max-trace-event-id-for-epoch
  "Given a `history` vector and an `epoch-id`, return the maximum `:id`
  among the trace events captured for that epoch, or nil. Pure data:
  history + id → cap-or-nil.

  Per Spec 009 §`:id` is process-monotonic — every emit increments a
  shared counter, so an event with `:id 42` was definitely emitted
  after every event with `:id ≤ 41`. The trace panel uses the returned
  cap as its filter pivot: a cascade whose max event-id ≤ cap stays
  visible; a cascade emitted after the epoch settled (max > cap) drops
  out.

  nil-result cases:
    - `epoch-id` is nil;
    - the epoch is gone from `history`;
    - the epoch's `:trace-events` carried no `:id`-bearing event (a
      synthetic `reset-frame-db!` epoch records `:trace-events []`)."
  [history epoch-id]
  (when epoch-id
    (some (fn [r]
            (when (= epoch-id (:epoch-id r))
              (let [ids (keep :id (:trace-events r))]
                (when (seq ids) (apply max ids)))))
          history)))

;; ---- cascade filter + highlight (cascades → visible cascades) ------------

(defn- max-id-in-cascade
  "Maximum `:id` across every event in a cascade record. Cascades whose
  events all carry an `:id` ≤ `cap` belong to the 'at or before the
  selected epoch' visible set."
  [{:keys [handler fx effects subs renders other]}]
  ;; The cascade's `:event` slot is the event vector (not a trace-event
  ;; map) per `re-frame.trace.projection/empty-cascade`; skip it.
  (let [all (concat (when handler [handler])
                    (when fx [fx])
                    effects subs renders other)
        ids (keep :id all)]
    (when (seq ids) (apply max ids))))

(defn filter-cascades-up-to
  "Given a vector of cascade records (`group-cascades` output) and a
  `cap` event-id (the maximum trace-event `:id` recorded for the
  selected epoch), return the subset whose own max-id is `≤ cap`.

  Per Spec 009 `:id` is process-monotonic; comparing cascade max-id
  against `cap` is total. A cap of nil (no scrub in flight, or a
  synthetic epoch with no event ids) is the identity — every cascade
  passes. Cascades whose events carry no `:id` (degenerate, shouldn't
  happen in practice) also pass.

  Pure data → data. JVM-testable."
  [cascades cap]
  (if (nil? cap)
    (vec cascades)
    (vec
      (filter (fn [c]
                (let [m (max-id-in-cascade c)]
                  (or (nil? m) (<= m cap))))
              cascades))))

(defn cascade-matches-selected-epoch?
  "True when this cascade's `:dispatch-id` is the cascade-id stamped on
  the currently-selected epoch (i.e. this cascade's post-effects
  produced the selected epoch). Pure data → boolean. nil
  `selected-cascade-id` is always false (no scrub in flight ⇒ no row
  highlights)."
  [cascade selected-cascade-id]
  (and (some? selected-cascade-id)
       (= selected-cascade-id (:dispatch-id cascade))))
