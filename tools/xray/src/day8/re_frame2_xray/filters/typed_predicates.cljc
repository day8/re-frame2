(ns day8.re-frame2-xray.filters.typed-predicates
  "Typed-predicate filter kinds for Xray pills.

  ## Why typed predicates

  A bare `{:pattern <kw-or-str>}` pill keys off the cascade's
  `event-id` only. Surface-aware filters — 'show
  every cascade that touched THIS machine' / 'show every cascade in
  THIS HTTP exchange' / 'show every cascade triggering THIS fx kind'
  — need to walk the cascade's trace-events, not just its event-id.
  This ns generalises the pill record into a typed predicate:

      {:kind   <:event-id-pattern | :machine | :http-correlation | :fx>
       :params <kind-specific map>}

  Each kind has a matcher in `cascade-matches-by-kind?` that consults
  the cascade's data shape — event-id-pattern delegates to the
  event-id matcher (`matcher.cljc`), the other three walk the
  cascade's trace-event buckets for the expected `:tags` slot.

  ## v1 kinds

  - `:event-id-pattern` — the `{:pattern <kw-or-str>}` shape.
    Delegates to the event-id pattern matcher.
  - `:machine` — match cascades whose trace-events include a
    `:tags :machine-id` equal to `<params :machine-id>`. The right-
    click affordance on the Machines panel rows fires
    `:rf.xray/filter-by-machine` which appends this kind.
  - `:http-correlation` — match cascades whose trace-events include a
    `:tags :correlation-id` equal to `<params :correlation-id>`. The
    right-click affordance on managed-fx HTTP records fires
    `:rf.xray/filter-by-http-correlation`.
  - `:fx` — match cascades that triggered the fx with this `fx-id`.
    Walks the trace-events for any event whose `:tags :rf.fx/id` equals
    `<params :fx-id>`. The right-click affordance on managed-fx
    records' surface badge fires `:rf.xray/filter-by-fx`.

  ## Deferred kinds

  - `:source-coord` — defer to v1.1; niche.
  - `:interceptor` — defer to v1.1; niche.

  ## Bare-pattern pills

  Pills stored under the bare `{:pattern <kw-or-str>}` shape
  hydrate as `:event-id-pattern` via
  `canonicalise-pill`. The matcher folds the missing `:kind` slot into
  the canonical kind on the way through. Typed pills written by the
  right-click affordances persist with the explicit `:kind` so re-load
  round-trips cleanly.

  ## Composition (spec/018 §7)

  IN/OUT pill composition is:

      keep = (no-IN-pills OR matches-IN) AND NOT (matches-OUT)

  — typed pills compose with keyword-pattern pills inside the same
  bucket via `some` (any pill in the bucket can match the cascade).
  Mixing typed + keyword pills in the same bucket is supported and
  exercised by tests.

  ## Causal-parent matching under epoch-per-event

  Under epoch-per-event each dequeued event — including `:fx :dispatch`
  children and machine-internal transitions — is its OWN cascade. A
  `:machine` / `:http-correlation` / `:fx` IN pill matching only the
  cascade carrying its tag would lose the link to the PARENT event that
  spawned the transition (a sibling cascade). The projection
  surfaces the causal-parent link once as `:parent-dispatch-id`; a
  directly-matching cascade pulls in its whole SPAWNING LINEAGE — every
  causal ancestor walked UP that link to the root user event — so a
  typed pill on a child transition cascade ALSO keeps the spawning
  event's cascade. `keep = matching cascades ∪ their ancestors`. The
  walk is in `cascade-self-and-ancestors` (cycle-guarded,
  root-terminated); `filter-cascades` builds the
  `{dispatch-id → cascade}` index once and folds the IN-kept id set
  in `in-kept-id-set`. OUT (hide) pills stay cascade-local so a hide
  never silently drops the ancestor that spawned the hidden child.

  Pure data → bool. JVM-runnable so the test corpus exercises every
  kind without a CLJS runtime."
  (:require [day8.re-frame2-xray.filters.matcher :as event-matcher]))

;; ---- canonical pill shape -----------------------------------------------

(defn canonicalise-pill
  "Coerce a pill record into the canonical typed-predicate shape:

      {:kind <keyword> :params <map>}

  - If `:kind` is already present, return verbatim (with `:params`
    defaulted to `{}`).
  - If `:pattern` is present and `:kind` is absent, infer
    `:event-id-pattern` and lift `:pattern` into `:params`. This is
    the path for pills persisted under the bare `{:pattern …}` shape.
  - Otherwise return the pill verbatim (the matcher will treat it as
    `:never` — guards a malformed pill from matching everything)."
  [pill]
  (cond
    (not (map? pill))
    {:kind :never :params {}}

    (some? (:kind pill))
    (-> pill
        (update :params #(or % {})))

    (contains? pill :pattern)
    ;; Event-id-pattern shape: surface the pattern under params so the
    ;; matcher reads from one slot. Event-id is the only scope.
    {:kind   :event-id-pattern
     :params {:pattern (:pattern pill)}}

    :else
    {:kind :never :params {}}))

;; ---- cascade-trace-events ------------------------------------------------

(defn cascade-trace-events
  "Flatten every trace-event bucket on a cascade into one seq. Per
  `re-frame.trace.projection/group-cascades` a cascade record carries
  raw trace maps under `:handler`, `:fx`, `:effects`, `:subs`,
  `:renders`, and `:other`; the `:event` slot is the bare event
  vector (not a trace map) so it's excluded.

  Lifted from `panels/routing_helpers.cljc` so the matcher stays a
  self-contained pure unit; both helpers walk the same shape."
  [cascade]
  (concat
    (when-let [handler (:handler cascade)] [handler])
    (when-let [fx (:fx cascade)] [fx])
    (:effects cascade)
    (:subs cascade)
    (:renders cascade)
    (:other cascade)))

(defn- tag-of
  "Read `k` off a trace event's `:tags` map, defensive against the
  flat-shape test fixtures that omit `:tags`. Mirrors
  `panels/common_helpers/tag-of` so the matcher stays decoupled from
  the panels' helper tree."
  [ev k]
  (or (get-in ev [:tags k])
      (get ev k)))

;; ---- per-kind cascade matchers ------------------------------------------

(defmulti cascade-matches-by-kind?
  "Dispatch on `:kind` to the per-kind matcher. Returns true iff the
  cascade satisfies the typed predicate.

  Each method receives the cascade and the pill's `:params` map. New
  kinds register a defmethod here + a kind row in the per-kind
  renderer in `filters/pills.cljs`."
  (fn [_cascade pill]
    (:kind (canonicalise-pill pill))))

(defmethod cascade-matches-by-kind? :default
  ;; Unknown / malformed kinds match nothing. Belt-and-braces — a
  ;; future kind that ships persisted but not yet wired matches
  ;; nothing instead of dropping every cascade.
  [_cascade _pill]
  false)

(defmethod cascade-matches-by-kind? :never
  [_cascade _pill]
  false)

(defmethod cascade-matches-by-kind? :event-id-pattern
  ;; Delegate to the event-id pattern matcher. The pill's
  ;; `:params :pattern` is the same `kw-or-str` shape the event-id
  ;; matcher consumes; we surface it under params for typed pills and
  ;; fall back to the top-level `:pattern` for the bare-pattern shape.
  [cascade pill]
  (let [canonical (canonicalise-pill pill)
        pattern   (get-in canonical [:params :pattern])
        event-id  (let [ev (:event cascade)]
                    (when (vector? ev) (first ev)))]
    (event-matcher/match-pill? {:pattern pattern} event-id)))

(defmethod cascade-matches-by-kind? :machine
  ;; True iff any trace event in the cascade carries a `:tags
  ;; :machine-id` equal to the pill's `:machine-id`. Reads the same
  ;; slot the machine-inspector / cancellation-cascade / arc helpers
  ;; consume so the right-click affordance's pill and the inspector's
  ;; per-machine projection compose against the same data.
  [cascade {:keys [params]}]
  (let [target (:machine-id params)]
    (boolean
      (when (some? target)
        (some (fn [ev]
                (= target (tag-of ev :machine-id)))
              (cascade-trace-events cascade))))))

(defmethod cascade-matches-by-kind? :http-correlation
  ;; True iff any trace event in the cascade carries a `:tags
  ;; :correlation-id` equal to the pill's `:correlation-id`. HTTP /
  ;; WebSocket / managed-fx surfaces stamp this tag on the issuing
  ;; effect + every downstream response/abort event so a single
  ;; correlation pill captures the whole exchange's events.
  [cascade {:keys [params]}]
  (let [target (:correlation-id params)]
    (boolean
      (when (some? target)
        (some (fn [ev]
                (= target (tag-of ev :correlation-id)))
              (cascade-trace-events cascade))))))

(defmethod cascade-matches-by-kind? :fx
  ;; True iff any trace event in the cascade carries a `:tags :rf.fx/id`
  ;; equal to the pill's `:fx-id`. Surfaces `:rf.fx/handled` +
  ;; `:rf.fx/override-applied` + `:rf.fx/skipped` events so an `:fx`
  ;; pill captures every cascade that triggered the registered fx
  ;; kind regardless of outcome.
  [cascade {:keys [params]}]
  (let [target (:fx-id params)]
    (boolean
      (when (some? target)
        (some (fn [ev]
                (= target (tag-of ev :rf.fx/id)))
              (cascade-trace-events cascade))))))

;; ---- cascade-level composition ------------------------------------------

(defn cascade-matches-pill?
  "True iff the typed pill matches the cascade. Single entrypoint —
  routes through `canonicalise-pill` so the bare `{:pattern ...}` shape
  and explicit `{:kind ...}` shape land at the same matcher."
  [cascade pill]
  (cascade-matches-by-kind? cascade (canonicalise-pill pill)))

(defn cascade-matches-any?
  "True iff any pill in `pills` matches the cascade. Empty / nil
  `pills` → false. Pre-alpha posture: short-circuits on first hit."
  [cascade pills]
  (boolean
    (when (seq pills)
      (some #(cascade-matches-pill? cascade %) pills))))

;; ---- causal-parent walk -------------------------------------------------
;;
;; Under epoch-per-event each dequeued event — including `:fx :dispatch`
;; children and machine-internal transitions — is its OWN cascade
;; (`re-frame.trace.projection/group-cascades` keys one record per
;; `:rf.trace/dispatch-id`). A `:machine` / `:http` / `:fx` typed pill
;; matching ONLY the cascade carrying its tag therefore loses the link
;; to the PARENT event that spawned the transition (the spawning event
;; lives in a sibling cascade). 'Filter to this machine' must
;; surface the spawning event's cascade too, not just the machine's
;; bare epoch.
;;
;; The projection surfaces the causal-parent link once per cascade as
;; `:parent-dispatch-id` (read off the dispatched event's
;; `:rf.trace/parent-dispatch-id` tag — the in-flight cascade that
;; emitted this dispatch). A MATCHING cascade pulls in its whole
;; SPAWNING LINEAGE: a `:machine` pill matches the transition CHILD
;; cascade, and we ALSO keep that child's ancestors (the spawning event
;; up to the root user event) — `keep = matching cascades ∪ their
;; ancestors`. Cascades key by `[frame dispatch-id]`; the parent link
;; is dispatch-id only (parent + child always share a frame under
;; epoch-per-event), so we index by dispatch-id and walk by id.

(defn index-by-dispatch-id
  "Build a `{dispatch-id → cascade}` index for ancestor walking. The
  `:ungrouped` pseudo-cascade and any cascade with a nil dispatch-id
  are excluded — they are not addressable causal nodes. Pure data."
  [cascades]
  (persistent!
    (reduce (fn [acc cascade]
              (let [id (:dispatch-id cascade)]
                (if (and (some? id) (not= :ungrouped id))
                  (assoc! acc id cascade)
                  acc)))
            (transient {})
            cascades)))

(defn cascade-self-and-ancestors
  "Return `cascade` followed by its causal ancestors, walking the
  `:parent-dispatch-id` link up to the root through `index` (a
  `{dispatch-id → cascade}` map). Cycle-guarded (a malformed trace
  with a parent loop terminates) and root-terminated (nil parent /
  missing parent ends the walk). Pure data → vector, self-first."
  [cascade index]
  (loop [c    cascade
         seen #{}
         acc  []]
    (if (or (nil? c)
            (contains? seen (:dispatch-id c)))
      acc
      (let [parent-id (:parent-dispatch-id c)]
        (recur (when (some? parent-id) (get index parent-id))
               (conj seen (:dispatch-id c))
               (conj acc c))))))

(defn in-kept-id-set
  "Compute the set of `:dispatch-id`s kept by the IN pills.
  A cascade that DIRECTLY matches any IN pill pulls in its whole
  spawning lineage — itself plus every causal ancestor (walked via
  `index`). The union is what surfaces the spawning event's cascade
  alongside the machine/http/fx transition cascade under
  epoch-per-event. nil / empty `in` returns nil (the 'no IN filter,
  keep everything' signal — distinct from an empty set, which means
  'IN active but nothing matched'). Pure data → set-or-nil."
  [cascades index in]
  (when (seq in)
    (reduce (fn [kept cascade]
              (if (cascade-matches-any? cascade in)
                (into kept
                      (map :dispatch-id)
                      (cascade-self-and-ancestors cascade index))
                kept))
            #{}
            cascades)))

(defn keep-cascade?
  "True iff `cascade` survives the active filter per spec/018 §7:

      keep = (no-IN-pills OR in-kept) AND NOT (matches-OUT)

  Mirrors `matcher/keep-cascade?` but routes through the typed-
  predicate dispatch so IN and OUT pills can be a mix of keyword
  patterns + typed predicates.

  `in-kept-ids` is the precomputed set from `in-kept-id-set` — the
  dispatch-ids kept by the IN pills, INCLUDING the spawning ancestors
  of every directly-matching cascade. nil `in-kept-ids`
  means no IN filter is active (keep every cascade subject to OUT). The
  OUT test stays cascade-local — a hide pill suppresses only the
  cascade carrying its tag, never its ancestors (so hiding a child's fx
  does not silently drop the originating user event).

  Pure data; JVM-runnable. The single-cascade arity falls back
  to direct (ancestor-free) IN matching for callers without the full
  cascade set."
  ([cascade {:keys [in out]}]
   (let [in-ok?  (or (empty? in)
                     (cascade-matches-any? cascade in))
         out-hit (cascade-matches-any? cascade out)]
     (and in-ok? (not out-hit))))
  ([cascade in-kept-ids {:keys [out]}]
   (let [in-ok?  (or (nil? in-kept-ids)
                     (contains? in-kept-ids (:dispatch-id cascade)))
         out-hit (cascade-matches-any? cascade out)]
     (and in-ok? (not out-hit)))))

(defn filter-cascades
  "Apply `filters` to `cascades`, returning the surviving subseq in
  order. Pure — no I/O, no atoms read. Drop-in replacement for
  `matcher/filter-cascades` that routes through typed-predicate
  dispatch.

  Builds a `{dispatch-id → cascade}` index once, computes the IN-kept
  id set (each directly-matching cascade pulls in its spawning
  ancestors), then keeps cascades whose id is in that set
  and which no OUT pill suppresses — so a typed pill on a child
  transition cascade also keeps the spawning event's cascade under
  epoch-per-event."
  [cascades filters]
  (let [index    (index-by-dispatch-id cascades)
        kept-ids (in-kept-id-set cascades index (:in filters))]
    (filterv #(keep-cascade? % kept-ids filters) cascades)))

;; ---- pill labels (presentation hooks) -----------------------------------

(defmulti pill-label
  "Render label for one pill — kind-specific format. Pure-data; the
  view (`filters/pills.cljs`) wraps this in the styled chrome."
  (fn [pill]
    (:kind (canonicalise-pill pill))))

(defmethod pill-label :default
  [pill]
  (pr-str pill))

(defmethod pill-label :never
  [_pill]
  "<empty>")

(defmethod pill-label :event-id-pattern
  [pill]
  (let [pattern (get-in (canonicalise-pill pill) [:params :pattern])]
    (cond
      (nil? pattern)              "<empty>"
      (keyword? pattern)          (str pattern)
      (and (string? pattern)
           (seq pattern))         pattern
      :else                       "<empty>")))

(defmethod pill-label :machine
  [pill]
  (let [{:keys [machine-id]} (:params (canonicalise-pill pill))]
    (if (some? machine-id)
      (str machine-id)
      "<machine>")))

(defmethod pill-label :http-correlation
  [pill]
  (let [{:keys [correlation-id]} (:params (canonicalise-pill pill))]
    (if (some? correlation-id)
      (str correlation-id)
      "<correlation>")))

(defmethod pill-label :fx
  [pill]
  (let [{:keys [fx-id]} (:params (canonicalise-pill pill))]
    (if (some? fx-id)
      (str fx-id)
      "<fx>")))

(defmulti pill-glyph
  "Per-kind glyph paired with the label — gives the user a quick
  visual key for what predicate kind the pill carries. Mirrors
  `surface->glyph` in `managed_fx_helpers.cljc` so the same alphabet
  is used across the chrome."
  (fn [pill]
    (:kind (canonicalise-pill pill))))

(defmethod pill-glyph :default
  [_pill]
  nil)

(defmethod pill-glyph :event-id-pattern
  [_pill]
  ;; The keyword-pattern pill has no per-kind glyph — the bare label
  ;; (`+ :auth/* ✎`) is the spec/018 §7 contract.
  nil)

(defmethod pill-glyph :machine
  [_pill]
  "M")

(defmethod pill-glyph :http-correlation
  [_pill]
  "H")

(defmethod pill-glyph :fx
  [_pill]
  "F")
