# 6. Xray, earned at failure

When a variant fails, Story tells you which step and which assertion failed,
and Xray shows the runtime detail behind that moment: the events, the app-db
changes and the trace. This chapter follows one failure from the Tests tab
back to the event that caused it.

Story and Xray split the work. Story shows the variant: its setup and script,
which assertions passed, failed or could not run, and the args and world
inputs the run used. Xray shows the runtime: epochs, app-db, views, trace,
machines and routing. Story embeds Xray instead of building a second app-db
inspector or trace viewer, so the two never give different accounts of one
run.

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
4. The narrative has one span for setup and one for each script step. Under
   each span are the beats it produced: the event, its epoch number, and
   counts of what it changed, such as `db Δ 1`, `effects 1`, `trace 22` and
   `sub-runs 8`. A step that commits no epoch of its own, such as this
   `[:assert …]` checkpoint, is marked "non-dispatch step — committed no
   epoch".
5. Each beat carries **Xray: Epoch**, **Xray: App-db** and **Xray: Trace**.
   Press **Xray: App-db** on the second `:login/flow` beat, the setup's
   `:login/failure`. The Xray panel at the top of the rail switches to App-db
   and focuses on that beat's epoch; scroll the rail up to it.

![The failing variant in the Tests tab with the Evidence panel open beside it. Numbered: 1 the verdict, 1 failed of 1; 2 and 3 the failed row with show detail and open in Evidence; 4 the failing beat, selected in the Evidence narrative; 5 the setup's :login/failure beat and its Xray links.](../images/story/story-tutorial-09-failing-run.png)

![The same run after pressing Xray: App-db on the :login/failure beat. The rail's Xray panel shows App-db at that epoch: the :login/flow machine is in :error and was :submitting.](../images/story/story-tutorial-07-xray-embed.png)

At that epoch App-db shows the `:login/flow` machine moving from `:submitting`
to `:error`: `:login/failure` is the event that left the machine in the state
the assertion did not expect.

Each beat is labelled with how Story knows it. "direct epoch evidence" was
recorded as the event ran: the app-db before and after, the effects, the trace.
"attributed (post-settle)" marks subscription runs and renders that were
matched to the event afterwards, which is useful but not the same proof.
**Copy narrative EDN** copies the whole narrative for a bug report or an agent.

Evidence is a panel in the right rail, not a fourth tab beside Canvas, Docs
and Tests, so ordinary state review stays uncluttered. It comes forward when a
run needs it: from a failed row's **open in Evidence →**, from Test mode's
link to the Evidence panel, or when you open the panel yourself. Docs mode
shows the same beats in its Evidence section, each with an **Inspect in Xray**
button.

## Xray in the right rail

The rail's Xray panel has one chip per panel: Epoch, App-db, Views, Trace,
Machines and Routing.

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
variant is selected: `:open? true` opens it, `:panel` selects its panel (and
the rail's, when the body names no `:xray-panel`), and `:filters` pre-loads
its event filters, as in `{:out [:my-app/tick]}` to hide a noisy event.
Neither slot has any effect in a published static build, which carries no
Xray.

## What the evidence records

re-frame2 records committed epochs: what event ran, what app-db changed, which
effects were emitted, which subscriptions and views ran, which trace events were
produced, and where schema failures appeared.

Story projects that evidence for the selected variant, and Xray lets you
inspect it in detail. So a failed variant comes with a record of what caused
it, not only a red mark.

For a login failure, Xray can show:

- the `:login/flow` dispatches in order;
- the machine snapshot, which the App-db panel lists under
  `:rf/machines › :login/flow` with its `:state` and `:data`;
- the error message and attempt count in that `:data`;
- the effect the stub took over for `:rf.http/managed`;
- the assertion event that recorded the verdict.

## Schema failures

Final state can hide a schema violation. A handler may roll back a bad value or
recover to a valid db, while the trace still holds the violation.

Story counts schema-failure evidence as part of the run, so a clean final
app-db does not clear a schema error raised earlier. Xray shows the violation
in the trace, and the run does not report `:pass` unless an
`:rf.assert/schema-error` declared that violation as expected.
