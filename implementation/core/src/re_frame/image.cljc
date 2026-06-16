(ns re-frame.image
  "`rf/image` — the public registration-set VALUE, plus the namespace-glob
  selector that projects descriptors out of a registration source store
  (EP-0023 §Image, §Namespace-Selected Images, §Image Fragments).

  > An image is not state. It is not the running object. It is not the event
  > stream. It answers one question: which registrations are visible to this
  > frame?

  This namespace is the FOUNDATION slice of the EP-0023 wave (rf2-32siq3.3):
  the constructor (`image`), the normalized image value, the exact `:include-ns`
  glob grammar, inline `:registrations` lowering, and the PURE selector that
  given a collection of descriptors — each carrying its source-code provenance
  namespace at `:rf.provenance/ns` — returns the subset an image selects. It is
  self-contained, pure logic.

  ## What this slice OWNS and what it defers

  OWNED here:

    * `image` — the public `rf/image` constructor (PURE; no realm, no
      registrar, no side effect — an image value is inert data, EP-0023
      §Public API \"An image is not itself a registration entry; it is a
      selected registration-set value\").
    * the normalized image VALUE shape (see §The image value below).
    * the `:include-ns` glob grammar — dot-separated namespace strings, `*`
      matches exactly one segment, `**` matches zero or more segments,
      case-sensitive whole-namespace matching (EP-0023 §Namespace-glob
      language).
    * inline `:registrations` — registrar-keyed sections (`:reg-event`,
      `:reg-sub`, …) lowered to inline descriptors carrying inline provenance
      (EP-0023 §Image Fragments).
    * `select-descriptors` — the PURE selector: given a collection of
      descriptors carrying `:rf.provenance/ns` and an image value, return the
      selected subset (matched-by-glob registered descriptors PLUS the image's
      inline descriptors), with zero-match `:include-ns` patterns failing loud
      (EP-0023 §Namespace-Selected Images \"Zero matches are fail-loud\").

  DEFERRED to sibling slices (NOT touched here):

    * the provenance-preserving registration SOURCE STORE keyed by
      `[kind id provenance-namespace]` (slice .2) — the live store that
      PRODUCES the descriptors this selector consumes. This slice tests the
      selector against SYNTHETIC descriptor collections so the two slices can
      land in parallel; the live wiring is slice .4 (assembly).
    * image ASSEMBLY into a sealed `[kind id]` generation, collision
      validation, framework-standard registrations, replacement winners
      (`:replace` / `:replace-standard`), capability checks, and resolved-
      generation caching (later wave beads).
    * `make-frame` / `reload-images!` frame loading (later wave beads).

  ## The selector contract (input shape from the source store)

  `select-descriptors` consumes a COLLECTION (seq) of descriptor maps. The
  ONLY field this slice reads off each descriptor is:

    :rf.provenance/ns   the source-code namespace STRING the descriptor was
                        authored in (EP-0023 §Registration Source Store — the
                        canonical-string provenance stamp every registered
                        `reg-*` descriptor carries). Selection is BY THIS
                        STRING, never by the registration-id's namespace: a
                        descriptor with id `:counter/inc` is selected because
                        it was authored in `\"docs.quickstart.counter.v2\"`,
                        not because the keyword starts with `counter`.

  Other descriptor fields (`:kind`, `:id`, `:impl`/`:handler`, source coords,
  metadata) are carried THROUGH untouched — the selector neither requires nor
  inspects them. That keeps this slice decoupled from slice .2's exact
  descriptor shape: as long as a registered descriptor carries
  `:rf.provenance/ns`, the selector works. (Descriptors with NO
  `:rf.provenance/ns` — e.g. framework-standard or programmatic ones — are
  never matched by an `:include-ns` glob, exactly as the EP requires:
  \"namespaces must already be loaded\"; selection chooses from registrations
  the runtime already knows about, by provenance string.)

  ## Production elision

  Pure data + string ops over plain maps; no trace emit sites, no DEBUG-gated
  branches, no feature sentinels. The only require is `re-frame.error` (already
  in the core spine) for fail-loud zero-match / malformed-input diagnostics. An
  image value is inert; nothing here runs on the registration hot path, so in
  an app that never constructs an image these fns are dead code Closure DCE
  removes."
  (:require [clojure.string :as str]
            [re-frame.error :as error]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the namespace-glob grammar (EP-0023 §Namespace-glob language) ---------
;;
;;   namespace        = dot-separated Clojure namespace string
;;   pattern          = segment-pattern ( "." segment-pattern )*
;;   segment-pattern  = literal segment | "*" | "**"
;;   "*"              = exactly one dot-free namespace segment
;;   "**"             = zero or more namespace segments
;;   match            = case-sensitive, whole-namespace match
;;
;; There is no substring matching and no regex mode in v1. A pattern either
;; matches the WHOLE `:rf.provenance/ns` string under the segment rules or it
;; does not. Matching is a backtracking segment walk (the only backtracking
;; source is `**`, which may absorb zero or more segments).

(defn- split-segments
  "Split a dot-separated namespace string into its segment vector. An empty
  string yields `[\"\"]` (a single empty segment) — but namespaces are never
  empty in practice; this is the literal `clojure.string/split` behaviour and
  the matcher's literal-segment compare handles it without a special case."
  [s]
  (str/split s #"\." -1))

(defn- match-segments?
  "Case-sensitive backtracking match of a `pattern` segment vector against a
  `ns` segment vector under the EP-0023 grammar:

    literal  — matches exactly that one segment (string =)
    \"*\"      — matches exactly ONE segment (any)
    \"**\"     — matches ZERO OR MORE segments (greedy with backtrack)

  Pure. Returns true iff the whole pattern matches the whole ns segments."
  [pattern ns-segs]
  (cond
    ;; Both exhausted — full match.
    (and (empty? pattern) (empty? ns-segs))
    true

    ;; Pattern exhausted but ns segments remain — no match.
    (empty? pattern)
    false

    :else
    (let [p (first pattern)]
      (cond
        ;; "**" — zero or more segments. Try consuming zero (advance the
        ;; pattern, keep the ns) first, else consume one ns segment and retry
        ;; the same "**". This ordering makes "**" match the EMPTY tail too
        ;; (e.g. "docs.shared.**" matches "docs.shared").
        (= "**" p)
        (or (match-segments? (rest pattern) ns-segs)
            (and (seq ns-segs)
                 (match-segments? pattern (rest ns-segs))))

        ;; Any remaining single-segment pattern ("*" or a literal) needs at
        ;; least one ns segment to consume.
        (empty? ns-segs)
        false

        ;; "*" — exactly one segment (any).
        (= "*" p)
        (match-segments? (rest pattern) (rest ns-segs))

        ;; literal — must equal the ns segment (case-sensitive).
        :else
        (and (= p (first ns-segs))
             (match-segments? (rest pattern) (rest ns-segs)))))))

(defn ns-matches?
  "True iff the namespace string `ns-str` matches the `:include-ns` glob
  `pattern` under the EP-0023 grammar (case-sensitive whole-namespace match;
  `*` = one segment, `**` = zero or more). Both args are strings. Pure.

  This is the single matching primitive: exact inclusion is a pattern with no
  wildcard (`\"docs.quickstart.counter.v2\"`), prefix inclusion is a normal
  glob (`\"docs.shared.widgets.*\"`), recursive inclusion uses `**`
  (`\"docs.shared.**\"`)."
  [pattern ns-str]
  (boolean
    (and (string? pattern)
         (string? ns-str)
         (match-segments? (split-segments pattern) (split-segments ns-str)))))

;; ---- inline descriptors (EP-0023 §Image Fragments) ------------------------
;;
;; Inline `:registrations` are registrar-keyed sections that mirror the public
;; `reg-*` names. Each section's entries are call-shaped tuples:
;;
;;   [id metadata body]   ;; handler entry
;;   [id metadata]        ;; metadata-only entry
;;
;; Inline descriptors do NOT enter the provenance source store and are NOT
;; selected by `:include-ns`. They are selected because their containing image
;; value was supplied. They still need a descriptor source coordinate so errors
;; and replacements can name them (EP-0023):
;;
;;   {:kind :event
;;    :id :counter/inc
;;    :rf.provenance/image :test/small
;;    :rf.provenance/inline [:reg-event :counter/inc]
;;    :impl <fn>}
;;
;; The section→kind map mirrors `re-frame.registrar/kinds` (the Spec 001
;; closed taxonomy); the section KEY is the public `reg-*` spelling.

(def ^:private reg-section->kind
  "The inline `:registrations` section keys (the public `reg-*` spellings)
  mapped to their Spec 001 registry kind. Adding a registry kind that an inline
  image section can carry is a one-row addition here, kept in lockstep with the
  closed `re-frame.registrar/kinds` set."
  {:reg-event          :event
   :reg-sub            :sub
   :reg-fx             :fx
   :reg-cofx           :cofx
   :reg-interceptor    :interceptor
   :reg-view           :view
   :reg-frame          :frame
   :reg-route          :route
   :reg-head           :head
   :reg-error-projector :error-projector
   :reg-flow           :flow
   :reg-resource       :resource
   :reg-mutation       :mutation
   :reg-resource-scope :resource-scope})

(defn- inline-entry->descriptor
  "Lower one inline registrar-section entry into an inline descriptor. `entry`
  is a call-shaped tuple `[id metadata body]` (handler) or `[id metadata]`
  (metadata-only). `image-id` is the containing image's `:id` (nil for an
  anonymous image — valid for local tests/examples that do not participate in
  replacement, EP-0023). Pure.

  Stamps the inline source coordinate (`:rf.provenance/image` + the
  `:rf.provenance/inline [section id]` pair) so an inline descriptor has a
  stable name for errors and replacement winners. The body (when present) is
  carried under `:impl`; the metadata under `:metadata` (omitted when empty).

  The arity is EXACT: an inline entry MUST be a 3-tuple `[id metadata body]`
  (handler) or a 2-tuple `[id metadata]` (metadata-only) — EP-0023 §Image
  Fragments admits only those two shapes. A 1-tuple `[id]` (no metadata slot)
  and a 4+-tuple `[id metadata body extra…]` (a trailing slot that would be
  silently ignored) both fail loud rather than be coerced — a malformed
  registration entry is a typo or a stale call shape, not a metadata-only
  entry with `nil` metadata or an entry with an extra argument the framework
  drops."
  [image-id section kind entry]
  (when-not (and (vector? entry) (<= 2 (count entry) 3))
    (error/throw-error!
      :rf.error/invalid-image
      'rf/image
      (str "rf/image: inline " section " entry must be a [id metadata body] "
           "(handler) or [id metadata] (metadata-only) tuple — got " (pr-str entry)
           " (" (if (vector? entry) (str "arity " (count entry)) "not a vector")
           ").")
      {:recovery :use-a-call-shaped-tuple
       :extra    {:image image-id :section section :entry entry}}))
  (let [id       (nth entry 0)
        metadata (nth entry 1 nil)
        has-body (= 3 (count entry))
        body     (when has-body (nth entry 2))]
    (cond-> {:kind                kind
             :id                  id
             :rf.provenance/inline [section id]}
      image-id        (assoc :rf.provenance/image image-id)
      has-body        (assoc :impl body)
      (seq metadata)  (assoc :metadata metadata))))

(defn- registrations->inline-descriptors
  "Lower an image's `:registrations` map (`{:reg-event [[id meta body] …] …}`)
  into a vector of inline descriptors, stamped with the image id. Pure. Throws
  `:rf.error/invalid-image` on an unknown section key (fail loud rather than
  silently drop an entry)."
  [image-id registrations]
  (into []
        (mapcat
          (fn [[section entries]]
            (let [kind (reg-section->kind section)]
              (when-not kind
                (error/throw-error!
                  :rf.error/invalid-image
                  'rf/image
                  (str "rf/image: unknown inline registrations section " section
                       " — use a reg-* section key (:reg-event, :reg-sub, …).")
                  {:recovery :correct-the-section-key
                   :extra    {:image image-id :unknown-section section}}))
              (map #(inline-entry->descriptor image-id section kind %) entries))))
        registrations))

;; ---- the image value (EP-0023 §Image) -------------------------------------
;;
;; The normalized image value this slice produces:
;;
;;   :rf.image/id        the image id, when supplied (`:id` in the spec map).
;;                       Anonymous images (no `:id`) are valid for local
;;                       tests/examples that do not participate in replacement;
;;                       the slot is absent there. Owner-qualified per the
;;                       EP-0007 one-name-per-fact convention (a FACT about the
;;                       image), parallel to `:rf.app/id` / `:rf.module/id`.
;;   :rf.image/include-ns the vector of `:include-ns` glob patterns (always a
;;                       vector; `[]` when none supplied). The selector runs
;;                       each pattern against descriptor `:rf.provenance/ns`.
;;   :rf.image/inline    the vector of inline descriptors lowered from
;;                       `:registrations` (`[]` when none supplied). These are
;;                       selected unconditionally (their image was supplied),
;;                       never by `:include-ns`.
;;   :rf.image/requires  the set of `:rf.capability/*` requirements the image
;;                       declares (defaults to `#{}`). Carried through for the
;;                       later capability-check slice; this slice neither reads
;;                       nor enforces it. Owner-qualified FACT key.
;;
;; The image value is INERT data. Two `image` calls with equal spec maps return
;; equal values.

(def ^:private image-reserved-keys
  "Recognized top-level image spec keys. Any other key is a malformed image —
  failed loudly rather than silently ignored.

  `:replace` / `:replace-standard` are the EP-0023 §Image Patching And Overrides
  declared-winner maps (`{[kind id] winner-source-coordinate}`). They are BARE
  structural slots (the EP-0017 v5 line, per Conventions §`:rf.image/*`) carried
  through UNINTERPRETED here — the image value is inert data; the assembly slice
  (rf2-32siq3.4) reads them to resolve collisions. The constructor validates the
  STRUCTURAL shape of each entry (`validate-replacement-map!`): the map and each
  key/value, so a malformed key or winner coordinate fails loud at `rf/image`
  with an actionable diagnostic rather than reaching assembly's collision checks
  as a generic `nth not supported` destructuring error or a misleading
  no-collision report (rf2-32siq3.20). The SEMANTIC winner-coordinate validation
  AGAINST the actual selected collisions (does this key really collide? does the
  coordinate name exactly one selected descriptor?) stays the assembly slice's
  fail-loud job."
  #{:id :include-ns :registrations :rf.image/requires :replace :replace-standard})

(def ^:private valid-kinds
  "The closed registry-kind set a `:replace` / `:replace-standard` key may name,
  derived from `reg-section->kind`'s values so it stays in lockstep with the
  closed `re-frame.registrar/kinds` set without coupling this pure ns to the
  registrar (which would pull `interop` and friends onto this otherwise
  `re-frame.error`-only slice)."
  (set (vals reg-section->kind)))

(defn- valid-winner-coordinate?
  "True when `coord` is a structurally well-formed replacement winner SOURCE
  coordinate (EP-0023 §Image Fragments — the descriptor source-coordinate shape
  produced by `image-assembly/descriptor-coordinate`). One of:

    {:ns \"source.ns\"}                      a registered descriptor (string ns)
    {:image <id> :inline [section id]}      an inline descriptor
    {:standard true}                        a framework-standard descriptor

  Structural only — whether the coordinate actually names a selected descriptor
  is the assembly slice's semantic job. Pure."
  [coord]
  (boolean
    (and (map? coord)
         (or (and (string? (:ns coord)) (= #{:ns} (set (keys coord))))
             (and (some? (:image coord))
                  (let [inl (:inline coord)]
                    (and (vector? inl) (= 2 (count inl))))
                  (= #{:image :inline} (set (keys coord))))
             (and (true? (:standard coord)) (= #{:standard} (set (keys coord))))))))

(defn- validate-replacement-map!
  "Fail-loud STRUCTURAL validation of a `:replace` / `:replace-standard` map at
  the image boundary (rf2-32siq3.20). `map-key` is `:replace` or
  `:replace-standard` (named in diagnostics); `m` the declared-winner map;
  `image-id` the containing image's id (when known). Pure.

  Each entry MUST be an exact two-element `[kind id]` vector key — the `kind` a
  member of the closed registry-kind set — mapping to a supported winner source
  coordinate (`{:ns …}`, `{:image … :inline [section id]}`, or
  `{:standard true}`). A malformed key (non-vector, wrong arity, or an
  unsupported kind) or a malformed winner coordinate throws
  `:rf.error/invalid-image` with an actionable diagnostic BEFORE assembly's
  `check-replacement-keys-collide!` destructures the key — so a keyword key no
  longer surfaces as `nth not supported`, and a typo'd coordinate no longer
  masquerades as a stale winner. The SEMANTIC checks (real collision? winner
  names exactly one selected descriptor?) remain the assembly slice's job."
  [image-id map-key m]
  (doseq [[k winner] m]
    (when-not (and (vector? k) (= 2 (count k)))
      (error/throw-error!
        :rf.error/invalid-image
        'rf/image
        (str "rf/image: " map-key " key must be an exact two-element [kind id] "
             "vector — got " (pr-str k)
             " (" (if (vector? k) (str "arity " (count k)) "not a vector") ").")
        {:recovery :use-a-kind-id-pair-key
         :extra    {:image image-id :replace-map map-key :bad-key k}}))
    (let [kind (nth k 0)]
      (when-not (contains? valid-kinds kind)
        (error/throw-error!
          :rf.error/invalid-image
          'rf/image
          (str "rf/image: " map-key " key " (pr-str k) " names an unsupported "
               "registration kind " (pr-str kind) " — use one of "
               (pr-str (vec (sort valid-kinds))) ".")
          {:recovery :use-a-supported-registration-kind
           :extra    {:image image-id :replace-map map-key :bad-key k :bad-kind kind}})))
    (when-not (valid-winner-coordinate? winner)
      (error/throw-error!
        :rf.error/invalid-image
        'rf/image
        (str "rf/image: " map-key " winner for key " (pr-str k)
             " must be a source coordinate map — {:ns \"source.ns\"}, "
             "{:image image-id :inline [section id]}, or {:standard true} — got "
             (pr-str winner) ".")
        {:recovery :use-a-supported-winner-source-coordinate
         :extra    {:image image-id :replace-map map-key :bad-key k :bad-winner winner}}))))

(defn image
  "Construct an IMAGE value — a selected registration-set value, as INERT data
  (EP-0023 §Image, §Public API). PUBLIC (`rf/image`).

  `spec` carries:

    :id            the image id (optional). Stamped as `:rf.image/id`. An image
                   that participates in replacement (named as a `:replace` /
                   `:replace-standard` winner) MUST have an `:id`; anonymous
                   images are valid for local tests/examples. BARE structural
                   input key; normalized to the owner-qualified `:rf.image/id`.
    :include-ns    a vector of namespace-glob strings (EP-0023 grammar). Each
                   pattern selects registered descriptors by their
                   `:rf.provenance/ns`. Optional (defaults to `[]`).
    :registrations inline registrar-keyed sections (`{:reg-event [[id meta
                   body] …] :reg-sub [[id meta body] …] …}`). Lowered to inline
                   descriptors. Optional.
    :rf.image/requires the set of `:rf.capability/*` requirements (defaults to
                   `#{}`). Carried through for a later slice; not enforced here.
    :replace       a declared-winner map `{[kind id] winner-source-coordinate}`
                   for application-owned collisions (EP-0023 §Image Patching And
                   Overrides). Optional. BARE structural slot, carried through
                   uninterpreted; the assembly slice resolves it.
    :replace-standard the declared-winner map for framework STANDARD collisions
                   (louder — a standard must be marked replaceable). Optional.
                   Carried through uninterpreted.

  Returns a normalized image value:

    {:rf.image/id         <id>          ;; present only when :id supplied
     :rf.image/include-ns [<glob> …]
     :rf.image/inline     [<inline-descriptor> …]
     :rf.image/requires   #{<:rf.capability/* …>}
     :replace             {[kind id] winner …}   ;; present only when supplied
     :replace-standard    {[kind id] winner …}}  ;; present only when supplied

  PURE — no realm, no registrar, no side effect (an `image` call is data, not
  registration). Throws `:rf.error/invalid-image` when `:include-ns` is not a
  vector of strings, when an inline entry is not a `[id metadata]` /
  `[id metadata body]` tuple, when the spec carries an unknown top-level key,
  or when a `:replace` / `:replace-standard` entry is structurally malformed
  (a non-`[kind id]` key, an unsupported kind, or an unsupported winner source
  coordinate)."
  [spec]
  (when-not (map? spec)
    (error/throw-error!
      :rf.error/invalid-image
      'rf/image
      "rf/image expects a spec map — e.g. {:id :counter/v2 :include-ns [\"docs.counter.v2\"]}."
      {:recovery :pass-a-spec-map
       :extra    {:spec spec}}))
  (doseq [k (keys spec)]
    (when-not (contains? image-reserved-keys k)
      (error/throw-error!
        :rf.error/invalid-image
        'rf/image
        (str "rf/image: unknown image key " k
             " — use :id, :include-ns, :registrations, :rf.image/requires, "
             ":replace, or :replace-standard.")
        {:recovery :remove-or-correct-the-key
         :extra    {:image (:id spec) :unknown-key k}})))
  (let [id         (:id spec)
        include-ns (vec (get spec :include-ns []))]
    (doseq [p include-ns]
      (when-not (string? p)
        (error/throw-error!
          :rf.error/invalid-image
          'rf/image
          (str "rf/image: :include-ns patterns must be namespace-glob STRINGS — got "
               (pr-str p) ". Use \"docs.counter.v2\", \"docs.shared.widgets.*\", "
               "or \"docs.shared.**\".")
          {:recovery :use-namespace-glob-strings
           :extra    {:image id :bad-pattern p}})))
    (doseq [k [:replace :replace-standard]]
      (when-let [m (get spec k)]
        (when-not (map? m)
          (error/throw-error!
            :rf.error/invalid-image
            'rf/image
            (str "rf/image: " k " must be a map from a [kind id] pair to a "
                 "winner source coordinate — got " (pr-str m) ".")
            {:recovery :use-a-replacement-winner-map
             :extra    {:image id :bad-key k :received m}}))
        ;; Structural validation of each entry (rf2-32siq3.20): the [kind id]
        ;; key and the winner source coordinate, fail-loud before assembly.
        (validate-replacement-map! id k m)))
    (cond-> {:rf.image/include-ns include-ns
             :rf.image/inline     (registrations->inline-descriptors
                                    id (get spec :registrations {}))
             :rf.image/requires   (set (get spec :rf.image/requires #{}))}
      (some? id)               (assoc :rf.image/id id)
      (:replace spec)          (assoc :replace (:replace spec))
      (:replace-standard spec) (assoc :replace-standard (:replace-standard spec)))))

;; ---- the selector (EP-0023 §Namespace-Selected Images) --------------------
;;
;; The PURE projection step this slice delivers: given the image value and a
;; collection of registered descriptors (the source store's output — slice .2),
;; return the selected subset. Selection has two sources:
;;
;;   1. `:include-ns` globs — match registered descriptors by their
;;      `:rf.provenance/ns` string. Each pattern MUST match at least one
;;      descriptor or selection fails loud (zero-match diagnostic naming the
;;      image id, the pattern, and the loaded provenance namespaces).
;;   2. inline `:registrations` — selected unconditionally (their image was
;;      supplied), never by glob.
;;
;; The result preserves every selected descriptor exactly once (a registered
;; descriptor selected by two patterns is included once). Collision validation,
;; framework-standard registrations, and sealing are NOT this slice's job (the
;; assembly slice runs after selection).

(defn- descriptor-provenance-ns
  "The source-code provenance namespace string a registered descriptor carries
  at `:rf.provenance/ns`, or nil when it carries none (framework-standard /
  programmatic descriptors — never `:include-ns`-selectable). Pure."
  [descriptor]
  (:rf.provenance/ns descriptor))

(defn select-by-include-ns
  "Select the subset of `descriptors` whose `:rf.provenance/ns` matches AT
  LEAST ONE of the `:include-ns` `patterns` (EP-0023 grammar). Pure.

  Each pattern MUST match at least one descriptor; an `:include-ns` pattern
  matching zero descriptors throws `:rf.error/image-zero-match` (fail-loud,
  EP-0023 §Namespace-Selected Images — keeps typos, forgotten requires, DCE
  surprises, and stale namespace names from producing a silently incomplete
  image). The diagnostic names `image-id`, the zero-match pattern, and the
  loaded provenance namespaces considered.

  Selection is BY `:rf.provenance/ns`, never by the registration-id's
  namespace. Descriptors with no `:rf.provenance/ns` are never matched.
  Returns a vector of the selected registered descriptors, each at most once,
  in input order."
  [image-id patterns descriptors]
  (let [;; The loaded provenance namespaces considered — distinct, for the
        ;; zero-match diagnostic + the membership scan.
        loaded-ns (into (sorted-set)
                        (keep descriptor-provenance-ns)
                        descriptors)]
    ;; Fail loud on any zero-match pattern BEFORE returning a partial set.
    (doseq [pattern patterns]
      (when-not (some #(ns-matches? pattern %) loaded-ns)
        (error/throw-error!
          :rf.error/image-zero-match
          'rf/image
          (str "rf/image: :include-ns pattern " (pr-str pattern)
               " matched no loaded registration — every pattern must select at "
               "least one descriptor (a zero match is a typo, a forgotten "
               "require, a DCE-removed namespace, or a stale namespace name). "
               "Loaded provenance namespaces considered: "
               (pr-str (vec loaded-ns)) ".")
          {:recovery :fix-the-pattern-or-require-the-namespace
           :extra    {:image           image-id
                      :pattern         pattern
                      :loaded-ns       (vec loaded-ns)}})))
    ;; Select every descriptor matching any pattern, preserving input order and
    ;; including each descriptor at most once.
    (into []
          (filter (fn [d]
                    (when-let [pns (descriptor-provenance-ns d)]
                      (some #(ns-matches? % pns) patterns))))
          descriptors)))

(defn select-descriptors
  "The PURE image selector (EP-0023 §Namespace-Selected Images, §Image
  Fragments). Given an `image` value and a collection of registered
  `descriptors` (each carrying its source-code provenance namespace at
  `:rf.provenance/ns` — the source store's output, slice .2), return the subset
  the image selects:

    * registered descriptors whose `:rf.provenance/ns` matches one of the
      image's `:include-ns` globs (selected BY provenance namespace, never by
      the registration-id's namespace; each `:include-ns` pattern must match at
      least one descriptor or selection fails loud); PLUS
    * the image's inline descriptors (from `:registrations`), selected
      unconditionally because the image value was supplied.

  Returns a vector: the glob-selected registered descriptors (in input order,
  each at most once) followed by the inline descriptors. Collision validation
  across the selected set, framework-standard registrations, and sealing into a
  `[kind id]` generation are the ASSEMBLY slice's job, not this selector's.

  Pure — a function of the image value and the descriptor collection only.
  This is the surface the assembly slice (.4) calls to turn an image + the
  live source store's descriptors into the candidate registration set."
  [image descriptors]
  (let [image-id   (:rf.image/id image)
        patterns   (:rf.image/include-ns image)
        inline     (:rf.image/inline image)
        selected   (if (seq patterns)
                     (select-by-include-ns image-id patterns descriptors)
                     [])]
    (into (vec selected) inline)))
