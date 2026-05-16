(ns day8.re-frame2-causa-mcp.path-slice
  "Wire-pipeline mechanism W-2 at the Causa-MCP boundary (rf2-8xzoe.6).
  Path slicing — per `tools/causa-mcp/spec/004-Wire-Pipeline.md` §2
  (Path slicing).

  ## What this provides

  Tools returning rich nested values (`get-app-db`,
  `get-machine-state`, `get-epoch-history`) MUST accept an optional
  `:path` argument — an EDN-encoded vector of keys (e.g.
  `\"[:cart :items 3 :sku]\"`) addressing a subtree.

  This namespace is the per-call host-side input parser plus the
  shared `:path-not-found` result builder. Tools call:

    - `parse-path-arg` — normalise the raw `:path` MCP arg (JS array,
      CLJS vector, EDN string, nil) into a CLJS vector suitable for
      `get-in`.
    - `apply-to-result` — resolve the path against a tree-typed value
      and write the addressed subtree onto an envelope; on
      out-of-range paths splice the structured `:path-not-found`
      shape carrying the deepest-valid-prefix instead.
    - `path-not-found` — the canonical structured error shape that
      `apply-to-result` emits. Tools that compute the path resolution
      server-side may emit it directly without going through
      `apply-to-result`.
    - `deepest-valid-prefix` — pure walker used by `path-not-found`
      to compute the prefix attachment so the agent can re-aim
      without binary-search.

  ## Default-without-path posture (MUST 9)

  Per spec/004 §2 L264: the default behaviour **without** a `:path`
  argument MUST be a tree-summary (mechanism W-4 — `:summary` mode),
  not the full payload. This namespace handles ONLY the path-arg
  surface: the W-4 default-summary posture is enforced by the sibling
  `summary` namespace's mode-resolver landing in W-4. The two
  mechanisms compose at the per-tool dispatcher: the dispatcher
  consults the parsed path; if non-nil, slice and ride the post-W-6
  walker output through `apply-to-result`; if nil, route through
  `summary/apply-to-result` instead.

  ## Out-of-range — deepest-valid-prefix

  Per spec/004 §2 L267-270: out-of-range paths return
  `:ok? false :reason :path-not-found` with the deepest valid prefix
  attached so the agent can re-aim. `deepest-valid-prefix` walks
  the path against the tree, returning the longest prefix that
  resolves. The walker handles map keys + sequential indices;
  anything else (a scalar at depth, a function value, etc.)
  terminates the walk.

  ## Path-arg vocabulary (cross-MCP)

  The parser accepts every shape pair2-mcp's `tools.args/parse-path-arg`
  accepts (the surface the agent has already learned), so an agent
  that learned `:path \"[:cart :items 0]\"` on pair2-mcp passes the
  same string to causa-mcp's `get-app-db` / `get-machine-state` /
  `get-epoch-history` tools unchanged.

    - a JS array of strings — each parsed as EDN; non-EDN entries
      stay as strings.
    - a CLJS vector — passed through.
    - an EDN-encoded string — `read-string`'d (e.g.
      `\"[:cart :items 3 :sku]\"`).
    - nil / missing — nil (no path slicing).

  ## Composition with W-1 / W-6 / B-1

  - **With W-1 (token-cap)**: path slicing typically reduces payload
    size enough to stay under the cap. When even the sliced subtree
    overflows, W-1's `apply-cap` trips with `:hint :slice` (further
    drill) or `:slice` redux. The composition is the canonical
    spec/004 §2 → §1 cascade: slice first, cap as backstop.
  - **With W-6 (size-elision)**: the walker runs server-side INSIDE
    the eval form; this namespace operates on the already-walked
    value. `apply-to-result` calls `elision/apply-to-result` to
    count + stamp the `:elided-large` counter. Tools wire both
    boundary wrappers in a single envelope.
  - **With B-1 (privacy)**: orthogonal — privacy lives on the
    trace-stream surface; path slicing lives on the direct-read
    surface. They don't compose at the per-call site, but the
    cross-MCP shape `:include-sensitive?` opt-in slot stays
    consistent across all of them.

  ## MUSTs honoured

  - MUST 8 — every direct-read tool MUST accept the `:path` arg
    (spec/004 §2). `parse-path-arg` is the single normative
    accept-shape parser; the per-tool dispatcher calls it once
    on the raw args object.
  - MUST 9 (default-mode-is-summary half) — the parser returns
    `nil` for absent `:path`, signalling the dispatcher to take
    the summary branch. This namespace owns the `nil` semantics;
    the summary branch itself is W-4."
  (:require [applied-science.js-interop :as j]
            [cljs.reader]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Path-arg accept-shape parser — cross-MCP vocabulary.
;;
;; Mirrors pair2-mcp's `tools.args/parse-path-arg` accept-shape table
;; (rf2-tygdv). Same input shapes, same output (a CLJS vector or nil),
;; same fallback semantics. Single namespace here so the per-tool
;; dispatcher requires one ns for path-arg concerns.
;; ---------------------------------------------------------------------------

(defn coerce-path-segment
  "Coerce one segment of a JS-array path argument.

  Try `read-string`; on any failure (the bare identifier case —
  `\"items\"` would otherwise read as a symbol, which is the wrong
  `get-in` key) fall through as the original string. `read-string`
  parses EDN literals (`\":cart\"` ⇒ `:cart`, `\"0\"` ⇒ `0`,
  `\"-1\"` ⇒ `-1`) and rejects anything else, so the catch-fallback
  IS the discriminator — no first-char heuristic needed."
  [s]
  (if-not (string? s)
    s
    (let [trimmed (str/trim s)
          parsed  (try (cljs.reader/read-string trimmed)
                       (catch :default _ ::reader-fail))]
      (cond
        (= ::reader-fail parsed) s
        ;; Symbols are the reader's "bare identifier" outcome — not a
        ;; valid `get-in` key on a map keyed by strings or keywords;
        ;; keep the original string instead so `{\"items\" ...}` works.
        (symbol? parsed)         s
        :else                    parsed))))

(defn parse-path-arg
  "Normalise the `:path` MCP arg into a CLJS vector suitable for
  `get-in`. Returns `nil` when the path is absent (signalling the
  dispatcher to take the summary branch per MUST 9). Returns `[]`
  for an explicit empty path (root). Unparseable strings fall
  through as single-segment string paths — `get-in` then treats
  them as map keys.

  Accepts every shape pair2-mcp's `tools.args/parse-path-arg`
  accepts (the cross-MCP path-arg vocabulary):

    - JS array of strings — each parsed as EDN; non-EDN entries
      stay as strings (`#js [\":cart\" \":items\" \"0\"]` ⇒
      `[:cart :items 0]`).
    - CLJS vector — pass through.
    - CLJS sequential — coerced to a vector.
    - EDN-encoded string — `read-string`'d
      (`\"[:cart :items 3 :sku]\"` ⇒ `[:cart :items 3 :sku]`).
    - nil / missing — `nil`.
    - blank / whitespace string — `nil`."
  [raw]
  (cond
    (nil? raw) nil
    (vector? raw) raw
    (sequential? raw) (vec raw)
    (array? raw) (mapv coerce-path-segment (js->clj raw))
    (string? raw)
    (let [trimmed (str/trim raw)]
      (cond
        (str/blank? trimmed) nil
        :else
        (try
          (let [parsed (cljs.reader/read-string trimmed)]
            (cond
              (vector? parsed)     parsed
              (sequential? parsed) (vec parsed)
              :else                [parsed]))
          (catch :default _
            ;; Unparseable; treat the whole string as a single segment.
            [trimmed]))))
    :else nil))

(defn path-arg
  "Extract the cross-server `:path` MCP arg from a raw arguments
  object and normalise it. Returns a CLJS vector or `nil`.

  Accepts both the JS-side args object (the npm MCP SDK shape) and
  a CLJS map (already-coerced upstream). The slot name is the cross-
  server `path` (string key for JS, `:path` for CLJS) per spec/004
  §2.

  Unrecognised / absent inputs collapse to `nil` (the signal for
  the dispatcher to take the W-4 summary branch)."
  [args]
  (let [raw (cond
              (or (nil? args) (undefined? args))
              nil

              (map? args)
              (or (get args :path)
                  (get args "path"))

              :else
              (let [v (j/get args "path")]
                (when-not (or (nil? v) (undefined? v)) v)))]
    (parse-path-arg raw)))

;; ---------------------------------------------------------------------------
;; deepest-valid-prefix — walker for the :path-not-found re-aim hint.
;; ---------------------------------------------------------------------------

(defn deepest-valid-prefix
  "Walk `path` against `tree` and return the deepest prefix that
  resolves. Used in `:path-not-found` errors so the agent can re-aim
  without a binary search. Handles map keys + sequential indices;
  anything else (a scalar at depth, a function value, etc.) terminates
  the walk.

  Returns a vector — possibly empty (no prefix resolved) up to the
  full path (every segment resolved; the call-site treats that as a
  successful resolution, not a `:path-not-found`)."
  [tree path]
  (loop [acc [] cur tree remaining (seq path)]
    (if-not remaining
      acc
      (let [k (first remaining)]
        (cond
          (and (map? cur) (contains? cur k))
          (recur (conj acc k) (get cur k) (next remaining))

          (and (sequential? cur)
               (integer? k)
               (<= 0 k (dec (count (if (counted? cur) cur (vec cur))))))
          (recur (conj acc k)
                 (nth (if (vector? cur) cur (vec cur)) k)
                 (next remaining))

          :else acc)))))

;; ---------------------------------------------------------------------------
;; path-not-found — the structured error result per spec/004 §2 L267.
;; ---------------------------------------------------------------------------

(defn path-not-found
  "Build the structured `:path-not-found` result map per spec/004 §2
  L267-270. Shape:

      {:ok?                   false
       :reason                :path-not-found
       :path                  <requested-path>
       :deepest-valid-prefix  <prefix-vector>}

  The agent reads `:deepest-valid-prefix` to re-aim without a
  binary-search — re-call with a path equal to (or one step into)
  the prefix.

  The `:reason :path-not-found` keyword is the cross-MCP convention
  for re-aim errors on tree-typed direct-read tools; agents pattern-
  match on it. (Sibling to `:rf.mcp/cursor-stale` on the
  cursor-pagination side — pinned in `re-frame.mcp-base.vocab`; this
  reason key stays unqualified because it's per-tool, not a cross-
  cutting wire-mechanism marker.)"
  [tree path]
  {:ok?                   false
   :reason                :path-not-found
   :path                  path
   :deepest-valid-prefix  (deepest-valid-prefix tree path)})

;; ---------------------------------------------------------------------------
;; resolve-path — sentinel-aware `get-in` for path slicing.
;;
;; The sentinel disambiguates a path that legitimately resolves to nil
;; from one that doesn't resolve at all. Both pair2-mcp's get-path
;; eval form and this host-side equivalent use the same trick: a fresh
;; identity-comparable sentinel value, returned by `get-in` only when
;; the path was missing.
;; ---------------------------------------------------------------------------

(def ^:private not-found-sentinel
  "Identity-comparable marker for `resolve-path`. Reified once at ns
  load so `identical?` resolves cheaply (a fresh `#js {}` on each
  call would mean `identical?` is a pointer compare, but the fresh
  value would prevent any pooling). Module-private — never escapes
  this ns."
  #js {})

(defn resolve-path
  "Resolve `path` against `tree`. Returns either the addressed
  subtree (which may legitimately be `nil`) or `::not-found` when
  the path is out of range.

  Disambiguates `nil-at-path` (a path that resolves to a value of
  `nil` — valid resolution) from `not-found` (a path that doesn't
  resolve at all — out-of-range, triggers `:path-not-found`) via
  an identity sentinel that `get-in` returns iff the path is missing."
  [tree path]
  (let [v (get-in tree path not-found-sentinel)]
    (if (identical? v not-found-sentinel)
      ::not-found
      v)))

;; ---------------------------------------------------------------------------
;; apply-to-result — per-tool boundary wrapper for direct-read tools.
;;
;; Tools that take a `:path` arg call this once at the end of their
;; body, with the already-walked tree (post-W-6 elision) + the parsed
;; path + the in-progress envelope. The helper resolves the path:
;;
;;   - If the path resolves, writes the addressed subtree under
;;     `value-key` on the envelope and returns the envelope.
;;   - If the path is out-of-range, splices the `:path-not-found`
;;     shape onto the envelope (slot-by-slot — `:ok?`, `:reason`,
;;     `:path`, `:deepest-valid-prefix`) and returns it WITHOUT
;;     writing under `value-key` (the slot is meaningless on a
;;     `:path-not-found` response).
;;
;; The wrapper does NOT call into W-6 — the dispatcher already
;; walked the tree server-side via the eval form. This wrapper is
;; pure host-side path resolution.
;;
;; ## Why splice rather than replace
;;
;; The envelope already carries cursor / mode / cache / tool-name
;; slots the dispatcher set upstream. Replacing the envelope with
;; just the `:path-not-found` map would lose them; splicing keeps
;; the dispatcher's other context (the agent still sees the
;; `:tool`, the `:mode` it picked, etc.) alongside the error.
;; ---------------------------------------------------------------------------

(defn apply-to-result
  "Apply the spec/004 §2 path-slice resolution to `tree` and write
  the result back into `envelope` under `value-key`. Returns the
  updated envelope.

  Arguments:
    - `envelope`   — the per-call result map (will be updated).
    - `value-key`  — the slot in `envelope` the resolved subtree
                     goes into (e.g. `:db` for `get-app-db`,
                     `:state` for `get-machine-state`, `:epochs`
                     for `get-epoch-history`).
    - `tree`       — the already-walked tree-typed payload (the
                     eval form ran `re-frame.core/elide-wire-value`
                     server-side; the marker substitution is
                     already in place).
    - `path`       — a CLJS vector of keys / indices (the output
                     of `parse-path-arg`). May be `[]` (root) or
                     a deep path.

  Returns the envelope with `value-key` set to the addressed
  subtree when the path resolves, or with the `:path-not-found`
  slots spliced in when out-of-range."
  [envelope value-key tree path]
  (let [resolved (resolve-path tree path)]
    (if (= ::not-found resolved)
      (merge envelope (path-not-found tree path))
      (assoc envelope value-key resolved))))
