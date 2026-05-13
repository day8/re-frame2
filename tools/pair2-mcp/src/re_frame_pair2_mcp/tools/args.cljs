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
            [clojure.string :as str]))

(def valid-slices
  #{:app-db :sub-cache :machines :epochs :traces})

(defn ->frame-keyword
  "Coerce a frame-id string into a keyword. Accepts both bare names
   (`\"rf/default\"`) and EDN-shaped strings (`\":rf/default\"`) — strips
   a leading colon when present so callers can pass either form."
  [x]
  (cond
    (keyword? x) x
    (string? x)
    (let [s (if (str/starts-with? x ":") (subs x 1) x)]
      (keyword s))
    :else (keyword x)))

(defn coerce-path-segment
  "Coerce one segment of a JS-array path argument.

  Heuristic: an EDN-shaped string is parsed (`\":cart\"` ⇒ `:cart`,
  `\"0\"` ⇒ `0`, `\"-1\"` ⇒ `-1`), but a bare identifier
  (`\"items\"`, `\"bare-key\"`) stays a string — the default reader
  would otherwise coerce it to a symbol, which is a different
  `get-in` key. Trigger characters are the EDN literal openers `:`
  (keyword), a leading digit, or `-`/`+` (signed number); anything
  else falls through as a plain string."
  [s]
  (if-not (string? s)
    s
    (let [trimmed   (str/trim s)
          fc        (when (pos? (count trimmed)) (.charAt trimmed 0))
          edn-shape (and fc
                         (or (= ":" fc)
                             (= "-" fc)
                             (= "+" fc)
                             (boolean (re-matches #"\d" fc))))]
      (if edn-shape
        (try (cljs.reader/read-string trimmed)
             (catch :default _ s))
        s))))

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
  (budget-sensitive default)."
  [raw]
  (cond
    (nil? raw)                 :summary
    (= raw :full)              :full
    (= raw :summary)           :summary
    (= raw "full")             :full
    (= raw "summary")          :summary
    :else                      :summary))

(defn parse-modes-arg
  "Normalise the per-slice `modes` MCP arg into a `{<slice-keyword>
  <mode-keyword>}` map. Accepts a JS object, a CLJS map, or nil.
  Unknown slices are dropped. Unknown mode values are dropped (the
  slice falls back to the global mode default). Slice keys may be
  bare strings (`\"app-db\"`), EDN-shaped strings (`\":app-db\"`),
  or keywords."
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
                 :else nil)
        coerce-k (fn [k]
                   (cond
                     (keyword? k) k
                     (string? k)
                     (let [s (if (str/starts-with? k ":") (subs k 1) k)]
                       (keyword s))
                     :else nil))
        coerce-v (fn [v]
                   (cond
                     (= v :summary)  :summary
                     (= v :full)     :full
                     (= v "summary") :summary
                     (= v "full")    :full
                     :else           nil))]
    (if-not (map? as-clj)
      {}
      (reduce-kv
        (fn [m k v]
          (let [k' (coerce-k k)
                v' (coerce-v v)]
            (if (and k' (contains? valid-slices k') v')
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
