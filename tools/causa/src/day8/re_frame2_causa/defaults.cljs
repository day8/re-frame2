(ns day8.re-frame2-causa.defaults
  "Shared defaults for Causa's registrar surface.

  Extracted so per-panel `install!` fns (the panel-owned blocks
  migrated out of `registry.cljs` per rf2-d4xda) can read these Vars
  without depending on `registry.cljs` — `registry.cljs` requires the
  panel namespaces to call their `install!` fns, so a panel→registry
  edge would form a cycle. This ns is the dependency-free seam.

  The Vars are re-exported from `registry.cljs` so callers (the
  shell, tests) can keep reading `registry/default-panel-id` /
  `registry/default-target-frame` — same source of truth, same
  external surface.")

(def default-panel-id
  "The hero panel — `:event-detail` — is Causa's default landing per
  spec/007-UX-IA.md §The default landing view + §10 Lock 7. Exposed
  as a Var so the shell and tests share the source of truth."
  :event-detail)

(def default-target-frame
  "The host frame Causa's time-travel scrubber inspects by default.
  Per spec/002-Time-Travel.md §Cross-frame scrubbing the scrubber is
  per-frame — once a frame picker ships it lets the user pick a
  different host frame. Until then the scrubber is hard-bound to
  :rf/default — the canonical host frame per Tool-Pair §Frame
  naming.

  Note this is the *host*'s frame, not :rf/causa — Causa's own
  state (selection, pin store) lives in :rf/causa via the shell's
  frame-provider; the *target* of restore-epoch / reset-frame-db! is
  the host's :rf/default."
  :rf/default)
