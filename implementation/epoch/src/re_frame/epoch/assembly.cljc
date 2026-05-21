(ns re-frame.epoch.assembly
  "Record assembly — the pure-data builder that takes a frame-id, the
  before/after app-db snapshots, and the harvested cascade trace
  events, and yields a fully-formed `:rf/epoch-record` map.

  Three responsibilities live here:

    1. `build-record`   — produces the record (one allocation per
                          drain-settle). Delegates the cascade-buffer
                          walks to `re-frame.epoch.capture`.
    2. `sensitive-rollup` — computes `:rf.epoch/sensitive?` from raw
                          signals (trace-event stamps + schema-declared
                          sensitive paths).
    3. `maybe-redact`   — runs the installed `:redact-fn` once between
                          assembly and ring-append / listener fan-out
                          so every downstream consumer sees the SAME
                          redacted shape.

  Plus the `current-schema-digest` accessor that both `build-record`
  (digest pinned on the record) and the restore-preconditions seam
  (`re-frame.epoch.write`) consume.

  Per rf2-0wi86 Phase-2 seam D: this namespace is dependency-free
  beyond state (atoms + counter), capture (cascade walks), and the
  shared `re-frame.elision` / `re-frame.late-bind` / `re-frame.privacy`
  / `re-frame.trace` libs. No upstream seam consumes it; downstream
  seams (write, listeners, facade orchestrators) call into it."
  (:require [re-frame.elision :as elision]
            [re-frame.epoch.capture :as capture]
            [re-frame.epoch.state :as state]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.trace :as trace]))

;; ---- redaction hook -------------------------------------------------------

(defn maybe-redact
  "Run the installed `:redact-fn` against `record` and return its
  result. nil `record` and nil `redact` are pass-throughs (no
  invocation, no try/catch overhead). A throwing fn emits
  `:rf.warning/epoch-redact-fn-exception` carrying `:frame` and
  `:ex-msg`, then falls back to the raw record so the drain itself
  is not broken.

  Per Tool-Pair §Time-travel §Redaction hook + Security.md §Epoch
  privacy posture (rf2-wp70d): the fn runs ONCE at build-time,
  BETWEEN `build-record` and ring-append / listener fan-out — the
  ring buffer, every `register-epoch-listener!` listener, and any off-box
  projection through `projected-record` all see the SAME redacted
  shape. The `:rf.epoch/sensitive?` rollup is computed inside
  `build-record` (which runs first) so the rollup reflects the RAW
  signal even when the fn erases the leaves it keyed on.

  Caller MUST wrap invocations in `(when interop/debug-enabled? ...)`
  — the whole epoch surface shares that gate; this helper carries no
  separate production gate.

  HOT PATH: fires once per drain-settle. Hot-path cost when no fn is
  installed is a single keyword lookup on the config map and an
  identity return."
  [record]
  (if-let [f (state/redact-fn)]
    (if (some? record)
      (try
        (f record)
        (catch #?(:clj Throwable :cljs :default) e
          ;; Failure isolation: emit the warning, fall back to the
          ;; raw record. The keyword literal sits inside an
          ;; `(when interop/debug-enabled? ...)` gate at the call
          ;; site (every consumer of `maybe-redact` is itself gated),
          ;; so Closure DCE elides the warning emit + literals
          ;; under :advanced + goog.DEBUG=false.
          (trace/emit! :warning :rf.warning/epoch-redact-fn-exception
                       {:frame  (:frame record)
                        :ex-msg #?(:clj (.getMessage ^Throwable e)
                                   :cljs (.-message e))})
          record))
      record)
    record))

;; ---- schema-digest --------------------------------------------------------

(defn current-schema-digest
  "Return the live digest of the named frame's registered app-schema set,
  or nil when the schemas namespace has not registered its late-bind
  hook (e.g. an embedding host that ships no schema layer). Per Spec 010
  §Per-frame schemas the digest is frame-scoped — restore-mismatch
  reasoning runs against the frame the epoch belongs to."
  [frame-id]
  (when-let [digest (late-bind/get-fn :schemas/app-schemas-digest)]
    (try (digest frame-id)
         (catch #?(:clj Throwable :cljs :default) _ nil))))

;; ---- sensitive rollup -----------------------------------------------------
;;
;; Per Security.md §Epoch privacy posture and rf2-mrsck: every assembled
;; record carries a top-level boolean `:rf.epoch/sensitive?` rollup so
;; listener fan-out, off-box egress, and recorder consumers can branch
;; on one slot per record (parallel to the trace-event-level
;; `:sensitive?` stamp per rf2-isdwf). Two cheap signals:
;;
;;   1. The captured trace stream already stamps `:sensitive?` per
;;      handler-meta scope — if any event in `:trace-events` carries the
;;      stamp, the record's cascade involved sensitive material.
;;   2. The frame's schema-declared `[:rf/elision :sensitive-declarations]`
;;      registry names paths that hold sensitive data; if any such path
;;      resolves to a non-nil leaf in `:db-before` or `:db-after`, the
;;      record's app-db state carries sensitive material.
;;
;; Either signal is sufficient. The check runs once at record-assembly
;; time so listeners and the projected-record helper read the rollup
;; without re-walking the record. Production builds elide the entire
;; record-assembly path — the rollup is dev-only by construction.

(defn- any-sensitive-event?
  "True when any captured trace event carries a top-level `:sensitive?
  true` stamp. Per privacy/sensitive? — the trace surface hoists the
  boolean from handler-scope-meta to the event top level (rf2-isdwf)."
  [events]
  (boolean (some privacy/sensitive? events)))

(defn- sensitive-paths-for
  "Return the schema-declared sensitive paths for `frame-id`. Empty when
  no schema layer is registered or when no slot carries
  `{:sensitive? true}`."
  [frame-id]
  (try (keys (elision/sensitive-declarations frame-id))
       (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- any-sensitive-leaf?
  "True when any sensitive-declared path resolves to a non-nil leaf in
  `db`. nil-leaf paths do NOT count — the path is declared sensitive
  but the slot is empty, so the record carries no actual sensitive
  material from this signal. `db` is `:db-before` or `:db-after`; both
  may be nil on halted paths (rf2-v0jwt :halted-destroy)."
  [db sensitive-paths]
  (and (some? db)
       (boolean
         (some (fn [path]
                 (some? (get-in db path)))
               sensitive-paths))))

(defn sensitive-rollup
  "Compute the record-level `:rf.epoch/sensitive?` rollup for the
  assembled record. Returns `true` when the record's content overlaps
  a sensitive area — either via a stamped trace event or via a
  schema-declared sensitive path that holds a non-nil leaf in the
  recorded db. Returns `false` otherwise (always a strict boolean —
  consumers branch on `(true? ...)` / `(false? ...)`).

  HOT PATH: fires once per drain-settle. `sensitive-paths-for` derefs
  the elision registry once; the leaf check short-circuits at the
  first non-nil hit; the trace-event check short-circuits at the first
  stamped event. For the common case (no schema-declared sensitive
  paths, no sensitive handlers in scope) the cost is one keys-of-empty
  call plus two sequence-with-no-work walks."
  [frame-id db-before db-after events]
  (boolean
    (or (any-sensitive-event? events)
        (let [sensitive-paths (sensitive-paths-for frame-id)]
          (and (seq sensitive-paths)
               (or (any-sensitive-leaf? db-before sensitive-paths)
                   (any-sensitive-leaf? db-after  sensitive-paths)))))))

;; ---- redacted-modified-paths-count (rf2-dl3gx) ---------------------------
;;
;; Per Security.md §Epoch privacy posture and rf2-dl3gx (follow-on to
;; rf2-bz1cl's Causa-side heuristic chip): the record carries a
;; top-level integer count of schema-declared sensitive paths
;; (`[:rf/elision :sensitive-declarations]`) whose value differs between
;; `:db-before` and `:db-after`. Computed inside `build-record` from RAW
;; values BEFORE the installed `:redact-fn` runs — parallel to the
;; `:rf.epoch/sensitive?` rollup pattern just above.
;;
;; ## Why the count exists
;;
;; When an app `:redact-fn` substitutes the `:rf/redacted` sentinel into
;; both `:db-before` and `:db-after` at the same path (the standard
;; pattern for keeping sensitive material out of recorded records), and
;; the underlying value at that path actually changed across the
;; cascade, the structural diff sees `:rf/redacted` on both sides and
;; (correctly per the elision contract) emits no row. The developer
;; sees an empty diff body and no signal that anything happened.
;;
;; The count closes that gap by recording, on the raw record, exactly
;; how many schema-declared sensitive paths mutated this cascade.
;; Downstream consumers (Causa's `redacted-paths-modified` chip per
;; `tools/causa/spec/004-App-DB-Diff.md`, MCP wire pipeline, story
;; recorders) read the integer directly rather than re-deriving from
;; the post-redaction shape (which is heuristic — see the Causa helper
;; for the heuristic's tightness and approximation notes).
;;
;; ## What counts
;;
;; A path P counts when:
;;   1. P appears in `sensitive-paths-for frame-id`
;;      (schema-declared via `{:sensitive? true}` props per Spec 015).
;;   2. `(not= (get-in db-before P) (get-in db-after P))`.
;;
;; The check uses **value-equality** on the raw (pre-redact-fn) dbs.
;; Pointer-equality would over-count under `assoc-in` rewrites that
;; produce a fresh subtree whose leaves are value-equal; `=` is the
;; precise predicate for "did this leaf actually change."
;;
;; ## What this does NOT cover
;;
;; - `:redact-fn` substitutions at NON-schema-declared paths. The
;;   redact-fn is opaque (record-in, record-out); the framework cannot
;;   inspect "would the fn redact this leaf?" without running it. The
;;   schema-declared sensitive-paths set is the framework's authoritative
;;   "what would be redacted" oracle — apps that install a redact-fn
;;   pointing at non-schema paths get the schema-declared count, not a
;;   superset. (In practice apps either declare sensitive props on
;;   schemas or run a redact-fn covering the same paths; the two
;;   surfaces compose at the schema-declaration site.)
;; - Paths nominated as sensitive but with no live leaf (`nil` on both
;;   sides) — `(= nil nil)` is true, so the path doesn't count. A
;;   transition from non-nil to nil (or vice versa) counts as a value
;;   change.
;; - Production builds. The entire epoch surface elides under
;;   `interop/debug-enabled?` false; the count is dev-only by
;;   construction.

(defn redacted-modified-paths-count
  "Compute the record-level `:rf.epoch/redacted-modified-paths-count`
  rollup for the assembled record. Returns the integer count of
  schema-declared sensitive paths whose value differs between
  `db-before` and `db-after` (per rf2-dl3gx).

  HOT PATH: fires once per drain-settle. `sensitive-paths-for` derefs
  the elision registry once and the walk is O(P) where P is the
  declared-sensitive-path count for the frame — typically a small
  constant (apps declare a handful of `[:auth :password]`-shaped
  paths). For the common case (no schema-declared sensitive paths)
  the cost is one keys-of-empty call and an empty-reduce.

  Returns `0` when:
    - No paths are declared sensitive (no schema layer / no
      `{:sensitive? true}` props), OR
    - No declared-sensitive path's value differs across the cascade.

  Halted records: `db-before` and/or `db-after` may be `nil` on the
  `:halted-destroy` path (per rf2-v0jwt). `(get-in nil P)` is `nil`;
  the predicate `(not= a b)` handles the nil/non-nil edge correctly
  (counts as a change when one side is nil and the other isn't)."
  [frame-id db-before db-after]
  (let [sensitive-paths (sensitive-paths-for frame-id)]
    (if (empty? sensitive-paths)
      0
      (reduce
        (fn [acc path]
          (if (not= (get-in db-before path)
                    (get-in db-after  path))
            (inc acc)
            acc))
        0
        sensitive-paths))))

;; ---- record assembly ------------------------------------------------------

(defn build-record
  ([frame-id db-before db-after events]
   (build-record frame-id db-before db-after events :ok nil))
  ([frame-id db-before db-after events outcome halt-reason]
   ;; Per rf2-v0jwt §Outcomes — :outcome is required and pins the
   ;; drain-boundary outcome. The reference runtime commits one of three:
   ;; :ok / :halted-depth / :halted-destroy. (:halted-handler-exception
   ;; is a schema-reserved value the runtime never emits — handler
   ;; exceptions ride the interceptor error-capture seam and the drain
   ;; settles :ok with the error trace under :trace-events; see
   ;; Spec-Schemas §:rf/epoch-record §Outcomes.) :halt-reason is a
   ;; structured descriptor populated on halt paths, absent on :ok. The
   ;; schema in Spec-Schemas §:rf/epoch-record is the canonical pin.
   ;;
   ;; Per rf2-kl5p1 (audit r3 §F1): `:event-id` and `:trigger-event`
   ;; are emitted only when `find-trigger-event` resolves them. The
   ;; schema declares `:event-id :keyword` (required, non-maybe) per
   ;; Spec-Schemas §`:rf/epoch-record` — emitting `:event-id nil` on a
   ;; halt path where no `:event/run-start` trace was buffered would
   ;; violate the schema; the open-map admits the slot's absence but
   ;; rejects a nil value. The live router halt paths already short-
   ;; circuit on an empty buffer via `(when (seq events) ...)` in
   ;; `settle!`, so the only path that can reach this branch with a
   ;; trigger-less buffer is `on-frame-destroyed!`'s `:halted-destroy`
   ;; commit; the conditional `cond->` slots make that record valid
   ;; against the schema.
   (let [{:keys [event-id event]}     (capture/find-trigger-event events)
         ;; Per rf2-ecu37: one fused walk producing all three
         ;; projections, replacing three independent transducer
         ;; passes over the same buffer. Mirrors `find-trigger-event`'s
         ;; rf2-txrq9 single-walk pattern.
         {:keys [sub-runs renders effects]} (capture/project-all events)
         ;; Per rf2-mrsck and Security.md §Epoch privacy posture:
         ;; the record-level boolean rollup mirrors the trace-event
         ;; `:sensitive?` stamp (rf2-isdwf) so listener consumers and
         ;; the projected-record helper branch on one slot per record.
         sensitive? (sensitive-rollup frame-id db-before db-after events)
         ;; Per rf2-dl3gx and Security.md §Epoch privacy posture: the
         ;; record-level integer counter of schema-declared sensitive
         ;; paths whose value differs between :db-before / :db-after.
         ;; Closes Causa's "redact-fn ⇒ empty diff but something changed"
         ;; gap by surfacing the suppressed signal directly on the
         ;; record. Computed BEFORE :redact-fn runs (parallel to the
         ;; :rf.epoch/sensitive? rollup above).
         redacted-modified-count (redacted-modified-paths-count
                                   frame-id db-before db-after)]
     (cond-> {:epoch-id           (state/next-epoch-id)
              :frame              frame-id
              :committed-at       (interop/now-ms)
              :db-before          db-before
              :db-after           db-after
              :outcome            outcome
              ;; Per Spec 010 §Schema digest — pinned at record time so a later
              ;; restore can compare 'recorded vs current' digests in the
              ;; :rf.epoch/restore-schema-mismatch trace tags. Optional per
              ;; Spec-Schemas §:rf/epoch-record (a host without a schema layer
              ;; produces nil; consumers tolerate the absent slot).
              :schema-digest      (current-schema-digest frame-id)
              :rf.epoch/sensitive? sensitive?
              :rf.epoch/redacted-modified-paths-count redacted-modified-count
              :trace-events       events
              :sub-runs           sub-runs
              :renders            renders
              :effects            effects}
       event-id    (assoc :event-id event-id)
       event       (assoc :trigger-event event)
       halt-reason (assoc :halt-reason halt-reason)))))
