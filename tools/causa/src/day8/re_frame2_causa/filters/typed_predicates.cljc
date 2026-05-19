(ns day8.re-frame2-causa.filters.typed-predicates
  "Typed-predicate filter kinds for Causa pills (rf2-piye4).

  ## Why typed predicates

  Spec/018 §7 pills shipped as `{:pattern <kw-or-str>}` records keyed
  off the cascade's `event-id` only. Surface-aware filters — 'show
  every cascade that touched THIS machine' / 'show every cascade in
  THIS HTTP exchange' / 'show every cascade triggering THIS fx kind'
  — need to walk the cascade's trace-events, not just its event-id.
  This ns generalises the pill record into a typed predicate:

      {:kind   <:event-id-pattern | :machine | :http-correlation | :fx>
       :params <kind-specific map>}

  Each kind has a matcher in `cascade-matches-by-kind?` that consults
  the cascade's data shape — event-id-pattern delegates to the
  existing event-id matcher (`matcher.cljc`), the other three walk the
  cascade's trace-event buckets for the expected `:tags` slot.

  ## v1 kinds (per Mike's rf2-drcyb closure)

  - `:event-id-pattern` — back-compat with rf2-ak4ms's
    `{:pattern <kw-or-str>}` shape. Delegates to the existing
    pattern matcher.
  - `:machine` — match cascades whose trace-events include a
    `:tags :machine-id` equal to `<params :machine-id>`. The right-
    click affordance on the Machines panel rows fires
    `:rf.causa/filter-by-machine` which appends this kind.
  - `:http-correlation` — match cascades whose trace-events include a
    `:tags :correlation-id` equal to `<params :correlation-id>`. The
    right-click affordance on managed-fx HTTP records fires
    `:rf.causa/filter-by-http-correlation`.
  - `:fx` — match cascades that triggered the fx with this `fx-id`.
    Walks the trace-events for any event whose `:tags :fx-id` equals
    `<params :fx-id>`. The right-click affordance on managed-fx
    records' surface badge fires `:rf.causa/filter-by-fx`.

  ## Deferred kinds (Mike's rf2-piye4 scoping)

  - `:source-coord` — defer to v1.1; niche.
  - `:interceptor` — defer to v1.1; niche.
  - `:descendant-of` — MOOT (Causality dropped this session).

  ## Back-compat

  Pills stored under the legacy `{:pattern <kw-or-str>}` shape (every
  pill written before this bead) hydrate as `:event-id-pattern` via
  `canonicalise-pill`. The persistence load path is unchanged; the
  matcher folds the missing `:kind` slot into the canonical kind on
  the way through. New typed pills written by the right-click
  affordances persist with the explicit `:kind` so re-load round-trips
  cleanly.

  ## Composition (spec/018 §7 unchanged)

  IN/OUT pill composition stays:

      keep = (no-IN-pills OR matches-IN) AND NOT (matches-OUT)

  — typed pills compose with keyword-pattern pills inside the same
  bucket via `some` (any pill in the bucket can match the cascade).
  Mixing typed + keyword pills in the same bucket is supported and
  exercised by tests.

  Pure data → bool. JVM-runnable so the test corpus exercises every
  kind without a CLJS runtime."
  (:require [day8.re-frame2-causa.filters.matcher :as event-matcher]))

;; ---- canonical pill shape -----------------------------------------------

(defn canonicalise-pill
  "Coerce a pill record into the canonical typed-predicate shape:

      {:kind <keyword> :params <map>}

  - If `:kind` is already present, return verbatim (with `:params`
    defaulted to `{}`).
  - If `:pattern` is present and `:kind` is absent, infer
    `:event-id-pattern` and migrate `:pattern` into `:params`. This is
    the back-compat path for pills persisted under the rf2-ak4ms shape.
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
    ;; Legacy shape: surface the pattern under params so the matcher
    ;; reads from one slot.
    {:kind   :event-id-pattern
     :params {:pattern (:pattern pill)
              :scope   (or (:scope pill) #{:event-id})}}

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
  ;; Delegate to the existing event-id pattern matcher. The pill's
  ;; `:params :pattern` is the same `kw-or-str` shape the legacy
  ;; matcher consumes; we surface it under params for typed pills and
  ;; fall back to the top-level `:pattern` for legacy shape.
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
  ;; True iff any trace event in the cascade carries a `:tags :fx-id`
  ;; equal to the pill's `:fx-id`. Surfaces `:rf.fx/handled` +
  ;; `:rf.fx/override-applied` + `:rf.fx/skipped` events so an `:fx`
  ;; pill captures every cascade that triggered the registered fx
  ;; kind regardless of outcome.
  [cascade {:keys [params]}]
  (let [target (:fx-id params)]
    (boolean
      (when (some? target)
        (some (fn [ev]
                (= target (tag-of ev :fx-id)))
              (cascade-trace-events cascade))))))

;; ---- cascade-level composition ------------------------------------------

(defn cascade-matches-pill?
  "True iff the typed pill matches the cascade. Single entrypoint —
  routes through `canonicalise-pill` so legacy `{:pattern ...}` shape
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

(defn keep-cascade?
  "True iff `cascade` survives the active filter per spec/018 §7:

      keep = (no-IN-pills OR matches-IN) AND NOT (matches-OUT)

  Mirrors `matcher/keep-cascade?` but routes through the typed-
  predicate dispatch so IN and OUT pills can be a mix of keyword
  patterns + typed predicates. Pure data; JVM-runnable."
  [cascade {:keys [in out]}]
  (let [in-ok?  (or (empty? in)
                    (cascade-matches-any? cascade in))
        out-hit (cascade-matches-any? cascade out)]
    (and in-ok? (not out-hit))))

(defn filter-cascades
  "Apply `filters` to `cascades`, returning the surviving subseq in
  order. Pure — no I/O, no atoms read. Drop-in replacement for
  `matcher/filter-cascades` that routes through typed-predicate
  dispatch."
  [cascades filters]
  (filterv #(keep-cascade? % filters) cascades))

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
