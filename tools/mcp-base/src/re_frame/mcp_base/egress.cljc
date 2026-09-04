(ns re-frame.mcp-base.egress
  "Cross-MCP `:rf.egress/*` profile vocabulary + a pure-data resolver
  (EP-0015 §10).

  ## Why this ns

  EP-0015 defines the named-egress model: an off-box surface chooses
  *which boundary is this?* — a named `:rf.egress/*` profile — not *which
  combination of `:rf.size/*` booleans did I remember?*. The framework
  owns the authoritative table in `re-frame.projection/profile-size-opts`
  (`implementation/core`), but that namespace transitively requires the
  framework runtime graph (`re-frame.elision` → `re-frame.frame` →
  substrate adapter, …). The shared base must not depend on that graph:

  - re-frame2-pair-mcp renders the resolved opts into an nREPL eval form
    without adding the framework graph to its Node server bundle.
  - story-mcp applies the same opts in-process. Sharing the pure table
    keeps the two server paths aligned despite their different hosts.

  This ns is the cross-MCP, framework-runtime-free mirror of the six-
  member closed profile enum and its `:rf.size/*` floor. It is pure data
  over the keys `re-frame.mcp-base.vocab` already owns
  (`:rf.size/include-sensitive?` / `:rf.size/include-large?` /
  `:rf.size/include-digests?`). The mcp-conformance wire-vocab gate pins
  this table value-for-value equal to the framework enum so the two cannot
  drift (a profile rename / opt-set change in the framework that does not
  land here fails the gate).

  ## The six profiles (EP-0015 §10, CLOSED enum)

  | Profile | `:rf.size/*` floor |
  |---|---|
  | `:rf.egress/off-box-observability` | sensitive redact, large elide, no digests |
  | `:rf.egress/off-box-tool`          | sensitive redact, large elide, digests ON (structural indicators) |
  | `:rf.egress/local-redacted`        | sensitive redact, large elide, no digests |
  | `:rf.egress/local-raw`             | sensitive AND large pass through |
  | `:rf.egress/ssr-hydration`         | sensitive redact, large elide, no digests |
  | `:rf.egress/public-error`          | sensitive redact, large elide, no digests |

  Cross-platform: pure-data table; loads identically into JVM (story-mcp)
  and CLJS (re-frame2-pair-mcp). No transport, no runtime, no framework
  dep."
  (:require [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

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

(def profile->size-opts
  "Resolve each `:rf.egress/*` profile to its `:rf.size/*` opt-set —
  the pure-data mirror of `re-frame.projection`'s private
  `profile->size-opts` table (EP-0015 §10 default-behaviour table). The
  mcp-conformance wire-vocab gate asserts this map equals the framework
  table value-for-value, so the two cannot drift.

  `:rf.egress/off-box-tool` is the only off-box profile that turns
  `:rf.size/include-digests?` ON — the §10 \"include structural
  indicators / counters so the tool can reason about shape without
  seeing content\" clause. `:rf.egress/local-raw` is the only profile
  that opts sensitive AND large back in (the trusted-local boundary)."
  {:rf.egress/off-box-observability
   {rf.mcp-base.vocab/include-sensitive-opt false
    rf.mcp-base.vocab/include-large-opt      false
    rf.mcp-base.vocab/include-digests-opt    false}

   :rf.egress/off-box-tool
   {rf.mcp-base.vocab/include-sensitive-opt false
    rf.mcp-base.vocab/include-large-opt      false
    rf.mcp-base.vocab/include-digests-opt    true}

   :rf.egress/local-redacted
   {rf.mcp-base.vocab/include-sensitive-opt false
    rf.mcp-base.vocab/include-large-opt      false
    rf.mcp-base.vocab/include-digests-opt    false}

   :rf.egress/local-raw
   {rf.mcp-base.vocab/include-sensitive-opt true
    rf.mcp-base.vocab/include-large-opt      true
    rf.mcp-base.vocab/include-digests-opt    false}

   :rf.egress/ssr-hydration
   {rf.mcp-base.vocab/include-sensitive-opt false
    rf.mcp-base.vocab/include-large-opt      false
    rf.mcp-base.vocab/include-digests-opt    false}

   :rf.egress/public-error
   {rf.mcp-base.vocab/include-sensitive-opt false
    rf.mcp-base.vocab/include-large-opt      false
    rf.mcp-base.vocab/include-digests-opt    false}})

(defn profile-size-opts
  "Return the `:rf.size/*` floor a profile resolves to, or `nil` for an
  unknown / absent profile. The pure-data counterpart of
  `re-frame.projection/profile-size-opts` an MCP server uses to express
  its egress boundary as a named profile (EP-0015 §10) without re-deriving
  the table and without pulling the framework runtime graph into its
  bundle."
  [profile]
  (get profile->size-opts profile))

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
  cannot drift. Callers resolve the returned profile to its `:rf.size/*`
  floor via `profile-size-opts`."
  [sensitive-reads-allowed?]
  (if sensitive-reads-allowed?
    :rf.egress/local-raw
    :rf.egress/off-box-tool))
