(ns day8.re-frame2-machines-viz.scxml
  "SCXML (W3C State Chart XML) import/export for re-frame2 machine
  definitions (rf2-6urjd · v1.1).

  SCXML is the W3C standard for statecharts. Round-tripping through
  SCXML lets re-frame2 machines be shared with non-CLJS tooling —
  external workflow systems, Erlang `gen_statem`-derived tools,
  Stately's importers, the xstate-visualizer. Same pure-data posture
  as `mermaid.cljc`: a machine definition in, an XML string out;
  and the inverse on the read side.

  ## Input / output

  Two public fns:

  - `(spec->scxml machine-spec)` — produces an SCXML XML string for
    the given normalised machine definition (the same shape
    `(rf/machine-meta id)` returns).
  - `(scxml->spec scxml-string)` — parses an SCXML XML string into a
    re-frame machine spec.

  Round-trip is exact for the supported subset:

  ```clojure
  (= machine-spec (-> machine-spec spec->scxml scxml->spec))
  ```

  ## Supported subset

  The SCXML mapping intentionally covers the static topology that
  has a direct SCXML equivalent. Other features (timer countdowns,
  `:spawn-all` rows, microstep semantics) survive as labelled edges
  but lose their runtime affordance — the same lossy-by-design
  posture the Mermaid emitter takes.

  | Re-frame2 | SCXML mapping |
  |---|---|
  | `:initial`                            | `<scxml initial=\"...\">` |
  | `:states` (flat)                      | `<state id=\"...\">` |
  | `:states` (compound)                  | nested `<state>` with `initial` |
  | `:final? true`                        | `<final id=\"...\">` |
  | `:on {:event :target}`                | `<transition event=\"event\" target=\"target\"/>` |
  | `:on {:event {:target ... :guard G}}` | `<transition cond=\"G\" .../>` |
  | `:after {ms :target}`                 | `<transition event=\"after.ms\" target=\"target\"/>` |
  | `:always [...]`                       | `<transition target=\"...\"/>` (eventless) |
  | `{:type :parallel :regions ...}`      | `<parallel>` containing region `<state>`s |
  | Namespaced ids (`:auth/login`)        | `auth__login` (hex-escaped; `__` separates ns/name) |
  | Multi-dot-ns ids (`:my.app/login`)    | `my_2eapp__login` (dots in the ns are escaped to `_2e`) |
  | Vector-path targets (`[:parent :child]`) | `parent___child` (`___` joins path segments) |
  | Nested-state ids                      | FULLY QUALIFIED (root→leaf, `___`-joined) — unique xsd:ID |

  ## Id codec — injective, xsd:ID-conformant (rf2-mnp93.1/.7)

  SCXML state ids are `xsd:ID` (XML NCName): letters / digits / `-` `.`
  `_`, NO `:`. The codec hex-escapes every keyword ns/name char to
  `_<2-hex>` (`.` → `_2e`, `?` → `_3f`, `_` → `_5f`) and uses two
  reserved markers the escaper can provably never emit — `__` between a
  keyword's namespace and name, `___` between vector-path segments — so:

  - ANY keyword round-trips EXACTLY, regardless of how many dots its
    namespace or name has (`:my.app.auth/login` ≠ `:my/app.auth.login`
    now produce DISTINCT ids — the pre-mnp93.1 `.`-as-ns/name scheme
    collided them; csq75's `:`-as-path was not a valid xsd:ID char).
  - State ids are FULLY QUALIFIED with their nesting path, so two
    same-named nested states emit UNIQUE xsd:IDs and transition targets
    reference those same unique ids (rf2-mnp93.7 — conformant for
    external SCXML tooling).
  - User events named `after.*` / `done.state.*` no longer collide with
    the synthetic timer / `:on-done` encodings: those carry a LITERAL
    `.` the codec never emits for a real keyword (rf2-mnp93.3).

  ## Not supported (lossy or omitted)

  - `:spawn-all` rows — omitted; the parent state renders without
    spawn affordances.
  - Machine-level (top-level) `:on` fallback transitions — W3C SCXML
    has no clean root-fallback slot (`<scxml>` does not host
    `<transition>` children per the schema, and the import side drops
    root-level transitions), so these are exported as a documenting
    XML comment and do **not** round-trip back through `scxml->spec`.
  - `:tags` — re-frame2-specific; not part of W3C SCXML.
  - `:action`s and guard FN bodies — only the *names* survive
    (SCXML `cond=\"name\"` for guards; entry/exit `<script>` would
    require evaluation context, so names are preserved as XML
    comments on imports/exports).
  - Source-coord metadata — stripped at export time (same posture as
    share-URL encoding; see `Principles.md` §No session data in shares).

  Round-trip failure modes throw `(ex-info ... {:reason :scxml/...})`
  with a `:reason` keyword for programmatic dispatch.

  Per [`API.md`](../../spec/API.md) §SCXML import/export."
  (:require [clojure.string :as str]
            ;; rf2-bs3us — share the canonical parallel-root done-state id
            ;; with the chart projector so the two emitters agree on the
            ;; `done.state.<id>` label (single source of truth).
            [day8.re-frame2-machines-viz.chart.layout :as layout]))

;; ---------------------------------------------------------------------------
;; Id codec — FULLY INJECTIVE, xsd:ID-conformant (rf2-mnp93.1)
;;
;; Re-frame2 ids are keywords (`:idle`, `:auth/login-flow`,
;; `:my.app.auth/login`) or vector paths (`[:authenticated :browsing]`).
;; SCXML state ids are `xsd:ID` (XML NCName): the leading char must be a
;; letter / `_`, subsequent chars letters / digits / `-` `.` `_`. A `:`
;; is NOT a valid NCName char — so the pre-mnp93.1 `:`-as-path-separator
;; scheme (rf2-csq75) emitted non-conformant ids that strict external
;; SCXML consumers reject (rf2-mnp93.7), and the `.`-as-ns/name scheme
;; was NON-INJECTIVE: a keyword namespace may itself contain dots
;; (`:my.app.auth/login` → `"my.app.auth.login"`), so the decoder could
;; not recover where the namespace ended (rf2-mnp93.1).
;;
;; FIX — hex-escape codec (the same injective scheme `chart/layout`'s
;; `node-id` uses for xyflow ids). EVERY non-alphanumeric char in a
;; segment is escaped to `_<2-hex>` (so a literal `_` → `_5f`, `.` →
;; `_2e`, `?` → `_3f`). After escaping, an `_` appears ONLY as the lead
;; of a `_XX` hex triple — never two underscores in a row — so we can use
;; consecutive-underscore RESERVED MARKERS the escaper can provably never
;; emit:
;;
;; - `__`  (DOUBLE underscore) joins a keyword's NAMESPACE and NAME:
;;   `:auth/login`         → `"auth__login"`
;;   `:my.app.auth/login`  → `"my_2eapp_2eauth__login"`
;;   (the dots in the ns are escaped to `_2e`; the SINGLE `__` is the
;;   unambiguous ns/name boundary regardless of how many dots the ns has)
;; - `___` (TRIPLE underscore) joins the SEGMENTS of a vector path:
;;   `[:authenticated :browsing]` → `"authenticated___browsing"`
;;   `[:auth/region :browsing]`   → `"auth__region___browsing"`
;;
;; Decode is unambiguous: split on `___` (longest marker first) to
;; recover vector-path segments, then split each segment on `__` to
;; recover ns vs name, then `_XX`-unescape each part. Because the escaper
;; never emits `__` or `___`, neither boundary can collide with segment
;; content for ANY keyword (multi-dot ns, dotted name, `/` in ns/name) —
;; the codec is fully injective and every emitted id is a valid xsd:ID.

(def ^:private ns-name-sep
  "rf2-mnp93.1 — the keyword NAMESPACE↔NAME boundary in an SCXML id. `__`
  (double underscore) is impossible for `escape-id-segment` to emit (it
  only ever emits `_` + 2 hex digits), so it is the injective ns/name
  marker. Supersedes csq75's `.`-as-ns/name (non-injective for multi-dot
  namespaces)."
  "__")

(def ^:private path-segment-sep
  "rf2-mnp93.1 — the vector-PATH segment boundary in an SCXML id. `___`
  (triple underscore) the escaper can never emit and is distinct from the
  `__` ns/name marker, so a vector path can never collide with a single
  namespaced keyword. Supersedes csq75's `:` (not a valid xsd:ID char —
  rf2-mnp93.7)."
  "___")

(defn- escape-id-segment
  "Escape one keyword ns/name part INJECTIVELY into an xsd:ID-safe
  string: every char outside `[A-Za-z0-9]` becomes `_<2-hex>` (the
  underscore itself → `_5f`). Distinct inputs always yield distinct
  outputs and the result contains no two consecutive underscores, so the
  `__` / `___` reserved markers can never arise from segment content.
  Mirrors `chart/layout/escape-id-segment` (single injective scheme
  across all machines-viz id emitters)."
  [s]
  (str/join
    (map (fn [ch]
           (if (re-matches #"[A-Za-z0-9]" (str ch))
             (str ch)
             (str "_" #?(:clj  (format "%02x" (int ch))
                         :cljs (let [h (.toString (.charCodeAt (str ch) 0) 16)]
                                 (if (= 1 (count h)) (str "0" h) h))))))
         s)))

(defn- unescape-id-segment
  "Inverse of `escape-id-segment`: replace every `_<2-hex>` triple with
  the char it encodes."
  [s]
  (str/replace s
               #"_([0-9a-fA-F]{2})"
               (fn [[_ hex]]
                 (str (char #?(:clj  (Integer/parseInt hex 16)
                               :cljs (js/parseInt hex 16)))))))

(defn- keyword->id-string
  "Map a single keyword to its injective, xsd:ID-conformant SCXML id
  string. Namespace and name are each `escape-id-segment`-escaped and
  joined with `__` (`ns-name-sep`); a bare keyword emits just its escaped
  name. rf2-mnp93.1 — exact round-trip for ANY keyword (multi-dot ns,
  dotted name, `?`/`-`/`_` in either)."
  [k]
  (if-let [ns (namespace k)]
    (str (escape-id-segment ns) ns-name-sep (escape-id-segment (name k)))
    (escape-id-segment (name k))))

(defn- path->id-string
  "Map a re-frame2 id (keyword or vector path) to a single SCXML id
  string. A keyword uses `keyword->id-string`; a vector path joins its
  per-segment id strings with `___` (`path-segment-sep`). The `__`
  ns/name and `___` path markers never collide with each other or with
  segment content (rf2-mnp93.1), so the codec is fully injective."
  [id]
  (cond
    (keyword? id) (keyword->id-string id)
    (vector? id)  (str/join path-segment-sep (map keyword->id-string id))
    (string? id)  id
    :else         (str id)))

(defn- id-string->keyword
  "Inverse of `keyword->id-string` for a SINGLE keyword segment (no `___`
  path separator). Splits on the `__` ns/name marker (at most once),
  `_XX`-unescaping each part: `\"auth__login\"` → `:auth/login`,
  `\"my_2eapp_2eauth__login\"` → `:my.app.auth/login`, bare
  `\"name\"` → `:name`. rf2-mnp93.1 / rf2-mnp93.2 — the symmetric inverse
  used by BOTH the id decoder and the guard `cond=` decoder."
  [s]
  (when s
    (let [idx (str/index-of s ns-name-sep)]
      (if (nil? idx)
        (keyword (unescape-id-segment s))
        (keyword (unescape-id-segment (subs s 0 idx))
                 (unescape-id-segment (subs s (+ idx (count ns-name-sep)))))))))

;; ---------------------------------------------------------------------------
;; XML emit — string-based, no external library

(defn- escape-xml-attr
  "Escape a string for safe inclusion inside an XML attribute value."
  [s]
  (-> s
      (str/replace "&"  "&amp;")
      (str/replace "<"  "&lt;")
      (str/replace ">"  "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'"  "&apos;")))

(defn- indent-str
  [depth]
  (apply str (repeat depth "  ")))

(defn- transition-candidates
  "Normalise a transition spec to candidate maps. Same grammar
  walker as `mermaid.cljc/transition-candidates`."
  [spec]
  (cond
    (keyword? spec) [{:target spec}]
    (vector? spec)  (if (every? keyword? spec)
                      [{:target spec}]
                      (mapcat transition-candidates spec))
    (map? spec)     [spec]
    :else           []))

;; --- path qualification (rf2-mnp93.7) ------------------------------------
;;
;; W3C SCXML state ids are `xsd:ID` — they MUST be unique across the whole
;; document. The pre-mnp93.7 emitter wrote BARE local-state names, so two
;; states sharing a name under different compound parents collided
;; (duplicate `<state id="idle">`) — invalid SCXML a conformant external
;; consumer rejects. We now emit the FULLY-QUALIFIED path id (root → leaf,
;; `___`-joined via `path->id-string`) for every state, `initial`, and
;; transition `target`, so every id is unique. The decoder reverses the
;; qualification: a state block's local `:states` key is the LAST segment
;; of its qualified id, and a target resolves back to the relative form
;; (a sibling keyword when it shares the source's parent, else the
;; absolute vector path) so the round-trip is exact.

(defn- target-path?
  "True when `v` is a grammar VECTOR-PATH target (a vector of keywords)."
  [v]
  (and (vector? v) (seq v) (every? keyword? v)))

(defn- parent-path [path] (if (seq path) (pop path) []))

(defn- resolve-target-path
  "Resolve a transition target to its ABSOLUTE path from the machine
  root. A keyword target is a SIBLING of the source state (Spec 005); a
  vector-path target is absolute. `source-path` is the source state's
  absolute path."
  [source-path target]
  (cond
    (keyword? target)     (conj (parent-path source-path) target)
    (target-path? target) (vec target)
    :else                 nil))

(defn- qualified-id
  "The unique xsd:ID for a state at absolute `path` (rf2-mnp93.7)."
  [path]
  (path->id-string (vec path)))

(defn- emit-transition
  "Emit a `<transition>` line for one candidate. `source-path` is the
  absolute path of the OWNING state; targets are path-qualified against
  it so the emitted `target` is the unique xsd:ID of the destination
  (rf2-mnp93.7)."
  [event-name {:keys [target guard action]} source-path depth]
  (let [target-id (when target
                    (qualified-id (resolve-target-path source-path target)))
        parts (cond-> []
                event-name (conj (str "event=\"" (escape-xml-attr event-name) "\""))
                target-id  (conj (str "target=\"" (escape-xml-attr target-id) "\""))
                guard      (conj (str "cond=\"" (escape-xml-attr (keyword->id-string guard)) "\"")))
        attrs (str/join " " parts)
        self-close? (nil? action)]
    (str (indent-str depth)
         (if self-close?
           (str "<transition " attrs "/>")
           (str "<transition " attrs ">"
                "<!-- action: " (escape-xml-attr (keyword->id-string action)) " -->"
                "</transition>")))))

(defn- emit-transitions-for-on
  [on-map source-path depth]
  (mapcat (fn [[event spec]]
            (map #(emit-transition (keyword->id-string event) % source-path depth)
                 (transition-candidates spec)))
          on-map))

(defn- emit-transitions-for-after
  [after-map source-path depth]
  (mapcat (fn [[delay spec]]
            (map #(emit-transition (str "after." (if (keyword? delay)
                                                  (keyword->id-string delay)
                                                  delay))
                                   % source-path depth)
                 (transition-candidates spec)))
          after-map))

(defn- emit-transitions-for-always
  [always source-path depth]
  (->> (transition-candidates always)
       (map #(emit-transition nil % source-path depth))))

(defn- emit-transitions-for-on-done
  "rf2-41goo — emit the compound / parallel `:on-done` (XState `onDone`)
  as W3C SCXML's `<transition event=\"done.state.<id>\" .../>`, placed
  inside the done node's own `<state>` element (SCXML §3.7: reaching a
  `<final>` child generates `done.state.<id>` into the internal queue; an
  enclosing transition takes it). `done-event` is the precomputed
  `\"done.state.<id-str>\"` for THIS node. A target-less candidate
  (action/fx-only — the parallel-root shape) emits a transition with NO
  `target` (the action survives as the emitter's `<!-- action -->`
  comment), faithful to the action-only completion the engine fires.
  `source-path` qualifies any target (rf2-mnp93.7)."
  [done-event on-done source-path depth]
  (->> (transition-candidates on-done)
       (map #(emit-transition done-event % source-path depth))))

(defn- emit-state
  "Emit a `<state>` (or `<final>`) block for one state-node. `path` is
  the absolute path (root → this state) used to emit unique xsd:IDs and
  to qualify transition targets (rf2-mnp93.7)."
  [path state-node depth]
  (let [{:keys [final? initial states on after always on-done]} state-node
        id-str (qualified-id path)
        tag    (if final? "final" "state")
        attrs  (cond-> (str "id=\"" (escape-xml-attr id-str) "\"")
                 (and (not final?) initial)
                 ;; rf2-mnp93.7 — `initial` references a CHILD by its
                 ;; unique qualified id (the child's absolute path).
                 (str " initial=\"" (escape-xml-attr (qualified-id (conj (vec path) initial))) "\""))
        children
        (concat
          (emit-transitions-for-on on path (inc depth))
          (emit-transitions-for-after after path (inc depth))
          (emit-transitions-for-always always path (inc depth))
          ;; rf2-41goo — the `done.state.<this-id>` completion transition
          ;; (XState `onDone`). Emitted INSIDE this node's own <state>.
          (when on-done
            (emit-transitions-for-on-done (str "done.state." id-str)
                                          on-done path (inc depth)))
          (mapcat (fn [[child-id child-node]]
                    (emit-state (conj (vec path) child-id) child-node (inc depth)))
                  states))]
    (if (seq children)
      (concat [(str (indent-str depth) "<" tag " " attrs ">")]
              children
              [(str (indent-str depth) "</" tag ">")])
      [(str (indent-str depth) "<" tag " " attrs "/>")])))

(defn- emit-machine-level-on
  "Emit the machine-level (top-level) `:on` fallback transitions
  (Spec 005 `005-StateMachines.md:181,199`) directly under `<scxml>`.

  rf2-ee38b.21 — these were previously dropped entirely (mirroring the
  parser bug). W3C SCXML has no clean root-fallback-transition slot
  (`<scxml>` does not host `<transition>` children per the schema, and
  the import side drops root-level transitions), so a perfect
  round-trip is impossible. Rather than silently lose the topology we
  emit them as `<transition>` elements wrapped in a documenting comment
  so the information survives the export and a human/tool reading the
  SCXML sees the machine-wide fallback."
  [on depth]
  (when (seq on)
    (concat
      [(str (indent-str depth)
            "<!-- machine-level (top-level) :on fallback transitions"
            " — inherited by every state (Spec 005 §top-level :on) -->")]
      ;; rf2-mnp93.7 — machine-level targets are siblings at the machine
      ;; root, so resolve them against the empty root path.
      (emit-transitions-for-on on [] depth))))

(defn- emit-flat-or-compound
  [{:keys [initial states on]} depth]
  (concat
    [(str (indent-str depth)
          "<scxml xmlns=\"http://www.w3.org/2005/07/scxml\""
          " version=\"1.0\""
          ;; rf2-mnp93.7 — the root `initial` references a TOP-LEVEL state
          ;; by its unique qualified id (a single-segment path, so the
          ;; bare name, identical to the pre-fix flat output).
          " initial=\"" (escape-xml-attr (qualified-id [initial])) "\">")]
    (emit-machine-level-on on (inc depth))
    (mapcat (fn [[child-id child-node]]
              (emit-state [child-id] child-node (inc depth)))
            states)
    [(str (indent-str depth) "</scxml>")]))

(def ^:private parallel-root-scxml-id
  "rf2-41goo — the SCXML id of the synthetic `<parallel>` element. Its
  W3C completion event is `done.state.<this-id>`.

  rf2-bs3us — sourced from the shared canonical sentinel
  (`layout/parallel-root-done-state-id`) so the SCXML emitter's
  `done.state.<id>` event and the chart projector's `:doneState` renderer
  label agree on the parallel-root done-state id."
  layout/parallel-root-done-state-id)

(defn- emit-parallel
  [{:keys [regions on-done]} depth]
  (concat
    [(str (indent-str depth)
          "<scxml xmlns=\"http://www.w3.org/2005/07/scxml\""
          " version=\"1.0\">")
     (str (indent-str (inc depth)) "<parallel id=\"" parallel-root-scxml-id "\">")]
    ;; rf2-41goo — the parallel-root `:on-done` (XState `onDone`):
    ;; `done.state.<parallel-id>` raised when ALL regions settle final.
    ;; A `:type :parallel` machine is root-only — registration rejects a
    ;; `:target`, so the transition is action/fx-only (no `target`); the
    ;; action survives as the emitter's `<!-- action -->` comment.
    (when on-done
      ;; The parallel-root :on-done is action-only (no target — a parallel
      ;; machine is root-only), so the source-path is unused; pass [].
      (emit-transitions-for-on-done (str "done.state." parallel-root-scxml-id)
                                    on-done [] (+ depth 2)))
    (mapcat (fn [[region-id region-node]]
              ;; Each region is a state with its own initial + states.
              ;; rf2-mnp93.7 — a region's path is rooted at the region id
              ;; (regions are the parallel's direct children).
              (emit-state [region-id] region-node (+ depth 2)))
            regions)
    [(str (indent-str (inc depth)) "</parallel>")
     (str (indent-str depth) "</scxml>")]))

;; ---------------------------------------------------------------------------
;; Public emit fn

(defn spec->scxml
  "Convert a re-frame2 machine spec to an SCXML XML string.

  `machine-spec` is the normalised definition shape `(rf/machine-meta
  id)` returns (per Spec 005 §Transition table grammar):

  ```clojure
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}}
  ```

  Or a parallel definition:

  ```clojure
  {:type :parallel
   :regions {:data { ... } :form { ... }}}
  ```

  Throws `(ex-info ... {:reason :scxml/invalid-spec})` if the spec
  is missing required keys.

  Round-trips through `scxml->spec`:

  ```clojure
  (= machine-spec (-> machine-spec spec->scxml scxml->spec))
  ```

  for the supported subset documented in the ns docstring. The
  equality is *not* exact for the lossy shapes the ns docstring lists
  under \"Not supported\" — notably a machine-level (top-level) `:on`
  fallback, which W3C SCXML cannot host as a root `<transition>`, so it
  is exported as a documenting comment and does **not** survive the
  parse back (alongside `:spawn-all`, `:tags`, action/guard bodies, and
  source-coord metadata)."
  [machine-spec]
  (cond
    (= :parallel (:type machine-spec))
    (let [{:keys [regions]} machine-spec]
      (when-not (and (map? regions) (seq regions))
        (throw (ex-info ":scxml/invalid-spec"
                        {:rf.error/id :scxml/invalid-spec
                         :where    'machines-viz/spec->scxml
                         :recovery :no-recovery
                         :reason   "parallel spec requires non-empty :regions"
                         :spec     machine-spec})))
      (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
           (str/join "\n" (emit-parallel machine-spec 0))))

    (and (:initial machine-spec) (map? (:states machine-spec)) (seq (:states machine-spec)))
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         (str/join "\n" (emit-flat-or-compound machine-spec 0)))

    :else
    (throw (ex-info ":scxml/invalid-spec"
                    {:rf.error/id :scxml/invalid-spec
                     :where    'machines-viz/spec->scxml
                     :recovery :no-recovery
                     :reason   "spec must carry :initial + non-empty :states, or :type :parallel + :regions"
                     :spec     machine-spec}))))

;; ---------------------------------------------------------------------------
;; XML parse — minimal regex-based reader for the SCXML subset we
;; emit. This deliberately doesn't try to be a full XML parser; it
;; round-trips our own output and consumes the common SCXML shapes
;; external tools emit (single-line tags, attribute order is free,
;; whitespace tolerated). For unsupported XML constructs (CDATA,
;; namespaces beyond the default scxml ns, processing instructions
;; other than the leading `<?xml ... ?>`) the parser is best-effort
;; and may throw.

(defn- strip-prolog
  "Drop the leading `<?xml ... ?>` declaration if present."
  [s]
  (str/replace s #"(?s)^\s*<\?xml[^?]*\?>\s*" ""))

(defn- strip-comments
  "Drop `<!-- ... -->` comments. Used by the parser to discard the
  action-name comments the emitter injects on transitions with
  actions (the action survives the round-trip as a comment because
  SCXML proper has no action-id-on-transition slot)."
  [s]
  (str/replace s #"(?s)<!--.*?-->" ""))

(defn- parse-attrs
  "Parse a space-separated attribute string from a start-tag into a
  map of string keys to unescaped string values. Tolerant of any
  attribute order; recognises both single and double quotes."
  [^String attr-string]
  (let [pattern #"(\w+)\s*=\s*\"([^\"]*)\""
        matches (re-seq pattern attr-string)]
    (into {}
          (map (fn [[_ k v]]
                 [k (-> v
                        (str/replace "&apos;" "'")
                        (str/replace "&quot;" "\"")
                        (str/replace "&gt;"   ">")
                        (str/replace "&lt;"   "<")
                        (str/replace "&amp;"  "&"))]))
          matches)))

(defn- unescape-id-string
  "Convert a SCXML id string back to a re-frame2 keyword (or vector
  path). Inverse of `path->id-string` (rf2-mnp93.1).

  The encoder uses two reserved markers the segment escaper can provably
  never emit, so decoding is topology-aware and fully injective:

  - `___` (`path-segment-sep`) joins VECTOR-PATH segments. Its presence
    is the injective marker of a vector path.
  - `__` (`ns-name-sep`) joins a namespaced keyword's namespace and name
    WITHIN a segment.

  So: an id containing `___` decodes to a vector of per-segment keywords
  (each segment via `id-string->keyword`); an id with NO `___` decodes to
  a single keyword (one `__` ⇒ namespaced, none ⇒ bare). Because the
  escaper never emits `__` or `___`, a vector path
  (`\"authenticated___browsing\"`) can never collide with a namespaced
  keyword (`\"authenticated__browsing\"`), and a multi-dot namespace
  (`\"my_2eapp_2eauth__login\"`) round-trips its dots exactly."
  [s]
  (when s
    (if (str/includes? s path-segment-sep)
      (mapv id-string->keyword (str/split s (re-pattern path-segment-sep)))
      (id-string->keyword s))))

;; --- qualified-id decode (rf2-mnp93.7) -----------------------------------

(defn- id-string->abs-path
  "Decode a qualified SCXML id (rf2-mnp93.7) into an ABSOLUTE path
  VECTOR. `unescape-id-string` returns a vector for a `___`-joined path
  and a bare keyword for a single segment; normalise both to a vector."
  [s]
  (let [decoded (unescape-id-string s)]
    (if (vector? decoded) decoded [decoded])))

(defn- abs-path->local-key
  "The LOCAL `:states` key for a state whose qualified id decodes to
  absolute `abs-path` — the last segment (rf2-mnp93.7)."
  [abs-path]
  (last abs-path))

(defn- decode-target
  "Reconstruct a transition target's RELATIVE grammar form from the
  qualified `target` id and the source state's absolute `source-path`
  (rf2-mnp93.7). A target whose parent equals the source's parent is a
  SIBLING — re-frame2 writes it as the bare keyword (the last segment);
  any other target is written as the absolute vector path. This inverts
  `resolve-target-path` so the round-trip is exact."
  [target-str source-path]
  (let [abs-path (id-string->abs-path target-str)]
    (if (= (parent-path (vec abs-path)) (parent-path (vec source-path)))
      (last abs-path)
      (vec abs-path))))

(defn- tokenize
  "Walk an XML string and return a flat seq of token maps:
  `{:kind :start :tag t :attrs {}}`, `{:kind :end :tag t}`,
  `{:kind :self :tag t :attrs {}}`. Discards whitespace and text
  (this subset has no text content)."
  [^String xml]
  (let [tag-re #"(?s)<(/?)\s*([A-Za-z][A-Za-z0-9_:-]*)\s*([^>]*?)(/?)>"]
    (->> (re-seq tag-re xml)
         (map (fn [[_ closing tag attrs self-close]]
                (cond
                  (= "/" closing) {:kind :end :tag tag}
                  (= "/" self-close) {:kind :self :tag tag :attrs (parse-attrs attrs)}
                  :else {:kind :start :tag tag :attrs (parse-attrs attrs)}))))))

(defn- direct-transitions
  "From a flat seq of tokens that compose one state's body, return
  only those `<transition>` tokens that are *direct* children — i.e.
  not nested inside a deeper `<state>` / `<final>` / `<parallel>`.
  Self-closing transitions don't contribute to depth."
  [tokens]
  (loop [remaining tokens
         depth     0
         acc       []]
    (if (empty? remaining)
      acc
      (let [t (first remaining)]
        (cond
          (= :self (:kind t))
          (recur (rest remaining)
                 depth
                 (if (and (zero? depth)
                          (= "transition" (:tag t)))
                   (conj acc t)
                   acc))

          (and (= :start (:kind t)) (= "transition" (:tag t)))
          ;; Non-self-closing transition (we emit them self-closing
          ;; for clean output, but be tolerant on the parse side).
          ;; We treat its content as not affecting state-block depth.
          (recur (rest remaining)
                 depth
                 (if (zero? depth) (conj acc t) acc))

          (and (= :end (:kind t)) (= "transition" (:tag t)))
          (recur (rest remaining) depth acc)

          (= :start (:kind t))
          (recur (rest remaining) (inc depth) acc)

          (= :end (:kind t))
          (recur (rest remaining) (dec depth) acc)

          :else
          (recur (rest remaining) depth acc))))))

(defn- consume-transitions
  "Walk an open `<state>` body's tokens and split out direct-child
  transitions vs the rest (which group-children-by-state will turn
  into nested state blocks). Returns `{:on ... :after ... :always
  [...] :children-tokens [...]}`. `source-path` is the OWNING state's
  absolute path — qualified targets are decoded relative to it
  (rf2-mnp93.7)."
  [child-tokens source-path]
  (let [ts              (direct-transitions child-tokens)
        ;; The remaining stream still contains the nested-state
        ;; tokens AND the direct-transition tokens (we filter the
        ;; latter out of children-tokens so they don't end up walked
        ;; as states).
        ts-set          (set ts)
        non-transitions (remove (fn [t]
                                  (or (ts-set t)
                                      ;; Drop the trailing </transition>
                                      ;; if a non-self-closing form was used.
                                      (and (= :end (:kind t))
                                           (= "transition" (:tag t)))))
                                child-tokens)
        coll
        (reduce
          (fn [acc t]
            (let [attrs    (:attrs t)
                  event    (get attrs "event")
                  target   (get attrs "target")
                  guard-s  (get attrs "cond")
                  cand-map (cond-> {}
                             ;; rf2-mnp93.7 — decode the qualified target id
                             ;; back to its relative grammar form (sibling
                             ;; keyword / absolute vector path).
                             target  (assoc :target (decode-target target source-path))
                             ;; rf2-mnp93.2 — decode the guard symmetrically
                             ;; with the encoder (keyword->id-string at emit).
                             ;; The pre-fix `(keyword guard-s)` never split the
                             ;; namespace, so `:auth/valid?` round-tripped to
                             ;; the bare `:auth.valid?` (ns lost). A guard is a
                             ;; single keyword (never a vector path), so route
                             ;; it through `id-string->keyword`, not
                             ;; `unescape-id-string`.
                             guard-s (assoc :guard (id-string->keyword guard-s)))]
              (cond
                (and event (str/starts-with? event "after."))
                (let [d-str (subs event 6)
                      d     (try
                              #?(:clj  (Long/parseLong d-str)
                                 :cljs (let [n (js/parseInt d-str 10)]
                                         (if (js/isNaN n) nil n)))
                              (catch #?(:clj Exception :cljs :default) _ nil))
                      ;; rf2-mnp93.1 — a non-numeric delay is an
                      ;; id-encoded keyword; decode it symmetrically with
                      ;; `keyword->id-string` so a namespaced keyword delay
                      ;; (`:a/b` → `after.a__b`) round-trips its namespace.
                      k     (or d (id-string->keyword d-str))]
                  (update-in acc [:after k] (fnil conj []) cand-map))

                ;; rf2-41goo — a `done.state.<id>` transition is the
                ;; XState `onDone` completion (SCXML §3.7). It sits inside
                ;; the done node's own element, so it round-trips back to
                ;; THIS node's `:on-done` (a single transition spec, not an
                ;; `:on`-keyed map). The `<id>` payload in the event name is
                ;; informational (the engine raises it relative to the
                ;; node); the re-frame2 grammar carries the completion as
                ;; `:on-done` on the node, so we drop the id suffix.
                (and event (str/starts-with? event "done.state."))
                (update acc :on-done (fnil conj []) cand-map)

                (nil? event)
                (update acc :always (fnil conj []) cand-map)

                ;; rf2-mnp93.1/.3 — an `:on` event key is a single keyword
                ;; (never a vector path), so decode it with
                ;; `id-string->keyword`. A user event whose name once
                ;; STARTED with `after.`/`done.state.` no longer collides
                ;; with the synthetic timer/done prefixes: the codec
                ;; escapes the literal `.` (`:after.foo` → `after_2efoo`),
                ;; so only the synthetic encodings carry a literal `.` here.
                :else
                (update-in acc [:on (id-string->keyword event)]
                           (fnil conj []) cand-map))))
          {}
          ts)]
    ;; rf2-mnp93.8 — finalise the candidate vectors. The reduce above
    ;; accumulates EVERY candidate as its full `{:target ... :guard ...}`
    ;; map. The bare-keyword/path SHORTHAND now applies ONLY to a SOLE
    ;; target-only candidate; a MULTI-candidate vector keeps each element
    ;; in its explicit map form. Pre-fix the decoder collapsed each
    ;; element of a vector to a bare keyword, so a MIXED vector
    ;; (`[{:target :a :guard :g1} {:target :b}]`) round-tripped to
    ;; `[{:target :a :guard :g1} :b]` — value-unequal to the input. Now:
    ;;   - 1 candidate, target-only  → bare keyword/path (`:b`)
    ;;     (matches the canonical `:on {:event :target}` shorthand)
    ;;   - 1 candidate, guard/action → the map (`{:target :b :guard :g}`)
    ;;   - N candidates              → vector of the full maps, verbatim
    ;; `:always` keeps the full vector-of-maps form so it lines up with
    ;; `(transition-candidates ...)`.
    (let [target-only? (fn [m] (= [:target] (keys m)))
          simplify     (fn [cands]
                         (if (= 1 (count cands))
                           (if (target-only? (first cands))
                             (:target (first cands))
                             (first cands))
                           cands))
          on*      (when (:on coll)
                     (into {} (map (fn [[ev cands]] [ev (simplify cands)]) (:on coll))))
          after*   (when (:after coll)
                     (into {} (map (fn [[k cands]] [k (simplify cands)]) (:after coll))))
          ondone*  (when (contains? coll :on-done) (simplify (:on-done coll)))]
      (cond-> {:children-tokens non-transitions}
        (:on coll)               (assoc :on on*)
        (:after coll)            (assoc :after after*)
        (:always coll)           (assoc :always (:always coll))
        (contains? coll :on-done) (assoc :on-done ondone*)))))

(declare parse-state-block)

(defn- group-children-by-state
  "Given a flat seq of tokens that sit inside one `<state>` body
  (already excluding transitions), pair every `<state>` /
  `<final>` open token with its matching close + interior tokens.
  Returns a seq of `{:start ... :body ... :self? bool}` maps."
  [tokens]
  (loop [remaining tokens
         acc       []]
    (if (empty? remaining)
      acc
      (let [t (first remaining)]
        (cond
          ;; Transitions are not state blocks — skip them at this
          ;; level. consume-transitions picks up the direct
          ;; transitions before group-children-by-state is called
          ;; on the children-tokens.
          (and (= "transition" (:tag t))
               (or (= :self (:kind t))
                   (= :start (:kind t))
                   (= :end (:kind t))))
          (recur (rest remaining) acc)

          (= :self (:kind t))
          (recur (rest remaining)
                 (conj acc {:start t :body [] :self? true}))

          (= :start (:kind t))
          (let [tag (:tag t)
                [body rest-tokens] (loop [body []
                                          depth 1
                                          rs (rest remaining)]
                                     (cond
                                       (empty? rs)
                                       (throw (ex-info ":scxml/parse-error"
                                                       {:rf.error/id :scxml/parse-error
                                                        :where    'machines-viz/scxml->spec
                                                        :recovery :no-recovery
                                                        :reason   (str "unclosed <" tag ">")
                                                        :tag      tag}))

                                       (and (= :end (:kind (first rs)))
                                            (= tag (:tag (first rs)))
                                            (= 1 depth))
                                       [body (rest rs)]

                                       (and (= :start (:kind (first rs)))
                                            (= tag (:tag (first rs))))
                                       (recur (conj body (first rs)) (inc depth) (rest rs))

                                       (and (= :end (:kind (first rs)))
                                            (= tag (:tag (first rs))))
                                       (recur (conj body (first rs)) (dec depth) (rest rs))

                                       :else
                                       (recur (conj body (first rs)) depth (rest rs))))]
            (recur rest-tokens
                   (conj acc {:start t :body body :self? false})))

          :else
          (recur (rest remaining) acc))))))

(defn- parse-state-block
  "Parse one `<state>` / `<final>` block into a `[state-id state-node]`
  pair. `self?` indicates a self-closing tag with no children.

  rf2-mnp93.7 — `id` is a FULLY-QUALIFIED unique xsd:ID (the state's
  absolute path). The LOCAL `:states` key is its last segment; the
  state's absolute path is threaded down so child transitions decode
  their qualified targets back to the relative grammar form."
  [{:keys [start body self?]}]
  (let [tag        (:tag start)
        attrs      (:attrs start)
        id-str     (get attrs "id")
        initial-str (get attrs "initial")
        abs-path   (id-string->abs-path id-str)
        state-id   (abs-path->local-key abs-path)
        base       (cond-> {}
                     (= "final" tag) (assoc :final? true)
                     ;; rf2-mnp93.7 — `initial` references a child by its
                     ;; qualified id; the re-frame2 `:initial` is the
                     ;; child's LOCAL key (last segment).
                     initial-str     (assoc :initial (abs-path->local-key
                                                       (id-string->abs-path initial-str))))]
    (if (or self? (empty? body))
      [state-id base]
      (let [{:keys [on after always children-tokens] :as consumed}
            (consume-transitions body abs-path)
            child-blocks (group-children-by-state children-tokens)
            child-states (when (seq child-blocks)
                           (into {}
                                 (map parse-state-block child-blocks)))
            node (cond-> base
                   (seq on)           (assoc :on on)
                   (seq after)        (assoc :after after)
                   (seq always)       (assoc :always always)
                   ;; rf2-41goo — `:on-done` round-trips from the
                   ;; `done.state.<id>` transition (may be a bare target,
                   ;; a candidate map, or a vector — never a collection we
                   ;; `seq`-test; use the key's presence).
                   (contains? consumed :on-done) (assoc :on-done (:on-done consumed))
                   (seq child-states) (assoc :states child-states))]
        [state-id node]))))

(defn- parse-parallel-body
  "Parse the children of a `<parallel>` element into a `:regions`
  map. `group-children-by-state` already filters transition tokens
  at this depth."
  [parallel-body]
  (let [region-blocks (group-children-by-state parallel-body)]
    (into {}
          (map (fn [{:keys [start body self?]}]
                 (let [region-id (abs-path->local-key
                                  (id-string->abs-path (get (:attrs start) "id")))
                       [_ region-node] (parse-state-block {:start start :body body :self? self?})]
                   ;; Strip :final? off the region top-level — regions are
                   ;; not final states even when their own children are.
                   [region-id (dissoc region-node :final?)])))
          region-blocks)))

(defn scxml->spec
  "Parse an SCXML XML string into a re-frame2 machine spec.

  Inverse of `spec->scxml`:

  ```clojure
  (= machine-spec (-> machine-spec spec->scxml scxml->spec))
  ```

  for the supported subset documented in the ns docstring.

  Throws `(ex-info ... {:reason :scxml/parse-error})` when the input
  is not a valid SCXML document our parser recognises (missing root
  `<scxml>`, unclosed tags, etc.). Throws `:scxml/invalid-spec` if
  the parsed structure is missing required keys (no `:initial`, no
  `:states`)."
  [scxml-string]
  (when-not (string? scxml-string)
    (throw (ex-info ":scxml/parse-error"
                    {:rf.error/id :scxml/parse-error
                     :where    'machines-viz/scxml->spec
                     :recovery :no-recovery
                     :reason   "scxml->spec expects a string"
                     :input    scxml-string})))
  (let [tokens (-> scxml-string strip-prolog strip-comments tokenize vec)
        root-start (first (filter #(= "scxml" (:tag %)) tokens))]
    (when-not root-start
      (throw (ex-info ":scxml/parse-error"
                      {:rf.error/id :scxml/parse-error
                       :where    'machines-viz/scxml->spec
                       :recovery :no-recovery
                       :reason   "no <scxml> root element found"})))
    (let [token-vec (vec tokens)
          start-idx (some (fn [i] (when (identical? (nth token-vec i) root-start) i))
                          (range (count token-vec)))
          root-body
          (let [;; Walk to the matching </scxml>
                tail (subvec token-vec (inc start-idx))
                end-idx (loop [i 0 depth 1]
                          (cond
                            (>= i (count tail))
                            (throw (ex-info ":scxml/parse-error"
                                            {:rf.error/id :scxml/parse-error
                                             :where    'machines-viz/scxml->spec
                                             :recovery :no-recovery
                                             :reason   "unclosed <scxml>"}))

                            (and (= :start (:kind (nth tail i)))
                                 (= "scxml" (:tag (nth tail i))))
                            (recur (inc i) (inc depth))

                            (and (= :end (:kind (nth tail i)))
                                 (= "scxml" (:tag (nth tail i))))
                            (if (= 1 depth)
                              i
                              (recur (inc i) (dec depth)))

                            :else
                            (recur (inc i) depth)))]
            (subvec tail 0 end-idx))

          parallel-token
          (first (filter #(and (= "parallel" (:tag %))
                               (= :start (:kind %)))
                         root-body))]
      (if parallel-token
        ;; Parallel definition
        (let [p-start-idx (some (fn [i] (when (identical? (nth root-body i) parallel-token) i))
                                (range (count root-body)))
              tail (subvec root-body (inc p-start-idx))
              end-idx (loop [i 0 depth 1]
                        (cond
                          (>= i (count tail))
                          (throw (ex-info ":scxml/parse-error"
                                          {:rf.error/id :scxml/parse-error
                                           :where    'machines-viz/scxml->spec
                                           :recovery :no-recovery
                                           :reason   "unclosed <parallel>"}))

                          (and (= :start (:kind (nth tail i)))
                               (= "parallel" (:tag (nth tail i))))
                          (recur (inc i) (inc depth))

                          (and (= :end (:kind (nth tail i)))
                               (= "parallel" (:tag (nth tail i))))
                          (if (= 1 depth)
                            i
                            (recur (inc i) (dec depth)))

                          :else
                          (recur (inc i) depth)))
              parallel-body (subvec tail 0 end-idx)
              ;; rf2-41goo — the parallel-root `:on-done` rides a
              ;; `done.state.<parallel-id>` transition that is a DIRECT
              ;; child of `<parallel>`. `parse-parallel-body` (via
              ;; `group-children-by-state`) filters transition tokens at
              ;; this depth, so pick it up directly via
              ;; `consume-transitions` before they are dropped.
              ;; The parallel-root :on-done is action-only (no target),
              ;; so the source-path is unused — pass [].
              parallel-on-done (:on-done (consume-transitions parallel-body []))]
          (cond-> {:type    :parallel
                   :regions (parse-parallel-body parallel-body)}
            (some? parallel-on-done) (assoc :on-done parallel-on-done)))

        ;; Flat / compound definition
        (let [initial-str (get-in root-start [:attrs "initial"])
              ;; group-children-by-state itself skips <transition>
              ;; tokens at the current depth — so root-level
              ;; transitions are dropped (we have no slot for them
              ;; in our spec grammar) and nested transitions stay
              ;; inside their owning state's body for
              ;; consume-transitions to pick up.
              top-state-blocks (group-children-by-state root-body)
              states (into {}
                           (map parse-state-block top-state-blocks))]
          (when (empty? states)
            (throw (ex-info ":scxml/invalid-spec"
                            {:rf.error/id :scxml/invalid-spec
                             :where    'machines-viz/scxml->spec
                             :recovery :no-recovery
                             :reason   "scxml document has no <state> or <final> elements"})))
          (cond-> {:states states}
            ;; rf2-mnp93.7 — the root `initial` is a top-level state's
            ;; qualified id; the re-frame2 `:initial` is its local key.
            initial-str (assoc :initial (abs-path->local-key
                                         (id-string->abs-path initial-str)))))))))
