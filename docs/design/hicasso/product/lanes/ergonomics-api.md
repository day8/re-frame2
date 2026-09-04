# Hicasso public language and ergonomics

## Recommendation

Converge on a deliberately small language. Freeze laws first and names only after application/host witnesses. Hicasso should feel like Hiccup plus re-frame2, not like a second React component system. The common path is data; ordinary Clojure functions provide composition; explicit functions and native React components cover the places where executable behavior is real. A small optional Hicasso-native namespace lets the exceptional native path reach Hicasso state without acquiring UIx. (*This sentence said the namespace made that path* "self-contained", *which described the authoring surface [retired below](#optional-native-surface) by `rf2-6c12m.3` on 2026-08-29; corrected 2026-09-04, `rf2-aunp`. What survives supplies the frame and nothing React already gives, so the path is React's own and the namespace is what it reads with. The lane's position — a small optional surface rather than a UIx clone — is unchanged, and the ruling is the strongest form of it.*) React-library interop and SSR/hydration correctness are core product contracts, while UIx, library-specific wrappers, and the deployable Node service remain optional.

## Proposed core surface

- `h/defview`: defines a React/re-frame boundary. It is used as a Hiccup head and refuses a direct Clojure call with a source-located recovery.
- `h/sub`: reads at the point of use during the direct synchronous execution of the active boundary. Branches, loops, and ordinary `defn` helpers are legal; deferred reads refuse.
- `h/event`: captures the current frame, runs later, dispatches a returned event vector, and ignores `nil` — at an **event** position, which is one of the three contracts the form carries. Its contract *does* vary by prop position, and HD-024 tabulates all three: a native `:on-*` prop is **event**; a `defhost` `:callbacks` entry is **as declared** (`:event`, `:handler` or `:render`); any other walked prop is **render** — pure, the return is render output, and dispatching from inside the call refuses at the position. `re-frame.hicasso.impl.intent` is the authority; [`invariants.md`](../invariants.md#3-provisional-facade--ordinary-surface-h) transcribes it in full. (This line read `h/handler`, and read that the meaning does not vary, until `rf2-hic-090`; it read *"`rf2-0fd3b` owns carrying that table alongside the name"* until 2026-08-16, `rf2-0fd3b`, which discharged that obligation by carrying it — here, in the specification, in the door's own docstring, and in the published guide chapter that introduces the name.) **[Amended 2026-08-29, PR #8755 (`rf2-6c12m.24`): HD-024 now tabulates two contracts, event and render, inferred from the position at a native tag and at a host alike; `:callbacks` is only an optional `:event` / `:render` override for an `on*`-named render prop, and `:handler` is deleted.]**
- Literal event vectors: the default event representation.
- `h/defhost`: names and documents a foreign React ABI, ReactNode positions, and server policy once.
- `h/as-element`: explicitly converts Hiccup returned through a foreign render-prop or slot callback.
- Attribute merge: a pure owned-wins recipe/helper, public only if an application witness needs it.
- `h/mount!`, `h/hydrate!`, `h/render!`, and `h/unmount!`: the root lifecycle.
- Server/hydration contract: every public view and host produces deterministic React server behavior or a source-located refusal, then hydrates with root-scoped identity, errors, adoption, and cleanup.
- `h/error-boundary`: a minimal error-region primitive.

The grammar needs only the fragment head, the raw React element head, one props map, and a very small reserved-data vocabulary: value extraction, checked extraction, explicit prevention, and controlled-value revision.

Applications get one obvious `h` facade; optional capabilities use named namespaces with bundle-reachability proofs. A new surface earns core status only when a living use-case ledger shows repeated use or a centralized defect class and the smallest direct-React, Hicasso-native, or established-library alternative is materially worse.

## Optional native surface

**Retired 2026-08-29 16:11:11 AUSEST under `rf2-6c12m.3`**, which ruled Option A: `re-frame.hicasso.native` (`n`) shrinks to exactly two public hooks, `n/use-sub` and `n/use-frame`. The `n/$` element grammar, `n/props`, `n/defcomponent`, the marker-preserving ABI helpers (`n/memo`, `n/lazy`), the tier marker and the seven `:rf.error/hicasso-native-*` refusals this section specified are deleted, and the grammar it carried is no longer a contract. The section's text stands in this page's history before that date.

What survives is the island: a UIx `defui` or a raw React component mounted through `h/defhost` (or `[:>]` for a one-off), reading through `n/use-sub` — the same cell table, reader membership and Xray rosters a boundary's `h/sub` uses — and dispatching through `n/use-frame`, whose operations are pinned to the frame's incarnation. The [canonical native-boundary laws](design-laws.md#native-boundary) were amended in the same act and remain the owner. The ruling's grounds: only the two hooks do something React cannot; everything else duplicated UIx, raw React or `h/defhost` and had one non-test consumer; and the direct-return measurement the grammar rested on (budgets row S8, 19.2% of mount time recovered, observed range 7.0–31.4%) is UNRESOLVED against the guide's own 20% keep line. A defview may still return a React element directly, and that escape is what S8 measures. If real application work later shows repeated friction, the smallest helper proven by that code is added then.

### Provisional `n/$` grammar

Retired with the section above, under the same ruling; the heading stays because [`specification.md`](../specification.md) and the package's `invariants.md` link to it. The grammar it described is deleted, and its text is in this page's history before 2026-08-29.

## Authoring laws

1. A `defview` is always a boundary; an ordinary `defn` is always inline composition.
2. A body is pure and may run, retry, or be abandoned. Render owns nothing.
3. `sub` is ambient only during the active synchronous body. A helper may donate reads to that boundary; a callback, promise, timer, or lazy escape may not.
4. React owns keys, refs, hooks, effects, errors, concurrency, hydration, and component identity.
5. Event vectors are data. `h/event` is the one explicit event-producing function form. Ordinary `fn` values retain ordinary JavaScript callback semantics.
6. Controlled fields use app-db, the synchronous controlled-input door, and an explicit revision for identity-preserving reset.
7. Owned control attributes beat forwarded attributes by presence, not truthiness.
8. Host props use an honest ABI: normalize documented HTML-like slots, pass other values by identity, and require explicit `clj->js` for JavaScript option objects.
9. No public option selects an execution mode. Native construction is explicit at the form or component boundary and never changes the meaning of Hiccup.

State ownership is explicit: durable and application-visible ephemera live at addressed re-frame2 locations; high-rate host-private mechanics may live inside a native host; DOM-owned state is an explicit interop choice. No hidden ratom-like tier appears.

## Surface boundaries and exclusions

- Prevention is explicit at every position except `:on-submit`, whose data spelling auto-prevents as the census-weighted default; no second auto-preventing position may be added, and a callback always owns its own event.
- Callback *carriers* are literal intents, `h/event`, and ordinary functions — one small authoring roster, rather than a taxonomy of carrier forms the author picks a contract from. The *contract* each carrier is read under still comes from its position: HD-024 tabulates event, as-declared and render, as the [`h/event` entry](#proposed-core-surface) above states. (*This clause read* "Callback contracts use literal intents, `h/event`, and ordinary functions rather than a positional taxonomy" *until 2026-08-16, `rf2-0fd3b`.* What the exclusion rules out is a roster of forms; as written it denied the position table on its own page.)
- Attribute forwarding uses an owned-wins pure merge recipe; a public helper exists only if repeated code warrants it.
- Keep key maps restricted to keyboard event props.
- Validate React refs instead of reserving an unproven vector-ref syntax.
- Presence, forms, routing integration, and overlays live in optional namespaces or recipes; a generic local-state facility is outside the product.
- Do not expose the codec or internal `rfProps` ABI as the outward bridge.
- Do not grow the native namespace into a second component framework, hook library, styling system, or host-schema language.

## Interop contract

`defhost` is the named seam for a foreign component, provider, compound component, retained callback, or server policy. Declared ReactNode props and named content slots lower Hiccup under the captured frame; dev schema/lint validates their positions without deep-converting arbitrary data. A one-off raw element remains available, but repeated or hot use should acquire a name and tests. Render props return Hiccup only through `h/as-element`; otherwise values cross by identity.

Add a thin outward bridge for a native React parent—authored with raw React, UIx, or JavaScript/TypeScript—to render a minted Hicasso view under an existing frame provider. It must retain the memo wrapper, frame isolation, key identity, hydration behavior, and teardown law without creating another root.

## Editor and diagnostic ergonomics

The defining macros capture file, line, and column. Every refusal carries a stable error id, view, source, tree path or host-prop position, offending value, expected shape, and concrete recovery. Publish clj-kondo exports for the syntactic facts that can be checked honestly; optional dev schemas may validate declared view args and host/control props without shipping a general validation framework or production cost. Do not imply whole-program analysis.

## Acceptance examples

The frozen ordinary surface must express a Todo flow, article editor, controlled grid, foreign date picker with a render prop, and virtualized list. Direct `defview` calls, deferred reads, malformed intents, opaque host assertions, native/Hiccup semantic mixing, and unsafe SSR policies fail didactically. Native-island completeness is decided only by the canonical hot-path checklist rather than a second paraphrased gate here.
