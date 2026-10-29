(ns day8.re-frame2-xray.defaults
  "Shared defaults for Xray's registrar surface.

  The dependency-free seam the per-panel `install!` fns (the
  panel-owned registrar blocks) read these Vars through without
  depending on `registry.cljs` — `registry.cljs` requires the
  panel namespaces to call their `install!` fns, so a panel→registry
  edge would form a cycle. This ns is the dependency-free seam.

  The Var is re-exported from `registry.cljs` so callers (the shell,
  tests) can read `registry/default-target-frame` — same
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

(defonce ^:private instance-frames
  ;; Every own frame a shell or panel mount seated (`mount/ensure-seated!`,
  ;; `mount/ensure-xray-frame!`).
  (atom #{}))

(defn note-instance-frame!
  "Record `frame-id` as one of Xray's own frames, so the trace and epoch
  mirrors deliver into it as well as into `default-frame-id`."
  [frame-id]
  (swap! instance-frames conj frame-id)
  nil)

(defn forget-instance-frames!
  "Drop every recorded own frame. Test fixtures that wipe the frame
  registry call it through `mount/reset-for-test!`."
  []
  (reset! instance-frames #{})
  nil)

(defn instance-frame-ids
  "Xray's own frames: `default-frame-id` and every frame a mount seated. The
  mirror drains (`install/drain-epoch-frames!`, the trace-buffer sync, the
  suppressed-count drain) dispatch into each one that is still registered,
  so a second shell on its own frame keeps updating."
  []
  (conj @instance-frames default-frame-id))

(defn close-event-for
  "The event the chrome `✕` dispatches for click `e`: `:rf.xray/close-popout`
  when the click landed inside the pop-out window's shell (its root carries
  `data-rf-xray-mode=\"popout\"`), else `:rf.xray/close-shell`. A pop-out
  action never changes the opener's mount state (tools/xray/spec/011
  §Pop-out), so the two surfaces close through different events."
  [e]
  (if (some-> e .-currentTarget (.closest "[data-rf-xray-mode=\"popout\"]"))
    :rf.xray/close-popout
    :rf.xray/close-shell))

(def default-target-frame
  "The default OBSERVED-target state when no host frame has been
  selected: **`nil` = UNSELECTED**.

  EP-0002 — Xray distinguishes its OWN frame (`default-frame-id`,
  `:rf/xray`, where the shell's chrome state lives) from the
  inspected TARGET frame (the host app frame the scrubber / app-db /
  machine-inspector panels observe). The target frame is NOT defaulted to
  `:rf/default`: `:rf/default` is the conventional app-frame id, never an
  absence-repair fallback (Spec 002 §Frame target resolution).
  The target frame starts UNSELECTED. What may take it out of that state
  is a RULE, not a list of sources: a selected target is either an
  EXPLICIT CHOICE or a frame RESOLVED FROM OBSERVED EVIDENCE — never a
  synthesised one.

    - EXPLICIT CHOICE — host config (`init! {:target-frame …}` /
      `set-target-frame!`); the frame picker, from the ribbon dropdown or
      the Cmd-K palette (both via `:rf.xray/select-frame`); and the
      operator's focus gestures over the L2 list and Epoch panel, which
      adopt the navigated event-bundle's or epoch's own frame
      (`spine/reseed-epoch-history-for-frame`). May RE-target an
      already-selected slot.
    - RESOLVED FROM OBSERVED EVIDENCE — the mount-time discovery
      policy (`focusable-head-frame-id`, the operator-present interactive
      tier that uniquely resolves the head app event-bundle's frame) and
      epoch-ingest adoption (`:rf.xray/epoch-recorded` adopting the frame
      that actually RECORDED; Xray's own `:rf/xray` excluded). Unique
      resolution, NOT synthesis, and fires ONLY while the slot is still
      UNSELECTED.

  Those are the paths that exist today, not a closed list — judge a new
  one by the rule (Xray spec 008 §Own frame vs target frame).
  When nothing selects a target the slot stays `nil`; the
  `:rf.xray/target-frame` sub reports `nil` and the panels render their
  unselected-target state (the frame picker prompts a choice) rather than
  reading a synthesised `:rf/default`. `set-target-frame! nil` resets to
  this UNSELECTED state.

  Read via the `:rf.xray/target-frame` sub or written via the
  `:rf.xray/set-target-frame` event; panels that need the host db read
  through `:rf.xray/target-app-db-value` (nil-safe when unselected)."
  nil)
