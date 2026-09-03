(ns re-frame.subs.override-schema
  "The ONE Story `:sub-overrides` schema-validation primitive
  (rf2-vxgfnd.21).

  A Story render's lowest-fidelity rung pins a view into an
  `:error`/`:loading`/`:empty` state by naming subscription query-vectors
  and the values they should surface (`tools/story/spec/017-Testing-Story.md`
  §View-state subscription overrides). An override whose value VIOLATES the
  target sub's own declared output `:schema` is exactly the 'pin a state the
  real derivation could never produce' anti-pattern — so the `subscribe`
  path (`re-frame.subs`) validates a HIT the SAME way Spec 010 §step 6
  validates a `:sub-return`: through the registered validator reached via the
  `:schemas/validate-with-registered-fn` late-bind hook (NOT a second
  validation mechanism, NO tools dependency), emit
  `:rf.error/schema-validation-failure` with the `:where :sub-override`
  discriminator, and recover-to-nil (mirroring `:sub-return`'s
  `:replaced-with-default`). An impossible override is rejected wherever it
  is consulted, never only in the fast path.

  This primitive is HOST-AGNOSTIC (`.cljc`) so a headless JVM render reaches
  it too (`interop/debug-enabled?` is true there), while the consult itself
  is gated behind `interop/debug-enabled?` so it DCEs under `:advanced` +
  `goog.DEBUG=false`. It is reached ONLY from those gated consults, so no
  production build retains it (its containing `(when interop/debug-enabled?
  …)` blocks fold away, dropping the last reference)."
  (:require [re-frame.late-bind :as rf.late-bind]
            [re-frame.trace :as rf.trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

(defn validate-sub-override!
  "Validate a resolved Story override HIT and RECOVER to nil on a violation.

  When `sub-meta` declares an output `:schema` — declaration is KEY-presence,
  not value truthiness (rf2-6eh5h); a present nil / false token is delegated
  verbatim — validate `value` against it
  via the registered validator (`:schemas/validate-with-registered-fn`). On
  a mismatch, emit `:rf.error/schema-validation-failure` tagged `:where
  :sub-override` (value-bearing slots routed through the shared
  `:schemas/redact-validation-tags` seam so a `:sensitive?`-marked sub
  scrubs identically to the `:sub-return` path) and return nil. On a pass /
  no `:schema` / no registered validator, return `value` unchanged. A
  throwing validator is treated as a pass (mirrors
  `subs.memo/maybe-validate-sub!`) so a bad validator never crashes the
  render.

  `query-v` is the overridden query (its head is the sub-id); `frame-id` is
  the subscribing frame, stamped onto the failure trace so
  `re-frame.epoch.capture/capture-event!` (which buffers only frame-tagged
  traces) attributes the failure to that frame's epoch — pass nil when
  frameless. Callers resolve `sub-meta` through the target frame's IMAGE
  generation (a multi-image frame validates against its own image's schema);
  this primitive takes the already-resolved metadata."
  [value query-v sub-meta frame-id]
  ;; KEY-presence, not value truthiness (rf2-6eh5h): a present nil / false
  ;; `:schema` is a declaration whose exact token is delegated to the
  ;; registered validator; only an ABSENT key means no declaration.
  ;; (`contains?` on a nil sub-meta is false, covering the frameless case.)
  (if-not (contains? sub-meta :schema)
    value
    (let [schema (:schema sub-meta)]
      (if-let [validate (rf.late-bind/get-fn-cached :schemas/validate-with-registered-fn)]
        (if (try (validate schema value)
                 (catch #?(:clj Throwable :cljs :default) _ true))
          value
          (let [sub-id  (first query-v)
                explain (when-let [exp (rf.late-bind/get-fn-cached
                                         :schemas/explain-with-registered-fn)]
                          (try (exp schema value)
                               (catch #?(:clj Throwable :cljs :default) _ nil)))
                redact  (rf.late-bind/get-fn-cached :schemas/redact-validation-tags)
                tags    (cond-> {:where          :sub-override
                                 :rf.sub/id      sub-id
                                 :failing-id     sub-id
                                 :schema-id      sub-id
                                 :rf.sub/query-v query-v
                                 :received       value
                                 :value          value
                                 :explain        explain
                                 :reason         (str "Subscription " sub-id
                                                      " :sub-override value failed schema "
                                                      (pr-str schema) ".")
                                 :recovery       :replaced-with-default}
                          frame-id (assoc :frame frame-id))]
            (rf.trace/emit-error! :rf.error/schema-validation-failure
                               (cond-> tags
                                 redact (->> (redact schema))))
            nil))
        value))))
