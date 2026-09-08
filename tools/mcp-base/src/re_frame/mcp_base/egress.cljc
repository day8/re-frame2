(ns re-frame.mcp-base.egress
  "Cross-MCP `:rf.egress/*` profile vocabulary (EP-0015 §10).

  ## Why this ns

  EP-0015 defines the named-egress model: an off-box surface chooses
  *which boundary is this?* — a named `:rf.egress/*` profile — not *which
  combination of `:rf.size/*` booleans did I remember?*. The framework
  owns the authoritative resolution in `re-frame.projection/project-egress`
  (`implementation/core`), but that namespace transitively requires the
  framework runtime graph (`re-frame.elision` → `re-frame.frame` →
  substrate adapter, …). The shared base must not depend on that graph:

  - re-frame2-pair-mcp renders a PROFILE NAME into an nREPL eval form and
    the app-side `project-egress` resolves it, so the Node server bundle
    never gains the framework graph.
  - story-mcp names the same profile in-process. Sharing the posture→
    profile mapping keeps the two server paths aligned despite their
    different hosts.

  This ns therefore carries the profile NAME SET and the posture→profile
  mapping — and NOT a resolution table. rf2-kuky.88 deleted the pure-data
  mirror of the framework's `:rf.size/*` floor: a server that names a
  profile has no reason to resolve it, and the mirror was a second place
  the §10 default-behaviour table could drift. The mcp-conformance
  wire-vocab gate still pins this NAME SET equal to
  `re-frame.projection/profiles`, so a profile added or renamed in the
  framework that does not land here fails the gate.

  ## The six profiles (EP-0015 §10, CLOSED enum)

  The authoritative `:rf.size/*` floor each one resolves to lives in the
  framework (`re-frame.projection`'s §10 default-behaviour table) and is
  applied by `re-frame.core/project-egress` at the app-side door. The one
  distinction a server needs to know when choosing a name:
  `:rf.egress/off-box-tool` is the boundary that carries structural
  digests; `:rf.egress/local-raw` is the trusted-local boundary that opts
  sensitive AND large back in.

  | Profile |
  |---|
  | `:rf.egress/off-box-observability` |
  | `:rf.egress/off-box-tool` |
  | `:rf.egress/local-redacted` |
  | `:rf.egress/local-raw` |
  | `:rf.egress/ssr-hydration` |
  | `:rf.egress/public-error` |

  Cross-platform: pure data + one pure fn; loads identically into JVM
  (story-mcp) and CLJS (re-frame2-pair-mcp). No transport, no runtime, no
  framework dep.")

(def profiles
  "The closed six-member `:rf.egress/profile` vocabulary (EP-0015 §10).
  Mirror of `re-frame.projection/profiles` — pinned value-for-value by the
  mcp-conformance wire-vocab gate."
  #{:rf.egress/off-box-observability
    :rf.egress/off-box-tool
    :rf.egress/local-redacted
    :rf.egress/local-raw
    :rf.egress/ssr-hydration
    :rf.egress/public-error})

(defn mcp-tool-profile
  "Map an MCP tool server's already-permission-gated sensitive-read
  posture to its named `:rf.egress/*` boundary profile (EP-0015 §10).

  `sensitive-reads-allowed?` is the boolean each server computes AFTER
  its own operator-launch gate (`--allow-sensitive-reads`) AND per-call
  `:include-sensitive` opt-in. This fn performs NO permission check — it
  is the pure two-value posture→profile mapping that the two MCP tool
  servers (story-mcp, re-frame2-pair-mcp) share:

  - `false` (the published-build default, or a caller under the gate who
    did not opt in) ⇒ `:rf.egress/off-box-tool` — the MCP/AI tool wire:
    sensitive redacts, large elides, structural digests on.
  - `true` (the trusted-local operator's deliberate raw read) ⇒
    `:rf.egress/local-raw` — sensitive AND large pass through.

  Both servers previously duplicated this exact `if` (story-mcp's
  `tools.egress/posture->profile`, pair-mcp's
  `tools.elision/posture->profile`); it lives here once so the two
  cannot drift. A caller NAMES the returned profile in the opts it hands
  `re-frame.core/project-egress`; the app-side door resolves it to the
  `:rf.size/*` floor (rf2-kuky.88 — the server never resolves it itself)."
  [sensitive-reads-allowed?]
  (if sensitive-reads-allowed?
    :rf.egress/local-raw
    :rf.egress/off-box-tool))
