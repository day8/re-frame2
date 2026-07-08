(ns re-frame.image
  "`rf/image` — the public registration-set VALUE, plus the namespace-glob
  selector that projects descriptors out of a registration source store
  (EP-0023 §Image, §Namespace-Selected Images, §Image Fragments).

  > An image is not state. It is not the running object. It is not the event
  > stream. It answers one question: which registrations are visible to this
  > frame?

  This namespace is the FOUNDATION slice of the EP-0023 wave:
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

  OWNED by sibling slices (NOT here):

    * the provenance-preserving registration SOURCE STORE keyed by
      `[kind id provenance-namespace]` (slice .2) — the live store that
      PRODUCES the descriptors this selector consumes. This selector works
      against any descriptor collection carrying `:rf.provenance/ns`; the live
      wiring is slice .4 (assembly).
    * image ASSEMBLY into a sealed `[kind id]` generation, collision
      validation, framework-standard registrations, image-order layering (the
      later image wins, EP-0026 §Layered Resolution), and resolved-generation
      caching.
    * `make-frame` frame loading (re-construction folds image hot-reload in).

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
;;   segment-pattern  = literal segment | intra-glob segment | "*" | "**"
;;   "*"              = exactly one dot-free namespace segment
;;   "**"             = zero or more namespace segments
;;   intra-glob       = a segment carrying one or more `*`, each matching ZERO
;;                      OR MORE characters WITHIN that one segment (never across
;;                      a `.`) — e.g. "*-cljs-test" matches "mount-cljs-test"
;;   match            = case-sensitive, whole-namespace match
;;
;; A pattern either matches the WHOLE `:rf.provenance/ns` string under the
;; segment rules or it does not. The intra-segment `*` is the narrow exception
;; to "no substring matching": it is bounded to ONE segment (the `.` separators
;; still delimit segments; only `**` crosses them) and lowers to an anchored
;; textual match — there is no regex mode exposed to callers. Matching is a
;; backtracking segment walk (the only multi-segment backtracking source is
;; `**`, which may absorb zero or more segments).

(defn- split-segments
  "Split a dot-separated namespace string into its segment vector. An empty
  string yields `[\"\"]` (a single empty segment) — but namespaces are never
  empty in practice; this is the literal `clojure.string/split` behaviour and
  the matcher's literal-segment compare handles it without a special case."
  [s]
  (str/split s #"\." -1))

(defn- collapse-double-stars
  "Collapse every RUN of consecutive `**` pattern segments into a single `**`.
  Pure; preserves match semantics exactly — `**` absorbs zero or more segments,
  so `**.**` (and any longer run) accepts the SAME segment sets as a single
  `**`. This is the M3 guard against the matcher's exponential-backtracking
  worst case: `match-segments?` backtracks per `**`, so a pathological pattern
  like `**.**.**.x` would otherwise fork 2^k ways across k adjacent stars on a
  non-match. Collapsing runs at parse time bounds the matcher to ONE `**`
  decision point per non-`**`-separated region, so a pattern can never carry
  more `**` decision points than it has literal/`*` anchors + 1 — linear in the
  pattern length. Applied to the PATTERN segments only (a namespace string is
  literal and has no `**`)."
  [segs]
  (into []
        (comp (partition-by #(= "**" %))
              (mapcat (fn [run]
                        (if (= "**" (first run)) ["**"] run))))
        segs))

(defn- segment-matches?
  "Match a SINGLE pattern segment against a single ns segment under the
  intra-segment glob rule (EP-0023 §Namespace-glob language). Pure.

  A pattern segment that is the whole-segment wildcard `\"*\"` or the
  multi-segment wildcard `\"**\"` is handled by `match-segments?` and never
  reaches here. Every other pattern segment is matched CHARACTER-WISE:

    * a segment with NO `*` is an exact literal — `(= p seg)`;
    * a segment containing one or more `*` is an intra-segment glob where each
      `*` matches ZERO OR MORE characters WITHIN the segment (case-sensitive).
      `\"*-cljs-test\"` matches `\"mount-cljs-test\"` and `\"core-cljs-test\"`
      but NOT `\"mount\"`; `\"foo*\"` matches `\"foobar\"`; `\"a*b\"` matches
      `\"aXXXb\"`. The `*` is intra-segment ONLY — it never crosses a `.`
      boundary (that is `**`'s job).

  The exact-literal fast path (no `*`) keeps the common case a single string
  compare; the glob path compiles the segment to an anchored regex with each
  `*` lowered to `.*` and every other character regex-escaped, so the match is
  purely textual (no namespace-segment semantics leak in) and portable across
  the JVM and JS regex engines."
  [p seg]
  (if (str/index-of p "*")
    (let [;; Lower the intra-segment glob to an anchored regex: split on `*`,
          ;; escape each literal run's regex metacharacters CHARACTER-WISE (a
          ;; portable escape that works on both the JVM and JS engines — JS has
          ;; no `\Q…\E` quoting), and join the runs with `.*` (the lowered `*`).
          parts   (str/split p #"\*" -1)
          escaped (map (fn [run]
                         ;; Function replacement (not a `$0` template) so the
                         ;; escaping is unambiguous on both the JVM and JS
                         ;; `clojure.string/replace` semantics.
                         (str/replace run #"[.*+?^${}()|\[\]\\]" (fn [m] (str "\\" m))))
                       parts)
          re      (re-pattern (str "^" (str/join ".*" escaped) "$"))]
      (boolean (re-find re seg)))
    (= p seg)))

(defn- match-segments?
  "Case-sensitive backtracking match of a `pattern` segment vector against a
  `ns` segment vector under the EP-0023 grammar:

    literal      — matches exactly that one segment (string =)
    intra-glob   — a segment containing `*` matches that segment character-wise,
                   each `*` absorbing zero or more chars WITHIN the segment
                   (`\"*-cljs-test\"` matches `\"mount-cljs-test\"`)
    \"*\"          — matches exactly ONE segment (any)
    \"**\"         — matches ZERO OR MORE segments (greedy with backtrack)

  Pure. Returns true iff the whole pattern matches the whole ns segments.

  Worst-case cost: each `**` is a backtrack point, so a pattern carrying many
  `**`s could in principle fork exponentially on a non-match. `ns-matches?`
  bounds this by passing the pattern through `collapse-double-stars` first
  (consecutive `**` runs are semantically one `**`), leaving at most one `**`
  decision per non-`**` anchor region — `match-segments?` itself assumes its
  `pattern` arg is already collapsed."
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

        ;; literal or intra-segment glob — match this one segment character-wise
        ;; (exact when `p` has no `*`; intra-segment glob when it does), then
        ;; advance both. `segment-matches?` is intra-segment ONLY — a `*` here
        ;; never crosses a `.` boundary.
        :else
        (and (segment-matches? p (first ns-segs))
             (match-segments? (rest pattern) (rest ns-segs)))))))

(defn ns-matches?
  "True iff the namespace string `ns-str` matches the `:include-ns` / `:exclude-ns`
  glob `pattern` under the EP-0023 grammar (case-sensitive whole-namespace match;
  `*` = one segment, `**` = zero or more, an intra-segment `*` = zero-or-more
  chars within one segment). Both args are strings. Pure.

  This is the single matching primitive: exact inclusion is a pattern with no
  wildcard (`\"docs.quickstart.counter.v2\"`), prefix inclusion is a normal
  glob (`\"docs.shared.widgets.*\"`), recursive inclusion uses `**`
  (`\"docs.shared.**\"`), and an intra-segment suffix/prefix glob narrows within
  a segment (`\"day8.re-frame2-xray.**\"` with an `:exclude-ns` of
  `\"day8.re-frame2-xray.**.*-cljs-test\"` drops the tool's own test
  namespaces)."
  [pattern ns-str]
  (boolean
    (and (string? pattern)
         (string? ns-str)
         ;; Collapse consecutive `**` runs in the PATTERN before matching: this
         ;; preserves semantics (a run of `**` accepts the same segment sets as
         ;; one `**`) and bounds the matcher away from its exponential-backtrack
         ;; worst case (M3 guard — see `collapse-double-stars`).
         (match-segments? (collapse-double-stars (split-segments pattern))
                          (split-segments ns-str)))))

;; ---- inline descriptors (EP-0026 §Inline Registration Grammar) -------------
;;
;; Inline `:registrations` are registrar-keyed sections that mirror the public
;; `reg-*` names. EP-0026 fixes the outer tuple shape at:
;;
;;   [id body]            ;; metadata defaults to {}
;;   [id metadata body]   ;; explicit metadata map
;;
;; The metadata map is OPTIONAL and normalizes to `{}`. A 2-tuple's second slot
;; is the BODY, not metadata — every inline entry carries a body. METADATA-ONLY
;; `[id metadata]` entries are INVALID (EP-0026 deliberately reverses EP-0023,
;; which permitted a metadata-only tuple); they fail loud at `rf/image`.
;;
;; Inline descriptors do NOT enter the provenance source store and are NOT
;; selected by `:include-ns`. They are selected because their containing image
;; value was supplied. They still need a descriptor source coordinate so errors
;; and replacements can name them:
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
  mapped to their Spec 001 registry kind. EP-0026 §Inline Registration Grammar
  NARROWS the inline grammar to EXACTLY the four kinds with a concrete inline
  parser + a published late-bind lowering hook (`re-frame.events` / `.subs` /
  `.fx` / `.cofx`'s `:image/lower-inline-<kind>`):

    :reg-event → :event
    :reg-sub   → :sub  (the layer-1 db-reader form ONLY)
    :reg-fx    → :fx
    :reg-cofx  → :cofx

  Every OTHER kind (`:reg-interceptor`, `:reg-view`, `:reg-frame`, `:reg-route`,
  `:reg-head`, `:reg-error-projector`, `:reg-flow`, `:reg-resource`,
  `:reg-mutation`, `:reg-resource-scope`) remains namespace-authored until its
  owning spec defines an inline lowering — an inline section for one fails loud
  with the unsupported-inline-kind diagnostic (`registrations->inline-descriptors`).
  Adding a kind here is a deliberate act: it MUST come with a published
  `:image/lower-inline-<kind>` hook, a body parser, metadata/body
  disambiguation, a provenance shape, and conformance coverage (EP-0026)."
  {:reg-event :event
   :reg-sub   :sub
   :reg-fx    :fx
   :reg-cofx  :cofx})

(defn- inline-entry->descriptor
  "Lower one inline registrar-section entry into an inline descriptor. `entry`
  is a call-shaped tuple `[id body]` (metadata defaults to `{}`) or
  `[id metadata body]` (explicit metadata). `image-id` is the containing image's
  `:id` (nil for an anonymous image — valid for local tests/examples that do not
  participate in composition). Pure.

  Stamps the inline source coordinate (`:rf.provenance/image` + the
  `:rf.provenance/inline [section id]` pair) so an inline descriptor has a
  stable name for errors and cross-image shadows. The body is ALWAYS carried
  under `:impl`; the metadata under `:metadata` (omitted when empty).

  The arity is EXACT (EP-0026 §Inline Registration Grammar): an inline entry
  MUST be a 2-tuple `[id body]` or a 3-tuple `[id metadata body]` — every inline
  registration carries a body. A 1-tuple `[id]`, an empty tuple `[]`, and a
  4+-tuple `[id metadata body extra…]` fail loud rather than be coerced.

  METADATA-ONLY `[id metadata]` is INVALID: a 2-tuple's second slot is the BODY,
  not metadata. EP-0026 deliberately reverses EP-0023 (which admitted a
  metadata-only tuple) — a registration with no body is not a registration. To
  attach metadata, use the 3-tuple `[id metadata body]`. The retired
  metadata-only form is caught FAIL-LOUD: for the four supported inline kinds the
  body is a HANDLER FUNCTION, never a map, so a 2-tuple whose body slot is a MAP
  is exactly the retired `[id metadata]` shape — it is rejected rather than
  silently lowered with the metadata map as the handler `:impl`."
  [image-id section kind entry]
  (when-not (and (vector? entry) (<= 2 (count entry) 3))
    (error/throw-error!
      :rf.error/invalid-image
      'rf/image
      (str "rf/image: inline " section " entry must be a [id body] or "
           "[id metadata body] tuple (EP-0026 — every inline registration carries "
           "a body; a metadata-only [id metadata] is INVALID) — got " (pr-str entry)
           " (" (if (vector? entry) (str "arity " (count entry)) "not a vector")
           ").")
      {:recovery :use-an-id-body-or-id-metadata-body-tuple
       :extra    {:image image-id :section section :entry entry}}))
  (let [id       (nth entry 0)
        has-meta (= 3 (count entry))
        metadata (when has-meta (nth entry 1))
        body     (if has-meta (nth entry 2) (nth entry 1))]
    ;; FAIL-LOUD on the retired metadata-only form: a 2-tuple whose body slot is
    ;; a MAP is the EP-0023 `[id metadata]` shape EP-0026 retires. The four
    ;; supported inline kinds all take a handler FUNCTION body, never a map, so a
    ;; map in the body slot is unambiguously the retired metadata-only tuple (to
    ;; attach metadata, use the 3-tuple `[id metadata body]`).
    (when (and (not has-meta) (map? body))
      (error/throw-error!
        :rf.error/invalid-image
        'rf/image
        (str "rf/image: inline " section " entry " (pr-str entry)
             " is a metadata-only [id metadata] tuple — INVALID under EP-0026. A "
             "2-tuple's second slot is the handler BODY (a function), not metadata; "
             "a registration with no body is not a registration. To attach metadata "
             "use the 3-tuple [id metadata body].")
        {:recovery :use-an-id-body-or-id-metadata-body-tuple
         :extra    {:image image-id :section section :entry entry}}))
    ;; FAIL-LOUD on a non-MAP metadata slot in the 3-tuple form. The metadata
    ;; slot of `[id metadata body]` is a Spec 001 registration metadata MAP
    ;; (EP-0026 §Inline Registration Grammar). An unvalidated non-map slot
    ;; otherwise either crashes raw at the `(seq metadata)` guard below (a
    ;; non-seqable such as a number) or is silently accepted and passed on as
    ;; junk `:metadata` to the lowering hook (a seqable non-map such as a
    ;; string or vector) — both defeat the canonical `:rf.error/invalid-image`
    ;; shape every sibling defect on this path gets.
    (when (and has-meta (not (map? metadata)))
      (error/throw-error!
        :rf.error/invalid-image
        'rf/image
        (str "rf/image: inline " section " entry " (pr-str entry)
             " has a non-map metadata slot — the middle slot of a 3-tuple "
             "[id metadata body] MUST be a registration metadata MAP (EP-0026). "
             "Got " (pr-str metadata) " (" (pr-str (type metadata)) "). For a "
             "registration with no metadata use the 2-tuple [id body].")
        {:recovery :use-an-id-body-or-id-metadata-body-tuple
         :extra    {:image image-id :section section :entry entry}}))
    (cond-> {:kind                 kind
             :id                   id
             :impl                 body
             :rf.provenance/inline [section id]}
      image-id       (assoc :rf.provenance/image image-id)
      (seq metadata) (assoc :metadata metadata))))

(defn- registrations->inline-descriptors
  "Lower an image's `:registrations` map (`{:reg-event [[id body] …] …}`) into a
  vector of inline descriptors, stamped with the image id. Pure. Throws
  `:rf.error/invalid-image` on an UNSUPPORTED inline kind (EP-0026 §Inline
  Registration Grammar — fail loud rather than silently drop an entry).

  EP-0026 standardizes inline grammar for EXACTLY four kinds — `:reg-event`,
  `:reg-sub`, `:reg-fx`, `:reg-cofx` — the kinds with a concrete inline parser
  and a published late-bind lowering hook (`reg-section->kind`). Every other
  registration kind (`:reg-interceptor`, `:reg-view`, `:reg-frame`, `:reg-route`,
  `:reg-head`, `:reg-error-projector`, `:reg-flow`, `:reg-resource`,
  `:reg-mutation`, `:reg-resource-scope`) — and any typo'd section key — fails
  loud with the unsupported-inline-kind diagnostic: those kinds remain
  namespace-authored until their owning spec defines inline lowering."
  [image-id registrations]
  (into []
        (mapcat
          (fn [[section entries]]
            (let [kind (reg-section->kind section)]
              (when-not kind
                (error/throw-error!
                  :rf.error/invalid-image
                  'rf/image
                  (str "rf/image: unsupported inline registrations section "
                       (pr-str section) " — EP-0026 standardizes inline grammar "
                       "for ONLY the four kinds :reg-event, :reg-sub, :reg-fx, and "
                       ":reg-cofx. Every other kind (interceptors, views, frames, "
                       "routes, heads, error-projectors, flows, resources, "
                       "mutations, resource-scopes) stays namespace-authored until "
                       "its owning spec defines an inline lowering — author it with "
                       "a reg-* form in a selected namespace instead.")
                  {:recovery :use-a-supported-inline-section-or-author-it-in-a-namespace
                   :extra    {:image            image-id
                              :unsupported-section section
                              :supported-sections (vec (sort (keys reg-section->kind)))}}))
              (map #(inline-entry->descriptor image-id section kind %) entries))))
        registrations))

;; ---- the image value (EP-0023 §Image) -------------------------------------
;;
;; The normalized image value this slice produces:
;;
;;   :rf.image/id        the image id, when supplied (`:id` in the spec map).
;;                       Anonymous images (no `:id`) are valid for local
;;                       tests/examples that do not participate in composition;
;;                       the slot is absent there. Owner-qualified per the
;;                       EP-0007 one-name-per-fact convention (a FACT about the
;;                       image).
;;   :rf.image/include-ns the vector of `:select-ns :include` glob patterns
;;                       (always a vector; `[]` when no `:select-ns`). The
;;                       selector runs each pattern against descriptor
;;                       `:rf.provenance/ns`.
;;   :rf.image/exclude-ns the vector of `:select-ns :exclude` subtractive glob
;;                       patterns (`[]` when none).
;;   :rf.image/inline    the vector of inline descriptors lowered from
;;                       `:registrations` (`[]` when none supplied). These are
;;                       selected unconditionally (their image was supplied),
;;                       never by `:select-ns`.
;;
;; The image value is INERT data. Two `image` calls with equal spec maps return
;; equal values. EP-0026 (rf2-dlvmpc) removed the `:rf.image/requires`
;; capability-requirement slot end-to-end (no image-declared host capabilities).

(def ^:private image-reserved-keys
  "Recognized top-level image spec keys (EP-0026 §Image Keys). The ordinary
  public image value accepts EXACTLY three top-level source keys — `:id`,
  `:select-ns`, and `:registrations`. Any other key is a malformed image — failed
  loudly rather than silently ignored.

  The EP-0023 keys `:include-ns`, `:exclude-ns`, `:replace`, `:replace-standard`,
  and `:rf.image/requires` are RETIRED (EP-0026, rf2-dlvmpc) and are NOT members:
  a spec carrying one fails loud with an actionable migration diagnostic
  (`retired-image-key-message`) pointing at the EP-0026 replacement —
  `:select-ns` + image-order layering for `:include-ns`/`:exclude-ns`/`:replace`,
  protected standards for `:replace-standard`, and ordinary registration
  selection for `:rf.image/requires`. They MUST NOT be accepted as aliases and
  MUST NOT be ignored."
  #{:id :select-ns :registrations})

(def ^:private retired-image-keys
  "The EP-0023 image source keys EP-0026 (rf2-dlvmpc) RETIRES with fail-loud
  rejection, each mapped to its actionable migration hint. A `rf/image` spec
  carrying any of these throws `:rf.error/invalid-image` so a stale example
  cannot keep working by accident (EP-0026 §Backwards Compatibility — \"Retired
  keys MUST fail loudly\"). This is the SCOPED retirement set: it names exactly
  the retired image source keys and nothing else — the legitimate
  `:rf.capability/*` host-service vocabulary, the conformance capability ids, and
  the tool capability flags are UNTOUCHED."
  {:include-ns       (str ":include-ns is RETIRED — use :select-ns {:include "
                          "[globs]}.")
   :exclude-ns       (str ":exclude-ns is RETIRED — use :select-ns {:include … "
                          ":exclude [globs]} (exclusion is global to the image "
                          "selection).")
   :replace          (str ":replace is RETIRED — composition resolves by IMAGE "
                          "ORDER (the later image in :images wins). Define the "
                          "override in a LATER image and read "
                          "(:rf.gen/shadows (rf/frame-generation f)) to assert on "
                          "what it shadowed; there is no acknowledgement key.")
   :replace-standard (str ":replace-standard is RETIRED — framework standards are "
                          "protected and not an ordinary app extension point. An "
                          "app image cannot shadow a standard.")
   :rf.image/requires (str ":rf.image/requires is RETIRED — image-declared host "
                           "capabilities are removed end-to-end (no :rf.image/"
                           "requires, no make-frame :capabilities, no :rf.gen/"
                           "requires). Model a host dependency through ordinary "
                           "registration selection, frame configuration, or "
                           "adapter setup.")})

(defn- check-retired-keys!
  "FAIL LOUD when a `rf/image` `spec` carries a RETIRED EP-0023 image key
  (EP-0026 §Image Keys / §Backwards Compatibility, rf2-dlvmpc). The diagnostic
  names the retired key and its EP-0026 replacement, so a stale example cannot
  keep working by accident. Pure (modulo the throw)."
  [spec]
  (doseq [[k hint] retired-image-keys]
    (when (contains? spec k)
      (error/throw-error!
        :rf.error/invalid-image
        'rf/image
        (str "rf/image: image key " k " is RETIRED (EP-0026). " hint
             " The public image value accepts only :id, :select-ns, and "
             ":registrations.")
        {:recovery :migrate-to-the-ep-0026-image-model
         :extra    {:image (:id spec) :retired-key k}}))))

;; ---- :select-ns — the EP-0026 single-map selection surface -----------------
;;
;; EP-0026 §Namespace Selection replaces the EP-0023 sibling `:include-ns` /
;; `:exclude-ns` keys with ONE `:select-ns {:include … :exclude …}` map. The two
;; legs reuse the EXACT EP-0023 glob grammar and lower to the same normalized
;; internal slots (`:rf.image/include-ns` / `:rf.image/exclude-ns`) the pure
;; selector already runs — `:select-ns` is the authoring surface; the internal
;; form is unchanged. The selected set is `union(:include) minus union(:exclude)`
;; with exclusion GLOBAL to the image selection (a namespace matched by any
;; `:exclude` is never selected, no re-admission), and STRICT include diagnostics:
;; `:include` is REQUIRED and a zero-match include pattern fails image assembly
;; (`select-by-include-ns`). `:select-ns` SELECTS, it does not LOAD — it filters
;; registrations the runtime already knows about by `:rf.provenance/ns`, so it
;; never forces a require and never defeats dead-code elimination.

(defn- normalize-select-ns
  "Validate + lower a `:select-ns` map into the `[include-ns exclude-ns]` vectors
  the pure selector consumes (EP-0026 §Namespace Selection). FAIL-LOUD STRICT
  diagnostics at `rf/image`:

    * `:select-ns` MUST be a map;
    * `:include` is REQUIRED, MUST be a NON-EMPTY vector of glob strings;
    * `:exclude` is optional, defaults to `[]`, MUST be a vector of glob strings
      when supplied;
    * no key other than `:include` / `:exclude` is permitted.

  The glob-string element check is shared with the legacy `:include-ns` path (the
  caller threads its `check-glob-strings!`). Returns `[include exclude]`. Pure
  (modulo the throw)."
  [image-id select-ns check-glob-strings!]
  (when-not (map? select-ns)
    (error/throw-error!
      :rf.error/invalid-image
      'rf/image
      (str "rf/image: :select-ns must be a map of {:include [globs] :exclude "
           "[globs]} — got " (pr-str select-ns) ".")
      {:recovery :use-a-select-ns-map
       :extra    {:image image-id :received select-ns}}))
  (doseq [k (keys select-ns)]
    (when-not (contains? #{:include :exclude} k)
      (error/throw-error!
        :rf.error/invalid-image
        'rf/image
        (str "rf/image: :select-ns key " (pr-str k) " is unknown — :select-ns "
             "carries only :include and :exclude.")
        {:recovery :remove-or-correct-the-select-ns-key
         :extra    {:image image-id :unknown-key k}})))
  (let [include (get select-ns :include)
        exclude (get select-ns :exclude [])]
    (when-not (and (vector? include) (seq include))
      (error/throw-error!
        :rf.error/invalid-image
        'rf/image
        (str "rf/image: :select-ns :include is REQUIRED and must be a NON-EMPTY "
             "vector of namespace-glob strings — got " (pr-str include)
             ". An image that selects no namespaces should omit :select-ns "
             "(and may still define inline :registrations).")
        {:recovery :supply-a-non-empty-include-vector
         :extra    {:image image-id :include include}}))
    (when-not (vector? exclude)
      (error/throw-error!
        :rf.error/invalid-image
        'rf/image
        (str "rf/image: :select-ns :exclude must be a vector of namespace-glob "
             "strings — got " (pr-str exclude) ".")
        {:recovery :use-a-glob-string-vector
         :extra    {:image image-id :exclude exclude}}))
    (check-glob-strings! :select-ns/include include)
    (check-glob-strings! :select-ns/exclude exclude)
    [include exclude]))

(defn image
  "Construct an IMAGE value — a selected registration-set value, as INERT data
  (EP-0023 §Image, §Public API). PUBLIC (`rf/image`).

  `spec` carries EXACTLY three public source keys (EP-0026 §Image Keys):

    :id            the image id (optional). Stamped as `:rf.image/id`. SHOULD be
                   stable enough for diagnostics + tooling; the shadow report
                   identifies images by id, and image ids MUST be unique within a
                   single `:images` composition. Anonymous images are valid for
                   local tests/examples that do not participate in composition.
                   BARE structural input key; normalized to the owner-qualified
                   `:rf.image/id`.
    :select-ns     the single-map selection surface — `{:include [globs]
                   :exclude [globs]}`. `:include` is REQUIRED (a non-empty vector
                   of namespace-glob strings, EP-0023 grammar) and selects
                   registered descriptors by their `:rf.provenance/ns`;
                   `:exclude` is optional (defaults to `[]`) and SUBTRACTS — the
                   selected set is `union(:include) minus union(:exclude)` with
                   exclusion GLOBAL to the image (a namespace matched by any
                   `:exclude` is never selected, no re-admission corner case).
                   STRICT include diagnostics: a zero-match `:include` pattern
                   FAILS LOUD. An `:exclude` pattern matching nothing is a no-op
                   (a defensive guard). Exclusion narrows the glob-selected set
                   ONLY, never the image's own inline `:registrations`.
                   `:select-ns` SELECTS, it does not LOAD — it filters
                   registrations the runtime already knows about by provenance,
                   so it never forces a require and never defeats dead-code
                   elimination. Normalizes to `:rf.image/include-ns` /
                   `:rf.image/exclude-ns`. Optional — an image with no
                   `:select-ns` selects no namespace-authored registrations (it
                   may still define inline `:registrations`).
    :registrations inline registrar-keyed sections (`{:reg-event [[id meta
                   body] …] :reg-sub [[id meta body] …] …}`). Lowered to inline
                   descriptors. Optional.

  The EP-0023 keys `:include-ns`, `:exclude-ns`, `:replace`, `:replace-standard`,
  and `:rf.image/requires` are RETIRED (EP-0026, rf2-dlvmpc): a spec carrying one
  fails loud (`check-retired-keys!`) with a migration diagnostic. Composition now
  resolves by IMAGE ORDER (the later image in `:images` wins) and reports
  shadows via `(:rf.gen/shadows (rf/frame-generation f))`; standards are
  protected; host-capability declarations are removed end-to-end.

  Returns a normalized image value:

    {:rf.image/id         <id>          ;; present only when :id supplied
     :rf.image/include-ns [<glob> …]
     :rf.image/exclude-ns [<glob> …]    ;; the subtractive globs ([] when none)
     :rf.image/inline     [<inline-descriptor> …]}

  PURE — no realm, no registrar, no side effect (an `image` call is data, not
  registration). Throws `:rf.error/invalid-image` when the spec carries a RETIRED
  key, when `:select-ns` is malformed, when an inline entry is not a `[id body]`
  / `[id metadata body]` tuple, or when the spec carries an unknown top-level
  key."
  [spec]
  (when-not (map? spec)
    (error/throw-error!
      :rf.error/invalid-image
      'rf/image
      "rf/image expects a spec map — e.g. {:id :counter/v2 :select-ns {:include [\"docs.counter.v2\"]}}."
      {:recovery :pass-a-spec-map
       :extra    {:spec spec}}))
  ;; FAIL LOUD on a RETIRED EP-0023 key BEFORE the unknown-key check, so a stale
  ;; example gets the actionable migration diagnostic, not a generic
  ;; "unknown image key" (EP-0026 §Backwards Compatibility, rf2-dlvmpc).
  (check-retired-keys! spec)
  (doseq [k (keys spec)]
    (when-not (contains? image-reserved-keys k)
      (error/throw-error!
        :rf.error/invalid-image
        'rf/image
        (str "rf/image: unknown image key " k
             " — the public image value accepts only :id, :select-ns, and "
             ":registrations.")
        {:recovery :remove-or-correct-the-key
         :extra    {:image (:id spec) :unknown-key k}})))
  (let [id         (:id spec)
        check-glob-strings!
        (fn [slot patterns]
          (doseq [p patterns]
            (when-not (string? p)
              (error/throw-error!
                :rf.error/invalid-image
                'rf/image
                (str "rf/image: " slot " patterns must be namespace-glob STRINGS — got "
                     (pr-str p) ". Use \"docs.counter.v2\", \"docs.shared.widgets.*\", "
                     "or \"docs.shared.**\".")
                {:recovery :use-namespace-glob-strings
                 :extra    {:image id :bad-pattern p}}))))
        ;; EP-0026 §Namespace Selection: `:select-ns {:include … :exclude …}` is
        ;; the SINGLE selection surface (the EP-0023 sibling `:include-ns` /
        ;; `:exclude-ns` keys are retired). An image with no `:select-ns` selects
        ;; no namespace-authored registrations (it may still define inline
        ;; `:registrations`). `:select-ns` lowers to the normalized internal
        ;; slots the pure selector runs.
        [include-ns exclude-ns]
        (if (contains? spec :select-ns)
          (normalize-select-ns id (:select-ns spec) check-glob-strings!)
          [[] []])]
    (cond-> {:rf.image/include-ns include-ns
             :rf.image/exclude-ns exclude-ns
             :rf.image/inline     (registrations->inline-descriptors
                                    id (get spec :registrations {}))}
      (some? id) (assoc :rf.image/id id))))

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

(defn exclude-by-ns
  "SUBTRACT from a glob-selected `descriptors` vector every descriptor whose
  `:rf.provenance/ns` matches AT LEAST ONE of the `:exclude-ns` `patterns`
  (same EP-0023 glob grammar as `:include-ns`). Pure.

  This is the narrowing knob for a recursive `**` `:include-ns` glob that sweeps
  in sibling namespaces a frame must not load — e.g. a tool's own `*-test`
  namespaces co-registering its ids in a test build (the EP-0023 §Xray Beside
  The Target singleton-seating case). The whole-namespace match is by
  `:rf.provenance/ns`, never by the registration-id keyword namespace, exactly
  as inclusion is.

  Unlike `select-by-include-ns`, exclusion is NOT zero-match fail-loud: an
  exclude pattern is a DEFENSIVE guard, so a pattern that matches nothing in
  this build (the guarded-against namespace simply is not loaded) is a no-op,
  not an error. An empty `patterns` returns `descriptors` unchanged. Preserves
  input order; descriptors with no `:rf.provenance/ns` are never matched by a
  glob, so they are never excluded."
  [patterns descriptors]
  (if (seq patterns)
    (into []
          (remove (fn [d]
                    (when-let [pns (descriptor-provenance-ns d)]
                      (some #(ns-matches? % pns) patterns))))
          descriptors)
    (vec descriptors)))

(defn select-descriptors
  "The PURE image selector (EP-0023 §Namespace-Selected Images, §Image
  Fragments). Given an `image` value and a collection of registered
  `descriptors` (each carrying its source-code provenance namespace at
  `:rf.provenance/ns` — the source store's output, slice .2), return the subset
  the image selects:

    * registered descriptors whose `:rf.provenance/ns` matches one of the
      image's `:include-ns` globs (selected BY provenance namespace, never by
      the registration-id's namespace; each `:include-ns` pattern must match at
      least one descriptor or selection fails loud) AND whose `:rf.provenance/ns`
      matches NONE of the image's `:exclude-ns` globs (the subtractive narrowing
      knob — applied to the glob-selected set, NOT to the inline descriptors);
      PLUS
    * the image's inline descriptors (from `:registrations`), selected
      unconditionally because the image value was supplied (never subject to
      `:exclude-ns` — they are selected by image-membership, not by provenance
      namespace).

  Returns a vector: the glob-selected-then-excluded registered descriptors (in
  input order, each at most once) followed by the inline descriptors. Collision
  validation
  across the selected set, framework-standard registrations, and sealing into a
  `[kind id]` generation are the ASSEMBLY slice's job, not this selector's.

  Pure — a function of the image value and the descriptor collection only.
  This is the surface the assembly slice (.4) calls to turn an image + the
  live source store's descriptors into the candidate registration set."
  [image descriptors]
  (let [image-id   (:rf.image/id image)
        patterns   (:rf.image/include-ns image)
        excludes   (:rf.image/exclude-ns image)
        inline     (:rf.image/inline image)
        selected   (if (seq patterns)
                     (select-by-include-ns image-id patterns descriptors)
                     [])
        ;; Subtract any `:exclude-ns`-matched descriptors from the glob-selected
        ;; set BEFORE folding in the inline descriptors (exclusion narrows the
        ;; provenance-glob selection only; inline descriptors are selected by
        ;; image membership, not by provenance namespace).
        narrowed   (exclude-by-ns excludes selected)]
    (into (vec narrowed) inline)))
