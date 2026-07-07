(ns day8.re-frame2-machines-viz.scxml
  "SCXML (W3C State Chart XML) import/export for re-frame2 machine
  definitions.

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
  | `:on {:event {:action A}}` — INTERNAL (no `:target`) | `<transition event=\"event\"><!-- action: A --></transition>` — round-trips to `{:action A}`, NOT the `{}` forbidden block |
  | Self-target (`:target :same-state`, or a keyword naming the state's OWN key) | `<transition … target=\"<source-id>\" type=\"internal\"/>` — references the SOURCE state's REAL declared id; `type=\"internal\"` is the explicit XState-v5 internal default. Round-trips to the canonical `:same-state`. |
  | `:reenter? true` (any target) | `type=\"external\"` — the EXTERNAL restart axis (re-run `:exit`+`:entry`). A target-bearing transition WITHOUT `:reenter?` carries `type=\"internal\"`. |
  | `:after {ms :target}`                 | `<transition event=\"after.ms\" target=\"target\"/>` |
  | `:always [...]`                       | `<transition target=\"...\"/>` (eventless) |
  | `{:type :parallel :regions ...}`      | `<parallel>` containing region `<state>`s |
  | Parallel-ROOT `:on` / `:after` (the ancestor fallback) | `<transition event=\"event\"\\|\"after.ms\" target=\"a___x b___y\"/>` as a DIRECT `<parallel>` child. A MULTI-region target is a SPACE-SEPARATED id list (W3C `target` grammar); a targetless / action-only root transition emits NO `target`. Round-trips back to the region-qualified grammar (`[:a :x]` / `[[:a :x] [:b :y]]`). |
  | `{:type :history :deep? <b> :default-target <t>}` | W3C `<history type=\"shallow\\|deep\">` inside the owning compound; a `:default-target` rides a default `<transition target=\"…\"/>`. Round-trips back to `:type :history`. A history pseudo-state is NEVER occupiable — it is a transition TARGET that resolves to the recorded/default leaf — so it emits `<history>`, never `<state>`/`<final>`. |
  | Namespaced ids (`:auth/login`)        | `auth__login` (hex-escaped; `__` separates ns/name) |
  | Multi-dot-ns ids (`:my.app/login`)    | `my_2eapp__login` (dots in the ns are escaped to `_2e`) |
  | Vector-path targets (`[:parent :child]`) | `parent___child` (`___` joins path segments) |
  | Nested-state ids                      | FULLY QUALIFIED (root→leaf, `___`-joined) — unique xsd:ID |

  ## Id codec — injective, xsd:ID-conformant

  SCXML state ids are `xsd:ID` (XML NCName): letters / digits / `-` `.`
  `_`, NO `:`. The codec hex-escapes every keyword ns/name char to
  `_<2-hex>` (`.` → `_2e`, `?` → `_3f`, `_` → `_5f`) and uses two
  reserved markers the escaper can provably never emit — `__` between a
  keyword's namespace and name, `___` between vector-path segments — so:

  - ANY keyword round-trips EXACTLY, regardless of how many dots its
    namespace or name has (`:my.app.auth/login` ≠ `:my/app.auth.login`
    produce DISTINCT ids).
  - State ids are FULLY QUALIFIED with their nesting path, so two
    same-named nested states emit UNIQUE xsd:IDs and transition targets
    reference those same unique ids — conformant for external SCXML
    tooling.
  - User events named `after.*` / `done.state.*` do not collide with
    the synthetic timer / `:on-done` encodings: those carry a LITERAL
    `.` the codec never emits for a real keyword.

  ## Not supported (lossy or omitted)

  - `:spawn-all` rows — omitted; the parent state renders without
    spawn affordances.
  - INTERNAL-default self / proper-ancestor SELF-TRANSITION semantics
    — re-frame2 / XState v5 make a targeted transition INTERNAL by
    default (the targeted state's OWN `:exit` / `:entry` do NOT re-run);
    W3C SCXML's `type=\"internal\"` only changes the transition domain
    for a COMPOUND source whose target is a PROPER DESCENDANT (the one
    case where the export IS the exact equivalent). For a self-target
    (source == target) or a proper-ancestor target, SCXML ALWAYS
    re-enters the source regardless of `type`, so the re-frame2 internal
    default has no exact SCXML equivalent. The export emits
    `type=\"internal\"` to record the intended axis and references the
    source state's REAL declared id, and the local `scxml->spec`
    round-trips the `:same-state` sentinel exactly — but a STRICT
    external SCXML engine executing the imported chart will re-run the
    source's exit/entry on the self-target where re-frame2 would not.
    This is the irreducible loss; it is documented here rather than
    papered over.
  - Machine-level (top-level) `:on` fallback transitions — W3C SCXML
    has no clean root-fallback slot (`<scxml>` does not host
    `<transition>` children per the schema, and the import side drops
    root-level transitions), so these are exported as a documenting
    XML comment and do **not** round-trip back through `scxml->spec`.
  - `:tags` — re-frame2-specific; not part of W3C SCXML.
  - `:action`s and guard FN bodies — only the *names* survive (SCXML
    `cond=\"name\"` for guards; a transition `:action` name rides an
    `<!-- action: name -->` comment, the only SCXML-tolerable slot, since
    SCXML proper has no transition-action-id attribute). An INLINE-FN
    `:guard` / `:action` (the Spec 005 escape hatch) is LOSSY-not-crash:
    `ref->label` surfaces the fn's `:name` meta or a stable `\"fn\"`
    fallback (consistent with chart/Mermaid; matches the API promise that
    fn bodies are lossy, not unsupported) — it does NOT round-trip back to
    the original fn. The action name **round-trips**: `scxml->spec` lifts
    the action comment back into the candidate's `:action` BEFORE comments
    are stripped, so an internal `:on {:tick {:action :log}}` returns
    `{:action :log}` — a valid Spec-005 internal action transition — NOT
    the empty `{}` FORBIDDEN BLOCK a naive comment-strip would synthesise
    (a semantic inversion). FN BODIES are still lost (only the name
    survives). Entry/exit actions would require an evaluation context, so
    their names are preserved as XML comments but are not part of this
    round-trip.
  - Source-coord metadata — stripped at export time (same posture as
    share-URL encoding; see `Principles.md` §No session data in shares).
  - Consumer-attachment `:rf.cofx/requires` (EP-0017 — the replay-critical
    host facts a named guard / action / entry / exit consumes):
    INTENTIONALLY OMITTED. W3C SCXML has no native attribute for re-frame2
    coeffect requirements, and the requirements ride the `:guards` /
    `:actions` named-entry registry, which SCXML does not carry (only the
    NAME survives, via `cond=` / the action comment). On import a re-frame2
    machine re-attaches its requirements from its own registry, so a comment
    round-trip would be redundant. The interactive MachineChart is the
    EP-0017 'which transitions consume which facts' surface; SCXML stays W3C
    topology interop. The omission is locked by `scxml-cljs-test`'s no-cofx-
    vocabulary regression.

  Round-trip failure modes throw the canonical thrown-error shape (Spec
  009 §The thrown-error shape): `:rf.error/id` is the `:scxml/...`
  discriminator for programmatic dispatch, `:reason` is the human
  sentence, and the message leads with the sentence + the
  `[:scxml/<id>]` token.

  Per [`API.md`](../../spec/API.md) §SCXML import/export."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            ;; The SHARED grammar walker + injective id codec the three
            ;; emitters route through, so they address every node identically
            ;; (one escape codec, one source of truth). The SCXML DECODE side
            ;; (unescape / decode-target) + the root-grammar-aware
            ;; `root-transition-candidates` stay LOCAL — they are genuinely
            ;; distinct from the shared emit-side walker.
            [day8.re-frame2-machines-viz.grammar :as g]
            ;; Share the canonical parallel-root done-state id with the chart
            ;; projector so the two emitters agree on the `done.state.<id>`
            ;; label (single source of truth).
            [day8.re-frame2-machines-viz.chart.layout :as layout]))

;; ---------------------------------------------------------------------------
;; Value-free error diagnostics
;;
;; EP-0015 / Spec 015 §exception-path residual — a thrown error must NOT
;; carry the raw rejected payload. A machine spec can carry a `:data`
;; slot of live runtime values and an SCXML input string can carry
;; interpolated secrets; projection cannot walk `ex-data` after the fact,
;; so the assembly site names the SHAPE (type tag, key SET, length) —
;; never the values.

(defn- spec-summary
  "Value-FREE diagnostic for a rejected machine `spec`: type tag plus —
  for a map — the top-level key SET, parallel?, and child counts."
  [spec]
  (if (map? spec)
    (cond-> {:type     :map
             :keys     (vec (sort-by str (keys spec)))
             :parallel (= :parallel (:type spec))}
      (map? (:states spec))  (assoc :state-count  (count (:states spec)))
      (map? (:regions spec)) (assoc :region-count (count (:regions spec))))
    {:type (cond (nil? spec) :nil (vector? spec) :vector :else :value)}))

(defn- input-summary
  "Value-FREE diagnostic for a rejected SCXML `input`: type + length."
  [input]
  (if (string? input)
    {:type :string :length (count input)}
    {:type (cond (nil? input) :nil (map? input) :map :else :value)}))

;; ---------------------------------------------------------------------------
;; Id codec — FULLY INJECTIVE, xsd:ID-conformant
;;
;; Re-frame2 ids are keywords (`:idle`, `:auth/login-flow`,
;; `:my.app.auth/login`) or vector paths (`[:authenticated :browsing]`).
;; SCXML state ids are `xsd:ID` (XML NCName): the leading char must be a
;; letter / `_`, subsequent chars letters / digits / `-` `.` `_`. A `:`
;; is NOT a valid NCName char, and a `.`-as-ns/name scheme would be
;; NON-INJECTIVE: a keyword namespace may itself contain dots
;; (`:my.app.auth/login` → `"my.app.auth.login"`), so a decoder could not
;; recover where the namespace ended.
;;
;; The hex-escape codec (the same injective scheme `chart/layout`'s
;; `node-id` uses for xyflow ids) handles this. EVERY non-alphanumeric
;; char in a segment is escaped to `_<2-hex>` (so a literal `_` → `_5f`,
;; `.` → `_2e`, `?` → `_3f`). After escaping, an `_` appears ONLY as the
;; lead of a `_XX` hex triple — never two underscores in a row — so we can
;; use consecutive-underscore RESERVED MARKERS the escaper can provably
;; never emit:
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
  "The keyword NAMESPACE↔NAME boundary in an SCXML id. `__` (double
  underscore) is impossible for `escape-id-segment` to emit (it only ever
  emits `_` + 2 hex digits), so it is the injective ns/name marker."
  "__")

(def ^:private path-segment-sep
  "The vector-PATH segment boundary in an SCXML id. `___` (triple
  underscore) the escaper can never emit and is distinct from the `__`
  ns/name marker, so a vector path can never collide with a single
  namespaced keyword. A valid xsd:ID."
  "___")

;; The xsd:ID-safe injective escape is the SHARED `grammar/escape-id-segment`
;; (every char outside `[A-Za-z0-9]` → `_<2-hex>`, the underscore itself →
;; `_5f`; no two consecutive underscores, so the `__` / `___` reserved markers
;; can never arise from segment content). The SINGLE injective scheme across
;; all machines-viz id emitters, so the SCXML codec mints byte-identical
;; segment escapes to the chart `node-id` + mermaid `sanitise-id`.
(def ^:private escape-id-segment g/escape-id-segment)

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
  name. Exact round-trip for ANY keyword (multi-dot ns, dotted name,
  `?`/`-`/`_` in either)."
  [k]
  (if-let [ns (namespace k)]
    (str (escape-id-segment ns) ns-name-sep (escape-id-segment (name k)))
    (escape-id-segment (name k))))

(defn- ref->label
  "Render a transition `:guard` / `:action` REF as an SCXML attribute
  label, tolerating inline fns the SAME way `chart/layout` + `mermaid` do.

  A keyword ref keeps the injective, round-tripping `keyword->id-string`
  codec (the supported subset). An INLINE FN ref is LOSSY by design — the
  machine grammar permits `:guard (fn …)` / `:action (fn …)` (Spec 005
  §Guards / §Actions), and the API promise is that fn BODIES are
  lossy/opaque, NOT unsupported. We surface the fn's `:name` meta (a named
  `(fn name […] …)` / `defn`) or a stable `\"fn\"` fallback, mirroring
  `chart/layout/name-of`. No attempt is made to serialise the executable
  body."
  [ref]
  (cond
    (keyword? ref) (keyword->id-string ref)
    (fn? ref)      (or (some-> ref meta :name str) "fn")
    (nil? ref)     nil
    :else          (str ref)))

(defn- path->id-string
  "Map a re-frame2 id (keyword or vector path) to a single SCXML id
  string. A keyword uses `keyword->id-string`; a vector path joins its
  per-segment id strings with `___` (`path-segment-sep`). The `__`
  ns/name and `___` path markers never collide with each other or with
  segment content, so the codec is fully injective."
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
  `\"name\"` → `:name`. The symmetric inverse used by BOTH the id decoder
  and the guard `cond=` decoder."
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

;; The generic per-state transition-spec walker is the SHARED
;; `grammar/transition-candidates` (chart + mermaid share it). NOTE the
;; parallel-ROOT SCXML emitter uses its OWN `root-transition-candidates`
;; (below) instead — there a vector-of-VECTORS is a SINGLE multi-region
;; target, NOT a candidate fork, so it must NOT route through the generic
;; walker. That divergence is PRESERVED.
(def ^:private transition-candidates g/transition-candidates)

;; --- path qualification --------------------------------------------------
;;
;; W3C SCXML state ids are `xsd:ID` — they MUST be unique across the whole
;; document. Two states sharing a name under different compound parents
;; would collide on a bare local-state name (duplicate `<state id="idle">`)
;; — invalid SCXML a conformant external consumer rejects. We emit the
;; FULLY-QUALIFIED path id (root → leaf, `___`-joined via `path->id-string`)
;; for every state, `initial`, and transition `target`, so every id is
;; unique. The decoder reverses the qualification: a state block's local
;; `:states` key is the LAST segment of its qualified id, and a target
;; resolves back to the relative form (a sibling keyword when it shares the
;; source's parent, else the absolute vector path) so the round-trip is
;; exact.

;; Grammar primitives aliased from the SHARED `grammar` ns. W3C SCXML emits
;; a first-class `<history>` element for a `:type :history` pseudo-state
;; (Spec 005 §History states), so `emit-state` routes a history child to
;; `<history>` rather than `<state>` / `<final>` to keep round-trip exact.
(def ^:private target-path? g/target-path?)
(def ^:private history-node? g/history-node?)
(def ^:private parent-path g/parent-path)

(defn- resolve-target-path
  "Resolve a transition target to its ABSOLUTE path from the machine
  root. `source-path` is the source state's absolute path.

  - `:same-state` (Spec 005 §Self-transitions self-target sentinel) →
    the SOURCE state's OWN path. This MUST match the runtime resolver
    (`re-frame.machines.transition/target-path`) and the chart / Mermaid
    projectors (`chart.layout/resolve-target-path`,
    `mermaid/resolve-target-path`), which ALL special-case `:same-state`
    to the declaring state's own path. Resolving to `source-path` emits
    the SOURCE state's real unique id, exactly as a sibling-keyword
    self-target (`:target :a` on state `:a`) does — the two are the SAME
    self-transition per Spec 005, and SCXML has no self-sentinel, only a
    target referencing the state's own id.
  - a keyword target is a SIBLING of the source state (Spec 005).
  - a vector-path target is absolute."
  [source-path target]
  (cond
    (= :same-state target) (vec source-path)
    (keyword? target)      (conj (parent-path source-path) target)
    (target-path? target)  (vec target)
    :else                  nil))

(defn- qualified-id
  "The unique xsd:ID for a state at absolute `path`."
  [path]
  (path->id-string (vec path)))

(defn- emit-transition
  "Emit a `<transition>` line for one candidate. `source-path` is the
  absolute path of the OWNING state; targets are path-qualified against
  it so the emitted `target` is the unique xsd:ID of the destination.

  The re-frame2 `:reenter? true` axis (Spec 005 §Self-transitions /
  XState v5: a TARGETED transition is INTERNAL by default; `:reenter?`
  opts into the EXTERNAL restart) maps onto W3C SCXML's `<transition
  type=\"internal|external\">`, which is the SAME external-vs-internal
  axis. Spec 005 §Self-transitions L332 records the inversion: SCXML's
  targeted-transition DEFAULT is `external` (`type=\"internal\"` opts
  out); XState v5 / re-frame2 use the opposite convention (internal
  default, `:reenter?` opts IN). So a target-bearing re-frame2 transition
  must be EXPLICIT about the type — leaving it absent would let the export
  silently inherit SCXML's EXTERNAL default and mis-state the re-frame2
  internal-default intent. We therefore:

  - emit `type=\"external\"` for a target-bearing `:reenter? true`
    candidate (the external restart — re-run exit/entry); and
  - emit `type=\"internal\"` for a target-bearing transition WITHOUT
    `:reenter?` (the re-frame2 / XState-v5 internal default).

  A TARGETLESS (action-only) transition carries NO `type` — SCXML's own
  default for a targetless transition is already `internal` and `external`
  is meaningless with no state to re-enter; this also keeps the round-trip
  for the existing action-only shapes unchanged.

  SCXML's `type=\"internal\"` is strictly equivalent to `external` for a
  COMPOUND source whose target is a PROPER DESCENDANT (the only case where
  W3C `getTransitionDomain` differs); for a self / proper-ancestor target
  it is an irreducible LOSS — SCXML re-enters the source on a self-target
  regardless of `type`, whereas re-frame2's internal default does NOT
  re-run the source's own exit/entry. `type=\"internal\"` records the
  intended axis (and is the exact equivalent for the descendant case); the
  irreducible self/ancestor loss is documented in API.md §Not supported
  rather than papered over by the local round-trip oracle. The decoder
  inverts this axis: `type=\"external\"` ⇒ `:reenter? true`, any other
  value (incl. `internal` / absent) ⇒ the internal default."
  [event-name {:keys [target guard action reenter?]} source-path depth]
  (let [target-id (when target
                    (qualified-id (resolve-target-path source-path target)))
        parts (cond-> []
                event-name (conj (str "event=\"" (escape-xml-attr event-name) "\""))
                target-id  (conj (str "target=\"" (escape-xml-attr target-id) "\""))
                ;; The external-restart axis. Emitted ONLY for a
                ;; target-bearing `:reenter? true` candidate (a targetless
                ;; transition has no state to re-enter; SCXML's `external` is
                ;; meaningless without a target).
                (and target reenter?)
                (conj "type=\"external\"")
                ;; The internal default is EXPLICIT. A target-bearing
                ;; transition WITHOUT `:reenter?` emits `type=\"internal\"` so
                ;; the export does NOT inherit SCXML's EXTERNAL
                ;; targeted-transition default (which would mis-state the
                ;; re-frame2 / XState-v5 internal default — see docstring).
                (and target (not reenter?))
                (conj "type=\"internal\"")
                ;; `ref->label` tolerates an inline-fn guard (lossy name/`fn`
                ;; label) rather than calling `keyword->id-string` on a
                ;; non-keyword.
                guard      (conj (str "cond=\"" (escape-xml-attr (ref->label guard)) "\"")))
        attrs (str/join " " parts)
        self-close? (nil? action)]
    (str (indent-str depth)
         (if self-close?
           (str "<transition " attrs "/>")
           (str "<transition " attrs ">"
                ;; Likewise tolerate an inline-fn action.
                "<!-- action: " (escape-xml-attr (ref->label action)) " -->"
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
  "rf2-oy49f1 — gated on `always` being present. `:always` is a SINGULAR
  top-level slot (unlike a per-event `:on`/`:after` MAP entry, where the
  caller only ever visits a key that EXISTS) — an absent `:always` and an
  explicit `nil` value are indistinguishable once destructured off the
  state-node, so `transition-candidates`' `(nil? spec) [{}]` forbidden-
  transition arm must not fire here (it would emit a phantom eventless
  `<transition/>` for EVERY state, even one with no `:always` declared)."
  [always source-path depth]
  (when always
    (->> (transition-candidates always)
         (map #(emit-transition nil % source-path depth)))))

(defn- emit-transitions-for-on-done
  "Emit the compound / parallel `:on-done` (XState `onDone`) as W3C
  SCXML's `<transition event=\"done.state.<id>\" .../>`, placed inside
  the done node's own `<state>` element (SCXML §3.7: reaching a `<final>`
  child generates `done.state.<id>` into the internal queue; an enclosing
  transition takes it). `done-event` is the precomputed
  `\"done.state.<id-str>\"` for THIS node. A target-less candidate
  (action/fx-only — the parallel-root shape) emits a transition with NO
  `target` (the action survives as the emitter's `<!-- action -->`
  comment), faithful to the action-only completion the engine fires.
  `source-path` qualifies any target."
  [done-event on-done source-path depth]
  (->> (transition-candidates on-done)
       (map #(emit-transition done-event % source-path depth))))

(defn- emit-history
  "Emit a W3C SCXML `<history>` pseudo-state element for a re-frame2
  `:type :history` node (Spec 005 §History states). `path` is the history
  node's absolute path (its unique xsd:ID).

  W3C SCXML §3.10: `<history type=\"shallow|deep\">` carries an OPTIONAL
  default `<transition target=\"…\"/>` used when the parent has no stored
  configuration — the exact semantics of re-frame2's `:default-target`
  (Spec 005: the target when the compound was never entered). A `:deep?
  true` node emits `type=\"deep\"`; shallow / absent emits
  `type=\"shallow\"`. The `:default-target` resolves relative to the
  history node's own level (a keyword target is a sibling — i.e. a direct
  child of the owning compound — per Spec 005), path-qualified to the
  destination's unique id, and round-trips back via `decode-target`."
  [path {:keys [deep? default-target]} depth]
  (let [id-str    (qualified-id path)
        type-str  (if deep? "deep" "shallow")
        attrs     (str "id=\"" (escape-xml-attr id-str) "\""
                       " type=\"" type-str "\"")
        target-id (when default-target
                    (qualified-id (resolve-target-path path default-target)))]
    (if target-id
      [(str (indent-str depth) "<history " attrs ">")
       (str (indent-str (inc depth))
            "<transition target=\"" (escape-xml-attr target-id) "\"/>")
       (str (indent-str depth) "</history>")]
      [(str (indent-str depth) "<history " attrs "/>")])))

;; EP-0011 — the re-frame2-specific carrier for an ERROR-terminal final
;; state. Spec 005 §`:final?` lets a `:final?` leaf carry `:error? true` (an
;; error terminal that routes the spawning parent's `:spawn` `:on-error` and
;; completes `:status :error`, vs a plain final's `:on-done` / `:status :ok`).
;; W3C SCXML's `<final>` has no error-terminal concept, so — like
;; `data_rf_action` — the bit rides a custom `data_*` attribute (underscore
;; key, so `parse-attrs`' `(\w+)` pattern recovers it; can never collide with
;; a real W3C SCXML attribute). The emitter writes it on the `<final>`
;; element when `:error? true`; the parser recovers it back to `:error?`.
;; Ordinary SCXML consumers ignore an unknown `data_*` attribute, so the
;; export stays consumable while the completion-status distinction survives.
(def ^:private error-final-attr "data_rf_error_final")

(defn- emit-state
  "Emit a `<state>` (or `<final>`) block for one state-node. `path` is
  the absolute path (root → this state) used to emit unique xsd:IDs and
  to qualify transition targets.

  A `:type :history` child of this state is emitted as a W3C `<history>`
  element (`emit-history`), NOT a nested `<state>`.

  EP-0011 — a `:final? true :error? true` leaf is an ERROR terminal; its
  `<final>` carries the `data_rf_error_final=\"true\"` carrier so the
  completion-status distinction survives the round-trip (see
  `error-final-attr`)."
  [path state-node depth]
  (let [{:keys [final? error? initial states on after always on-done]} state-node
        id-str (qualified-id path)
        tag    (if final? "final" "state")
        attrs  (cond-> (str "id=\"" (escape-xml-attr id-str) "\"")
                 (and (not final?) initial)
                 ;; `initial` references a CHILD by its unique qualified id
                 ;; (the child's absolute path).
                 (str " initial=\"" (escape-xml-attr (qualified-id (conj (vec path) initial))) "\"")
                 ;; EP-0011 — a `:final? true :error? true` leaf is an ERROR
                 ;; terminal: it lowers to the uniform reply envelope as
                 ;; `:status :error` and routes the spawning parent's
                 ;; `:spawn` `:on-error` (vs a plain final's `:status :ok` /
                 ;; `:on-done`). W3C SCXML's `<final>` has no error-terminal
                 ;; concept, so the bit rides the re-frame2-specific
                 ;; `data_rf_error_final` custom attribute (same carrier
                 ;; posture as `data_rf_action`) — ordinary SCXML consumers
                 ;; ignore it; the import side recovers `:error?` from it so
                 ;; the completion STATUS does not silently collapse to
                 ;; success.
                 (and final? error?)
                 (str " " error-final-attr "=\"true\""))
        children
        (concat
          (emit-transitions-for-on on path (inc depth))
          (emit-transitions-for-after after path (inc depth))
          (emit-transitions-for-always always path (inc depth))
          ;; The `done.state.<this-id>` completion transition (XState
          ;; `onDone`). Emitted INSIDE this node's own <state>.
          (when on-done
            (emit-transitions-for-on-done (str "done.state." id-str)
                                          on-done path (inc depth)))
          (mapcat (fn [[child-id child-node]]
                    ;; A `:type :history` child is a W3C `<history>`
                    ;; pseudo-state, not a nested `<state>`.
                    (if (history-node? child-node)
                      (emit-history (conj (vec path) child-id) child-node (inc depth))
                      (emit-state (conj (vec path) child-id) child-node (inc depth))))
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

(defn- root-transition-candidates
  "rf2-656ivk / rf2-m3otj2 — normalise a parallel-ROOT `:on` / `:after` SPEC
  VALUE into candidate maps, matching the RUNTIME grammar
  (`re-frame.machines.grammar/transition-value-form`) — NOT the generic
  `transition-candidates`. The crucial divergence: a vector-of-VECTORS
  (`[[:a :x] [:b :y]]`) is a SINGLE multi-region TARGET (`:vec-target`), NOT a
  candidate fork. The generic `transition-candidates` (which keys off
  `(every? keyword? …)`) would mis-split it into two candidates; only a vector
  of MAPS is a real candidate fork (`:candidate-vector`). Keeping the root
  emitter on this root-aware normaliser preserves the multi-region-target
  semantics (one atomic `<transition target=\"a___x b___y\"/>`) AND round-trips
  exactly."
  [spec]
  (cond
    (nil? spec)     [{}]                          ; forbidden / internal no-op
    (keyword? spec) [{:target spec}]
    (map? spec)     [spec]
    (vector? spec)  (if (and (seq spec) (every? map? spec))
                      spec                         ; :candidate-vector — a fork
                      [{:target spec}])            ; :vec-target — ONE target
    :else           []))

;; rf2-b2ygd2 — the parallel-root `:target` normaliser is the SHARED
;; `grammar/normalise-root-targets` (also the chart projector's
;; `normalise-root-targets`), mirroring the runtime resolver
;; (`re-frame.machines.parallel/normalise-root-targets`):
;;   - nil / absent  → `[]` (TARGETLESS / action-only);
;;   - vector of KEYWORDS (`[:a :two]`) → ONE region-qualified target;
;;   - vector of VECTORS (`[[:a :x] [:b :y]]`) → MULTIPLE, returned as-is.
;; This is the inverse-symmetric partner of `decode-root-parallel-target`.
(def ^:private root-region-qualified-targets g/normalise-root-targets)

(defn- emit-root-parallel-transition
  "rf2-656ivk / rf2-m3otj2 — emit ONE root-parallel `<transition>` (an `:on`
  or `:after` candidate declared on the `:type :parallel` ROOT itself — the
  ancestor fallback for its regions, Spec 005 §Root parallel `:on` /
  §Root-level `:after`). It sits as a DIRECT child of `<parallel>`.

  Each region-qualified target `[<region> & <in-region-path>]` is the ABSOLUTE
  path of a region substate (regions are the `<parallel>`'s direct children),
  so it resolves through `qualified-id` to the same unique xsd:ID the region
  state emits. W3C SCXML's `target` attribute is a SPACE-SEPARATED id list, so
  a MULTI-region target `[[:a :x] [:b :y]]` emits `target=\"a___x b___y\"` —
  one space-joined attribute that round-trips back through
  `decode-root-parallel-target` to the multi-region grammar. A TARGETLESS /
  action-only candidate emits a `<transition>` with NO `target` (the action
  survives as the `<!-- action -->` comment, like every action-only
  transition). The guard rides `cond=` as usual.

  No `type=\"internal|external\"` axis is emitted: a root transition moves
  region substates (never the root itself), so the self/ancestor re-enter
  axis does not apply."
  [event-name {:keys [target guard action] :as candidate} depth]
  (let [targets   (root-region-qualified-targets target)
        target-id (when (seq targets)
                    (str/join " " (map qualified-id targets)))
        parts (cond-> [(str "event=\"" (escape-xml-attr event-name) "\"")]
                target-id (conj (str "target=\"" (escape-xml-attr target-id) "\""))
                guard     (conj (str "cond=\"" (escape-xml-attr (ref->label guard)) "\"")))
        attrs (str/join " " parts)]
    (str (indent-str depth)
         (if (nil? action)
           (str "<transition " attrs "/>")
           (str "<transition " attrs ">"
                "<!-- action: " (escape-xml-attr (ref->label action)) " -->"
                "</transition>")))))

(defn- emit-root-parallel-on
  "rf2-656ivk — emit the parallel-ROOT's own `:on` transitions (the ancestor
  fallback) as DIRECT `<parallel>` children, wrapped in a documenting comment.
  Pre-fix `emit-parallel` dropped the root `:on` entirely; it now survives the
  export AND round-trips (the import recovers these direct-child transitions)."
  [on depth]
  (when (seq on)
    (concat
      [(str (indent-str depth)
            "<!-- parallel-root :on ancestor fallback"
            " (Spec 005 §Root parallel :on) -->")]
      (mapcat (fn [[event spec]]
                (map #(emit-root-parallel-transition (keyword->id-string event) % depth)
                     (root-transition-candidates spec)))
              on))))

(defn- emit-root-parallel-after
  "rf2-m3otj2 — emit the parallel-ROOT's own `:after` (delayed) transitions —
  the timer-driven analog of the root `:on` ancestor fallback (Spec 005
  §Root-level `:after`) — as DIRECT `<parallel>` children, wrapped in a
  documenting comment. The delay becomes `event=\"after.<delay>\"` exactly as
  a state's `:after` does (`emit-transitions-for-after`)."
  [after depth]
  (when (seq after)
    (concat
      [(str (indent-str depth)
            "<!-- parallel-root :after timer ancestor fallback"
            " (Spec 005 §Root-level :after) -->")]
      (mapcat (fn [[delay spec]]
                (let [event (str "after." (if (keyword? delay)
                                            (keyword->id-string delay)
                                            delay))]
                  (map #(emit-root-parallel-transition event % depth)
                       (root-transition-candidates spec))))
              after))))

(defn- emit-parallel
  [{:keys [regions on after on-done]} depth]
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
    ;; rf2-656ivk / rf2-m3otj2 — the parallel-root's OWN `:on` / `:after`
    ;; ancestor-fallback transitions (region-qualified target grammar). Pre-fix
    ;; both were silently dropped (only `:on-done` survived).
    (emit-root-parallel-on on (+ depth 2))
    (emit-root-parallel-after after (+ depth 2))
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

  Throws the canonical thrown-error shape with
  `:rf.error/id :scxml/invalid-spec` if the spec is missing required keys.

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
  ;; EP-0029 — lower the named-intent grammars before export: `:timeout` /
  ;; `:on-timeout` → `:after` (A4, surfaces as a `delayed` SCXML
  ;; `<transition>`) and `:type :choice` / `:choice` → `:always` (A5,
  ;; surfaces the candidate `<transition>`s). The runtime drives the same
  ;; lowered forms. Idempotent + nil-safe.
  (let [machine-spec (g/desugar-grammar machine-spec)]
  (cond
    (= :parallel (:type machine-spec))
    (let [{:keys [regions]} machine-spec]
      (when-not (and (map? regions) (seq regions))
        (error/throw-error!
          :scxml/invalid-spec
          'machines-viz/spec->scxml
          (str "SCXML export: a parallel machine spec requires a non-empty "
               ":regions map; add :regions to the parallel spec.")
          {:recovery :supply-non-empty-regions
           ;; rf2-8nzxib — value-FREE; never the raw spec (its :data
           ;; slot can carry live runtime values).
           :extra    {:spec-summary (spec-summary machine-spec)}}))
      (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
           (str/join "\n" (emit-parallel machine-spec 0))))

    (and (:initial machine-spec) (map? (:states machine-spec)) (seq (:states machine-spec)))
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         (str/join "\n" (emit-flat-or-compound machine-spec 0)))

    :else
    (error/throw-error!
      :scxml/invalid-spec
      'machines-viz/spec->scxml
      (str "SCXML export: a machine spec must carry :initial + a non-empty "
           ":states map, or :type :parallel + :regions. Provide one of "
           "those shapes.")
      {:recovery :supply-a-valid-machine-spec
       ;; rf2-8nzxib — value-FREE; never the raw spec.
       :extra    {:spec-summary (spec-summary machine-spec)}}))))

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

;; rf2-mnp93.5 — the synthetic attribute the action-comment lift-pass folds
;; an action name into. `parse-attrs`' `(\w+)` key pattern forbids hyphens,
;; so the key uses underscores; it can never collide with a real SCXML
;; attribute (W3C SCXML has no `data_rf_action`). It is stripped from the
;; emitted output (the emitter writes the action as a COMMENT for external
;; tooling); it exists ONLY transiently between `lift-action-comments` and
;; `parse-attrs` on the DECODE side.
(def ^:private action-attr "data_rf_action")

(defn- lift-action-comments
  "rf2-mnp93.5 — fold every transition action COMMENT into a synthetic
  attribute on its OWN `<transition>` start-tag BEFORE comments are
  stripped, so the action name survives the round-trip.

  The emitter writes an action-bearing transition as
  `<transition ATTRS><!-- action: NAME --></transition>` (the comment is
  how the action survives for external SCXML tooling, which has no
  transition-action slot). Pre-fix, `strip-comments` discarded the comment
  globally, so an INTERNAL action transition (`:on {:tick {:action :log}}`)
  decoded to the EMPTY candidate map `{}` — which Spec 005 §Forbidden
  transitions defines as a FORBIDDEN BLOCK (consume-the-event-and-block-
  inheritance), the OPPOSITE of 'run an action'. A semantic inversion, not
  a lossy detail (rf2-mnp93.5).

  This pass rewrites `<transition ATTRS><!-- action: NAME --></transition>`
  to `<transition ATTRS data_rf_action=\"NAME\"/>` so the decoder recovers
  the action into the candidate map's `:action` — yielding a VALID Spec-005
  internal action transition (`{:action :log}`), never the forbidden-block
  `{}`. Per Spec 005 §Forbidden transitions L1346, an action-bearing
  internal transition halts the walk AND runs the action; the distinguishing
  shape feature is the PRESENCE of `:action`."
  [s]
  (str/replace
    s
    #"(?s)(<transition\b[^>]*?)>\s*<!--\s*action:\s*(\S+)\s*-->\s*</transition>"
    (fn [[_ start-tag action-name]]
      (str start-tag " " action-attr "=\"" action-name "\"/>"))))

(defn- strip-comments
  "Drop `<!-- ... -->` comments. The action-name comments the emitter
  injects on transitions are first lifted into a synthetic attribute by
  `lift-action-comments` (rf2-mnp93.5) so they survive the round-trip;
  this then drops the remaining (documenting / machine-level) comments,
  which SCXML proper has no slot for."
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
  (rf2-mnp93.7). This inverts `resolve-target-path` so the round-trip is
  exact:

  - rf2-0pp6as — a SELF-TARGET (the resolved target path EQUALS the source
    state's own path) decodes to the canonical `:same-state` sentinel
    (Spec 005 §Self-transitions). The emitter resolves BOTH `:target
    :same-state` AND a keyword naming the state's own key to the source
    state's own id, and SCXML has no self-sentinel — it just references the
    state's own id. Those two re-frame2 spellings are the SAME
    self-transition per Spec 005 (L327, transition.cljc L1248-1250), so the
    canonical decode is `:same-state` for both. This keeps the round-trip
    exact for `{:target :same-state}` even though the export no longer
    emits the dangling `same_2dstate` phantom id.
  - a target whose parent equals the source's parent is a SIBLING —
    re-frame2 writes it as the bare keyword (the last segment).
  - any other target is written as the absolute vector path."
  [target-str source-path]
  (let [abs-path (vec (id-string->abs-path target-str))
        src      (vec source-path)]
    (cond
      (= abs-path src)                          :same-state
      (= (parent-path abs-path) (parent-path src)) (last abs-path)
      :else                                     abs-path)))

(defn- decode-root-parallel-target
  "rf2-656ivk / rf2-m3otj2 — inverse of `emit-root-parallel-transition`'s
  target encoding. A parallel-ROOT `:on` / `:after` `target` is a
  SPACE-SEPARATED list of region-qualified absolute ids
  (`\"a___x b___y\"`). Each id decodes (`id-string->abs-path`) to its
  region-qualified absolute path `[<region> & <in-region-path>]`. Returns the
  re-frame2 root-target grammar:

    - one id   → the SINGLE region-qualified target vector `[:a :x]`;
    - multiple → the MULTI-region vector-of-vectors `[[:a :x] [:b :y]]`;
    - nil / blank → nil (a targetless / action-only root transition).

  This is the symmetric inverse of `root-region-qualified-targets`."
  [target-str]
  (when (and target-str (seq (str/trim target-str)))
    (let [paths (->> (str/split (str/trim target-str) #"\s+")
                     (mapv (comp vec id-string->abs-path)))]
      (if (= 1 (count paths))
        (first paths)
        paths))))

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

(defn- parse-after-key
  "Decode an `after.<delay>` SCXML event name into its `:after` map key.
  A numeric delay parses to a Long/int; a non-numeric delay is an
  id-encoded keyword (rf2-mnp93.1) decoded symmetrically with
  `keyword->id-string` so a namespaced keyword delay (`:a/b` →
  `after.a__b`) round-trips its namespace. Shared by `consume-transitions`
  and `parse-root-parallel-transitions` (each keeps its own target decoder)."
  [event]
  (let [d-str (subs event 6)
        d     (try
                #?(:clj  (Long/parseLong d-str)
                   :cljs (let [n (js/parseInt d-str 10)]
                           (if (js/isNaN n) nil n)))
                (catch #?(:clj Exception :cljs :default) _ nil))]
    (or d (id-string->keyword d-str))))

(defn- simplify-candidates
  "rf2-mnp93.8 — finalise an accumulated candidate vector to the canonical
  SCXML-import shorthand, shared by `consume-transitions` and
  `parse-root-parallel-transitions`:

    - 1 candidate, target-only  → bare keyword/path target
      (matches the canonical `:on {:event :target}` shorthand)
    - 1 candidate, guard/action → the candidate map verbatim
    - N candidates              → the vector of full maps verbatim

  A MULTI-candidate vector keeps each element in its explicit map form so a
  MIXED vector (`[{:target :a :guard :g1} {:target :b}]`) round-trips
  value-equal rather than collapsing `{:target :b}` to a bare `:b`."
  [cands]
  (if (= 1 (count cands))
    (let [c (first cands)]
      (if (= [:target] (keys c)) (:target c) c))
    cands))

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
                  ;; rf2-9dj21r — the SCXML external/internal axis. `type=
                  ;; "external"` round-trips to the re-frame2 `:reenter? true`
                  ;; opt-in (Spec 005 §Self-transitions / XState v5). Only a
                  ;; target-bearing transition can be external (a targetless
                  ;; transition has no state to re-enter), matching the
                  ;; emitter's `(and target reenter?)` guard. Any other `type`
                  ;; value (incl. the SCXML default `internal`) leaves
                  ;; `:reenter?` unset — the re-frame2 internal default.
                  type-s   (get attrs "type")
                  reenter? (and target (= type-s "external"))
                  ;; rf2-mnp93.5 — the action name lifted from the
                  ;; `<!-- action: NAME -->` comment by `lift-action-comments`
                  ;; (a single keyword, never a vector path — decode it the
                  ;; same way the guard `cond=` does).
                  action-s (get attrs action-attr)
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
                             guard-s (assoc :guard (id-string->keyword guard-s))
                             ;; rf2-9dj21r — decode the external-restart axis.
                             ;; `:reenter?` is added (and stays in the explicit
                             ;; map form because the candidate is then no longer
                             ;; target-only, so `simplify` keeps it verbatim
                             ;; rather than collapsing to a bare keyword) ONLY
                             ;; when `type="external"` rode a target.
                             reenter? (assoc :reenter? true)
                             ;; rf2-mnp93.5 — recover the action so an INTERNAL
                             ;; action transition (`:on {:tick {:action :log}}`)
                             ;; round-trips to a VALID Spec-005 internal action
                             ;; transition (`{:action :log}`) — NOT the empty
                             ;; `{}` FORBIDDEN BLOCK the comment-strip used to
                             ;; synthesise (a semantic inversion).
                             action-s (assoc :action (id-string->keyword action-s)))]
              (cond
                (and event (str/starts-with? event "after."))
                (update-in acc [:after (parse-after-key event)] (fnil conj []) cand-map)

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
    ;; rf2-mnp93.8 — finalise the candidate vectors via the shared
    ;; `simplify-candidates`. `:always` keeps the full vector-of-maps form so
    ;; it lines up with `(transition-candidates ...)`.
    (let [on*      (when (:on coll)
                     (into {} (map (fn [[ev cands]] [ev (simplify-candidates cands)]) (:on coll))))
          after*   (when (:after coll)
                     (into {} (map (fn [[k cands]] [k (simplify-candidates cands)]) (:after coll))))
          ondone*  (when (contains? coll :on-done) (simplify-candidates (:on-done coll)))]
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
                                       (error/throw-error!
                                         :scxml/parse-error
                                         'machines-viz/scxml->spec
                                         (str "SCXML import: unclosed <" tag
                                              "> element; add the matching </"
                                              tag "> closing tag.")
                                         {:recovery :close-the-open-element
                                          :extra    {:tag tag}})

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

(defn- parse-history-block
  "rf2-m285a — parse a W3C SCXML `<history>` pseudo-state element back into
  a re-frame2 `[state-id {:type :history …}]` node (Spec 005 §History
  states). `type=\"deep\"` ⇒ `:deep? true`; `type=\"shallow\"` (or absent)
  ⇒ `:deep? false`. An OPTIONAL child default `<transition target=\"…\"/>`
  (W3C SCXML §3.10) round-trips to `:default-target`, decoded back to its
  relative grammar form (sibling keyword / absolute vector) via
  `decode-target` — the exact inverse of `emit-history`."
  [{:keys [start body self?]}]
  (let [attrs      (:attrs start)
        id-str     (get attrs "id")
        abs-path   (id-string->abs-path id-str)
        state-id   (abs-path->local-key abs-path)
        deep?      (= "deep" (get attrs "type"))
        ;; The default transition is the SINGLE direct-child <transition>
        ;; of the <history> element (target-only — no event / guard).
        default-tt (when-not self?
                     (let [t (->> (direct-transitions body)
                                  (some #(get-in % [:attrs "target"])))]
                       (when t (decode-target t abs-path))))
        node (cond-> {:type :history :deep? deep?}
               (some? default-tt) (assoc :default-target default-tt))]
    [state-id node]))

(defn- parse-state-block
  "Parse one `<state>` / `<final>` block into a `[state-id state-node]`
  pair. `self?` indicates a self-closing tag with no children.

  rf2-mnp93.7 — `id` is a FULLY-QUALIFIED unique xsd:ID (the state's
  absolute path). The LOCAL `:states` key is its last segment; the
  state's absolute path is threaded down so child transitions decode
  their qualified targets back to the relative grammar form.

  rf2-m285a — a `<history>` pseudo-state child is routed to
  `parse-history-block` (it is `:type :history`, not an ordinary state)."
  [{:keys [start body self?] :as block}]
  (if (= "history" (:tag start))
    (parse-history-block block)
   (let [tag        (:tag start)
        attrs      (:attrs start)
        id-str     (get attrs "id")
        initial-str (get attrs "initial")
        abs-path   (id-string->abs-path id-str)
        state-id   (abs-path->local-key abs-path)
        base       (cond-> {}
                     (= "final" tag) (assoc :final? true)
                     ;; EP-0011 — recover the error-terminal KIND from the
                     ;; re-frame2 carrier (`emit-state`). Only meaningful on
                     ;; a `<final>`; the parent's `:on-error` vs `:on-done`
                     ;; routing (`:status :error` vs `:status :ok`) depends
                     ;; on this bit surviving the round-trip.
                     (and (= "final" tag)
                          (= "true" (get attrs error-final-attr)))
                     (assoc :error? true)
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
        [state-id node])))))

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

(defn- parse-root-parallel-transitions
  "rf2-656ivk / rf2-m3otj2 — recover the parallel-ROOT's own `:on` / `:after`
  ancestor-fallback transitions (the inverse of `emit-root-parallel-on` /
  `emit-root-parallel-after`). These are DIRECT `<transition>` children of
  `<parallel>` (alongside the `done.state.<id>` `:on-done`). Their `target` is
  a SPACE-SEPARATED region-qualified id list decoded by
  `decode-root-parallel-target` — NOT the source-relative grammar
  `consume-transitions`/`decode-target` use — so we parse them directly here.

  Returns `{:on {..} :after {..}}` (omitting empty slots). The
  `done.state.*` completion transitions are left for the existing `:on-done`
  recovery path. Each candidate is finalised to the canonical shorthand: a
  SOLE target-only candidate collapses to the bare target vector; a guard /
  action / multi candidate stays in the explicit map form."
  [parallel-body]
  (let [ts (direct-transitions parallel-body)
        coll
        (reduce
          (fn [acc t]
            (let [attrs    (:attrs t)
                  event    (get attrs "event")
                  target   (get attrs "target")
                  guard-s  (get attrs "cond")
                  action-s (get attrs action-attr)
                  cand-map (cond-> {}
                             target   (assoc :target (decode-root-parallel-target target))
                             guard-s  (assoc :guard (id-string->keyword guard-s))
                             action-s (assoc :action (id-string->keyword action-s)))]
              (cond
                ;; rf2-41goo — the whole-parallel completion stays on :on-done
                ;; (recovered separately); skip it here.
                (and event (str/starts-with? event "done.state."))
                acc

                (and event (str/starts-with? event "after."))
                (update-in acc [:after (parse-after-key event)] (fnil conj []) cand-map)

                (some? event)
                (update-in acc [:on (id-string->keyword event)]
                           (fnil conj []) cand-map)

                :else acc)))
          {}
          ts)]
    ;; rf2-mnp93.8 — same shorthand finalisation `consume-transitions` uses
    ;; (the shared `simplify-candidates`): a sole target-only candidate
    ;; collapses to its bare target.
    (cond-> {}
      (:on coll)    (assoc :on    (into {} (map (fn [[ev cs]] [ev (simplify-candidates cs)]) (:on coll))))
      (:after coll) (assoc :after (into {} (map (fn [[k cs]] [k (simplify-candidates cs)]) (:after coll)))))))

(defn scxml->spec
  "Parse an SCXML XML string into a re-frame2 machine spec.

  Inverse of `spec->scxml`:

  ```clojure
  (= machine-spec (-> machine-spec spec->scxml scxml->spec))
  ```

  for the supported subset documented in the ns docstring.

  Throws the canonical thrown-error shape with
  `:rf.error/id :scxml/parse-error` when the input is not a valid SCXML
  document our parser recognises (missing root `<scxml>`, unclosed tags,
  etc.). Throws `:scxml/invalid-spec` if
  the parsed structure is missing required keys (no `:initial`, no
  `:states`)."
  [scxml-string]
  (when-not (string? scxml-string)
    (error/throw-error!
      :scxml/parse-error
      'machines-viz/scxml->spec
      "SCXML import: scxml->spec expects an SCXML string; pass the SCXML document as a string."
      {:recovery :pass-an-scxml-string
       ;; rf2-8nzxib — value-FREE; never the raw input.
       :extra    {:input-summary (input-summary scxml-string)}}))
  (let [tokens (-> scxml-string strip-prolog lift-action-comments strip-comments tokenize vec)
        root-start (first (filter #(= "scxml" (:tag %)) tokens))]
    (when-not root-start
      (error/throw-error!
        :scxml/parse-error
        'machines-viz/scxml->spec
        (str "SCXML import: no <scxml> root element found; wrap the document "
             "in a top-level <scxml>…</scxml> element.")
        {:recovery :wrap-in-an-scxml-root}))
    (let [token-vec (vec tokens)
          start-idx (some (fn [i] (when (identical? (nth token-vec i) root-start) i))
                          (range (count token-vec)))
          root-body
          (let [;; Walk to the matching </scxml>
                tail (subvec token-vec (inc start-idx))
                end-idx (loop [i 0 depth 1]
                          (cond
                            (>= i (count tail))
                            (error/throw-error!
                              :scxml/parse-error
                              'machines-viz/scxml->spec
                              (str "SCXML import: unclosed <scxml> root element; "
                                   "add the matching </scxml> closing tag.")
                              {:recovery :close-the-scxml-root})

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
                          (error/throw-error!
                            :scxml/parse-error
                            'machines-viz/scxml->spec
                            (str "SCXML import: unclosed <parallel> element; "
                                 "add the matching </parallel> closing tag.")
                            {:recovery :close-the-parallel-element})

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
              parallel-on-done (:on-done (consume-transitions parallel-body []))
              ;; rf2-656ivk / rf2-m3otj2 — the parallel-root's OWN `:on` /
              ;; `:after` ancestor-fallback transitions are ALSO direct
              ;; `<parallel>` children, but carry region-qualified
              ;; (space-separated) targets `consume-transitions` mis-decodes;
              ;; parse them with the dedicated root resolver.
              root-on-after (parse-root-parallel-transitions parallel-body)]
          (cond-> {:type    :parallel
                   :regions (parse-parallel-body parallel-body)}
            (some? parallel-on-done) (assoc :on-done parallel-on-done)
            (:on root-on-after)      (assoc :on (:on root-on-after))
            (:after root-on-after)   (assoc :after (:after root-on-after))))

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
            (error/throw-error!
              :scxml/invalid-spec
              'machines-viz/scxml->spec
              (str "SCXML import: the document has no <state> or <final> "
                   "elements; add at least one <state> (or <final>) child.")
              {:recovery :add-a-state-or-final-element}))
          (cond-> {:states states}
            ;; rf2-mnp93.7 — the root `initial` is a top-level state's
            ;; qualified id; the re-frame2 `:initial` is its local key.
            initial-str (assoc :initial (abs-path->local-key
                                         (id-string->abs-path initial-str)))))))))
