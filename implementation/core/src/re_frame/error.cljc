(ns re-frame.error
  "Shared error-message helpers — the central thrown-error builder, plus
  short tag fns and reason-string primitives used by error / warning
  trace emit sites across core and the per-feature artefacts.

  `thrown-ex-info` / `throw-error!` are the canonical thrown-error
  builders (Spec 009 §The thrown-error shape). Every `(throw (ex-info …))`
  site in the runtime routes through them so the human message and the
  `:rf.error/id` machine discriminator cannot drift: the builder DERIVES
  the message from `:reason` + the `[:rf.error/<id>]` greppability token
  and sets the canonical ex-data shape. Keyword-only messages are
  structurally impossible by construction.

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

;; ---- the canonical thrown-error builder ----------------------------------
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
;;      log/CI greppability, so a log line or raw stack trace still
;;      pivots to a stable category from the message alone.
;;   5. `:reason` IS that required human sentence; there is no separate
;;      `:rf.error/message` slot (it would duplicate `:reason`).
;;
;; The builder derives the message FROM `:reason` + the token, so the
;; message and `:rf.error/id` cannot drift, and a keyword-only message is
;; impossible to emit. Every framework `(throw (ex-info …))` site routes
;; through here.

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
  shape). The SINGLE chokepoint every framework
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
  `ex-info` in one call (Spec 009 §The thrown-error shape).
  Same args as `thrown-ex-info`. Never returns normally."
  ([error-id where-sym reason] (throw-error! error-id where-sym reason nil))
  ([error-id where-sym reason opts]
   (throw (thrown-ex-info error-id where-sym reason opts))))

;; ---- nil-safe thrown-value message extractor -----------------------------
;;
;; The runtime catches thrown values at ~7 sites (the router pipeline
;; exception, the cofx supplier throw, the fx-handler throw, the reactive /
;; compute sub throws, the interceptor-registry arg-resolve throw) and stamps
;; the host message into the `:exception-message` trace / error-record slot.
;; A raw `#?(:clj (.getMessage e) :cljs (.-message e))` is unsafe in CLJS:
;; ANY value is legally throwable, and a thrown NON-Error value (a keyword, a
;; map, a string — `(throw :boom)` / `(throw {:k 1})`) has no `.-message`
;; property, so `(.-message e)` reads `nil` and `:exception-message` silently
;; becomes nil — the off-box shipper then sees an error with no message at all.
;;
;; This is the ONE nil-safe `ex-message`-equivalent the catch sites route
;; through, so a non-Error throwable degrades to a useful rendering instead of
;; nil. (`error.cljc` centralises message BUILDING in the thrown-error builders
;; above, and message EXTRACTION here.) Pure; runs only on the failure path.

(defn ex-message-safe
  "Nil-safe extractor for the human message of a CAUGHT thrown value `e`.
  Returns a string (or nil only when `e` itself is nil).

  - For a host exception (JVM `Throwable`, CLJS `js/Error` / `ExceptionInfo`)
    returns its message — `(.getMessage e)` on the JVM, `(ex-message e)` /
    `(.-message e)` on CLJS.
  - For a thrown NON-Error value (legal in CLJS: `(throw :boom)`,
    `(throw {:k 1})`, `(throw \"oops\")`) the host message is nil, so this
    falls back to `(str e)` — the value's printed form — rather than letting
    `:exception-message` silently become nil. KEYWORDS / strings / numbers
    render to a recognisable token; a map / collection renders structurally.
  - Returns nil ONLY for a nil `e` (there is genuinely no message).

  A raw `(.-message e)` is unsafe on CLJS because the property is absent on
  a plain value, so it reads `nil` with no error — the worst failure mode for
  a diagnostic. This degrades loudly-but-safely instead. Pure; hot-path safe
  (runs only on the catch path)."
  [e]
  (cond
    (nil? e) nil
    #?@(:clj  [(instance? Throwable e) (or (.getMessage ^Throwable e) (str e))]
        :cljs [(instance? js/Error e)  (or (.-message e) (str e))])
    :else
    ;; A thrown non-Error value (CLJS-legal). The host message is nil; render
    ;; the value so the diagnostic carries SOMETHING rather than nil.
    (str e)))

;; ---- shared removed-API thrower: inline interceptor -----------------------
;;
;; The "throwing stub" pattern (a removed public API survives as a `^:no-doc`
;; var that throws a LOUD, actionable `:rf.error/<x>-removed` naming the
;; replacement) is described per surface in DATA TABLES
;; (`events/removed-reg-event-names`, `std-interceptors/removed-std-
;; interceptor-values`) that collapse each surface's rows to one literal
;; vector. The helper below is the ONE shared definition both inline-interceptor
;; rejection sites delegate to, so the throw mechanics live once: each caller
;; passes its exact `where` / `reason` / `extra`, so the thrown ex-info shape
;; is the caller's own. (The always-on FAN-OUT removed-stub variant — the
;; `inject-cofx` + reg-event removals — shares `cofx/raise-removed!` instead,
;; because that fan-out needs `late-bind` + `trace`, both of which require
;; `error`; housing it here would close a load cycle.)

(defn throw-inline-interceptor-removed!
  "Throw the hard error `:rf.error/inline-interceptor-removed` for an INLINE
  interceptor value found where a chain expects a REFERENCE (interceptor chains
  are reference-only, EP-0022 §Event and frame chain grammar). The ONE
  definition both rejection sites delegate to — the registration-time site
  (`re-frame.events/validate-meta-interceptors!`, `:where 'rf/reg-event`) and
  the chain-assembly site (`re-frame.interceptor-registry/resolve-chain`,
  `:where 'rf/resolve-chain`). Each passes its own `where` symbol, fully
  composed `reason` sentence, and `extra` ex-data map, so the thrown shape is
  the caller's own; only the throw mechanics are shared. The fix the message
  names: register the interceptor with `reg-interceptor` and reference it by id
  (a bare keyword or an `[id arg]` 2-vector). Never returns normally."
  [where reason extra]
  (throw-error!
    :rf.error/inline-interceptor-removed
    where
    reason
    {:recovery :fix-registration
     :extra    extra}))

(defn ex-info-from-data
  "Build a canonical thrown-error `ex-info` whose ex-data is the ALREADY-BUILT
  `data` map verbatim, deriving the human message from the map's own
  `:rf.error/id` + `:reason` slots (Spec 009 §The thrown-error shape).

  The companion to `thrown-ex-info` for the sites that build a SHARED ex-data
  payload up front — reused by both a throw and a trace-emit / final-boundary
  emit (e.g. `re-frame.frame`'s `no-frame-context-payload`,
  `re-frame.events`'s `legacy-runtime-root-ex-data`) — so the two surfaces
  carry an IDENTICAL map. Those sites cannot route the BUILD through
  `thrown-ex-info` without forking the shared payload; this helper lets them
  keep the one payload and still emit the human message in the message
  position rather than a bare `(str (:rf.error/id data))` keyword.

  `data` MUST carry `:rf.error/id` and SHOULD carry `:reason`; the message is
  `(human-message (:rf.error/id data) (:reason data))`. ex-data is `data`
  unchanged."
  [data]
  (ex-info (human-message (:rf.error/id data) (:reason data)) data))

(defn keyword-only-message?
  "Conformance predicate: true when `message` is a bare stringified
  `:rf.error/…` keyword — the keyword-only message shape the contract
  forbids. A conformant framework message LEADS with a human sentence and
  only carries the keyword inside the trailing `[:rf.error/<id>]` token, so a
  conformant message is never `(str some-keyword)` on its own.

  Used by the thrown-error conformance test to reject any framework throw
  that regresses to a keyword-only human message."
  [message]
  (boolean
    (and (string? message)
         (re-matches #":rf\.error/[A-Za-z0-9*+!_.?\-]+" message))))

(defn message-has-id-token?
  "Conformance predicate: true when `message` contains the
  trailing `[:rf.error/<id>]` greppability token (Spec 009 §The
  thrown-error shape, rule 4). The conformance test asserts every
  framework thrown message carries the token so a log/CI grep still
  pivots to a stable category from the message alone."
  [message]
  (boolean
    (and (string? message)
         (re-find #"\[:rf\.error/[A-Za-z0-9*+!_.?\-]+\]" message))))

;; ---- EP-0015-safe diagnostic value summary -------------------------------
;;
;; Spec 015 §Data-Classification rules that raw application values MUST NOT
;; be baked into framework exception messages or ex-data: once a value is
;; flattened into a string message or stuffed into ex-data, it is captured
;; by browser consoles, error boundaries, host logs, SSR/static-export
;; error handlers, and production observability BEFORE the record projector
;; (`project-egress`) can classify the original paths — path-based
;; projection cannot recover a value that no longer sits at a path. So
;; adapter/diagnostic surfaces carry a SUMMARY of the offending value
;; (shape / type / count / keys / bounded head), never the value itself.
;; `diag-value-summary` is that summary primitive.
;;
;; It is intentionally tiny and self-contained (no `elide-wire-value` /
;; `project-egress` dependency) because the surfaces that need it include
;; bundle-isolated artefacts (reagent-slim) that MUST NOT `:require`
;; re-frame.* — those artefacts replicate this exact shape inline. The
;; summary is the same shape on every surface so a tool consuming a thrown
;; ex-data reads one diagnostic vocabulary.

(def ^:private diag-head-limit
  "Max chars of a leaf scalar's printed form to retain in a diagnostic
  head. Bounded so a large/secret scalar never rides whole into ex-data."
  24)

(defn- diag-head
  "A bounded, content-light head string for a scalar leaf — enough to
  recognise the value's flavour in a diagnostic without carrying the whole
  value off-box. Keywords/symbols keep their name (structural, not user
  content); strings/numbers/other scalars are truncated to
  `diag-head-limit` chars with an ellipsis marker."
  [v]
  (cond
    (keyword? v) (str v)
    (symbol? v)  (str v)
    :else
    (let [s (str v)]
      (if (> (count s) diag-head-limit)
        (str (subs s 0 diag-head-limit) "…")
        s))))

(defn diag-value-summary
  "EP-0015-safe SUMMARY of a value for a framework diagnostic message or
  ex-data slot (Spec 015 §Data-Classification). Returns a
  small data map describing the value's SHAPE — never the value itself —
  so the diagnostic survives off-box capture without leaking app-owned
  sensitive/large content.

  Shape (keys present only when meaningful):

    {:type   :map | :vector | :seq | :set | :keyword | :symbol
             | :string | :number | :boolean | :nil | :fn | :scalar
     :count  <int>     ;; collection / string element count
     :keys   [...]     ;; sorted top-level map keys (KEYS are structural)
     :head   \"…\"}     ;; bounded recognition head for a scalar leaf

  Map keys are treated as structural (they are part of the shape the
  caller declares, not free user content) and are retained sorted; map
  VALUES are never included. A scalar carries a bounded `:head`; a
  collection carries `:count`. The whole value is never reproduced."
  [v]
  (cond
    (nil? v)     {:type :nil}
    (map? v)     {:type  :map
                  :count (count v)
                  :keys  (try (vec (sort-by str (keys v)))
                              (catch #?(:clj Throwable :cljs :default) _
                                (vec (keys v))))}
    (vector? v)  {:type :vector :count (count v)}
    (set? v)     {:type :set :count (count v)}
    (string? v)  {:type :string :count (count v) :head (diag-head v)}
    (keyword? v) {:type :keyword :head (diag-head v)}
    (symbol? v)  {:type :symbol :head (diag-head v)}
    (boolean? v) {:type :boolean :head (str v)}
    (number? v)  {:type :number :head (diag-head v)}
    (seq? v)     {:type :seq}
    (fn? v)      {:type :fn}
    (seqable? v) {:type :seq}
    :else        {:type :scalar :head (diag-head v)}))

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
