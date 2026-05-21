(ns reagent2.dom
  "Throw-on-call shims for React-19-removed surfaces (rf2-6hyy Stage 4-F).

  Per IMPL-SPEC §10.1 + DECISION-7 (Class B): React 19 removes
  `ReactDOM.render`, `ReactDOM.unmountComponentAtNode`, and React 17's
  internal `findDOMNode`/`forceUpdateAll` machinery. Stock Reagent's
  `reagent.dom/render`, `reagent.dom/unmount-component-at-node`, and
  `reagent.dom/force-update-all` had no React-19 replacement; the slim
  rewrite ships explicit throw-on-call shims so calls into the legacy
  surface fail loudly with a migration message at first invocation.

  The three shims here mirror stock Reagent's `reagent.dom` namespace
  so a `s/reagent\\./reagent2./g` import-site rewrite continues to
  resolve. The body throws an `ex-info` whose canonical `:rf.error/id`
  discriminator (per Spec 009) is `:rf.error/react-19-removed-surface`
  — a single try/catch in a migration helper can match all five Class B
  shims (the two `reagent2.core` shims share the same `:rf.error/id`
  per IMPL-SPEC §10.1).

  Static-analysis friendliness: each shim's body is a single throw, so
  `:advanced` Closure compilation can DCE the symbol when no call site
  reaches it. Apps that import `[reagent2.dom :as rdom]` but never call
  `rdom/render` pay zero runtime cost — the import resolves; the throw
  is unreachable.

  Migration: see migration/from-re-frame-v1/README.md M-42 — legacy mount path. Use
  `reagent2.dom.client/{create-root, render, unmount}` instead.")

;; ---------------------------------------------------------------------------
;; Shim bodies (per IMPL-SPEC §10.1)
;;
;; Migration-message strings are fixed and visible in source so an
;; eyeball-level grep at the call site surfaces the migration target
;; without consulting MIGRATION.md.
;; ---------------------------------------------------------------------------

(defn render
  "REMOVED under React 19. See migration message; throws on first call.

  Use `reagent2.dom.client/create-root` + `reagent2.dom.client/render`
  instead — the React 18+ root API replaces the React 17 legacy mount."
  [& _]
  (throw
    (ex-info
      ":rf.error/react-19-removed-surface"
      {:rf.error/id :rf.error/react-19-removed-surface
       :where       'reagent2.dom/render
       :recovery    :no-recovery
       :reason      "reagent.dom/render is removed under React 19. Use reagent2.dom.client/{create-root, render} instead."
       :surface     'reagent2.dom/render
       :migration   "https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md#legacy-mount-path"})))

(defn unmount-component-at-node
  "REMOVED under React 19. See migration message; throws on first call.

  Use `reagent2.dom.client/unmount` instead."
  [& _]
  (throw
    (ex-info
      ":rf.error/react-19-removed-surface"
      {:rf.error/id :rf.error/react-19-removed-surface
       :where       'reagent2.dom/unmount-component-at-node
       :recovery    :no-recovery
       :reason      "reagent.dom/unmount-component-at-node is removed under React 19. Use reagent2.dom.client/unmount instead."
       :surface     'reagent2.dom/unmount-component-at-node
       :migration   "https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md#legacy-mount-path"})))

(defn force-update-all
  "REMOVED under React 19. See migration message; throws on first call.

  No replacement — the stock impl iterated React 17 internals. If you
  hit a real use case, file an issue at
  https://github.com/day8/re-frame2/issues."
  []
  (throw
    (ex-info
      ":rf.error/react-19-removed-surface"
      {:rf.error/id :rf.error/react-19-removed-surface
       :where       'reagent2.dom/force-update-all
       :recovery    :no-recovery
       :reason      "reagent.dom/force-update-all is removed (it iterated React 17 internals). If you have a legitimate use case, file an issue at https://github.com/day8/re-frame2/issues."
       :surface     'reagent2.dom/force-update-all
       :migration   "https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md#legacy-mount-path"})))
