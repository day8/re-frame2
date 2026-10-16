# EP-0039: Static Data Classification

Status: accepted
Type: standards-track
Created: 2026-10-16
Resolution: accepted 2026-10-16 (operator ruling: durable state is classified by static, authored declarations, and a retained value keeps the redactions it was recorded under; the declaration's home, name and shape, and how the subsystem resolver hooks are published, are settled in this EP under that ruling)

> This EP classifies durable state — app-db, and the runtime-db that machines,
> resources, mutations and routes hold — by **static, authored declarations**
> resolved through the target frame's image. A retained old value keeps the
> redactions it was recorded under. On graduation the normative home is
> [Spec 015](../../spec/015-Data-Classification.md), with the new registration
> kind in Spec 001, the effect map in Spec 002 and the reservations in
> Conventions.
>
> **Supersedes in part** [EP-0025](EP-0025-data-classification.md). This EP
> replaces two parts of EP-0025: its durable app-db design (the four
> commit-plane effects and the per-frame elision registry they write) and its
> subsystem design (per-instance lowering into that registry, dropped at
> teardown). It **keeps** the rest of EP-0025:
>
> - its north star: hygiene, not security; trust the developer; minimal
>   infrastructure; fail-open on omission;
> - its failure posture;
> - its registration-owned transient classification;
> - its egress rules: sensitive wins, a `:large` marker over a `:sensitive`
>   leaf is measured after redaction, nothing propagates, and projection is
>   profile-aware through `project-egress`.
>
> EP-0025 stays `final` and carries a dated note pointing here.

## Abstract

A feature declares the app-db paths it makes sensitive or large once, beside its other registrations:

```clojure
(rf/reg-classification :auth
  {:sensitive [[:auth :token]
               [:auth :login-form :draft :password]]})
```

Machines, resources, mutations and routes keep declaring projection-relative paths on their own registrations. Those declarations are applied at egress, to the instances present in the value being projected. There is no runtime registry, no owner bookkeeping and no classification effect.

Declarations still change over time: a namespace is re-evaluated, a registration is replaced, or a frame gets a new generation. So a retained old value — an epoch record, or a subscription's previous value — is projected under the classification policy it was recorded with, carried by reference, as well as under the current one.

## Motivation

EP-0025 put durable classification in a per-frame elision registry in runtime-db (`[:rf.runtime/elision]`). Four commit-plane effects write it for app-db, and each subsystem lowers its declarations into it per instance. That works, but the registry is time-varying state, and keeping it correct is most of the machinery.

**The bookkeeping.** Owners claim and release paths as instances come and go:

- multi-owner claim sets, in `re-frame.elision` (`add-claims`, `remove-owner`, `replace-owner-claims`);
- `reconcile-runtime-db-effect`, which carries the registry across a whole-partition runtime-db write;
- `elide-pending-db`, which projects a pre-commit trace against a candidate registry so that a path classified in the same event is redacted on its first egress;
- `reconcile-registry` and `reconcile-mutation-registry` in `re-frame.resources.classification`;
- `lower-at-spawn!` and `drop-at-destroy!` in `re-frame.machines.classification`, plus a re-lowering for each actor `re-frame.machines.hydrate` installs;
- `apply-route-classification`, which replaces the route's claims on every navigation.

**The bugs.** That variation produced four defects, all since fixed:

- a path classified and written in the same event shipped raw in that event's pending-db trace (rf2-3x7nj.4.3);
- a persisted machine install lost its per-actor classification (rf2-gko6q);
- route read subs leaked a declared-sensitive param in `:rf.sub/prev-value` after navigating to a route that declared none (rf2-kuyza);
- epoch export redacted historic slots against the current registry, so a cleared flow's output leaked (rf2-hakdt, rf2-03jei).

**The ceremony.** Apps write events whose only job is classification, or fold a `:sensitive` effect into a seed event:

- `:auth/classify-token` in `examples/real-apps/realworld_resources/core.cljs`;
- `:auth.login/initialise-form` in `examples/core/login/model.cljc`;
- `:settings/load` in `examples/real-apps/realworld_resources/settings.cljs`.

**Per-entity paths need no runtime registry.** The one thing a runtime registry seemed to buy was a way to classify paths that exist only at runtime, such as `[:users <id> :token]`. Static declarations already cover that. The collection matcher ([Spec 015 §Projection](../../spec/015-Data-Classification.md#collection-matching); `fork-decl-paths` in `re-frame.elision`) lets a declared `[:users :token]` reach the `:token` under every key of a `:users` map. Two tests in `classification_map_of_test.clj` pin it: `a-sensitive-mark-under-a-map-of-value-redacts-under-every-key` and `a-same-named-slot-below-a-matched-segment-also-matches`. No new path grammar is needed.

**History must keep its redactions.** This is the one thing the registry design gets right, and static declarations must keep it:

- `re-registering-the-leaving-route-keeps-the-held-subs-prev-values-classified` (`routing_sub_prev_value_classification_test.clj`) requires a held route secret to stay redacted after its route is re-registered without the declaration. Today `slice-classification` (`re-frame.routing.classification`) does this by recording the declaration on the slice's metadata.
- `a-cleared-flows-output-keeps-its-recorded-classification` (`epoch_egress_historic_classification_test.clj`) requires an epoch record to keep a cleared flow's redaction. Today `redact-by-recorded-registry` (`re-frame.epoch.tool-pair`) does this by applying the registry recorded in the frame-state before the live one.

Static declarations change too. A frame made without `:images` resolves the live registration store, and a frame's generation can be replaced. So the static design needs its own answer for history (§5).

## Specification

**Scope.** This EP owns how durable state is classified: where app-db declarations live, how subsystem declarations reach egress, and how retained values keep their redactions. It does not change:

- transient classification (`:sensitive` / `:large` on `reg-event`, `reg-fx`, `reg-cofx` and `reg-sub`);
- the HTTP carriers, or schema validation-failure redaction;
- the `:rf.egress/*` profiles, the sentinels, or `project-egress` and `elide-wire-value` as the egress doors;
- the SSR allowlist, or the frame `:observability` sink policy.

### 1. Home: an image registration

App-db classification is a registration, of kind `:classification`. It lives in the program, not in frame state, which gives it three properties:

- **Declared where the feature is defined.** A feature's classification sits in the namespace that registers its events and subs, not in an init event that has to run first.
- **Hot-reloads.** Re-evaluating the namespace replaces the registration, and the next resolution sees it ([EP-0023 §Default Image Semantics](EP-0023-image-loaded-frames.md#default-image-semantics)).
- **Consistent across frames.** Every frame running the same image classifies the same paths. Frames that must differ run different images, which is how [`reg-app-schema`](../../spec/010-Schemas.md#schemas-resolve-through-the-frames-images) already works.

Frame config was the alternative. It scatters a feature's classification into every frame that mounts the feature. And the frame is not app-db's definition site, which is why EP-0025 removed the frame annotation in the first place.

### 2. Name and shape: `reg-classification`

```clojure
(rf/reg-classification :auth
  {:doc       "The session token and the login draft password."
   :sensitive [[:auth :token]
               [:auth :login-form :draft :password]]})

(rf/reg-classification :docs
  {:large [[:docs :body]]})   ; every document's :body, under any doc id
```

The rules:

- **The call.** `(reg-classification id decl)` registers `decl` under kind `:classification` and returns `id`. The id is the feature's keyword. It lets the declaration be replaced, cleared with `(rf/clear :classification id)` and shown by tools; nothing else refers to it.
- **The map.** `decl` is a closed map: `:sensitive` and `:large`, each a vector of `:rf/path` vectors that are **absolute in app-db**, plus an optional `:doc`. This is the same vocabulary, path grammar and validator the transient registration metadata uses, so `[]` classifies the whole of app-db.
- **Matching.** The paths are matched by the existing collection matcher, unchanged. A declared slot reaches the same slot under every map key, vector index and set element below its first matched segment.
- **Union.** A frame's app-db classification is every `:classification` registration its resolution runs. Two features may name the same path.
- **Failure.** A malformed declaration fails at registration with `:rf.error/bad-classification`, the id the transient declarations already raise.
- **The macro.** `reg-classification` sits on the `rf/` façade with the other registration macros, and stamps source coordinates.

The name keeps the noun the contract already uses: Spec 015's title, and `:rf.error/bad-classification`. It needs no `app-` qualifier, because app-db is the only partition an app names absolutely; subsystems classify on their own registrations.

**Flows.** A `reg-flow` output's `:sensitive` / `:large` is already a static declaration on the flow, relative to its `:output-path`. Flows are top-level registrations that a frame runs by naming them in its `:flows` config, resolved through the frame's own view like any other kind ([Spec 013 §Activation](../../spec/013-Flows.md#activation)). So the flows artefact contributes the output paths of the flows a frame runs to that frame's app-db classification through one late-bound hook, `:flows/output-classification`. Nothing else about flows changes.

### 3. Resolution through the target frame's image

Live egress reads the frame's **classification policy**. The policy is a small immutable value holding the frame's declarations, never its instances:

```clojure
{:app-db {:sensitive [path …]   ; :classification registrations ∪ the frame's flow outputs,
          :large     [path …]}  ; each path with the surface that declared it
 :roots  {:rf.runtime/machines {<machine-type> decl}   ; per runtime-db root: the declarations
          :rf.runtime/routing  {<route-id> decl}       ; its owner collected (§4)
          …}}
```

The policy is built inside the existing frame-resolution seam: `re-frame.live-frame/call-with-frame-resolution`, reached late-bound as `:live-frame/call-with-frame-resolution`, which is the route `reg-app-schema` lookups already take. So it resolves through the **target** frame's sealed generation, or through the live view of the registration store for a frame made without `:images`. It never resolves through an ambient global lookup.

The policy is recomputed only when one of its inputs changes: the resolution view (the sealed generation, or the store snapshot a live view closes over), or the frame's flow outputs. Successive egresses, and every value recorded while the declarations stand still, therefore share one policy value.

`elide-wire-value`, `project-egress` and the trace chokepoint read the policy where they read `[:rf.runtime/elision]` today. These are unchanged:

- precedence between the axes, and fail-closed frame resolution;
- the profiles;
- the large marker's `:reason`. It names the declaring surface (`:classification`, `:flow`, `:machine`, `:route`, `:resource` or `:mutation`), chosen deterministically when several surfaces declare one path.

### 4. Subsystem declarations apply at egress: one hook per runtime-db root

Machines, resources, mutations and routes keep their projection-relative declarations, written as [Spec 015 §Subsystem projection-relative classification](../../spec/015-Data-Classification.md#subsystem-projection-relative-classification) describes. What changes is when those declarations become absolute paths. Today they are lowered into a registry when an instance is born and dropped when it is torn down. Here they are applied at **egress**, to the instances present in the value being projected.

Each runtime-db root has one owner, and the owner publishes **one late-bound hook** for it:

| Runtime-db root | Hook key | Publisher |
|---|---|---|
| `:rf.runtime/machines` | `:machines/runtime-classification` | `re-frame.machines.classification` |
| `:rf.runtime/resources` | `:resources/runtime-classification` | `re-frame.resources.classification` |
| `:rf.runtime/mutations` | `:resources/mutation-runtime-classification` | `re-frame.resources.classification` |
| `:rf.runtime/routing` | `:routing/runtime-classification` | `re-frame.routing.classification` |

Every hook has the same two-arity contract:

```clojure
(fn
  ([] {registration-id decl …})        ; collect
  ([root-value decls]                  ; apply
   {:sensitive [abs-path …] :large [abs-path …]}))
```

- **Collect** is called while the policy is built, inside the frame-resolution seam. It returns the declarations of its family's registrations that declare any, keyed by registration id, and the policy stores the result under the hook's root.
- **Apply** is called at egress. `root-value` is the value under that root in the runtime-db being projected: the live partition for a direct read, or the recorded one for an epoch. `decls` is the policy's map for that root. The hook names each instance's registration from the value itself, then re-roots that registration's declaration onto the instance, exactly as the lowering re-roots it today. Each instance names its registration by:
    - a machine snapshot: its `:rf/machine-type`, or its own id for a singleton;
    - a resource entry: its `:resource/key`;
    - a mutation instance: its `:mutation/id`;
    - the current route slice: its `:route-id` ([Spec-Schemas §`:rf/route-slice`](../../spec/Spec-Schemas.md#rfroute-slice)).
- **Purity.** Apply is pure: no registry write, no frame lookup and no container read. It returns runtime-db-absolute paths, which core walks with the ordinary matcher. A consumer projecting a single instance hands the hook a root value holding just that instance.

Core keeps the table that maps each root to its hook key. A root whose hook is unbound has no classification, which is safe for the same reason it is safe for the `:machines/project-ssr-runtime-db` hook: without its artefact, a runtime-db carries no instances under that root. A new runtime-db root publishes its hook the same way.

Every consumer that needs a subsystem's classification goes through these hooks:

- the trace chokepoint;
- the framework read subs (`[:rf/machine …]`, the route subs and the resource subs), re-seeded at their storage position;
- the SSR runtime-db projection;
- the epoch and direct-read projections.

An instance is therefore classified exactly while it is present in the value. Nothing has to be lowered, dropped, re-lowered after a hydrate or a restore, or replaced on navigation.

### 5. The historical-value rule

**A retained old value keeps the redactions it was recorded under.** It is projected under the policy it was recorded with *and* the target frame's current policy, recorded policy first — the order `redact-by-recorded-registry` uses today. This has two consequences:

- a large marker is never measured over a secret that only the recorded policy names;
- a declaration added since the value was recorded covers that value too.

History is never re-projected against the current image alone.

**The capture is a policy reference.** A retained value carries the policy value that was current when it was recorded. The policy is compact (declarations only, never instances) and immutable, and every value recorded while the declarations stand still shares it, so a retained value carries a pointer, not a copy. It rides in a framework slot that projection consumes and strips, so it never egresses. Two kinds of retained value carry one:

- **Epoch records.** Each record carries the one policy taken as the record is assembled. Epoch projection applies it where `redact-by-recorded-registry` applies the recorded registry today.
- **Previous subscription values.** The sub memo captures the policy with each value it computes, so `:rf.sub/prev-value` is projected under the policy its value was computed with. This replaces both the route slice's declaration metadata and the machine sub's read of the registry out of its prior inputs.

None of this needs a per-instance owner registry. The recorded runtime-db value already names the instances, and the policy names their declarations.

A restored epoch is not history. Once installed it is live state, projected under the current policy.

### 6. SSR, first egress and failure posture

- **First egress.** A declaration exists before any event runs, so a classified path is redacted from its first egress, including the pre-commit `:rf.event/db-pending` trace, with no candidate projection.
- **SSR.** The server and the client run the same image, so they resolve the same policy. Nothing classification-related crosses the hydration wire, and the `:payload-include-sensitive` permit is unchanged. A hydrated client still receives `:rf/redacted` at a classified path the host did not permit, so the login example's draft-password re-seed after hydration stays.
- **Failure posture.** Unchanged. A forgotten declaration fails open. A malformed one fails loud, now at registration rather than at commit. An unknown frame or profile fails closed.

### 7. What goes, and what is accepted

Removed:

- **The four commit-plane effects** (`:sensitive`, `:large`, `:clear-sensitive`, `:clear-large`) and `:rf.error/classification-effect-shape`. The closed effect map returns to `:db`, `:rf.db/runtime` and `:fx`, so a handler that still returns one of the four is refused by `:rf.error/effect-map-shape`, whose message names `reg-classification`.
- **The registry.** The per-frame elision registry at `[:rf.runtime/elision]`, its owner sets, the candidate projection (`elide-pending-db`) and `reconcile-runtime-db-effect`.
- **Subsystem lowering.** Lowering, dropping and re-lowering; `reconcile-registry` and `reconcile-mutation-registry`; the route's claim replace.
- **The SSR carve-out.** `re-frame.ssr.payload-policy` omits the registry from the hydration wire and `re-frame.ssr.render-state` refuses `:rf.runtime/elision` in a render-state allowlist. With no registry, there is nothing left to omit or refuse.

**Accepted loss.** Three capabilities go, and nothing in the repository uses any of them:

- value-dependent classification: classifying a path only when its value turns out to need it;
- per-frame classification within one image;
- un-classifying a reused path.

A programmer who needs a body visible keeps it on an unclassified path. We trust the programmer.

### Spec sections the implementation changes

These graduation edits land with the stages under [Backwards Compatibility](#backwards-compatibility):

- **[Spec 015](../../spec/015-Data-Classification.md).** §The vocabulary in one place; §Durable app-db, which becomes `reg-classification`; §Subsystem projection-relative classification, which moves from lowering to the egress hooks; §SSR and hydration (the registry omission); §Epoch projection (the recorded policy); §Failure posture; §What is removed; and the §Tests fixture rows that install `:source :effect`.
- **[Spec 001](../../spec/001-Registration.md#registry-model--the-canonical-kind-keyword-set)** gains the `:classification` kind.
- **[Spec 002](../../spec/002-Frames.md#commit-plane-data-classification-effects-ep-0025)** loses the commit-plane classification effects.
- **[Conventions](../../spec/Conventions.md#reserved-commit-plane-classification-effects)** loses those reservations, and the two `:rf.runtime/elision` rows of its runtime-db catalogue.
- **The rest:** the classification passages of Specs 005, 011, 012, 013 and 016; the classification error rows in Spec 009; `:rf/effect-map` and `:rf/runtime-db` in Spec-Schemas; `spec/API.md` with its manifest sidecars; and the conformance fixtures.

### Guide impact

`how-to/keep-secrets-out-of-traces.md` changes most. Its "Classify a durable secret in app-db" section teaches `reg-classification` beside the feature instead of an init-event effect, and the effects' lifecycle advice goes. The glossary's data-classification entry follows, as do the classification recaps in `observability.md`, `concepts/server-state.md`, `concepts/routing.md` and `concepts/ssr.md`.

Newly teachable: classification is one declaration per feature, visible in the source, and it holds from the first event and the first SSR render.

## Rationale

**Classification is a property of the program, not of a frame's state.** Which paths hold secrets is decided when the feature is written. EP-0025 stored that decision in runtime-db, so it had to be written by events, carried across partition writes, rebuilt after hydration and restore, and recorded with history, and each of those duties produced a bug. As a registration, it resolves like every other program fact: through the target frame's image.

**Egress resolution over instance-birth lowering.** A subsystem instance's classification is a pure function of the instance's registration and its position. Computing it where a value is projected removes every moment at which the registry and the instances could disagree: spawn, teardown, eviction, hydrate, restore and navigation.

EP-0025 rejected a "per-egress resolver", but what it rejected was a global registrar folded over every record. Here the work is a lookup over values already being projected, against declarations already resolved per frame.

**One policy reference for history.** Today history keeps its classification three different ways: the registry inside a recorded frame-state, the inputs a prior sub value was computed from, and declaration metadata on the route slice. One immutable policy value, captured by reference, replaces all three, and it needs no owner registry.

**Rejected:**

- **A — keep the runtime registry.** Its machinery exists only to keep time-variance correct, and that variance produced the four defects above.
- **C — a permanent hybrid of static declarations and the effects.** It leaves two ways to classify one durable path, and keeps the registry's bookkeeping.
- **D on its own — subsystem declarations at egress while app-db keeps its effects and registry.** That is stage 1 of the migration below: a stage, not an end state.
- **Generic policy callbacks.** A function in the declaration buys value-dependence, which is the accepted loss, at the price of declarations that cannot be read, shown or compared.
- **A new wildcard grammar.** The collection matcher already covers per-entity paths.

## Backwards Compatibility

Pre-alpha, so there is no shim. The migration runs in four steps. Each step is its own bead, and each leaves the tree green.

1. **Vertical slice (rf2-o5rtr).** Build `reg-classification`, the policy, and its capture on epoch records and sub memos. Prove them end to end on one fixed path, the login draft password:
    - **first egress**, with no classification event;
    - **SSR**: the server's payload permit and the client's classification agree;
    - **history** after the declaration is replaced — the two historical tests above stay green, and a new test covers the slice's own path;
    - **resolution** through the target frame's image.

    The registry still serves everything else. The slice measures what it deletes, or would let a later stage delete, against what it adds. If the per-root hooks prove harder to reason about than the lowering they replace, the slice stops and reports to the operator instead of continuing.
2. **Stage 1 — subsystems at egress.** Publish the four root hooks. Then delete lowering, dropping, re-lowering, `reconcile-registry`, `reconcile-mutation-registry` and the route replace. App-db still uses the effects at this point: this is option D, as a stage.
3. **Stage 2 — the app-db registration.** Move every app-db classification to `reg-classification`, and flow outputs to the flows hook. Then remove the four effects, `[:rf.runtime/elision]`, the owner sets, the candidate projection, `reconcile-runtime-db-effect` and the SSR carve-out.
4. **Propagation.** Update the remaining surfaces:
    - the examples, including the three ceremony sites above;
    - the how-to and the glossary;
    - the classification skill (`skills/re-frame2/references/cross-cutting/privacy-and-elision.md`);
    - Story's variant classification: a variant's `[:world :sensitive]` / `[:world :large]`, which `apply-variant-classification!` applies today as effects;
    - the tools that read the registry to show what is classified, which read the policy instead.

## Resolved Decisions

- **2026-10-16 — static classification (option B), under a new EP.** Operator ruling. The decision dossier and an independent Codex review both chose B. They differed only on the historical-value rule and on building one vertical slice first, and the operator settled both on the Codex side.
- **2026-10-16 — retained values keep their recorded redactions.** This is the amendment adopted with the ruling; §5 settles the capture as a policy reference.
- **Home, name and shape, and hook publication.** Settled in §1, §2 and §4, under the same ruling.

## Open Issues

None. The vertical slice's measurement decides whether stages 1 and 2 proceed. If it says no, that goes to the operator as a stop report.

## References

- [EP-0025](EP-0025-data-classification.md): the predecessor, superseded in part.
- [Spec 015](../../spec/015-Data-Classification.md): the normative home, and the collection matcher the declarations rely on.
- [EP-0023](EP-0023-image-loaded-frames.md) and [EP-0024](EP-0024-unified-frame-identity-and-lifecycle.md): images, and frame-derived registration resolution.
- [Spec 010 §Schemas resolve through the frame's images](../../spec/010-Schemas.md#schemas-resolve-through-the-frames-images): the `reg-app-schema` precedent for a frame-resolved app-db registration.
- [EP-0012](EP-0012-path-optics-and-canonical-forms.md): the `:rf/path` vocabulary.
- Tracker:
    - rf2-0iyzr, this EP;
    - rf2-o5rtr, the vertical slice;
    - rf2-mn2ht, the adversarial architecture review that raised it;
    - rf2-by79j, the collection matcher;
    - rf2-3x7nj.4.3, rf2-gko6q, rf2-kuyza, rf2-hakdt and rf2-03jei, the defects cited above.
- Source, cited by symbol:
    - `re-frame.live-frame`: `call-with-frame-resolution`, `call-with-record-resolution`;
    - `re-frame.elision`: `fork-decl-paths`, `reconcile-runtime-db-effect`, `elide-pending-db`;
    - `re-frame.machines.classification`: `lower-at-spawn!`, `drop-at-destroy!`;
    - `re-frame.resources.classification`: `reconcile-registry`;
    - `re-frame.routing.classification`: `slice-classification`;
    - `re-frame.epoch.tool-pair`: `redact-by-recorded-registry`.
