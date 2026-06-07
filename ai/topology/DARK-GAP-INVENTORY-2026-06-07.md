# Dark Machine Chart Gap Inventory

Generated: 2026-06-07

Scope: dark-theme matched PNG comparison, one pair at a time.

Inputs:
- Xray current: `ai/topology/xray-pngs/dark/<id>.png`
- Stately target: `ai/topology/xstate-pngs/dark/<stately>.png`

Pairs audited:
`door->door`, `traffic->traffic-parallel`, `quiz->quiz-always`,
`brew->brew-after`, `session->session-flow-parent`,
`hvac->hvac-deep-compound`, `media->media-history-deep`,
`modal->modal-multi-event`, `gate->gate`.

`fuse` was skipped because it renders blank.

Severity is assigned only to `RENDERER-GAP` rows. `DATA`,
`CAPTURE-ARTIFACT`, and `ALREADY-MATCHES` rows use `-`.

Settled or by-design items were not counted as renderer gaps: events as nodes,
single-branch `IF` guard labels, bolt actions, title+body state boxes,
kebab-vs-camel names, blue live active-state highlighting, the documented
back-edge return route residual, and the documented adaptive per-machine aspect
residual.

## Door

| Element | Stately | Xray | Classification | Severity |
|---|---|---|---|---|
| Flow direction/aspect | Tall, compact vertical column inside a root-framed chart. | Also vertical, but the canvas capture is wider and the `open` branch fans farther right/down. The remaining spread is mostly the documented adaptive aspect/residual routing surface. | ALREADY-MATCHES | - |
| Root/title/context chrome | Shows the machine title `door` in the root title bar. | Captures the Xray canvas frame; the `main` frame title is clipped/overlapped by the Canvas/List toggle and no root context frame is included. | CAPTURE-ARTIFACT | - |
| Initial-state placement and icon | Small initial dot with a short hooked arrow into `locked`. | Initial dot and entry arrow are present and readable; hook is slightly looser and larger in the captured scale. | ALREADY-MATCHES | - |
| Final marker | No final state exercised in this machine. | No final state exercised. | ALREADY-MATCHES | - |
| State-node shape/size/fill/title | Compact dark state boxes with title strip, divider, and body tag. | Same title/body grammar and dark fill; `locked` is blue because Xray captured live active state. Apparent larger size comes from fit/crop. | CAPTURE-ARTIFACT | - |
| Event-chip shape/size/radius | Rounded capsule event nodes. | Rounded capsule event nodes now match closely. Events-as-nodes are by design. | ALREADY-MATCHES | - |
| Edge routing, attachments, arrowheads | Edges attach cleanly to box faces; the reset return is compact in the Stately crop. | Arrowheads are small and flush. The long return path for `reset` is the documented back-edge residual and is not counted here. | ALREADY-MATCHES | - |
| Compound enclosure | No compound enclosure in this flat machine. | No compound enclosure. | ALREADY-MATCHES | - |
| Parallel regions | None. | None. | ALREADY-MATCHES | - |
| Colours | Dark greys, white text, quiet grey edges. | Static palette is close; blue active state/edge is live runtime capture. | CAPTURE-ARTIFACT | - |
| Fonts | Sans UI text with compact weights. | Reads equivalent in title and body labels. | ALREADY-MATCHES | - |
| Spacing/density | Compact vertical spacing. | Slightly looser branch spacing; dominated by capture scale and documented aspect/routing residuals. | CAPTURE-ARTIFACT | - |
| Background | Subtle dark grid. | Dark dotted canvas grid with a slightly bluer tint. | RENDERER-GAP | low |

## Traffic

| Element | Stately | Xray | Classification | Severity |
|---|---|---|---|---|
| Flow direction/aspect | Wide landscape parallel chart; regions are stacked vertically and each region flows left-to-right. | Regions are side-by-side and each region flows top-to-bottom. This is the documented adaptive aspect/parallel-axis residual. | ALREADY-MATCHES | - |
| Root/title/context chrome | Root title reads `trafficLight`. | Canvas capture title reads `light` and is overlapped by the Canvas/List toggle. | CAPTURE-ARTIFACT | - |
| Initial-state placement and icon | Initial dot/hook appears for both regions. | Initial dots/arrows are present for both regions; live active state makes the first paths blue. | ALREADY-MATCHES | - |
| Final marker | No final state exercised. | No final state exercised. | ALREADY-MATCHES | - |
| State-node shape/size/fill/title | Compact state nodes inside each region. | Same state grammar; active nodes have blue borders due runtime state capture. | CAPTURE-ARTIFACT | - |
| Event-chip shape/size/radius | Rounded event capsules between states. | Rounded event capsules match. | ALREADY-MATCHES | - |
| Edge routing, attachments, arrowheads | Short horizontal chains within each region. | Vertical chains due documented axis residual; arrowheads remain flush and sized close to target. | ALREADY-MATCHES | - |
| Compound enclosure | Parallel root encloses regions. | Parallel root/regions render; capture shows active-region solid blue outlines. Resting dashed border is known wired and not a renderer gap. | CAPTURE-ARTIFACT | - |
| Parallel regions | Region titles are lower-case (`vehicle`, `pedestrian`). | Region titles render upper-case (`VEHICLE`, `PEDESTRIAN`). | RENDERER-GAP | low |
| Colours | Dark greys with quiet grey edges. | Static palette close; active blue is runtime capture. | CAPTURE-ARTIFACT | - |
| Fonts | Region and state labels are compact sans. | Similar font family and sizing; uppercase region title treatment is counted in the parallel-region row. | ALREADY-MATCHES | - |
| Spacing/density | Wide and dense, with long horizontal rank usage. | Tall/side-by-side composition driven by documented aspect residual, not counted. | ALREADY-MATCHES | - |
| Background | Subtle dark grid. | Dark dotted canvas grid with a slightly bluer tint. | RENDERER-GAP | low |

## Quiz

| Element | Stately | Xray | Classification | Severity |
|---|---|---|---|---|
| Flow direction/aspect | Landscape-ish flow: `asking` on the left, answer/guard flow to the right, `passed` far right. | Portrait stack: `asking` above event/guard nodes above `passed`. This is the documented adaptive aspect residual. | ALREADY-MATCHES | - |
| Root/title/context chrome | Root title and context frame for `quizScorer`. | Canvas title reads `scorer` and is partly hidden by the Canvas/List toggle; root chrome is outside the capture. | CAPTURE-ARTIFACT | - |
| Initial-state placement and icon | Small dot/hook into `asking`. | Dot and entry arrow are present; scale appears larger because the sparse chart is fit to the canvas. | CAPTURE-ARTIFACT | - |
| Final marker | No final state marker is visible in this reference pair. | No final state marker is exercised. | ALREADY-MATCHES | - |
| State-node shape/size/fill/title | Compact state nodes with title/body. | Same grammar; `asking` is blue due live active capture. | CAPTURE-ARTIFACT | - |
| Event-chip shape/size/radius | Rounded event capsule for `quiz/answer`; always/guard node is visually simple. | Event capsules are rounded. The `quiz/answer` chip has a dashed/variant treatment and the always transition carries an infinity glyph. | RENDERER-GAP | low |
| Edge routing, attachments, arrowheads | Short routed branches around the guard/event chain. | Vertical routing comes from documented aspect residual; arrowheads are close and flush. | ALREADY-MATCHES | - |
| Compound enclosure | None. | None. | ALREADY-MATCHES | - |
| Parallel regions | None. | None. | ALREADY-MATCHES | - |
| Colours | Static dark palette with grey edges. | Static palette close; active blue is runtime capture. | CAPTURE-ARTIFACT | - |
| Fonts | Compact sans labels. | Similar font family; guard punctuation/casing differs because source naming differs. | DATA | - |
| Spacing/density | Compact horizontal spacing. | Larger nodes and more vertical whitespace due fit/capture plus aspect residual. | CAPTURE-ARTIFACT | - |
| Background | Subtle dark grid. | Dark dotted canvas grid with a slightly bluer tint. | RENDERER-GAP | low |

## Brew

| Element | Stately | Xray | Classification | Severity |
|---|---|---|---|---|
| Flow direction/aspect | Compact vertical sequence from `idle` to `ready`. | Same broad vertical shape, but wider and more zoomed in the canvas capture. | CAPTURE-ARTIFACT | - |
| Root/title/context chrome | Root title reads `brew`. | Canvas title reads `machine` and is overlapped by the Canvas/List toggle. | CAPTURE-ARTIFACT | - |
| Initial-state placement and icon | Small dot/hook into `idle`. | Dot and entry arrow are present and close. | ALREADY-MATCHES | - |
| Final marker | No final marker visible in this pair. | No final marker exercised. | ALREADY-MATCHES | - |
| State-node shape/size/fill/title | Compact dark title/body state boxes. | Same grammar; `idle` is blue due live active capture and capture zoom makes boxes larger. | CAPTURE-ARTIFACT | - |
| Event-chip shape/size/radius | Rounded event capsules; timer reads as `after 5000 ms`. | Rounded capsules match, but the timer chip is rendered as a clock glyph plus `5000ms`. | RENDERER-GAP | low |
| Edge routing, attachments, arrowheads | Back edges are comparatively compact. | Long `abort`/`start` return paths are the documented back-edge residual. Arrowheads are close and flush. | ALREADY-MATCHES | - |
| Compound enclosure | None. | None. | ALREADY-MATCHES | - |
| Parallel regions | None. | None. | ALREADY-MATCHES | - |
| Colours | Static dark palette. | Static palette close; active blue is runtime capture. | CAPTURE-ARTIFACT | - |
| Fonts | Compact sans labels. | Similar; `5000ms` formatting differs from `5000 ms`. | DATA | - |
| Spacing/density | Tight vertical chain. | More whitespace due canvas fit and documented residual routing. | CAPTURE-ARTIFACT | - |
| Background | Subtle dark grid. | Dark dotted canvas grid with a slightly bluer tint. | RENDERER-GAP | low |

## Session

| Element | Stately | Xray | Classification | Severity |
|---|---|---|---|---|
| Flow direction/aspect | Wide flow with `idle` and `authenticating` side-by-side. | Vertical flow, which is the documented adaptive aspect residual. | ALREADY-MATCHES | - |
| Root/title/context chrome | Root title reads `sessionFlow` and shows context information. | Canvas title reads `flow`; root/context chrome is not captured. | CAPTURE-ARTIFACT | - |
| Initial-state placement and icon | Small dot/hook into `idle`. | Dot and entry arrow are present; scale is affected by canvas fit. | CAPTURE-ARTIFACT | - |
| Final marker | No final marker visible in this pair. | No final marker exercised. | ALREADY-MATCHES | - |
| State-node shape/size/fill/title | `authenticating` includes invoke/actor/done details in the state body. | `authenticating` only has the state tag; invoke, actor, done, and `captureToken` are absent from the machine data. | DATA | - |
| Event-chip shape/size/radius | Rounded `session/open`, `session/close`, and done-event chips. | Rounded event capsules for the events that exist in the re-frame2 machine. | ALREADY-MATCHES | - |
| Edge routing, attachments, arrowheads | Short side-by-side transition paths. | Vertical paths are from documented aspect residual; arrowheads are close and flush. | ALREADY-MATCHES | - |
| Compound enclosure | None in the rendered Xray data. | None. | DATA | - |
| Parallel regions | None. | None. | ALREADY-MATCHES | - |
| Colours | Static dark palette. | Static palette close; active blue is runtime capture. | CAPTURE-ARTIFACT | - |
| Fonts | Sans labels, mono-ish context details. | Similar sans labels; missing context panel is capture/data, not font. | CAPTURE-ARTIFACT | - |
| Spacing/density | Dense horizontal pair. | Tall sparse capture due documented aspect residual and viewport fit. | CAPTURE-ARTIFACT | - |
| Background | Subtle dark grid. | Dark dotted canvas grid with a slightly bluer tint. | RENDERER-GAP | low |

## HVAC

| Element | Stately | Xray | Classification | Severity |
|---|---|---|---|---|
| Flow direction/aspect | Large landscape chart. Parallel regions are stacked vertically; nested climate states flow mostly left-to-right. | Parallel regions sit side-by-side and nested states stack vertically. This is the documented adaptive aspect/parallel-axis residual. | ALREADY-MATCHES | - |
| Root/title/context chrome | Root title reads `hvacController`. | Canvas title reads `controller` and is overlapped by the Canvas/List toggle. | CAPTURE-ARTIFACT | - |
| Initial-state placement and icon | Initial dots/hooks appear at the root region level and nested compound levels. | Initial dots/arrows appear at the climate, conditioning, fan, and nested levels; active capture makes several borders/edges blue. | ALREADY-MATCHES | - |
| Final marker | No final marker exercised. | No final marker exercised. | ALREADY-MATCHES | - |
| State-node shape/size/fill/title | Compact state boxes inside nested compound enclosures. | State title/body grammar matches; active nodes are blue. Current enclosure sizing no longer visibly overflows. | ALREADY-MATCHES | - |
| Event-chip shape/size/radius | Rounded event capsules. | Rounded event capsules match. | ALREADY-MATCHES | - |
| Edge routing, attachments, arrowheads | Horizontal routes inside nested compounds, with compact fan and return paths. | Vertical/narrow-region routes come from documented aspect residual; back-edge detours are the documented route residual. | ALREADY-MATCHES | - |
| Compound enclosure | `climate -> running -> conditioning` is fully enclosed. | Nested compound enclosure is fully visible and no longer spills children outside parent bounds. | ALREADY-MATCHES | - |
| Parallel regions | Region titles are lower-case (`climate`, `fan`) and resting borders are dashed. | Region titles render upper-case (`CLIMATE`, `FAN`). Borders appear solid/blue because the capture is live-active; resting dashed is known wired. | RENDERER-GAP | low |
| Colours | Static dark palette. | Static palette close; blue active outlines/edges are runtime capture. | CAPTURE-ARTIFACT | - |
| Fonts | Compact sans labels. | Similar font family; uppercase region title treatment is counted in the parallel-region row. | ALREADY-MATCHES | - |
| Spacing/density | Dense nested landscape with large but purposeful region whitespace. | Sparse side-by-side capture from documented aspect residual and viewport fit. | ALREADY-MATCHES | - |
| Background | Subtle dark grid. | Dark dotted canvas grid with a slightly bluer tint. | RENDERER-GAP | low |

## Media

| Element | Stately | Xray | Classification | Severity |
|---|---|---|---|---|
| Flow direction/aspect | Wide landscape chart: tray/stopped/player/paused are arranged across a large compound. | Tall portrait chart with tray above player and stopped/paused below; this is the documented adaptive aspect residual. | ALREADY-MATCHES | - |
| Root/title/context chrome | Root title reads `mediaDeep`. | Canvas title reads `shallow`; root chrome is clipped/overlapped by the Canvas/List toggle. The title also indicates the Xray capture is not the same deep-history variant as the Stately target. | DATA | - |
| Initial-state placement and icon | Initial dots/hooks at the top-level and inside `player`/`playing`. | Initial dots/arrows are present but scaled by capture; some are partly inside narrow nested columns. | CAPTURE-ARTIFACT | - |
| Final marker | No final marker exercised. | No final marker exercised. | ALREADY-MATCHES | - |
| State-node shape/size/fill/title | Compact nested state boxes and labelled history marker. | State boxes use the same grammar; chart is the shallow variant, so the history marker differs from the deep-history target data. | DATA | - |
| Event-chip shape/size/radius | Rounded event capsules for insert/eject/play/seek/stop/pause/resume. | Rounded event capsules match. | ALREADY-MATCHES | - |
| Edge routing, attachments, arrowheads | Long but readable horizontal routes through player and history. | Vertical/narrow routes and long `eject` return path come from documented aspect/routing residuals. | ALREADY-MATCHES | - |
| Compound enclosure | `player` contains `playing`; `playing` contains `atStart` and `midTrack`. | Nested compounds are enclosed without the old overflow defect. | ALREADY-MATCHES | - |
| Parallel regions | None. | None. | ALREADY-MATCHES | - |
| Colours | Static dark palette. | Static palette close; active blue is runtime capture. | CAPTURE-ARTIFACT | - |
| Fonts | Compact sans labels. | Similar; title/history naming mismatch is data. | DATA | - |
| Spacing/density | Wide, dense compound layout. | Tall sparse capture due documented aspect residual and viewport fit. | ALREADY-MATCHES | - |
| Background | Subtle dark grid. | Dark dotted canvas grid with a slightly bluer tint. | RENDERER-GAP | low |

## Modal

| Element | Stately | Xray | Classification | Severity |
|---|---|---|---|---|
| Flow direction/aspect | `closed` and `open` are side-by-side with event nodes between/around them. | `closed` is above `open`, with event nodes below. This is the documented adaptive aspect residual. | ALREADY-MATCHES | - |
| Root/title/context chrome | Root title reads `modal`. | Canvas title reads `main`; the Canvas/List toggle overlaps the top-left title area. | CAPTURE-ARTIFACT | - |
| Initial-state placement and icon | Small dot/hook into `closed`. | Dot and entry arrow are present, but larger because of capture scale. | CAPTURE-ARTIFACT | - |
| Final marker | No final marker exercised. | No final marker exercised. | ALREADY-MATCHES | - |
| State-node shape/size/fill/title | Compact `closed`/`open` title/body state boxes. | Same grammar; `closed` is blue due runtime active capture. | CAPTURE-ARTIFACT | - |
| Event-chip shape/size/radius | Rounded event capsules, including `modal/submit` with action body. | Rounded event capsules match. Bolt action presentation is by design. | ALREADY-MATCHES | - |
| Edge routing, attachments, arrowheads | Compact routes between side-by-side states and events. | Vertical routes and long returns come from documented aspect/routing residuals; arrowheads are close and flush. | ALREADY-MATCHES | - |
| Compound enclosure | None. | None. | ALREADY-MATCHES | - |
| Parallel regions | None. | None. | ALREADY-MATCHES | - |
| Colours | Static dark palette. | Static palette close; active blue is runtime capture. | CAPTURE-ARTIFACT | - |
| Fonts | Compact sans labels. | Similar font family and weight. | ALREADY-MATCHES | - |
| Spacing/density | Dense landscape. | Sparse vertical capture due documented aspect residual and fit/crop. | ALREADY-MATCHES | - |
| Background | Subtle dark grid. | Dark dotted canvas grid with a slightly bluer tint; the visible zoom toolbar is capture chrome. | RENDERER-GAP | low |

## Gate

| Element | Stately | Xray | Classification | Severity |
|---|---|---|---|---|
| Flow direction/aspect | Root-framed chart with `idle`, guarded branches, targets, and reset edges arranged compactly inside the frame. | Wider row of event nodes below `idle`, targets below events. The broad shape difference is the documented adaptive aspect residual; the guarded branch gap is recorded below. | ALREADY-MATCHES | - |
| Root/title/context chrome | Root title reads `gate` and context shows `level: number`. | Canvas title reads `main`; root context chrome is outside the captured canvas and the toggle overlaps the title area. | CAPTURE-ARTIFACT | - |
| Initial-state placement and icon | Small dot/hook into `idle`. | Dot and entry arrow are present; active capture makes `idle` blue. | CAPTURE-ARTIFACT | - |
| Final marker | No final marker exercised. | No final marker exercised. | ALREADY-MATCHES | - |
| State-node shape/size/fill/title | Compact title/body state boxes for `idle`, `high`, `low`, `rejected`. | Same grammar; active state is blue due runtime capture. | CAPTURE-ARTIFACT | - |
| Event-chip shape/size/radius | Rounded capsules. The three `gate/check` alternatives are rendered as an ordered cascade with numbered badges and IF/ELSE IF/ELSE structure. | Rounded capsules match, but `gate/check` renders as separate event chips in a row, with no priority badges or dotted evaluation-order connector; the default path appears visually first. | RENDERER-GAP | high |
| Edge routing, attachments, arrowheads | Ordered guard branches make evaluation order and targets clear; reset paths return to `idle`. | Edge fan-out from `idle` and reset return paths are visually busy. The long reset routes are the documented route residual; the guard-order loss is a renderer gap. | RENDERER-GAP | med |
| Compound enclosure | None. | None. | ALREADY-MATCHES | - |
| Parallel regions | None. | None. | ALREADY-MATCHES | - |
| Colours | Static dark palette. | Static palette close; active blue is runtime capture. | CAPTURE-ARTIFACT | - |
| Fonts | Guard/event labels are compact. | Similar font family; kebab/camel guard naming is source data/by design. | DATA | - |
| Spacing/density | Dense chart with clear branch hierarchy. | Large empty top area and broad event row come from viewport fit plus aspect residual; guard cascade still needs a compact ordered visual. | RENDERER-GAP | med |
| Background | Subtle dark grid. | Dark dotted canvas grid with a slightly bluer tint. | RENDERER-GAP | low |

## Consolidated Cross-Machine Summary

Only `RENDERER-GAP` items are ranked here. Data differences and capture
artifacts are excluded.

| Rank | Recurring renderer gap | Visual impact | Machines showing it | Notes |
|---|---|---|---|---|
| 1 | Guarded multi-branch cascade rendering | High | gate | Stately's numbered IF/ELSE IF/ELSE cascade communicates transition priority. Xray renders separate event nodes and visually loses ordering, including placing the default branch first. This matters for real SPA workflows where guard priority decides navigation, permissions, validation, and fallback states. |
| 2 | Canvas background treatment | Low | door, traffic, quiz, brew, session, hvac, media, modal, gate | Stately's dark grid is subtler and reads as a framed static diagram. Xray uses a bluer dotted interactive-canvas grid. This is a renderer/theme presentation difference, separate from capture chrome such as zoom controls and the Canvas/List toggle. |
| 3 | Parallel region title casing | Low | traffic, hvac | Stately uses lower-case region titles from the model (`vehicle`, `pedestrian`, `climate`, `fan`). Xray uppercases region title strips. The structure matches; this is a small visual fidelity issue. |
| 4 | Semantic event chip variants | Low | quiz, brew | Stately labels timer transitions as `after 5000 ms`; Xray uses a clock glyph plus `5000ms`. In quiz, Xray adds extra visual treatment around the always/event chips. These are readable Xray choices, but not Stately-parity rendering. |

## Important Non-Gaps

| Item | Classification | Machines | Why it is not a current renderer gap |
|---|---|---|---|
| Adaptive aspect and parallel axis | ALREADY-MATCHES | traffic, quiz, session, hvac, media, modal, gate | This is the documented G-ASPECT residual and should not be re-filed from this audit. |
| Long back-edge return paths | ALREADY-MATCHES | door, brew, hvac, media, modal, gate | This is the documented G-ROUTE residual and should not be re-filed from this audit. |
| Blue active outlines and edges | CAPTURE-ARTIFACT | all Xray captures | Xray captures runtime active state; Stately references are static. |
| Root title/context missing or overlapped | CAPTURE-ARTIFACT | all Xray captures | The capture is of the Xray canvas and includes devtools chrome overlap; root/context presentation is outside the screenshot scope. |
| Session invoke/done details | DATA | session | The Xray machine data lacks the invoked actor and done transition present in the Stately reference. |
| Media deep history mismatch | DATA | media | The Stately reference is `mediaDeep`, while the Xray capture is titled `shallow`; the history variant mismatch is data/provenance, not renderer parity. |
| Kebab vs camel names | DATA | door, quiz, brew, hvac, gate | Source naming differs; Xray is rendering its source data. |
| Events as nodes, IF labels, bolt actions, title/body state boxes | ALREADY-MATCHES | corpus-wide | These are Stately-like or re-frame2 by-design conventions and should not be "fixed away". |
