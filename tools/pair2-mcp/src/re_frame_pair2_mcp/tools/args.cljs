(ns re-frame-pair2-mcp.tools.args
  "Argument-coercion helpers for the snapshot / get-path / subscribe
  family — path vectors, frame ids, slice include lists, summary modes,
  per-slice mode maps, and the streaming filter map.

  Path-arg parsing (rf2-tygdv): two tools take a `:path` argument:
  `snapshot` (slice the :app-db slice) and `get-path` (direct
  read-by-path). Same parser, same semantics so agents learn the shape
  once.

  Accepted shapes from the MCP host:
    - JS array of strings  ⇒ each entry parsed as EDN; non-EDN entries
                             stay as strings.
    - CLJS vector          ⇒ pass through.
    - EDN-encoded string   ⇒ read-string (e.g. `\"[:cart :items 3 :sku]\"`).
    - nil / missing        ⇒ nil (no path slicing).

  Mirrors the causa-mcp wire-protocol Principles §\"2. Path slicing\"
  convention: a path is an EDN-encoded vector of keys addressing a
  subtree. The vocabulary is shared across pair2-mcp / causa-mcp /
  story-mcp so agents recognise the surface once."
  (:require [cljs.reader]
            [clojure.string :as str]
            [re-frame.mcp-base.args :as base-args]))

(def valid-slices
  #{:app-db :sub-cache :machines :epochs :traces})

(defn ->frame-keyword
  "Coerce a frame-id string into a keyword. Accepts both bare names
   (`\"rf/default\"`) and EDN-shaped strings (`\":rf/default\"`) — strips
   a leading colon when present so callers can pass either form.

   Delegates to `re-frame.mcp-base.args/parse-keyword` (rf2-vw4sq) so
   the slice-key / frame-key coercion is single-sourced across the
   pair2-mcp wire surface — same helper underpins both
   `parse-frames-arg` and the per-slice-mode key coercion in
   `parse-modes-arg`."
  [x]
  (base-args/parse-keyword x))

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
        ;; keep the original string instead so `{"items" ...}` works.
        (symbol? parsed)         s
        :else                    parsed))))

(defn parse-path-arg
  "Normalise the `path` MCP arg into a CLJS vector suitable for
   `get-in`. Returns `nil` when the path is absent. Returns `[]` for an
   explicit empty path (root). Unparsable strings fall through as
   strings — `get-in` will then treat them as map keys."
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

(defn parse-frames-arg
  "Normalise the `frames` MCP arg into the form the runtime expects.
   Accepts `:all`, the string \"all\", a JS array of strings, or a CLJS
   vector. Returns `:all` or a vector of keyword frame-ids. Returns
   `:all` for nil / empty / unrecognised input — least-surprise."
  [raw]
  (cond
    (nil? raw) :all
    (or (= raw :all) (= raw "all")) :all
    (array? raw)
    (->> (js->clj raw) (mapv ->frame-keyword))
    (sequential? raw)
    (mapv ->frame-keyword raw)
    :else :all))

(defn parse-include-arg
  "Normalise the `include` MCP arg into the slice vector the runtime
   expects. Filters to known slices; returns the full set when arg
   is nil / empty / all-unknown."
  [raw]
  (let [full [:app-db :sub-cache :machines :epochs :traces]
        coerce (fn [xs]
                 (->> xs
                      (map keyword)
                      (filter valid-slices)
                      vec))]
    (cond
      (nil? raw) full
      (array? raw)
      (let [v (coerce (js->clj raw))]
        (if (seq v) v full))
      (sequential? raw)
      (let [v (coerce raw)]
        (if (seq v) v full))
      :else full)))

(defn parse-mode-arg
  "Normalise the global `mode` MCP arg. Accepts strings (`\"summary\"`,
  `\"full\"`) or keywords. Defaults to `:summary` — the lazy-summary
  default per rf2-u2029. Unrecognised values default to `:summary`
  (budget-sensitive default).

  Delegates to `re-frame.mcp-base.args/parse-mode` (rf2-vw4sq)."
  [raw]
  (base-args/parse-mode raw :summary #{:summary :full}))

(defn parse-modes-arg
  "Normalise the per-slice `modes` MCP arg into a `{<slice-keyword>
  <mode-keyword>}` map. Accepts a JS object, a CLJS map, or nil.
  Unknown slices are dropped. Unknown mode values are dropped (the
  slice falls back to the global mode default). Slice keys may be
  bare strings (`\"app-db\"`), EDN-shaped strings (`\":app-db\"`),
  or keywords.

  Slice-key coercion delegates to `->frame-keyword` (which routes
  through `re-frame.mcp-base.args/parse-keyword`); per-slice mode
  coercion delegates to `re-frame.mcp-base.args/parse-mode` with a
  sentinel default so unrecognised values can be detected and
  dropped rather than coerced to the global default (rf2-vw4sq)."
  [raw]
  (let [as-clj (cond
                 (nil? raw)            nil
                 (map? raw)            raw
                 ;; JS object from the MCP wire.
                 (and (some? raw)
                      (not (array? raw))
                      (not (string? raw))
                      (not (boolean? raw))
                      (not (number? raw)))
                 (try (js->clj raw) (catch :default _ nil))
                 :else nil)]
    (if-not (map? as-clj)
      {}
      (reduce-kv
        (fn [m k v]
          (let [k' (->frame-keyword k)
                v' (base-args/parse-mode v ::unknown #{:summary :full})]
            (if (and k'
                     (contains? valid-slices k')
                     (not= v' ::unknown))
              (assoc m k' v')
              m)))
        {} as-clj))))

(defn parse-filter-arg
  "MCP-side filter arg can be either a JS object or an EDN string. We
  accept both for ergonomic parity with the bash-shim chain (`pred`
  has been a JSON object there). Returns an EDN-printable map or nil
  when missing."
  [raw]
  (cond
    (nil? raw)        nil
    (string? raw)     (try (cljs.reader/read-string raw)
                           (catch :default _
                             {:invalid-filter-edn raw}))
    (map? raw)        raw
    :else             (js->clj raw :keywordize-keys true)))
