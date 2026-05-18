(ns day8.re-frame2-causa.defaults
  "Shared defaults for Causa's registrar surface.

  Extracted so per-panel `install!` fns (the panel-owned blocks
  migrated out of `registry.cljs` per rf2-d4xda) can read these Vars
  without depending on `registry.cljs` — `registry.cljs` requires the
  panel namespaces to call their `install!` fns, so a panel→registry
  edge would form a cycle. This ns is the dependency-free seam.

  The Var is re-exported from `registry.cljs` so callers (the shell,
  tests) can keep reading `registry/default-target-frame` — same
  source of truth, same external surface.")

(def default-target-frame
  "The default host frame Causa observes. Per `:rf/causa` frame
  isolation (spec/008-Embedding-Contract.md §Frame isolation) Causa's
  own state lives in `:rf/causa`; the `:target-frame` slot picks the
  *observed* host frame (default `:rf/default` per Tool-Pair §Frame
  naming — the canonical host frame). Read via the `:rf.causa/target-
  frame` sub or written via the `:rf.causa/set-target-frame` event;
  every panel that needs the host db reads through `:rf.causa/target-
  frame-db`."
  :rf/default)
