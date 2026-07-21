(ns re-frame.ui.react
  "The frozen `re-frame.ui.react` interop tier — six wrappers (Spec 004
  §The React interop tier) for compiled views that must participate in a
  *foreign* React world: an exported `defview` living inside a legacy/foreign
  parent (`ui/->react`), or a foreign widget embedded inside a `defview` whose
  API demands contexts, ids, effects, or code-splitting.

  This is NOT a second state or reactivity model. The roster is deliberately
  tiny and closed: `use-effect`, `use-layout-effect`, `use-effect-event`,
  `use-context`, `use-id`, `lazy`. The everyday DOM-node ref is NOT here — it is
  the substrate-native `re-frame.ui/ref` (promoted out of this tier, rf2-u53yy.9;
  its lowering target stays `re-frame.ui.hooks/use-ref`). `use-state` (that is
  `local`), `use-memo`/`use-callback` (per-site stable identity is the
  compiler's job), `use-reducer`/`use-sync-external-store`/`use-transition`/
  `use-deferred-value` (a second state model / `startTransition` over app-db)
  do NOT exist.

  Five of the six (`use-effect` … `use-id`) are HOST HOOKS: the compiler
  recognises their call sites by resolved head symbol (the same finite-site
  machinery as `sub`/`local`/`effect`/`ui/ref`), lowers them to
  `re-frame.ui.hooks/*`, and enforces the POSITION LAW — a hook site is legal
  only where it evaluates unconditionally, exactly once per render (the
  straight-line top region of a view body: outer `let` bindings and positions
  reachable without crossing a control form, a fn form, or a loop). React's hook
  order must be static.

  `lazy` is a DEF-LEVEL constructor (a macro), exempt from the position law but
  subject to its own: a `react/lazy` call inside a view body/template is a
  compile error, because calling it per render mints a new component type and
  remount-loops.

  Second sanctioned audience (readiness C-6): `use-layout-effect` — paired with
  the substrate `ui/ref` — serves native component-library infrastructure doing
  measure-before-paint (popover/dropdown placement, table viewport geometry):
  measure in the layout effect, compute placement with pure `.cljc` geometry,
  apply, clean up exactly. Passive `use-effect` stays the home for listeners and
  deferrable work.

  The five hook vars exist for symbol resolution only; a direct call fails
  loudly. The compiler rewrites the authored form to the lowered runtime call.

  The OUTWARD bridge `ui/->react` (rf2-u53yy.2) — a compiled view exported as a
  React component for a foreign React/UIx/Helix parent — is the outward
  counterpart to `ui/raw`. Its public authoring name is `re-frame.ui/->react`
  and its CLJS runtime lives in `re-frame.ui.runtime/->react-component` (kin to
  the other facade/emitter runtime helpers); it is not part of this resolution
  tier's `react/*` call surface."
  (:refer-clojure :exclude [lazy])
  #?(:cljs (:require-macros [re-frame.ui.react]))
  (:require [re-frame.error :as error]
            [re-frame.ui.hooks]
            #?(:clj [re-frame.ui.compiler :as compiler])))

(defn- lowering-only! [name* shape]
  (error/throw-error!
   :rf.error/ui-tree-malformed name*
   (str "(" name* " …) executed outside compiler lowering — it is a lexical "
        "re-frame.ui.react interop form recognised inside a compiled view, not "
        "a callable helper. Author it as " shape " in a defview's unconditional "
        "top region")
   nil))

(defn use-effect
  "(react/use-effect setup) / (react/use-effect setup deps) → nil (`useEffect`,
  passive, after paint). `setup` is a zero-arg fn returning a cleanup fn or nil,
  honoured on dep change, disconnect, and unmount; StrictMode dev replay is
  expected and MUST be idempotent-safe — the `effect` contract in a foreign
  spelling. One-arg form runs after every commit; a `deps` vector is compared
  per authored slot by `rf=` (the ONE effect-dependency equality doctrine the
  native `effect` tier and the memo/prop comparator share — value semantics, so
  a distinct-but-`rf=`-equal CLJS deps value does not re-run). `[]` deps ⇒
  connect-only.

  This var exists for symbol resolution only; a direct call fails loudly."
  ([_setup] (lowering-only! 're-frame.ui.react/use-effect
                            "(let [_ (react/use-effect setup)] …)"))
  ([_setup _deps] (lowering-only! 're-frame.ui.react/use-effect
                                  "(let [_ (react/use-effect setup deps)] …)")))

(defn use-layout-effect
  "(react/use-layout-effect setup) / (… setup deps) → nil (`useLayoutEffect`,
  after DOM mutation, BEFORE paint) for measure-then-mutate work that would
  flicker under passive timing — the component-library measure-before-paint
  door (readiness C-6). Observes the committed frame, never a mid-commit state.
  Same setup/cleanup/per-slot-`rf=`-deps contract as `use-effect`.

  This var exists for symbol resolution only; a direct call fails loudly."
  ([_setup] (lowering-only! 're-frame.ui.react/use-layout-effect
                            "(let [_ (react/use-layout-effect setup)] …)"))
  ([_setup _deps] (lowering-only! 're-frame.ui.react/use-layout-effect
                                  "(let [_ (react/use-layout-effect setup deps)] …)")))

(defn use-effect-event
  "(react/use-effect-event f) → fn (`useEffectEvent`, React 19.2). The returned
  fn's body always sees the latest render's values; its identity is NOT stable
  (React allocates a fresh function every render — do not rely on stability).
  Takes NO deps. The returned fn MUST NOT appear in any deps vector and MUST NOT
  be called during render (host throws — it is effect-phase machinery). App
  intent still goes through event vectors / `ui/event`; this is for the foreign
  pattern of a latest-values callback invoked from an effect.

  This var exists for symbol resolution only; a direct call fails loudly."
  [_f] (lowering-only! 're-frame.ui.react/use-effect-event
                       "(let [on-x (react/use-effect-event f)] …)"))

(defn use-context
  "(react/use-context ctx) → the context's current value (`useContext`). `ctx`
  is a FOREIGN Context object (`React.createContext` in foreign code, an opaque
  foreign value); the value is handed through uncoerced. Internal state never
  rides React context — frames have `frame-root`/`frame-provider`, app state
  has `sub`; this reads the foreign world's providers (theme, i18n, router).

  This var exists for symbol resolution only; a direct call fails loudly."
  [_ctx] (lowering-only! 're-frame.ui.react/use-context
                         "(let [v (react/use-context ctx)] …)"))

(defn use-id
  "(react/use-id) → string (`useId`): a host-generated tree-positional token
  prefixed by the root's `identifierPrefix`. Determinism under SSR rides the
  root contract (a hydrating root takes the server's prefix from the manifest).
  On the JVM structural render it yields a deterministic inert string (prefix +
  per-render occurrence counter) — a value serialised into a *hydrating* root's
  markup is a mismatch risk; static roots are safe.

  This var exists for symbol resolution only; a direct call fails loudly."
  [] (lowering-only! 're-frame.ui.react/use-id "(let [id (react/use-id)] …)"))

#?(:clj
   (defmacro lazy
     "(react/lazy load-thunk) / (react/lazy load-thunk {:fallback tpl}) — DEF
     LEVEL only. `React.lazy` over a foreign component-loading `load-thunk` (a
     zero-arg fn returning a Promise of a foreign component). The result is a
     foreign component reference, legal exactly where foreign heads are:
     `[HeavyChart {…}]`. Code-splitting interop for FOREIGN components, not a
     loading-orchestration surface (loading state in re-frame2 is explicit
     application state — `sub` over resource state).

     `{:fallback tpl}` declares per-site loading content: a capability-free
     template the emitter renders inside a minimal host Suspense boundary
     wrapping that one foreign element. Without a `:fallback`, `React.lazy`'s
     own rule applies — a Suspense boundary must exist somewhere above.

     A `react/lazy` call inside a view body/template is a compile error."
     [& args]
     (compiler/expand-lazy &form &env args)))
