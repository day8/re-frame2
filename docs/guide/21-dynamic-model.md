# 21 - Runtime model

You want to know which parts of the state model are yours and which parts belong to the framework, because crossing that line casually is how clever code becomes archaeology. This chapter explains the dynamic runtime model: `app-db` contains both app-owned facts and framework-managed slices, and the difference is encoded by ownership and access paths.

Your app owns ordinary domain keys.

```clojure
{:cart {...}
 :profile {...}
 :settings {...}}
```

The framework owns `:rf/runtime`.

```clojure
{:rf/runtime {:machines {...}
              :routing {...}
              :elision {...}}}
```

## Read, do not scribble

Framework-managed state lives in `app-db` so it can be inspected, diffed, restored, tested, and serialized with the rest of the frame. That does not mean you mutate it directly.

Read through documented subscriptions or helpers. Change it through registered events, route APIs, machine transitions, or configuration surfaces.

## Why expose runtime state at all

The alternative is hidden mutable runtime state. That makes tools weaker and tests stranger. re-frame2 chooses visible runtime state with clear ownership because visible things can be debugged.

## Dynamic registration

Registries are also runtime data: events, subs, effects, coeffects, views, machines, schemas, routes. Tools can ask what is registered. Tests can inspect handler metadata. Story can discover views and variants. This is why naming matters; ids are not decoration, they are the address system.

## Pitfall: relying on internals

If a path under `:rf/runtime` is not documented as an app surface, treat it as implementation detail. Use the public query or event. The runtime being inspectable does not make every private path your API.
