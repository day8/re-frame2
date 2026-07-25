(ns re-frame.freehand.compiler.root
  "Root identity + mount-surface compiler half (S1c, rf2-vxgfnd.3; the
  root-identity-and-mount contract).

  What lives here:

    - the ROOT-ID grammar: authored validation (qualified keyword | vector
      of qualified keyword + scalar disambiguators), the derivation default
      (the mounted view's registered id, `[view-id disambiguator]` on
      double-mount), the deterministic root-id SLUG and the
      `identifierPrefix` default `\"rf2-\" + slug + \"-\"`;
    - the STATIC TOP-REGION scan over the analyzed root-form AST: the
      mounted view (exactly one for derivation). The static frame-plan half
      went with the `frame-root` / `frame-provider` analyzer arms
      (rf2-h1ae3); the one-frame-one-plan law
      (`:rf.error/frame-payload-conflict`) lives where the plan does, at
      `re-frame.freehand.root`'s mount preflight;
    - LAYER-1 root-id indexing (build tier): the per-build root-site index
      (`:rf.error/duplicate-root-id`). On the Freehand door the client mount
      verbs are runtime fns (`v/mount` / `v/hydrate-root` / `v/unmount!`,
      re-exported from `re-frame.freehand.root`), so `v/render-static` is the
      ONLY entry point that indexes a root site through this half;
    - the `v/render-static` macro body (JVM-only — it runs at CLJS/JVM macro
      expansion; it is the sole compiler-side mount-surface entry point).

  ## Build scope of Layer 1 ([S1-CONFIRM] item 3 — resolved for S1)

  Macro expansion has no entry-point-closure visibility (shadow-cljs
  assigns modules after analysis), so S1 implements the STRICTER
  whole-build projection: every root site expanded by one compiler JVM
  lands in one index, and a duplicate root-id arriving from a DIFFERENT
  file fails the build. Sites re-expanded from the SAME file replace their
  entry (watch-mode/hot-reload tolerance — a moved line is not a second
  site); a genuine same-file duplicate therefore passes Layer 1 and is
  caught by the Layer-3 live-root registry at runtime, before any render.
  Entry-closure scoping (which would additionally ADMIT cross-entry reuse)
  is deferred until the first multi-entry consumer lands, per the
  contract's own [S1-CONFIRM] note — whole-build cannot admit a duplicate
  entry-closure would reject, so no consumer written against S1 breaks
  when the scope narrows.

  Pure analysis fns are host-neutral (resolution is injected via the env,
  the S1b pattern) so the conformance suites run under both
  `clojure -M:test` and the node runner; only the `render-static` macro
  body is JVM-gated."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.build :as build]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.root-id :as root-id]
            #?@(:clj [[re-frame.freehand.descriptor :as descriptor]
                      [re-frame.freehand.compiler :as compiler]
                      [re-frame.freehand.compiler.emit-jvm :as emit-jvm]])))

;; ---------------------------------------------------------------------------
;; Root-id grammar (contract §1)
;; ---------------------------------------------------------------------------

(defn- fail
  ([id msg] (fail id msg nil))
  ([id msg data] (throw (env/compile-error id msg data))))

(def scalar-disambiguator?
  "The disambiguator grammar: keyword, string, or integer (contract §1.2).
  One implementation, in [[re-frame.freehand.root-id]] — the client tier
  asks the same question of the same opts map."
  root-id/scalar-disambiguator?)

(defn validate-authored-root-id!
  "Authored `:root-id` shapes (contract §1.1): a qualified keyword
  (canonical: `:page/shop`) or a vector of a qualified keyword plus scalar
  disambiguators. Anything else is a compile error — identity opts are
  literals, so the shape is always statically checkable."
  [where root-id]
  (when-not (root-id/authored-root-id? root-id)
    (fail :rf.ui.compile/bad-root-id
          (str where ": :root-id must be a qualified keyword (canonical: "
               ":page/shop) or a vector of a qualified keyword plus scalar "
               "disambiguators (e.g. [:shop/product-panel :left]); got "
               (pr-str root-id))
          {:root-id root-id}))
  root-id)

;; The slug and the `identifierPrefix` default it seeds are the SAME
;; function the client tier computes for a root nobody compiled, so they
;; live in `re-frame.freehand.root-id` and are read here. Two
;; implementations that agree today are two implementations that can
;; disagree tomorrow — and a compile-time prefix that disagreed with the
;; runtime one would break `use-id` hydration in a way neither tier could
;; see alone.

(def root-id-slug
  "The ONE deterministic, INJECTIVE slug (contract §1) —
  [[re-frame.freehand.root-id/root-id-slug]]."
  root-id/root-id-slug)

(def default-identifier-prefix
  "`\"rf2-\" + root-id-slug + \"-\"` (contract §3) — the `identifierPrefix`
  default fed to the React root."
  root-id/default-identifier-prefix)

;; ---------------------------------------------------------------------------
;; Top-region scan (contract §5/§6)
;; ---------------------------------------------------------------------------

(defn scan-root-ast
  "Walk an analyzed root-form AST through the top-region wrappers only
  (element / fragment children) collecting, in document order, `:views` —
  the top-region internal-view nodes root-id derivation counts.

  The static FRAME-PLAN half went with the `frame-root` / `frame-provider`
  analyzer arms (rf2-h1ae3): neither authoring var is published on either
  host, so no compiled root form could carry one, and both lowered to a
  `re-frame.freehand.frames/*` namespace that exists nowhere. The
  one-frame-one-plan law is unaffected — the Freehand mount door takes its
  frame from the mount call's `:frame` opt, and `re-frame.freehand.root`
  proves the law there, at preflight, where the plan actually exists."
  [ast]
  (let [acc (volatile! {:views []})]
    (letfn [(walk [n]
              (case (:op n)
                :view (vswap! acc update :views conj n)
                (:element :fragment) (run! walk (:children n))
                nil))]
      (walk ast))
    @acc))

(defn analyze-root
  "Analyze a LITERAL root form (`:top-region? true`):
  -> `{:ast .. :views ..}` (top-region internal views, document order).
  Non-vector root forms are a compile error — the compiler must see the
  root to keep the AST closed."
  [e where form]
  (when-not (vector? form)
    (fail :rf.ui.compile/runtime-root-form
          (str where ": the root form must be a LITERAL vector at the call "
               "site — the compiler must see the root to keep the AST "
               "closed. A runtime-assembled tree is not v1 grammar "
               "(runtime-chosen components are v/view / v/element "
               "[WAVE-2]; a React ELEMENT built at runtime already crosses "
               "into an INTERPRETED tree unwrapped, so keep such a subtree "
               "interpreted); a control form at root position wraps in a "
               "view (conservative S1 pin)")
          {:form form}))
  (let [ast (ana/analyze (assoc e :top-region? true) form)]
    {:ast ast :views (:views (scan-root-ast ast))}))

;; ---------------------------------------------------------------------------
;; Identity resolution (contract §1)
;; ---------------------------------------------------------------------------

(defn validate-disambiguator! [where d]
  (when-not (scalar-disambiguator? d)
    (fail :rf.ui.compile/bad-disambiguator
          (str where ": :disambiguator must be a scalar compile-time "
               "literal (keyword, string, or integer); got " (pr-str d))
          {:disambiguator d}))
  d)

(defn resolve-root-identity
  "-> `{:root-id .. :provenance :authored|:derived}`. Authored wins;
  otherwise the root-id derives from the mounted view's registered id
  (+ `[view-id disambiguator]` when supplied). Derivation requires exactly
  one top-region internal view — zero (bare DOM / foreign root) or more
  than one (fragment of two views) is the contract-pinned compile error."
  [where {:keys [root-id disambiguator]} views]
  (if (some? root-id)
    {:root-id (validate-authored-root-id! where root-id)
     :provenance :authored}
    (let [n (count views)]
      (when (not= 1 n)
        (fail :rf.ui.compile/no-single-mounted-view
              (str where ": root form has no single mounted view — author "
                   ":root-id (found " n " top-region internal views; "
                   "derivation needs exactly one)")
              {:view-count n :view-ids (mapv :view-id views)}))
      (let [vid (:view-id (first views))]
        (when (some? disambiguator)
          (validate-disambiguator! where disambiguator))
        {:root-id    (root-id/derive-root-id vid disambiguator)
         :provenance :derived}))))

;; ---------------------------------------------------------------------------
;; Layer 1 — the build-tier root-site index (contract §7)
;; ---------------------------------------------------------------------------

;; The Layer-1 root-site index is keyed PER build id in the one build-scoped
;; authority (`re-frame.freehand.compiler.build`, rf2-df9873) — no
;; process-global atom to cross-contaminate between the builds one daemon
;; compiles in parallel. Consumers read the ambient build's aggregate through
;; this accessor (a pure function of the current build inputs): a
;; recompiled / edited / renamed / removed source replaces its whole prior
;; contribution atomically, so a deleted root cannot linger to raise a FALSE
;; cross-file duplicate.

(defn build-roots
  "The ambient build's Layer-1 root-site index: root-id ->
  {:file :line :provenance}. Cross-NAMESPACE duplicates fail the build;
  same-namespace re-registration replaces (watch-mode re-expansion tolerance
  — Layer 3 backstops same-namespace duplicates at runtime)."
  ([] (build/aggregate build/roots))
  ([build-state] (build/accepted-aggregate build/roots build-state)))

(defn reset-build-registries!
  "Hard-clear every build-scoped compiler registry (views, the Layer-1
  root-site index, compile-time custom-elements) and the contribution
  ledger — the clean-build / test-isolation boundary. Delegates to the one
  build-scoped model; correctness across a watch session comes from
  per-source replacement on `build/begin-build!`, not from this wipe
  (rf2-vxgfnd.16 AC6)."
  []
  (build/reset-build!)
  nil)

(defn- site-str [{:keys [file line]}]
  (str (or file "<unknown-file>") (when line (str ":" line))))

(defn register-root-site!
  "Index one root site's statically resolved root-id (Layer 1), owned by its
  declaring namespace `ns-sym`. A second site in a DIFFERENT namespace with
  an equal root-id is the build-tier `:rf.error/duplicate-root-id` — thrown
  through the canonical builder, with the both-derived didactic fix per the
  contract. Same-namespace re-registration REPLACES (watch-mode re-expansion
  tolerance — a moved / renamed / deleted site never conflicts with its own
  ghost).

  On a real Shadow build PASS (rf2-u53yy.1 S4) the whole-build roots registry is
  harvested at compile-finish from the disk-cache-durable SYNTHETIC per-namespace
  analyzer-map descriptor — root sites are CALL SITES with no def to carry
  var-meta (unlike views, S2), so this macro stamps the site there via
  `build/stamp-root-plan-site!` and does NOT contribute a per-source slice row (a
  warm cache-hit source never re-runs it). The cross-namespace duplicate-root-id
  law then runs at compile-finish over the carrier
  (`build/harvest-root-plan-registries`, a compile-finish error is still a
  build-time error). Off that path (plain-JVM / SSR, REPL — no compile-finish
  analyzer harvest) the per-source slice contribution + this expansion-time
  cross-namespace check apply; the check-then-write is ONE atomic transition, so
  parallel compilation cannot miss a duplicate or drop a concurrent write."
  [where root-id provenance ns-sym coords]
  (if (build/shadow-build-pass?)
    (build/stamp-root-plan-site!
     ns-sym :roots
     {:root-id root-id :row (assoc coords :provenance provenance)})
    (when-let [{:keys [conflict]}
               (build/contribute-checked!
                build/roots ns-sym root-id (assoc coords :provenance provenance)
                ;; any OTHER namespace's row for this root-id is the conflict
                (fn [existing] existing))]
      (error/throw-error!
       :rf.error/duplicate-root-id where
       (str "two root sites in one build resolve to root-id "
            (pr-str root-id) " — " (site-str conflict) " and "
            (site-str coords) ". Root-ids are page-unique identity; "
            (if (= :derived (:provenance conflict) provenance)
              (str "both ids derived from the same view — add "
                   ":disambiguator or author :root-id")
              "author distinct :root-id values"))
       {:recovery :make-root-ids-unique
        :extra {:root-id    root-id
                :provenance [(:provenance conflict) provenance]
                :sites      [(dissoc conflict :provenance) coords]}})))
  nil)

;; ---------------------------------------------------------------------------
;; Macro body (JVM only — CLJS/JVM macro expansion)
;; ---------------------------------------------------------------------------

#?(:clj
   (do

(defn- source-coords [form cljs?]
  (let [m (meta form)]
    (cond-> {:file (if cljs?
                     (try @(requiring-resolve 'cljs.analyzer/*cljs-file*)
                          (catch Exception _ *file*))
                     *file*)}
      (:line m)   (assoc :line (:line m))
      (:column m) (assoc :column (:column m)))))

(defn- anchored
  "Run `thunk`; a compile error escaping it gains the call form's source
  coordinates (:file/:line/:column) in its ex-data — the S1e file:line
  anchoring, mirroring the `re-frame.freehand.compiler` expansion wrapper.
  Existing ex-data wins on collisions; id, message and cause are
  preserved."
  [coords thunk]
  (try
    (thunk)
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (throw
         (if (and (:rf.ui.compile/error data) (nil? (:file data)))
           (ex-info (ex-message ex) (merge coords data) ex)
           ex))))))

(defn- print-warnings! [e where]
  (doseq [w @(:warnings e)]
    (binding [*out* *err*]
      (println (str "WARNING re-frame.freehand [" where "] " (:id w) ": " (:msg w))))))

(defn declared-view
  "The DECLARED view value a Freehand view-id names, or nil.

  A Freehand `v/defview` mints its id from the declaring namespace and the
  declared symbol and admits no `:id` override (the option roster is closed),
  so the id and the Var are two spellings of one thing and the id is
  resolvable back to the declaration without an index. That is what makes a
  CROSS-BUILD read possible at all: a view compiled in another build (AOT, a
  precompiled jar) contributes nothing to this build's registries, but its
  `def` runs when its namespace loads, so the descriptor is here.

  Deliberately NON-loading. `find-ns` answers only for a namespace that is
  already loaded, which for a render-static dependency it always is — the root
  form's head resolved, so the declaration it names was read. Requiring the
  namespace HERE, mid-expansion, would let a fact lookup run arbitrary
  load-time code, which is not a proof step's business."
  [view-id]
  (when (and (keyword? view-id) (namespace view-id))
    (when-let [ns' (find-ns (symbol (namespace view-id)))]
      (when-let [vr (ns-resolve ns' (symbol (name view-id)))]
        (let [v (deref vr)]
          (when (descriptor/view? v) v))))))

(defn registered-view-static-facts
  "The render-static `{:caps :deps}` facts a COMPILED view carries on its
  declared manifest (`re-frame.freehand.compiler/structural-manifest` →
  `:static-facts`, published on the descriptor `v/defview` defs) — the
  cross-build-context resolution seam (rf2-uv7n6 hole 2, rf2-drpa3.159).

  A view compiled in ANOTHER build (AOT / precompiled JAR) is absent from THIS
  build's `view-static` index, but its declaration runs when its namespace
  loads, so the manifest is resolvable here through [[declared-view]]. nil when
  the id names no declaration, when the declaration is INTERPRETED (an
  interpreted body has no analysis and therefore no facts to carry — see
  [[static-capability-offender]]), or when a compiled manifest predates
  `:static-facts`."
  [view-id]
  (some-> (declared-view view-id) descriptor/manifest :static-facts))

(defn- static-capability-offender
  "The render-static transitive static-capability proof (Spec 004C §3, EP-0034
  §2 no-silent-elision): given the ROOT form's own `{:caps :deps}` facts and the
  ambient build's per-view `view-static` index, return

    - `{:view-id .. :caps ..}`     the FIRST server-reachable view (or the root
                                   form itself, `:rf.root/root-form`) carrying a
                                   runtime-requiring capability;
    - `{:view-id .. :unproven? true}`  the first referenced view whose facts are
                                   genuinely UNOBTAINABLE — a COMPILED view
                                   whose manifest predates `:static-facts`, or
                                   an id naming no reachable declaration at all;
    - nil                          the whole server-reachable closure is
                                   admitted (proven static-safe at BUILD time,
                                   or handed to the render — see below).

  Cycle-safe: the closure follows direct view dependencies breadth-first over a
  `seen` set, so a self-recursive or mutually-recursive view terminates. Each
  dependency's facts resolve from the ambient index first, then from its
  declared manifest (a cross-build/AOT view). Unknown facts are NOT proof of
  static safety (rf2-uv7n6 hole 2): a dependency resolvable from NEITHER source
  is reported UNPROVEN, never silently skipped.

  ## The interpreted arm (rf2-drpa3.159)

  An INTERPRETED declaration has no finite grammar, no analysis step and no
  manifest — that is what the mode IS, and `v/manifest` reports nil for one
  rather than inventing a roster. So there are no build-time facts to resolve
  and there never can be, and reporting the ordinary paved-path view UNPROVEN
  told the author to recompile something that was not stale. The law does not
  weaken: the interpreted tier proves it where its capabilities actually become
  visible, in the RENDER. `re-frame.freehand.tree/emit-static-html` audits the
  structural tree the render produced and refuses to fold a live capability
  away, and a reactive read fails on its own account
  (`:rf.error/view-read-outside-render`). Each tier proves the same law with
  what it actually has — the compiled one with its analysis, the interpreted one
  with its render — so an interpreted dependency is ADMITTED here and NOT
  descended into (its children are its own render's to audit)."
  [own-facts index]
  (if (seq (:caps own-facts))
    {:view-id :rf.root/root-form :caps (:caps own-facts)}
    (loop [queue (vec (:deps own-facts)), seen #{}]
      (when (seq queue)
        (let [vid   (peek queue)
              queue (pop queue)]
          (if (contains? seen vid)
            (recur queue seen)
            (if-let [{:keys [caps deps]} (or (get index vid)
                                             (registered-view-static-facts vid))]
              (if (seq caps)
                {:view-id vid :caps caps}
                (recur (into queue deps) (conj seen vid)))
              (if-let [view (declared-view vid)]
                (if (= :interpreted (:lowering (descriptor/describe view)))
                  ;; No analysis exists to consult — the render is this
                  ;; declaration's proof. Admit, and do not descend.
                  (recur queue (conj seen vid))
                  ;; A COMPILED declaration with no facts is genuinely stale:
                  ;; its manifest was built before `:static-facts` existed.
                  {:view-id vid :unproven? true :reason :stale-compiled})
                {:view-id vid :unproven? true :reason :unresolvable}))))))))

(defn render-static-form
  "`(v/render-static root-form)` — the pure `:server`-phase static-HTML render
  (Spec 004C §3; Spec 011 §Phase flip). JVM-ONLY, the counterpart of the client
  mount verbs: it compiles the LITERAL root form to the versioned JVM structural
  tree and folds it to an INERT HTML string through `re-frame.ssr/emit-ui-tree`
  (late-resolved — see INDEPENDENCE) — NO manifest, NO hydration payload, NO phase
  flip (a `client-only` site renders its capability-free fallback and stops there;
  there is no fallback pass to flip away from). It is the static-page path, not the
  SSR-then-hydrate path.

  Four invariants, all reusing the mount-surface machinery:

    - LITERAL ROOT FORM — `analyze-root` rejects a runtime-assembled vector with
      the SAME `:rf.ui.compile/runtime-root-form` compile error `ui.test/render`
      raises (a macro sees the literal form; a fn cannot). The runtime mount door
      (`v/mount` / `v/hydrate-root`) cannot enforce this — the literal-form
      guarantee is render-static's alone, the kind-label delta a fn could not
      honour.
    - NO SILENT ELISION (Spec 004C §3, EP-0034 §2) — a runtime-requiring
      capability (subs, committed handlers, effects, refs, context, foreign / lazy
      heads) anywhere in the root's SERVER-REACHABLE view closure fails LOUD,
      never as a capability dropped from the static output. Each tier proves that
      with what it actually has. A COMPILED view is proven at BUILD time:
      `compiler/view-static-facts` projects its server-reachable capabilities +
      direct-view dependencies onto its manifest and into the `view-static` build
      index, and `static-capability-offender` closes over both cycle-safe, so a
      breach is `:rf.ui.compile/static-root-requires-runtime` with source
      coordinates. An INTERPRETED view has no analysis to consult — there is no
      finite grammar and no manifest — so it is proven at RENDER, by
      `re-frame.freehand.tree/emit-static-html`, which refuses to fold a live
      capability out of the structural tree. `use-id` is exempt (an inert
      deterministic id is static-safe); `v/client-only` stays legal (only its
      capability-free fallback is server-reachable — a static root never flips).
    - IDENTITY PARTICIPATION (§7) — with no opts the root-id derives from the
      single mounted view, so `resolve-root-identity` enforces exactly one view
      (`:rf.ui.compile/no-single-mounted-view` otherwise). The derived identity is
      RETAINED and registered into the Layer-1 build-tier duplicate-root-id index
      via `register-root-site!` — on the Freehand door render-static is the ONLY
      entry point that registers here (the runtime mount verbs backstop duplicates
      at the Layer-3 live-root registry); a second site resolving to the same
      root-id is `:rf.error/duplicate-root-id`.
      Layer-2 duplicate detection (server render time, S5) is the SSR host's
      per-response registry, not the macro's.
    - INDEPENDENCE — the macro emits a call to the ui-side late-resolution seam
      `re-frame.freehand.tree/emit-static-html` (NOT a bare `re-frame.ssr/emit-ui-tree`
      reference), so `re-frame.freehand` never statically depends on `re-frame.ssr` (Spec
      011 §wall — no `ui → ssr` static require) AND the caller's namespace carries
      no compile-time `re-frame.ssr` reference: a documented render-static call in
      a namespace that requires only `re-frame.freehand` compiles and renders (the seam
      loads the emitter at render time) instead of failing with a raw
      `ClassNotFoundException`. A missing `day8/re-frame2-ssr` artefact is the
      ruled, typed `:rf.error/ssr-artefact-missing`, never a raw host exception.

  Expanding in a CLJS build is a compile error (`:rf.ui.compile/ui-render-static-jvm-only`)
  — CLJS has no structural trees (the client emitter targets React directly), and
  a CLJS expansion would emit the JVM tree + SSR serialiser into a browser bundle."
  [form menv root-form]
  ;; JVM-only. A CLJS expansion would (a) render statically in a browser — wrong —
  ;; and (b) pull the JVM tree + SSR emitter into the CLJS bundle (a ui→ssr edge).
  ;; Reject before touching the compiler, mirroring ui.test/render.
  (when (some? (:ns menv))
    (fail :rf.ui.compile/ui-render-static-jvm-only
          (str "v/render-static is the pure :server static-HTML render — it "
               "runs on the JVM/server. CLJS builds have no structural trees "
               "(the client emitter targets React directly); author render-static "
               "in a .clj / .cljc-on-JVM server render namespace, and mount in the "
               "browser with v/mount / v/hydrate-root")
          nil))
  (let [coords (source-coords form false)
        ns-sym (ns-name *ns*)]
    (anchored coords
      (fn []
        (let [e (-> (env/make-env {:host :clj :ns-sym ns-sym})
                    (env/with-locals (keys menv)))
              {:keys [ast views]} (analyze-root e 'v/render-static root-form)
              ;; Identity participation (§7): no opts ⇒ derive from the single
              ;; mounted view; reuse the exact one-view invariant + its frozen
              ;; compile id.
              {:keys [root-id provenance]} (resolve-root-identity
                                            'v/render-static {} views)]
          (print-warnings! e 'v/render-static)
          ;; No-silent-elision proof (Spec 004C §3, EP-0034 §2): a pure :server
          ;; static render cannot honour a live-runtime capability, so any
          ;; runtime-requiring capability in the root's server-reachable view
          ;; closure (subs, committed handlers, effects, refs, context, foreign /
          ;; lazy heads) is a loud BUILD error with source coordinates — never a
          ;; silently dropped capability. `v/client-only` stays legal (only its
          ;; capability-free fallback is server-reachable); `use-id` is exempt.
          (when-let [{:keys [view-id caps unproven? reason]}
                     (static-capability-offender
                      (compiler/view-static-facts ast @(:sites e))
                      (compiler/build-view-static))]
            (if unproven?
              ;; A referenced view whose render-static facts are UNOBTAINABLE is
              ;; UNPROVEN — a loud build error, never a silently-inert render that
              ;; could drop an interactive dependency's handler (hole 2). The
              ;; diagnostic states WHICH of the two obtainable-fact routes failed,
              ;; because they have different recoveries and a message naming the
              ;; wrong one sends the author to recompile a view that is not stale
              ;; (rf2-drpa3.159).
              (fail :rf.ui.compile/static-root-unproven-dependency
                    (if (= :unresolvable reason)
                      (str "v/render-static: the static root depends on view "
                           (pr-str view-id) " whose render-static facts are "
                           "UNPROVEN — the id names no reachable v/defview "
                           "declaration, so neither this build's view-static index "
                           "nor a declared manifest can answer for it. Unknown "
                           "facts are not proof of static safety, so render-static "
                           "fails loud rather than shipping a possibly-inert "
                           "render. Require the namespace that declares "
                           (pr-str view-id) " from this server render namespace, "
                           "or mount this tree in the browser with v/mount / "
                           "v/hydrate-root")
                      (str "v/render-static: the static root depends on COMPILED "
                           "view " (pr-str view-id) " whose render-static facts are "
                           "UNPROVEN — its declared manifest carries no "
                           ":static-facts, so it was compiled before those facts "
                           "existed (an AOT/precompiled artefact built against an "
                           "older re-frame.freehand). Unknown facts are not proof "
                           "of static safety, so render-static fails loud rather "
                           "than shipping a possibly-inert render. Recompile "
                           (pr-str view-id) " against a current re-frame.freehand, "
                           "or mount this tree in the browser with v/mount / "
                           "v/hydrate-root"))
                    {:root-id root-id :unproven-view view-id :reason reason})
              (fail :rf.ui.compile/static-root-requires-runtime
                    (str "v/render-static: the static root's server-reachable view "
                         "closure requires runtime capability "
                         (str/join ", " (map name (sort caps)))
                         (if (= :rf.root/root-form view-id)
                           " in the root form itself"
                           (str " (via view " (pr-str view-id) ")"))
                         " — render-static is the pure :server static-HTML path (no "
                         "subs, handlers, effects, refs, context, or foreign/lazy "
                         "heads; no manifest, no hydration, no phase flip). Mount "
                         "this tree in the browser with v/mount / v/hydrate-root, "
                         "or move the live subtree behind a v/client-only with a "
                         "capability-free fallback")
                    {:root-id root-id :offending-view view-id
                     :capabilities (vec (sort caps))})))
          ;; Layer-1 identity registration (§7): the static root participates in
          ;; the build-tier duplicate-root-id index — the derived identity is
          ;; retained, not discarded. On the Freehand door render-static is the
          ;; only entry point that registers here.
          (register-root-site! 'v/render-static root-id provenance ns-sym coords)
          ;; Emit a call to the ui-side late-resolution seam (NOT a bare
          ;; `re-frame.ssr/emit-ui-tree` reference) so the caller's namespace
          ;; carries no compile-time `re-frame.ssr` reference — a missing SSR
          ;; artefact is the ruled typed `:rf.error/ssr-artefact-missing`, not a
          ;; raw ClassNotFoundException. `re-frame.freehand` still takes no static
          ;; require on `re-frame.ssr` (the resolve is late, at render time).
          `(~'re-frame.freehand.tree/emit-static-html
            (~'re-frame.freehand.tree/render-root-tree
             (fn [] ~(emit-jvm/emit-node nil ast)))))))))

))
