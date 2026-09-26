# 6. Xray, earned at failure

You have a failing variant and you do not want a giant EDN blob as your prize.
This chapter shows the diagnostic boundary: Story tells you which variant,
script step, expectation, and run result matter; Xray shows the runtime detail
behind that moment. The goal is a short path from "red" to "cause".

## Story owns the example

Story knows:

- which variant you selected;
- which setup and script ran;
- which assertions passed, failed, or could not run;
- which args and world inputs were active;
- which evidence belongs to this run.

That is the narrative layer. It should answer the first human question:

> What failed?

It should not open with an app-db dump. That is how tools punish honesty.

## Xray owns the deep panels

Story embeds Xray in the right-hand rail. The panel chips are the familiar Xray
surfaces:

- Epoch;
- App-db;
- Views;
- Trace;
- Machines;
- Routing.

![Story embedding the Xray App-db panel for the selected login variant.](../images/story/story-tutorial-07-xray-embed.png)

Story does not build another app-db inspector or trace viewer. That would create
two tools that can disagree about the same run, and then everyone gets to spend
an afternoon saying "interesting" in a meeting. Story brings the run into focus;
Xray handles the detailed runtime inspection.

The embedded Xray watches the selected variant's frame, because each variant's
frame is registered under the variant's own id. Select another variant and
Xray follows it. With no variant selected, the panel reads "Select a variant to
inspect via Xray."

The rail shows one Xray panel at a time, below a strip of the variant's recent
events. It opens on Epoch. A story or variant can choose a different starting
panel with `:xray-panel`, one of `:epoch`, `:app-db`, `:views`, `:trace`,
`:machines` or `:routing`, and the variant's choice beats its story's:

```clojure
(rf.story/reg-story :story.login
  {:component  :my-app.views/login-card
   :xray-panel :machines})
```

Clicking a chip overrides that for the rest of the session, across every
variant. **Pop out** opens the full Xray shell in a second window, with room
for every panel at once.

A body can also carry an `:xray` map that configures that full shell when the
variant is selected: `:open? true` opens it, `:panel` selects its panel, and
`:filters` pre-loads its event filters, as in `{:out [:my-app/tick]}` to hide a
noisy event. Neither slot has any effect in a published static build, which
carries no Xray.

## The failure path

Take a variant that expects the wrong state:

```clojure
(rf.story/reg-variant :story.login/wrong-expectation
  {:extends :story.login/error
   :script  [[:assert [:rf.assert/state-is :login/flow :idle]]]
   :tags    #{:dev :test}})
```

1. Open the **Tests** tab. The summary reads "1 failed of 1", and the row names
   `:rf.assert/state-is`.
2. Press **show detail**. It names the expected `:idle`, the actual `:error`,
   and the reason.
3. Press **open in Evidence →**. The Evidence panel in the right rail opens on
   the run's narrative, with the failing assertion's beat selected.
4. The narrative has one span per setup and script step. Under each span are
   the beats it produced: the event, its epoch number, and counts of what it
   changed, such as `db Δ 1`, `effects 1`, `trace 14` and `sub-runs 2`. A step
   that dispatches nothing, such as an `[:assert …]` checkpoint, is marked
   "non-dispatch step — committed no epoch".
5. Each beat carries **Xray: Epoch**, **Xray: App-db** and **Xray: Trace**.
   Pressing one focuses the Xray panel on that beat's epoch, so you can walk
   back from the failed assertion to the setup event that put the machine in
   `:error`.

Each beat is labelled with how Story knows it. "direct epoch evidence" was
recorded as the event ran: the app-db before and after, the effects, the trace.
"attributed (post-settle)" marks subscription runs and renders that were
matched to the event afterwards, which is useful but not the same proof.
**Copy narrative EDN** copies the whole narrative for a bug report or an agent.

Story owns the author-facing run narrative, and Xray owns runtime diagnosis.
Docs mode shows the same beats in its Evidence section, each with an **Inspect
in Xray** button.

## What the epoch tape buys you

re-frame2 records committed epochs: what event ran, what app-db changed, which
effects were emitted, which subscriptions and views ran, which trace events were
produced, and where schema failures appeared.

Story projects that evidence for the selected variant. Xray lets you inspect it
in detail. This is why a Story failure can be better than an ordinary component
test failure: the run is not just red, it is red with a retained causal record.

For a login failure, Xray can show:

- the `:login/flow` dispatches in order;
- the machine snapshot under `[:rf.runtime/machines :snapshots :login/flow]` (in runtime-db);
- the effect stub for `:rf.http/managed`;
- the final error data in app-db;
- the assertion event that recorded the verdict.

## Evidence is not a fourth top-level mode

The Story shell has Canvas, Docs, and Tests. It does not have a fourth
"Evidence" tab.

The default workshop stays focused on states, examples, and tests. Evidence
becomes primary when a run needs it: a failed assertion, an Inspect gesture, or
a selected Xray panel brings it forward.

Story stays calm until the moment it must be specific. Debugging a red variant
surfaces evidence without a hunt, and ordinary state review is never drowned out
by evidence shouting over it.

## Schema failures

Schema violations are especially important because final state can hide them. A
handler may roll back a bad value or recover to a valid db, while the trace still
contains the violation that should fail the run.

Story treats schema failure evidence as part of the run. That means "the final
app-db looks clean" is not enough to wash away a schema error emitted earlier.
The trace remembers. Xray shows it. Story's result should not green-light it.

You now have a diagnostic path. The next thing to learn is how to keep a growing
variant library organized without smuggling behaviour through hidden globals.
