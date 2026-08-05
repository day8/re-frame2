(ns day8.re-frame2-machines-viz.chart.context-redaction
  "EP-0015 local-redacted projection for the chart's root Context band.

  ## Why this exists

  The Context band paints a `(key, value)` map the host feeds as
  `:context-band`. The SOLE production feeder today is the static context
  SHAPE (key → type-caption — value-free; see `context-shape`). But the
  contract also lets a host feed the LIVE machine `:data` VALUES
  (`:context-band-inferred? false`). Those values land in the rendered
  DOM (`chart.nodes/root-container-node`), and the SVG / PNG / clipboard
  exporters serialise that DOM (`export/chart-as-svg` clones the live
  `.react-flow__viewport`). So a host that feeds live `:data` carrying a
  schema-marked **sensitive** or **large** slot would otherwise embed the
  raw value in an official egress artefact with no local-redacted default.

  Per [EP-0015](../../../docs/EP/EP-0015-frame-owned-egress-policy.md)
  §96-110, §985-989: framework-created export/copy artefacts are egress;
  local tools default `:rf.egress/local-redacted`; raw requires an
  explicit trusted-local opt-in. EP-0015 issue 12 / Spec 015 §Machine
  `:data`: machine `:data` sensitivity is **schema-first** — per-slot
  `:sensitive?` / `:large?` props on the machine's `[:schemas :data]`
  schema (the EP-0029 A3 clean-break home for what EP-0005 called
  `:data-schema`).

  ## What this does

  `redact-context` projects the band's `(key, value)` map to a
  local-redacted display map BEFORE it reaches the DOM:

    - a key declared **sensitive** projects to the sentinel `:rf/redacted`;
    - a **large** value projects to the canonical `:rf.size/large-elided`
      marker (size diagnostic preserved; NO content head — a head fragment
      would leak into the export);
    - sensitive WINS over large (the EP-0015 ordering);
    - everything else passes through unchanged.

  Sensitivity is supplied as a SET of sensitive keys + a SET of large keys
  (`derive-classification` extracts them from a `[:schemas :data]` schema), so
  this namespace stays dependency-free and JVM-portable (no Malli runtime — it
  reads the schema as plain data, mirroring `context-shape`).

  The static type-caption shape is already value-free, so redaction over
  it is a no-op (captions like `\"string\"` are neither sensitive markers
  nor large). The default-on redaction therefore never changes the
  production surface; it only fires for a host feeding live values.")

;; ---------------------------------------------------------------------------
;; Classification extraction from a [:schemas :data] schema (plain-data walk)

(def ^:private map-unwrap-heads
  "Malli wrapper heads whose first contained schema carries the real map —
  mirrors `context-shape/map-unwrap-heads`."
  #{:and :or :schema :ref})

(defn- map-schema
  "Unwrap common Malli wrappers to the contained `[:map …]` vector, else
  nil. Mirrors `context-shape/map-schema` (kept local so this ns carries
  no cross-dep on the caption namespace)."
  [schema]
  (loop [s schema budget 32]
    (when (and (vector? s) (pos? budget))
      (let [head (first s)]
        (cond
          (= :map head) s
          (contains? map-unwrap-heads head)
          (recur (some #(when-not (map? %) %) (rest s)) (dec budget))
          :else nil)))))

(defn- entry-props
  "The per-entry props map of a Malli `:map` entry `[k props? child]`, or
  nil. An entry is `[k child]` or `[k {…} child]`."
  [entry]
  (let [maybe-props (second entry)]
    (when (map? maybe-props) maybe-props)))

(defn derive-classification
  "Extract `{:sensitive #{k …} :large #{k …}}` from a machine's
  `[:schemas :data]` schema by reading the per-slot `:sensitive?` / `:large?`
  props on each `:map` entry (EP-0005 / Spec 015 §Machine `:data`). Pure;
  walks the schema as plain data — no Malli runtime. Returns
  `{:sensitive #{} :large #{}}` when the schema is absent or carries no
  `:map`."
  [data-schema]
  (if-let [ms (map-schema data-schema)]
    (let [entries (->> (rest ms) (filter vector?))]
      (reduce (fn [acc entry]
                (let [k     (first entry)
                      props (entry-props entry)]
                  (cond-> acc
                    (:sensitive? props) (update :sensitive conj k)
                    (:large? props)     (update :large conj k))))
              {:sensitive #{} :large #{}}
              entries))
    {:sensitive #{} :large #{}}))

;; ---------------------------------------------------------------------------
;; Large-value heuristic

(def ^:private default-large-char-cap
  "A printed value longer than this many CHARACTERS is elided as large
  even when the schema did not mark it `:large?`. A defensive size guard
  so a big unmarked slot cannot bloat (and leak its content into) the
  export. Conservative — ordinary context values render well under this.

  Two units live side by side here, on purpose: this CAP is characters
  (what the band paints), while the `:rf.size/large-elided` marker's
  `:bytes` slot is UTF-8 bytes (the framework's wire vocabulary). The
  cap's threshold therefore does not move under rf2-2rtt6.132 — nothing
  that rendered inline before is elided now, and nothing that was elided
  is admitted."
  512)

(defn- printed-size
  "The CHARACTER length of `(pr-str v)` — UTF-16 code units, which is what
  `count` answers on both hosts — i.e. how much TEXT the band would
  otherwise render. This is the ruler the large heuristic uses, and
  `default-large-char-cap` is named for it.

  Characters, deliberately: the cap guards how much a chart band paints,
  which is a count of glyphs on screen, not of octets on a wire. It is
  the `:bytes` MARKER slot that must speak the framework's unit — see
  `utf8-bytes` below (rf2-2rtt6.132)."
  [v]
  (count (pr-str v)))

(defn- utf8-bytes
  "UTF-8 BYTE length of `(pr-str v)` — the figure the `:rf.size/large-elided`
  marker publishes, matching what `re-frame.elision`'s walker means by
  `:bytes` so a machines-viz chip and a framework one report the same
  quantity.

  Until rf2-2rtt6.132 the marker's `:bytes` slot carried `printed-size`,
  i.e. UTF-16 CODE UNITS under a byte name. That fails OPEN: the two
  rulers agree exactly on ASCII, so the wrong expression printed the
  right number and a green suite never noticed — until a context value
  grew an em-dash or an emoji, at which point the published figure
  under-reported by up to 3x (4x for astral code points).

  `TextEncoder` and not `Buffer.byteLength`: this ns compiles into the
  BROWSER viewer bundle (`:machines-viz-viewer`, and under `:advanced`),
  where `Buffer` is not there; `^js` hints so `:advanced` cannot rename
  the call. TextEncoder is UTF-8 BY DEFINITION and carries no encoding
  argument a later edit could silently drop. Same helper shape as
  `day8.re-frame2-xray.panels.epoch.format/pr-str-bytes` (rf2-2rtt6.131)."
  [v]
  (let [s (pr-str v)]
    #?(:clj  (alength (.getBytes ^String s "UTF-8"))
       :cljs (let [^js enc (js/TextEncoder.)
                   ^js buf (.encode enc s)]
               (.-length buf)))))

(defn- value-type
  "Coarse type tag for the `:rf.size/large-elided` marker payload.
  Mirrors `re-frame.elision/value-type` (inlined — this ns carries no
  dependency on the core runtime graph)."
  [v]
  (cond
    (map? v)    :map
    (vector? v) :vector
    (set? v)    :set
    (string? v) :string
    :else       :scalar))

(defn- large-marker
  "Build the canonical content-FREE `:rf.size/large-elided` marker for a
  large context VALUE `v` at single-key path `[k]`. Mirrors the SHAPE of
  `re-frame.classification/large-marker` (a single-key map `{:rf.size/large-elided
  {…}}`) so the projected map is recognised by every consumer of the
  framework's large-elision vocabulary (e.g. xray's `large-sentinel?`,
  mcp-base's `count-elided-markers`) — inlined because machines-viz is
  intentionally JVM-portable / plain-data and cannot `:require` the core
  elision namespace.

  Content-free: carries only the size diagnostic (`:bytes`/`:type`) and
  provenance (`:path`/`:reason`), never a content head, preserving the
  export-safety property.

  `:bytes` is UTF-8 bytes (`utf8-bytes`), NOT the `printed-size`
  characters the cap is measured in — the slot is the framework's wire
  vocabulary and must mean what the framework means by it
  (rf2-2rtt6.132). The two agree on ASCII and diverge on everything
  else, which is precisely why the mismatch went unnoticed."
  [k v]
  {:rf.size/large-elided
   {:path   [k]
    :bytes  (utf8-bytes v)
    :type   (value-type v)
    :reason :schema}})

;; ---------------------------------------------------------------------------
;; Projection

(defn redact-value
  "Project one context VALUE under the resolved classification.

    - sensitive (key ∈ `sensitive`)  → `:rf/redacted`
    - large (key ∈ `large`, OR printed size > `large-char-cap`)
                                      → `:rf.size/large-elided` marker (no head)
    - otherwise                       → `v` unchanged

  Sensitive WINS over large (EP-0015). Returns the value to render."
  [k v {:keys [sensitive large large-char-cap]
        :or   {sensitive #{} large #{} large-char-cap default-large-char-cap}}]
  (cond
    (contains? sensitive k) :rf/redacted
    (or (contains? large k)
        (> (printed-size v) large-char-cap))
    (large-marker k v)
    :else v))

(defn redact-context
  "Project the band's `(key, value)` map to a local-redacted display map
  (EP-0015 `:rf.egress/local-redacted`). `classification` is
  `{:sensitive #{k} :large #{k} :large-char-cap N?}`. Order-preserving.

  Returns the redacted map; `nil` / empty in → `nil` out (band hidden)."
  [context-band classification]
  (when (seq context-band)
    (into (empty context-band)
          (map (fn [[k v]] [k (redact-value k v classification)]))
          context-band)))

;; ---------------------------------------------------------------------------
;; Display rendering — what the band paints for a (possibly redacted) value

(defn display-string
  "The display TEXT the band paints for a (possibly redacted) value `v`.
  `:rf/redacted` and the canonical `:rf.size/large-elided` marker render
  as stable, content-FREE sentinels so they read clearly in the chart AND
  in any serialised export. Everything else renders via `pr-str` (the
  prior behaviour)."
  [v]
  (cond
    (= :rf/redacted v) "🔒 :rf/redacted"
    (and (map? v) (contains? v :rf.size/large-elided))
    (let [bytes (:bytes (:rf.size/large-elided v))]
      (str "… :rf.size/large-elided"
           (when bytes (str " {:bytes " bytes "}"))))
    :else (pr-str v)))
