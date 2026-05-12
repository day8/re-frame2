# Causa — UX/UI Design

> Bead **rf2-90d5 (P2 research)**. Local-only working draft. **Not committed; not normative.** Companion to [`re-frame-10x-v2-design.md`](re-frame-10x-v2-design.md) (rf2-buor). rf2-buor establishes **what** Causa is; this document designs **how it feels**. Visuals are described in prose plus ASCII layout sketches; every pixel size, colour token, and timing is concrete enough that a programmer can implement against it.

---

## §1 — One-sentence pitch + the canonical 30-second experience

**Pitch.** *Causa shows you the cascade your last click triggered, lets you ask why, and answers in five seconds.*

That's the contract. Open it, see a story, find the surprise, close it.

### The first 30 seconds

The programmer installed Causa's `:preloads` and refreshed. They click around the app, log in, navigate. A button doesn't do what they expected.

**T+0.0s.** `Ctrl+Shift+X`. A 320ms slide-in from the right edge covers 40% of the window. First frame paints in under 80ms — Causa was mounted, just hidden. No splash, no "loading."

**T+0.3s.** They see this:

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                                                                                  │
│  [APP CONTENT — DIMMED 12%, INTERACTIONS PASS THROUGH]                          │
│                                                                                  │
│                                                                                  │
│                                                                                  │
│                                                                                  │
│                                                                                  │
├──────────────────────────────────────────────────────────────────────────────────┤
│ ╭─ CAUSA ───────────────────────────────────────────────────────── :app/main ▾─╮ │
│ │ ◆──○──○──○──○──○──○──○──○──○──●           ⚠ 0   ⏵◀ epoch 11/11  ⌘K   ?  ✕ │ │
│ ├──────────────────────────────┬───────────────────────────────────────────────┤ │
│ │ ◉ Events                     │ ╭─ event 11 ────────────────────────────────╮ │ │
│ │ ○ App-db                     │ │ :checkout/submit                          │ │ │
│ │ ○ Causality                  │ │ source • src/cart/events.cljs:213         │ │ │
│ │ ○ Subscriptions              │ │ origin • :app                             │ │ │
│ │ ○ Effects                    │ │ caused-by • :cart/finalise (epoch 10)     │ │ │
│ │ ○ Trace                      │ │                                           │ │ │
│ │ ○ Machines                   │ │ db-diff   • [:cart :submitting?] → true   │ │ │
│ │ ○ Flows                      │ │            • [:cart :error] → nil         │ │ │
│ │ ○ Performance                │ │ fx fired  • :http (POST /orders)          │ │ │
│ │ ○ Issues                     │ │            • :rf.machine/send → :auth     │ │ │
│ │ ○ Routes                     │ │ subs run  • 4 recomputed, 18 cached       │ │ │
│ │ ○ Schemas                    │ │ renders   • 3 views                       │ │ │
│ │ ○ Hydration                  │ │ duration  • 4.3ms                         │ │ │
│ │ ○ Co-pilot                   │ ╰───────────────────────────────────────────╯ │ │
│ │ ○ Settings                   │                                               │ │
│ ╰──────────────────────────────┴───────────────────────────────────────────────╯ │
│ ◀◀ ─────────────────●──── ▶▶   :app/main   11 epochs   8.2KB                   │ │
╰──────────────────────────────────────────────────────────────────────────────────╯
```

The top strip — the **causality strip** — shows the last 11 dispatches as pills. The rightmost is filled (●), the others are hollow (○). The leftmost has a diamond (◆) marking a cascade root. The frame picker says `:app/main` because there's only one frame.

The main canvas shows the **most recent epoch's detail**: the event vector, where it came from, what caused it, the app-db diff, fx fired, sub recomputes, renders, and timing. Everything important about the last dispatch is in one panel. No graph yet. No scrubber action yet.

**T+1.5s.** Their eye lands on the diff: `[:cart :submitting?] → true`. The dispatch happened. The `caused-by` link reads `:cart/finalise (epoch 10)`.

**T+3s.** They press `[`. The scrubber steps back one epoch. Detail rebases to epoch 10; the causality strip's filled pill slides left. The live app behind Causa **does not** rebase visually — time-travel requires explicit opt-in via the rewind button. (Default is "show me history without affecting the live app." v1 of 10x got this wrong; v2 inverts it.)

**T+5s.** Epoch 10's detail: `:cart/finalise` ran, fx `:http` POSTed `/cart`, completed, dispatched `:checkout/submit`. The chain is there. `]` returns to current. They press `c` (causality view).

**T+6s.** 180ms cross-fade to a vertical graph: epoch 10 at the top, epoch 11 below, edge labelled `:fx [[:dispatch ...]]`. Forks from epoch 10 to `:http POST /cart`, from epoch 11 to `:http POST /orders`.

**T+10s.** Click `:http POST /orders`. Popover: URL, headers, status `pending`. The order request never resolved. *That's* the bug.

**T+12s.** `Esc`. Causa retracts. They open their network panel and find a CORS error.

**Muscle memory built:** `Ctrl+Shift+X` (open) → glance at strip → read detail → `[`/`]` to walk history → `c` for the graph when the chain matters → `Esc` to close. One hand, eyes on the panel, no tool-switching.

---

## §2 — The interface, sketched in prose

Causa fills a panel along the **right edge** of the viewport by default, taking 40% of the window width (resizable). It is **not** a bottom panel because right-edge gives more vertical real estate for the graph and the event detail. The user can pop it out to a separate window (`Ctrl+Shift+P`) or detach the right rail to a separate monitor.

### Top region — causality strip + global filters + frame picker

Pinned. 56px tall on cosy density.

```
╭─ CAUSA ───────────────────────────────────────────────────── :app/main ▾ ──╮
│ ◆──○──○──○──○──○──○──○──○──○──●        ⚠ 0   ⏵◀ epoch 11/11   ⌘K   ?   ✕ │
╰─────────────────────────────────────────────────────────────────────────────╯
```

**Causality strip (left two-thirds).** Horizontal row of dispatch pills, oldest left, newest right. Each pill 24×24px on cosy density with 4px gap. Most-recent pill filled in the frame's accent (default violet `#7C5CFF`); older pills hollow (1px stroke). Cascade roots (no `:parent-dispatch-id`) are diamonds (◆); children are circles (○). Strip scrolls horizontally (default scrolled to right-edge); buffer 200 deep (Spec 009).

Hover pill → 240ms popover with event vector, source coords, duration, epoch-id. Click → detail panel rebases. Right-click → context menu (Replay, Re-dispatch, Copy, Open source, Filter graph to this cascade).

**Pill colours.** Violet `:app`; indigo `:pair`; cyan `:story`/`:test`; red (cascade contained error); amber (warning / schema-replaced-with-default); grey (registry churn, hidden by default).

**Right side.** Issues badge (`⚠ N`, red+pulse on new), epoch counter (`epoch 11/11`; click for numeric input), `⌘K`, `?`, `✕`.

**Frame picker** in the title bar (`:app/main ▾`). Dropdown over `(rf/frame-ids)`; collapses to static label when there's only one frame.

### Left sidebar — panel navigation + density toggle

Pinned. 192px wide on cosy density; 56px when collapsed (icons only).

```
┌──────────────────────────────┐
│ ◉ Events                     │ ← active panel
│ ○ App-db                     │
│ ○ Causality                  │
│ ○ Subscriptions              │
│ ○ Effects                    │
│ ○ Trace                      │
│ ─                            │
│ ○ Machines           ●●●     │ ← active count
│ ○ Flows                      │
│ ○ Performance                │
│ ○ Issues             ●3      │ ← issues count
│ ○ Routes                     │
│ ─                            │
│ ○ Schemas            ◌       │ ← dormant
│ ○ Hydration          ◌       │
│ ─                            │
│ ○ Co-pilot                   │
│ ○ Settings                   │
│ ▥ ▥▥ ▥▥▥                    │ ← density toggle
└──────────────────────────────┘
```

Three groups, divider-separated: **hot panels** (Events / App-db / Causality / Subscriptions / Effects / Trace) always at top; **conditional panels** (Machines / Flows / Performance / Issues / Routes) with activity badges; **dormant panels** (Schemas / Hydration) marked `◌` until activity.

**Item shape.** 32px tall; 8px padding. Active item: 2px violet left-border + `bg-active` tint. Hover: 1px grey left-border at 50% opacity. **Badges** to the right: `●` (active, no count), `●3` (numeric count), `●●●` (multiplicity), `◌` (dormant). Density toggle at bottom cycles compact → cosy → comfy; persists to schema'd localStorage.

### Main canvas — whichever panel is active

Fills the remaining space. The active panel's content. The canvas is **everything between the top strip, the sidebar, and the bottom rail**. Scrollable internally; the canvas-internal scroll is independent of the panel chrome.

Every panel has the same chrome:
- **Title bar** (40px tall): panel name on the left, panel-specific actions on the right (filter chips, refresh, expand).
- **Content area**: panel-specific. Most panels are split into a list + detail view; the canvas decides the split orientation based on width (vertical split when wide, horizontal when narrow).

### Right rail — AI co-pilot (when open)

Detachable, **closed by default**. Toggled with `Ctrl+Shift+/` or sidebar click. 320px wide on the right side of the canvas. Below 1200px viewport, takes over the full canvas when active.

```
┌──────────────────────────────────────┐
│  AI Co-pilot                ⌗ ⛶  ✕  │  ← title, model picker, expand, close
├──────────────────────────────────────┤
│  ▸ Why did :checkout/submit fire?    │
│    It was dispatched by an           │
│    :fx [[:dispatch ...]] inside the  │
│    :cart/finalise handler            │
│    (events.cljs:213). That handler   │
│    ran because of                    │
│    :user/clicked-checkout at         │
│    10:43:21.                         │
│    [ Open source ] [ Show graph ]    │
│  ─────────────────────────────────── │
│  ▸ What's the auth-flow state?       │
│    :auth/login-flow → :authenticating│
│    Parent :login is :open. Pending   │
│    :after timer at 5000ms, 2.1s left.│
│    [ Open machine chart ]            │
├──────────────────────────────────────┤
│ ╭──────────────────────────────────╮ │
│ │ Ask anything…                  ↑ │ │
│ ╰──────────────────────────────────╯ │
│ /slash for commands · 🎙 to dictate  │
└──────────────────────────────────────┘
```

Conversation newest-at-bottom. Each turn: question (▸ in violet) → response. Responses contain inline **action chips** that act on the main canvas, **embedded data** (mini app-db trees, tables, source-coord blocks) with the same visual language as panels, and source-coord chips that open in the editor. Input is multiline; `↑` submits, `/` triggers slash commands, `🎙` triggers browser STT.

### Bottom rail — time-travel scrubber + issues badge

Pinned. 40px tall on cosy density.

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ ◀◀ ─────────────────●──── ▶▶   :app/main   11/11 epochs   8.2KB   ⚠ 0   ●●●    │
└──────────────────────────────────────────────────────────────────────────────────┘
```

**Scrubber (left half).** A horizontal track with one tick per epoch in the current frame's `epoch-history`. The current position is a violet filled circle. `◀◀` jumps to oldest; `▶▶` jumps to newest. The track is **draggable**.

**Default behaviour: passive scrubbing.** Dragging the scrubber **does not** rewind the app — it just rebases the panel's view of history. The detail panel rebases. The graph rebases. App-db panel rebases. But `(rf/get-frame-db ...)` returns the live value still. The user must click `Rewind here` (a contextual button that appears 200ms after a drag ends) to actually call `restore-epoch`.

This is the v1-of-10x mistake-not-repeated. `app-db-follows-events?` was too implicit — users accidentally rewound. v2's posture: **inspection is the default, rewind is opt-in.**

**Right side of bottom rail.** Frame name, epoch counter, scrubber buffer size, issues count, machine activity dots.

**Issues badge** (`⚠ N`). Same as in the top strip — duplicated here for glance-ability. New issues cause a 600ms red pulse.

### Modal layers

Three modal surfaces float over the chrome:

- **Command palette (`Ctrl+K`).** Centred 560px modal, 50% height. Input on top, fuzzy-matched result list grouped by Events / Handlers / Panels / Commands / Settings (see §6 for the full index). Type → filter live → arrows → Enter. The spine of expert workflows.
- **Keyboard cheat-sheet (`?`).** 480px modal listing every shortcut grouped by category. Static (Ctrl+K covers search).
- **Settings (`,` from anywhere except a text input).** 640×480px modal: Theme, Density, Keybindings, AI provider, Buffer depths, Frame defaults, Telemetry (always off; statement of why we don't ship any).

### Narrow viewport — what collapses

Below 1200px viewport width:
- Sidebar collapses to icons-only (56px).
- Co-pilot rail collapses to 0; the panel takes over the full canvas when opened.

Below 900px viewport width:
- Causa's panel takes 100% of viewport width (overlays the app).

Below 600px viewport (phones): **Causa refuses to mount.** The DOM root is created but the visible UI is a single message: "Causa needs a larger viewport. Open from a desktop or tablet." This is per the §10.9 lock.

### What's keyboard-reachable

Everything. The chrome has a strict tab order: top-strip controls → sidebar items → canvas content (focus enters the active panel) → bottom rail controls. `Esc` always returns focus to the canvas. The co-pilot rail is in the tab order when open.

---

## §3 — The five canonical user journeys

These are the five experiences Causa earns its keep with. For each, the user starts with Causa **closed**, mid-debugging session.

### Journey 1 — "Why did this event fire?"

User saw `:checkout/submit` dispatch in console; doesn't remember writing code that fires it.

1. `Ctrl+Shift+X`. Causa opens. Rightmost strip pill is `:checkout/submit`. Detail panel: `caused-by • :cart/finalise (epoch 10)`.
2. Press `c` (causality view). Vertical graph: epoch 11 at bottom, edge `:fx [[:dispatch ...]]` up to epoch 10 (`:cart/finalise`), edge up to epoch 9 (`:cart/http-success`).
3. Click `:cart/finalise` → popover shows `src/cart/events.cljs:213`. Press `o` → editor opens at line 213. Code reads `:fx [[:dispatch [:checkout/submit]]]`. Answered.
4. `Esc`.

Three keys (`Ctrl+Shift+X`, `c`, `o`), no mouse, no search. The detail panel answers most of the question by itself; the graph is the deeper-walk option.

### Journey 2 — "What did that event change?"

User notices `[:cart :items]` is unexpectedly empty in app-db.

1. **T+0.** `Ctrl+Shift+X`, click `App-db`. The tree opens with the most recently-changed paths in a fading 400ms yellow flash. `[:cart :items]` is dim — it didn't change in this epoch.
2. **T+2s.** `Ctrl+F`, type `:items`. The tree filters to `[:cart :items]`. Value: `[]`. Right-click → `Show me when this changed`.
3. **T+4s.** Canvas pivots to a list of epochs that touched the path. Four entries. Click `:cart/clear` (epoch 3). Detail rebases. The diff shows `[:cart :items] [{:sku "abc" :qty 2}] → []`. The fx list shows `:rf.machine/send → :cart-machine :submit`.
4. **T+7s.** Press `c` for the causality graph at this epoch. Parent of `:cart/clear` is `:user/clicked-logout`. **Bug found:** logout is clearing the cart unintentionally.

The "show me when this changed" right-click is what turns "I notice this is wrong" into "show me when it became wrong" in two clicks.

### Journey 3 — "Why is this subscription returning the wrong value?"

`:cart/total` returns `0` instead of `42`.

1. `Ctrl+Shift+X` → `Subscriptions`. Canvas shows the sub-dependency graph. Type `:cart/total` in the panel's search.
2. The graph filters to `:cart/total` and its transitive inputs. Each node shows its current value inline: `:cart/items = []`, `:product/price-map = {...}`, `:cart/items-with-prices = []`, `:cart/total = 0`. The bisection is visual: trace from the wrong output backward to the empty input.
3. Click `:cart/items`. Side popover: sub source, cache state, most recent recompute trigger (`:cart/clear`). Same root as Journey 2, found from the other direction.

The sub graph reads like a circuit diagram: outputs on top, inputs at the bottom, bad value dim.

### Journey 4 — "Why is this view re-rendering?"

A view re-renders on every keystroke.

1. `Ctrl+Shift+X` → `Performance`. The ribbon shows a bar per epoch; every epoch in the last 30s has one. Typing is causing dispatches.
2. Press `r` (renders panel). Table of views with re-render counts. `cart-summary-view — 14 renders` is the outlier. Click.
3. Detail: `:render-key [cart-summary-view "instance-7c2"]`. Subs deref'd: `[:input/draft :cart/items :cart/total]`. Recompute counts across the 14 renders: `:input/draft = 14, :cart/items = 0, :cart/total = 0`. **The view derefs `:input/draft`** — which the user didn't intend.
4. Open source, find the stray `@(subscribe [:input/draft])`.

Render-key attribution (`:render-key` projection in `:rf/epoch-record :renders`) is the magic. The user doesn't instrument anything; the runtime records what each render derefed.

### Journey 5 — "What's currently broken?"

App feels off, no console error.

1. `Ctrl+Shift+X`. Top-right badge says `⚠ 3`. Press `i`.
2. Issues panel: a schema violation (`[:auth :email]` expected `:string`, got `nil`, recovery `replaced-with-default`), a hot-reload warning (handler replaced 47 times this session), a long task (epoch 14 took 312ms — INP threshold).
3. Click the schema violation. Detail shows the Malli explanation, the offending value, and `Show me the dispatch that caused this`. Clicking jumps to the causality graph at that epoch — the dispatch was `:user/load-profile`; the server returned `nil` for `:email`.

The issues feed is a permanent ribbon, not a console one-liner that scrolls past. "Show me the dispatch that caused this" is the high-leverage affordance.

---

## §4 — The causality graph: hero or one-of-many?

**The decision: demote the graph; the flat event log + causality strip carry the weight.** Option (B) in the brief.

Here's why. The flat detail-and-strip pair answers every one of the five canonical journeys above on its own. The graph view appears in journeys 1 and 2 (and Issues drills into it from journey 5) as a deeper-walk option — but in none of them is it the entry point. In every one, the user lands on the flat detail panel, glances, follows a `caused-by` link or a path-changed link, and only opens the graph when the chain is more than two hops long.

This is the difference between **a graph that's visible because the answer is in it** and **a graph that's visible because we made it the front door**. The former earns its keep; the latter is impressive-but-unused.

### What stays of the graph

The causality view is still a first-class panel (sidebar entry, shortcut `c`). It's excellent when the cascade is > 2 hops, spans frames, or when triaging a session with 30 events. It's the right tool for those moments; it's just not the front door.

The graph also lives as a **micro-view inside the event-detail panel**: when the current epoch has a `:parent-dispatch-id`, the detail shows a small inline graph (3–5 nodes) of the local cascade above the event vector — a causal breadcrumb. Costs 80px of vertical space; gives the user the chain at a glance without committing to the full graph view.

```
┌─ event 11 ───────────────────────────────────────────────╮
│                                                          │
│      ●  :user/clicked-checkout                           │
│      │                                                   │
│      ●  :cart/finalise                                   │
│      │                                                   │
│      ●  :cart/http-success                               │
│      │                                                   │
│      ◉  :checkout/submit          ← you are here         │
│                                                          │
│  ─────────────────────────────────────────────────────── │
│                                                          │
│  :checkout/submit                                        │
│  source • src/cart/events.cljs:213                       │
│  origin • :app                                           │
│  caused-by • :cart/finalise (epoch 10)                   │
│  …                                                       │
╰──────────────────────────────────────────────────────────╯
```

### What fills the hero slot

**The event detail panel** is the hero — the canonical-question-answering one §1 sketched. First thing the user sees, where they land 90% of the time, the panel that decides whether the next click is "found it" or "need to dig." It answers all five canonical questions in one click:

- Q1 (why did this fire?) — `caused-by` + inline mini-graph.
- Q2 (what changed?) — `db-diff` rows.
- Q3 (why is this sub stale?) — `subs run` row links to the sub graph at this epoch.
- Q4 (why re-rendering?) — `renders` row links to render attribution.
- Q5 (what's broken?) — `issues` row lists schema violations / errors tagged to this epoch.

The graph is the second-click answer when the first click wasn't enough.

### What the demotion means for rf2-buor §6

The 16-panel list survives; the marketing changes. "The cascade you can see" becomes a tagline about *the chain Causa renders inline* (in the detail panel and the strip), not the graph view itself. Causality is a peer panel, not a hero. The graph is still striking when opened — but Causa earns its keep at the detail panel; the graph is the bonus.

**Trade-off acknowledged.** We're trading a demo-friendly front door for a habit-friendly one. The risk: colleagues demo the graph, not the detail panel; marketing loses a screenshot. The bet: habit beats demo. If usage shows programmers spending 80% of their time in the graph, we revisit — Causa can self-instrument panel-view durations. Low-cost reversal.

---

## §5 — Visual language

### Typography

Two typefaces. No more.

- **UI sans:** `Inter` (variable, wght 400–700), fallback `system-ui` / `-apple-system` / `Segoe UI`. ~80KB WOFF2. Used for labels, headings, sidebar, modals, buttons, popovers.
- **Data mono:** `JetBrains Mono` (variable, wght 400–700), fallback `ui-monospace` / `SF Mono` / `Menlo`. ~100KB WOFF2. Used for app-db trees, event vectors, source coords, code blocks, EDN.

**Sizes (cosy density).** Display 16px/1.4/600 (panel titles); body 14px/1.5/400; mono body 13px/1.45/400 (mono renders larger at equal point size, so 1px down); caption 12px/1.4/400; micro 11px/1.2/600 (badges, tabs). **Compact:** −1px, tighter line-heights (1.3 / 1.35). **Comfy:** +1px, looser (1.6 / 1.55). Below 10px refused.

### Colour system

Two themes (dark default, light second). Both **WCAG AA** minimum on text-against-background; AAA on the high-contrast variant (v1.1 per rf2-buor).

**Dark theme tokens:**

```
Surfaces:  bg-0 #0E0F12 (backdrop)  bg-1 #15171B (sidebar, top strip)
           bg-2 #1B1E24 (panels)    bg-3 #232730 (popovers)
           bg-active #2A2F3D (hover, selected)
Borders:   subtle #232730  default #2F3441  strong #444B5B
Text:      primary #E8EAF0  secondary #A8AEC0  tertiary #6B7080  disabled #494E5A
Accents:   violet  #7C5CFF  brand, current epoch
           indigo  #5570FF  :pair-origin
           cyan    #43C3D0  :story/:test origin, info
           green   #4ADE80  success, additions, machine-active
           yellow  #FBBF24  warnings, schema-replaced-with-default
           orange  #FB923C  long-task
           red     #F87171  errors, schema-violations, hydration-mismatches
           magenta #E879F9  AI co-pilot highlight
Perf:      fast #4ADE80 (<16ms)  medium #FBBF24 (16-50)  slow #FB923C (50-100)
           blocking #F87171 (>100ms, INP threshold)
```

**Light theme** inverts lightness (`bg-0 #FAFBFC`, `bg-1 #F1F3F6`, `bg-2 #FFFFFF`); accents darkened slightly to maintain contrast. **High-contrast variant** uses pure black/white with maximum-saturation accents.

**Colour is never alone.** Every coloured marker pairs with a shape or icon: errors (red dot + `!` icon + "Error" label), schema violations (yellow triangle + path), pair-origin (indigo + `🔗`), active machine (green + filled glyph; idle: hollow).

### Spacing scale

4px grid. Everything is a multiple.

- `space-0` = 0px (collapsed)
- `space-1` = 4px (tight, badges-to-text gap)
- `space-2` = 8px (default inline gap, button padding)
- `space-3` = 12px (section spacing)
- `space-4` = 16px (panel padding)
- `space-5` = 24px (between sections)
- `space-6` = 32px (panel-level separators)
- `space-8` = 48px (rare; modal margins)

Border-radius: `radius-sm` 4px (buttons, chips), `radius-md` 8px (panels, popovers), `radius-lg` 12px (modals).

### Iconography

Single icon set: **Lucide** (open-source, ~1000 icons, programmer-friendly). 1.5px stroke. Sizes 14 / 16 / 20px (inline in body, sidebar, modal headers respectively). 100ms hover fade to `accent-violet` or context-sensitive accent; no size change on hover. Causa-specific custom icons: `◆` cascade root, `●` filled node, `○` hollow node, `◉` selected node, `↺` rewind, `🎙` voice.

### Motion + animation

Animation communicates, not decorates. Three durations: **quick** (100ms: hover, focus rings), **standard** (200–250ms: panel switches, scrubber drag-snap, sidebar collapse), **slow** (400–600ms: diff flashes, error pulses, the 320ms Causa slide-in). Easings: default `cubic-bezier(0.4, 0, 0.2, 1)`; entering `cubic-bezier(0, 0, 0.2, 1)`; exiting `cubic-bezier(0.4, 0, 1, 1)`.

Specific motions: panel switch 180ms cross-fade; detail diff flash 400ms yellow→transparent; error pulse single 600ms expand-fade red ring (no looping); machine-active state 1.2s gentle scale 1.0→1.05→1.0 (only continuous animation in chrome, only on the machine chart node); co-pilot streaming typewriter ~25 chars/sec.

**Reduced motion** respects `prefers-reduced-motion: reduce`: all durations clamp to 0 except a 1-frame opacity tween where layout needs to settle. The error pulse becomes a static red ring for 1.5s; the machine-pulse stops entirely.

### Density gradient

Three settings. Affects:
- **Compact:** font sizes shrink 1px; vertical paddings 50%; sidebar 160px wide; pill 20px; epoch ticks 4px gap.
- **Cosy** (default): the spec everything else in this doc references.
- **Comfy:** font sizes grow 1px; vertical paddings 150%; sidebar 224px wide; pill 28px; epoch ticks 6px gap.

What does *not* change between densities: icon weights, border radii, animation durations, accent colours. Density is a vertical-rhythm knob, not a redesign.

### Theming as a token system

All values above are CSS custom properties under a single root. Themes are 50-line CSS files. Users can drop a custom theme via Settings → Theme → Load CSS. Default theme files are bundled; the runtime exposes `(rf.causa/load-theme css-string)` for programmatic loading (handy for editor-driven palette sync).

---

## §6 — Keyboard and command palette

### Shortcut map (complete)

**Global (always active when Causa is open):**

| Key | Action |
|---|---|
| `Ctrl+Shift+X` | Toggle Causa |
| `Ctrl+Shift+P` | Pop out to separate window |
| `Ctrl+K` | Command palette |
| `?` | Keyboard cheat-sheet |
| `,` | Settings |
| `Esc` | Close modal / collapse popover / focus canvas |
| `Ctrl+Shift+/` | Toggle co-pilot rail |
| `Ctrl+F` | Find within active panel |

**Navigation (within Causa, when focus is on chrome or panel):**

| Key | Action |
|---|---|
| `j` / `k` | Next / previous in any list (event log, scrubber positions, sidebar) |
| `g g` | Jump to top |
| `G` | Jump to bottom |
| `[` / `]` | Previous / next epoch (rebases detail panel without rewinding live app) |
| `Shift+[` / `Shift+]` | Previous / next cascade root |
| `Tab` / `Shift+Tab` | Move between regions (sidebar / canvas / co-pilot) |

**Panel jumps (mnemonics):**

| Key | Panel |
|---|---|
| `e` | Events |
| `a` | App-db |
| `c` | Causality graph |
| `s` | Subscriptions |
| `f` | Effects (fx) |
| `t` | Trace |
| `m` | Machines |
| `w` | floWs |
| `p` | Performance |
| `i` | Issues |
| `r` | Routes |
| `S` | Schemas |
| `h` | Hydration |
| `/` | Co-pilot (with input focused) |
| `,` | Settings |

(`a` was chosen over `d` for App-db so the more-frequent app-db opens cheaply; `w` over `f` for Flows so `f` stays for fx.)

**Event actions (when an event/epoch is focused):**

| Key | Action |
|---|---|
| `Enter` | Open detail |
| `o` | Open source in editor (URL handler) |
| `R` | Re-dispatch this event |
| `r` | Rewind to before this event (calls `restore-epoch`) |
| `Shift+r` | Hard rewind (with `restore-epoch` failure modes shown) |
| `f` | Filter the causality graph to this dispatch's cascade |
| `Ctrl+C` | Copy event vector to clipboard |
| `Ctrl+Shift+C` | Copy source coord |

**Scrubber:**

| Key | Action |
|---|---|
| `Space` | Toggle play (auto-step at 500ms intervals) |
| `Shift+Space` | Slow play (1000ms intervals) |
| `0` | Jump to oldest epoch |
| `$` | Jump to newest epoch |
| `*` | Pin a snapshot at current epoch |

**Co-pilot:**

| Key | Action |
|---|---|
| `/` (from chrome) | Focus co-pilot input |
| `Enter` (input focused) | Submit |
| `Shift+Enter` | New line in input |
| `Ctrl+L` | Clear conversation |
| `Ctrl+P` | Previous question |
| `Ctrl+N` | Next question |

### Command palette (`Ctrl+K`)

The spine of expert workflows. Every interaction is reachable from here, fuzzy-search.

**Indexed sources** (matched together, recency-weighted, command-boosted): recent events (200-entry buffer; matches event-id + source coord), registered handlers (id + `:doc`), frames, machines with current state, flows, subs, panel names, command verbs (Rewind / Re-dispatch / Snapshot / Filter / Pin / Pop-out / Switch frame / …), settings entries, recent co-pilot questions. Fuzzy match splits on camelCase / kebab-case / namespace boundaries.

**Empty palette is never empty.** With no input, the top of the list shows context-aware suggestions — actions for the active panel, "Open source" / "Rewind here" when an event is selected, "Investigate :rf.error/handler-exception" when a fresh error landed.

**Rows.** 40px tall (compact 32, comfy 48): 16px type icon, label, right-aligned hint (`epoch 11`, `events.cljs:213`, shortcut). Arrows; Enter invokes; `Ctrl+Enter` invokes in a pop-out.

### How experts move

Hands stay on the keyboard. A common expert pattern:
- `Ctrl+Shift+X` (open).
- `Ctrl+K`, type `submit` (palette filters to events containing "submit"), `↓ Enter` (select the second one).
- Causa jumps to that event's detail. Cascade visible inline.
- `c` (graph), then `r` (rewind to here).

Total time: ~3 seconds. Three keys + one term.

### Discoverability for new users

Three layers:

1. **The `?` cheat-sheet.** A modal showing every shortcut. Discoverable from the top strip (`?` icon). On first open, Causa flashes a 800ms violet ring around the `?` icon for one cycle (then never again).

2. **Empty-state hints.** When a panel is empty, the empty state shows a contextual keyboard hint: "No events yet. Press `Ctrl+Shift+X` again to close, or click your app." When an event is selected for the first time, a popover (4-second auto-dismiss) suggests "Press `c` for the causality graph."

3. **The command palette itself.** Typing `?` in the palette filters to commands and shows their shortcuts. The palette is the doc.

We do not ship a tour or onboarding flow. Per the §11 anti-temptations: Causa is a tool, not an experience.

---

## §7 — Empty states and edge cases

### First-time use

Before the user has interacted with the app, the main canvas shows:

```
┌─ event 0 ─────────────────────────────────────────────────╮
│   Causa is watching.                                       │
│   No events yet. Click around your app — every dispatch   │
│   will land here.                                          │
│                                                           │
│   ▸ Open the keyboard cheat-sheet                  ?      │
│   ▸ Open the command palette                       ⌘K     │
│   ▸ Try the co-pilot                              ⌃⇧/     │
│                                                           │
│   Tip: ] jumps to the most-recent epoch from anywhere.    │
╰───────────────────────────────────────────────────────────╯
```

The strip shows a single hollow circle labelled `(boot)` for the framework's boot epoch. The sidebar shows hot panels at top, conditional panels dimmed, dormant panels (Schemas, Hydration) marked with `◌`. After first interaction, the tips disappear; "No events yet" persists until first dispatch.

### No errors / no schema violations / no SSR

These panels still appear in the sidebar with `◌`. Clicking shows a graceful empty state explaining the feature and linking to docs. Example for Schemas:

```
   No schemas registered.
   Once your app registers schemas via :app-schemas (Spec 010),
   validation events will appear here:
   • Schema violations
   • Path-typed lookups
   • Schema-replaced-with-default events
   → Read about schema integration
```

No marketing — just a pointer for users who didn't know the feature exists.

### Many panels are empty most of the time — the discoverability problem

Per rf2-buor's §Self-criticism. 16 panels, ~6 always-active, ~5 conditional, ~5 dormant. The risk: **the sidebar feels cluttered; dormant panels feel like dead weight.**

Solution (concrete):

1. **Three sidebar groups, separated by dividers.**
   - **Always-active** (Events / App-db / Causality / Subscriptions / Effects / Trace): always shown, always pinned at top.
   - **Conditional with activity** (Machines / Flows / Performance / Issues / Routes): shown when activity has occurred in the session, marked with activity badges. When no activity, collapsed under a `+5 more` row.
   - **Always-dormant** (Schemas / Hydration / Co-pilot / Settings): shown but visually de-emphasised (`text-tertiary` colour, `◌` indicator) until activity occurs.

2. **Right-click sidebar → "Show all panels."** Power-users get the full list. Persisted.

3. **Activity-driven re-sorting (off by default; opt-in in Settings).** "Surface most-active panels at top." Helpful for users with one machine-heavy app and another schema-heavy app.

4. **The `+5 more` row.** Above the always-dormant divider, a single row collapses unused conditional panels. Clicking expands inline. This solves "the sidebar feels too long" without burying anything.

### Long debug sessions — when does it feel sluggish?

Causa's targets (per rf2-buor §8):
- Trace buffer 200 entries.
- Epoch history 50 per frame.
- App-db diff O(changed paths).
- Causality graph caps at 200 dispatches.

When the user is approaching limits:
- **Epoch history fill ratio** shown in the bottom rail (`11/50 epochs` early in session; `49/50 epochs` near full). Hover → "Older epochs will age out. Increase depth in Settings.")
- **Trace buffer fill** is visible in the Trace panel (`190/200`).
- When **a single epoch's render takes > 50ms** (the causality graph re-laying out, the app-db tree expanding a 100k-node sub-tree), Causa shows a one-time toast: "Rendering is slowing. Consider reducing scrubber range or filtering events." Click → opens Settings → Performance.

We **do not** ship a "Causa is using N% of your runtime" indicator. The runtime cost is fixed (Spec 009 guarantees µs-scale) and a percentage indicator would imply the user has a per-cycle decision to make. They don't.

### Multi-frame apps — 1 / 3 / 10 frames

- **1 frame:** picker is a static label (`:app/main`). No dropdown clutter.
- **3 frames:** picker is a dropdown with current at top and recent-event timestamps for the others. Switching re-binds every panel in <200ms.
- **10 frames** (Story apps, multi-frame SaaS): dropdown becomes a search box; type-to-filter; most-recently-active at top. A "show all frames" button opens a Frames panel listing every frame's epoch count, last activity, current state.

**Cross-frame events.** When a dispatch in frame A causes a dispatch in frame B (cross-frame fx per Spec 002), the causality graph shows both frames as swimlanes with explicit cross-lane arrows. Causality strip pills get a thin coloured ring indicating frame. No other JS tool can render this.

### Frame destroyed mid-look

Per Tool-Pair's destroyed-frame contract. The panel shows a banner ("Frame `:story.auth/login-form` was destroyed"), the picker auto-switches to the next-most-recently-active frame, the bottom rail shows "Last epoch: 11. Buffer aged out 0 epochs." The panel is not auto-closed — the user can still scrub the destroyed frame's history.

### Production builds

Per Spec 009, Causa's surface elides in prod (`goog.DEBUG=false`). The launch button doesn't render; `Ctrl+Shift+X` does nothing. CI verifies via `npm run test:elision`. In a non-elided dev build running in production-like conditions, Causa shows a yellow top banner: "Causa is enabled in this build. Disable for production." Single-click dismiss, remembered for the session.

---

## §8 — AI co-pilot UX specifics

The co-pilot panel is **detachable**, **rail-by-default**, **never full-screen-mandatory**.

### Input affordances

**Primary: text.** A multiline `<textarea>`-equivalent (auto-grows to 8 lines). Submit on `Enter` (without modifiers). `Shift+Enter` for new line. Maximum 1000 characters; soft warning at 800.

**Slash commands.** Typing `/` at the start opens a command dropdown:
- `/explain <event-id-or-epoch>` — describe one epoch.
- `/diff <epoch-a> <epoch-b>` — what changed between two epochs?
- `/find <pattern>` — search the trace stream.
- `/rewind <event-or-epoch>` — propose a rewind (executes on confirmation).
- `/state <machine-id>` — describe a machine's current state.
- `/why <epoch>` — causal-ancestor walk.
- `/whatif <hypothetical>` — speculative reasoning (the model warns it's reasoning, not measuring).
- `/clear` — clear conversation.

**Voice (browser STT).** A `🎙` button next to the submit arrow. Click → uses `webkitSpeechRecognition` / `SpeechRecognition` API. Streaming transcription appears in the input field. Click again or press `Esc` to stop. Voice is **not** sent to a server; the browser does the transcription. Defaults off; opt-in per session.

### Result formatting

Responses are markdown rendered with:

- **Code blocks** in mono with syntax highlighting (cljs / edn / json).
- **Source-coord chips.** `src/cart/events.cljs:213` renders as a chip with a `🔗` icon; click opens the source.
- **Action chips.** `[Open source]`, `[Show graph]`, `[Rewind here]`, `[Filter to this cascade]` — inline buttons in the response.
- **Embedded data.** Small data structures (an app-db slice, a 5-row table) render with the same chrome as the main panels. 200px max height; expandable.
- **Mini-graphs.** A causal chain renders as a 5-node mini graph using the same SVG primitive as the Causality panel; click opens the full panel filtered to that cascade.

Responses are **streamed** (per token, ~25 tokens/sec). First words within ~600ms of Enter. Respects `prefers-reduced-motion`.

### Conversation history

**Per-session by default** (cleared on reload). Optional cross-session persistence to localStorage (opt-in; capped at 1000 turns; oldest evict). Default is per-session because conversation may contain sensitive app data.

`Ctrl+P` / `Ctrl+N` walk previous/next questions. `Ctrl+R` searches within conversation (distinct from global `Ctrl+F`).

### Provider switching

A `⌗` icon next to the panel title opens a dropdown: Claude (default), OpenAI, Gemini, Local (Ollama), Custom. Each uses the user's API key, configured in Settings → AI Provider. Keys are stored locally only. Switching is live; conversation history is sent to the new provider as context.

### Tone / personality

**Direct, observational, programmer-to-programmer.** No preambles, no apologising, no hedging. The system prompt encodes: "You are a debugger's assistant. The user wants answers, not conversation. Cite source coords. Say 'I can't determine that' plainly when you can't. One action at a time. Never invent data. Short answers; detail only when asked." User-editable in Settings → AI Provider → System prompt.

### When the agent is wrong

Three error states:

1. **API error** ("rate limit hit", "network failure"). The panel shows a red banner: "Couldn't reach Claude. Retry in 30s? [Retry now] [Switch provider]." The user's question is preserved.

2. **Model says "I don't know."** The model returns "I can't determine that from the trace I have access to. The trace buffer has the last 200 events. You can see them in the Trace panel. [Open Trace]." No hallucination. Failure case is a navigation aid.

3. **Model is confidently wrong.** Hardest case. The user has to recognise this — there's no automatic detection. Mitigation: every claim the model makes references data the user can verify (source coords, epoch ids, event vectors). When the model says "epoch 10 dispatched `:cart/finalise`," the user can click that link and see whether it's right. If a claim doesn't link to data, it's a tell.

The **failure mode design**: the co-pilot is **a navigator, not an oracle.** It points at the data; the user verifies. We design every response to surface its evidence.

---

## §9 — Empty-panel discoverability

Covered in §7 (the third group treatment, the `+5 more` collapse, the right-click "Show all panels"). Two reinforcing details:

### Activity badges as the primary signal

Each conditional or dormant panel shows an activity indicator:
- `●` (filled): activity now or in last 5 seconds.
- `●N` (numbered): unread count (3 issues, 5 schema violations).
- `●●●`: multiplicity (3 machines currently running).
- (hollow `◌`): no activity this session.
- (no badge): always-active panel (Events / App-db / etc.).

Badges fade in (200ms) on first activity; never fade out — the dot persists for the session as a "this panel has data" signal.

### Power-user enable-all

Settings → Sidebar → **"Show all panels even when dormant"** (toggle). Persisted per-machine. For programmers who want every option on screen.

The hidden assumption to surface: **most users want the simpler view.** The default is "hide what isn't doing anything." The toggle is for the rare user who wants visibility into every option.

### Dormant-but-suggested

Some panels can be dormant *and* highly relevant. Example: a user is debugging a hydration mismatch but hasn't opened the Hydration panel yet. When `:rf.ssr/hydration-mismatch` first fires:
- The Hydration sidebar entry's icon flashes red for 600ms.
- The Issues badge increments.
- The Co-pilot, if active, suggests "I see a hydration mismatch. Open the Hydration panel? [Yes]."

We **don't** auto-open panels. The user is in control of their canvas. We just flag.

---

## §10 — Visual identity

### The mark

A single icon: **three nodes connected by arrows from one parent**. The cascade gesture, abstracted.

```
       ●
      ╱│╲
     ● ● ●
```

Stroke: 2px. Nodes are 5px circles, filled in the accent violet. Connecting strokes arc slightly downward (a 4px curve) rather than running straight — gives the icon a sense of motion, like the cascade is unfolding.

At favicon size (16×16), the curves disappear; it becomes three filled dots with straight lines. At 32×32, the curves return. At 256×256 (README hero), the icon is a confident gesture: one parent node, three children, the violet of `:origin :pair` carried through (per the rf2-buor §12 visual identity lock).

### The wordmark

**Causa** in Inter, 800 weight, all-lowercase, with a slight letter-spacing tightening (-0.02em). Below it, in 14px 400 weight, the tagline: *the cascade you can see.* (Italicised, dimmer.)

Logo + wordmark stack uses:
- Light backgrounds: violet `#7C5CFF` icon, charcoal `#1B1E24` text.
- Dark backgrounds: violet `#9B7EFF` (a brighter violet for contrast), white text.

### The Causa launch button

In re-frame apps, the launch button is a small floating action — when Causa is closed, a 48×48px circular button in the bottom-right corner of the viewport (z-index pinned just below modal overlays, above app content). The icon centred. Subtle 1px violet ring at 60% opacity on idle. Pulses softly when there's an active error in the issues feed (600ms expand-fade pulse, every 4s, max 3 pulses then stops).

`Ctrl+Shift+X` is the primary; the button is the discoverable secondary.

Click → Causa opens (same animation as keyboard shortcut). Right-click → contextual mini-menu (Open / Pop out / Settings / Hide button).

The button itself is **hideable** (settings option for users who only use keyboard). Hidden state persists per-app.

### The favicon

When the standalone viewer is running (remote-attach mode), the page favicon is the mark. 16×16 and 32×32 sizes; both PNG. The 32×32 version uses the curved connecting strokes; the 16×16 falls back to straight lines.

### Standalone viewer chrome

The standalone HTML page (per rf2-buor §8) has its own chrome:

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  ●╲│╱●  Causa                                          ⌗ Connected to localhost:9710  │
├──────────────────────────────────────────────────────────────────────────────┤
│  [ the standard Causa layout fills the rest of the page ]                    │
└──────────────────────────────────────────────────────────────────────────────┘
```

A title bar with the mark, the wordmark, and the WebSocket connection indicator. Connected = green dot; Reconnecting = amber spinner; Disconnected = red dot + "Reconnect" button. The rest of the page is the standard Causa layout.

### README hero image

A 1200×630px PNG (Open Graph dimensions). Dark theme. The mark on the left at 256×256, the wordmark + tagline below. On the right, a screenshot of Causa mid-session: the causality strip, an event detail panel, and a fragment of the co-pilot showing "It was dispatched by an `:fx [[:dispatch ...]]` inside the `:cart/finalise` handler..." The screenshot is real (not mocked); generated from a sample app shipped in `examples/`.

No marketing copy in the image. The product is the screenshot.

---

## §11 — What this design deliberately doesn't do

Temptations rejected:

- **No telemetry sent home.** Causa transmits nothing to Day8, Anthropic, or anywhere. The co-pilot calls the user's chosen provider directly with the user's API key. Settings has a "Telemetry" section that exists solely to state this.
- **No AI-generates-code.** The co-pilot reads state and explains causality. It does not write tests, handlers, sub functions, or code. Code generation belongs to re-frame-pair (editor-side authority).
- **No video / session replay.** No DOM mutation recording, no frame-by-frame visual replay. The scrubber is event-driven time-travel, not visual time-travel. (Sentry / Replay.io stay in their lane; Causa stays in re-frame's data plane.)
- **No "AI debugger that thinks for you."** The co-pilot is a navigator pointing at evidence; the user decides. Never framed as "Causa figures out the bug."
- **No mobile / phone surface** (§10.9 lock). < 600px refuses to mount.
- **No Chrome extension** (§10.3 lock).
- **No session export / import** (§10.5 lock).
- **No fork-from-here** (§10.5 lock).
- **No in-place app-db editing** (§10.4 lock).
- **No onboarding tour.** The empty state is the onboarding. The cheat-sheet is the manual.
- **No dark patterns.** No "rate Causa," no upsell, no marketplace.
- **No third-party plugins at v1.** Every panel is first-party. (Future versions may expose a panel-registration API.)

---

## §12 — Open questions for Mike

Decisions that require Mike's call, not a designer's:

**§12.1 Detail-panel-as-hero (§4).** This design demotes the causality graph from hero to peer. The bet: habit beats demo. The risk: the graph is the screenshot people love; demoting it costs marketing. If you'd rather flip it (graph as default canvas, detail as side popover), the design rearranges cleanly.

**§12.2 Co-pilot default state.** **Locked 2026-05-11: open by default.** The marquee-pull argument wins — Causa's pitch hinges on the AI co-pilot being immediately visible, not hidden behind a shortcut. User can close the rail (it remembers the choice across the session).

**§12.3 The launch button (§10).** A 48×48px floating button in the bottom-right when Causa is closed. Discoverable secondary to `Ctrl+Shift+X`. Some JS devtools don't ship one (Redux, Vue). Keep, drop, or default-hidden-with-settings-toggle?

**§12.4 Source-coord click-through fallback.** Editors with URL handlers (`vscode://`, `idea://`) work out of the box. Editors without (Vim, Emacs, Sublime) fall back to "copy coord to clipboard." Is clipboard enough, or do we ship per-editor preset configurations in Settings?

**§12.5 Conversation persistence default.** Per-session (cleared on reload) in this design — opt-in to localStorage. Privacy bet vs. utility bet. Per-session or persistent-with-opt-out?

**§12.6 Voice STT at v1.** Fully implementable (browser-local, no service dependency) but niche. Ship at v1 or defer to v1.1 to reduce launch surface?

---

## Closing — the bar

The bar this design sets: a programmer hits `Ctrl+Shift+X`, sees the cascade their last click triggered in the detail panel, asks "why?" — either in their head (and finds it via `caused-by` + the inline mini-graph) or out loud (to the co-pilot) — and has the answer in 5 seconds. Causa earns its keep at that bar.

If a panel doesn't help answer "why?" or "what happened?" or "what's broken?", it doesn't ship. We could pile features. Restraint is what keeps the tool habit-forming instead of impressive.

The headline isn't "we built a 16-panel debugger." The headline is "the cascade you can see — and the question you can ask."

— end of UX design draft —
