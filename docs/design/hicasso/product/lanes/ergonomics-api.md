# Hicasso public language and ergonomics

## Recommendation

Converge on a deliberately small language. Freeze laws first and names only after application/host witnesses. Hicasso should feel like Hiccup plus re-frame2, not like a second React component system. The common path is data; ordinary Clojure functions provide composition; explicit functions and native React components cover the places where executable behavior is real. A small optional Hicasso-native namespace makes the exceptional native path self-contained without cloning UIx. React-library interop and SSR/hydration correctness are core product contracts, while UIx, library-specific wrappers, and the deployable Node service remain optional.

## Proposed core surface

- `h/defview`: defines a React/re-frame boundary. It is used as a Hiccup head and refuses a direct Clojure call with a source-located recovery.
- `h/sub`: reads at the point of use during the direct synchronous execution of the active boundary. Branches, loops, and ordinary `defn` helpers are legal; deferred reads refuse.
- `h/handler`: captures the current frame, runs later, dispatches a returned event vector, and ignores `nil`. Its meaning does not vary by prop position.
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

The same artifact exposes a separately reachable namespace, provisionally `re-frame.hicasso.native` (`n`). The [canonical native-boundary laws](design-laws.md#native-boundary) govern its semantics, dependency isolation, ABI, and opacity; the [canonical acceptance checklist](hot-path-architecture.md#canonical-native-tier-acceptance-checklist) owns its release proof.

| Surface | Contract |
|---|---|
| `n/$` | Macro-expand an explicit native element form to direct React construction; native props, callbacks, children, keys, and refs apply, with no Hicasso intent or controlled-field lowering |
| `n/props` | Mark a dynamic ClojureScript map or JavaScript object as the props operand of `n/$`; the marker itself emits no wrapper |
| `n/defcomponent` | Define a stable native function component with source/display metadata, HMR behavior, one props/children ABI, and an explicit server policy that defaults to Client-only |
| `n/use-sub` | Read re-frame2 state through the substrate-neutral native React hook in the current frame; do not wrap or import UIx |
| `n/use-frame` | Reuse the existing direct-React frame hook for native component work |
| ABI helpers | Preserve component identity and the boundary marker through memo, lazy loading, refs, and the two same-root embedding directions |

Ordinary React hooks are used directly. Clojure-friendly wrappers are added only when real island code demonstrates repeated ceremony.

### Provisional `n/$` grammar

The authoring shape is deliberately small:

```clojure
(n/$ head)
(n/$ head child*)
(n/$ head literal-props child*)
(n/$ head (n/props dynamic-props) child*)
```

- An unqualified keyword head such as `:div` names an intrinsic React element. A string head names an intrinsic or custom element verbatim. Any other head expression must evaluate to a native React component. Selector shorthand such as `:div.card#main` is not in the v0 native grammar; spell class and id as props.
- The macro treats only `nil`, a literal ClojureScript map, a `#js` object literal, or the explicit `(n/props expression)` marker as the props operand. Every other trailing form is a child. This syntactic rule avoids misclassifying a dynamic React element—which is itself a JavaScript object—as props.
- A JavaScript props object passes by identity. A ClojureScript map is converted shallowly: literal keys are lowered by the macro and a dynamic map inside `n/props` uses the separately reachable native helper. The `n/props` marker emits no component or wrapper.
- ClojureScript-map prop names use the same canonical top-level React slot-name rule as the Hicasso codec: kebab spellings become React camelCase, `:class`/`:for` use the React names, and `data-*`/`aria-*` remain hyphenated. Existing camelCase keywords are fixpoints and string keys pass verbatim. A raw JavaScript object already uses exact React property names and is not renamed. Macro and runtime map conversion share one `.cljc` rule plus parity fixtures; they may not carry copied algorithms.
- Prop values pass by identity. There is no intent lowering, class collection merge, style-map conversion, keyword-value conversion, controlled-field repair, or deep conversion. Use a JavaScript object where a native API expects one, including React style objects and CSS custom properties.
- Children are trailing ReactNode values. Nested elements use nested `n/$`; Hiccup vectors are not interpreted. Collections must already be valid React children, normally a JavaScript array or iterable. `:children` in the props map is refused so there is one child channel.
- `:key` and `:ref` use the ordinary React slots. Two source keys that normalize to the same slot refuse rather than acquire an order-dependent winner.

The provisional `n/defcomponent` ABI is one raw JavaScript props object; React children are available at `.-children`. A declaration map before the argument vector carries the server policy, provisionally `{:server :render}` or `{:server :client-only}`; omission means Client-only. The macro supplies stable top-level component identity, source/display metadata, and HMR conduct, but does not allocate a Clojure map merely to destructure props. A later compile-time destructuring convenience may be considered before the Phase 3 ABI freeze only if it expands to the same object ABI and materially improves real island code.

```clojure
(ns app.hot-row
  (:require [re-frame.hicasso.native :as n]))

(n/defcomponent hot-row
  {:server :render}
  [^js props]
  (let [row-id (.-rowId props)
        label  (n/use-sub [:row/label row-id])]
    (n/$ :button
         {:class       "hot-row"
          :data-row-id row-id
          :on-click    (.-onOpen props)}
         label)))

(n/$ hot-row
     {:row-id row-id
      :on-open (fn [_event] (open-row! row-id))})
```

Here the macro emits the canonical `className`, `data-row-id`, `onClick`, `rowId`, and `onOpen` slots. The callback remains a native function; an event vector in `:on-click` is an error, not Hicasso intent syntax.

## Authoring laws

1. A `defview` is always a boundary; an ordinary `defn` is always inline composition.
2. A body is pure and may run, retry, or be abandoned. Render owns nothing.
3. `sub` is ambient only during the active synchronous body. A helper may donate reads to that boundary; a callback, promise, timer, or lazy escape may not.
4. React owns keys, refs, hooks, effects, errors, concurrency, hydration, and component identity.
5. Event vectors are data. `h/handler` is the one explicit event-producing function form. Ordinary `fn` values retain ordinary JavaScript callback semantics.
6. Controlled fields use app-db, the synchronous controlled-input door, and an explicit revision for identity-preserving reset.
7. Owned control attributes beat forwarded attributes by presence, not truthiness.
8. Host props use an honest ABI: normalize documented HTML-like slots, pass other values by identity, and require explicit `clj->js` for JavaScript option objects.
9. No public option selects an execution mode. Native construction is explicit at the form or component boundary and never changes the meaning of Hiccup.

State ownership is explicit: durable and application-visible ephemera live at addressed re-frame2 locations; high-rate host-private mechanics may live inside a native host; DOM-owned state is an explicit interop choice. No hidden ratom-like tier appears.

## Surface boundaries and exclusions

- Prevention is explicit everywhere; there is no submit-only auto-prevention.
- Callback contracts use literal intents, `h/handler`, and ordinary functions rather than a positional taxonomy.
- Attribute forwarding uses an owned-wins pure merge recipe; a public helper exists only if repeated code warrants it.
- Keep key maps restricted to keyboard event props.
- Validate React refs instead of reserving an unproven vector-ref syntax.
- Presence, forms, routing integration, and overlays live in optional namespaces or recipes; a generic local-state facility is outside the product.
- Do not expose the codec or internal `rfProps` ABI as the outward bridge.
- Do not grow the native namespace into a second component framework, hook library, styling system, or host-schema language.

## Interop contract

`defhost` is the named seam for a foreign component, provider, compound component, retained callback, or server policy. Declared ReactNode props and named content slots lower Hiccup under the captured frame; dev schema/lint validates their positions without deep-converting arbitrary data. A one-off raw element remains available, but repeated or hot use should acquire a name and tests. Render props return Hiccup only through `h/as-element`; otherwise values cross by identity.

Add a thin outward bridge for a native React parent—authored with `n/defcomponent`, UIx, or JavaScript/TypeScript—to render a minted Hicasso view under an existing frame provider. It must retain the memo wrapper, frame isolation, key identity, hydration behavior, and teardown law without creating another root.

## Editor and diagnostic ergonomics

The defining macros capture file, line, and column. Every refusal carries a stable error id, view, source, tree path or host-prop position, offending value, expected shape, and concrete recovery. Publish clj-kondo exports for the syntactic facts that can be checked honestly; optional dev schemas may validate declared view args and host/control props without shipping a general validation framework or production cost. Do not imply whole-program analysis.

## Acceptance examples

The frozen ordinary surface must express a Todo flow, article editor, controlled grid, foreign date picker with a render prop, and virtualized list. Direct `defview` calls, deferred reads, malformed intents, opaque host assertions, native/Hiccup semantic mixing, and unsafe SSR policies fail didactically. Native-island completeness is decided only by the canonical hot-path checklist rather than a second paraphrased gate here.
