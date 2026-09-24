(ns re-frame.derivation.egress
  "The OFF-BOX EGRESS REDACTION algebra for a `DerivationGraph` — the single
  owner of the projection a consuming tool applies at the wire boundary where
  it ships the graph OFF the developer's box (EP-0014; [spec/Derivations.md]
  §Redaction metadata + §Conformance 'Tool redaction').

  ## Why this lives in `implementation/core`, gated as TOOLING

  This namespace is the ONE owner of the redaction algorithm, and both of
  its consumers delegate to it: the named first-consumer Xray call site
  (`day8.re-frame2-xray.panels.derivation-graph-helpers/redact-graph-for-egress`,
  a thin alias) and the derivation-conformance suite, which tests THIS
  namespace directly. Two independent copies would drift: a fix (say, the
  canonical work-id / host-transient projection) would have to land in both.

  It sits in core/src beside the graph COMPOSER (`re-frame.derivation.graph`)
  because it is built ENTIRELY from `implementation/`-resident primitives —
  `re-frame.elision/elide-wire-value` (the shared per-frame fail-closed value
  walker, EP-0015 §11), `re-frame.identity/canonical-bytes` (the CEDN-1
  canonical token, EP-0012), and `re-frame.privacy/redacted-sentinel` (the
  `:rf/redacted` sentinel) — so both consumers can reach ONE owner without
  the conformance surface reaching into `tools/` (the dependency arrow flows
  tools → implementation, NEVER implementation → tools). It carries NO
  dependency on any per-feature artefact or on `tools/`.

  ## BUNDLE ISOLATION (load-bearing)

  This is TOOLING, not production API. It is NOT exposed from the
  `re-frame.core` facade and is NOT `:require`d by any production-reachable
  namespace — it is loaded only by dev tools (Xray) + the conformance
  fixtures, which `:require` it directly. A production application bundle
  never loads this ns, so `:advanced` + `goog.DEBUG=false` DCE its body
  wholesale. The bundle-isolation sentinel at the foot of this ns proves no
  stray `:require` pulled it into the counter example's production bundle
  (`implementation/scripts/check-bundle-isolation.cjs`).

  ## The contract

  Given a `DerivationGraph` (`{:mode :nodes :edges}`) and the observed
  frame-id whose elision policy governs egress, `project-graph` returns the
  graph with:

    - **value-bearing node fields** (`:value` / `:params` / `:query` /
      `:state` — `value-bearing-node-keys`) walked through
      `elide-wire-value` under THAT frame's own declared `:sensitive` /
      `:large` policy (passed as the explicit `:frame` opt so the named
      frame's policy applies regardless of any ambient scope);
    - **fail-closed** on a nil / unreachable frame — the observed frame-id
      is stamped as the `:frame` opt whatever it is, and `elide-wire-value`
      reads that opt by KEY PRESENCE, so a nil / destroyed id takes its
      unresolvable-frame branch (whole value ⇒ `:rf/redacted`) rather than
      borrowing an ambient dynamically-bound frame and shipping raw;
    - **identity-embedded scoped keys** — the positions the value-path walk
      is structurally blind to (node KEY, `:id`, `:output`, realized
      `:inputs`, `:work-ledger` work-id + `:resource/key`, `:host-transient`
      in-flight handle, and every edge endpoint) projected into STABLE OPAQUE
      HANDLES; and
    - **STRUCTURE PRESERVED** — a redacted value / param is still an edge;
      the node is still present + classified; the projection is idempotent.

  JVM-portable (`.cljc`) so the projection + redaction contracts are pinned
  by the JVM test corpus without a CLJS runtime."
  (:require #?@(:cljs [[goog.crypt :as gcrypt]
                       [goog.crypt.Hmac]
                       [goog.crypt.Sha256]])
            [re-frame.elision :as rf.elision]
            [re-frame.identity :as rf.identity]
            [re-frame.privacy :as rf.privacy])
  #?(:clj (:import [java.nio.charset StandardCharsets]
                   [java.security SecureRandom]
                   [javax.crypto Mac]
                   [javax.crypto.spec SecretKeySpec])))

#?(:clj (set! *warn-on-reflection* true))

;; The value-bearing summary fields a LIVE node may carry — the value
;; summaries [spec/Derivations.md] §Redaction metadata names as egress-bearing
;; once shipped off-box: a sub-cache entry's `:value`, a route slice's
;; `:params` / `:query`, a machine instance's `:state` summary. (Each is the
;; in-process value summary the sibling live surface carries; the composer
;; carries them verbatim.) Node IDs, edges, and the storage / evaluation /
;; lifecycle classifications are STRUCTURE and are never touched by the value
;; walk. Identity-embedded secrets ride the scoped-key projection below, NOT
;; this list.
(def value-bearing-node-keys
  [:value :params :query :state])

;; ---------------------------------------------------------------------------
;; LIVE RESOURCE IDENTITY redaction.
;;
;; The `elide-wire-value` value-field walk redacts a sensitive VALUE sitting
;; at a node's `:value` / `:params` / `:query` / `:state` summary — it matches
;; by the FRAME's declared `:sensitive` `:app-db` PATH. But a LIVE resource
;; node carries its sensitive scope/params in a place that walk can NEVER
;; reach: the concrete SCOPED KEY `[cache-scope resource-id canonical-params]`
;; is the node's IDENTITY — it is the node KEY (`[:resource <scoped-key>]`),
;; the node's `:id`, it is embedded in the `:output` runtime path, in the
;; `:work-ledger :record :resource/key`, and a route-owned activation surfaces
;; it as the `:to` end of a `:param` edge. These are STRUCTURE positions, not
;; value-bearing leaves — and the param/scope they carry are exactly the
;; "sensitive params/scopes" [spec/Derivations.md] §Redaction metadata names
;; as egress-bearing.
;;
;; A value-path walk is structurally blind to identity-embedded secrets, so
;; egress must project the scoped-key's secret-bearing components into STABLE
;; OPAQUE HANDLES: within one runtime the same scoped key always maps to the
;; same projected key (graph CONNECTIVITY survives — a redacted param is still
;; an edge), but the raw scope/params never cross the wire. We mint the handle
;; as a keyed digest of the core CEDN-1 identity token
;; (`rf.identity/canonical-bytes`) so it is deterministic for a given value
;; within that runtime; a value outside the CEDN-1 domain (or any
;; error) FAILS CLOSED to the `:rf/redacted` sentinel rather than risk
;; shipping a host-stringified secret. The middle `resource-id` (a
;; registration keyword, never sensitive) is PRESERVED so a tool still sees
;; WHICH resource the node is.

(defn scoped-resource-key?
  "True when `v` is a live resource SCOPED KEY — a 3-tuple
  `[cache-scope resource-id canonical-params]` (the resource's concrete
  live fact identity, [spec/Derivations.md] §Fact identity): the MIDDLE
  element is the registration `resource-id` keyword and the LAST is the
  canonical-params MAP (resource params are validated by a `[:map …]`
  `:params-schema`, so the concrete params are always a map). Requiring the
  params map keeps it from matching the `:output` runtime path
  `[:rf.runtime/resources :entries …]`, whose last element is not a map. A
  static resource node's `:id` is a bare keyword (not this shape). It is
  still a pure SHAPE test, and an unrelated 3-vector can match it: a live
  subscription's query vector `[:sub-id :kw {…}]` does. So `project-graph`
  applies it to resource nodes only (`resource-node?`). Already-projected
  handles do NOT re-match (the projected scoped key's tail is an opaque
  VECTOR, not a map), so re-projection is a no-op — see `opaque-handle`."
  [v]
  (and (vector? v)
       (= 3 (count v))
       (keyword? (nth v 1))
       (map? (nth v 2))))

(defn- already-projected?
  "True when `v` is ALREADY an egress-projected handle — an opaque
  `[:rf.resource/opaque <digest>]` token or the `:rf/redacted` fail-closed
  sentinel. Re-projecting such a value MUST be the identity (idempotence): a
  forwarder pipeline may run egress more than once (re-egress on re-render /
  re-subscribe / event-bundle), and hashing an already-projected handle would
  mint a NEW, different handle — silently changing the live node's identity
  across the boundary and breaking stable graph connectivity."
  [v]
  (or (= v rf.privacy/redacted-sentinel)
      (and (vector? v)
           (= 2 (count v))
           (= :rf.resource/opaque (nth v 0)))))

;; The handle digest: HMAC-SHA-256 over the UTF-8 bytes of the
;; value's CEDN-1 token, under a private random key minted once per runtime,
;; rendered as the full 64-char lowercase hex digest. A 32-bit `hash` of the
;; token would collide — `{:q "Aa"}` and `{:q "BB"}` would merge into one
;; node — and would give up a low-entropy param to enumeration. The UTF-8 /
;; hex idiom is `re-frame.schemas.digest`'s, copied rather than required so
;; core takes no dependency on the schemas artefact.

(defn- utf8-bytes
  "Encode a string as UTF-8 bytes."
  [s]
  #?(:clj  (.getBytes ^String s StandardCharsets/UTF_8)
     :cljs (gcrypt/stringToUtf8ByteArray s)))

(defn- bytes->hex
  "Lowercase hex encoding of a byte sequence."
  [bs]
  #?(:clj  (let [sb (StringBuilder.)]
             (doseq [b bs]
               (let [u (bit-and (long b) 0xff)]
                 (when (< u 0x10) (.append sb \0))
                 (.append sb (Long/toString u 16))))
             (str sb))
     :cljs (gcrypt/byteArrayToHex bs)))

(defn- hmac-sha256-hex
  "The 64-char lowercase hex HMAC-SHA-256 of `s`'s UTF-8 bytes under
  `key-bytes` — a host byte array (`byte[]` on the JVM, an array of 0-255
  integers on CLJS)."
  [key-bytes s]
  #?(:clj  (let [mac (Mac/getInstance "HmacSHA256")]
             (.init mac (SecretKeySpec. ^bytes key-bytes "HmacSHA256"))
             (bytes->hex (.doFinal mac ^bytes (utf8-bytes s))))
     :cljs (bytes->hex (.getHmac (goog.crypt.Hmac. (goog.crypt.Sha256.) key-bytes)
                                 (utf8-bytes s)))))

(def ^:private handle-key
  "The private 32-byte handle key: cryptographically random, minted once per
  runtime on first use (a `delay`, so loading this ns has no side effect), and
  never exposed. There is deliberately no fallback key: a runtime without a
  secure random source throws here, and `opaque-handle` fails closed to
  `:rf/redacted` rather than mint handles under a predictable key."
  (delay
    #?(:clj  (let [k (byte-array 32)]
               (.nextBytes (SecureRandom.) k)
               k)
       :cljs (let [k (js/Uint8Array. 32)]
               (.getRandomValues js/crypto k)
               (js/Array.from k)))))

(defn- opaque-handle
  "A STABLE, ONE-WAY opaque handle for one secret-bearing scoped-key
  component: `[:rf.resource/opaque <hex>]`, where `<hex>` is the full 64-char
  HMAC-SHA-256 of the value's CEDN-1 canonical token under the private
  per-runtime `handle-key` — never the token itself, which PRESERVES the raw
  value and would defeat redaction.

  STABLE WITHIN ONE RUNTIME: equal values get equal handles, so graph
  connectivity survives (and equality between values stays visible). A
  restart or a page load mints a new key and changes every handle, so handles
  cannot be compared across processes.

  ONE-WAY IN A BOUNDED SENSE: it protects against someone who receives an
  exported snapshot but has neither the runtime key nor a live projection
  oracle. It does NOT protect against the trusted programmer, against anyone
  with local evaluation access, or against what the graph topology itself
  reveals.

  FAILS CLOSED to the `:rf/redacted` sentinel for any value outside the
  CEDN-1 identity domain or on any error, a missing secure random source
  included (never host-stringify a secret onto the wire). IDEMPOTENT: an
  already-projected `[:rf.resource/opaque …]` handle / `:rf/redacted`
  sentinel is returned UNCHANGED (digesting it again would mint a fresh,
  DIFFERENT handle and silently change the projected identity on a second
  egress pass)."
  [v]
  (if (already-projected? v)
    v
    (try
      ;; `canonical-bytes` is the deterministic CEDN-1 token (stable for a
      ;; given value, cross-spelling-invariant); the keyed digest makes it
      ;; one-way, and its full width keeps distinct values from merging.
      [:rf.resource/opaque (hmac-sha256-hex @handle-key (rf.identity/canonical-bytes v))]
      (catch #?(:clj Throwable :cljs :default) _
        rf.privacy/redacted-sentinel))))

(defn- project-scoped-key
  "Project a live resource scoped key `[scope resource-id params]` into its
  egress form `[<scope-handle> resource-id <params-handle>]` — the scope and
  params replaced by stable opaque handles, the registration `resource-id`
  preserved. Idempotent: a component already shaped `[:rf.resource/opaque …]`
  / `:rf/redacted` re-projects to itself (the handle of a handle is stable,
  and `:rf/redacted` is a non-secret scalar)."
  [[scope resource-id params]]
  [(opaque-handle scope) resource-id (opaque-handle params)])

(defn- project-identity-in-path
  "Replace any live resource scoped key embedded in `path` (e.g. the
  `:output` runtime path `[:runtime [:rf.runtime/resources :entries
  <scoped-key>]]`) with its projected form, so the secret-bearing scoped key
  never egresses through a structure position. Walks the path vector and
  projects any element that IS a scoped key."
  [path]
  (if (sequential? path)
    (mapv (fn [el]
            (cond
              (scoped-resource-key? el) (project-scoped-key el)
              (sequential? el)          (project-identity-in-path el)
              :else                     el))
          path)
    path))

(defn- project-resource-inputs
  "Project the live resource node's realized `:inputs`
  `[[:scope <scope>] [:param <params>]]` — opaque the `[:scope …]` /
  `[:param …]` payloads (the realized scope + params edges carry the same
  sensitive identity). Other input shapes ride through untouched. Idempotent:
  the payload runs through `opaque-handle`, which returns an already-projected
  handle / `:rf/redacted` UNCHANGED, so re-projecting an already-projected
  inputs vector is the identity (this is the one input position
  projected unconditionally rather than gated by the scoped-key shape, so its
  idempotence MUST come from the handle minter)."
  [inputs]
  (if (sequential? inputs)
    (mapv (fn [in]
            (if (and (vector? in) (= 2 (count in))
                     (#{:scope :param} (first in)))
              [(first in) (opaque-handle (second in))]
              in))
          inputs)
    inputs))

(defn- project-work-id
  "Project the scoped key embedded in a resource work-id
  `[:rf.work/resource <scoped-key> <generation>]` — a work-id of another
  shape (e.g. a non-resource family's work-id, or a bare scalar) rides
  through unchanged. Idempotent by
  delegation: `project-scoped-key` already returns an already-projected
  handle unchanged, regardless of whether the embedded key still LOOKS like
  a raw `scoped-resource-key?` shape (an opaqued key's tail is a vector, not
  a map)."
  [work-id]
  (if (and (vector? work-id) (= :rf.work/resource (first work-id)))
    (update work-id 1 project-scoped-key)
    work-id))

(defn- project-host-transient
  "Project the work-id embedded in a `:host-transient`
  `[[:rf.http/in-flight <work-id>]]` in-flight handle address
  — the abortable-handle address names the SAME work-id the work-ledger
  link carries, so it must not leak the raw scoped key either. Other
  shapes ride through untouched."
  [host-transient]
  (if (sequential? host-transient)
    (mapv (fn [entry]
            (if (and (vector? entry) (= 2 (count entry)) (= :rf.http/in-flight (first entry)))
              (update entry 1 project-work-id)
              entry))
          host-transient)
    host-transient))

(defn- project-resource-node-identity
  "Project ONE live resource node's secret-bearing identity fields:
  `:id` (the scoped key), `:output` (the scoped key embedded in the runtime
  path), the realized `:inputs` `[:scope …]` / `[:param …]` payloads,
  `:work-ledger :record :resource/key` (the scoped key on the work-ledger
  summary), the resource work-id embedded in BOTH `:work-ledger :work/id`
  and `:work-ledger :record :work/id` (a THIRD identity
  position, independent of `:resource/key`), and the `:host-transient`
  in-flight handle address (which names that SAME work-id). Structure /
  classification fields are untouched."
  [node]
  (cond-> node
    (scoped-resource-key? (:id node))
    (update :id project-scoped-key)

    (contains? node :output)
    (update :output project-identity-in-path)

    (contains? node :inputs)
    (update :inputs project-resource-inputs)

    (scoped-resource-key? (get-in node [:work-ledger :record :resource/key]))
    (update-in [:work-ledger :record :resource/key] project-scoped-key)

    (contains? (:work-ledger node) :work/id)
    (update-in [:work-ledger :work/id] project-work-id)

    (contains? (get-in node [:work-ledger :record]) :work/id)
    (update-in [:work-ledger :record :work/id] project-work-id)

    (contains? node :host-transient)
    (update :host-transient project-host-transient)))

(defn- resource-node-key?
  "True when `node-key` is a LIVE resource node id `[:resource <scoped-key>]`
  whose scoped key embeds a secret-bearing scope/params. A static resource
  node is `[:resource <bare-keyword>]`, which does NOT match."
  [node-key]
  (and (vector? node-key)
       (= :resource (first node-key))
       (scoped-resource-key? (second node-key))))

(defn- resource-node?
  "True when `node-key` names a RESOURCE node, the only family whose identity
  embeds a scoped key. The composer keys every resource node, static or live,
  `[:resource <resource-id-or-scoped-key>]`. That is the same family tag
  `project-resource-node-key` reads, so every node whose key it remaps has its
  fields projected too. Any other family's node is left alone: a live
  subscription's `:id` and `:output` carry its query vector, which can have
  the scoped-key shape without being one."
  [node-key]
  (and (vector? node-key)
       (= :resource (first node-key))))

(defn- project-resource-node-key
  "Project a live resource node KEY `[:resource <scoped-key>]` to
  `[:resource <projected-scoped-key>]`; other node keys ride through."
  [node-key]
  (if (resource-node-key? node-key)
    [:resource (project-scoped-key (second node-key))]
    node-key))

(defn- project-resource-edge-endpoints
  "Remap any `:from` / `:to` edge endpoint that names a live resource node
  key to the projected key, so an edge naming a redacted resource node still
  CONNECTS to it (structure preserved — the same projection applied to keys
  applies to endpoints, so the remap stays consistent)."
  [edges]
  (mapv (fn [edge]
          (cond-> edge
            (resource-node-key? (:from edge)) (update :from project-resource-node-key)
            (resource-node-key? (:to edge))   (update :to project-resource-node-key)))
        edges))

(defn project-graph
  "Project a `DerivationGraph` through the FRAME's egress policy for the wire
  boundary where a tool ships the graph OFF-BOX ([spec/Derivations.md]
  §Redaction metadata; EP-0014 issue-1).

  This is the egress redaction CALL SITE, which lives in the consuming
  tool — NOT in the registrar-derived composer (which composes
  nodes verbatim, raw-on-box by design). Each node's value-bearing summary
  field (`:value` / `:params` / `:query` / `:state` —
  `value-bearing-node-keys`) is walked through the single shared
  `elide-wire-value` walker under the named `frame-id`'s own elision policy:

  - **per-frame** — the policy is the FRAME's declared `:sensitive` /
    `:large` `:app-db` classifications (`re-frame.elision`, sourced from
    the commit-plane classification effects), passed explicitly as the
    `:frame` opt so the walk applies THAT frame's policy regardless of any
    ambient scope;
  - **fail-closed** — `elide-wire-value` redacts the whole value to the
    `:rf/redacted` sentinel when no frame is reachable (frameless egress
    under no `:rf.egress/include-sensitive?` opt-out); a sensitive-declared
    value is replaced by the sentinel; a large-declared value by the
    `:rf.size/large-elided` marker.

  STRUCTURE IS PRESERVED (the headline guarantee): a redacted value / param
  is still an `:input` / `:param` edge, the node is still present and still
  classified by its `:kind` superkind. The storage / evaluation / lifecycle
  classifications, `:source-form`, and `:refinement` are structure, not
  values, and are never touched.

  LIVE RESOURCE IDENTITY redaction. A
  live resource node carries its sensitive scope/params NOT in a
  value-bearing field but in its IDENTITY — the concrete scoped key
  `[cache-scope resource-id canonical-params]` that is the node KEY
  (`[:resource <scoped-key>]`), the node's `:id`, and is embedded in the
  `:output` runtime path, the realized `:inputs` `[:scope …]` / `[:param …]`
  edges, `:work-ledger :record :resource/key`, the resource work-id
  embedded in BOTH `:work-ledger :work/id` and `:work-ledger :record
  :work/id` (a THIRD identity position, independent of `:resource/key`),
  and the `:host-transient` in-flight handle address (which names that
  SAME work-id). The `elide-wire-value` value-path walk is structurally
  BLIND to these identity-embedded secrets. So this projection ALSO replaces
  each scoped key's secret-bearing scope + params with STABLE OPAQUE HANDLES
  (a keyed digest of the `rf.identity/canonical-bytes` token, stable within one
  runtime — see `opaque-handle`; fail-closed to `:rf/redacted` outside
  the CEDN-1 domain), preserving the registration `resource-id` so a tool
  still sees WHICH resource the node is. The SAME projection is applied to
  the `:nodes` keys AND every edge endpoint that names a resource node, so
  the remap stays CONSISTENT — a redacted resource node is still a node, and
  the edges naming it still connect (connectivity survives, the raw
  scope/params never cross the wire). The identity walk runs over RESOURCE
  nodes only (`resource-node?`): a subscription's query vector can have the
  scoped-key shape without being one.

  `frame-id` is the frame whose elision policy governs egress (the observed
  app's frame — typically the graph's `:frame` for a live graph). `opts`
  (optional) ride through to `elide-wire-value` (e.g.
  `:rf.egress/threshold-bytes`); the `:frame` opt is set from `frame-id` and
  overrides any caller-supplied one (egress redacts under the OBSERVED
  frame's policy, never a borrowed one).

  FAIL-CLOSED on a nil / UNREACHABLE frame: `elide-wire-value` reads its
  `:frame` opt by KEY PRESENCE, so STAMPING `frame-id` — whatever it is —
  is the whole mechanism. A nil id, a destroyed frame and a
  never-registered one all take the walker's unresolvable-frame branch
  (`rf.frame/frame` returns nil for each) and redact the whole value to
  `:rf/redacted`, rather than falling through to the AMBIENT
  dynamically-bound frame and shipping value-bearing fields RAW under that
  borrowed frame's (possibly empty) policy. Egress must NOT borrow an
  ambient frame. This is the silent leak the contract rules out — a graph
  egressing under no reachable policy redacts, never ships raw, even when
  an ambient frame is dynamically bound.

  The walker believes an explicit nil, so no local liveness probe and no
  minted dead-frame sentinel is needed.

  Returns the graph with redacted node value fields + projected live
  resource identities; `:mode` / `:frame` unchanged. IDEMPOTENT — a value may
  egress more than once (re-egress on re-render / re-subscribe / a forwarder
  event-bundle), and re-projecting an already-projected graph is the IDENTITY:
  `project-graph` ∘ `project-graph` == `project-graph`.
  `elide-wire-value` is a no-op over an already-`:rf/redacted` value (the
  sentinel is a non-matchable scalar), and the opaque resource handle is
  idempotent at the source: `opaque-handle` returns an already-
  `[:rf.resource/opaque …]` handle / `:rf/redacted` sentinel UNCHANGED rather
  than re-hash it into a fresh, different handle. (Pinned by the
  derivation-conformance egress arms `g-*` — the cross-family witnesses — and
  the focused Xray consumer/wiring tests.)"
  ([graph frame-id] (project-graph graph frame-id nil))
  ([graph frame-id opts]
   ;; STAMP the observed frame, whatever it is. `:frame` is read by KEY
   ;; PRESENCE, so a nil / destroyed / never-registered `frame-id` takes
   ;; `elide-wire-value`'s unresolvable-frame FAIL-CLOSED branch (whole value
   ;; ⇒ `:rf/redacted`) instead of falling through to the AMBIENT
   ;; dynamically-bound frame and shipping value-bearing fields RAW under
   ;; that frame's policy — the ambient-borrow leak this contract rules out.
   ;; The walker validates liveness itself, so there is no
   ;; local reachability probe and no sentinel frame id to mint.
   (let [walk-opts  (assoc opts :frame frame-id)
         redact-node
         (fn [node-key node]
           (cond-> (reduce
                    (fn [n k]
                      (if (contains? n k)
                        (assoc n k (rf.elision/elide-wire-value (get n k) walk-opts))
                        n))
                    node
                    value-bearing-node-keys)
             ;; the live resource scoped-key identity walk —
             ;; the secrets the value-path walk above cannot reach. Resource
             ;; nodes only.
             (resource-node? node-key) project-resource-node-identity))]
     (-> graph
         ;; remap node KEYS so a live resource scoped key carries no
         ;; raw scope/params in the node id (and the edge endpoints below
         ;; stay consistent with the remapped keys).
         (update :nodes (fn [nodes]
                          (into {}
                                (map (fn [[k node]]
                                       [(project-resource-node-key k)
                                        (redact-node k node)]))
                                nodes)))
         (update :edges (fn [edges]
                          (project-resource-edge-endpoints (or edges []))))))))

;; ---- bundle-isolation sentinel ------------------------------------------
;;
;; THE SENTINEL FOR THIS NAMESPACE IS NOT IN THIS FILE, AND THERE IS NO
;; PLANTED STRING TO MAINTAIN HERE. `implementation/scripts/check-bundle-
;; isolation.cjs` greps the EMITTED counter/positive-control MODULE, not this
;; source, and the literal it greps for `derivation-egress` is
;; `rf.resource/opaque` — the opaque resource-handle marker this namespace
;; MINTS on a live path (`opaque-handle` emits `[:rf.resource/opaque
;; <digest>]`; `already-projected?` reads the same keyword back), so a CLJS
;; keyword's fully-qualified name is interned into the emitted JS as a string
;; constant Closure does not rename.
;;
;; A planted `defonce ^:private bundle-isolation-sentinel` string here would
;; be inert: private and unconsumed, it is dropped by Closure `:advanced` —
;; ZERO occurrences in the emitted module — so a `do-not-rename` instruction
;; on it would guard nothing.
;;
;; The rule, and the reason a planted string is the WRONG instinct here: pick
;; a literal a LIVE code path emits, and verify the count in the emitted
;; module, never in the `.cljc`. The roster comment in check-bundle-
;; isolation.cjs beside the `derivation-egress` entry is the full account,
;; including two literals that do not work.
