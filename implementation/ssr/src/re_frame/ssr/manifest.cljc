(ns re-frame.ssr.manifest
  "Root Manifest v1 (S5) — the server-render form of the `:rf.root/*` root
  descriptor family.

  The schema family and its compatibility rule are owned by
  [Spec 004C §2]; this namespace owns what Spec 011 owes it: the
  **extension keys** the server render supplies, the **wire form** the
  manifest rides, and the **discovery** rule `hydrate-root` follows to
  find it.

  ## The one invariant: the manifest is a VERSIONED SUPERSET

  Root Manifest v1 = Root Descriptor v1 (the S1 compile-time static
  subset, minus the dev-only `:root-id-provenance`) PLUS six render-time
  extension keys. Every extension key is OPTIONAL, so:

      an unmodified S1 Root Descriptor IS a valid Root Manifest v1.

  That is not a courtesy — it is the contract. `:rf.root/schema-version`
  stays `1` across both shapes (additive keys never bump the integer,
  004C §2 rule 3), so there is no migration step, no negotiation
  handshake, and no second schema to keep in sync. `problems` below is
  the executable statement of the property; the subset-property test
  pins it.

  Readers ignore unknown keys (004C §2 rule 2). `problems` therefore
  validates only the keys it KNOWS, and only for SHAPE — it never
  rejects a map for carrying an extra key, including the dev-only
  `:root-id-provenance` an S1 descriptor may still hold. Stripping that
  key is an EMIT-side duty (`manifest` does it); it is not a read-side
  rejection, or a descriptor could not validate.

  ## The extension keys (004C §2)

  | Key | Meaning |
  |---|---|
  | `:element-locator`    | `{:id \"shop-root\"}` — the container, §4 |
  | `:props`              | render-time prop VALUES, EDN-carryable |
  | `:frame-payload-ids`  | full referenced payload set at render |
  | `:render-fingerprint` | over the rendered structural output |
  | `:identifier-prefix`  | the prefix the server actually used |
  | `:phase`              | `:server` — the only v1 value |

  ## The wire form and discovery

  One `<script type=\"application/edn\" data-rf-root>` element placed
  IMMEDIATELY AFTER the root's container, carrying the `pr-str`'d
  manifest escaped by `re-frame.ssr.html-helpers/escape-edn-script-body`
  — the same escape the `__rf_payload` hydration payload uses.

  `data-rf-root` is a BARE MARKER: it carries no value and no identity.
  Identity is read from the manifest's CONTENT (`:root-id`), never from
  the element's attributes or its position. Spelling the root-id into
  the attribute as well would put identity on the wire twice and invite
  a reader to trust the cheaper copy; one spelling, in the content, is
  the whole rule.

  This follows the S4 fixture precedent (rf2-j81hs): a wire artefact
  names things EXPLICITLY, never implicitly. The manifest names the
  mounted view through the explicit `:view-id` KEY — it never re-spells
  a view as a callable or a head position, so the keyword-head ambiguity
  that forced the `[:view-ref <id> & args]` marker in the conformance
  fixtures cannot arise here.

  ## What this namespace deliberately does NOT do

  No schema framework, no migration mechanism, no version negotiation.
  v1 is the first version, not a compatibility layer. Assembling
  manifests during a real page render, the Layer-2 per-response page
  registry (004C §7), and the client `hydrate-root` preflight that
  CONSUMES a discovered manifest are later S5 leaves — this namespace
  supplies the shape, the wire, and the finder they will use."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.ssr.constants :as constants]
            [re-frame.ssr.html-helpers :as html]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))

;; ---------------------------------------------------------------------------
;; The key sets (004C §2)
;; ---------------------------------------------------------------------------

(def ^:const schema-version
  "The ONE version integer governing the whole `:rf.root/*` family. A
  descriptor and the manifest extending it declare the SAME value —
  additive keys never bump it (004C §2 rule 3)."
  1)

(def descriptor-keys
  "Root Descriptor v1 — the S1 compile-time static SUBSET, as
  `re-frame.ui.compiler.root/root-descriptor` emits it plus the
  read-time-projected `:build-digest` (004C §2/§2.1). Listed here as
  DATA so the subset property is checkable rather than asserted."
  #{:rf.root/schema-version
    :root-id
    :root-id-provenance
    :view-id
    :props-shape
    :static-props
    :frame-plans
    :template-fingerprint
    :build-digest})

(def extension-keys
  "The six render-time keys Root Manifest v1 ADDS to the descriptor
  (004C §2). Every one is OPTIONAL — that optionality IS the subset
  property."
  #{:element-locator
    :props
    :frame-payload-ids
    :render-fingerprint
    :identifier-prefix
    :phase})

(def dev-only-keys
  "Descriptor keys that are dev-only and MUST NOT ride a shipped
  manifest (004C §2). Stripped on EMIT by `manifest`; never a read-side
  rejection — an S1 descriptor carrying provenance is still a valid
  manifest."
  #{:root-id-provenance})

(def ^:const manifest-script-type
  "The manifest script element's `type`. Shares the hydration payload's
  `application/edn` type: the body is EDN, and a non-JS `type` keeps the
  browser from executing it."
  "application/edn")

;; ---------------------------------------------------------------------------
;; Validation — the executable subset property
;; ---------------------------------------------------------------------------

(defn- locator-problem [loc]
  (cond
    (not (map? loc))                      {:invalid :element-locator :got loc}
    (not= #{:id} (set (keys loc)))        {:invalid :element-locator
                                           :expected :closed-id-vocabulary
                                           :got (vec (keys loc))}
    (not (string? (:id loc)))             {:invalid :element-locator :got (:id loc)}
    (str/blank? (:id loc))                {:invalid :element-locator :got (:id loc)}))

(defn problems
  "-> `nil` when `m` is a valid Root Manifest v1, else a DATA map naming
  the violated rule (the `ex-data` an eventual `:rf.error/root-manifest-invalid`
  carries). Pure and host-neutral — the server emitter, the client
  hydrate preflight, and tooling all read the same verdict.

  **The subset property.** Only `:rf.root/schema-version` is REQUIRED,
  and it must equal `schema-version`. Every extension key is optional
  and is shape-checked ONLY when present. Unknown keys are ignored
  (004C §2 rule 2). An unmodified S1 Root Descriptor therefore validates
  unchanged — making any extension key mandatory here would break that,
  which is exactly what the subset-property test watches."
  [m]
  (cond
    (not (map? m))
    {:invalid :not-a-map :got (type m)}

    (not (contains? m :rf.root/schema-version))
    {:missing :schema-version}

    (not= schema-version (:rf.root/schema-version m))
    {:invalid :schema-version
     :got     (:rf.root/schema-version m)
     :expected schema-version}

    :else
    (let [{:keys [element-locator props frame-payload-ids
                  render-fingerprint identifier-prefix phase]} m]
      (or
       (when (contains? m :element-locator) (locator-problem element-locator))
       (when (and (contains? m :props) (not (map? props)))
         {:invalid :props :got props})
       (when (and (contains? m :frame-payload-ids)
                  (not (and (coll? frame-payload-ids)
                            (every? keyword? frame-payload-ids))))
         {:invalid :frame-payload-ids :got frame-payload-ids})
       (when (and (contains? m :render-fingerprint) (not (string? render-fingerprint)))
         {:invalid :render-fingerprint :got render-fingerprint})
       (when (and (contains? m :identifier-prefix) (not (string? identifier-prefix)))
         {:invalid :identifier-prefix :got identifier-prefix})
       (when (and (contains? m :phase) (not= :server phase))
         {:invalid :phase :got phase :expected :server})))))

(defn valid?
  "Does `m` satisfy Root Manifest v1? (`problems` is the reason.)"
  [m]
  (nil? (problems m)))

(defn validate!
  "-> `m` when valid; otherwise throws the canonical
  `:rf.error/root-manifest-invalid` (Spec 009) carrying `problems`'
  verdict. The fail-loud read arm — a manifest that does not validate is
  never partially adopted."
  [where m]
  (if-let [p (problems m)]
    (error/throw-error!
     :rf.error/root-manifest-invalid where
     (str "root manifest is not a valid Root Manifest v1 ("
          (pr-str p) "). Root Manifest v1 is the versioned SUPERSET of "
          "Root Descriptor v1: `:rf.root/schema-version " schema-version
          "` is the only required key and every extension key is "
          "optional, so a valid descriptor is already a valid manifest — "
          "a failure here means the value is not from this schema family")
     {:recovery :re-render-the-root-manifest
      :extra    p})
    m))

;; ---------------------------------------------------------------------------
;; Element locators (004C §4) — the closed `{:id string}` vocabulary
;; ---------------------------------------------------------------------------

(defn host-locator
  "The locator for a HOST-AUTHORED container (the page skeleton supplied
  `[:div#shop-root]`): its `id`, verbatim. A host-authored container
  WITHOUT an id fails the server render — the emitter never synthesises
  an id onto an element the host owns, because the host's own markup
  would then disagree with the manifest (004C §4)."
  [where container-id]
  (if (and (string? container-id) (not (str/blank? container-id)))
    {:id container-id}
    (error/throw-error!
     :rf.error/root-manifest-invalid where
     (str "a host-authored root container has no `id` — the manifest's "
          ":element-locator vocabulary is the closed `{:id …}` form, and "
          "the emitter never synthesises an id onto host-owned markup "
          "(the host's markup would then disagree with the manifest). "
          "Give the container an id, or let the emitter produce the "
          "container itself")
     {:recovery :give-the-root-container-an-id
      :extra    {:missing :container-id}})))

(defn synthesised-locator
  "The locator for an EMITTER-SYNTHESISED container: `\"rf2-root-\" +
  root-id-slug`. Unique per page for free — the slug is injective over
  root-id (004C §1) and root-ids are page-unique (004C §7), so two
  synthesised locators can never collide. The slug is passed in rather
  than recomputed: it is the `re-frame.ui` compiler's value, and the SSR
  artefact does not depend on the UI artefact."
  [root-id-slug]
  {:id (str "rf2-root-" root-id-slug)})

;; ---------------------------------------------------------------------------
;; Render-time props (004C §5)
;; ---------------------------------------------------------------------------

(defn edn-carryable?
  "Can `v` ride the manifest's EDN wire? Scalars (nil / boolean / number
  / string / keyword / symbol) and collections of carryable values.
  A fn, a host object, or any other opaque value cannot — and is never
  silently dropped (see `serialise-props`).

  The question this answers is exactly `(= v (read (pr-str v)))` under
  the BUNDLED safe reader — not \"is `v` map-shaped\". The distinction is
  what the `record?` arm below exists for."
  [v]
  (cond
    (nil? v)     true
    (boolean? v) true
    (number? v)  true
    (string? v)  true
    (keyword? v) true
    (symbol? v)  true
    ;; A record satisfies `map?`, so without this arm it took the map
    ;; branch, every entry tested carryable, and the record was declared
    ;; wire-safe (rf2-v4foc). But `pr-str` does not emit a record as a
    ;; map — it emits the TAGGED literal `#my.ns.R{:x 1}`, and the safe
    ;; reader has no constructor for that tag, so the body threw
    ;; `:invalid :unreadable` at the far end of the wire. The check must
    ;; precede `map?`: a record is map-LIKE but not map-PRINTING, and
    ;; this predicate is about the printed form.
    ;;
    ;; Rejecting rather than converting is deliberate. Reading the record
    ;; back as a plain map would silently change the prop's TYPE between
    ;; server and client, which is the same class of defect one layer
    ;; down; the author converts explicitly (`(into {} r)`) so both hosts
    ;; agree about what they are holding.
    (record? v)  false
    (map? v)     (every? (fn [[k v']] (and (edn-carryable? k) (edn-carryable? v'))) v)
    (coll? v)    (every? edn-carryable? v)
    :else        false))

(defn serialise-props
  "-> the render-time `:props` map for the manifest. A value the EDN wire
  cannot carry FAILS the server render for this root rather than
  producing a silently truncated manifest (004C §5) — hydration applies
  the manifest's props as the server-rendered truth, so a missing prop
  would be a wrong tree, not a smaller one."
  [where props]
  (when (some? props)
    (when-not (map? props)
      (error/throw-error!
       :rf.error/root-manifest-invalid where
       (str "root props must be a map to ride the manifest; got "
            (pr-str props))
       {:recovery :re-render-the-root-manifest
        :extra    {:invalid :props :got props}}))
    ;; Both HALVES of every entry are checked. Testing only the value
    ;; (rf2-v4foc) let an opaque KEY — a fn, a host object — through: the
    ;; entry looked fine, `pr-str` emitted `#object[…]` for the key, and
    ;; the reader rejected the whole body on the client. A prop map is
    ;; carried key-and-value, so it is validated key-and-value.
    (doseq [[k v] props]
      (let [bad-key? (not (edn-carryable? k))]
        (when (or bad-key? (not (edn-carryable? v)))
          (error/throw-error!
           :rf.error/root-manifest-invalid where
           (str "root prop " (pr-str k) " has a "
                (if bad-key? "KEY" "value")
                " the manifest's EDN wire cannot carry — the manifest "
                "records RENDER-TIME prop values and hydration applies "
                "them as the server-rendered truth, so dropping one would "
                "hydrate a different tree. Pass serialisable data (derive "
                "the opaque value inside the view instead of handing it "
                "in as a prop)")
           {:recovery :pass-serialisable-root-props
            :extra    {:unserialisable-prop k
                       :unserialisable-half (if bad-key? :key :value)}}))))
    props))

;; ---------------------------------------------------------------------------
;; Assembly
;; ---------------------------------------------------------------------------

(defn manifest
  "Assemble Root Manifest v1: a complete Root Descriptor v1 PLUS the
  render-time extension facts.

  The descriptor rides through UNCHANGED apart from the dev-only keys,
  which are stripped (004C §2) — no descriptor key is renamed, retyped,
  or re-semanticised, which is what makes this a superset rather than a
  replacement. Extension keys are omitted when their fact is absent;
  `:phase :server` is always stamped (the only v1 value — the field
  exists so a future phase is additive).

  `extensions` is the closed render-time fact map: `:element-locator`,
  `:props`, `:frame-payload-ids`, `:render-fingerprint`,
  `:identifier-prefix`. Validated before it is returned, so an
  ill-formed fact fails at ASSEMBLY, not at the far end of the wire."
  ([descriptor] (manifest 'rf.ssr/manifest descriptor nil))
  ([descriptor extensions] (manifest 'rf.ssr/manifest descriptor extensions))
  ([where descriptor extensions]
   (validate! where descriptor)
   (let [{:keys [element-locator props frame-payload-ids
                 render-fingerprint identifier-prefix]} extensions]
     (validate!
      where
      (cond-> (apply dissoc descriptor dev-only-keys)
        true                        (assoc :phase :server)
        (some? element-locator)     (assoc :element-locator element-locator)
        (some? props)               (assoc :props (serialise-props where props))
        (some? frame-payload-ids)   (assoc :frame-payload-ids
                                           (vec (distinct frame-payload-ids)))
        (some? render-fingerprint)  (assoc :render-fingerprint render-fingerprint)
        (some? identifier-prefix)   (assoc :identifier-prefix identifier-prefix))))))

;; ---------------------------------------------------------------------------
;; The wire form
;; ---------------------------------------------------------------------------

(defn- unwireable-key
  "-> the first manifest KEY whose entry cannot ride the EDN wire, else
  `nil`. Names the top-level key rather than a deep path: the key is
  what the author wrote and can change, and `pr-str` of the offending
  value is already in the message."
  [m]
  (some (fn [[k v]]
          (when-not (and (edn-carryable? k) (edn-carryable? v)) k))
        m))

(defn script-html
  "-> the manifest's WIRE FORM: one `<script type=\"application/edn\"
  data-rf-root>` element carrying the `pr-str`'d manifest, escaped by
  the shared EDN script-body escape (so a `</script` breakout in the
  data cannot end the element, and the body still round-trips through
  the EDN reader).

  Emit this element IMMEDIATELY AFTER the root's container — adjacency
  is the discovery rule (`discover`). The marker attribute is bare: it
  says \"a root manifest is here\", never WHICH root — that is the
  content's `:root-id`.

  ## The wire gate

  Emission is where \"this value can ride EDN\" stops being advice and
  becomes a guarantee, so the WHOLE manifest is checked here — not just
  the `:props` `serialise-props` screened at assembly. The two are not
  redundant: `serialise-props` fails EARLY, naming the offending prop;
  this gate makes the property TOTAL. `script-html` is the only door
  onto the wire, and every descriptor key rides through it too, so
  without it a non-carryable `:static-props` still produced a body the
  reader rejects — the same defect as the props one, one layer out
  (rf2-v4foc).

  One predicate, `edn-carryable?`, spelled once and enforced at both
  points. Not a second rule."
  [m]
  (validate! 'rf.ssr/manifest-script m)
  (when-let [k (unwireable-key m)]
    (error/throw-error!
     :rf.error/root-manifest-invalid 'rf.ssr/manifest-script
     (str "root manifest key " (pr-str k) " holds a value the EDN wire "
          "cannot carry, so the emitted body would not read back — "
          "`pr-str` emits a tagged or host-object literal the bundled "
          "safe reader has no constructor for, which would defer an "
          "emit-time authoring mistake into a client hydration failure. "
          "Every value on the manifest must be plain EDN data")
     {:recovery :pass-serialisable-root-props
      :extra    {:unserialisable-manifest-key k}}))
  (str "<script type=\"" manifest-script-type "\" "
       constants/root-manifest-marker-attribute ">"
       (html/escape-edn-script-body (pr-str m))
       "</script>"))

(defn- read-edn-forms
  "-> a vector of EVERY top-level EDN form in `s`.

  Reading `\"[\" s \"\\n]\"` is what makes \"exactly one form\" checkable
  with the bundled safe readers on BOTH hosts. Neither
  `clojure.edn/read-string` nor `cljs.reader/read-string` reports what it
  left unconsumed — each returns the first form and discards the rest —
  and their stream APIs differ enough that a per-host `read`-loop would
  be two implementations of one rule. EDN has no context-sensitive
  syntax, so bracket-wrapping yields exactly the top-level forms.

  The `\\n` before `]` matters: a trailing `;` line comment would
  otherwise swallow the closing bracket and turn a well-formed body into
  a spurious read error.

  Both readers are the SAFE EDN readers (no `#=` / eval) — the same ones
  `read-server-payload` uses for the hydration payload."
  [s]
  (let [wrapped (str "[" s "\n]")]
    #?(:clj  (edn/read-string wrapped)
       :cljs (reader/read-string wrapped))))

(defn read-manifest
  "Parse a manifest script BODY -> the validated manifest. Unreadable EDN,
  a body that is not EXACTLY ONE form, and a value outside the schema
  family all fail loud with `:rf.error/root-manifest-invalid` — a
  hydrating root never guesses identity (004C §3).

  The one-form rule is not pedantry. `read-string` returns the FIRST form
  and silently drops whatever follows, so a body carrying trailing text
  or a second map — a truncated render, two manifests concatenated by a
  faulty page assembly, an injected suffix — hydrated happily against the
  first map and ignored the evidence that the wire was wrong (rf2-v4foc).
  A manifest is one value; anything else is a corrupt body, not a body
  with extras."
  [where s]
  (let [forms (try
                (read-edn-forms (str s))
                (catch #?(:clj Exception :cljs :default) e
                  (error/throw-error!
                   :rf.error/root-manifest-invalid where
                   (str "root manifest script body is not readable EDN: "
                        #?(:clj (.getMessage ^Exception e) :cljs (.-message e)))
                   {:recovery :re-render-the-root-manifest
                    :extra    {:invalid :unreadable}})))]
    (when-not (= 1 (count forms))
      (error/throw-error!
       :rf.error/root-manifest-invalid where
       (str "root manifest script body must hold EXACTLY ONE EDN form; "
            "found " (count forms) ". A body with trailing content or a "
            "second form is a corrupt wire, and adopting its first form "
            "would hydrate against evidence the render went wrong")
       {:recovery :re-render-the-root-manifest
        :extra    {:invalid :form-count :got (count forms)}}))
    (validate! where (first forms))))

;; ---------------------------------------------------------------------------
;; Discovery (CLJS — it reaches into the DOM)
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn manifest-script?
     "Is `el` a root-manifest script element? Both marks must be present:
     the `application/edn` type AND the bare `data-rf-root` marker. An
     ordinary `application/edn` script (the `__rf_payload` hydration
     payload, a JSON-LD-style data island) is therefore never mistaken
     for a manifest."
     [el]
     (boolean
      (and el
           (= "SCRIPT" (.-tagName el))
           (= manifest-script-type (.getAttribute el "type"))
           (.hasAttribute el constants/root-manifest-marker-attribute)))))

#?(:cljs
   (defn discover
     "Discover the Root Manifest for `container` -> the validated
     manifest, or `nil` when there is none.

     Discovery is POSITIONAL: the manifest is the IMMEDIATELY FOLLOWING
     ELEMENT SIBLING of the container, and nothing else is searched. No
     document-wide scan, no id lookup, no selector — adjacency is what
     makes a root's manifest unambiguously ITS manifest on a page of N
     roots, and it survives fragment reordering because the pair moves
     together.

     Identity is then read from the CONTENT (`:root-id`), never from the
     element. `nil` means \"no manifest here\" — the caller decides what
     that means (`ui/hydrate-root` fails loud with
     `:rf.error/root-manifest-invalid` `{:missing :manifest}`; a
     client-only mount never asks)."
     [container]
     (let [el (some-> container .-nextElementSibling)]
       (when (manifest-script? el)
         (read-manifest 'rf.ssr/discover-root-manifest (.-textContent el))))))
