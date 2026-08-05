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

  `safe-form` / `pr-form` are the CYCLE-SAFE printing pair every
  diagnostic that renders a caller-supplied value routes through, so a
  cyclic foreign value cannot turn a good error into a stack overflow.

  Pure; no runtime state. Hot-path safe (the type-tag cond cascade
  allocates nothing on the common branches; the thrown-error builders
  run only on the failure path)."
  ;; CLJS-only: the JVM arm of `safe-form` is `identity`, so `clojure.walk`
  ;; would be an unused namespace there.
  #?(:cljs (:require [clojure.walk :as walk])))

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
  `:rf.<ns>/…` discriminator keyword — the keyword-only message shape the
  contract forbids. A conformant framework message LEADS with a human
  sentence and only carries the keyword inside the trailing
  `[:rf.<ns>/<id>]` token, so a conformant message is never
  `(str some-keyword)` on its own.

  Matches ANY reserved `:rf.<ns>/` root (`:rf.error/…` is the common case,
  but a few error-ids live under another reserved namespace — e.g.
  `:rf.ssr/hydration-mismatch`), so the predicate is uniform across the
  whole thrown-error corpus, not just `:rf.error/…`.

  Used by the thrown-error conformance test to reject any framework throw
  that regresses to a keyword-only human message."
  [message]
  (boolean
    (and (string? message)
         (re-matches #":rf\.[a-z][\w.-]*/[A-Za-z0-9*+!_.?\-]+" message))))

(defn message-has-id-token?
  "Conformance predicate: true when `message` contains the
  trailing `[:rf.<ns>/<id>]` greppability token (Spec 009 §The
  thrown-error shape, rule 4). The conformance test asserts every
  framework thrown message carries the token so a log/CI grep still
  pivots to a stable category from the message alone.

  Matches ANY reserved `:rf.<ns>/` root — most thrown-error ids live under
  `:rf.error/…`, but a few use another reserved namespace
  (`:rf.ssr/hydration-mismatch`), and the token contract is the same for
  all of them."
  [message]
  (boolean
    (and (string? message)
         (re-find #"\[:rf\.[a-z][\w.-]*/[A-Za-z0-9*+!_.?\-]+\]" message))))

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
;; (its type, and the size of a counted collection), never the value
;; itself. `diag-value-summary` is that summary primitive.
;;
;; It is intentionally tiny and self-contained (no `elide-wire-value` /
;; `project-egress` dependency) because the surfaces that need it include
;; bundle-isolated artefacts (reagent-slim) that MUST NOT `:require`
;; re-frame.* — those artefacts replicate this exact shape inline. The
;; summary is the same shape on every surface so a tool consuming a thrown
;; ex-data reads one diagnostic vocabulary.

(defn diag-value-summary
  "EP-0015-safe SUMMARY of a value for a framework diagnostic message or
  ex-data slot (Spec 015 §Data-Classification). Returns a small data map
  describing the value's SHAPE — never the value itself — so the
  diagnostic survives off-box capture without leaking app-owned
  sensitive/large content.

  Shape (`:count` present only for a counted collection or string):

    {:type   :map | :vector | :seq | :set | :keyword | :symbol
             | :string | :number | :boolean | :nil | :fn | :scalar
     :count  <int>}    ;; collection / string element count

  **The summary is content-free BY CONSTRUCTION (rf2-210uq).** Every value
  it can carry is either a member of the closed `:type` vocabulary above or
  an integer count, so no expression here is derived from the input's
  CONTENT. That makes the serialized summary a fixed size whatever arrives
  — there is no prefix to bound and no key set to cap — and it is why a
  caller may hand this function a session token, a password or an
  attacker-chosen key set without further thought.

  It did not always hold. The summary previously carried a `:head` (a raw
  24-char prefix of the printed value, which reproduced a short secret
  WHOLE and a long one's prefix, and which was not bounded at all for
  keywords/symbols) and a map's `:keys` (every top-level key, uncapped and
  unsanitised, though app-controlled keys carry content and an
  attacker-sized key set grew the 'bounded' summary without limit). Both
  are gone. `(str v)` is gone with them, so an unknown host value whose
  `toString` throws no longer destroys the failure being described.

  SIZE IS DELIBERATELY KEPT. `:count` is what makes the summary useful
  rather than merely safe — 'a 4000-char string where a keyword was
  expected' is the diagnosis — and an integer cannot carry a fragment of
  the value. A lazy/unbounded seq is NOT counted: realising it on the
  failure path is its own hazard.

  If a diagnostic needs a structural IDENTIFIER — an fx-id, an arg key, a
  view-id — the caller passes it through its own explicitly trusted
  ex-data slot (as `re-frame.ssr.response` does with `:fx-id` / `:key`).
  This function will not guess that an arbitrary value is structural."
  [v]
  (cond
    (nil? v)     {:type :nil}
    (map? v)     {:type :map :count (count v)}
    (vector? v)  {:type :vector :count (count v)}
    (set? v)     {:type :set :count (count v)}
    (string? v)  {:type :string :count (count v)}
    (keyword? v) {:type :keyword}
    (symbol? v)  {:type :symbol}
    (boolean? v) {:type :boolean}
    (number? v)  {:type :number}
    (seq? v)     {:type :seq}
    (fn? v)      {:type :fn}
    (seqable? v) {:type :seq}
    :else        {:type :scalar}))

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

;; ---- cycle-safe diagnostic printing --------------------------------------
;;
;; A validator that rejects a malformed form correctly but EXPLODES while
;; explaining why is still broken on hostile input (rf2-9s68n). Diagnostics
;; across the framework build their messages with `(pr-str v)` over the
;; offending runtime value, and on ClojureScript that is a live
;; stack-overflow: `cljs.core`'s printer has exactly TWO branches that
;; descend into a foreign JS value — `object?` (prints `#js {…}` by walking
;; `js-keys`) and `array?` (prints `#js […]` by walking elements,
;; `cljs/core.cljs` `pr-writer-impl`) — and NEITHER carries a seen-set.
;; Every other print form a foreign value can take (`#inst`, `#"re"`,
;; `#object[Ctor]`, `#object[Symbol(x)]`) is bounded and recurses into
;; nothing.
;;
;; So an author who writes `[ThemeContext.Provider {…}]` on the hiccup tier
;; — the ordinary mistake `:rf.error/invalid-hiccup-head` exists to catch,
;; and one reachable straight from the shipped `re-frame.ssr/render-to-
;; string` — got `RangeError: Maximum call stack size exceeded` and NO
;; message at all. React 19's `createContext` returns an object carrying a
;; `Provider` key that points back at the context itself, so `ctx.Provider`
;; IS a cycle; the defect is a property of the foreign object graph rather
;; than of React, and a hand-built `(unchecked-set o "self" o)` reproduces
;; it exactly.
;;
;; `re-frame.ssr.hash/canonical-edn-into` met the same crossing from the
;; other side (rf2-56iys) and STOPS at those two predicates: a hash wants
;; one identity-free token, so collapsing every foreign value is right
;; there. A DIAGNOSTIC wants the opposite — it exists to show the author
;; their value — so this elides the strict minimum instead:
;;
;;   **A foreign JS value is replaced only when a cycle is REACHABLE from
;;   it. Everything else, foreign or not, is handed to `pr-str` untouched.**
;;
;; Which is what makes the guarantee hold by construction rather than by
;; inspection:
;;
;;   **An input from which no cycle is reachable is returned IDENTICAL, so
;;   its diagnostic is byte-identical to the one it produced before this
;;   pair existed.** `safe-form` scans first and only rebuilds when the scan
;;   finds a cycle; on the JVM it is `identity` outright (no foreign
;;   objects, and no cyclic value can be built from the persistent
;;   collections these walks otherwise see).
;;
;; THE VALUES THIS PROTECTS, AND WHERE THEY GO. `pr-form` is for the message
;; string; `safe-form` for the `:extra` ex-data payload. BOTH matter: a
;; cyclic value that merely rides out in `{:extra {:value v}}` explodes at a
;; DOWNSTREAM logger / error projector / trace sink that `pr-str`s the
;; ex-data, i.e. at someone else's boundary rather than at the thrower's,
;; which is strictly worse to diagnose.
;;
;; IT LIVES IN CORE BECAUSE ITS CONSUMERS ARE SIBLINGS. `re-frame.ssr` and
;; `re-frame.ui` are sibling artefacts — both depend on core, neither may
;; `:require` the other — so a helper serving both can only live beneath
;; them. Every consumer already `:require`s this namespace, so the shared
;; home costs no new dependency edge anywhere, and it sits beside
;; `diag-value-summary`, the other primitive answering "how do I render an
;; untrusted value into a diagnostic without harm?" (that one for content
;; safety, this one for termination).
;;
;; rf2-y1jbaq (never stringify an unescaped hiccup form to the wire): this
;; does NOT widen the wire surface. The elision token is a fixed constant
;; carrying no input-derived text, so the only content it can add to any
;; surface is content an ACYCLIC form of the same shape already put there.

#?(:cljs
   (deftype ElidedForeign [token]
     Object
     (toString [_] token)
     IPrintWithWriter
     (-pr-writer [_ writer _opts] (-write writer token))))

#?(:cljs
   (def ^:private elided-object
     (ElidedForeign. "#js {…cyclic…}")))

#?(:cljs
   (def ^:private elided-array
     (ElidedForeign. "#js […cyclic…]")))

#?(:cljs
   (defn- descends?
     "Would `cljs.core`'s printer DESCEND into `x`? Exactly the two
     branches of `pr-writer-impl` that do: `object?` (own enumerable keys,
     via `js-keys`) and `array?` (elements). Deliberately the same pair
     `re-frame.ssr.hash/canonical-edn-into` intercepts — widening it would
     make the two walks disagree about what `pr-str` can survive."
     [x]
     (or (object? x) (array? x))))

#?(:cljs
   (defn- reaches-self?
     "Depth-first over everything `cljs.core`'s printer descends into,
     carrying the FOREIGN ancestors on the CURRENT path. True as soon as a
     value already on that path is reached again — which is precisely the
     condition under which `pr-str` would not terminate.

     IT DESCENDS PERSISTENT COLLECTIONS TOO, because the printer does: the
     `object?` branch hands each value to `pr-writer`, which recurses into a
     vector or map like any other, so a cycle can be reached by a MIXED
     chain — `#js {\"held\" [:p ctx.Provider]}`. A walk that stopped at the
     first persistent collection would call that value acyclic and hand
     `pr-str` the overflow anyway. Only foreign values go ON the path: a
     cycle cannot be built out of persistent collections, so nothing else
     can close one.

     Path-scoped, not global: a value reachable twice by DIFFERENT paths (a
     shared subtree) is not a cycle, and `pr-str` prints it twice and
     terminates. Cost tracks `pr-str`'s own — it visits the same graph the
     printer would — so a graph this is slow on is one `pr-str` was already
     slow on. Failure path only."
     [x path]
     (cond
       (descends? x)
       (or (boolean (some #(identical? % x) path))
           (let [path (conj path x)]
             (boolean
               (if (array? x)
                 (some #(reaches-self? % path) (array-seq x))
                 (some #(reaches-self? (unchecked-get x %) path) (js-keys x))))))

       (coll? x)
       (boolean (some #(reaches-self? % path) (seq x)))

       :else false)))

#?(:cljs
   (defn- cyclic-foreign?
     [x]
     (and (descends? x) (reaches-self? x []))))

#?(:cljs
   (defn- elide-cyclic
     "Leaf rewrite: a foreign value from which a cycle is reachable becomes
     the fixed token; everything else — including an ACYCLIC foreign value,
     which `pr-str` renders fully and terminates on — is returned as-is."
     [x]
     (if (cyclic-foreign? x)
       (if (array? x) elided-array elided-object)
       x)))

#?(:cljs
   (defn- reaches-cycle?
     "Is there a foreign cycle anywhere in `form`? One `reaches-self?` from
     the root covers it, because that walk already crosses freely between
     persistent collections and foreign values.

     This scan is what buys the byte-identity guarantee: when it says no,
     `safe-form` returns the input object itself and cannot have perturbed
     anything."
     [form]
     (reaches-self? form [])))

(defn safe-form
  "`form` with every foreign JS value FROM WHICH A CYCLE IS REACHABLE
  replaced by a fixed, identity-free, self-describing token
  (`#js {…cyclic…}` / `#js […cyclic…]`), so the result is safe to `pr-str`
  — by the caller building a message, and by any downstream consumer that
  `pr-str`s it out of `:extra` ex-data.

  Returns `form` ITSELF when no cycle is reachable (always, on the JVM)."
  [form]
  #?(:clj  form
     :cljs (if (reaches-cycle? form)
             (walk/postwalk elide-cyclic form)
             form)))

(defn pr-form
  "`(pr-str form)`, made total: byte-identical to `pr-str` for every form
  `pr-str` terminates on, and a bounded elision instead of `RangeError:
  Maximum call stack size exceeded` for the ones it does not."
  [form]
  (pr-str (safe-form form)))
