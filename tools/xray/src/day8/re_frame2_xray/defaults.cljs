(ns day8.re-frame2-xray.defaults
  "Shared defaults for Xray's registrar surface.

  The dependency-free seam the per-panel `install!` fns (the
  panel-owned registrar blocks) read these Vars through without
  depending on `registry.cljs` — `registry.cljs` requires the
  panel namespaces to call their `install!` fns, so a panel→registry
  edge would form a cycle. This ns is the dependency-free seam.

  The Var is re-exported from `registry.cljs` so callers (the shell,
  tests) can keep reading `registry/default-target-frame` — same
  source of truth, same external surface.")

(def default-frame-id
  "Production singleton frame-id for the Xray SHELL itself.
  Distinct from `default-target-frame` (the OBSERVED host frame): this
  is the frame the shell's OWN app-db lives in — selected tab, focused
  epoch, theme, modal open-state. The single permitted bare `:rf/xray`
  literal in the render-tree; every other
  affordance resolves its frame from React-context or a captured
  dispatcher rather than this literal.

  Testbeds that mount N shells side-by-side pass DISTINCT frame-ids to
  `shell-view`/`ensure-xray-frame!` so each cell's app-db is isolated.
  Lives in this dependency-free seam so the low-level shared widgets
  (`views/resizable-table`) and the shell can both read it without a
  require cycle. Re-exported as `shell/default-frame-id`."
  :rf/xray)

(def default-target-frame
  "The default OBSERVED-target state when no host frame has been
  selected: **`nil` = UNSELECTED**.

  EP-0002 — Xray distinguishes its OWN frame (`default-frame-id`,
  `:rf/xray`, where the shell's chrome state lives) from the
  inspected TARGET frame (the host app frame the scrubber / app-db /
  machine-inspector panels observe). The target frame is NOT defaulted to
  `:rf/default`: `:rf/default` is an ordinary id, never an
  absence-repair fallback (Spec 002 §Frame target resolution).
  The target frame starts UNSELECTED and becomes selected only by:

    - host config (`init! {:target-frame …}` / `set-target-frame!`),
    - the frame picker (`:rf.xray/set-target-frame`), or
    - the mount-time discovery policy (`focusable-head-frame-id` — the
      operator-present interactive tier that uniquely resolves the head
      app cascade's frame; unique resolution, NOT synthesis).

  When none of those select a target the slot stays `nil`; the
  `:rf.xray/target-frame` sub reports `nil` and the panels render their
  unselected-target state (the frame picker prompts a choice) rather than
  reading a synthesised `:rf/default`. `set-target-frame! nil` resets to
  this UNSELECTED state.

  Read via the `:rf.xray/target-frame` sub or written via the
  `:rf.xray/set-target-frame` event; panels that need the host db read
  through `:rf.xray/target-app-db-value` (nil-safe when unselected)."
  nil)
