# Story UI — Docs Mode and Share

> The Story `:docs` mode as curated, executable variant documentation
> with evidence excerpts; and the **human-facing egress** surface — share
> URLs, static export, copied EDN, and screenshots —
> shipping freely and each labelled with its **reproducibility status**
> (fully / partially / view-only) so a recipient knows what they can
> replay. This spec **extends** [`008-Docs-Mode.md`](008-Docs-Mode.md)
> with evidence excerpts and the reproducibility contract.
>
> **Reframe (authoritative, Mike 2026-05-30; rf2-ba86n.16).** Human-facing
> egress of a developer's OWN running app is NOT a privacy concern: a
> local developer already has programmatic access to their own secrets,
> so redacting human egress is futile and is NOT the goal. The two real
> redaction points — the AI/MCP boundary and logs — are handled
> elsewhere (rf2-m25hd verified the MCP gate; rf2-6773q scrubbed logs;
> the common-seam dependency rf2-qarwq is CLOSED). So the human share /
> copy / static / screenshot commands SHIP — enabled, working, **not**
> disabled-pending-a-seam and **not** privacy-gated. What survives is the
> genuinely valuable half of the T4 tension: a shared / exported / copied
> artifact states whether the recipient can **reproduce** it. This spec
> previously encoded the pre-reframe "route all egress through a common
> redaction seam, treat share as privacy-sensitive" model; that framing
> is superseded by the reproducibility-honesty contract below.

## Builds on

- [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) — the
  product contract and the T4 reproducibility-honesty tension
  resolution (§4).
- [`008-Docs-Mode.md`](008-Docs-Mode.md) — the current read-only
  AutoDocs-equivalent docs pane (header, prose, args, decorators,
  parameters, tags) and its read-only contract.
- [`013-Static-Build.md`](013-Static-Build.md) — `story:build`, the
  static HTML export; the reproducibility contract here labels its output.
- [`005-SOTA-Features.md`](005-SOTA-Features.md) — the per-variant share
  URL surface (live address-bar) and embed code; this spec labels those
  with their reproducibility status.
- [`017-Testing-Story.md`](017-Testing-Story.md) — the run-result and
  evidence projection that docs excerpts and copied artifacts draw from.
  **Source of truth for the substrate.**
- [`../../../spec/015-Data-Classification.md`](../../../spec/015-Data-Classification.md)
  and [`../../../spec/Security.md`](../../../spec/Security.md) — the
  framework's path-level data-classification and privacy posture. These
  bear on the AI/MCP boundary + logs (the off-box boundaries, handled
  elsewhere — §4), NOT on human egress of the dev's own app. The EP-0015
  scope reconciliation for the feature-created-artifact exception is §3.

## Supersedes

- Nothing behavioural at the docs-pane level — this spec **extends**
  [`008-Docs-Mode.md`](008-Docs-Mode.md) with evidence excerpts,
  fidelity/world-input/runner/frame-binding chips, and links into
  Inspector/Xray, rather than replacing its read-only contract.
- **The pre-reframe egress model.** An earlier draft of this spec
  required every egress call to flow through a common redaction seam and
  treated "share current state" as a privacy-sensitive command. Per the
  reframe that model is superseded: human egress of the dev's own app
  ships freely and is not privacy-gated; the net-new requirement is the
  reproducibility-honesty contract (§3) — a shared/exported artifact says
  whether the recipient can reproduce it.

## Depends on

- The evidence-spine display for the docs excerpt — owned by
  [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
  §3.

  The pre-reframe "BLOCKED on the common egress redaction seam
  (`rf2-qarwq`)" dependency is GONE: human egress is not redaction-gated,
  and `rf2-qarwq`'s real scope (the AI-boundary + logs) is handled
  elsewhere (rf2-m25hd, rf2-6773q) and is out of scope here (§4).

## Out of scope

- **The AI/MCP egress path.** Agent/MCP read/copy/share rides its own
  (separate, already-shipped) gate per
  [`006-MCP-Surface.md`](006-MCP-Surface.md) +
  [`../../story-mcp/spec/003-Write-Surface-Gating.md`](../../story-mcp/spec/003-Write-Surface-Gating.md)
  (rf2-m25hd verified the gate). This spec is the HUMAN egress UX; it does
  not re-gate or re-specify the AI boundary.
- Log redaction — handled by the framework log scrub (rf2-6773q).
- Test-mode result presentation and failure promotion —
  [`021-Story-UI-Test-And-Evidence.md`](021-Story-UI-Test-And-Evidence.md).
- Controls and save-current-state authoring —
  [`019-Story-UI-Controls-And-View-States.md`](019-Story-UI-Controls-And-View-States.md).
- A hosted visual-review service — OUT (see
  [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) §6).

## Status labels

This spec uses the Story UI status labels (CURRENT / TARGET / BLOCKED /
SUPERSEDES / FUTURE / OUT) defined in
[`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md)
§"Normative language".

## 1. Docs mode

Story pressure: S1, S8, S11.

Docs mode remains the read-only curated presentation of the variant. It
MUST preserve the current docs-pane contract
([`008-Docs-Mode.md`](008-Docs-Mode.md)) unless superseded through one
converged docs path.

Docs SHOULD add:

- fidelity badges, world-input chips, runner-requirement chips, and
  frame-binding chips;
- a view-arg schema table;
- a current test-status summary;
- a visual/a11y status summary;
- a sparse evidence excerpt where useful (drawn from the evidence-spine
  display,
  [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
  §3);
- links into Inspector/Xray for detailed diagnostics.

Docs MUST NOT become a debugging log. Detailed diagnostics belong in
Inspector/Xray. Docs mode should make a variant feel like a curated
example, not an implementation fixture, and MUST avoid marketing-page
layout patterns — the user is already inside a developer tool.

## 2. Evidence excerpts

Story pressure: S8.

Docs MAY summarize status, fidelity, and one or two narrative beats
inline, but detailed diagnostics MUST link to the Inspector/Xray embed
rather than inlining a full beat tree. The excerpt is a curated pointer
into the evidence spine, not a second evidence renderer — the spine
display contract in
[`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
§3 owns the rendering; docs excerpts select a sparse projection of it.

## 3. Human egress and the reproducibility contract

Story pressure: S6, S9, S11.

Human-facing egress of the developer's OWN running app SHIPS freely — it
is NOT privacy-gated. The local developer already has programmatic access
to their own app's state and secrets, so redacting the URLs, EDN, static
builds, and screenshots they emit of their own running app is futile and
is not the goal (the reframe). The human egress commands are:

- share URL;
- static build (`story:build`);
- copied EDN;
- screenshots.

### 3.0 EP-0015 scope reconciliation (rf2-nnc06c)

EP-0015 (final) scopes screenshots / manual copy OUT of the egress
contract **except where a re-frame2 feature itself creates the
copied/exported artifact** ([spec/015 §Out of scope](../../../spec/015-Data-Classification.md#out-of-scope-explicit-non-goals)
+ [EP-0015 §Non-Goals](../../../docs/EP/EP-0015-frame-owned-egress-policy.md)).
The four commands above ARE feature-created artifacts, so by the letter of
that exception they fall IN scope. This is the recorded post-EP ruling
that reconciles them, not an omission:

- **They are a TRUSTED-LOCAL operator act.** A human pressing
  share / copy / export / screenshot is the trusted-local operator
  revealing their OWN frame — the same actor and intent as the
  `:rf.egress/local-raw` profile ([spec/015 §Cross-tool visibility grain](../../../spec/015-Data-Classification.md#cross-tool-visibility-grain),
  EP-0015 issue 7). The artifact is produced on the operator's box, at the
  operator's deliberate request, of an app the operator already has full
  programmatic access to — no NEW disclosure is created. **Ruling:**
  human-local share/copy/export/screenshot artifacts ship UNREDACTED, with
  the residual risk documented — the recipient of a deliberately shared
  artifact sees whatever the operator chose to share. The operator owns
  that choice, exactly as they own pasting their own console output.
- **The off-box / saved / tool boundaries ARE classified — elsewhere
  (§4).** An AI/MCP read of the same data is the off-box-tool boundary
  (`:rf.egress/off-box-tool`, gated in the MCP jar); a hosted log sink is
  the off-box-observability boundary (scrubbed). Those, not this human
  egress UX, are where EP-0015's redaction lives.
- **This module classifies REPRODUCIBILITY, not sensitivity** — it answers
  "can the recipient reproduce this?", never "is this sensitive?". The two
  axes are orthogonal; EP-0015 owns sensitivity at the off-box boundaries.

Promotion (turning a captured run artifact into a named variant) is NOT in
this set: it is registrar-WRITE authoring owned by
[`021-Story-UI-Test-And-Evidence.md`](021-Story-UI-Test-And-Evidence.md)
§3, not an outbound artifact. A promoted variant becomes a first-class
registered variant that is then shareable through the four egress commands
above.

Each of these MUST be **enabled and working** — none is disabled pending a
redaction seam, and none is fronted with a "are you sure this is
sensitive?" privacy prompt. Privacy-theatre friction on human egress is
explicitly rejected.

What the UI MUST carry on every human egress command is the
**reproducibility status** — the genuinely valuable half of the T4
tension ([`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) §4).
A shared / exported / copied artifact MUST state whether the recipient
can reproduce it:

- **fully reproducible** — every input that drives the variant survives
  the EDN / URL round-trip; paste the URL or the EDN and you land on the
  exact same cell;
- **partially reproducible** — most state carries, but something is
  omitted or does not survive serialisation (a cell-override value that
  is not readable EDN, a `:network` reply that is a closure, share-URL
  overrides that no longer apply); the recipient reproduces a degraded-
  but-usable approximation;
- **view-only** — the variant fundamentally cannot be replayed from the
  artifact (a function pinned as an arg / sub-override value the view
  calls, external state the artifact cannot capture, or a screenshot —
  always a static image); the recipient gets a view-only snapshot.

The status MUST be visible AND, where the artifact is partial or
view-only, the UI MUST say WHAT made it so (the specific override, the
specific signal, the dropped tokens). This is a reproducibility honesty
contract — `"can the recipient reproduce this?"` — never a sensitivity
warning. The status is the lowest any reason implies — one view-only
reason makes the whole artifact view-only — so the label never overstates
what the recipient can do.

Share semantics:

- the share URL is the browser's own address-bar URL (Cmd-L / Cmd-A /
  Cmd-C copies it; rf2-ymnfx retired the separate Share button + QR
  popover); the dialog offers a one-click copy of it, not a second
  artifact;
- it encodes the variant + workspace + mode-tab + active modes + viewport
  + background + tag-filter + the focused variant's cell-overrides +
  substrate (`re-frame.story.share/build-params`);
- the cell-overrides are encoded as one `pr-str`-printed EDN map and read
  back as one map (rf2-j0hwf), so the encode/decode round-trip is
  faithful — including string override values that carry the list
  separator (a comma);
- the decoded overrides hydrate the shell under
  `[:cell-overrides <focused-variant>]` on both mount and back/forward
  (`re-frame.story.ui.url-state/apply-parsed-to-state`), so a pasted URL
  renders the same effective args, not just the selection (rf2-j0hwf);
- the URL is AUTHORITATIVE for the focused variant's overrides: hydrating a
  URL that keeps that variant but carries NO overrides CLEARS any stale
  in-memory override slice for it (not merely skipping the write), so
  back/forward, a bookmark, or a share link captured before a control edit
  renders WITHOUT that edit — the address bar stays the source of truth.
  Only the focused variant's slice is touched; other variants' overrides
  are left intact (rf2-2cpoo);
- the same authoritative-clear discipline extends to EVERY URL-owned chrome
  slot, not just overrides (rf2-fkmnh): once URL hydration is in play
  (`apply-parsed-to-state` runs on a populated mount or any popstate), an
  omitted `modes=` clears `:active-modes` to `[]`, an omitted `tag-filter=`
  clears `:tag-filter` to `#{}`, an omitted (or present-but-invalid)
  `viewport=`/`background=` clears the slot to its neutral default (`:full`
  / no background), and an omitted (or present-but-unregistered) `substrate=`
  clears `:substrate` to the `:reagent` default (rf2-dxz4sg). The substrate is
  the one slot whose default is itself the omission token — `build-params`
  emits `substrate=` only for a non-default substrate — so an omitted param
  carries the SAME default-restore meaning as the other URL-owned slots and is
  validated against the live substrate registry. Mode-tab gets the SAME
  treatment but is per-variant rather than global (rf2-gchydo): when the URL
  keeps the focused variant but omits `mode-tab=`, the stale
  `[:active-mode-tab <focused-variant>]` entry is cleared (dissoc'd, not
  merely left unset) so the reader's `:dev` default applies — the same fix
  the encode side already assumed (mode-tab changes are pushState'd, per
  `url-relevant-slots-changed?`), so Back/Back past a tab switch reverts the
  rendered tab, not just the address bar. A no-query popstate
  (back/forward to a bare URL) likewise clears the URL-owned selection / modes
  / framing / filter / substrate / mode-tab rather than no-op'ing. So a share
  link like `?variant=story.counter/loaded` restores
  the DEFAULT view for the recipient instead of keeping their prior
  localStorage-seeded chrome — the address bar is the source of truth for the
  full share surface. The intentional localStorage fallback survives ONLY for
  a fresh mount with no URL state at all (the localStorage hydrators run first
  on mount, and `apply-parsed-to-state` only runs when the URL carries query
  params);
- stale overrides are DROPPED AND REPORTED, not silently merged (rf2-76l69l):
  a URL override is dropped at two stages — `parse-overrides-param*` drops an
  UNPARSEABLE entry (malformed EDN, a non-keyword key), and
  `drop-stale-overrides` then drops every parsed override whose arg-key the
  CURRENTLY-SELECTED variant no longer DECLARES (the variant's args were
  refactored, renamed, or removed since the URL was captured). The declared
  contract is the same arg surface the controls panel exposes — the variant's
  resolved-args keys, its (and its parent story's) `:argtypes` keys, and the
  compiled view-args schema's top-level `:map` entry keys
  (`re-frame.story.ui.share/declared-arg-keys`). Both drop classes feed the
  share-import hint's `:dropped` count, so a stale override DOWNGRADES the
  reproducibility status and surfaces the drift banner instead of being
  installed as an orphan live arg `args/resolve-args` would merge into the
  recipient's effective state (a partial artifact masquerading as full);
- mount URL hydration runs in TWO passes with a SINGLE authoritative owner
  per slot (rf2-ovb1en — `re-frame.story.ui.shell/hydrate-url-state!`):
  pass 1 (`url-state/hydrate-from-url!`) is the sole VALIDATING owner of
  selection, substrate, and every chrome slot (an unregistered `substrate=`
  degrades to `:reagent`; an unregistered variant degrades to no selection);
  pass 2 (`share/hydrate-from-url!`) is the sole owner of the focused
  variant's cell-overrides slice — it applies the `drop-stale-overrides`
  drift filter pass 1 cannot run (pass 1 is pure, with no registrar access)
  and writes the filtered slice authoritatively (installing it, or CLEARING
  it when every parsed override is stale), plus the drift hint. Pass 2 READS
  — never rewrites — pass 1's validated selection / substrate, so the second
  pass can never undo the first pass's validation (the bug rf2-ovb1en fixed:
  pass 2 had reparsed the raw `substrate=` unvalidated and resurrected a
  stale value, and left an all-stale override slice live);
- a shared link restores VIEW STATE (it lands the recipient on the cell);
  it does not replay a run — that is the recorder's `:play-script` export;
- the reproducibility status is computed (purely) by
  `re-frame.story.egress/classify` over the variant's compiled plan + the
  share params, and surfaced by `re-frame.story.ui.share` on each command.

## 4. Agent egress (out of scope here — already gated elsewhere)

Story pressure: S9, S11.

Agent/MCP read, copy, and share operations are governed by the
already-shipped AI-boundary gate
([`006-MCP-Surface.md`](006-MCP-Surface.md) +
[`../../story-mcp/spec/003-Write-Surface-Gating.md`](../../story-mcp/spec/003-Write-Surface-Gating.md);
verified by rf2-m25hd) — that gate, NOT the human egress UX, is where the
AI-boundary redaction lives. The no-second-artifact-model rule (T3,
[`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) §4) still
holds: the artifact an agent copies is the same artifact a human would
copy. This spec is the human egress UX and does not re-gate or
re-specify the AI path.

## 5. Acceptance criteria

The docs-and-share contract is satisfied when:

- docs mode preserves the read-only curated presentation and adds
  fidelity/world-input/runner/frame-binding chips, a view-arg schema
  table, test and visual/a11y status summaries, and sparse evidence
  excerpts that link into Inspector/Xray;
- docs never becomes a debugging log or a marketing page;
- evidence excerpts are a curated pointer into the evidence spine, not a
  second evidence renderer;
- the human egress commands (share URL, static build, copy EDN,
  screenshot) ship enabled and are NOT privacy-gated — no "is this
  sensitive?" friction on egress of the dev's own app;
- every human egress command states the artifact's reproducibility status
  (fully / partially / view-only) and, where partial or view-only, says
  WHAT made it so;
- the reproducibility status is the lowest any downgrade reason implies,
  so the label never overstates what the recipient can reproduce;
- agent/MCP egress is governed by the already-shipped AI-boundary gate
  (out of scope here), and the no-second-artifact-model rule holds.

## Cross-references

| Concern | Source |
|---|---|
| Product contract + T4 safe-sharing tension | [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) |
| Current docs pane | [`008-Docs-Mode.md`](008-Docs-Mode.md) |
| Static build (`story:build`) | [`013-Static-Build.md`](013-Static-Build.md) |
| Share URL + embed code | [`005-SOTA-Features.md`](005-SOTA-Features.md) |
| Run-result + evidence projection | [`017-Testing-Story.md`](017-Testing-Story.md) |
| Evidence-spine display | [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md) |
| Reproducibility classifier (`egress/classify`) + UI surface | `re-frame.story.egress`, `re-frame.story.ui.share` |
| Data classification + privacy (AI boundary + logs, not human egress) | [`../../../spec/015-Data-Classification.md`](../../../spec/015-Data-Classification.md), [`../../../spec/Security.md`](../../../spec/Security.md) |
| AI/MCP egress gate (out of scope here) | [`006-MCP-Surface.md`](006-MCP-Surface.md), [`../../story-mcp/spec/003-Write-Surface-Gating.md`](../../story-mcp/spec/003-Write-Surface-Gating.md) |
