# History states

Some compound states have a memory. A media player you stop and restart should resume *mid-track*, not jump back to the first second. A wizard you step away from and return to should land on the step you left, not step one. A tabbed settings panel should remember which tab — and how far down each tab was scrolled — across a close/reopen. That "resume where I last was" behaviour is what a **history state** gives you, declaratively, for any [compound state](concepts.md#when-the-machine-grows).

Without it you reach for the obvious workaround — stash the last substate in `:data` on the way out, read it back on the way in, and write the wiring to restore it. History states make that pattern a single node in the transition table, and (because the record/restore lives *inside the snapshot*) they ride undo, time-travel, persistence, and SSR for free. This is xstate's history-state concept, shipped first-class in re-frame2 as a `:type :history` node — same idea, idiomatic spelling.

> History only applies to a **compound** state — a state with its own `:states` and `:initial`. If "which tab" or "which step" is a single flat value, just keep it in [`:data`](concepts.md#the-same-flow-as-a-transition-table) like any other state; that is ordinary modelling, not a feature you need to name. History earns its keep when a *non-trivial nested* configuration is worth remembering across a leave/return.

## A history state is a transition *target*, not a state you occupy

The mental model to get right first: a history state is a **pseudo-state**. You declare it under a compound's `:states` map, right alongside the compound's real substates, but the machine *never sits in it*. Its only job is to be the **target of a transition** — and when a transition resolves to it, it stands in for "the substate this compound was in when control last left it." The runtime resolves it to a real leaf, the entry cascade enters *that* leaf, and the [snapshot](glossary.md#snapshot)'s `:state` records the resolved leaf — never the pseudo-state.

So you write `[:player :hist]` as a transition target the same way you'd write any state path, and it resolves to a recorded (or default) configuration instead of to a fixed state.

## A worked example — a media player that resumes

Here is a player with a `:tray` (no disc) and a `:player` compound (disc inserted). The `:player` compound owns a `:type :history` pseudo-state. `:eject` leaves `:player` for `:tray`; `:insert` comes back in *through history*, restoring whatever the player was doing when it was ejected:

```clojure
(rf/reg-machine :media-player
  {:initial :tray
   :states
   {;; No disc. :insert re-enters :player THROUGH the history pseudo-state.
    :tray
    {:on {:insert [:player :hist]}}

    ;; Disc inserted — the compound whose configuration we remember.
    ;; :eject leaves :player entirely, which is what makes it record.
    :player
    {:initial :stopped
     :on      {:eject :tray}
     :states
     {:hist    {:type :history
                :deep? true                 ;; omit ⇒ SHALLOW (see below)
                :default-target :stopped}   ;; where the FIRST :insert lands
      :stopped {:on {:play [:player :playing]}}
      :playing {:initial :at-start
                :on      {:stop  [:player :stopped]
                          :pause [:player :paused]}
                :states  {:at-start  {:on {:seek :mid-track}}
                          :mid-track {}}}
      :paused  {:on {:resume [:player :playing]}}}}}})
```

A machine **is an event handler** ([this is the load-bearing difference from xstate](coming-from-xstate.md) — there is no actor to `send` to), so you drive it with ordinary dispatched events and read it with an ordinary subscription:

```clojure
(rf/dispatch [:media-player [:insert]])  ;; :tray → nothing recorded yet → :default-target → [:player :stopped]
(rf/dispatch [:media-player [:play]])    ;; → [:player :playing :at-start]
(rf/dispatch [:media-player [:seek]])    ;; → [:player :playing :mid-track]
(rf/dispatch [:media-player [:eject]])   ;; → :tray   AND records :player's last config
(rf/dispatch [:media-player [:insert]])  ;; → restores [:player :playing :mid-track] — resumes mid-track

@(rf/subscribe [:rf/machine :media-player])
;; => {:state      [:player :playing :mid-track]
;;     :data       {}
;;     :rf/history {[:player] [:player :playing :mid-track]}}
;; (no :tags key here — this machine declares no tags, and an empty tag
;;  union is omitted from the snapshot rather than stored as #{})
```

You wrote no capture code, no "remember the last tab" action. The `[:player :hist]` target does the work.

## The three keys — and nothing else

A `:type :history` pseudo-state carries **exactly** three keys, all owned by the history grammar:

| Key | Value | Meaning |
|---|---|---|
| `:type` | `:history` | The discriminator that marks this node as a history pseudo-state rather than a real substate. **Required.** |
| `:deep?` | boolean | `true` ⇒ **deep** history (restore the full recorded leaf path). `false` or **absent** ⇒ **shallow** (restore the recorded *direct child*, then cascade through *that* child's `:initial` chain). Default is shallow — a missing `:deep?` reads as `false`. |
| `:default-target` | child keyword *or* absolute vector | Where to land the **first** time the compound is entered, before anything has been recorded. A direct-child keyword (`:stopped`) or an absolute path. **Absent** ⇒ falls back to the owning compound's `:initial`. |

It MUST NOT declare `:on` / `:entry` / `:exit` / `:always` / `:after` / `:spawn` / `:spawn-all` / `:states` / `:initial` / `:tags` / `:final?` — the machine never occupies it, so transition and lifecycle keys are meaningless on it. Any such key is a **registration error** (caught when you `reg-machine`, not on the unlucky dispatch that first reaches it).

## Shallow vs deep — how *much* of the path is restored

This is the one knob that trips people. Take the same player and eject from deep inside — `[:player :playing :mid-track]`:

- **Deep** (`:deep? true`) records the **full leaf path** beneath the compound. On `:insert` it re-enters every level back down to the exact leaf — `[:player :playing :mid-track]`. The player resumes the exact track *and* its mid-track position.
- **Shallow** (omit `:deep?`) records only the compound's **direct child** — here `:playing`. On `:insert` it restores that child and then cascades through *its* `:initial` chain. So it resumes `[:player :playing :at-start]` — the right top-level branch (we were playing), but its initial inner position, not `:mid-track`.

So the recorded slot differs by kind:

```clojure
;; after :eject, DEEP:
:rf/history {[:player] [:player :playing :mid-track]}   ;; full absolute leaf path
;; after :eject, SHALLOW:
:rf/history {[:player] :playing}                        ;; just the direct child keyword
```

Reach for **deep** when the precise nested position matters (resume the exact track and scrub point); **shallow** when only the top-level branch matters (which tab, not the tab's inner scroll).

## Recording happens on exit — the compound must actually be *left*

Recording is automatic and happens on the way **out**: whenever the exit cascade *leaves* a compound that owns a history pseudo-state, the runtime writes that compound's last-active configuration into the snapshot. You never write capture code.

The owning compound must be **genuinely exited** to record. This is the [W3C SCXML](https://www.w3.org/TR/scxml/#history) / xstate exit-set rule: a `<history>` value is written only for states in the exit set. A transition that merely moves *between two children* of the compound keeps the compound as the [least-common ancestor](concepts.md#when-the-machine-grows) — the compound **survives**, was never left, and so records nothing (there is no "last config" to remember; it is still in one).

That is exactly why `:eject` targets `:tray` (a *sibling of* `:player`, outside it) rather than some inner state: only leaving `:player` puts it in the exit set. A common first mistake is to put the history node on a compound that nothing ever exits — then it silently never records and "restore" always falls to the default. If your history isn't sticking, check that *something leaves the owning compound.*

Symmetrically, the restore transition — which *enters* the compound through `[:player :hist]` — records nothing for that compound; re-entry leaves the recorded slot untouched.

## Restoring — recorded, else default-target, else `:initial`

When a transition resolves to the pseudo-state, the runtime resolves the target leaf in this order:

1. **A valid recording exists** for the compound → restore it (deep = full leaf path; shallow = recorded child then its `:initial` cascade).
2. **No recording** (the compound was never entered/exited) → resolve the pseudo-state's `:default-target`; if `:default-target` is absent, fall back to the compound's own `:initial` and cascade from there exactly as a first-ever entry would.
3. **A recording exists but is no longer a valid path** in the current definition — a *dangling recorded path* after a hot reload removed that substate → the runtime discards it and falls back to `:default-target` / `:initial` per (2). It never enters a dead path; this is benign and raises no error.

Once the pseudo-state resolves to a concrete leaf, nothing else about history is special: the standard LCA computation, exit/entry cascade ordering, `:always` settling, and `:after` timer scheduling all apply to the resolved leaf exactly as if you'd written that leaf as a literal `:target`. History is a *target-resolution step*, not a new cascade mechanism.

## The `:rf/history` snapshot slot

The recording lives in a reserved, framework-owned slot at the root of the snapshot — a sibling of the other `:rf/*` runtime slots:

```clojure
{:state      [:player :stopped]
 :data       {…}
 :rf/history {[:player] [:player :playing :mid-track]}}   ;; map: compound decl-path → recorded config
```

`:rf/history` is a **map**, keyed by the compound's **declaration path** (a vector of keywords) → that compound's recorded configuration (a full leaf path for deep, a direct-child keyword for shallow). It is a map because a machine can own several history-bearing compounds, each recorded independently. The slot is:

- **read-only for you** — the runtime owns it and writes it during the exit cascade; app code MUST NOT write under it;
- **allocated lazily** — absent until a history-bearing compound is first exited; a machine with no history nodes never carries the key;
- **EDN-clean** — vectors and keywords only, so it round-trips through `pr-str` / `read-string` like the rest of the snapshot.

Because the recording is *part of the snapshot value* — not a side-table — recorded history rides every path the snapshot rides, with no extra machinery: undo and [time-travel](../core/glossary.md#time-travel) (rewind to an earlier epoch and its `:rf/history` rewinds too, so "rewind, then re-enter the compound" replays deterministically), persistence, and SSR hydration. The snapshot lives in the frame's [runtime-db](../core/glossary.md#runtime-db), the framework's half of state — so, like everything else there, it is just a value that replay can reach.

## Per-region history under `:type :parallel`

Under a [parallel](concepts.md#when-the-machine-grows) machine each region runs an independent state-tree, so **history is per-region**: a history node declared inside a region's compound records and restores *that region's* configuration on the region's own exit cascade, independently of its siblings. The `:rf/history` keys are **region-qualified** — the region name is the head segment of the declaration-path key — so two regions that each declare a history-bearing compound at structurally-identical paths never collide:

```clojure
;; Two regions, each with a history-bearing :on compound at the same shape.
;; The region name heads the KEY (so recordings stay separate); the recorded
;; VALUE is that region's own within-region path:
{:rf/history {[:left  :group :on] [:group :on :bright]
              [:right :group :on] [:group :on :dim]}}
```

Restoring history in one region leaves the others untouched — the same per-region scoping that `:spawn`, `:after`, and `:always` follow under parallel.

## Coming from XState

If you've drawn history states in xstate, the concepts port one-for-one; only the spelling changes (re-frame2 prefers `?`-suffixed booleans and keyword targets). re-frame2 tracks **behavioural parity** with [xstate v6](coming-from-xstate.md), not API mimicry:

| XState (v5 / v6) | re-frame2 | Note |
|---|---|---|
| `{ type: 'history' }` | `{:type :history}` | Same pseudo-state. |
| `history: 'deep'` (default `'shallow'`) | `:deep? true` (default shallow) | A boolean rather than a string; default is shallow in both. |
| `target: 'someState'` (default when no history) | `:default-target :some-state` | Where the first entry lands before anything is recorded; absent ⇒ the compound's `:initial`. |
| internal `historyValue` on the actor | the `:rf/history` slot **inside the snapshot** | **The deliberate divergence.** xstate keeps history as internal actor state; re-frame2 keeps it as a revertible value in the frame, which is why undo / time-travel / SSR get history for free. |
| records on `statesToExit` (SCXML Appendix D) | records only when the owning compound is in the **exit set** | Identical semantic — history means "where this compound was when we *left* it," so the compound must actually be left. |

The one thing to unlearn is *where the memory lives*: there is no actor object holding `historyValue`. The memory is a value in the snapshot, and the snapshot is just data on the frame.

## Why history is first-class, not a snapshot you stash yourself

You *could* hand-roll the equivalent: an `:exit` action that copies the current sub-path into `:data`, an entry that reads it back, plus the bookkeeping to know *which* compound (and, under parallel, *which region*). re-frame2 ships the declarative node instead, for four reasons:

- **Declarative beats hand-rolled.** `{:type :history :deep? true}` is one node an AI agent (or a teammate) reads and writes confidently; the stash-and-restore version is bespoke per machine.
- **Composition you'd otherwise re-derive.** Per-region history under parallel, deep nesting, and shallow-vs-deep depth all fall out of the grammar plus the existing cascade machinery — the hand-rolled form has to re-implement each, correctly, every time.
- **Tooling legibility.** A `:type :history` node is visible to the machine inspector, the diagram exporter, and the SCXML / xstate corpus; hand-rolled `:data` shuffling is invisible to all of them.
- **Parity.** Both SCXML (`<history>`) and xstate (`{type:'history'}`) ship first-class history; matching the shape keeps the conformance corpus and the AI-trained-on-xstate path aligned.

Revertibility is what makes history *cheap* (the recording rides the snapshot), but it's the foundation history is built on, not a reason to skip the feature.

## Gotchas

- **The owning compound must actually be exited** to record. A within-compound sibling move (the compound survives as the LCA) records nothing — give the compound an off-state to leave for (the `:tray` in the example).
- **The pseudo-state is never in `:state`.** The machine's `:state` is never `[… :hist]`; a transition to `:hist` resolves to a real leaf. Don't `case` on the pseudo-state keyword in views.
- **History only applies to a compound.** A `:type :history` node at the machine root (or under a `:type :parallel` root with no enclosing compound region) is a registration error — there is no configuration for it to record.
- **At most one history node per compound.** Deep-vs-shallow is the single node's `:deep?`, not a reason for two nodes. Two history children under one compound is a registration error.
- **`:default-target` (keyword form) names a *direct child*** of the owning compound, then cascades any `:initial` chain — not an arbitrary sibling elsewhere. Use the vector form for an absolute path. An unresolvable `:default-target` is a registration error.
