# 3. The fidelity ladder

A variant can reach its state in three ways: by running real events, by
seeding app-db directly, or by pinning subscription values. The cheaper ways
are useful for design work. Story labels every variant with the way it used,
so a painted state is never mistaken for a proven one.

## What a screenshot proves

A screenshot of an error state can be useful and still prove almost nothing.
If you painted it by feeding `"Invalid credentials"` straight into the view,
the screenshot proves the red text renders. It does not prove the login flow can
reach the error state, that the machine transitioned correctly, or that the
subscription computes the right value.

![The login error variant with two places its fidelity shows: 1 the real-setup chip under the variant in the sidebar, and 2 the View State section of Controls, listing the three rungs with real setup in use.](../images/story/story-tutorial-03-controls-and-fidelity.png)

## The three rungs

Story works out the rung from the inputs the variant uses; you do not write it
yourself.

| Rung | Meaning |
|---|---|
| `:real-setup` | Real events drove real state through the event pipeline. Highest fidelity. |
| `:db-seed` | App-db was seeded directly, then schema-checked. Useful, but it bypasses event/cofx validation. |
| `:sub-overrides` | Subscription values were pinned for render. Fast design-state exploration, not proof of subscription logic. |

Args are not a fidelity rung: they are view inputs. Network stubs and effect
overrides are world inputs, and the runner a variant needs is a separate
property again. The sidebar shows each on a chip of its own.

The rung shows in three places: the chip under the variant in the sidebar (1
in the screenshot above), the View State section of Controls (2), which
numbers the three rungs and marks each "in use" or "available", and the
Status & fidelity section of Docs mode.

## Rung 1: real setup

The login error variant uses real setup:

```clojure
:setup [[:login/flow
         [:login/submit {:email "ada@example.com"
                         :password "wrong"}]]
        [:login/flow
         [:login/failure
          {:failure {:status 401
                     :message "Invalid credentials."}}]]]
```

The event handler runs. The state machine transitions. Subscriptions compute
from the resulting `app-db`. The view renders what the app actually reached.

Use real setup when the variant makes a claim about behaviour.

## Rung 2: schema-checked app-db seed

Sometimes a state would take twenty setup events to reach, and those events
are beside the point of the example. A db seed places the state directly:

```clojure
(rf.story/reg-variant :story.profile/with-avatar
  {:db-seed {:profile {:name "Ada"
                       :avatar-url "/avatars/ada.png"}}
   :tags #{:dev :docs}})
```

A seed skips the event and coeffect path, so Story schema-checks the seeded
data instead. If the seeded slice violates the registered app-db schema, the
run fails with `:rf.error/story-db-seed-invalid`, naming each violating path,
before the script starts.

Use this when the state is legitimate but tedious to reach.

## Rung 3: subscription overrides

The fastest design-state path is to pin the value a subscription returns:

```clojure
(rf.story/reg-variant :story.login/error-painted
  {:sub-overrides {[:login/state] :error
                   [:login/error] "Invalid credentials."
                   [:login/attempts] 1}})
```

Use it to design loading, empty, error or permission-denied states before the
event path that reaches them exists.

A pin is not proof of the subscription.

```clojure
[:rf.assert/sub-equals [:login/state] :error]
```

does not pass because you pinned `[:login/state]`. `sub-equals` computes the
subscription against the real frame's db, while overrides feed only the render
path.

A pinned value is still checked against the subscription's output schema, when
the subscription declares one. A value the real subscription could never
return fails the variant with `:rf.error/story-sub-override-invalid` before it
renders. In View State, a subscription with an output schema also gets a typed
form for editing its pinned value.

Pins are a dev-build feature. A published static build compiles them out, so
there the variant renders its real subscription values; a state you intend to
publish should use a `:db-seed` or real setup.

## Upgrading fidelity

Start cheap, and move up the ladder when the state becomes important:

1. Use args or sub-overrides to design the shape.
2. Move important state to a db seed if the app-db shape is the thing you care about.
3. Replace the seed with real setup events when the behaviour matters.
4. Add assertions once the state is worth keeping.

View State helps with steps 2 and 3. While sub-overrides is the lowest rung in
use, it shows a "Low-fidelity: a picture, not proof" note and a button for each
stronger rung. A button opens a `reg-variant` form to paste into your stories
namespace: the same state without the pins, with an empty `:setup` or
`:db-seed` for you to fill in.

Not every state needs the top rung. The label says which rung it has, so a
green variant with pinned subscriptions is never read as proof of the logic
behind them.
