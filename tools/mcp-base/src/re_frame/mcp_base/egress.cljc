(ns re-frame.mcp-base.egress
  "Cross-MCP `:rf.egress/*` profile vocabulary + a pure-data resolver
  (EP-0015 §10).

  ## Why this ns

  EP-0015 defines the named-egress model: an off-box surface chooses
  *which boundary is this?* — a named `:rf.egress/*` profile — not *which
  combination of `:rf.size/*` booleans did I remember?*. The framework
  owns the authoritative table in `re-frame.projection/profile-size-opts`
  (`implementation/core`), but that ns transitively requires the
  framework runtime graph (`re-frame.elision` → `re-frame.frame` →
  substrate adapter, …). The MCP servers must NOT pull that graph into
  their wire-egress decision:

  - **re-frame2-pair-mcp** ships as a self-contained Node `server.js`
    bundle; requiring the runtime graph would bloat it and trip
    bundle-isolation.
  - the profile→opts resolution is a *server-side* decision (it renders
    the resolved `:rf.size/*` map into the eval form before it crosses
    the wire), so it cannot defer to the live app's `project-egress`.

  This ns is the cross-MCP, framework-runtime-free mirror of the six-
  member closed profile enum and its `:rf.size/*` floor. It is pure data
  over the keys `re-frame.mcp-base.vocab` already owns
  (`:rf.size/include-sensitive?` / `:rf.size/include-large?` /
  `:rf.size/include-digests?`). The mcp-conformance wire-vocab gate pins
  this table byte-identical to the framework enum so the two cannot
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
  (:require [re-frame.mcp-base.vocab :as vocab]))

(def profiles
  "The closed six-member `:rf.egress/profile` vocabulary (EP-0015 §10).
  Mirror of `re-frame.projection/profiles` — pinned byte-identical by the
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
   {vocab/include-sensitive-opt false
    vocab/include-large-opt      false
    vocab/include-digests-opt    false}

   :rf.egress/off-box-tool
   {vocab/include-sensitive-opt false
    vocab/include-large-opt      false
    vocab/include-digests-opt    true}

   :rf.egress/local-redacted
   {vocab/include-sensitive-opt false
    vocab/include-large-opt      false
    vocab/include-digests-opt    false}

   :rf.egress/local-raw
   {vocab/include-sensitive-opt true
    vocab/include-large-opt      true
    vocab/include-digests-opt    false}

   :rf.egress/ssr-hydration
   {vocab/include-sensitive-opt false
    vocab/include-large-opt      false
    vocab/include-digests-opt    false}

   :rf.egress/public-error
   {vocab/include-sensitive-opt false
    vocab/include-large-opt      false
    vocab/include-digests-opt    false}})

(defn profile-size-opts
  "Return the `:rf.size/*` floor a profile resolves to, or `nil` for an
  unknown / absent profile. The pure-data counterpart of
  `re-frame.projection/profile-size-opts` an MCP server uses to express
  its egress boundary as a named profile (EP-0015 §10) without re-deriving
  the table and without pulling the framework runtime graph into its
  bundle."
  [profile]
  (get profile->size-opts profile))
