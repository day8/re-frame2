(ns re-frame.subs.override-schema
  "The ONE Story `:sub-overrides` schema-validation primitive.

  A Story render's lowest-fidelity rung pins a view into an
  `:error`/`:loading`/`:empty` state by naming subscription query-vectors
  and the values they should surface (`tools/story/spec/017-Testing-Story.md`
  §View-state subscription overrides). An override whose value VIOLATES the
  target sub's own declared output `:schema` is exactly the 'pin a state the
  real derivation could never produce' anti-pattern — so the `subscribe`
  path (`re-frame.subs`) validates a HIT the SAME way Spec 010 §step 6
  validates a `:sub-return`: through the `:schemas/check!` late-bind hook, the
  one validation seam (NOT a second validation mechanism, NO tools
  dependency), which emits `:rf.error/schema-validation-failure` with the
  `:where :sub-override` discriminator, and render the pinned value anyway
  (`:recovery :no-recovery`, as `:sub-return` does). An impossible override
  is reported wherever it is consulted, never only in the fast path.

  This primitive is HOST-AGNOSTIC (`.cljc`) so a headless JVM render reaches
  it too (`interop/debug-enabled?` is true there), while the consult itself
  is gated behind `interop/debug-enabled?` so it DCEs under `:advanced` +
  `goog.DEBUG=false`. It is reached ONLY from those gated consults, so no
  production build retains it (its containing `(when interop/debug-enabled?
  …)` blocks fold away, dropping the last reference)."
  (:require [re-frame.late-bind :as rf.late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(defn validate-sub-override!
  "Validate a resolved Story override HIT, report a violation, and return
  the pinned value either way.

  When `sub-meta` declares an output `:schema` — declaration is KEY-presence,
  not value truthiness; a present nil / false token is delegated
  verbatim — validate `value` against it through the `:schemas/check!` seam,
  which reports a mismatch as `:rf.error/schema-validation-failure` tagged
  `:where :sub-override` (redacted exactly as the `:sub-return` path is, so a
  `:sensitive?`-marked sub scrubs identically) and a validator throw as
  `:rf.error/malformed-schema`, both with `:recovery :no-recovery`. `value`
  is returned unchanged in every case, so a bad schema or validator never
  crashes the render.

  `query-v` is the overridden query (its head is the sub-id); `frame-id` is
  the subscribing frame, stamped onto the failure trace so
  `re-frame.epoch.capture/capture-event!` (which buffers only frame-tagged
  traces) attributes the failure to that frame's epoch — pass nil when
  frameless. Callers resolve `sub-meta` through the target frame's own view
  (a multi-image frame validates against its own image's schema);
  this primitive takes the already-resolved metadata."
  [value query-v sub-meta frame-id]
  ;; KEY-presence, not value truthiness: a present nil / false
  ;; `:schema` is a declaration whose exact token is delegated to the
  ;; registered validator; only an ABSENT key means no declaration.
  ;; (`contains?` on a nil sub-meta is false, covering the frameless case.)
  (if-not (contains? sub-meta :schema)
    value
    (do
      (when-let [check! (rf.late-bind/get-fn :schemas/check!)]
        (let [schema (:schema sub-meta)
              sub-id (first query-v)]
          (check! {:where      :sub-override
                   :id         sub-id
                   :schema     schema
                   :value      value
                   :frame      frame-id
                   :extra-tags {:rf.sub/id sub-id :schema-id sub-id :rf.sub/query-v query-v}
                   :reason     #(str "Subscription " sub-id
                                     " :sub-override value failed schema "
                                     (pr-str schema) ".")})))
      value)))
