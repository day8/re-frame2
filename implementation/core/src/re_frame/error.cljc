(ns re-frame.error
  "Shared error-message helpers — the central thrown-error builder, plus
  short tag fns and reason-string primitives used by error / warning
  trace emit sites across core and the per-feature artefacts.

  `thrown-ex-info` / `throw-error!` are the canonical thrown-error
  builders (Spec 009 §The thrown-error shape). Every `(throw (ex-info …))`
  site in the runtime routes through them so the human message and the
  `:rf.error/id` machine discriminator cannot drift: the builder DERIVES
  the message from `:reason` + the `[:rf.error/<id>]` greppability token
  and sets the canonical ex-data shape. Keyword-only messages become
  structurally impossible by construction (rf2-vvixub).

  `type-of-value` renders a short type tag in the `:reason` string of
  a `:rf.error/schema-validation-failure` trace event; consumed by
  `re-frame.spec` and `re-frame.schemas.validate`.

  Lives in core because schemas / flows / routing depend on core (Spec
  006 §Adapter shipping convention) — pushing the helpers down means the
  per-feature artefacts can `:require` it without inverting the dep
  direction. Apps that don't load the schemas artefact still get the
  helpers for free under core.

  Pure; no runtime state. Hot-path safe (the type-tag cond cascade
  allocates nothing on the common branches; the thrown-error builders
  run only on the failure path).")

#?(:clj (set! *warn-on-reflection* true))

;; ---- the canonical thrown-error builder (rf2-vvixub) ----------------------
;;
;; Spec 009 §The thrown-error shape rules the human-message contract:
;;
;;   1. `(ex-message e)` is a human-actionable one-line sentence (the
;;      public concept + the expected fix + key context). It is NOT the
;;      stringified discriminator keyword.
;;   2. `:rf.error/id` is the SOLE canonical machine discriminator; tools
;;      and tests branch on it, never on the message.
;;   3. The message is stable in MEANING, not bytes — tests MUST NOT
;;      exact-equal it.
;;   4. The message carries a trailing `[:rf.error/<id>]` token for
;;      log/CI greppability, preserving the only thing the old
;;      "category-from-message-alone" property bought.
;;   5. `:reason` IS that required human sentence; there is no separate
;;      `:rf.error/message` slot (it would duplicate `:reason`).
;;
;; The builder derives the message FROM `:reason` + the token, so the
;; message and `:rf.error/id` cannot drift, and a keyword-only message is
;; impossible to emit. Today's hand-rolled `(ex-info (str error-kw) …)`
;; sites — which made the keyword the WHOLE message — route through here.

(defn id-token
  "The trailing greppability token for a thrown-error `:rf.error/id` —
  the bracketed keyword `[:rf.error/<id>]` appended to every framework
  exception message (Spec 009 §The thrown-error shape, rule 4). A log
  line or raw stack trace still pivots to a stable category by grepping
  the bracketed token, while the leading text carries the human sentence.

  nil-safe — returns the empty string when `error-id` is nil so a
  malformed call degrades to the bare reason rather than `[null]`."
  [error-id]
  (if (some? error-id)
    (str "[" error-id "]")
    ""))

(defn human-message
  "Derive the human-facing exception message (`ex-message`) from the
  required human `reason` sentence and the `error-id` greppability token
  (Spec 009 §The thrown-error shape). Shape:

    \"<reason> [:rf.error/<id>]\"

  e.g. \"rf/init! cannot continue because no adapter is installed;
  require an adapter ns and install it before boot.
  [:rf.error/no-adapter-installed]\".

  The message LEADS with the human sentence (rule 1) and trails with the
  category token (rule 4). Non-normative in bytes — consumers branch on
  `:rf.error/id`, never on this string."
  [error-id reason]
  (let [sentence (if (and (string? reason) (seq reason))
                   reason
                   ;; Defensive: a missing :reason is a builder-call bug,
                   ;; not a user-facing condition. Still emit a non-keyword
                   ;; message so the conformance gate's "no bare keyword
                   ;; message" invariant holds even on the degenerate path.
                   "re-frame2 raised an error")
        token    (id-token error-id)]
    (if (seq token)
      (str sentence " " token)
      sentence)))

(defn thrown-ex-info
  "Build the canonical thrown-error `ex-info` (Spec 009 §The thrown-error
  shape, rf2-vvixub). The SINGLE chokepoint every framework
  `(throw (ex-info …))` site routes through.

  Args:
    error-id  — the `:rf.error/<category>` keyword. The SOLE canonical
                machine discriminator; lands in the `:rf.error/id` slot.
    where-sym — the user-facing fn symbol that threw (`'rf/init!`,
                `'rf/reg-flow`, …) so a grep-for-symbol lands on the call
                site; lands in `:where`.
    reason    — the required one-sentence human diagnostic naming the
                public concept, the expected fix, and key context. Lands
                in `:reason` AND leads the derived message.

  Options (a map; all optional):
    :recovery — the [§Recovery contract] disposition; defaults to
                `:no-recovery`.
    :extra    — a map of surface-specific ex-data slots merged on top
                (`:flow`, `:route-id`, `:received`, `:bad-entries`, …).

  Returns the `ex-info`. The message is `(human-message error-id reason)`
  — the human sentence + the `[:rf.error/<id>]` token — so the message
  and the discriminator are derived from one source and cannot drift.
  Use `throw-error!` to build AND throw in one call."
  ([error-id where-sym reason] (thrown-ex-info error-id where-sym reason nil))
  ([error-id where-sym reason {:keys [recovery extra]}]
   (ex-info (human-message error-id reason)
            (merge {:rf.error/id error-id
                    :where       where-sym
                    :recovery    (or recovery :no-recovery)
                    :reason      reason}
                   extra))))

(defn throw-error!
  "Build (via `thrown-ex-info`) and `throw` the canonical thrown-error
  `ex-info` in one call (Spec 009 §The thrown-error shape, rf2-vvixub).
  Same args as `thrown-ex-info`. Never returns normally."
  ([error-id where-sym reason] (throw-error! error-id where-sym reason nil))
  ([error-id where-sym reason opts]
   (throw (thrown-ex-info error-id where-sym reason opts))))

(defn keyword-only-message?
  "Conformance predicate (rf2-vvixub): true when `message` is a bare
  stringified `:rf.error/…` keyword — i.e. the OLD keyword-only message
  shape the new contract forbids. A conformant framework message LEADS
  with a human sentence and only carries the keyword inside the trailing
  `[:rf.error/<id>]` token, so a conformant message is never `(str
  some-keyword)` on its own.

  Used by the thrown-error conformance test to reject any framework throw
  that regresses to a keyword-only human message."
  [message]
  (boolean
    (and (string? message)
         (re-matches #":rf\.error/[A-Za-z0-9*+!_.?\-]+" message))))

(defn message-has-id-token?
  "Conformance predicate (rf2-vvixub): true when `message` contains the
  trailing `[:rf.error/<id>]` greppability token (Spec 009 §The
  thrown-error shape, rule 4). The conformance test asserts every
  framework thrown message carries the token so a log/CI grep still
  pivots to a stable category from the message alone."
  [message]
  (boolean
    (and (string? message)
         (re-find #"\[:rf\.error/[A-Za-z0-9*+!_.?\-]+\]" message))))

(defn type-of-value
  "Best-effort short tag for a value's type — surfaced inside the
  `:reason` slot of a `:rf.error/schema-validation-failure` (or
  similar) trace event. Returns a stable lowercase string for the
  primitive Clojure shapes; falls back to `(str (type v))` for
  anything else.

  Stable contract — the eight enumerated tags
  (`string` / `integer` / `number` / `boolean` / `keyword` / `map`
  / `vector` / `nil`) are part of the framework's reason-string
  vocabulary. Adding new fast-path tags is additive; renaming an
  existing one breaks consumers' grep-pinned reason-string fixtures."
  [v]
  (cond
    (string? v)  "string"
    (integer? v) "integer"
    (number? v)  "number"
    (boolean? v) "boolean"
    (keyword? v) "keyword"
    (map? v)     "map"
    (vector? v)  "vector"
    (nil? v)     "nil"
    :else        (str (type v))))
