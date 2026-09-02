(ns re-frame.api-manifest.gen
  "Public-API manifest generator and drift check.

  THE PROBLEM. re-frame2 has one truth — the public API — with many
  projections (spec/API.md, the per-tool specs, the docs, the MCP tool
  descriptors, the skills). Projections drift. A one-time reconciliation
  fixes today's drift; a generated, CI-guarded MANIFEST prevents
  tomorrow's.

  THE ARTEFACT. `spec/api-manifest.edn` is the machine-readable public-API
  manifest — one row per public var:

      {:namespace ..., :var ..., :tier ..., :kind ..., :owner ...,
       :status ..., :facade? ..., :runtime-verified? ...}

  SINGLE-SOURCE DESIGN — code + curated sidecar.
    - EXISTENCE + :kind + :facade? are DERIVED from live vars (the
      introspectable truth). For every JVM-loadable public namespace
      this generator calls `ns-publics`, drops the documented `^:no-doc`
      internal carve-outs, and derives `:kind` (`:macro` / `:fn` /
      `:var`) and `:facade?` (does the var live in the user-facing
      `re-frame.core` façade, or in its home artefact ns?) from var
      metadata.
    - :tier / :owner / :status are CURATED in the sidecar
      `spec/api-manifest-metadata.edn`, keyed by `[namespace var]`,
      because they are human-classification axes that cannot be derived
      from code (the Tier closed vocabulary lives in spec/API.md §Tier
      taxonomy; the owning Spec is editorial).
    - The manifest is their JOIN. Code owns *what exists*; the sidecar
      owns *how it is classified*; neither fact has two homes.

  DRIFT-CHECK. `--check` regenerates the manifest in memory and compares
  it to the committed `spec/api-manifest.edn`. Any difference — a public
  var added, removed, or renamed in code; a var missing a sidecar entry;
  a stale sidecar entry for a var that no longer exists — fails the
  check. This is the PRIMARY drift-guard: it goes red in CI until the
  manifest + sidecar are updated.

  CLJS-ONLY SURFACES. The Reagent / UIx adapter namespaces, the
  Xray `mount-*!` family, and the pair-MCP server are ClojureScript-only
  and cannot be `require`d on the JVM. Their rows live in the sidecar
  under `:cljs-only` and are carried through verbatim. The JVM
  existence-check does not reach them; instead a CLJS-side enumeration
  probe (`implementation/scripts/api-manifest/probe/`, run by
  `npm run test:cljs`) reconciles each covered namespace's live public
  vars against its rows. A `:cljs-only` row carries its own
  `:runtime-verified?` flag — `true` once the probe covers its
  namespace, `false` otherwise — and the generator emits that flag
  verbatim (it does not itself run the probe)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.pprint :as pprint]))

;; ---------------------------------------------------------------------------
;; Locating the repo root + artefact paths.
;;
;; The generator runs from implementation/scripts/api-manifest/; the repo
;; root is four levels up. We resolve it relative to *this* file's
;; classpath entry so the generator works from any CWD on any platform.
;; ---------------------------------------------------------------------------

(def ^:private here
  "implementation/scripts/api-manifest — the generator's own directory,
  derived from the `user.dir` the clojure CLI sets to the deps.edn dir."
  (io/file (System/getProperty "user.dir")))

(def repo-root
  "Repo root = three dirs above implementation/scripts/api-manifest/
   (api-manifest → scripts → implementation → <repo-root>)."
  (-> here .getParentFile .getParentFile .getParentFile))

(defn- spec-file [name]
  (io/file repo-root "spec" name))

(def manifest-file (delay (spec-file "api-manifest.edn")))
(def sidecar-file  (delay (spec-file "api-manifest-metadata.edn")))

;; ---------------------------------------------------------------------------
;; JVM-loadable public namespaces.
;;
;; Every namespace here is `.cljc` (or `.clj`) and loads on the JVM, so
;; `ns-publics` returns its live public vars. The order is editorial and
;; does not affect output (rows are sorted before emission).
;; ---------------------------------------------------------------------------

(def jvm-namespaces
  "Public namespaces this generator introspects on the JVM. Each maps to
  the same façade?/home decision the sidecar's `:owner` records — but the
  generator derives existence + kind from the live vars here, not the
  sidecar."
  '[;; Core façade + the two sibling test namespaces.
    re-frame.core
    re-frame.test-support
    re-frame.test-helpers
    re-frame.schemas
    re-frame.machines
    re-frame.routing
    re-frame.resources
    re-frame.flows
    re-frame.http
    re-frame.ssr
    re-frame.ssr.ring
    ;; The two JVM-loadable namespaces of the ssr-node crossing (rf2-8arzr.7).
    ;; Both are requires-directly host-adapter surfaces that nothing
    ;; re-exports: `re-frame.ssr.ring.node` provides `renderer`, the one
    ;; non-local `:renderer` the reference ships (the JVM→Node adapter over
    ;; the bounded sidecar at implementation/ssr-node), and
    ;; `re-frame.ssr.render-state` is the render-visible projection that seam
    ;; runs. spec/API.md §Namespaces places them at the `:implementation`
    ;; tier "on the same footing as `re-frame.ssr.ring`'s own vars", so they
    ;; are rowed here and NOT rowed as var-rows in spec/API.md. They shipped
    ;; unenrolled — public vars, no `^:no-doc`, zero manifest rows — which is
    ;; the hole the roster-completeness gate below now closes.
    re-frame.ssr.render-state
    re-frame.ssr.ring.node
    re-frame.epoch
    ;; The Hicasso view substrate's public door. A `.cljc` whose `:clj` arm
    ;; is the three authoring macros (`defview` / `event` / `defhost`) and
    ;; whose `:cljs` arm is the runtime aliases — a SPLIT-HOST public
    ;; namespace, so each host inventories the arm it can see: `ns-publics`
    ;; here returns the macros, and the CLJS probe reconciles the aliases
    ;; against these same `:classification` rows (rf2-phm7g).
    re-frame.hicasso
    ;; Tool artefacts with JVM-loadable public surfaces.
    re-frame.story
    ;; MCP support namespaces — the tooling trace/egress surfaces the
    ;; pair-MCP servers consume (per spec/API.md §Tiering of cross-tool
    ;; surfaces). Only the public-var-bearing ones are introspected.
    re-frame.mcp-base.elision
    re-frame.mcp-base.sensitive])

(def extra-vars
  "Individually-named public vars whose HOME namespace is mostly internal
   (so we do NOT enumerate the whole namespace) but which spec/API.md rows
   as a documented public surface. Each is JVM-introspected and
   runtime-verified individually. Shape: `[ns-sym var-sym]`."
  '[;; The two dev-gate Vars rowed in spec/API.md §Tracing. Their home
    ;; namespaces (re-frame.interop / re-frame.performance) are otherwise
    ;; internal plumbing.
    [re-frame.interop     debug-enabled?]
    [re-frame.performance enabled?]])

(defn- source-file->ns-sym
  "Namespace symbol for a Clojure source file at `rel-path` (a `/`-joined
   path relative to a source root, extension included). Reverses the standard
   munge: strip the extension, `/` → `.`, `_` → `-`. Returns nil for a
   non-source file."
  [rel-path]
  (when-let [[_ base] (re-matches #"(.+)\.clj[cs]?$" rel-path)]
    (symbol (-> base (str/replace "/" ".") (str/replace "_" "-")))))

(defn namespaces-under
  "The sorted set of namespace symbols for every `.clj` / `.cljc` / `.cljs`
   source file beneath `root`. Throws when `root` does not exist — a
   completeness gate that quietly finds nothing is the defect it exists to
   prevent, so an unreadable tree must be loud rather than green."
  [^java.io.File root]
  (when-not (.isDirectory root)
    (throw (ex-info (str "Source root not found: " (.getPath root)
                         " — the roster-completeness gate cannot run, and must "
                         "not pass by default. Run the generator from "
                         "implementation/scripts/api-manifest/.")
                    {:root (.getPath root)})))
  (let [root-path (.getPath root)
        prefix    (count (str root-path java.io.File/separator))]
    (into (sorted-set)
          (comp (filter #(.isFile ^java.io.File %))
                (map (fn [^java.io.File f]
                       (-> (.getPath f)
                           (subs prefix)
                           (str/replace "\\" "/"))))
                (keep source-file->ns-sym))
          (file-seq root))))

;; ---------------------------------------------------------------------------
;; Roster completeness (rf2-8arzr.7 — restoring the mechanism of rf2-o8xev).
;;
;; THE HOLE. `jvm-namespaces` above is an EXPLICIT roster, and `doc_api_check`
;; derives ITS namespace roster from the rows that roster produces. So a
;; namespace absent from the roster is not UNCLASSIFIED — it is UNSCANNED:
;; `--check` stays green, no documentation-coverage check reaches it, and
;; every public var in it is invisible to every manifest-derived gate at once.
;; A completeness check keyed on the roster cannot see what the roster omits.
;;
;; WHY THIS IS BEING WRITTEN A SECOND TIME. It is not a new idea. rf2-o8xev
;; built exactly this reconciliation for `implementation/freehand/src`, with
;; a public roster, an internal roster and an assertion called FIRST in
;; `build-manifest`. Freehand's retirement (rf2-0yp7w.6, commit c951808b47)
;; deleted the tree and — correctly, since the gate refuses to build when a
;; source namespace is named by neither roster — retired the rosters, the
;; assertion and its call site with it. What it left behind was
;; `namespaces-under` and `source-file->ns-sym`: the two pure helpers, with no
;; caller. The gate was not forgotten, it was ORPHANED, and the orphan reads
;; exactly like a live backstop to anyone grepping for one. Three namespaces
;; then shipped unscanned through the gap (`re-frame.ssr.ring.node`,
;; `re-frame.ssr.render-state`, `re-frame.hicasso.server`).
;;
;; THE GATE. It infers NOTHING about publicness: no namespace is
;; auto-enrolled, no var is auto-published, and `^:no-doc` carve-outs behave
;; exactly as before. It asserts only that every source namespace under a
;; covered root has been ACCOUNTED FOR — named either in `jvm-namespaces`
;; above (a supported surface, introspected and rowed), in the sidecar's
;; `:cljs-only` rows (a surface the JVM cannot require), or in
;; `internal-namespaces` below (plumbing nobody authors against). A newly
;; shipped namespace is in none of the three, so it fails BY NAME with the
;; ways to answer for it. Classification stays a human decision; only the
;; OBLIGATION to make one is automated.
;;
;; WHY THESE ROOTS AND NOT EVERY TREE. Deliberately narrow, and the narrowness
;; is the honest part of this fix rather than a shortcut. The SSR trees are
;; where the class just recurred, and enrolling a tree is not free: every
;; namespace in it must be classified by a human, once, and recorded below.
;; `implementation/hicasso/src` joined them under rf2-3ne8, and what it cost is
;; worth recording, because it is the argument for the gate rather than against
;; it. Twenty-four of its twenty-six namespaces were unaccounted, and the
;; assumption — this bead's own, and the dispatch's — was that they were mostly
;; internal. FIVE WERE PUBLIC AUTHORING SURFACES: `.forms`, `.motion`,
;; `.overlay`, `.native` and `.substrate`, each carrying a require-me-directly
;; example in its own ns docstring, and `.substrate` supplying the very adapter
;; `(rf/init! …)` takes. They had shipped with no manifest row and no
;; documentation page, and nothing but this gate would have said so. They were
;; tiered honestly and given pages rather than quieted with `^:no-doc`; the two
;; tool-tier namespaces (`.tool`, `.evidence`) were rowed `:tooling`, which
;; obliges no page; the seventeen `re-frame.hicasso.impl.*` are below.
;; Widening to the remaining artefacts is a per-tree decision with a per-tree
;; cost; the point of the data-driven shape below is that each is a root plus
;; its classifications, never another mechanism.
;;
;; ROUTING AND RESOURCES joined under rf2-hjj4, executing step 2 of the
;; rf2-qvhx ruling, and their result is worth recording because it points the
;; OTHER WAY from hicasso's. They were chosen on blast radius — the two trees
;; judged most likely to hide a public authoring surface an application
;; requires directly. Fifty-one namespaces were unaccounted (routing 28,
;; resources 23) and ALL FIFTY-ONE classified internal: no new manifest row, no
;; new documentation page, `:var-count` unchanged. The reason the two trees
;; differ from hicasso is structural rather than lucky. Both are FAÇADE
;; artefacts — one enrolled door re-exporting what an app may call (27 rows for
;; routing, 21 for resources), with the siblings holding handler bodies,
;; `*-meta` registration maps, cofx constructors, `*-sub-fn` bodies and
;; host-side caches. Hicasso is the opposite shape: its optional modules are
;; separately requirable BECAUSE they are opt-in, so its door could not
;; re-export them. That distinction is the useful predictor for the remaining
;; trees, and it is what rf2-hjj4's verdict rests on.
;; ---------------------------------------------------------------------------

(def roster-covered-roots
  "Repo-relative source trees whose every namespace must be accounted for by
   one of the three rosters. Adding a tree here is deliberate work, not a
   free generalisation: it obliges a classification for every namespace
   beneath it (see this section's header)."
  ["implementation/ssr/src"
   "implementation/ssr-ring/src"
   "implementation/hicasso/src"
   "implementation/routing/src"
   "implementation/resources/src"])

(def internal-namespaces
  "Source namespaces under `roster-covered-roots` that are deliberately NOT a
  supported surface: SSR pipeline internals, host-adapter plumbing and
  emitters, the Hicasso runtime beneath its door, and the per-concern siblings
  the routing and resources façades compose. Nothing here is published,
  documented or rowed in the manifest — being on this list is the RECORD of
  that decision, not a consequence of it.

  This is not a synonym for `^:no-doc`. NONE of these carry that metadata
  (the SSR trees use it nowhere at all), so the marker cannot be the
  classifier — which is precisely why the roster is written down instead of
  derived. The public doors of these five artefacts are `re-frame.ssr`,
  `re-frame.ssr.ring`, `re-frame.hicasso`, `re-frame.routing` and
  `re-frame.resources`, plus the two crossing surfaces rf2-8arzr.7 enrolled and
  the Hicasso modules rf2-3ne8 enrolled; everything below is reached only from
  inside them.

  READ THE GROUPING COMMENTS AS THE CLASSIFICATION. Each names what the group
  is and the checkable reason it is not a surface — most often the artefact's
  own docstring, a spec section, or the fact that the door already rows the
  var. A bare name here with no reason above it would be a rubber stamp, which
  is worse than an unenrolled tree because it looks like a decision."
  '#{;; --- implementation/ssr/src -------------------------------------------
     ;; Boot, install and the shared constant/hash/manifest plumbing.
     re-frame.ssr.boot
     re-frame.ssr.constants
     re-frame.ssr.hash
     re-frame.ssr.install
     re-frame.ssr.manifest
     re-frame.ssr.substrate
     ;; The render pipeline: request/response shaping, emission, the UI tree.
     re-frame.ssr.emit
     re-frame.ssr.html-helpers
     re-frame.ssr.http-validation
     re-frame.ssr.request
     re-frame.ssr.response
     re-frame.ssr.ui-tree
     ;; `<head>` collection and emission.
     re-frame.ssr.head
     re-frame.ssr.head.emit
     re-frame.ssr.head.registry
     ;; Error capture and projection.
     re-frame.ssr.error-listener
     re-frame.ssr.error-projector
     ;; Hydration, egress, payload policy and the server-fx schemas.
     re-frame.ssr.egress
     re-frame.ssr.hydrate
     re-frame.ssr.payload-policy
     re-frame.ssr.server-fx-schemas
     re-frame.ssr.suspense
     ;; Streaming internals (the client half is the browser-side reader).
     re-frame.ssr.streaming
     re-frame.ssr.streaming.client
     re-frame.ssr.streaming.constants
     ;; --- implementation/ssr-ring/src --------------------------------------
     ;; Ring host-adapter plumbing beneath `re-frame.ssr.ring`'s own door.
     re-frame.ssr.ring.cookie
     re-frame.ssr.ring.headers
     re-frame.ssr.ring.lifecycle
     re-frame.ssr.ring.payload
     re-frame.ssr.ring.pipeline
     re-frame.ssr.ring.shell
     re-frame.ssr.ring.streaming
     re-frame.ssr.ring.trust
     ;; --- implementation/hicasso/src ---------------------------------------
     ;; Everything under `re-frame.hicasso.impl.*` and nothing else: the door
     ;; (`re-frame.hicasso`), its five optional authoring modules, its SSR
     ;; module and its two tool-tier namespaces are all ENROLLED instead — nine
     ;; namespaces of the tree's twenty-six, leaving these seventeen. The `impl`
     ;; segment is the artefact's own published boundary, stated in
     ;; `docs/api/re-frame.hicasso.md` and in spec/Conventions.md's artefact
     ;; row: "everything else is `re-frame.hicasso.impl.*` and is not a
     ;; consumer surface". So the roster and the naming agree here, which the
     ;; SSR trees above could not manage — but the roster is still written out
     ;; rather than derived from the segment, because a prefix rule would
     ;; silently absorb a future non-`impl` namespace that nobody classified,
     ;; which is the exact failure this gate exists to prevent.
     ;; The render pipeline: hiccup in, React elements out, and the commit
     ;; fence that keeps one render pass on one commit.
     re-frame.hicasso.impl.codec
     re-frame.hicasso.impl.collector
     re-frame.hicasso.impl.generation
     re-frame.hicasso.impl.slot
     ;; Props and callbacks: intent lowering, controlled-element converge.
     re-frame.hicasso.impl.controlled
     re-frame.hicasso.impl.intent
     ;; Roots, mounting and the hydration adoption window.
     re-frame.hicasso.impl.mount
     re-frame.hicasso.impl.roots
     ;; Frame-locked ops and instance-key local state.
     re-frame.hicasso.impl.frames
     re-frame.hicasso.impl.state
     ;; Refusals: the one constructor, the dev-only ledger, and the runtime's
     ;; own error boundary component.
     re-frame.hicasso.impl.boundary
     re-frame.hicasso.impl.error
     ;; The impure halves of the optional modules, whose doors are the
     ;; enrolled `re-frame.hicasso.overlay` / `.motion` namespaces, plus the
     ;; portal and route-link markup shapes the door re-exports.
     re-frame.hicasso.impl.overlay
     re-frame.hicasso.impl.portal
     re-frame.hicasso.impl.presence
     re-frame.hicasso.impl.presence-react
     re-frame.hicasso.impl.route-link
     ;; --- implementation/routing/src ---------------------------------------
     ;; The door is `re-frame.routing`, enrolled above, and it says so in its
     ;; own docstring: "This namespace is the public boot point and facade for
     ;; the routing artefact: apps load it with `(:require [re-frame.routing])`
     ;; … The implementation lives in per-concern namespaces". TWENTY-FIVE of
     ;; the twenty-eight below repeat that verbatim in their own docstrings —
     ;; "Internal namespace; the public facade is `re-frame.routing`" — so this
     ;; roster records the artefact's OWN published boundary rather than a
     ;; judgement invented here. Exactly three lack that sentence —
     ;; `readiness`, `tooling` and `test-support` — and each is given its own
     ;; reason at its group below.
     ;;
     ;; What the façade re-exports is ALREADY rowed under `re-frame.routing`
     ;; (27 rows spanning :advanced / :tooling / :implementation). Every
     ;; remaining public below is an intra-artefact seam the façade composes:
     ;; `*-handler` fns, their `*-meta` registration maps, `*-cofx`
     ;; constructors, host-side cache accessors (`reset-cache!`,
     ;; `release-frame!`) and pure projectors. None is an authoring surface,
     ;; and none is named by spec/API.md, docs/ or skills/ as one.
     ;;
     ;; URL, pattern and address primitives — pure parsing/encoding beneath
     ;; `match-url` / `route-url`, which the façade rows at :advanced.
     re-frame.routing.address
     re-frame.routing.match
     re-frame.routing.resolve
     re-frame.routing.url
     ;; Registration and the projection-relative data classification lowered
     ;; at route registration. `reg-route` / `route-ids` / `route-meta` are the
     ;; façade's rows; these are the validators and the route-table cache.
     re-frame.routing.classification
     re-frame.routing.registry
     ;; Navigation planning and commit: the pre-commit planning seam, the
     ;; leave/entry decisions, the commit-time readiness projector, the shared
     ;; nav-event helpers, and the two commands that reuse the standing plan.
     ;; `readiness` carries no "internal namespace" sentence, but its only
     ;; public (`project-at-commit`) takes the `:routing/on-route-entry` hook
     ;; result and is called by the commit assembler — the façade lists it as a
     ;; per-concern namespace, and Spec 012 §Route readiness names the
     ;; projection, never a var.
     re-frame.routing.decisions
     re-frame.routing.events
     re-frame.routing.plan
     re-frame.routing.prefetch
     re-frame.routing.readiness
     re-frame.routing.replan
     ;; Nav tokens, the monotonic host-side allocators, and the `:rf.nav/*` fx
     ;; legs with their Malli args schemas. The consumer surface here is the
     ;; event/fx/cofx vocabulary (`:rf.route/with-nav-token`, `:rf.nav/push-url`),
     ;; registered by the façade; these namespaces supply the handler bodies.
     re-frame.routing.navigate
     re-frame.routing.nav-counters
     re-frame.routing.nav-fx
     re-frame.routing.nav-fx-schemas
     re-frame.routing.nav-token
     re-frame.routing.url-bound
     re-frame.routing.url-change
     ;; History strategy and scroll restoration. The two SHIPPED strategy maps
     ;; and `with-base-path` are façade rows at :advanced and documented on
     ;; docs/api/re-frame.routing.md; a CUSTOM strategy is a five-key map the
     ;; app writes itself (Spec 012 §URL strategies), so the encode/decode legs
     ;; and `url-strategy-from-config` are those maps' internals, not a
     ;; compose-your-own kit — spec/009-Instrumentation.md's only mention of
     ;; the latter calls it a dev-only tripwire.
     re-frame.routing.history
     re-frame.routing.scroll
     re-frame.routing.strategy
     ;; Reads: the framework-shipped subs, their off-box egress projection, and
     ;; the `:route/link` registered view. The consumer names are the sub ids
     ;; and the `:route/link` view id, both registered by the façade.
     re-frame.routing.link
     re-frame.routing.sub-egress
     re-frame.routing.subs
     ;; Async lowering onto the shared reply envelope. Its own docstring is
     ;; explicit that this is "internal lowering only" and that "the PUBLIC
     ;; routing API … is unchanged".
     re-frame.routing.reply
     ;; The bundle-isolated tooling sibling. Its ENTIRE public surface — both
     ;; algebra views — is already rowed at :tooling under `re-frame.routing`
     ;; via the JVM façade aliases, and documented on
     ;; docs/api/re-frame.routing.md, which names the CLJS call form too.
     ;; Enrolling the namespace as well would row the same two fns twice.
     ;; spec/Derivations.md §Routes expose algebra views is the authority for
     ;; the disposition: this exposure "is *internal registration metadata* …
     ;; it ships **no public accessor**", with the public name deferred to a
     ;; stated graduation gate.
     re-frame.routing.tooling
     ;; The test-only fixture namespace, and the ONE name in these two groups
     ;; that documented guidance tells an author to type:
     ;; skills/re-frame2/references/tooling/routing.md says of the
     ;; `:rf.test/simulate-http-resolution` fixture event that it "is test-only,
     ;; so `(:require [re-frame.routing.test-support])` to register it (it is
     ;; NOT wired into the production `re-frame.routing` façade)". That require
     ;; is wanted for its LOAD EFFECT — it registers the event — and nobody
     ;; calls the one public var (`simulate-http-resolution-handler`, the
     ;; handler body). One row per public VAR therefore leaves nothing here to
     ;; row, which is why the tier is internal; but the NAMESPACE NAME is
     ;; load-bearing all the same, because a name authors are instructed to
     ;; require is part of the contract even when none of its vars are. Rename
     ;; it and that skill page breaks. The resources sibling below is NOT this
     ;; case — see its note.
     re-frame.routing.test-support
     ;; --- implementation/resources/src -------------------------------------
     ;; The door is `re-frame.resources`, enrolled above, whose docstring calls
     ;; it "the **public boot point and façade** for the resources artefact:
     ;; apps boot it with `(:require [re-frame.resources])`. Doing so
     ;; transitively loads every concern sibling under `re-frame.resources.*`".
     ;; Unlike routing, the siblings here carry NO per-file "internal
     ;; namespace" sentence, so each group below states its own reason.
     ;;
     ;; The consumer surface is `reg-resource` / `reg-mutation` /
     ;; `reg-resource-scope`, the `:rf.resource/*` + `:rf.mutation/*` event and
     ;; sub vocabularies, and the registry introspection accessors — 21 rows
     ;; already carried under `re-frame.resources`. Every public below is a
     ;; runtime seam: durable-shape constructors and path fns, `*-handler` /
     ;; `*-meta` pairs, `*-sub-fn` bodies, host-side cache accessors and pure
     ;; projectors. Spec 016 names the events, subs and registration fns; it
     ;; names none of these vars.
     ;;
     ;; Runtime-db paths and the durable entry / instance shapes every sibling
     ;; agrees on. `state`'s own docstring: "the paths and shapes are pinned
     ;; here so every sibling agrees on one home".
     re-frame.resources.mutation-runtime
     re-frame.resources.state
     ;; Registration: the three registrar kinds, the shared params
     ;; validate+canonicalize pipeline, and the durable classification lowered
     ;; at registration. The `reg-*` / `clear-*` / `*-ids` / `*-meta` surfaces
     ;; are the façade's rows; these hold the validators, the registrar-kind
     ;; keywords and the scope resolvers behind them.
     re-frame.resources.classification
     re-frame.resources.mutation-registry
     re-frame.resources.params
     re-frame.resources.registry
     re-frame.resources.scope-registry
     ;; The causal write surface: the `:rf.resource/*` and `:rf.mutation/*`
     ;; event handlers plus the framework-internal `*.internal/*` replies. Spec
     ;; 016 §Public API §Events documents the EVENT IDS; these namespaces are
     ;; the handler bodies the façade registers.
     re-frame.resources.events
     re-frame.resources.mutation-events
     ;; The passive read surface: the `:rf/resource` / `:rf/mutation` sub
     ;; families. Again the consumer names are the sub ids; the publics here
     ;; are `*-sub-fn` bodies and their registration entry points.
     re-frame.resources.mutation-subs
     re-frame.resources.subs
     ;; Async lowering and the frame work ledger: the uniform reply envelope,
     ;; the shared stale-suppression substrate above it, the ledger records and
     ;; host-side handles, and the transport seam. The artefact ships ONE
     ;; built-in transport (`:rf.http/managed`); transport-neutrality is a
     ;; design property of the core, not a published extension API — no doc,
     ;; spec, skill or example names `managed-http-transport`,
     ;; `default-transport` or `assert-managed-transport!`.
     re-frame.resources.reply
     re-frame.resources.reply-handlers
     re-frame.resources.transport
     re-frame.resources.transport.http
     re-frame.resources.work-ledger
     ;; Host-side scheduling: the stale / GC / poll timer side-table and the
     ;; window-focus / network-reconnect listeners. Both are installed and
     ;; torn down by the façade and by frame lifecycle; the app-facing controls
     ;; are the policy keys on a resource spec and the two
     ;; `install-` / `remove-revalidation-listeners!` façade rows.
     re-frame.resources.revalidate-listeners
     re-frame.resources.timers
     ;; The two LATE-BOUND cross-artefact integrations. Resources never
     ;; statically requires routing or ssr; these publish the hooks those
     ;; artefacts resolve. `install-routing-integration!` /
     ;; `install-ssr-integration!` are called by the façade at load (and again
     ;; by tests after a registrar reset, the same `:reload` re-wiring the
     ;; façade docstring describes) — never by application code.
     re-frame.resources.route
     re-frame.resources.ssr
     ;; The OFF-BOX trace-row egress projector. Production-reachable rather
     ;; than tool-only (the façade requires it on both runtimes so the
     ;; `:resources/project-resource-trace-egress` hook is always published),
     ;; but consumed only through that hook by the epoch tool pair.
     re-frame.resources.trace-egress
     ;; The bundle-isolated tooling sibling — same disposition as routing's,
     ;; and the same authority: spec/Derivations.md §Resources expose process
     ;; nodes says this exposure "is *internal registration metadata* … it
     ;; ships **no public accessor**", the public name deferred to the
     ;; graduation gate. Its two algebra views are already rowed at :tooling
     ;; under `re-frame.resources` via the JVM façade aliases; its five other
     ;; publics (`resource-superkind`, `resource-refined-kind`,
     ;; `resource-storage`, `resource-lifecycle`, `resource-evaluation`) are
     ;; module-level classification CONSTANTS read only by the two view
     ;; builders in that same file — public by omission beside the `^:private`
     ;; `resource-selectors` directly below them, not a surface.
     re-frame.resources.tooling
     ;; The test-only fixture namespace, the sibling of
     ;; `re-frame.routing.test-support` above (its docstring names the family:
     ;; routing, resources and http). Required directly by test suites, again
     ;; for the LOAD EFFECT: the require publishes the
     ;; `:resources/reset-resources!` late-bind hook, and the consumer-facing
     ;; name is `re-frame.test-support/make-reset-runtime-fixture` — already
     ;; rowed at :testing with a docs/api page — which FIRES the one public var
     ;; here. No consumer calls `reset-resources!` itself. And unlike routing's,
     ;; THIS name is not itself in the contract: no page under skills/, docs/,
     ;; spec/ or migration/ instructs the require, so authors reach the reset
     ;; only through that rowed fixture. The two reasons differ on purpose.
     re-frame.resources.test-support})

(defn- repo-file
  "Resolve a `/`-joined repo-relative path against `repo-root`, portably."
  [rel-path]
  (apply io/file repo-root (str/split rel-path #"/")))

(defn covered-source-namespaces
  "The sorted set of every namespace under every root in
   `roster-covered-roots`. Throws (via `namespaces-under`) when a root has
   gone missing, rather than quietly reconciling an empty tree."
  []
  (into (sorted-set)
        (mapcat #(namespaces-under (repo-file %)))
        roster-covered-roots))

(defn- cljs-only-namespaces
  "Namespaces the sidecar carries `:cljs-only` rows for — accounted for, but
   by the curated roster rather than by JVM introspection."
  [sidecar]
  (into #{} (map (comp symbol :namespace)) (:cljs-only sidecar)))

(defn roster-drift
  "Reconcile the three rosters against the live source tree. Returns
   `{:unaccounted [...] :stale [...] :contradictory [...]}`:

     :unaccounted   — a source namespace named by NO roster. The new surface
                      that would otherwise ship unscanned.
     :stale         — an INTERNAL roster entry with no source file left. The
                      roster rots the same way the sidecar does, and is
                      reconciled the same way.
     :contradictory — a namespace claimed as BOTH a public surface and
                      internal, which is not a classification but a
                      contradiction.

   `present` is the live set from `covered-source-namespaces`; passing it in
   keeps the reconciliation pure and lets a test drive a synthetic tree
   through it."
  [present sidecar]
  (let [present  (set present)
        ;; Only the enrolled namespaces SOURCED FROM a covered root count as
        ;; accounting for that root — `jvm-namespaces` spans every artefact,
        ;; and the two SSR trees deliberately share one `re-frame.ssr` prefix
        ;; (`re-frame.ssr.ring` lives in ssr-ring, `re-frame.ssr.render-state`
        ;; in ssr), so a prefix filter would mis-assign both. Intersecting
        ;; with what is actually on disk is exact and needs no per-tree rule.
        enrolled (set/intersection (set jvm-namespaces) present)
        public   (set/union enrolled
                            (set/intersection (cljs-only-namespaces sidecar)
                                              present))]
    {:unaccounted   (vec (sort (set/difference present
                                               (set/union public
                                                          internal-namespaces))))
     :stale         (vec (sort (set/difference internal-namespaces present)))
     :contradictory (vec (sort (set/intersection public internal-namespaces)))}))

(defn assert-roster-complete!
  "Throw with an actionable message when `present` reveals roster drift.
   Returns `present` unchanged when the rosters account for the tree exactly."
  [present sidecar]
  (let [{:keys [unaccounted stale contradictory]} (roster-drift present sidecar)]
    (when (seq unaccounted)
      (throw (ex-info
              (str "Unaccounted public-API source namespace(s) — every "
                   "namespace under a root in `roster-covered-roots` must be "
                   "classified, or it ships UNSCANNED (no manifest rows, no "
                   "documentation-coverage check, and this gate green). "
                   "Classify each one of:\n  "
                   (str/join "\n  " unaccounted)
                   "\n\nEither (a) SUPPORTED SURFACE — add it to "
                   "`jvm-namespaces` in this generator (or, if it is "
                   "CLJS-only, add its rows under `:cljs-only` in "
                   "spec/api-manifest-metadata.edn), and classify each public "
                   "var there with :tier/:owner/:status; or (b) INTERNAL — add "
                   "it to `internal-namespaces` in this generator, under the "
                   "grouping comment that says what it is.")
              {:unaccounted unaccounted})))
    (when (seq stale)
      (throw (ex-info
              (str "Stale `internal-namespaces` entries — these are recorded "
                   "as internal plumbing but no source file under "
                   "`roster-covered-roots` answers to them any more (remove "
                   "them from the roster in this generator):\n  "
                   (str/join "\n  " stale))
              {:stale stale})))
    (when (seq contradictory)
      (throw (ex-info
              (str "Contradictory classification — these namespaces are BOTH "
                   "enrolled as a public surface (`jvm-namespaces`, or a "
                   "sidecar `:cljs-only` row) AND recorded as internal in "
                   "`internal-namespaces`. A namespace is one or the other; "
                   "remove the wrong entry:\n  "
                   (str/join "\n  " contradictory))
              {:contradictory contradictory})))
    present))

;; ---------------------------------------------------------------------------
;; Derivation from live vars.
;; ---------------------------------------------------------------------------

(defn- kind-of
  "Derive the manifest `:kind` for a var from its metadata.
   `:macro` — a defmacro; `:fn` — a fn / has an arglist; `:var` — a plain
   value (e.g. an adapter map, a canonical-vocabulary set)."
  [v]
  (let [m (meta v)]
    (cond
      (:macro m)                 :macro
      (or (:arglists m) (fn? @v)) :fn
      :else                      :var)))

(defn- public-vars-of
  "Live `{var-symbol -> kind}` for a namespace, minus the documented
   `^:no-doc` internal carve-outs (per spec/API.md §Not-rowed internal
   carve-outs). Returns a sorted seq of `[var-sym kind]`."
  [ns-sym]
  (require ns-sym)
  (->> (ns-publics ns-sym)
       (remove (fn [[_ v]] (:no-doc (meta v))))
       (map (fn [[sym v]] [sym (kind-of v)]))
       (sort-by first)))

(defn- resolve-extra-var
  "Resolve an individually-named [ns-sym var-sym] from `extra-vars` to a
   live var + its kind, or throw if it no longer exists."
  [[ns-sym var-sym]]
  (require ns-sym)
  (if-let [v (ns-resolve ns-sym var-sym)]
    [var-sym (kind-of v)]
    (throw (ex-info (str "extra-vars names a var that no longer exists: "
                         ns-sym "/" var-sym
                         " — remove it from `extra-vars` in the generator.")
                    {:ns ns-sym :var var-sym}))))

(defn all-jvm-vars
  "Every JVM-introspected `[ns-sym var-sym kind]` triple — the full set of
   whole-namespace public vars PLUS the individually-named `extra-vars`.
   The single source the manifest build + stale-check both read."
  []
  (concat
   (for [ns-sym jvm-namespaces
         [var-sym kind] (public-vars-of ns-sym)]
     [ns-sym var-sym kind])
   (for [[ns-sym var-sym :as ev] extra-vars]
     (let [[_ kind] (resolve-extra-var ev)]
       [ns-sym var-sym kind]))))

;; ---------------------------------------------------------------------------
;; The sidecar — curated classification metadata.
;;
;; Shape:
;;   {:meta {...}
;;    :classification {[ns-str var-str] {:tier ... :owner ... :status ...}
;;                     ...}
;;    :cljs-only [{:namespace ... :var ... :kind ... :facade? ...
;;                 :tier ... :owner ... :status ...} ...]}
;; ---------------------------------------------------------------------------

(defn read-sidecar []
  (with-open [r (io/reader @sidecar-file)]
    (edn/read (java.io.PushbackReader. r))))

(defn- facade?
  "A var is part of the user-facing `re-frame.core` façade iff it is
   published from that namespace. Everything else lives in its home
   artefact namespace."
  [ns-sym]
  (= 're-frame.core ns-sym))

(defn- classification-for
  "Look up the curated {:tier :owner :status} for a `[ns var]` pair.
   Returns nil (→ a drift error) when the sidecar has no entry."
  [classification ns-sym var-sym]
  (get classification [(name ns-sym) (name var-sym)]))

;; ---------------------------------------------------------------------------
;; Building the manifest rows.
;; ---------------------------------------------------------------------------

(defn build-rows
  "Return `[rows missing]` where rows is the sorted vector of manifest
   maps and `missing` is the vector of `[ns var]` pairs the sidecar does
   not classify (a drift error). Includes both the JVM-introspected rows
   and the curated CLJS-only rows from the sidecar."
  [sidecar]
  (let [classification (:classification sidecar)
        jvm-rows-and-missing
        (for [[ns-sym var-sym kind] (all-jvm-vars)]
          (if-let [c (classification-for classification ns-sym var-sym)]
            {:row {:namespace         (name ns-sym)
                   :var               (name var-sym)
                   :tier              (:tier c)
                   :kind              kind
                   :owner             (:owner c)
                   :status            (:status c)
                   :facade?           (facade? ns-sym)
                   :runtime-verified? true}}
            {:missing [(name ns-sym) (name var-sym)]}))
        jvm-rows (keep :row jvm-rows-and-missing)
        missing  (keep :missing jvm-rows-and-missing)
        cljs-rows
        (for [r (:cljs-only sidecar)]
          {:namespace         (:namespace r)
           :var               (:var r)
           :tier              (:tier r)
           :kind              (:kind r)
           :owner             (:owner r)
           :status            (:status r)
           :facade?           (boolean (:facade? r))
           ;; Per-row flag (rf2-2mtte): a `:cljs-only` row is
           ;; `:runtime-verified? true` once the CLJS-side enumeration
           ;; probe (implementation/scripts/api-manifest/probe/, run by
           ;; `npm run test:cljs`) covers its namespace — the probe is the
           ;; CLJS equivalent of the JVM `ns-publics` existence-check.
           ;; Rows the probe does not (yet) cover stay `false`. The JVM
           ;; generator carries the curated flag through verbatim; it does
           ;; not itself run the CLJS probe.
           :runtime-verified? (boolean (:runtime-verified? r))})
        rows (->> (concat jvm-rows cljs-rows)
                  (sort-by (juxt :namespace :var))
                  vec)]
    [rows (vec missing)]))

(defn- stale-sidecar-entries
  "Curated classification keys whose `[ns var]` no longer resolves to a
   live public var — a stale entry that must be removed (drift)."
  [sidecar]
  (let [live (set (for [[ns-sym var-sym _] (all-jvm-vars)]
                    [(name ns-sym) (name var-sym)]))]
    (->> (:classification sidecar)
         (map key)
         (remove live)
         (sort-by (juxt first second))
         vec)))

(defn duplicate-rows
  "Return a sorted vector of `[[namespace var] count]` for every
   `[namespace var]` key carried by MORE THAN ONE manifest row (rf2-nlnd9y.2).

   The manifest contract is one row per public var (this ns docstring's THE
   ARTEFACT note). JVM-derived rows are unique by construction (a namespace's
   `ns-publics` map has unique var names, and `extra-vars` adds distinct
   pairs). But the curated `:cljs-only` sidecar rows are carried through
   VERBATIM and concatenated with the JVM rows, with no uniqueness check — so
   a duplicated `:cljs-only` entry, or a `:cljs-only` row colliding with a
   JVM-derived row, produced a manifest with two rows for one var (possibly
   with conflicting tier/kind/status/runtime metadata) and an inflated
   `:var-count`. Downstream projections then either silently overwrite one
   row (`index-by-ns+var` `assoc`) or tolerate multiple tiers — masking the
   contradiction. Detecting duplicates HERE, before write / `--check`, keeps
   the one-row-per-var invariant where it is generated."
  [rows]
  (->> rows
       (group-by (juxt :namespace :var))
       (keep (fn [[k group]] (when (> (count group) 1) [k (count group)])))
       (sort-by (comp (juxt first second) first))
       vec))

(defn implementation-facade-rows
  "Return a sorted vector of `[namespace var]` for every row that combines an
   implementation-only disposition (`:tier :implementation`) with
   `:facade? true` (rf2-93sxp).

   A facade export tiered `:implementation` is annotation, not removal: the
   var still resolves from `re-frame.core`, still projects into docs/api and
   still reaches SCI's `copy-ns`, while its row claims it is internal. Per
   Conventions §Removing or demoting a facade export the disposition must
   fire on the SURFACE — the var leaves the facade for its owning namespace,
   where the generator does not row it — so a row of this shape is a
   contradiction to refuse at generation, exactly as `duplicate-rows` refuses
   two rows for one var. `:internal-public` is deliberately NOT in scope: that
   tier is a supported host/tool embed seam, not an internal one."
  [rows]
  (->> rows
       (filter #(and (:facade? %) (= :implementation (:tier %))))
       (map (juxt :namespace :var))
       (sort-by (juxt first second))
       vec))

(defn build-manifest
  "Build the full manifest data structure (the value written to
   spec/api-manifest.edn). Throws on missing / stale sidecar entries with
   an actionable message — that throw is what turns the drift-check red."
  [sidecar]
  ;; ROSTER COMPLETENESS FIRST (rf2-8arzr.7). The reconciliations below all
  ;; read `jvm-namespaces`, so they can only report on namespaces the roster
  ;; already names — an unenrolled namespace is invisible to every one of
  ;; them. Asserting the rosters account for the source tree BEFORE any of
  ;; them run is what turns "a new public namespace shipped unscanned" from a
  ;; green run into a named failure.
  (assert-roster-complete! (covered-source-namespaces) sidecar)
  (let [[rows missing] (build-rows sidecar)
        stale          (stale-sidecar-entries sidecar)
        dups           (duplicate-rows rows)
        demoted        (implementation-facade-rows rows)]
    (when (seq missing)
      (throw (ex-info
              (str "Public vars with no sidecar classification (add a "
                   "`spec/api-manifest-metadata.edn` :classification entry "
                   "with :tier/:owner/:status for each):\n  "
                   (str/join "\n  " (map #(str/join "/" %) missing)))
              {:missing missing})))
    (when (seq stale)
      (throw (ex-info
              (str "Stale sidecar :classification entries — these "
                   "`[namespace var]` pairs no longer resolve to a live "
                   "public var (remove them from "
                   "spec/api-manifest-metadata.edn):\n  "
                   (str/join "\n  " (map #(str/join "/" %) stale)))
              {:stale stale})))
    ;; One-row-per-public-var invariant (rf2-nlnd9y.2). A duplicate
    ;; `[namespace var]` — within `:cljs-only`, or between a `:cljs-only`
    ;; row and a JVM-derived row — must FAIL generation / `--check`, never
    ;; ship two rows for one var (which inflates :var-count and lets
    ;; downstream projections silently overwrite or mask conflicting tiers).
    (when (seq dups)
      (throw (ex-info
              (str "Duplicate manifest rows — these `[namespace var]` keys "
                   "appear MORE THAN ONCE (the manifest is one row per public "
                   "var). A duplicate within `:cljs-only`, or a `:cljs-only` "
                   "row colliding with a JVM-derived row, must be removed from "
                   "spec/api-manifest-metadata.edn (a JVM-loadable var must "
                   "NOT also be hand-rowed under :cljs-only):\n  "
                   (str/join "\n  "
                             (map (fn [[[ns-str var-str] n]]
                                    (str ns-str "/" var-str " (" n " rows)"))
                                  dups)))
              {:duplicates dups})))
    ;; Facade-vs-disposition invariant (rf2-93sxp). A `:facade? true` row at
    ;; `:tier :implementation` says "internal" about a var that still exports
    ;; from `re-frame.core` — annotation, not removal. Refuse it here so the
    ;; disposition has to land on the surface (Conventions §Removing or
    ;; demoting a facade export — delete, don't demote).
    (when (seq demoted)
      (throw (ex-info
              (str "Implementation-only rows exported from the facade — these "
                   "`[namespace var]` pairs are `:tier :implementation` AND "
                   "`:facade? true`. A facade export cannot be internal by "
                   "annotation: move the var off `re-frame.core` into its owning "
                   "namespace (Conventions §Removing or demoting a facade export "
                   "— delete, don't demote), or tier it as the surface it is:\n  "
                   (str/join "\n  " (map #(str/join "/" %) demoted)))
              {:implementation-facade demoted})))
    {:meta {:doc        (str "GENERATED public-API manifest — do NOT hand-edit "
                             "the :vars list. Regenerate with: clojure -M -m "
                             "re-frame.api-manifest.gen (run from "
                             "implementation/scripts/api-manifest/). The "
                             "tier/owner/status axes are curated in "
                             "spec/api-manifest-metadata.edn; existence + kind "
                             "+ facade? are derived from live public vars. "
                             "See that generator ns for the design.")
            :keystone   "rf2-3nbl5.2"
            :var-count  (count rows)
            :tier-vocab [:front-porch :advanced :tooling :adapter
                         :testing :internal-public :implementation
                         :deprecated]}
     :vars rows}))

;; ---------------------------------------------------------------------------
;; EDN emission — deterministic, stable, diff-friendly.
;; ---------------------------------------------------------------------------

(def ^:private row-key-order
  [:namespace :var :tier :kind :owner :status :facade? :runtime-verified?])

(defn- ordered-row
  "An array-map preserving the canonical key order so the printed EDN is
   stable across runs (sorted-map would alphabetise; we want :namespace
   first)."
  [row]
  (apply array-map (mapcat (fn [k] [k (get row k)]) row-key-order)))

(defn render-edn
  "Render the manifest to a deterministic EDN string. One row per line
   keeps git diffs surgical — adding a var is a one-line diff.

   Line endings are normalised to bare `\\n` so the output is
   byte-identical on Windows and Linux — `pprint` emits the
   platform line-separator, which would otherwise make the committed
   (Windows) file mismatch a CI (Linux) regeneration and trip a spurious
   drift failure. The committed file is `.gitattributes`-pinned to LF for
   the same reason."
  [manifest]
  (let [{:keys [meta vars]} manifest
        raw (str ";; GENERATED by implementation/scripts/api-manifest — do NOT hand-edit.\n"
                 ";; Regenerate: clojure -M -m re-frame.api-manifest.gen (from\n"
                 ";; implementation/scripts/api-manifest/). Curated tier/owner/status\n"
                 ";; live in spec/api-manifest-metadata.edn. Keystone rf2-3nbl5.2.\n"
                 "{:meta\n "
                 (with-out-str (pprint/pprint meta))
                 " :vars\n ["
                 (str/join "\n  " (map (comp pr-str ordered-row) vars))
                 "]}\n")]
    (str/replace raw "\r\n" "\n")))

;; ---------------------------------------------------------------------------
;; Read-back of the committed manifest (for --check).
;; ---------------------------------------------------------------------------

(defn read-committed-manifest []
  (when (.exists ^java.io.File @manifest-file)
    (with-open [r (io/reader @manifest-file)]
      (edn/read (java.io.PushbackReader. r)))))

;; ---------------------------------------------------------------------------
;; Entry points.
;; ---------------------------------------------------------------------------

(defn generate!
  "Regenerate spec/api-manifest.edn from live vars + the curated sidecar.
   Returns the manifest map."
  []
  (let [manifest (build-manifest (read-sidecar))]
    (spit @manifest-file (render-edn manifest))
    (println (format "Wrote %s (%d public vars)."
                     (.getPath ^java.io.File @manifest-file)
                     (:var-count (:meta manifest))))
    manifest))

(defn check!
  "Regenerate in memory and compare to the committed manifest. Returns
   true when in sync, false (with a printed diff summary) when drifted."
  []
  (let [generated (build-manifest (read-sidecar))
        committed (read-committed-manifest)
        gen-vars  (set (:vars generated))
        com-vars  (set (:vars committed))]
    (cond
      (nil? committed)
      (do (binding [*out* *err*]
            (println "DRIFT: spec/api-manifest.edn does not exist. Run the generator."))
          false)

      ;; Compare LF-normalised so a CRLF working-tree checkout on Windows
      ;; does not trip a spurious drift (the canonical committed file is LF).
      (= (render-edn generated) (str/replace (slurp @manifest-file) "\r\n" "\n"))
      (do (println (format "OK: spec/api-manifest.edn in sync (%d public vars)."
                           (:var-count (:meta generated))))
          true)

      :else
      (let [added   (sort-by (juxt :namespace :var) (set/difference gen-vars com-vars))
            removed (sort-by (juxt :namespace :var) (set/difference com-vars gen-vars))]
        (binding [*out* *err*]
          (println "DRIFT: generated manifest differs from spec/api-manifest.edn.")
          (println "Regenerate with: clojure -M -m re-frame.api-manifest.gen")
          (when (seq added)
            (println "  Rows the generator produced that the committed file lacks"
                     "(new/renamed public var or changed classification):")
            (doseq [r added] (println "    +" (:namespace r) "/" (:var r)
                                      "->" (:tier r) (:kind r) (:status r))))
          (when (seq removed)
            (println "  Rows in the committed file the generator no longer produces"
                     "(removed/renamed public var):")
            (doseq [r removed] (println "    -" (:namespace r) "/" (:var r))))
          (when (and (empty? added) (empty? removed))
            (println "  (var set identical; :meta or formatting differs — regenerate)")))
        false))))

(defn -main [& args]
  (try
    (if (some #{"--check"} args)
      (System/exit (if (check!) 0 1))
      (do (generate!) (System/exit 0)))
    (catch Throwable t
      (binding [*out* *err*]
        (println "api-manifest generator FAILED:")
        (println (.getMessage t)))
      (System/exit 2))))
