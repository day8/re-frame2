# 26 - Operating well

You have the pieces and now you want the codebase to stay pleasant when it has deadlines, bugs, half-finished features, and a team of humans doing human things. This chapter is the operating guide: habits that keep re-frame2 boring in the good way.

Boring is not an insult here. Boring means a new feature has an obvious place to go, a failure has an obvious place to inspect, and a test has an obvious layer to run at.

## Name things by feature

Use namespaced ids.

```clojure
:cart/add
:cart/save
:cart/count
:profile.form/edit
:auth.login/flow
```

Search should work. A vague `:save` id is a future treasure hunt. A feature-qualified id is a map.

## Keep the loop intact

When adding code, ask which part of the loop it belongs to:

| Need | Home |
|---|---|
| State fact | `app-db` under feature-owned path. |
| User or system occurrence | Event. |
| State transition | Event handler or machine. |
| Derived read | Subscription or flow. |
| UI rendering | View. |
| External work | Effect. |
| External input | Cofx. |
| Cross-cutting pipeline rule | Interceptor. |

If a piece does not fit, either you found a real new primitive or you are about to build a private framework in a corner. Most days it is the second one.

## Prefer the cheapest truthful test

Do not run a browser to prove a pure handler. Do not unit-test around the browser when the bug is focus behaviour. Use the cheapest runner that can actually observe the claim.

## Make tools part of the workflow

Use Story for UI states and regression variants. Use Xray for causal debugging. Use trace and epoch evidence when a bug gets weird. Use MCP surfaces and skills as the same operations through another front door, not as a secret second runtime.

## Keep prose and examples honest

A guide, Story variant, or test fixture is part of the product. If it lies, users learn the wrong thing. Keep examples runnable, small, and boringly correct. The reader should spend their confusion budget on the idea, not on a typo in the sample.
