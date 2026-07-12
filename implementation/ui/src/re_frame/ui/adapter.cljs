(ns re-frame.ui.adapter
  "The compiled-view substrate's adapter-side touchpoint (rf2-vxgfnd.24).

  ## Why this ns exists

  `re-frame.ui.frames/resolve-frame` reads the shared React frame context
  (`re-frame.adapter.context/frame-context`) DIRECTLY, so frame-scoped
  operations that route through it already see the `frame-provider` /
  `frame-root` scope. But the compiled view SUB-READ path does not go
  through that primitive: `re-frame.ui.reactive/sub-read` →
  `re-frame.substrate.observation/resolve-target` →
  `re-frame.frame/require-current-frame!` → `resolve-current-frame`, which
  consults the `:adapter/current-frame` late-bind hook for the React-context
  tier (Spec 006 §Frame-provider via React context). In a PURE compiled-ui
  app — one running on the plain-atom reactive adapter with no
  Reagent/UIx/Helix adapter loaded — that hook has no publisher, so a
  mounted `(sub …)` under a provider resolved the dynamic tier only and
  raised `:rf.error/no-frame-context`.

  ## The publish (Mike-ruled mechanism (a), 2026-07-12)

  This ns publishes the SAME React-context reader UIx/Helix publish —
  `re-frame.adapter.context/function-component-current-frame` — through the
  SAME `:adapter/current-frame` hook, so core's `resolve-current-frame`
  reaches the context tier on the compiled-view sub-read path. There is ONE
  resolution order (frames.cljc §The ambient frame chain); this reuses the
  shared reader rather than duplicating it.

  Homed in the ui artefact (not core's `plain-atom`) because the reader is a
  React-context read — the React dependency belongs with the substrate that
  renders React, keeping plain-atom's headless CLJS bundle React-free.

  ## Routing, not clobbering

  Published via `substrate-adapter/route-hook!` against the plain-atom
  adapter spec — exactly the mechanism UIx/Helix/test-react (and plain-atom
  itself, for `:adapter/*` disposal) use. The pure compiled-view runtime
  runs on the plain-atom reactive adapter (rf2-uatcy), so the reader fires
  when — and only when — plain-atom is the `(rf/init!)`-installed adapter.
  A React adapter installed instead routes to its OWN impl through the same
  chain (`same-adapter?` gating, rf2-dkl5z1): this publisher is ADDITIVE and
  behaviour-preserving for the classic adapters, in any ns-load order.

  MIXED-app arbitration — a compiled-view app that ALSO installs a classic
  React adapter, where the single hook slot has two candidate publishers —
  is out of scope here (rf2-3yij); this is the pure compiled-view publish."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; The React-context-tier frame-id reader for the pure compiled-view runtime.
;; Routed against plain-atom so `resolve-current-frame` sees the context tier
;; when plain-atom is installed (the pure-ui case); the classic-adapter chain
;; is untouched. Fallback `#(frame/current-frame)` mirrors the UIx/Helix
;; routing — the dynamic-var tier when neither this nor a chained handler runs.
(substrate-adapter/route-hook! plain-atom/adapter :adapter/current-frame
  adapter-context/function-component-current-frame
  #(frame/current-frame))
