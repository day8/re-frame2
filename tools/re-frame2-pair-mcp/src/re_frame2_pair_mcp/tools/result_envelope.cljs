(ns re-frame2-pair-mcp.tools.result-envelope
  "Total, TAGGED result codec across the MCP↔runtime boundary.

  ## What this codec guarantees

  The CLJS→wire value path carries a total, tagged envelope so distinct
  outcomes stay distinct instead of collapsing to a bare `null` (or a
  stringly `:unexpected-shape`), which would cost round-trips and produce
  wrong conclusions. Without the tag:

    - `eval-cljs` would return `null` for (a) a genuine `nil`, (b) an
      UNRESOLVED symbol / wrong-ns guess (which looks identical to nil),
      and (c) an UNSERIALIZABLE value — a value `pr-str` can render but
      `cljs.reader/read-string` cannot read back (a raw `#object[...]`,
      a `#js {...}` literal, a Function ref). The blank-value path in
      `nrepl/cljs-eval-value` reads all three as `nil`.
    - `handler-meta` would return `:ok? false :reason :unexpected-shape`
      while the real meta sat in `:value` as a STRING the caller had to
      re-parse.

  The project's wire-SIZE machinery — elision, dedup, the
  token-budget cap (`re-frame.mcp-base.*`, `tools.elision`,
  `tools.dedup`, `tools.cap`) — handles payload size; this namespace is
  the wire-FIDELITY/typing layer and composes ON TOP of elision:
  the runtime-side wrap classifies the value, and the elision walker (if
  the caller routes through it) still runs over the `:value` payload —
  this codec never bypasses size machinery, it only tags the outcome.

  ## Where the discrimination happens

  Serializability and thrown-exception detection both require the LIVE
  value, which exists only in the browser runtime — not in the MCP
  server's host process where the value arrives as already-`pr-str`'d
  text. So the classification is a RUNTIME-SIDE wrap (`wrap-form` below)
  in the same vein as `await-promise/wrap-form`: the user form is
  evaluated inside a `try`, the result is round-trip-probed
  (`pr-str` → `read-string`), and a tagged envelope is `pr-str`'d back.
  The server then reads that envelope and renders the typed wire shape
  (`envelope->result`).

  The runtime preflight (`probe/resolve-and-preflight!`) already
  guarantees a live runtime BEFORE the eval, so a blank value from the
  wrap can only mean the wrap itself failed to serialize — never \"no
  runtime\" (that path fails loud upstream with `:no-runtime-for-build`).

  ## Envelope grammar (the wire contract)

  The tagged map rides under the cross-MCP `:rf.mcp/result` key (per
  Conventions §Reserved namespaces — `:rf.mcp/*` is the MCP-server wire
  vocabulary). Exactly one tag per result:

    {:rf.mcp/result :value :value <v>}
        — a genuine value that round-trips as EDN.

    {:rf.mcp/result :nil}
        — the form genuinely returned `nil`. Distinct from `:value nil`
          (which never occurs — a nil value is tagged `:nil`) and from
          the `:unserializable` / `:eval-error` cases.

    {:rf.mcp/result :eval-error :reason <kw> :ex <str> [:via <vec>]}
        — the form threw (or a compile/resolve warning surfaced as a
          throw). `:ex` is the `pr-str` / message of the throwable;
          `:via` carries the ex-chain class names when available.

    {:rf.mcp/result :unserializable :type <str> :preview <str>}
        — the value `pr-str`'d to text that `read-string` cannot read
          back (a `#object[...]`, a `#js {...}`, a Function). `:type`
          is the JS `goog/typeOf` / constructor name; `:preview` is the
          (truncated) `pr-str` text so the agent sees WHAT it was.

  ## Server-side projection (`envelope->result`)

  The server reads the tagged envelope and projects it onto the
  tool's own `:ok?` vocabulary so each tool keeps its result shape:

    :value          → `(on-value v)` (the tool decides — `eval-cljs`
                       puts it under `:value`; `handler-meta` merges the
                       parsed meta map).
    :nil            → `(on-value nil)` — the tool sees a genuine nil.
    :eval-error     → `{:ok? false :reason :rf.error/eval-cljs-threw …}`.
    :unserializable → `{:ok? false :reason :rf.error/unserializable …}`.

  Untagged values (a runtime without the wrap preloaded, or a
  tool that doesn't wrap) flow through `envelope->result` unchanged via
  the fall-through arm — the codec is additive: an untagged value is a
  valid input and projects straight to `(on-value v)`."
  (:require [clojure.string :as str]
            [re-frame.mcp-base.vocab :as base-vocab]))

;; ---------------------------------------------------------------------------
;; Wire vocabulary.
;; ---------------------------------------------------------------------------

(def result-key
  "Top-level discriminator key for a tagged result envelope. Reserved
  under the cross-MCP `:rf.mcp/*` namespace (Conventions §Reserved
  namespaces); single-sourced from `re-frame.mcp-base.vocab/result-key`."
  base-vocab/result-key)

(def preview-cap
  "Max chars of an unserializable value's `pr-str` text to carry on the
  wire as `:preview`. Long enough to identify the shape, short enough to
  stay well under the token cap on its own.

  Public so the test-only `test-utils/truncate-preview`
  pins against the SAME single-sourced cap the runtime wrap embeds."
  240)

;; ---------------------------------------------------------------------------
;; REPL-special pass-through.
;;
;; shadow-cljs's `cljs-eval` compiles a handful of forms as REPL SPECIALS
;; — `require`, `require-macros`, `import`, `use`, `ns`, `in-ns`, `refer`,
;; `refer-clojure`, `load`, `load-file`, `load-namespace`. These only get
;; their special handling (e.g. `require`'s async module-load machinery)
;; at the TOP LEVEL of the eval'd form. The moment one is buried inside an
;; expression context — `(try (let [...] (do (require ...))))`, exactly
;; what `wrap-form` builds — shadow emits a top-level `;`-terminated
;; statement into an expression slot and the browser REPL throws
;; `SyntaxError: Unexpected token ';'` → `:repl/exception!`. The require's
;; module never loads, and a follow-on form referencing the namespace dies
;; with `Cannot read properties of undefined`.
;;
;; The codec can't classify a module-load side effect anyway — `require`
;; returns nil — so wrapping buys nothing here. `wrap-form` detects a
;; leading REPL-special and returns the form VERBATIM so shadow gives it
;; the top-level handling it needs; the untagged nil result then flows
;; through `envelope->result`'s passthrough arm as `(on-value nil)`,
;; i.e. `:ok? true :value nil` — the correct shape for a require.
;; ---------------------------------------------------------------------------

(def ^:private repl-special-heads
  "Leading symbols shadow-cljs treats as REPL specials needing top-level
  compilation. Wrapping any of these breaks them (see the comment above)."
  #{"require" "require-macros" "import" "use" "ns" "in-ns"
    "refer" "refer-clojure" "load" "load-file" "load-namespace"})

(defn repl-special?
  "True when `form-str` is a top-level call to a shadow REPL-special op
  that must NOT be wrapped. Cheap textual probe: strip leading
  whitespace + a single opening paren, then read the first token. A
  `(require ...)` / `(ns ...)` / etc. matches; `(+ 1 2)`, `(require-foo)`
  (not an exact match), and a bare value do not. Quote-prefixed forms
  (`'(require ...)`) are data, not a special invocation, and correctly do
  NOT match."
  [form-str]
  (when (string? form-str)
    (let [t (str/triml form-str)]
      (when (str/starts-with? t "(")
        (let [after (str/triml (subs t 1))
              ;; first whitespace- or paren- or close-delimited token
              head  (re-find #"^[^\s()\[\]{}]+" after)]
          (boolean (contains? repl-special-heads head)))))))

;; ---------------------------------------------------------------------------
;; Runtime-side wrap — classify the eval result into a tagged envelope.
;;
;; Mirrors `await-promise/wrap-form`: the user form is wrapped in CLJS
;; source that the runtime evaluates. The wrap returns a CLJS map that
;; `pr-str` renders as the tagged envelope, which the server reads back
;; via `nrepl/cljs-eval-value` and projects with `envelope->result`.
;; ---------------------------------------------------------------------------

(defn wrap-form
  "Wrap `form-str` so the runtime classifies its result into a tagged
  `:rf.mcp/result` envelope. The wrap:

    1. Evaluates the user form inside a `try`. A throw → the
       `:eval-error` tag carrying the message + ex-data when present
       (so a resolve/compile error that surfaces at eval time, or any
       runtime throw, rides back as data instead of an opaque nREPL
       `:ex` transport reject).
    2. On a value, round-trip-probes it: `pr-str` then
       `cljs.reader/read-string`. A value whose text reads back equal-
       enough is tagged `:value` (or `:nil` for a genuine nil); a value
       whose text fails to read back, or contains an unreadable token
       (`#object`, `#js`), is tagged `:unserializable` with a truncated
       `:preview` of the text + the JS `goog/typeOf` name.

  The probe is conservative: any throw from the round-trip read, OR a
  rendered text starting with an unreadable reader-macro token, counts
  as unserializable. This keeps a `#object[Function]` / `#js {…}` from
  riding back as a bare nil — it rides back tagged `:unserializable`
  instead, so the agent sees what it actually was.

  The whole wrap is itself wrapped in an outer `try` so a failure in the
  classification machinery degrades to an `:eval-error` envelope rather
  than throwing on the wire.

  REPL-special forms (`require` / `ns` / `import` / …) are returned
  VERBATIM — shadow only honours their special compilation at
  the top level, and burying them in the wrapper's expression context
  breaks them (`SyntaxError: Unexpected token ';'`). Their untagged result
  flows through `envelope->result`'s passthrough."
  [form-str]
  (if (repl-special? form-str)
    form-str
    (str
    "(try"
    "  (let [result# (try {:rf2pair/ok (do " form-str ")}"
    "                     (catch :default e# {:rf2pair/threw e#}))]"
    "    (if (contains? result# :rf2pair/threw)"
    "      (let [e# (:rf2pair/threw result#)]"
    "        {" (pr-str result-key) " :eval-error"
    "         :reason :rf.error/eval-cljs-threw"
    "         :ex (cljs.core/pr-str e#)"
    "         :message (try (.-message e#) (catch :default _# nil))"
    "         :ex-data (try (cljs.core/ex-data e#) (catch :default _# nil))})"
    "      (let [v# (:rf2pair/ok result#)]"
    "        (if (nil? v#)"
    "          {" (pr-str result-key) " :nil}"
    "          (let [text#  (cljs.core/pr-str v#)"
    "                round# (try {:rf2pair/read (cljs.reader/read-string text#)}"
    "                            (catch :default _# nil))"
    "                unreadable-token?#"
    "                (let [t# (clojure.string/triml text#)]"
    "                  (or (clojure.string/starts-with? t# \"#object\")"
    "                      (clojure.string/starts-with? t# \"#js\")"
    "                      (clojure.string/starts-with? t# \"#<\")))]"
    "            (if (and round# (not unreadable-token?#))"
    "              {" (pr-str result-key) " :value :value v#}"
    "              {" (pr-str result-key) " :unserializable"
    "               :type (goog/typeOf v#)"
    "               :preview (let [n# (count text#)]"
    "                          (if (> n# " preview-cap ")"
    "                            (str (subs text# 0 " preview-cap ") \" …(\" n# \" chars)\")"
    "                            text#))}))))))"
    "  (catch :default e#"
    "    {" (pr-str result-key) " :eval-error"
    "     :reason :rf.error/result-envelope-wrap-failed"
    "     :ex (cljs.core/pr-str e#)}))")))

;; ---------------------------------------------------------------------------
;; Server-side projection — tagged envelope → tool result.
;; ---------------------------------------------------------------------------

(defn tagged?
  "True iff `v` is a tagged `:rf.mcp/result` envelope produced by
  `wrap-form`. Used by tools to decide whether to project via
  `envelope->result` or fall back to the legacy untagged path."
  [v]
  (and (map? v) (contains? v result-key)))

(defn envelope->result
  "Project a tagged `:rf.mcp/result` envelope (or an untagged
  value) onto the calling tool's result map.

  `on-value` is the tool's own success shaper — a 1-arity fn
  `(fn [genuine-value] => result-map)`. It is invoked for BOTH the
  `:value` and `:nil` tags (the tool sees the genuine CLJS value, nil
  included). The error tags are projected to a structured `:ok? false`
  map the tool surfaces verbatim:

    :eval-error     → {:ok? false :reason <the envelope's :reason>
                       :ex … :message … :ex-data …}
    :unserializable → {:ok? false :reason :rf.error/unserializable
                       :type … :preview … :hint …}

  An untagged `v` (a runtime without the wrap, or a tool that didn't
  wrap) flows straight to `(on-value v)` — the codec is additive.

  Returns the tool result map (NOT a wire envelope — the caller wraps
  it with `wire/ok-text` / `wire/err-text`). `error?` on the returned
  map tells the caller which wire wrapper to use — it is true ONLY for
  the codec's OWN error tags (`:eval-error` / `:unserializable` /
  unknown-tag), NOT for an `on-value` result that happens to carry
  `:ok? false` (e.g. a `handler-meta` `:not-registered` miss — a
  legitimate answer, not a transport error). The discriminator is
  Clojure metadata (`::codec-error`), which `pr-str` drops so it never
  rides the wire."
  [v on-value]
  (if-not (tagged? v)
    (on-value v)
    (case (get v result-key)
      :value (on-value (:value v))
      :nil   (on-value nil)

      :eval-error
      (with-meta
        (cond-> {:ok?    false
                 :reason (or (:reason v) :rf.error/eval-cljs-threw)
                 :ex     (:ex v)}
          (some? (:message v)) (assoc :message (:message v))
          (some? (:ex-data v)) (assoc :ex-data (:ex-data v)))
        {::codec-error true})

      :unserializable
      (with-meta
        {:ok?     false
         :reason  :rf.error/unserializable
         :type    (:type v)
         :preview (:preview v)
         :hint    (str "the form returned a value that pr-str renders but "
                       "EDN cannot read back (a #object / #js / Function). "
                       "Project it to data inside the form — e.g. "
                       "(js->clj v :keywordize-keys true), (.-foo v), or "
                       "(into {} v) — before returning.")}
        {::codec-error true})

      ;; Unknown tag — shouldn't happen against this codec, but degrade
      ;; to surfacing the raw envelope rather than swallowing it.
      (with-meta
        {:ok?    false
         :reason :rf.error/unknown-result-tag
         :tag    (get v result-key)
         :raw    v}
        {::codec-error true}))))

(defn error?
  "True iff `result-map` is a codec-produced ERROR result (an
  `:eval-error` / `:unserializable` / unknown-tag projection). Lets the
  caller pick `wire/err-text` vs `wire/ok-text` without re-inspecting
  the tag. Reads the `::codec-error` metadata stamped by
  `envelope->result` — so an `on-value` result carrying `:ok? false`
  (a legitimate structured answer like `:not-registered`) is NOT an
  error here and rides back as `wire/ok-text`."
  [result-map]
  (boolean (some-> result-map meta ::codec-error)))

;; `truncate-preview` (the test-only preview-shape helper) lives in
;; `re-frame2-pair-mcp.test-utils/truncate-preview` — the
;; MCP server never calls it; only tests assert the preview contract.
;; It pins against the same `preview-cap` constant above (public so
;; the single source of truth stays here).
