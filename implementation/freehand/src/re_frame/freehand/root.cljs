(ns re-frame.freehand.root
  "The interpreted client mount surface — `v/mount`, `v/hydrate-root`,
  `v/unmount!`, the Root Descriptor, and the per-document live-root
  registry.

  This is the browser half of getting a Freehand tree onto a page. Its
  structural counterpart on the JVM is [[re-frame.freehand.tree/render]],
  which the same root form drives unchanged: `[app {…}]` mounts to real
  DOM here and answers a structural tree there, so the minimal one-root
  spelling is one spelling on both hosts.

  ## Root identity — derived by default, authored when it must be

  A mount derives a **Root Descriptor** — the small, static value that
  names WHAT was mounted and gives the root its identity:

      {:rf.root/schema-version 1
       :root-id            :app.shop/app   ; derived from the mounted view
       :view-id            :app.shop/app
       :root-id-provenance :derived}

  The single-root page authors nothing: with no `:root-id` and no
  `:disambiguator` the root-id is the mounted view's own registered id
  (Spec 004C §1.2). Two roots on one page need to differ, and they differ
  by a fact the author states — `:disambiguator :left` derives
  `[:shop/panel :left]`, or `:root-id` names the id outright. Provenance
  records which happened, so a duplicate diagnostic can say *both ids
  derived from the same view* rather than leaving the reader to guess.

  ## One page, N roots — the claims a mount makes before it renders

  A root claims three things in the per-document registry, and it claims
  them BEFORE any React work: its **root-id**, its **container**, and its
  effective **identifierPrefix**. Each has its own diagnostic, and every
  one of them is raised with the existing roots untouched — that is what
  failure isolation means at admission time (Spec 004C §7, Layer 3):

    - an id already live in the document → `:rf.error/duplicate-root-id`;
    - a container already owned by a DIFFERENT root →
      `:rf.error/root-container-in-use`;
    - a prefix already claimed → `:rf.error/duplicate-identifier-prefix`
      (the DERIVED prefix is injective over root-id, so this backstops
      AUTHORED prefixes, which can still alias).

  The one admitted repeat is the reload path: the same root-id into the
  same container RE-RENDERS the existing host root rather than allocating
  a second one (Spec 004C §3). A second `createRoot` on one container
  tears the whole tree down and re-seeds it.

  Reuse is what gives the claims their fourth arm. React root options are
  fixed at `createRoot`, so the root a reload re-renders cannot adopt a
  new `identifierPrefix` — and a re-mount authoring a different one is
  refused with `:rf.error/root-identifier-prefix-immutable` rather than
  silently kept, which would leave the entry claiming a prefix `use-id`
  never emits and the old prefix looking free to the check above. What is
  refused is DRIFT, not the re-mount: the same effective prefix, authored
  or derived, is the ordinary reload.

  The SAME `createRoot`-fixes-the-options fact governs the three host error
  callbacks — `:on-uncaught-error`, `:on-caught-error`,
  `:on-recoverable-error` — but their honest answer is the opposite one.
  Identity must stay put, so a prefix that would move is refused; a callback
  is meant to move — a hot reload's fresh closure is the ordinary case, and
  refusing it would make HMR needlessly brittle. So rather than pass the
  opts' callbacks straight to React (where the first mount's would be fixed
  and every later one silently ignored), the root installs one STABLE
  delegate per key that reads the live callback off a mutable cell, and an
  accepted re-mount advances that cell. React holds the delegate; the
  delegate's target is what the remount moves. A key a mount omits falls
  back to React's own default reporting for that error kind, so a stale
  closure is never retained and a newly supplied one is never dropped.

  ## HMR identity — occurrence identity is the qualified view id

  A root's occurrence identity is its `:root-id`, and a derived
  `:root-id` is the view's QUALIFIED id — a namespaced keyword that a
  redefinition does not move. A declared view is a descriptor VALUE, so a
  hot reload mints a new descriptor object; but the value it holds carries
  the same `:view-id`, so the derived `:root-id` is unchanged. Body and
  generation churn is an internal fact of the descriptor object, never
  part of the identity.

  Reusing the host root is HALF of not reseeding, and the weaker half.
  What hangs below the root is a React component, and React reconciles on
  component identity: a fresh component type under a reused root unmounts
  the boundary and mounts a new one, so the reloaded body appears on top
  of an occurrence that was thrown away. The other half is therefore
  [[re-frame.freehand.react]]'s boundary cache, keyed on the same
  qualified view id this registry keys on.

  ## Preflight — the frame, before React

  A root's `:frame` opt is its **plan**, and the plan runs to completion
  before `createRoot` is called (Spec 004C §3): the frame is ENSUREd,
  its `:initial-events` drain, and only then does React see anything. The
  ordering is the point — a view body that reads a subscription during
  the first render must find the frame already seeded, not seeded by an
  effect that runs after the first paint.

  Two spellings, and they mean different lifetimes:

    - `{:frame {:id :shop/main …}}` — the root OWNS the frame. It creates
      it if absent, refreshes its own plan when the config changes, and
      DESTROYS it at `unmount!` once no other live root references it.
    - `{:frame :shop/main}` — the root SCOPES a frame something else owns
      (a boot call, an SSR hydrate). It never creates and never destroys;
      a target naming no live frame fails loud.

  A plan meeting a frame another root installed under a DIFFERENT config
  fingerprint fails THAT root with `:rf.error/frame-payload-conflict`,
  before install and before React. The installed frame and the roots
  already using it are untouched: a bad plan affects exactly the roots
  carrying it.

  ## Preflight is also the ownership boundary

  Running the plan before React makes preflight the first thing a mount
  WRITES, and that splits every mount in two.

  AHEAD of it goes everything that can still fail on the SHAPE of the
  call — the opts, the identity, the three claims, and building the root's
  element. None of those has written anything, so a rejection there is
  free: no frame, no ledger record, no claim, nothing to give back. That
  is the same property the three admission claims have, and it is strictly
  better than any rollback, so the ordering is arranged to extend it as
  far as it will go.

  AFTER it, the document is not necessarily the one this mount was
  admitted against. `:initial-events` are application code running before
  React, and application code can mount a root, unmount one, or take the
  container. So the claim is re-asserted ([[recheck-claim!]]) and a mount
  whose ground moved refuses — `:rf.error/root-container-in-use` when its
  own plan took the container, `:rf.error/root-not-live` when the
  incumbent it was going to re-render is gone — rather than allocating a
  host root over whatever moved onto it.

  What is left outstanding past that point is small and enumerable: a host
  root React handed over, and the frame reference preflight took. A
  failure gives back exactly those two and nothing else — never an
  incumbent's reference and never a sibling's — and re-raises the original
  error unchanged ([[attempt!]]).

  ## Hydration — identity from the wire, then adopt the server's DOM

  A hydrating root does not derive its identity; it READS it. The server
  knows what it rendered and says so on the wire, in the **Root Manifest**
  it emits as the container's immediately following element sibling, and
  [[hydrate-root]] takes its `:root-id` and its `identifierPrefix` from
  that manifest's CONTENT (Spec 011 §Root Manifest v1). Guessing an
  identity and hoping it matches is exactly what breaks `use-id`
  hydration when the guess is wrong, and a client-side identity opt is
  refused here for the same reason.

  Discovery lives in the optional SSR artefact and is reached through the
  `:ssr/discover-root-manifest` late-bind hook — `freehand → core
  late-bind ← ssr`, the same shape [[re-frame.freehand.route-link-seam]]
  uses against routing. A direct require would drag server code into
  every client bundle and fail to compile every app that renders nothing
  on a server. Two failures, and neither is a new diagnostic: the
  artefact ABSENT is `:rf.error/ssr-artefact-missing` naming the
  `day8/re-frame2-ssr` coordinate; the artefact present and nothing
  adjacent is `:rf.error/root-manifest-invalid` `{:missing :manifest}`.

  **The empty-container fallback precedes discovery**, and it has to. A
  container with nothing to adopt is the client-only first load, and a
  page the server never rendered carries no manifest either — asking for
  one first would turn every client-only first load into a hard failure
  and delete the fallback outright. So the one input recognisable before
  React is involved is recognised first, and only a container that DOES
  carry server markup is required to carry the manifest that says what
  the server rendered it as. Markup without a manifest is a broken server
  render, not a client-only load.

  Verification is then React's own adoption: React diffs the client's first
  render against the server DOM and reports the divergences it RECOVERS
  from — a text mismatch, a missing/extra/wrong-type element — through
  the root's `onRecoverableError`. The framework surfaces that as
  `:rf.ssr/hydration-mismatch`, composed OVER any host callback (emit
  first, then delegate — never clobber), and bounded to the adoption
  window: React holds that callback for the root's whole lifetime, so an
  unbounded emit would mislabel a later recovery as a hydration mismatch.

  Attribute-only divergence is deliberately outside this signal — React
  documents that it makes no guarantee to patch attribute mismatches, so
  it calls neither `onRecoverableError` nor any production equivalent.
  The adoption signal is *React-recoverable adoption errors*, not
  exhaustive server-vs-client divergence detection.

  A container the server did not render has nothing to adopt, so
  hydration cannot proceed at all. That is the **fallback** path: the
  root mounts client-side instead, and says which happened through
  [[hydrated?]] rather than hydrating an empty node against a non-empty
  tree — which React would resolve by discarding and re-rendering
  everything anyway, loudly, for a page that was never server-rendered.

  ## Teardown

  [[unmount!]] is TOTAL. It releases the registry entry (id, container
  and prefix claims), unmounts the React root — whose layout cleanups
  disconnect every ViewCell below, releasing every dependency and
  retiring every published callback — and releases this root's reference
  to its frame, destroying the frame when the root owned it and no other
  live root still references it. What is left afterwards is nothing: not
  a claim, not a subscription, not a frame.

  INTERNAL. `v/mount`, `v/hydrate-root` and `v/unmount!` are the
  authoring surface over this namespace; nothing else here is application
  API.

  Normative owner:
  [`spec/004C-Roots-and-Mount.md`](../../../../../spec/004C-Roots-and-Mount.md);
  hydration and the fallback are
  [`spec/011-SSR.md`](../../../../../spec/011-SSR.md)."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.fingerprint :as fingerprint]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root-id :as root-id]
            [re-frame.freehand.shell :as shell]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.live-frame :as live-frame]
            [re-frame.trace :as trace]))

;; ---------------------------------------------------------------------------
;; The Root value
;; ---------------------------------------------------------------------------
;;
;; A small host handle, deliberately its OWN type rather than a map: a map
;; at the root head is exactly the shape a mistaken caller might pass to
;; `mount`, and a distinct type keeps a live host root from being confused
;; with a descriptor or a props map. It carries the static Root Descriptor
;; (the identity the registry keys on), the claims the registry holds on
;; its behalf, and the host handles a re-render and a teardown need.
;;
;; `callbacks` is a mutable cell (a `volatile!`) holding the root's CURRENT
;; host-error callbacks — the `{:on-uncaught-error … :on-caught-error …
;; :on-recoverable-error …}` subset of the last accepted mount's opts. React
;; fixes a root's options at `createRoot`, so the running root cannot adopt a
;; fresh callback object; the framework installs a stable delegate per key
;; that READS this cell, and an accepted re-mount advances the cell rather
;; than silently keeping the first mount's closures. A remount reuses the
;; incumbent's cell, so the delegates React already fixed see the new target.

(deftype Root [descriptor container react-root identifier-prefix frame-id hydrated callbacks])

(defn root?
  "True when `x` is a live [[mount]] / [[hydrate-root]] handle."
  [x]
  (instance? Root x))

(defn root-descriptor
  "The static Root Descriptor `root` was mounted under — the minimal
  `:rf.root/*` identity value (see the namespace docstring)."
  [^Root root]
  (.-descriptor root))

(defn root-identifier-prefix
  "The effective React `identifierPrefix` `root` claimed — authored, or
  derived from its root-id."
  [^Root root]
  (.-identifier-prefix root))

(defn root-frame-id
  "The frame this root bound into its React tree, or nil when it bound
  none."
  [^Root root]
  (.-frame-id root))

(defn hydrated?
  "True when `root` ADOPTED server-rendered markup; false when it
  client-rendered — either an ordinary [[mount]], or a [[hydrate-root]]
  that took the fallback path because its container carried nothing to
  adopt."
  [^Root root]
  (.-hydrated root))

;; ---------------------------------------------------------------------------
;; Opts — a closed grammar, validated before anything happens
;; ---------------------------------------------------------------------------

(def ^:private identity-opt-keys #{:root-id :disambiguator :identifier-prefix})
(def ^:private host-opt-keys #{:on-uncaught-error :on-caught-error :on-recoverable-error})
(def ^:private mount-opt-keys (into (conj identity-opt-keys :frame) host-opt-keys))

;; A hydrating root takes its identity FROM the server (Spec 004C §3): the
;; client must render under the server's prefix or `use-id` hydration
;; breaks, so an identity opt supplied client-side is a conflict, not an
;; override. `:frame` stays legal — a plan is preflight, not identity.
(def ^:private hydrate-opt-keys (into #{:frame} host-opt-keys))

(defn- bad-opts!
  [where reason extra]
  (error/throw-error!
    :rf.error/ui-tree-malformed where reason
    {:recovery :no-recovery :extra extra}))

(defn- check-opt-keys!
  [where opts allowed]
  (when-not (map? opts)
    (bad-opts! where
               (str where " takes an opts MAP; a " (name (:type (error/diag-value-summary opts)))
                    " is not one.")
               {:value (error/diag-value-summary opts)}))
  (when-let [unknown (seq (remove allowed (keys opts)))]
    (bad-opts! where
               (str where ": unknown root opt" (when (next unknown) "s") " "
                    (str/join ", " (map pr-str (sort-by str unknown)))
                    " — the opts map is CLOSED. It accepts "
                    (str/join ", " (map pr-str (sort-by str allowed))) ".")
               {:unknown (vec unknown)}))
  opts)

(defn- check-identity-opts!
  "Identity opts are a conflict at a HYDRATING root, not an override: the
  server already fixed this root's id and prefix, and a client that
  renders under a different prefix breaks `use-id` hydration outright."
  [opts]
  (when-let [bad (and (map? opts) (seq (filter identity-opt-keys (keys opts))))]
    (error/throw-error!
      :rf.error/root-manifest-invalid
      'v/hydrate-root
      (str "v/hydrate-root: identity opt" (when (next bad) "s") " "
           (str/join ", " (map pr-str (sort-by str bad)))
           " supplied client-side. A hydrating root takes its identity from the "
           "server that rendered it — the client must render under the server's "
           "identifierPrefix or use-id hydration breaks. Host-behaviour opts only.")
      {:recovery :drop-the-client-side-identity-opts
       :extra    {:conflicting-keys (vec (sort-by str bad))}}))
  opts)

;; ---------------------------------------------------------------------------
;; Root identity — authored, or derived from the mounted view
;; ---------------------------------------------------------------------------

(defn- head-view
  "The declared view at `root-form`'s head, or nil when the head is
  something else."
  [root-form]
  (let [head (and (vector? root-form) (first root-form))]
    (when (descriptor/view? head) head)))

(defn- mounted-view-id
  "The registered id of the single declared view at `root-form`'s head —
  the derivation source for the root's identity (Spec 004C §1.3).

  The minimal grammar is a bare declared view at the head: `[app {…}]`.
  Anything else — a bare DOM element, a fragment, a runtime value — has no
  single mounted view to derive a `:root-id` from, and rather than guess
  one this fails loud, naming both recoveries."
  [where root-form]
  (if-let [view (head-view root-form)]
    (:view-id (descriptor/describe view))
    (error/throw-error!
      :rf.error/ui-tree-malformed
      where
      (str where " has no single mounted view to derive a root-id from. Write "
           "(" where " [the-view {…}] …) with a v/defview at the head, or author "
           ":root-id — a root whose head is a bare element, a fragment or a "
           "runtime value has an identity only you can name.")
      {:recovery :no-recovery
       :extra    {:value (error/diag-value-summary
                           (and (vector? root-form) (first root-form)))}})))

(defn- resolve-identity
  "-> `{:root-id … :view-id … :provenance :authored|:derived}`.

  Authored wins, verbatim (Spec 004C §1.1). Otherwise the id derives from
  the mounted view's registered id, with `:disambiguator` appended when
  the site supplies one — the spelling that lets ONE view mount twice on
  one page with neither site authoring an id.

  `:view-id` records WHICH view was mounted, which is how a tool gets from
  a root back to a declaration. It is present exactly when the root form
  has one: derivation requires it, an authored id does not."
  [where root-form {:keys [root-id disambiguator]}]
  (when (and (some? root-id) (some? disambiguator))
    (bad-opts! where
               (str where ": :disambiguator modifies DERIVATION and :root-id is "
                    "already verbatim identity — keep one.")
               {:root-id root-id :disambiguator disambiguator}))
  (when (and (some? disambiguator) (not (root-id/scalar-disambiguator? disambiguator)))
    (bad-opts! where
               (str where ": :disambiguator must be a scalar — a keyword, a string "
                    "or an integer. It is identity, and identity a diagnostic "
                    "cannot print is identity nobody can act on.")
               {:value (error/diag-value-summary disambiguator)}))
  (if (some? root-id)
    (do
      (when-not (root-id/authored-root-id? root-id)
        (bad-opts! where
                   (str where ": :root-id must be a qualified keyword (canonical: "
                        ":page/shop) or a vector of a qualified keyword plus scalar "
                        "disambiguators (e.g. [:shop/panel :left]); got "
                        (pr-str root-id) ".")
                   {:root-id root-id}))
      (cond-> {:root-id root-id :provenance :authored}
        (head-view root-form)
        (assoc :view-id (:view-id (descriptor/describe (head-view root-form))))))
    (let [view-id (mounted-view-id where root-form)]
      {:root-id    (root-id/derive-root-id view-id disambiguator)
       :view-id    view-id
       :provenance :derived})))

(defn- descriptor-for
  "The Root Descriptor for a resolved identity (Spec 004C §2). Every field
  is a static fact of the mount site.

  `:root-id-provenance` is one of `:authored`, `:derived` or `:manifest`
  — the third being a hydrating root, whose id came off the wire rather
  than out of a derivation. It is a dev-only field, and it earns its place
  by making a duplicate diagnostic able to say WHERE the colliding id came
  from instead of leaving the reader to work it out."
  [{:keys [root-id view-id provenance]}]
  (cond-> {:rf.root/schema-version 1
           :root-id                root-id
           :root-id-provenance     provenance}
    (some? view-id) (assoc :view-id view-id)))

;; ---------------------------------------------------------------------------
;; The per-document live-root registry
;; ---------------------------------------------------------------------------
;;
;; `defonce`, so a namespace reload hands the reloaded code the SAME
;; registry a live root was registered in — the reload re-runs `mount` and
;; must find that root, not a fresh empty map. Keyed by `:root-id`.
;;
;; ONE map, not three. The container claim and the prefix claim are
;; PROPERTIES of a live root, so they are read off the same entries rather
;; than mirrored into parallel tables that can disagree. A page holds a
;; handful of roots; the scan is nothing, and the invariant is free.

(defonce ^:private live-roots (atom {}))

;; The frame ledger:
;;   frame-id -> {:config-fingerprint … :installed-by root-id :refs #{root-id}}
;;
;; `:installed-by` names the root that ENSUREd the frame (a `{:id …}` plan)
;; and is nil for a frame this page only SCOPES; `:refs` is every live root
;; that referenced it, which is what makes teardown able to destroy an
;; installed frame exactly when the last of them goes.
(defonce ^:private frame-ledger (atom {}))

(defn ^:no-doc live-root-ids
  "Every root-id currently live in the document — a registry read for
  tests and tools, never application API."
  []
  (set (keys @live-roots)))

(defn ^:no-doc frame-ledger-snapshot
  "The preflight ledger projected for reading: frame-id →
  `{:config-fingerprint :installed-by :refs}`. A test and diagnostic seam."
  []
  @frame-ledger)

(defn ^:no-doc reset-registry!
  "Drop every registry and ledger entry WITHOUT tearing anything down — a
  test-isolation seam only. Total teardown is [[unmount!]]; this exists so
  a suite that mounts under an id another suite also uses cannot inherit a
  stale entry masquerading as a live root."
  []
  (reset! live-roots {})
  (reset! frame-ledger {})
  nil)

(defn- owner-of-container
  [dom-node]
  (some (fn [[rid ^Root r]]
          (when (identical? dom-node (.-container r)) rid))
        @live-roots))

(defn- owner-of-prefix
  [prefix except-root-id]
  (some (fn [[rid ^Root r]]
          (when (and (not= rid except-root-id)
                     (= prefix (.-identifier-prefix r)))
            rid))
        @live-roots))

(defn- require-container!
  "Assert `dom-node` is a live DOM element, and answer it.

  Separated from [[claim!]] because a HYDRATING root has to make this
  check before it knows its `root-id` at all: identity arrives in the
  manifest, discovery walks the container's `nextElementSibling`, and a
  nil container walked that way answers nil — so the honest \"you handed
  me no container\" fault would masquerade as \"no manifest here\"."
  [where root-id dom-node]
  (when (or (nil? dom-node) (nil? (.-nodeType dom-node)))
    (error/throw-error!
      :rf.error/root-container-missing where
      (str where ": the container is not a DOM element. A root mounts INTO a "
           "live node — check the lookup that was meant to produce it.")
      {:recovery :supply-a-live-container
       :extra    (cond-> {:value (error/diag-value-summary dom-node)}
                   (some? root-id) (assoc :root-id root-id))}))
  dom-node)

(defn- require-stable-identifier-prefix!
  "The fourth admission claim, and the one only a RE-MOUNT can fail: the
  effective `identifierPrefix` a live root was created with is the prefix
  it keeps.

  The reload path re-renders the EXISTING host root, and a React root's
  options are fixed at `createRoot` — the running root has no way to adopt
  a new prefix. Accepting the drift would put the registry and React out
  of step in the one place it costs the most: the entry would claim a
  prefix `use-id` never emits, and would leave the OLD prefix looking free
  to the uniqueness check, so the next root to author it is admitted
  against a live root still rendering under it. That is the cross-root
  `use-id` collision the prefix claim exists to prevent, manufactured by
  the check itself.

  So drift fails loud, alongside the other three claims and for the same
  reason: raised before preflight and before any React work, the live root
  keeps its prefix, its claim, its frame and its committed DOM. It runs
  inside [[claim!]] with PRECEDENCE over the cross-root prefix-uniqueness
  check: once the same-root/same-container incumbent is known, a requested
  prefix that differs from ITS own is drift the reload cannot carry — and
  its recovery, unmount-first, is the real one. Reporting it as a duplicate
  of whichever OTHER root happens to own the requested value would send the
  author chasing a distinct prefix, when EVERY prefix but the incumbent's is
  equally forbidden for that reused root."
  [where root-id requested ^Root existing]
  (when (and (some? existing) (not= requested (.-identifier-prefix existing)))
    (error/throw-error!
      :rf.error/root-identifier-prefix-immutable where
      (str "re-mounting root " (pr-str root-id) " asks for identifierPrefix "
           (pr-str requested) ", but the live root in that container was created "
           "with " (pr-str (.-identifier-prefix existing)) " — a root's "
           "identifierPrefix is fixed when React creates it, so a re-mount "
           "re-renders that root under the OLD prefix and use-id keeps emitting "
           "it. Unmount this root and mount again to render under the new "
           "prefix, or drop the change.")
      {:recovery :unmount-before-changing-identifier-prefix
       :extra    {:root-id   root-id
                  :requested requested
                  :existing  (.-identifier-prefix existing)}}))
  nil)

(defn- claim!
  "Assert the admission claims and answer the live root this mount
  RE-RENDERS, or nil when it is a fresh root.

  Every arm here throws before any React work and before any registry or
  ledger mutation, so a rejected root leaves the document exactly as it
  found it — the existing roots keep rendering, and nothing has to be
  rolled back because nothing was written.

  Read-only and cheap, which is why it is also what [[recheck-claim!]]
  runs a second time once the frame plan has had its chance to move the
  document underneath the mount."
  [where dom-node root-id prefix]
  (require-container! where root-id dom-node)
  (let [^Root existing (get @live-roots root-id)]
    (when (and (some? existing) (not (identical? dom-node (.-container existing))))
      (error/throw-error!
        :rf.error/duplicate-root-id where
        (str "root-id " (pr-str root-id) " is already live in this document, in a "
             "DIFFERENT container. Root-ids are page-unique identity: two roots "
             "under one id would each believe they were the page's " (pr-str root-id)
             ". Author :root-id, or add :disambiguator so the two sites derive "
             "distinct ids.")
        {:recovery :make-root-ids-unique
         :extra    {:root-id root-id}}))
    (when (nil? existing)
      (when-let [owner (owner-of-container dom-node)]
        (error/throw-error!
          :rf.error/root-container-in-use where
          (str "that container is already owned by the live root " (pr-str owner)
               " — one container, one root. A second host root on one node tears "
               "the first one's tree down and re-seeds it. Unmount the owner "
               "first, or mount into a fresh node.")
          {:recovery :unmount-the-owning-root-first
           :extra    {:root-id root-id :owner-root-id owner}})))
    ;; The fourth admission claim, given PRECEDENCE over cross-root prefix
    ;; ownership below: once the same-root/same-container incumbent is known,
    ;; a requested prefix that differs from ITS own is immutable-prefix drift,
    ;; not a duplicate of whatever other root owns the requested value. A
    ;; fresh root (no incumbent) skips this and meets the duplicate check.
    (require-stable-identifier-prefix! where root-id prefix existing)
    (when-let [owner (owner-of-prefix prefix root-id)]
      (error/throw-error!
        :rf.error/duplicate-identifier-prefix where
        (str "identifierPrefix " (pr-str prefix) " is already claimed by the live "
             "root " (pr-str owner) ". Two roots sharing a prefix collide React's "
             "use-id output. The derived default is unique per root-id, so this is "
             "an authored prefix — give one root a distinct :identifier-prefix, or "
             "drop the opt and take the derived default.")
        {:recovery :make-identifier-prefixes-unique
         :extra    {:root-id root-id :identifier-prefix prefix :owner-root-id owner}}))
    existing))

(defn- recheck-claim!
  "Re-assert the admission claims AFTER preflight, and require the same
  answer they gave before it.

  A plan's `:initial-events` are application code, and they run BEFORE
  React — so by the time preflight returns, the document is not
  necessarily the one this mount was admitted against. A handler that
  mounts a root has taken the container; a handler that unmounts one has
  taken the incumbent away. Re-asserting is three reads over a handful of
  roots, and it is the whole difference between refusing and clobbering.

  [[claim!]] itself raises the ordinary admission diagnostics for every way
  the document can move under a DIFFERENT id. What is left for here is the
  two ways it can move and still answer: an id and container that were free
  and are now taken, and an incumbent that is no longer the one captured."
  [where dom-node root-id prefix ^Root admitted]
  (let [^Root now (claim! where dom-node root-id prefix)]
    (when-not (identical? now admitted)
      (if (some? admitted)
        (error/throw-error!
          :rf.error/root-not-live where
          (str "the live root under " (pr-str root-id) " is no longer the one this "
               "mount was admitted to re-render — the frame plan unmounted it, or a "
               "newer root claimed the id while the plan ran. Rendering through the "
               "handle captured before the plan would render into a host root React "
               "has already discarded, and registering it would overwrite whatever "
               "holds the id now.")
          {:recovery :recreate-the-root
           :extra    {:root-id root-id}})
        (error/throw-error!
          :rf.error/root-container-in-use where
          (str "that container was free when this mount was admitted and is now owned "
               "by the live root " (pr-str (:root-id (.-descriptor now))) " — this "
               "mount's own frame plan took it. A plan's :initial-events run BEFORE "
               "React, so a handler that mounts a root wins the container; a second "
               "host root on that node would tear the first one's tree down and "
               "re-seed it.")
          {:recovery :unmount-the-owning-root-first
           :extra    {:root-id       root-id
                      :owner-root-id (:root-id (.-descriptor now))}})))
    now))

;; ---------------------------------------------------------------------------
;; Preflight — the frame plan, before React
;; ---------------------------------------------------------------------------

(defn- plan-for
  "Read the `:frame` opt into a preflight plan, or nil when the root binds
  no frame.

    :frame :shop/main          → SCOPE an existing frame (never created here)
    :frame {:id :shop/main …}  → ENSURE it: create-if-absent, config verbatim"
  [where frame-opt]
  (cond
    (nil? frame-opt) nil

    (keyword? frame-opt)
    {:frame-id frame-opt :owned? false}

    (and (map? frame-opt) (keyword? (:id frame-opt)))
    {:frame-id           (:id frame-opt)
     :owned?             true
     :config             (dissoc frame-opt :id)
     :config-fingerprint (fingerprint/config-fingerprint (:id frame-opt)
                                                         (dissoc frame-opt :id))}

    :else
    (bad-opts! where
               (str where ": :frame is either a frame-id keyword — SCOPE a frame "
                    "something else owns — or a make-frame opts map carrying :id, "
                    "which the root ENSUREs and owns for its lifetime.")
               {:value (error/diag-value-summary frame-opt)})))

(defn- preflight!
  "Run `plan` to completion, and answer the frame-id it bound. Called
  BEFORE `createRoot`, so a body that reads a subscription on its first
  render finds a seeded frame rather than an empty one — and called after
  every step that can fail on the shape of the call, so a rejected mount
  never reaches it.

  The ledger is what makes several roots over one frame honest. An equal
  fingerprint is the ratified idempotent no-op — the frame is NOT re-seeded,
  because `:initial-events` are a seed, not a replay. A DIFFERENT
  fingerprint recorded by a DIFFERENT root is the conflict: one frame, one
  plan."
  [where {:keys [frame-id owned? config config-fingerprint] :as plan} root-id]
  (when (some? plan)
    (let [record (get @frame-ledger frame-id)]
      (when (and owned?
                 (some? (:installed-by record))
                 (not= (:installed-by record) root-id)
                 (not= (:config-fingerprint record) config-fingerprint))
        (error/throw-error!
          :rf.error/frame-payload-conflict where
          (str "frame " (pr-str frame-id) " is already installed by root "
               (pr-str (:installed-by record)) " under a DIFFERENT config. One "
               "frame, one plan: a second config would silently reset whatever "
               "the first root's frame has been doing. Align the two configs, or "
               "give this root its own frame.")
          {:recovery :align-frame-plan-config
           :extra    {:frame-id  frame-id
                      :installed {:config-fingerprint (:config-fingerprint record)
                                  :installed-by       (:installed-by record)}
                      :arriving  {:config-fingerprint config-fingerprint
                                  :root-id            root-id}}}))
      (if owned?
        ;; ENSURE. `make-frame` is idempotent replacement: absent → created
        ;; and its :initial-events drain; present under the same plan → left
        ;; exactly as it is. The same-root differing fingerprint is a config
        ;; edit, so the plan re-runs — durable state survives, because that
        ;; is what idempotent replacement means.
        (when (or (nil? (frame/frame frame-id))
                  (not= (:config-fingerprint record) config-fingerprint))
          (live-frame/make-frame (assoc config :id frame-id)))
        ;; SCOPE. A frame this root does not own must already exist — there
        ;; is no config here to create one from, and scoping a frame that
        ;; is not there would leave every read below the root silently
        ;; frameless.
        (when (nil? (frame/frame frame-id))
          (error/throw-error!
            :rf.error/frame-provider-frame-absent where
            (str where ": :frame " (pr-str frame-id) " names no live frame. A "
                 "keyword :frame SCOPES a frame something else owns; pass a "
                 "make-frame opts map carrying :id for the root to own it, or "
                 "create the frame before mounting.")
            {:recovery :ensure-or-create-the-frame
             :extra    {:root-id root-id :frame frame-id}})))
      (swap! frame-ledger update frame-id
             (fn [r]
               {:config-fingerprint (if owned? config-fingerprint (:config-fingerprint r))
                :installed-by       (if owned? (or (:installed-by r) root-id) (:installed-by r))
                :refs               (conj (or (:refs r) #{}) root-id)}))
      frame-id)))

(defn- frame-ref-held?
  "True when `root-id` ALREADY references `frame-id` in the ledger.

  Read BEFORE preflight, so an attempt that fails after it can tell the
  reference IT took from one the incumbent — or a sibling root — was
  already holding. Giving back a reference this attempt never took is how
  a rollback turns one failure into two."
  [frame-id root-id]
  (boolean (and (some? frame-id)
                (contains? (:refs (get @frame-ledger frame-id)) root-id))))

(defn- release-frame!
  "Drop `root-id`'s reference to its frame, and destroy the frame when a
  root INSTALLED it and this was the last live reference.

  A scoped frame is never destroyed — the root borrowed it. An installed
  frame outlives its root only while a sibling still references it, which
  is the same rule that makes install idempotent in the first place."
  [frame-id root-id]
  (when (some? frame-id)
    (let [{:keys [installed-by refs]} (get @frame-ledger frame-id)
          remaining                   (disj (or refs #{}) root-id)]
      (if (seq remaining)
        (swap! frame-ledger update frame-id assoc :refs remaining)
        (do (swap! frame-ledger dissoc frame-id)
            (when (and (some? installed-by) (some? (frame/frame frame-id)))
              (frame/destroy-frame! frame-id))))))
  nil)

;; ---------------------------------------------------------------------------
;; React root options
;; ---------------------------------------------------------------------------

(defn- report-error!
  "React's own default reporting for an UNCAUGHT or a RECOVERABLE error:
  hand it to the host `reportError` so a window error handler still sees it,
  or `console.error` on a runtime without one. Replicated here because the
  framework always installs a stable delegate for these keys (so a remount
  can advance the callback), and once a delegate is set React no longer runs
  its own default — a delegate that swallowed the no-callback case would be
  a silent regression rather than the honest passthrough this is."
  [error*]
  (if (fn? (.-reportError js/globalThis))
    (js/reportError error*)
    (when (exists? js/console) (.error js/console error*))))

(defn- report-caught!
  "React's own default for an error an Error Boundary CAUGHT: the boundary
  handled it, so the default is an informational `console.error`, not a
  re-throw to the window the way an uncaught or recoverable error earns."
  [error*]
  (when (exists? js/console) (.error js/console error*)))

(defn- host-callback-delegate
  "A stable React error-option callback that dispatches to the CURRENT
  authored callback under `k` in the root's `callbacks` cell, falling back
  to `default!` — React's own reporting for that error kind — when the cell
  holds no fn there.

  This is the whole of the remount-honesty mechanism: React fixes a root's
  options at creation, so the delegate object never changes, but the cell it
  reads is advanced by an accepted re-mount. A callback supplied on the
  reload therefore takes effect and the first mount's is not silently kept."
  [callbacks k default!]
  (fn [error* error-info]
    (let [f (get @callbacks k)]
      (if (fn? f) (f error* error-info) (default! error*)))))

(defn- emit-hydration-mismatch!
  [root-id error*]
  (when interop/debug-enabled?
    (trace/emit! :warning :rf.ssr/hydration-mismatch
                 {:root-id  root-id
                  :error    (some-> error* .-message)
                  :where    're-frame.freehand/hydrate-root
                  :recovery :warned-and-replaced})))

(defn ^:no-doc hydration-reporter
  "The composed `onRecoverableError` for a HYDRATING root.

  `adoption` is the root-local `#js {:adopting true}` window flag;
  `callbacks` is the root's live host-callback cell (see [[Root]]). The
  framework diagnostic fires only while the window is open, and the CURRENT
  authored `:on-recoverable-error` — the one the last accepted mount
  supplied — is ALWAYS delegated to (compose, never clobber), inside the
  window and outside it, falling back to React's default when none is set.
  Reading the callback off the cell rather than capturing it is what lets a
  re-mount advance it: React fixes this delegate at `hydrateRoot`, but the
  cell it reads is not fixed.

  Public so a mounted browser proof can drive the REAL callback across the
  window boundary rather than a stand-in shaped like it."
  [^js adoption root-id callbacks]
  (let [delegate (host-callback-delegate callbacks :on-recoverable-error report-error!)]
    (fn on-recoverable [error* error-info]
      (when (.-adopting adoption)
        (emit-hydration-mismatch! root-id error*))
      (delegate error* error-info))))

(defn ^:no-doc adoption-window-closer
  "A React component that CLOSES a hydrating root's adoption window on its
  first commit. It reads the window flag off its `rfAdoption` prop and
  clears it from a passive effect with empty deps, so it runs exactly once
  and strictly AFTER the hydration commit React reports mismatches
  against. Renders nil, so it adds nothing to adopt and cannot itself
  mismatch."
  [^js props]
  (react/useEffect
    (fn close-window []
      (when-some [adoption (.-rfAdoption props)]
        (set! (.-adopting adoption) false))
      js/undefined)
    #js [])
  nil)

(defn- root-options
  "The React root options object: `identifierPrefix` and the three stable
  host-error delegates. Each delegate reads the CURRENT authored callback
  off `callbacks` (the root's live cell, advanced by an accepted remount)
  and defaults to React's own reporting when none is set — so installing
  them is honest for a root that supplied no callback and STAYS honest
  across a remount that supplies or changes one. `recoverable` overrides the
  recoverable delegate for a hydrating root: the adoption reporter, which
  composes the mismatch signal over the same cell."
  [prefix callbacks recoverable]
  (let [o (js-obj)]
    (when prefix (aset o "identifierPrefix" prefix))
    (aset o "onUncaughtError" (host-callback-delegate callbacks :on-uncaught-error report-error!))
    (aset o "onCaughtError"   (host-callback-delegate callbacks :on-caught-error report-caught!))
    (aset o "onRecoverableError"
          (or recoverable (host-callback-delegate callbacks :on-recoverable-error report-error!)))
    o))

(defn- root-element
  "The element a root renders: the root form's own element, wrapped in the
  frame provider when the root bound a frame, plus any extra children the
  mount kind needs (the hydration window closer). The provider is the
  outermost wrapper because a `useContext` read anywhere below it must
  resolve, including inside the mounted view's own boundary."
  [root-form frame-id extra]
  (let [element (fr/element root-form)]
    (cond
      (some? frame-id) (apply shell/provide-frame frame-id element extra)
      (seq extra)      (apply react/createElement react/Fragment nil element extra)
      :else            element)))

;; ---------------------------------------------------------------------------
;; mount
;; ---------------------------------------------------------------------------

(defn- register!
  [root-id ^Root root]
  (swap! live-roots assoc root-id root)
  root)

(defn- attempt!
  "Run the post-preflight half of a mount, giving back exactly what this
  attempt took if it throws.

  Preflight is the first thing a mount WRITES, and everything ahead of it
  is ordered so a rejection there has nothing to undo. What can be
  outstanding after it is therefore small and enumerable: the host root
  React hands over, and — when `acquired?` — the frame reference preflight
  took for this root. `body` reports the host root through the `keep!` it
  is handed, the moment React returns one; an INCUMBENT's host root is
  never reported, because a failed re-render must leave the incumbent
  rendering exactly as it was.

  The original failure stays primary: the give-back is best-effort, and
  the throw is re-raised unchanged."
  [frame-id root-id acquired? body]
  (let [host (volatile! nil)]
    (try
      (body (fn keep! [react-root] (vreset! host react-root) react-root))
      (catch :default e
        (when-some [react-root @host]
          (try (.unmount react-root) (catch :default _ nil)))
        (when acquired? (release-frame! frame-id root-id))
        (throw e)))))

(defn- client-render!
  "Allocate a host root, render `element` into it, and register the result
  — the shared tail of a fresh [[mount]] and of [[hydrate-root]]'s
  fallback. Both are ordinary client renders, and both hand the host root
  they allocate to `keep!` so a render that throws gives it back."
  [desc dom-node prefix opts frame-id element keep!]
  (let [callbacks  (volatile! (select-keys opts host-opt-keys))
        react-root (keep! (rdc/createRoot dom-node (root-options prefix callbacks nil)))]
    (.render react-root element)
    (register! (:root-id desc)
               (->Root desc dom-node react-root prefix frame-id false callbacks))))

(defn mount
  "Mount the declared view at `root-form`'s head into `dom-node`, and
  return the [[Root]] host handle.

      (v/mount [app {}] (js/document.getElementById \"app\"))
      (v/mount [panel {}] left  {:disambiguator :left :frame {:id :shop/main}})

  IDEMPOTENT PER ROOT (Spec 004C §3): re-mounting the same `:root-id` into
  the same container re-renders the existing host root instead of
  allocating a second one. That is the reload path — a hot reload re-runs
  the mount, finds the root live under its qualified-view-id identity, and
  re-renders the new body without reseeding the host root. The one thing
  that re-mount cannot carry is a DIFFERENT effective `identifierPrefix`:
  React fixes a root's options when it creates it, so drift is refused
  (`:rf.error/root-identifier-prefix-immutable`) rather than silently
  ignored — unmount, then mount again, to render under a new prefix.

  `opts` is a CLOSED map: `:root-id` / `:disambiguator` /
  `:identifier-prefix` (identity), `:frame` (the preflight plan), and
  React's `:on-uncaught-error` / `:on-caught-error` /
  `:on-recoverable-error`.

  The three host error callbacks are LATE-BOUND across the reload. React
  fixes a root's options when it creates it, so the framework installs a
  stable delegate per key and an accepted re-mount ADVANCES the callback the
  delegate runs: the effective callback for each key is the one from the
  MOST RECENT accepted mount. A re-mount that changes a callback drops the
  stale one; a re-mount that omits a callback it earlier supplied restores
  React's own default reporting for that kind. What never happens is the
  first mount's closure quietly outliving a reload that replaced it — unlike
  the immutable `:identifier-prefix`, a callback is meant to move."
  ([root-form dom-node] (mount root-form dom-node {}))
  ([root-form dom-node opts]
   (check-opt-keys! 'v/mount opts mount-opt-keys)
   (let [ident          (resolve-identity 'v/mount root-form opts)
         root-id        (:root-id ident)
         desc           (descriptor-for ident)
         prefix         (or (:identifier-prefix opts)
                            (root-id/default-identifier-prefix root-id))
         plan           (plan-for 'v/mount (:frame opts))
         frame-id       (:frame-id plan)
         ^Root existing (claim! 'v/mount dom-node root-id prefix)
         ;; The element BEFORE preflight. Building it is the last step that
         ;; can fail on the SHAPE of the call, and preflight is the FIRST
         ;; step that writes anything — so a malformed root form is refused
         ;; with no frame created, no ledger record and no claim to give
         ;; back. It needs the frame's id and nothing else, which the plan
         ;; already carries: the provider wrapper names a frame, it does
         ;; not read one.
         element        (root-element root-form frame-id nil)
         acquired?      (and (some? frame-id) (not (frame-ref-held? frame-id root-id)))]
     ;; The fourth admission claim — the immutable identifierPrefix — is
     ;; asserted inside `claim!` above, before preflight and with precedence
     ;; over cross-root prefix ownership, so a refused re-mount has run no
     ;; plan and given nothing away.
     (preflight! 'v/mount plan root-id)
     (attempt!
       frame-id root-id acquired?
       (fn [keep!]
         (recheck-claim! 'v/mount dom-node root-id prefix existing)
         (if (some? existing)
           ;; The reload path: the same host root, re-rendered. No createRoot,
           ;; so nothing downstream is reseeded; the descriptor snapshot is
           ;; refreshed because the mounted view object may be a fresh
           ;; generation, but the identity it keys on is unchanged. That host
           ;; root belongs to the INCUMBENT, so it is not handed to `keep!`:
           ;; a re-render that fails leaves the incumbent rendering.
           (let [react-root (.-react-root existing)
                 callbacks  (.-callbacks existing)
                 root       (->Root desc dom-node react-root prefix frame-id
                                    (.-hydrated existing) callbacks)]
             ;; Advance the live host-callback cell to THIS mount's callbacks
             ;; before re-rendering. React fixed the error delegates at
             ;; createRoot, so a reload's fresh :on-*-error closures reach
             ;; React only through the cell those delegates read; keeping the
             ;; incumbent's cell object is what makes the already-fixed
             ;; delegates see the new target instead of the stale one.
             (vreset! callbacks (select-keys opts host-opt-keys))
             (.render react-root element)
             (register! root-id root))
           (client-render! desc dom-node prefix opts frame-id element keep!)))))))

;; ---------------------------------------------------------------------------
;; hydrate-root
;; ---------------------------------------------------------------------------

(defn- server-rendered?
  "True when `dom-node` carries markup a hydration could adopt. An empty
  container is the client-only first load — there is nothing there to
  adopt, and hydrating against it is not a degraded adoption but no
  adoption at all."
  [dom-node]
  (boolean (or (pos? (.-childElementCount dom-node))
               (not (str/blank? (.-textContent dom-node))))))

(def ^:private ssr-artefact
  ;; The optional-artefact identity the hook-absent diagnostic reports.
  ;; Freehand carries its own copy exactly as `re-frame.freehand.route-link-seam`
  ;; carries its own routing copy: the hydrate site names the artefact that is
  ;; missing without reaching into core internals, and without a compile-time
  ;; reference to a namespace a client-only app never has on its classpath.
  {:error-keyword :rf.error/ssr-artefact-missing
   :maven         "day8/re-frame2-ssr"
   :require-ns    "re-frame.ssr"})

(defn- discover-manifest!
  "The Root Manifest for `dom-node` — the validated manifest, or nil when
  there is none adjacent.

  Resolved through the `:ssr/discover-root-manifest` late-bind hook, which
  the SSR artefact publishes at namespace load: `freehand → core late-bind
  ← ssr`. `hydrate-root` is a BOOT-TIME caller, so this is the plain
  unmemoised resolution — no cached hook, because this is not a hot path —
  and `require-fn!` supplies the hook-absent throw, which names the
  `day8/re-frame2-ssr` coordinate an app that hydrates without the SSR
  artefact needs to add.

  Absence is not an error at the discovery layer (Spec 011 §Discovery): nil
  means \"no manifest here\", and the caller decides what that means."
  [dom-node]
  ((late-bind/require-fn! :ssr/discover-root-manifest
                          'v/hydrate-root
                          ssr-artefact)
   dom-node))

(defn- require-fresh-root!
  "A hydrating root must be the FIRST root on its container. Hydration
  adopts server markup, and a live client root has already replaced it."
  [^Root existing root-id]
  (when (some? existing)
    (error/throw-error!
      :rf.error/duplicate-root-id 'v/hydrate-root
      (str "root-id " (pr-str root-id) " is already live in this container. "
           "Hydration ADOPTS server markup, and a live client root has already "
           "replaced it — re-render through v/mount, or unmount first.")
      {:recovery :make-root-ids-unique
       :extra    {:root-id root-id}}))
  nil)

(defn- hydrate-fallback!
  "The FALLBACK: an ordinary client mount, spelled as a hydration.

  Nothing in the container means nothing to adopt — the client-only first
  load of a page whose server never rendered this root. Identity therefore
  derives exactly as [[mount]]'s does: there is no server render here, so
  there is no server identity to read."
  [dom-node root-form opts]
  (let [ident    (resolve-identity 'v/hydrate-root root-form opts)
        root-id  (:root-id ident)
        prefix   (root-id/default-identifier-prefix root-id)
        plan     (plan-for 'v/hydrate-root (:frame opts))
        frame-id (:frame-id plan)
        existing (claim! 'v/hydrate-root dom-node root-id prefix)]
    ;; Before preflight, because a rejected root must not have run one.
    (require-fresh-root! existing root-id)
    (let [element   (root-element root-form frame-id nil)
          acquired? (and (some? frame-id) (not (frame-ref-held? frame-id root-id)))]
      (preflight! 'v/hydrate-root plan root-id)
      (attempt!
        frame-id root-id acquired?
        (fn [keep!]
          (recheck-claim! 'v/hydrate-root dom-node root-id prefix existing)
          (client-render! (descriptor-for ident) dom-node prefix opts frame-id
                          element keep!))))))

(defn- adopt!
  "The ADOPTION path: `manifest` is the server's own account of what it
  rendered into `dom-node`, and this root renders under it.

  `:root-id` and `:identifier-prefix` come from the manifest's CONTENT and
  from nowhere else — not from the element that carried it, and not from a
  derivation that would have to agree with the server by coincidence.
  `:view-id` stays the client's own fact: it records which declared view
  this site mounted, which is how a tool gets from a root back to a
  declaration."
  [dom-node root-form opts manifest]
  (let [root-id  (:root-id manifest)
        ident    (cond-> {:root-id root-id :provenance :manifest}
                   (head-view root-form)
                   (assoc :view-id (:view-id (descriptor/describe (head-view root-form)))))
        prefix   (:identifier-prefix manifest)
        plan     (plan-for 'v/hydrate-root (:frame opts))
        frame-id (:frame-id plan)
        existing (claim! 'v/hydrate-root dom-node root-id prefix)]
    (require-fresh-root! existing root-id)
    ;; The adoption window, its reporter and the element they ride on are
    ;; all built BEFORE preflight, for the same reason the element is: they
    ;; are construction, they can fail, and preflight is what writes.
    (let [callbacks (volatile! (select-keys opts host-opt-keys))
          adoption  #js {:adopting true}
          reporter  (hydration-reporter adoption root-id callbacks)
          closer    (react/createElement adoption-window-closer
                                         #js {:key "rf-adoption" :rfAdoption adoption})
          element   (root-element root-form frame-id [closer])
          acquired? (and (some? frame-id) (not (frame-ref-held? frame-id root-id)))]
      (preflight! 'v/hydrate-root plan root-id)
      (attempt!
        frame-id root-id acquired?
        (fn [keep!]
          (recheck-claim! 'v/hydrate-root dom-node root-id prefix existing)
          (let [react-root (keep! (rdc/hydrateRoot dom-node element
                                                   (root-options prefix callbacks reporter)))]
            (register! root-id (->Root (descriptor-for ident) dom-node react-root
                                       prefix frame-id true callbacks))))))))

(defn hydrate-root
  "Adopt the server-rendered markup already in `dom-node` for the declared
  view at `root-form`'s head, and return the [[Root]] host handle.

      (v/hydrate-root (js/document.getElementById \"app\") [app {}])

  Identity comes FROM THE WIRE. The server emits a Root Manifest as the
  container's immediately following element sibling, and this root takes
  its `:root-id` and its `identifierPrefix` from that manifest's content
  (Spec 011 §Root Manifest v1) — so identity opts are REFUSED here
  (`:rf.error/root-manifest-invalid`): a client that picks its own
  `identifierPrefix` breaks `use-id` hydration.

  A container carrying nothing to adopt takes the FALLBACK first, before
  any manifest is asked for: the root mounts client-side under a DERIVED
  identity, and [[hydrated?]] answers false. A container that DOES carry
  server markup must carry the manifest that says what the server rendered
  it as — nothing adjacent is `:rf.error/root-manifest-invalid`
  `{:missing :manifest}`, and no SSR artefact at all is
  `:rf.error/ssr-artefact-missing`.

  A divergence React recovers from — a text mismatch, a missing, extra or
  wrong-type element — is reported as `:rf.ssr/hydration-mismatch` and
  React replaces the offending DOM. An ATTRIBUTE-only divergence is
  outside that signal by React's own contract."
  ([dom-node root-form] (hydrate-root dom-node root-form {}))
  ([dom-node root-form opts]
   ;; Identity opts FIRST. They are outside the closed key set too, so the
   ;; generic unknown-key refusal would otherwise answer the specific
   ;; question — and "unknown opt :root-id" is a much worse sentence than
   ;; "identity comes from the server" for a reader who supplied it on
   ;; purpose.
   (check-identity-opts! opts)
   (check-opt-keys! 'v/hydrate-root opts hydrate-opt-keys)
   ;; The container, before anything reads through it. At a hydrating root
   ;; the root-id is unknown until the manifest is read, so a nil container
   ;; reported any later would masquerade as "no manifest here".
   (require-container! 'v/hydrate-root nil dom-node)
   (if-not (server-rendered? dom-node)
     (hydrate-fallback! dom-node root-form opts)
     (if-let [manifest (discover-manifest! dom-node)]
       (adopt! dom-node root-form opts manifest)
       (error/throw-error!
         :rf.error/root-manifest-invalid 'v/hydrate-root
         (str "this container carries server-rendered markup but no Root "
              "Manifest follows it. A hydrating root takes its root-id and its "
              "identifierPrefix from the manifest the server emits as the "
              "container's immediately following element sibling, and nothing "
              "was found there — so there is no identity to hydrate AS. Emit "
              "the manifest with the render, or mount client-side with v/mount.")
         {:recovery :emit-the-root-manifest-or-use-v-mount
          :extra    {:missing :manifest}})))))

;; ---------------------------------------------------------------------------
;; unmount!
;; ---------------------------------------------------------------------------

(defn unmount!
  "Tear `root` down completely, and answer nil.

  Releases the registry entry — and with it the root-id, container and
  identifierPrefix claims — unmounts the React root, and releases this
  root's reference to its frame, destroying an OWNED frame once no live
  root still references it. React's unmount runs the shell's layout
  cleanups, so every ViewCell below disconnects: every dependency
  released, every published callback retired.

  GUARDED, and deliberately a no-op rather than a throw when the guard
  fails: a root that was already unmounted, or superseded by a newer root
  claiming its id, has nothing left to release, and tearing down on its
  behalf would tear down the SUCCESSOR."
  [^Root root]
  (when (root? root)
    (let [root-id (:root-id (.-descriptor root))]
      (when (identical? root (get @live-roots root-id))
        (swap! live-roots dissoc root-id)
        (release-frame! (.-frame-id root) root-id)
        (.unmount (.-react-root root)))))
  nil)
