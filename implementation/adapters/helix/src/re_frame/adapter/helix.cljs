(ns re-frame.adapter.helix
  "The Helix adapter — third canonical browser substrate, targeting the
  Helix 0.2.x line. Per Spec 006 §CLJS reference: Helix as alternative
  substrate. Ships in `day8/re-frame2-helix`; dependency flows adapter
  → core.

  Shares the React-shaped substrate machinery (container quartet,
  derived value, render-root, render-to-string, frame-provider, use-
  subscribe, flush-views!, source-coord wrapper, warn-once-cache) with
  the UIx adapter via `re-frame.substrate.spine`. Helix-specific
  configuration: gensym prefixes, substrate name (used in warn-once
  text), and helix.hooks `use-memo*` / `use-callback*` (the spine
  passes JS-array deps unconditionally). The React frame-context
  comes from `re-frame.adapter.context` so a mixed-substrate app's
  frame-provider chain composes across substrates."
  (:require [helix.core          :refer-macros [defnc]]
            [helix.hooks         :as helix-hooks]
            [re-frame.substrate.spine   :as spine]
            [re-frame.views.owned-frame :as owned-frame]))

;; ---- shared spine wiring --------------------------------------------------

(def ^:private spine-fns
  (spine/make-react-spine
    {:substrate-name        "Helix"
     :gensym-prefix-sub     "rf-helix-sub-"
     :gensym-prefix-derived "rf-helix-derived-"
     :gensym-prefix-use-sub "rf-helix-use-sub-"
     :use-memo              helix-hooks/use-memo*
     :use-callback          helix-hooks/use-callback*
     :use-context           helix-hooks/use-context}))

;; ---- public surface (Helix-named) -----------------------------------------

(def set-hiccup-emitter!
  "Install a render-tree → HTML fn for use by render-to-string. Idempotent.
  Helix itself doesn't render to string in browser bundles; SSR consumers
  install the hiccup emitter explicitly (mirroring the Reagent and UIx
  adapters)."
  (:set-hiccup-emitter! spine-fns))

(def use-current-frame
  "Helix hook returning the current frame keyword from the surrounding
  React context, or the no-provider sentinel
  (`re-frame.adapter.context/no-provider-sentinel`) when no frame-provider
  sits above — NOT `:rf/default` (EP-0002 carried invariant: the React-
  context default is a no-provider sentinel, never a synthesised default).
  Decision 2 mandates every React-shaped adapter resolves the same
  context, so a Helix subtree under a Reagent or UIx frame-provider
  sees the right frame and vice versa.

  React-context tier only — this is the NARROW raw `useContext` read; it
  does not map the sentinel to nil. For the full resolution chain
  (dynamic-var → React-context → nil) use `(rf/current-frame-id)`; the
  routed `:adapter/current-frame` hook (registered via
  `spine/make-react-adapter`) covers that chain and returns nil (not
  `:rf/default`) when no scope is established, so a public frame-scoped op
  fails loudly with `:rf.error/no-frame-context`. Per rf2-84myk."
  (:use-current-frame spine-fns))

(defnc frame-provider
  "User-facing component — the UI-OWNED frame LIFECYCLE boundary (EP-0024
  §Scope, carry, and ownership). It CREATES a frame on mount, PROVIDES its
  id to descendants, and DESTROYS it on unmount. NOT a scope-only component
  — scoping descendants to a frame that ALREADY EXISTS is
  `frame-provider-existing` (scope-into-React) or `rf/with-frame` (lexical).
  Helix call shape — the idiomatic `$` TRAILING-CHILDREN form, identical to
  every other Helix component and mirroring Reagent's trailing-hiccup mental
  model (rf2-7kii2):

      ($ frame-provider {:id :session :images [session-image] :initial-db {}}
         ($ header)
         ($ main))

  Children ride the native `$` trailing-args channel; there is no
  `:children` prop-map key to remember (forgetting it used to drop the
  subtree silently — that footgun is gone by construction). A single
  child works too: `($ frame-provider {:id :session} ($ app))`.

  `frame-provider` OWNS a frame lifetime, so it takes the same `:id` /
  `:images` / `:initial-db` (plus record-config) opts as `rf/make-frame`,
  runs that one constructor on mount, and tears the frame down on unmount.
  `:id` is REQUIRED and must be a KEYWORD: a missing / nil / non-keyword
  `:id` is a CONFIGURATION ERROR — the owned-frame core emits + throws
  `:rf.error/owned-frame-provider-missing-id` (a `:frame` key with no `:id`
  is the common mistake and trips this guard — switch to `:id`, or use
  `frame-provider-existing {:frame …}` to merely scope). The three
  React-shaped adapters share one React Context (per rf2-2qit Decision 2)
  so a subtree under any frame-provider sees the right frame regardless of
  which substrate rendered the provider.

  Idempotent re-mount (hot reload / React StrictMode dev double-invoke /
  Story re-evaluation): re-mounting under the same `:id` MUST NOT destroy
  durable state — `make-frame` is idempotent replacement and the
  destroy-on-unmount is DEFERRED + cancelled by a re-acquire (see
  `re-frame.views.owned-frame`).

  Native shell above the prop-marshalling seam (rf2-z7hfp — Mike-ruled
  C, MOVE THE SEAM UP). This is a NATIVE Helix `defnc` component, NOT a
  re-export of a shared fn handed to `$`. Because it is a real `defnc`,
  Helix's `$` routes its props through `extract-cljs-props`, which beans the
  JS object back into a CLJS map with KEYWORD keys (and Helix's `-props`
  preserves keyword VALUES — only keys are stringified) AND lifts the native
  trailing children onto the `:children` key (Helix's `$`/`extract-cljs-
  props` contract: `($ Comp props c1 c2)` arrives as `{... :children #js [c1
  c2]}`). So the props destructure cleanly with namespaces intact. The body
  delegates to the substrate-agnostic owned-frame core
  `owned-frame-react-element` (`:id` validation + lifecycle element-build).
  The prop-mangling class — Helix's `$` handing a plain fn a raw JS object
  with string keys — is impossible by construction: there is no plain fn
  under `$` for the element macro to mangle."
  [props]
  (owned-frame/owned-frame-react-element
    (dissoc props :children)
    (:children props)
    're-frame.adapter.helix/frame-provider))

(defnc frame-provider-existing
  "User-facing SCOPE-ONLY component (EP-0024 §Scope, carry, and ownership).
  It provides an ALREADY-CREATED frame's id to descendants via the shared
  React context and creates / refreshes / destroys NOTHING — the frame is
  owned elsewhere. The scope-INTO-React counterpart to `rf/with-frame` (a
  dynamic var cannot cross React's render boundary). Helix call shape — the
  idiomatic `$` TRAILING-CHILDREN form:

      ($ frame-provider-existing {:frame :session}
         ($ header)
         ($ main))

  `:frame` is REQUIRED (EP-0002 carried invariant) and must be a KEYWORD
  frame id. A missing or `nil` `:frame` is a CONFIGURATION ERROR: the spine
  core `build-frame-provider-element` emits `:rf.error/no-frame-context` and
  throws rather than synthesising `:rf/default` (Spec 002 §Frame target
  resolution). A non-nil but non-keyword `:frame` emits the distinct
  `:rf.error/bad-frame-provider-arg` and throws (rf2-9kpigo). A
  frame-CONSTRUCTION / lifecycle opt (`:id` / `:images` / `:initial-db` /
  `:on-create` / …) is a MISUSE: the owned-frame core emits + throws
  `:rf.error/frame-provider-existing-lifecycle-opt` pointing the caller at
  the OWNED `frame-provider`, because a scope-only provider neither creates
  nor owns a frame.

  Native shell above the prop-marshalling seam (rf2-z7hfp): a real `defnc`,
  so Helix's `$` reconstructs the lossless CLJS props map; the body rejects
  lifecycle opts then delegates the clean `:frame` + children to the
  substrate-agnostic spine core `build-frame-provider-element` (the
  scope-only provide tier)."
  [props]
  (owned-frame/reject-lifecycle-opts!
    (dissoc props :children)
    're-frame.adapter.helix/frame-provider-existing)
  (spine/build-frame-provider-element (:frame props) (:children props)))

(def use-subscribe
  "Helix hook that reads a re-frame subscription. Returns the current
  value; re-renders the calling component when the value changes.

  Frame resolution: reads the surrounding frame-provider's keyword via
  `use-context` (rf2-2qit Decision 2). Override via the 2-arg form to
  pin to an explicit frame-id.

  Per rf2-2qit Decision 1 the hook is named `use-subscribe` to match
  the React/Helix idiom — symmetric ergonomics to Reagent's
  `(rf/subscribe ...)` deref shape, asymmetric naming (hooks live in
  hook-named space)."
  (:use-subscribe spine-fns))

(def flush-views!
  "Flush pending Helix renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act f); with no arg, calls (act
  (fn [] nil)) to flush pending effects. Returns nil. Resolves React's
  act() across React 18 (in `react-dom/test-utils`) and React 19 (on
  the React namespace directly).

  Per rf2-2qit Decision 6: the canonical test-flush hook for
  Helix-based apps."
  (:flush-views! spine-fns))

(def wrap-view
  "Wrap a Helix-shape user component in a function component that
  injects `data-rf2-source-coord` on the rendered root DOM element
  (when `interop/debug-enabled?` is true). Returned fn has the same
  call signature as `user-fn` and is suitable for use as a Helix
  component head. Production builds elide via `interop/debug-enabled?`
  per Spec 009 §Production builds."
  (:wrap-view spine-fns))

(def ^:no-doc clear-warned-non-dom-roots!
  "Reset the warn-once cache for non-DOM-root warnings. Tests use this
  between cases (via `make-reset-runtime-fixture` and the chained
  `:adapter/clear-warn-once-caches!` hook) so a sibling test's
  first-encounter warning cannot silently swallow a later test's same-id
  warning. Per rf2-4edk."
  (:clear-warned-non-dom-roots! spine-fns))

;; ---- adapter Var ----------------------------------------------------------

(def adapter
  "The Helix adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.helix :as helix])
      (rf/init! helix/adapter)

  See Spec 006 §CLJS reference: Helix as alternative substrate.
  Implements the same adapter contract as re-frame.adapter.reagent
  and re-frame.adapter.uix (6 required + 3 optional + 1 lifecycle fn).
  Per rf2-agql there is no default-adapter registry — adapter wiring
  is explicit at the call site.

  Assembled by `spine/make-react-adapter` (rf2-ee38b.1): the substrate
  map, the five React-hook `route-hook!` calls, and the two
  chained installs (warn-once clear + SSR emitter) all live once in the
  spine — the Helix and UIx adapter wiring is byte-identical, so this
  single call replaces the former hand-copied block (which had drifted in
  prose against the UIx twin). The per-hook rationale lives at
  `spine/make-react-adapter`.

  The native `frame-provider` `defnc` component (rf2-z7hfp) is passed in
  as `:frame-provider` so the spine wires it into the
  `:register-context-provider` substrate slot — the component shell lives
  in this ns, above where Helix's `$` marshals props; the spine carries
  no Helix element-macro dependency."
  (spine/make-react-adapter spine-fns
                            {:kind           :rf.adapter/helix
                             :frame-provider frame-provider}))
