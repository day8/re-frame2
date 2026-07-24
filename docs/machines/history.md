# History states

Re-enter a [compound](hierarchical-states.md) at the substate you left — a media
player resumes mid-track. History is a transition *target*, not a state you occupy.

Without it you stash the last substate in `:data` on the way out, read it back on
the way in, and wire the restore yourself. A **history state** is that pattern as
one node — `:type :history` — and because record/restore live *inside the
snapshot*, they ride undo, time-travel, persistence, and SSR without extra wiring.

> History only applies to a **compound** state — a state with its own `:states` and
> `:initial`. If "which tab" is a single flat value, keep it in
> [`:data`](concepts.md#the-same-flow-as-a-transition-table). History earns its keep
> when a *non-trivial nested* configuration is worth remembering across a leave/return.

## A history state is a transition *target*, not a state you occupy

A history state is a **pseudo-state**. You declare it under a compound's `:states`
alongside real substates, but the machine *never sits in it*. Its only job is to be
the **target of a transition** — when a transition resolves to it, it stands for
"the substate this compound was in when control last left it." The runtime resolves
it to a real leaf, the entry cascade enters *that* leaf, and the
[snapshot](glossary.md#snapshot)'s `:state` records the resolved leaf — never the
pseudo-state.

Write `[:player :hist]` as a target the same way you'd write any state path; it
resolves to a recorded (or default) configuration.

## A worked example — a media player that resumes

A player with a `:tray` (no disc) and a `:player` compound (disc inserted). The
`:player` compound owns a `:type :history` pseudo-state. `:eject` leaves `:player`
for `:tray`; `:insert` comes back *through history*, restoring whatever the player
was doing when ejected:

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

Drive it with ordinary dispatched events; read it with an ordinary subscription:

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

You wrote no capture code. The `[:player :hist]` target does the work.

## The three keys — and nothing else

A `:type :history` pseudo-state carries **exactly** three keys:

| Key | Value | Meaning |
|---|---|---|
| `:type` | `:history` | Marks this node as a history pseudo-state. **Required.** |
| `:deep?` | boolean | `true` ⇒ **deep** (full recorded leaf path). `false` or **absent** ⇒ **shallow** (recorded *direct child*, then that child's `:initial` chain). Default is shallow. |
| `:default-target` | child keyword *or* absolute vector | Where the **first** entry lands before anything is recorded. **Absent** ⇒ owning compound's `:initial`. |

It MUST NOT declare `:on` / `:entry` / `:exit` / `:always` / `:after` / `:spawn` /
`:spawn-all` / `:states` / `:initial` / `:tags` / `:final?` — the machine never
occupies it. Any such key is a **registration error** at `reg-machine` time, not on
first dispatch.

## Shallow vs deep — how *much* of the path is restored

Eject from deep inside — `[:player :playing :mid-track]`:

- **Deep** (`:deep? true`) records the **full leaf path**. On `:insert` it re-enters
  every level back to that leaf — `[:player :playing :mid-track]`.
- **Shallow** (omit `:deep?`) records only the compound's **direct child** — here
  `:playing`. On `:insert` it restores that child and cascades through *its*
  `:initial` — so `[:player :playing :at-start]` (right branch, initial inner position).

```clojure
;; after :eject, DEEP:
:rf/history {[:player] [:player :playing :mid-track]}   ;; full absolute leaf path
;; after :eject, SHALLOW:
:rf/history {[:player] :playing}                        ;; just the direct child keyword
```

Reach for **deep** when the precise nested position matters; **shallow** when only
the top-level branch matters (which tab, not the tab's inner scroll).

## Recording happens on exit — the compound must actually be *left*

Recording is automatic on the way **out**: when the exit cascade *leaves* a compound
that owns a history pseudo-state, the runtime writes that compound's last-active
configuration into the snapshot. You never write capture code.

The owning compound must be **genuinely exited**. This is the
[W3C SCXML](https://www.w3.org/TR/scxml/#history) exit-set rule: a `<history>` value
is written only for states in the exit set. A transition that merely moves *between
two children* of the compound keeps the compound as the
[least-common ancestor](hierarchical-states.md#entryexit-cascading-along-the-lca) —
the compound **survives**, was never left, and records nothing.

That is why `:eject` targets `:tray` (a *sibling of* `:player`, outside it) rather
than some inner state: only leaving `:player` puts it in the exit set. A common
mistake is putting the history node on a compound that nothing ever exits — then it
silently never records and "restore" always falls to the default. If history isn't
sticking, check that *something leaves the owning compound.*

Symmetrically, restoring through `[:player :hist]` records nothing for that
compound; re-entry leaves the recorded slot untouched.

## Restoring — recorded, else default-target, else `:initial`

When a transition resolves to the pseudo-state, the runtime picks a leaf in this
order:

1. **A valid recording exists** → restore it (deep = full leaf path; shallow =
   recorded child then its `:initial` cascade).
2. **No recording** → resolve `:default-target`; if absent, the compound's
   `:initial` and cascade from there.
3. **Recording exists but is no longer a valid path** (hot reload removed a
   substate) → discard it and fall back per (2). Never enters a dead path; benign,
   no error.

Once resolved to a concrete leaf, history is just target resolution: standard LCA
computation, exit/entry cascade, `:always` settling, and `:after` scheduling apply
as if you'd written that leaf as a literal `:target`.

## The `:rf/history` snapshot slot

Recording lives in a reserved framework-owned slot at the snapshot root:

```clojure
{:state      [:player :stopped]
 :data       {…}
 :rf/history {[:player] [:player :playing :mid-track]}}   ;; compound decl-path → recorded config
```

`:rf/history` is a **map**, keyed by the compound's **declaration path** (keyword
vector) → recorded configuration (full leaf path for deep, direct-child keyword for
shallow). A machine can own several history-bearing compounds. The slot is:

- **read-only for you** — runtime writes it during the exit cascade; app code MUST
  NOT write under it;
- **allocated lazily** — absent until a history-bearing compound is first exited;
- **EDN-clean** — vectors and keywords only, so it round-trips with the rest of the
  snapshot.

Because the recording is *part of the snapshot value*, it rides every path the
snapshot rides: undo and [time-travel](../core/glossary.md#time-travel), persistence,
SSR hydration. The snapshot lives in [runtime-db](../core/glossary.md#runtime-db).

## Per-region history under `:type :parallel`

Under a [parallel](parallel-states.md) machine each region runs an independent
state-tree, so **history is per-region**: a history node inside a region's compound
records and restores *that region's* configuration on the region's own exit cascade.
`:rf/history` keys are **region-qualified** — region name is the head segment — so
structurally identical paths in two regions never collide:

```clojure
;; Two regions, each with a history-bearing :on compound at the same shape.
;; The region name heads the KEY; the recorded VALUE is the within-region path:
{:rf/history {[:left  :group :on] [:group :on :bright]
              [:right :group :on] [:group :on :dim]}}
```

Restoring history in one region leaves the others untouched — same per-region
scoping as `:spawn`, `:after`, and `:always` under parallel.

## Advanced

You *could* hand-roll the equivalent: an `:exit` action that copies the current
sub-path into `:data`, an entry that reads it back, plus bookkeeping for which
compound (and region). re-frame2 ships the declarative node instead:

- **One node** — `{:type :history :deep? true}` is readable in review and to tools;
  stash-and-restore is bespoke per machine.
- **Composition falls out of the grammar** — per-region history, deep nesting,
  shallow-vs-deep, without re-implementing cascade rules.
- **Tooling** — a `:type :history` node is visible to the inspector and diagram
  exporters; hand-rolled `:data` shuffling is not.
- **SCXML parity** — `<history>` keeps the conformance corpus aligned.

The recording rides the snapshot so revertibility is cheap; that is the foundation,
not a reason to skip the feature.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| History never restores; always hits default | Owning compound never left (sibling moves keep it as LCA) | Give the compound an off-state outside it (the `:tray` pattern) |
| View `case`s on `:hist` | Pseudo-state is never in `:state` | Transition to `:hist` resolves to a real leaf — case on that |
| Registration error at root / bare parallel | History needs an enclosing compound | Nest under a compound with `:states` + `:initial` |
| Two history children under one compound | At most one history node per compound | Use one node; deep-vs-shallow is `:deep?` |
| Unresolvable `:default-target` | Keyword form must name a *direct child* | Use a direct-child keyword, or vector form for an absolute path |
