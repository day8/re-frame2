(ns day8.re-frame2-machines-viz.chart.context-redaction
  "EP-0015 local-redacted projection for the chart's root Context band.

  ## Why this exists

  The Context band paints a `(key, value)` map the host feeds as
  `:context-band`. The SOLE production feeder is the static context
  SHAPE (key → type-caption — value-free; see `context-shape`). But the
  contract also lets a host feed the LIVE machine `:data` VALUES
  (`:context-band-inferred? false`). Those values land in the rendered
  DOM (`chart.nodes/root-container-node`), and the SVG / PNG / clipboard
  exporters serialise that DOM (`export/chart-as-svg` clones the live
  `.react-flow__viewport`). So a host that feeds live `:data` carrying a
  declared **sensitive** or **large** slot would otherwise embed the
  raw value in an official egress artefact with no local-redacted default.

  Per [EP-0015](../../../docs/EP/EP-0015-frame-owned-egress-policy.md)
  §96-110, §985-989: framework-created export/copy artefacts are egress;
  local tools default `:rf.egress/local-redacted`; raw requires an
  explicit trusted-local opt-in. Spec 015 §Subsystem projection-relative
  classification / Spec 005: a machine's durable `:data` classification
  is **declared on the machine definition** as projection-relative
  `:sensitive` / `:large` paths (`{:sensitive [[:data :token]]}`). The
  `[:schemas :data]` schema validates `:data`, and its `:sensitive?` /
  `:large?` props drive only validation-failure-trace redaction, so they do
  not classify the band.

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
  (`derive-classification` extracts them from the machine definition's own
  declaration; a whole-`:data` declaration yields a set carrying a
  whole-data marker that covers every key, runtime-only keys included), so
  this namespace stays dependency-free and JVM-portable.

  The static type-caption shape is already value-free, so redaction over
  it is a no-op (captions like `\"string\"` are neither sensitive markers
  nor large). The default-on redaction therefore never changes the
  production surface; it only fires for a host feeding live values.")

;; ---------------------------------------------------------------------------
;; Classification extraction from the machine definition's declaration

(def ^:private whole-data
  "The WHOLE-`:data` marker. A classification set carrying it classifies
  EVERY band key — including a key the live `:data` first gains at runtime,
  which no expansion over the definition's initial `:data` could name.
  It rides INSIDE the key set, so the set still flows through
  the documented recipe and the `:context-band-sensitive` /
  `:context-band-large` props unchanged."
  ::whole-data)

(defn derive-classification
  "Extract `{:sensitive #{k …} :large #{k …}}` — sets of Context-band KEYS —
  from a machine DEFINITION's own projection-relative `:sensitive` / `:large`
  declaration (Spec 015 §Subsystem projection-relative
  classification, Spec 005 — e.g. `{:sensitive [[:data :payment :token]]}`).
  Pure.

    - `[:data k …]` names band key `k`. The band prints each top-level
      `:data` value WHOLE, so a deeper path classifies its whole top-level
      slot (`[:data :payment :token]` redacts `:payment`).
    - A bare `[:data]` (or the whole-snapshot `[]`) keeps WHOLE-data scope:
      the set carries the whole-data marker, which classifies every band
      key, runtime-only keys included.
    - Any other path not rooted at `:data` names no band key and is ignored.

  The `[:schemas :data]` schema's `:sensitive?` / `:large?` props are NOT
  read: they drive only validation-failure-trace redaction. Returns `{:sensitive #{} :large #{}}` when nothing is
  declared."
  [definition]
  (let [band-keys (fn [paths]
                    (into #{}
                          (mapcat (fn [p]
                                    (when (sequential? p)
                                      (cond
                                        (empty? p)             [whole-data]
                                        (not= :data (first p)) nil
                                        (next p)               [(second p)]
                                        :else                  [whole-data]))))
                          (when (sequential? paths) paths)))]
    {:sensitive (band-keys (:sensitive definition))
     :large     (band-keys (:large definition))}))

(defn- classifies?
  "Does the classification key set `ks` cover band key `k`?"
  [ks k]
  (or (contains? ks k) (contains? ks whole-data)))

;; ---------------------------------------------------------------------------
;; Large-value heuristic

(def ^:private default-large-char-cap
  "A printed value longer than this many CHARACTERS is elided as large
  even when the machine did not declare it `:large`. A defensive size guard
  so a big unmarked slot cannot bloat (and leak its content into) the
  export. Conservative — ordinary context values render well under this.

  Two units live side by side here, on purpose: this CAP is characters
  (what the band paints), while the `:rf.size/large-elided` marker's
  `:bytes` slot is UTF-8 bytes (the framework's wire vocabulary)."
  512)

(defn- printed-size
  "The CHARACTER length of `(pr-str v)` — UTF-16 code units, which is what
  `count` answers on both hosts — i.e. how much TEXT the band would
  otherwise render. This is the ruler the large heuristic uses, and
  `default-large-char-cap` is named for it.

  Characters, deliberately: the cap guards how much a chart band paints,
  which is a count of glyphs on screen, not of octets on a wire. It is
  the `:bytes` MARKER slot that must speak the framework's unit — see
  `utf8-bytes` below."
  [v]
  (count (pr-str v)))

(defn- utf8-bytes
  "UTF-8 BYTE length of `(pr-str v)` — the figure the `:rf.size/large-elided`
  marker publishes, matching what `re-frame.elision`'s walker means by
  `:bytes` so a machines-viz chip and a framework one report the same
  quantity.

  Carrying `printed-size` in the `:bytes` slot — UTF-16 CODE UNITS under a
  byte name — would fail OPEN: the two rulers agree exactly on ASCII, so
  the wrong expression prints the right number and a green suite never
  notices, while a context value carrying an em-dash or an emoji
  under-reports by up to 3x (4x for astral code points).

  `TextEncoder` and not `Buffer.byteLength`: this ns compiles into the
  BROWSER viewer bundle (`:machines-viz-viewer`, and under `:advanced`),
  where `Buffer` is not there; `^js` hints so `:advanced` cannot rename
  the call. TextEncoder is UTF-8 BY DEFINITION and carries no encoding
  argument a later edit could silently drop. Same helper shape as
  `day8.re-frame2-xray.panels.epoch.format/pr-str-bytes`."
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
  vocabulary and must mean what the framework means by it. The two agree
  on ASCII and diverge on everything else, so an ASCII-only suite cannot
  tell them apart."
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

  A set carrying the whole-data marker (`derive-classification` of a bare
  `[:data]`) contains every key. Sensitive WINS over large (EP-0015).
  Returns the value to render."
  [k v {:keys [sensitive large large-char-cap]
        :or   {sensitive #{} large #{} large-char-cap default-large-char-cap}}]
  (cond
    (classifies? sensitive k) :rf/redacted
    (or (classifies? large k)
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
  in any serialised export. Everything else renders via `pr-str`."
  [v]
  (cond
    (= :rf/redacted v) "🔒 :rf/redacted"
    (and (map? v) (contains? v :rf.size/large-elided))
    (let [bytes (:bytes (:rf.size/large-elided v))]
      (str "… :rf.size/large-elided"
           (when bytes (str " {:bytes " bytes "}"))))
    :else (pr-str v)))
