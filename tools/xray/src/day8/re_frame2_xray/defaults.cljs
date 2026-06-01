(ns day8.re-frame2-xray.defaults
  "Shared defaults for Xray's registrar surface.

  Extracted so per-panel `install!` fns (the panel-owned blocks
  migrated out of `registry.cljs` per rf2-d4xda) can read these Vars
  without depending on `registry.cljs` — `registry.cljs` requires the
  panel namespaces to call their `install!` fns, so a panel→registry
  edge would form a cycle. This ns is the dependency-free seam.

  The Var is re-exported from `registry.cljs` so callers (the shell,
  tests) can keep reading `registry/default-target-frame` — same
  source of truth, same external surface.")

(def default-frame-id
  "Production singleton frame-id for the Xray SHELL itself (rf2-lnluk).
  Distinct from `default-target-frame` (the OBSERVED host frame): this
  is the frame the shell's OWN app-db lives in — selected tab, focused
  epoch, theme, modal open-state. The single permitted bare `:rf/xray`
  literal in the render-tree per the rf2-1w07r EPIC; every other
  affordance resolves its frame from React-context or a captured
  dispatcher rather than this literal.

  Testbeds that mount N shells side-by-side pass DISTINCT frame-ids to
  `shell-view`/`ensure-xray-frame!` so each cell's app-db is isolated.
  Lives in this dependency-free seam so the low-level shared widgets
  (`views/resizable-table`) and the shell can both read it without a
  require cycle. Re-exported as `shell/default-frame-id`."
  :rf/xray)

(def default-target-frame
  "The default host frame Xray observes. Per `:rf/xray` frame
  isolation (spec/008-Embedding-Contract.md §Frame isolation) Xray's
  own state lives in `:rf/xray`; the `:target-frame` slot picks the
  *observed* host frame (default `:rf/default` per Tool-Pair §Frame
  naming — the canonical host frame). Read via the `:rf.xray/target-
  frame` sub or written via the `:rf.xray/set-target-frame` event;
  every panel that needs the host db reads through `:rf.xray/target-
  app-db-value`."
  :rf/default)
