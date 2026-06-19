(ns re-frame.source-coords
  "Compile-time source-coordinate capture for registration macros.
  Per Spec 001 §Source-coordinate capture (CLJS reference) and
  Tool-Pair §Source-mapping.

  Every registration's metadata carries `:ns` / `:line` / `:file`
  auto-supplied at compile time. Tools (re-frame-pair, re-frame-10x,
  IDE jump-to-source) consume these via `(rf/handler-meta kind id)`.

  The capture mechanism:

    1. Each public reg-* macro at the re-frame.core boundary captures
       :line / :column from `(meta &form)` and :ns / :file from the
       compile-time environment, builds a `coords` literal map, and
       binds `*pending-coords*` around the underlying registration
       fn call.
    2. The registration fn merges *pending-coords* into the metadata
       it stores in the registrar slot. User-supplied :ns / :line /
       :file override the auto-captured values (so tooling that
       synthesises registrations from another source can stamp the
       original coordinates).

  ## Production elision (rf2-3un2g)

  Source-coord capture has TWO sinks:

    1. **Public registry-meta**: in dev the captured coords are merged
       into the registrar slot's metadata via [[merge-coords]] —
       `(rf/handler-meta kind id)` consumers (Xray Open-in-editor,
       re-frame-pair, IDE jump-to-source) read them from there. In
       CLJS production (`:advanced` + `goog.DEBUG=false`) [[merge-coords]]
       returns `user-meta` unchanged — the coord keys are stripped from
       the public meta. The `:column` literal in the macro-emitted
       coords-form additionally DCEs (the slim prod coords-form omits
       `:column` entirely).

    2. **Always-on error-coord registry**: [[remember-error-coords!]]
       populates [[error-coords-by-id]] at registration time. The
       error-emit substrate (`re-frame.error-emit/dispatch-on-error!`)
       looks up coords via [[error-coords-for]] when assembling the
       tight error-record and the structured policy-event — so
       Sentry/Honeybadger/Rollbar shippers still see source-line info
       in production builds where the trace surface is gone. This
       channel survives `goog.DEBUG=false` by construction.

  The DOM-annotation hook (per Tool-Pair §Source-mapping) is the dev-only
  piece, gated separately."
  (:require [clojure.string :as str]
            [re-frame.interop :as interop]
            [re-frame.source-coords.editor-uri :as editor-uri]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:dynamic *pending-coords*
  "Per-thread (per-call) source coords captured by a reg-* macro and
  consumed by the underlying registration fn. nil outside a macro
  invocation.

  Shape: `{:ns sym :line int :file string :column int}` — see Spec 001
  §The metadata map. :ns / :line / :file are the locked keys; :column
  is an optional refinement. All keys are present when a macro
  captured the call site; nil otherwise (programmatic / REPL
  registrations that bypass the macro path)."
  nil)

(def ^:dynamic *pending-form-source*
  "Per-thread (per-call) handler form-source captured by `reg-event-{db,
  fx,ctx}` macros and consumed by `re-frame.events/register-event!`. nil
  outside a macro invocation.

  Per Spec 009 §`:rf.handler/source` and Xray Spec 021 §11.2 B.7
  stretch: the macros stamp the whole `(reg-event-X :id ...)` form as
  a string into the handler's registry metadata under
  `:rf.handler/source` so Xray's Event panel can render the source
  inline (no need to leave the browser to read what code ran).

  CLJS production elision: the macro emission wraps the binding-value
  in an `(if interop/debug-enabled? <source-string> nil)` gate so
  Closure constant-folds the gate to `nil` under `:advanced` +
  `goog.DEBUG=false` and DCEs both the literal source string and the
  `:rf.handler/source` keyword's reachability from this slot. The
  elision probe asserts the absence; per-string DCE depends on no
  other surface keeping the same byte sequence reachable.

  JVM-side: always captured. The bundle-size argument doesn't apply on
  the JVM; SSR / test / tooling builds can read
  `(:rf.handler/source (rf/handler-meta :event id))` directly."
  nil)

(defn merge-coords
  "Merge `*pending-coords*` into `user-meta`. User-supplied :ns / :line
  / :file override auto-captured values per Spec 001. Returns user-meta
  unchanged when no coords are pending (programmatic registration,
  REPL eval without the macro path).

  Per rf2-3un2g §Production elision: in CLJS `:advanced` +
  `goog.DEBUG=false` builds (and JVM SSR with `re-frame.debug=false`)
  this fn returns `user-meta` unchanged regardless of any pending
  coords binding. Coord-keys are stripped from the public registry-meta
  in production; the always-on `error-coords-by-id` parallel registry
  (see [[remember-error-coords!]]) carries them through to the
  error-emit substrate for Sentry-style observability."
  [user-meta]
  (if-not interop/debug-enabled?
    ;; Production: strip the coord-keys from public meta. The always-on
    ;; error-coords parallel registry retains them for error-emit
    ;; observability — see [[remember-error-coords!]] / [[error-coords-for]].
    (or user-meta {})
    (let [coords *pending-coords*]
      (if coords
        (merge coords (or user-meta {}))
        (or user-meta {})))))

;; ---- DOM source-coord attribute parser (rf2-nr7vf2) -----------------------
;;
;; The inverse of `re-frame.adapter.context/format-source-coord` (re-exported
;; as `re-frame.views/format-source-coord` / `re-frame.substrate.spine/
;; format-source-coord`). The formatter renders a registry slot's id + coords
;; into the `data-rf2-source-coord` DOM-attribute value `<ns>:<sym>:<line>:
;; <col>`; this parser recovers `{:ns :handler-id :line :col}` from that
;; string. It lives HERE — in the source-coord contract owner (Spec 006
;; §Source-coord annotation / Tool-Pair §Source-mapping), not in the
;; CLJS-only `adapter.context` ns — because consumers reading the attribute
;; include JVM-side `.cljc` surfaces (Story's element-inspector pure helpers,
;; the SSR round-trip parity test). Co-locating format + parse here keeps the
;; round-trip a single source of truth (rf2-nr7vf2 collapses the parser that
;; was reimplemented near-byte-for-byte in Story's element_inspector.cljc and
;; the re-frame2-pair preload runtime).
;;
;; Tool-Pair.md declares the attribute value opaque to consumers and warns
;; downstream callers MUST NOT depend on the parsed shape's stability across
;; re-frame2 versions. Canonicalising the parser in the contract owner ties
;; the format and its inverse to the same version boundary — the elegant
;; single-source posture pre-alpha invites.

(defn- parse-coord-int
  "Parse a `data-rf2-source-coord` line/col segment to an integer, or nil.
  Returns nil for the `?` placeholder (programmatic registrations that
  bypassed the macro path) and any other non-numeric / non-string input.
  Never throws."
  [s]
  (when (and (string? s) (re-matches #"\d+" s))
    #?(:cljs (js/parseInt s 10)
       :clj  (Long/parseLong s))))

(defn parse-source-coord
  "Parse a `data-rf2-source-coord` attribute value into
  `{:ns :handler-id :line :col}`, or nil. The inverse of
  `re-frame.adapter.context/format-source-coord` (re-exported as
  `re-frame.views/format-source-coord`).

  Per Spec 006 §Attribute value format the value is a four-segment colon-
  separated string `<ns>:<sym>:<line>:<col>` where `<ns>` / `<sym>` derive
  from the registry id keyword (`(namespace id)` / `(name id)`) and
  `<line>` / `<col>` are the macro-captured source coords. Either coord
  segment may be the literal `?` for programmatic registrations that
  bypassed the macro path; `:line` / `:col` are nil in that case.

  Returns nil for malformed input — blank / non-string input, fewer or
  more than four segments, or an empty `<ns>` / `<sym>` segment. Never
  throws. Pair-shaped consumers fall back to `(rf/handler-meta :view id)`
  when this returns nil (Spec 006 §Documented exemption).

  Tool-Pair.md declares the attribute value opaque to consumers; this
  parser exists so tooling's DOM → source bridge can be useful, but
  downstream callers MUST NOT depend on the parsed shape's stability
  across re-frame2 versions."
  [attr-val]
  (when (and (string? attr-val) (seq attr-val))
    (let [parts (str/split attr-val #":")]
      (when (= 4 (count parts))
        (let [[ns-part sym-part line-part col-part] parts]
          (when (and (seq ns-part) (seq sym-part))
            {:ns         ns-part
             :handler-id sym-part
             :line       (parse-coord-int line-part)
             :col        (parse-coord-int col-part)}))))))

;; ---- data-rf-view attribute parser (rf2-ztxnm8 / rf2-16znzb) ---------------
;;
;; The inverse of `re-frame.adapter.context/format-view-id` (re-exported as
;; `re-frame.views/format-view-id` / `re-frame.substrate.spine/format-view-id`).
;; The formatter renders a registry id keyword into the `data-rf-view` DOM-
;; attribute value via `(str id)` — `:rf.foo/bar` → `":rf.foo/bar"`; this parser
;; recovers the id from that string. It lives HERE — beside `parse-source-coord`,
;; in the source-coord contract owner (Spec 006 §View tagging contract /
;; §Source-coord annotation) — because the read rule used to live ONLY in
;; `format-view-id`'s docstring + Spec 006 prose and was re-implemented inline by
;; every consumer (the Xray fallback view-walker and the re-frame2-pair preload
;; runtime's `view-entity`). Co-locating format + parse keeps the round-trip a
;; single source of truth (rf2-ztxnm8 collapses those re-derivations — the
;; data-rf-view analogue of the `parse-source-coord` work in rf2-nr7vf2).
;;
;; Tool-Pair.md declares the attribute value opaque to consumers and warns
;; downstream callers MUST NOT depend on the parsed shape's stability across
;; re-frame2 versions. Canonicalising the parser in the contract owner ties the
;; format and its inverse to the same version boundary.

(defn parse-view-id
  "Parse a `data-rf-view` attribute value back into the registry id. The
  inverse of `re-frame.adapter.context/format-view-id` (re-exported as
  `re-frame.views/format-view-id` / `re-frame.substrate.spine/format-view-id`),
  which renders the id via `(str id)`.

      \":rf.foo/bar\"  → :rf.foo/bar         ;; namespaced keyword id
      \":bare\"        → :bare               ;; unqualified keyword id
      \"raw-string\"   → \"raw-string\"      ;; non-keyword id (unusual)

  A leading `:` marks a stringified keyword: the body is split on the first
  `/` to recover namespace / name; a body with no `/` becomes an unqualified
  keyword. Any other (non-colon-prefixed) string is a non-keyword id and is
  returned verbatim. Returns nil for nil / non-string input; never throws.

  Per Spec 006 §View tagging contract §Attribute value format and
  Spec-Schemas §`:rf/view-id-attr`.

  Tool-Pair.md declares the attribute value opaque to consumers; this parser
  exists so tooling's DOM → registry-id bridge can be useful, but downstream
  callers MUST NOT depend on the parsed shape's stability across re-frame2
  versions."
  [attr-val]
  (cond
    (or (nil? attr-val) (not (string? attr-val)))
    nil

    ;; Leading colon → keyword. Splits on the first `/` to recover ns/name.
    (and (pos? (count attr-val)) (= ":" (subs attr-val 0 1)))
    (let [body  (subs attr-val 1)
          slash (str/index-of body "/")]
      (if (nil? slash)
        (keyword body)
        (keyword (subs body 0 slash) (subs body (inc slash)))))

    :else
    attr-val))

;; ---- co-located per-element machine source (rf2-npvsx) -------------------
;;
;; The registered machine spec carries ONE cohesive map per guard / action /
;; on-spawn-action: `:guards {<id> {:fn <fn> :source-coords {...} :source-code
;; "..."}}` (and likewise `:actions` / `:on-spawn-actions`). The `:fn` slot is
;; ALWAYS present — it's the function the transition engine invokes. The
;; `:source-coords` / `:source-code` slots are DEBUG-only: the reg-machine
;; macro emits them via [[collocate-element-source]] under an
;; `(if interop/debug-enabled? ...)` gate, so production CLJS builds run the
;; `(else)` branch — [[wrap-element-fns]] — which collapses each entry to
;; `{:fn <fn>}` with no source literals reachable. Keeping `:fn` separable is
;; what lets Closure DCE the dev-only source bytes (rf2-npvsx supersedes the
;; rf2-8bp3 `:rf.machine/source-coords` + rf2-ypu5i `:rf.machine/handler-
;; source` side-indexes — consumers now read ONE place, one lookup).

(defn wrap-element-fns
  "Production-branch co-location: rewrite the `slot-key` map's
  `{<id> <fn-or-ref>}` entries to `{<id> {:fn <fn-or-ref>}}`, dropping
  every debug slot. A keyword-reference value (`{<id> :other-id}`) is
  preserved verbatim — `:fn` would be a misnomer for an indirection, and
  the transition engine's `chase-ref` follows the bare keyword. No-op
  when the slot is absent or not a map. Returns the updated spec.

  This is the `(else)` arm of the reg-machine macro's
  `(if interop/debug-enabled? <collocate> <wrap>)` gate — it references
  NO source literals, so under `:advanced` + `goog.DEBUG=false` the
  whole dev arm (with its coord maps + `pr-str` source strings) DCEs and
  only `{:fn <fn>}` entries ship."
  [spec slot-key]
  (let [m (get spec slot-key)]
    (if-not (map? m)
      spec
      (assoc spec slot-key
             (reduce-kv
               (fn [acc id v]
                 (assoc acc id (if (keyword? v) v {:fn v})))
               {} m)))))

(defn collocate-element-source
  "Dev-branch co-location: rewrite the `slot-key` map's `{<id> <fn-or-ref>}`
  entries to `{<id> {:fn <fn> :source-coords {...} :source-code \"...\"}}`,
  merging the per-id `source-data` (a `{<id> {:source-coords {...}
  :source-code \"...\"}}` map the reg-machine macro stamped at expansion
  time). Entries with no matching `source-data` (e.g. a let-bound symbol
  the walker couldn't `pr-str` meaningfully) still get a `{:fn <fn>}`
  wrapper. Keyword-reference values are preserved verbatim (no `:fn`
  wrapper — `chase-ref` follows the bare keyword). Returns the updated
  spec.

  This is the dev arm of the reg-machine macro's `(if interop/debug-
  enabled? <collocate> <wrap>)` gate. The `source-data` literal is
  referenced ONLY here, so it DCEs alongside this arm under `:advanced` +
  `goog.DEBUG=false`."
  [spec slot-key source-data]
  (let [m (get spec slot-key)]
    (if-not (map? m)
      spec
      (assoc spec slot-key
             (reduce-kv
               (fn [acc id v]
                 (assoc acc id
                        (if (keyword? v)
                          v
                          (merge {:fn v} (get source-data id)))))
               {} m)))))

(defn collocate-state-source
  "Dev-branch runtime co-location of reference-site `:source-coords` onto
  each map node inside the registered machine spec's `:states` tree
  (rf2-vqja2). `coords` is the `{<map-spec-path> <coord-map>}` index the
  reg-machine macro built via [[walk-machine-spec]] at expansion time; this
  fn splices each coord onto the map living at its spec-path, yielding e.g.

      :states {:locked {:tags #{:door/locked}
                        :on   {:door/insert-coin :closed}
                        :source-coords {:ns .. :file .. :line N :column N}}}

  Only paths whose live value is a map get a `:source-coords` (the walker
  only emits map-valued paths; this fn defends with `(map? (get-in ...))`
  so a spec whose runtime shape diverged from the literal — never, in
  practice — degrades to a no-op rather than corrupting a non-map slot).

  This is the dev arm of the reg-machine macro's `(if interop/debug-enabled?
  <collocate> <identity>)` gate — the `coords` literal is referenced ONLY
  here, so under `:advanced` + `goog.DEBUG=false` the whole co-location (and
  its coord maps) DCEs and the registered state-nodes ship clean (just the
  user's `{:tags :on …}`). Returns the updated spec."
  [spec coords]
  (reduce-kv
    (fn [acc path coord]
      (if (map? (get-in acc path))
        (assoc-in acc (conj (vec path) :source-coords) coord)
        acc))
    spec coords))

(defn collocate-state-inline-source
  "Dev-branch runtime co-location of inline-fn `:source-code` strings onto
  each enclosing `:states`-tree map node (rf2-se70xj). `inline-source` is the
  `{<map-spec-path> {<slot> <source-string>}}` index the macro built via
  [[walk-machine-inline-source]] at expansion time; this fn splices each
  per-slot source map onto the map living at its spec-path under
  `:source-code`, yielding e.g.

      :states {:open {:entry (fn …)
                      :on    {:door/close {:target :closed :guard :may-close?}}
                      :source-coords {…}
                      :source-code   {:entry \"(fn …)\"}}}

  and for an inline transition `:action`:

      :on {:cancel {:target :idle :action (fn [_] {})
                    :source-coords {…}
                    :source-code   {:action \"(fn [_] {})\"}}}

  The `:source-code` map sits ALONGSIDE the `:source-coords` already
  co-located on the node (per rf2-vqja2 / [[collocate-state-source]]); a tool
  resolving an inline-fn slot key (`[… :action]`) reads `(get-in spec […
  :source-code :action])` off the enclosing node. The inline SLOT itself
  keeps its bare fn value — the runtime engine resolves it via `fn?` and
  stamps it as the trace `:action-id`/`:guard-id`, so it must not be wrapped.

  This co-location does not collide with the named-element `:source-code`
  (a STRING on `:guards`/`:actions` entries via [[collocate-element-source]]):
  those live under the `:guards`/`:actions` registry slots, never on a
  `:states`-tree map node, so the two `:source-code` shapes never share a map.

  Only paths whose live value is a map get a `:source-code` (the walker only
  emits map-valued paths; this fn defends with `(map? (get-in …))` so a spec
  whose runtime shape diverged from the literal degrades to a no-op). Returns
  the updated spec.

  This is the dev arm of the reg-machine macro's `(if interop/debug-enabled?
  <collocate> <identity>)` gate — the `inline-source` literal is referenced
  ONLY here, so under `:advanced` + `goog.DEBUG=false` the whole co-location
  (with its `pr-str` source strings) DCEs and the registered state-nodes ship
  clean."
  [spec inline-source]
  (reduce-kv
    (fn [acc path src]
      (if (map? (get-in acc path))
        (assoc-in acc (conj (vec path) :source-code) src)
        acc))
    spec inline-source))

;; ---- always-on error-coord registry (rf2-3un2g) --------------------------
;;
;; The parallel registry that retains source-coords in production builds.
;; Populated unconditionally at registration time via [[remember-error-
;; coords!]]; the error-emit substrate reads it via [[error-coords-for]]
;; when assembling the tight error-record passed to corpus-wide listener
;; fans (Sentry / Honeybadger / Rollbar). Survives `:advanced` +
;; `goog.DEBUG=false` — the namespace and the atom are unconditional;
;; only the dev-side merge into public registry-meta is elided.

(defonce
  ^{:doc "kind → id → coords-map. Atomic. Per-process. Mirrors the
          registrar shape so error-emit can pivot on `(kind, id)`. The
          values are coord-maps (`:rf/source-coord-meta` per
          Spec-Schemas — `:ns` / `:file` / `:line`; `:column` is dev-
          only). Survives production elision so Sentry-style shippers
          see source-line info even when the trace surface is gone."}
  error-coords-by-id
  (atom {}))

(defn remember-error-coords!
  "Store coord-map under `[kind id]` in the always-on parallel registry.
  Called by `re-frame.registrar/register!` from any path where
  `*pending-coords*` is bound (the public reg-* macro path). In CLJS
  production builds the coord-map's `:column` slot is absent — the
  prod-side macro emission omits it; only `:ns`/`:file`/`:line` ride
  through. Returns the stored coord-map.

  Per rf2-3un2g §Always-on error-coord registry."
  [kind id coords]
  (when (and kind id coords)
    (swap! error-coords-by-id assoc-in [kind id] coords))
  coords)

(defn error-coords-for
  "Look up the stored source-coord map for `[kind id]`. Returns nil when
  no coords were captured for that pair (programmatic registration, REPL
  eval that bypassed the macro path). The error-emit substrate uses this
  to stamp `:source-coord` on the tight record + policy-event in BOTH
  dev AND production. Per rf2-3un2g."
  [kind id]
  (get-in @error-coords-by-id [kind id]))

(defn forget-error-coords!
  "Clear the parallel registry. Test fixtures use this between cases.
  Mirrors `registrar/clear-all!`. Per rf2-3un2g."
  []
  (reset! error-coords-by-id {})
  nil)

;; ---- :file resolution at macro-expansion time (rf2-mdjp) ------------------
;;
;; The reg-* macros in `re-frame.core` capture `(meta &form)` and `*file*`
;; from their compile-time environment and emit a `*pending-coords*`
;; binding map. The naive `*file*`-only path is wrong under CLJS: the
;; CLJS analyzer's macro-expansion entry point (`cljs.analyzer/
;; macroexpand-1*`, cljs/analyzer.cljc ~L4284) binds `*cljs-file*` rather
;; than Clojure's `*file*` during expansion — so `*file*` retains the
;; JVM compiler's default `"NO_SOURCE_PATH"` sentinel under CLJS. That
;; sentinel would then get baked into the `:file` slot of every
;; registration's source-coord, defeating jump-to-source and tooling
;; that reads `(rf/handler-meta kind id)`.
;;
;; The fix mirrors rf2-ulxi (PR #340, Story's `coords-form`):
;; prefer `(:file (meta &form))` — tools.reader's indexing-push-back-reader
;; stamps `:file` on every collection-form's metadata, which survives the
;; macro-expansion handoff to cljs.analyzer. Fall back to `*file*` (the
;; JVM-only correct path). Reject the `"NO_SOURCE_PATH"` sentinel from
;; either source — if both resolve to it, omit `:file` entirely (better
;; no `:file` than a poison value).

(defn ^:private no-source-path? [s]
  (or (nil? s) (= "NO_SOURCE_PATH" s)))

(defn resolve-file
  "Pick the right `:file` value for a reg-* macro's emitted source-coord
  map. `form-meta` is `(meta &form)`; `file` is `*file*` at expansion
  time. Returns the form-meta `:file` when non-sentinel, else the
  `*file*` arg when non-sentinel, else `nil` (caller `cond->`s it in,
  so nil means omit the slot).

  The CLJS analyzer never binds Clojure's `*file*` during macro
  expansion, so reading `*file*` alone returns the JVM
  `\"NO_SOURCE_PATH\"` sentinel under CLJS. Form-meta `:file` is the
  portable answer."
  [form-meta file]
  (let [meta-file (:file form-meta)]
    (cond
      (not (no-source-path? meta-file)) meta-file
      (not (no-source-path? file))      file
      :else                              nil)))

;; ---- :file absolutisation (rf2-wvsxg) ------------------------------------
;;
;; Both shadow-cljs and the JVM compiler put the **classpath-relative**
;; portion of a source file in the form's `:file` slot — for
;; `tools/xray/src/day8/re_frame2_xray/views/edn_inspector.cljs` (whose
;; classpath root is `tools/xray/src/`), the form-meta `:file` is just
;; `"day8/re_frame2_xray/views/edn_inspector.cljs"`. The source-root
;; segment is invisible to anyone consuming the captured coord, which
;; defeats Story / Xray's `open-in-editor` chip — `:project-root` plus
;; the classpath-relative tail produces the wrong on-disk path
;; whenever the project-root isn't the same as the classpath root that
;; resolved the file.
;;
;; Live failure shape (rf2-wvsxg):
;;   project-root  C:/Users/me/code/my-app/tools/xray/testbeds
;;   :file         day8/re_frame2_xray/views/edn_inspector.cljs
;;   composed      C:/.../tools/xray/testbeds/day8/.../edn_inspector.cljs
;;   actual        C:/.../tools/xray/src/day8/.../edn_inspector.cljs
;;
;; The fix: at macro-expansion time on the JVM, resolve the classpath-
;; relative `:file` to its on-disk URL via the context class-loader and
;; bake the **absolute on-disk path** into the emitted coord. The
;; downstream URI builder's `compose-path` already detects absolute
;; paths and passes them through unchanged, so a coord baked with an
;; absolute `:file` ships the right URI regardless of which
;; `:project-root` the host configured. Absolutisation runs once per
;; macro expansion (JVM-side); the CLJS runtime sees a literal string
;; (cheap), and production builds elide both the literal and the
;; surrounding coord-form per the existing rf2-3un2g gate.
;;
;; Failure modes that fall through to the unchanged input:
;;   - Already-absolute path (e.g. a JVM-compile `*file*` that
;;     happened to be absolute, or a synthetic coord with an absolute
;;     `:file`): detected by `editor-uri/absolute-path?` — the same
;;     predicate `editor-uri/compose-path` uses (leading `/`, leading
;;     drive letter, leading `file:` scheme, leading backslash).
;;   - File not resolvable on classpath (REPL-eval forms with synthetic
;;     `:file`, a test fixture's fabricated path, a path under a
;;     classpath root the JVM doesn't have when the macro expands):
;;     pass through unchanged; the downstream URI builder still gets a
;;     coord, just one whose `:project-root` join is the legacy
;;     behaviour.
;;   - CLJS-side calls (no class-loader access): no-op pass-through;
;;     the macro path is JVM-side by construction so this branch only
;;     fires when callers reach the fn from CLJS runtime code (rare).

#?(:clj
   (defn ^:private context-class-loader ^ClassLoader []
     (.getContextClassLoader (Thread/currentThread))))

#?(:clj
   (defn absolutise-file
     "JVM-only. Resolve a classpath-relative source file path to its
     absolute on-disk path via the context class-loader. Returns the
     input unchanged when the path is already absolute, when classpath
     resolution fails (no resource found, non-`file:` URL), or when
     `path` is nil / blank.

     Used by `coords-form` / `prod-coords-form` / `form-coords` at
     macro-expansion time to bake an absolute `:file` value into each
     emitted source-coord literal — defeating the source-root
     ambiguity that bites multi-source-path builds (shadow-cljs lists
     both `tools/xray/src` and `tools/xray/testbeds` for the panel-
     gallery testbed; the form-meta's classpath-relative `:file` carries
     no signal about which source root resolved it).

     Per rf2-wvsxg."
     [path]
     (if (or (nil? path) (.isEmpty ^String path) (editor-uri/absolute-path? path))
       path
       (try
         (if-let [url (.getResource (context-class-loader) path)]
           (if (= "file" (.getProtocol url))
             ;; URL paths come out URL-encoded (e.g. `%20` for spaces)
             ;; and use `/` on every platform. Decode via `URI.getPath`
             ;; rather than `URLDecoder`: `URLDecoder` is a form-body
             ;; (`application/x-www-form-urlencoded`) decoder that maps a
             ;; literal `+` to a space — but a `file:` URL path is NOT
             ;; form-encoded, so a literal `+` in a checkout path (e.g.
             ;; `C:/code/re-frame2+wip`) must survive verbatim. `URI.getPath`
             ;; decodes percent-escapes (`%20` → space, `%2B` → `+`) while
             ;; leaving a literal `+` untouched — the correct grammar for a
             ;; `file:` URL path (mirrors `file-url->path` in
             ;; re-frame.testbed.open-in-editor-server, the runtime twin).
             ;; The result on Windows is `/C:/Users/...` so strip a leading
             ;; slash before a drive-letter to get the canonical
             ;; `C:/Users/...` shape `compose-path` already handles.
             (let [decoded (.getPath (.toURI url))]
               (if (and (> (.length ^String decoded) 2)
                        (= \/ (.charAt ^String decoded 0))
                        (= \: (.charAt ^String decoded 2)))
                 (.substring ^String decoded 1)
                 decoded))
             ;; jar:/zip:/http: — leave the input unchanged; an
             ;; in-jar source file isn't editable on disk anyway.
             path)
           ;; Not on classpath (REPL eval, synthetic coord, test
           ;; fabrication): pass through.
           path)
         (catch Throwable _
           ;; Defensive — classpath probing must never break macro
           ;; expansion. Any failure → preserve original behaviour.
           path)))))

(defn coords-form
  "Construct the compile-time `(cond-> {:ns 'sym} ...)` form that every
  reg-* macro emits as the value of its `*pending-coords*` binding.

  `form-meta` is `(meta &form)`; `file` is `*file*`; `ns-sym` is the
  consumer's namespace symbol. The returned form is syntax-quote-safe
  data the caller splices into its expansion.

  :file picks the form-meta value over `*file*` and rejects the
  `\"NO_SOURCE_PATH\"` sentinel via `resolve-file`.

  Per rf2-3un2g §Production elision: callers SHOULD wrap the dev
  emission alongside [[prod-coords-form]] under
  `(if interop/debug-enabled? <dev> <prod>)` so Closure DCEs the dev
  shape (with `:column`) under `:advanced` + `goog.DEBUG=false`. The
  `with-coords-form` / `expand-reg-machine` helpers do this internally;
  per-element machine stamping and call-site stamping handle elision
  through their own outer gates and call this fn directly.

  Per rf2-wvsxg: when running on the JVM (the macro-expansion side),
  the picked `:file` is fed through [[absolutise-file]] to resolve the
  classpath-relative form-meta `:file` to its absolute on-disk path.
  Downstream URI builders' `compose-path` detects absolute paths and
  passes them through unchanged, so the emitted coord ships the right
  on-disk path regardless of the host's `:project-root` configuration."
  [form-meta file ns-sym]
  (let [chosen-file (resolve-file form-meta file)
        chosen-file #?(:clj  (when chosen-file (absolutise-file chosen-file))
                       :cljs chosen-file)]
    `(cond-> {:ns '~ns-sym}
       ~chosen-file         (assoc :file ~chosen-file)
       ~(:line form-meta)   (assoc :line ~(:line form-meta))
       ~(:column form-meta) (assoc :column ~(:column form-meta)))))

#?(:clj
   (defn prod-coords-form
     "Slim production-side variant of [[coords-form]]: omits `:column`.
     Per rf2-3un2g — `:column` is dev-tooling-only (IDE jump-to-source
     refinement); Sentry-style observability needs only `:ns`/`:file`/
     `:line`. Emitting the slim form under the prod branch of an
     `(if interop/debug-enabled? ...)` lets Closure DCE the dev coords
     literal (with `:column`) under `:advanced` + `goog.DEBUG=false`,
     so the bundle ships the slimmer payload only.

     Caller wraps:

         `(if re-frame.interop/debug-enabled?
            ~(coords-form form-meta file ns-sym)
            ~(prod-coords-form form-meta file ns-sym))

     Both branches use `cond->` so absent keys (e.g. nil `:line` on a
     programmatic synthesis) elide cleanly.

     Per rf2-wvsxg: the picked `:file` is absolutised via
     [[absolutise-file]] at macro-expansion time so the downstream URI
     builder receives an absolute on-disk path regardless of which
     source-root resolved the file on shadow-cljs's classpath."
     [form-meta file ns-sym]
     (let [chosen-file (resolve-file form-meta file)
           chosen-file (when chosen-file (absolutise-file chosen-file))]
       `(cond-> {:ns '~ns-sym}
          ~chosen-file       (assoc :file ~chosen-file)
          ~(:line form-meta) (assoc :line ~(:line form-meta))))))

;; ---- co-located reference-site (states-tree) coord stamping --------------
;;
;; Per Spec 005 §Source-coord stamping: the `reg-machine` macro walks its
;; literal machine-spec form at expansion time and CO-LOCATES a REFERENCE-SITE
;; `:source-coords` onto each map node inside the `:states` tree — directly on
;; the state-node / transition map at its spec path, e.g.
;;
;;   :states {:locked {:tags #{:door/locked}
;;                     :on   {:door/insert-coin :closed}
;;                     :source-coords {:ns .. :file .. :line N :column N}}}
;;
;; Tools (pair, 10x, IDE jump-to-source) read the coord back by navigating
;; from a snapshot state-path to the state-node and reading its `:source-
;; coords` — `(rf/handler-meta :event machine-id)` → `:rf/machine` → `(get-in
;; spec [:states ...])` → `:source-coords`. Per rf2-vqja2 this supersedes the
;; flat, spec-path-keyed `:rf.machine/state-coords` side-index that paralleled
;; the `:states` tree (the same parallel-side-index anti-pattern rf2-npvsx
;; removed for guards/actions, now removed for STATES).
;;
;; The DEFINITION-site coords (where a guard / action / on-spawn-action fn
;; literal lives — the `:guards` / `:actions` / `:on-spawn-actions` map
;; values) live on each element entry per rf2-npvsx (`:guards {<id> {:fn ..
;; :source-coords .. :source-code ..}}`). See [[walk-element-source]] +
;; [[collocate-element-source]].
;;
;; What lives here is the reference-site co-location: each MAP node inside
;; `:states` (state-node, `:spawn` map, transition map) carries its own
;; `:source-coords`. Inline-fn slots (`:entry` / `:exit` / `:guard` /
;; `:action` / `:on-spawn`) hold a fn or keyword VALUE — there is no map to
;; hang a key on — so they are NOT stamped directly; a tool resolving an
;; inline-fn slot reads the `:source-coords` off the nearest enclosing map
;; (its state-node / transition map), which IS stamped. This mirrors Mike's
;; keyword-reference rule: a keyword `:guard :form-valid?` carries no reader
;; metadata, and the enclosing transition map's coord stands in for it.
;;
;; Spec paths are vectors of keys mirroring the spec's `:states` structure
;; (`[:states :idle :on :submit]` for the `:submit` transition map). The
;; walker runs at compile time on JVM only (the Clojure side of the macro)
;; and produces a `{<map-spec-path> <coord-form>}` index that
;; [[collocate-state-source]] splices onto each map node at RUNTIME — but
;; only in the dev arm of the macro's `interop/debug-enabled?` gate, so the
;; whole co-location DCEs under `goog.DEBUG=false`.

#?(:clj
   (do

(defn ^:private form-coords
  "Read source coords off a Clojure form's metadata. Forms the reader has
  decorated (lists, vectors, maps, symbols) carry `:line` / `:column` from
  the source position. Returns nil when the form has no positional meta.

  The same `:file` resolution as the call-site path applies: prefer
  the reader-attached `:file` on the form's metadata over the macro's
  `*file*` arg, and reject the `\"NO_SOURCE_PATH\"` sentinel.

  Per rf2-wvsxg: the picked `:file` is absolutised via
  [[absolutise-file]] (JVM macro-expansion path) so per-machine-element
  coords ship absolute on-disk paths matching the reg-* macro coords."
  [form ns-sym file]
  (let [form-meta    (meta form)
        chosen-file  (resolve-file form-meta file)
        chosen-file  (when chosen-file (absolutise-file chosen-file))]
    (when (and form-meta (or (:line form-meta) (:column form-meta)))
      (cond-> {:ns ns-sym}
        chosen-file        (assoc :file chosen-file)
        (:line form-meta)  (assoc :line (:line form-meta))
        (:column form-meta) (assoc :column (:column form-meta))))))

(defmacro ^:private stamp-map!
  "Compile-time helper for the machine-spec walker. Reads source coords off
  the MAP form `form` and, when any are present, stamps them into the
  transient accumulator at `path`. Guards on `(map? form)` so only map
  nodes (state-nodes, transition maps, `:spawn` / `:after` maps) get a
  co-located coord — an inline-fn / keyword value at the same slot is
  skipped (there is no map to hang `:source-coords` on; a tool reads the
  enclosing map's coord). Inlines to the equivalent
  `(when (and (map? form) ...) (assoc! acc path c))` so the imperative-
  mutation shape `walk-states-tree` relies on for macro-expansion-time
  performance is preserved.

  Lexical-capture contract: callers must have `acc` (a transient map),
  `ns-sym` (a symbol), and `file` (a string or nil) in scope. The macro
  is private to this namespace and used only inside `walk-states-tree`
  and `walk-machine-spec`, both of which bind those three locals.

  Hides the repetitive shape behind a single two-arg call at every
  reference-site stamp."
  [path form]
  `(let [form# ~form]
     (when (map? form#)
       (when-let [c# (form-coords form# ~'ns-sym ~'file)]
         (assoc! ~'acc ~path c#)))))

(defn- walk-states-tree
  "Recursively walk the literal `:states` map. `path` accumulates the
  spec-path from the spec's root. Adds an entry into the mutable `acc`
  transient for each MAP node (state-node, `:spawn` map, transition map)
  that carries reader-position metadata — `{<map-spec-path> <coord-map>}`.
  Inline-fn / keyword slots (`:entry` / `:exit` / `:guard` / `:action` /
  `:on-spawn`) are NOT stamped: they hold a fn or keyword value, not a map,
  so there is no node to co-locate `:source-coords` onto (a tool resolving
  such a slot reads the enclosing map's coord). Per rf2-vqja2.

  Note on style: this walker is mutation-heavy (transient `acc` threaded
  through nested `reduce-kv` / `doseq` with `assoc!` via the `stamp-map!`
  macro) rather than the more functional shape of a visitor that returns
  collected entries. The imperative shape is deliberate — this code runs
  at macro-expansion time and gets called on every `reg-machine` form.
  Transients avoid the per-state allocation cost of building intermediate
  persistent maps during expansion. The result is materialised once at
  the edge in `walk-machine-spec`. Refactoring to a fully declarative
  visitor is feasible but would need to be benchmarked against current
  compile-time numbers before adoption."
  [states-form path acc ns-sym file]
  (when (map? states-form)
    (reduce-kv
      (fn [acc state-id node]
        (let [node-path (conj path state-id)]
          ;; The state-node map itself carries its co-located coord.
          (stamp-map! node-path node)
          (when (map? node)
            ;; :spawn map (its inline `:on-spawn` fn slot is not stamped).
            (when-let [inv (:spawn node)]
              (stamp-map! (conj node-path :spawn) inv))
            ;; :on transitions — map of event-id → transition-or-vector.
            ;; Each transition MAP carries its coord; inline `:guard` /
            ;; `:action` fn slots inside it are not stamped (the
            ;; transition map's coord stands in).
            (when-let [on-map (:on node)]
              (when (map? on-map)
                (reduce-kv
                  (fn [_ ev-id t]
                    (let [tp (conj node-path :on ev-id)]
                      (cond
                        (map? t)
                        (stamp-map! tp t)
                        (vector? t)
                        (doseq [[i tx] (map-indexed vector t)
                                :when (map? tx)]
                          (stamp-map! (conj tp i) tx)))
                      nil))
                  nil on-map)))
            ;; :always — vector of transition maps.
            (when-let [always (:always node)]
              (when (vector? always)
                (doseq [[i tx] (map-indexed vector always)
                        :when (map? tx)]
                  (stamp-map! (conj node-path :always i) tx))))
            ;; :after — map of delay → target-or-transition map.
            (when-let [after (:after node)]
              (when (map? after)
                (reduce-kv
                  (fn [_ delay t]
                    (stamp-map! (conj node-path :after delay) t)
                    nil)
                  nil after)))
            ;; recurse into nested :states
            (walk-states-tree (:states node) (conj node-path :states) acc ns-sym file))
          acc))
      acc states-form)))

(defn walk-machine-spec
  "Compile-time helper. Walk a literal machine-spec form's `:states` tree
  (a Clojure map literal as it appears in user code) and return a flat map
  `{<map-spec-path> {:ns :line :column :file}, ...}` capturing the
  REFERENCE-SITE source coordinate of each MAP node inside `:states`.

  Reference-site MAP nodes: each state-node, `:spawn` map, and transition
  map inside the `:states` tree is keyed by its full spec path, e.g.
  `[:states :idle :on :submit]` for the `:submit` transition map and
  `[:states :idle]` for the `:idle` state-node. Inline-fn / keyword slots
  (`:entry` / `:exit` / `:guard` / `:action` / `:on-spawn`) are NOT keyed —
  they hold a value, not a map, so there is no node to co-locate a coord
  on; a tool reads the enclosing map's coord (per rf2-vqja2, mirroring the
  keyword-reference rule).

  The returned index is consumed by [[collocate-state-source]], which
  splices each coord onto its map node at runtime (the dev arm of the
  macro's `interop/debug-enabled?` gate), so the registered spec carries
  `:source-coords` directly on each state-node / transition map rather than
  in a flat side-index.

  Definition-site coords (each fn literal under `:guards` / `:actions` /
  `:on-spawn-actions`) are NOT produced here — per rf2-npvsx they are
  co-located on each element entry via [[walk-element-source]].

  When the spec form is not a map literal (a symbol, a let-bound expr),
  returns `{}` — there's no literal tree to walk; tools fall back to the
  reg-machine call-site coords on the spec's top-level handler-meta.

  `ns-sym` and `file` come from the calling macro's compile environment.

  JVM-only — runs on the Clojure side of the macro. Returns a plain map
  literal that the macro feeds [[collocate-state-source]]; the splice runs
  only in the dev arm, so the whole co-location DCEs under
  `goog.DEBUG=false`."
  [spec-form ns-sym file]
  (if-not (map? spec-form)
    {}
    (let [acc (transient {})]
      ;; Reference-site stamping of MAP nodes under :states.
      (walk-states-tree (:states spec-form) [:states] acc ns-sym file)
      (persistent! acc))))

;; ---- inline-fn source-code co-location (rf2-se70xj) -----------------------
;;
;; Per rf2-vqja2 an inline-fn slot (`:entry` / `:exit` / `:guard` / `:action`)
;; inside the `:states` tree holds a fn VALUE, not a map, so it carries no
;; `:source-coords` of its own — a tool resolving such a slot reads the
;; enclosing map node's coord (jump-to-editor lands on the right line). But
;; the enclosing map's coord cannot supply the inline fn's CODE TEXT: pr-str
;; of the enclosing transition map (`{:target :x :action (fn …)}`) is not the
;; action fn body, so Xray's Epoch-panel micro-step rendered an inline action
;; as `#object[Function]` (the bare compiled fn falling through `pr-str`).
;;
;; The fix mirrors the guard mechanism's `:source-code` capture (the
;; `pr-str` of the fn literal) WITHOUT wrapping the inline slot value — the
;; runtime engine resolves `:entry`/`:exit`/`:guard`/`:action` slots
;; directly via `fn?`/`keyword?` and stamps the slot value as the trace
;; `:action-id`/`:guard-id`, so the slot value must stay a bare fn. Instead
;; the inline fn's source string is co-located onto the ENCLOSING map node
;; under `:source-code` — a `{<slot> <source-string>}` map keyed by the
;; inline slot — parallel to the `:source-coords` already co-located there
;; (`walk-machine-spec` / `collocate-state-source`). A tool resolving an
;; inline-fn slot key (`[… :action]`) reads `(get-in spec [… :source-code
;; :action])` off the enclosing map. Keyword-reference slots (`:action
;; :clear-hold`) carry NO inline source — their body lives on the named
;; `:actions` entry (per `walk-element-source`).
;;
;; The whole index rides the dev arm of the macro's `interop/debug-enabled?`
;; gate (spliced only by `collocate-state-inline-source`), so the source
;; strings DCE under `:advanced` + `goog.DEBUG=false` exactly as the
;; reference-site coord index does.

#?(:clj
   (do

(def ^:private inline-source-slots
  "The inline-fn slots whose fn-literal source is co-located onto the
   enclosing `:states`-tree map node's `:source-code` map (rf2-se70xj).
   State-nodes carry `:entry` / `:exit`; transition maps carry `:guard` /
   `:action`. Only fn-literal values are captured (keyword references defer
   to the named `:guards` / `:actions` entry's own `:source-code`)."
  [:entry :exit :guard :action])

(defn- node-inline-source
  "Build the `{<slot> <source-string>}` map for a single `:states`-tree map
   node `form`, capturing the `pr-str` of each inline-fn slot whose value is
   a fn LITERAL (`(fn …)` / `#(…)` reader form — a list). Keyword references
   and non-list values are skipped (a keyword's body lives on its named
   `:guards` / `:actions` entry; a non-list slot value carries no fn form to
   render). Returns nil when the node carries no capturable inline fn so the
   caller emits no entry. Compile-time / JVM-only."
  [form]
  (when (map? form)
    (let [m (reduce
              (fn [acc slot]
                (let [v (get form slot)]
                  ;; A fn literal reads as a list — `(fn …)` or the `#(…)`
                  ;; reader-macro expansion. `seq?` distinguishes it from a
                  ;; keyword reference or any non-form value, mirroring the
                  ;; `(keyword? fn-form)` skip in `walk-element-source`.
                  (if (seq? v)
                    (assoc acc slot (pr-str v))
                    acc)))
              {}
              inline-source-slots)]
      (when (seq m) m))))

(defn- walk-states-inline-source
  "Recursively walk the literal `:states` map and return a flat index
   `{<map-spec-path> {<slot> <source-string>}}` capturing each inline-fn
   slot's fn-literal source (`:entry` / `:exit` on state-nodes; `:guard` /
   `:action` on transition maps), keyed by the ENCLOSING map node's
   spec-path (rf2-se70xj). Mirrors `walk-states-tree`'s structural recursion
   so the same `:on` / `:always` / `:after` / nested-`:states` shapes are
   covered, but collects fn-literal SOURCE rather than map-node coords.

   `path` accumulates the spec-path from the spec's root. The mutable `acc`
   transient is threaded through. Compile-time / JVM-only."
  [states-form path acc]
  (if-not (map? states-form)
    acc
    (reduce-kv
      (fn [acc state-id node]
        (let [node-path (conj path state-id)]
          (when (map? node)
            ;; State-node inline `:entry` / `:exit`.
            (when-let [src (node-inline-source node)]
              (assoc! acc node-path src))
            ;; :on transitions — each transition MAP's inline `:guard` /
            ;; `:action`. Single-map and vector-of-candidates forms.
            (when-let [on-map (:on node)]
              (when (map? on-map)
                (reduce-kv
                  (fn [_ ev-id t]
                    (let [tp (conj node-path :on ev-id)]
                      (cond
                        (map? t)
                        (when-let [src (node-inline-source t)]
                          (assoc! acc tp src))
                        (vector? t)
                        (doseq [[i tx] (map-indexed vector t)
                                :when (map? tx)]
                          (when-let [src (node-inline-source tx)]
                            (assoc! acc (conj tp i) src)))))
                    nil)
                  nil on-map)))
            ;; :always — single transition MAP or a vector of candidate maps
            ;; (rf2-k7yqod; mirrors the `:on` single-map / vector forms the
            ;; runtime + validator both accept — `transition/pick-always-
            ;; transition` wraps a non-vector `:always` into `[always]`,
            ;; `validation/always-entries` does the same). The single-map
            ;; form is keyed at the bare `:always` path (the transition MAP
            ;; IS the `:always` value, like a single-map `:on` entry); the
            ;; vector form keys each candidate by index.
            (when-let [always (:always node)]
              (cond
                (map? always)
                (when-let [src (node-inline-source always)]
                  (assoc! acc (conj node-path :always) src))
                (vector? always)
                (doseq [[i tx] (map-indexed vector always)
                        :when (map? tx)]
                  (when-let [src (node-inline-source tx)]
                    (assoc! acc (conj node-path :always i) src)))))
            ;; :after — map of delay → target-or-transition map.
            (when-let [after (:after node)]
              (when (map? after)
                (reduce-kv
                  (fn [_ delay t]
                    (when (map? t)
                      (when-let [src (node-inline-source t)]
                        (assoc! acc (conj node-path :after delay) src)))
                    nil)
                  nil after)))
            ;; Recurse into nested :states.
            (walk-states-inline-source (:states node) (conj node-path :states) acc))
          acc))
      acc states-form)))

(defn walk-machine-inline-source
  "Compile-time helper. Walk a literal machine-spec form's `:states` tree
   and return a flat index `{<map-spec-path> {<slot> <source-string>}}`
   capturing each inline-fn slot's fn-literal source (rf2-se70xj). Keyed by
   the ENCLOSING `:states`-tree map node's spec-path — e.g.
   `[:states :idle :on :submit]` → `{:action \"(fn [_] {})\"}` for an inline
   transition `:action`, `[:states :open]` → `{:entry \"(fn …)\"}` for an
   inline state `:entry`.

   Inline-fn slots hold a fn VALUE, not a map, so they cannot carry a
   `:source-code` key of their own (per rf2-vqja2 the same reason
   `:source-coords` lives on the enclosing node). This index is consumed by
   [[collocate-state-inline-source]], which co-locates the per-slot source
   strings under a `:source-code` map on each enclosing node — the read-back
   surface Xray's Epoch-panel micro-step uses to render an inline action's
   code (it previously fell through to `pr-str` of the bare fn → an opaque
   `#object[Function]` token).

   Keyword-reference slots (`:action :clear-hold`) are NOT captured here —
   their body lives on the named `:actions` entry's own co-located
   `:source-code` (via [[walk-element-source]]).

   Returns `{}` when the spec form is not a map literal (a symbol /
   let-bound expr — nothing to walk). JVM-only — runs on the Clojure side
   of the `reg-machine` / `defmachine` macro. The whole returned literal is
   referenced only from the dev arm of the macro's `interop/debug-enabled?`
   gate, so it DCEs under `:advanced` + `goog.DEBUG=false`."
  [spec-form]
  (if-not (map? spec-form)
    {}
    (let [acc (transient {})]
      (walk-states-inline-source (:states spec-form) [:states] acc)
      (persistent! acc))))

   )) ;; end #?(:clj (do ...)) for the inline-source walk

;; ---- co-located per-element source walk (rf2-npvsx) ----------------------
;;
;; The reg-machine macro walks the literal `:guards` / `:actions` /
;; `:on-spawn-actions` maps at expansion time and produces, per id, the
;; definition-site source coords (the fn-form's reader position) AND the
;; `pr-str` of the fn-form. These are merged onto each element entry's
;; `{:fn .. :source-coords .. :source-code ..}` co-located map at registration
;; runtime by [[collocate-element-source]] — but ONLY in the dev arm of the
;; macro's `(if interop/debug-enabled? ...)` gate, so under `:advanced` +
;; `goog.DEBUG=false` the source-coord maps + `pr-str` strings DCE and prod
;; entries collapse to `{:fn <fn>}` ([[wrap-element-fns]]).
;;
;; A keyword-reference entry (`{<id> :other-id}`) carries no source — it is
;; skipped here and preserved verbatim by the co-location helpers.

(defn walk-element-source
  "Compile-time helper. Walk a literal machine-spec form's `slot-key` map
  (`:guards` / `:actions` / `:on-spawn-actions`) and return per-id source
  data:

      {<id> {:source-coords <coord-form>   ;; the fn-form's reader position
             :source-code   <pr-str>}      ;; the fn-form as source text
       ...}

  `<coord-form>` is the syntax-quote-safe coord map (`:ns` quoted) the
  caller splices into the dev arm of the macro expansion; `<pr-str>` is the
  literal source string. Keyword-reference entries are skipped (no source).
  Returns `{}` when the slot is absent / not a map, or the spec form is not
  a map literal (a symbol, a let-bound expr).

  JVM-only — runs on the Clojure side of the `reg-machine` macro. The whole
  returned literal is referenced only from the dev arm, so it DCEs under
  `:advanced` + `goog.DEBUG=false`."
  [spec-form slot-key ns-sym file]
  (if-not (map? spec-form)
    {}
    (let [m (get spec-form slot-key)]
      (if-not (map? m)
        {}
        (reduce-kv
          (fn [acc id fn-form]
            (if (keyword? fn-form)
              acc
              (let [coords (form-coords fn-form ns-sym file)
                    ;; Symbols inside `:ns` need explicit quoting — see the
                    ;; rationale in stamp-machine-spec-expr (otherwise the
                    ;; splice would namespace-resolve them at compile time).
                    coord-form (when coords
                                 (cond-> {:ns (list 'quote (:ns coords))}
                                   (:file coords)   (assoc :file (:file coords))
                                   (:line coords)   (assoc :line (:line coords))
                                   (:column coords) (assoc :column (:column coords))))]
                (assoc acc id
                       (cond-> {:source-code (pr-str fn-form)}
                         coord-form (assoc :source-coords coord-form))))))
          {} m)))))

   )) ;; end #?(:clj (do ...))
