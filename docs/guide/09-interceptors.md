# 09 - Interceptors

You want to understand the machinery around a handler, because eventually you need validation, path focus, logging, coeffects, error policy, or all of them without turning the handler itself into a junk drawer. This chapter teaches interceptors: the before/after sandwich that shapes the cascade without stealing the handler's job.

An interceptor is a map with an id and optional `:before` and `:after` functions.

```clojure
{:id :debug/log-event
 :before (fn [ctx]
           (js/console.log "event" (get-in ctx [:coeffects :event]))
           ctx)}
```

The runtime threads a context map through the chain. `:before` functions run on the way in. The handler runs. `:after` functions run on the way out.

## Why they exist

Handlers should say what the feature does. Interceptors say how the runtime prepares, constrains, observes, and cleans up around that feature.

Common jobs:

| Job | Interceptor shape |
|---|---|
| Focus a handler on a path | `path` rewrites `:db` on the way in and splices it back on the way out. |
| Inject time or storage | `inject-cofx` adds named inputs. |
| Validate schemas | Boundary interceptors check event args, db paths, or fx args. |
| Trace or audit | A `:before` or `:after` records structured evidence. |

## Path focus

```clojure
(rf/reg-event-db :cart/clear
  [(rf/path [:cart])]
  (fn [cart _]
    (assoc cart :items {})))
```

Inside the handler, `db` is the cart slice, not the whole app. The interceptor puts the result back under `[:cart]` after the handler returns. This is useful when a feature owns a small slice and the handler should not care about the rest of the world.

## Ordering matters

Interceptors compose in order, so write the chain as if another human will read it under mild stress.

```clojure
[(rf/inject-cofx :app/now)
 (rf/path [:session])
 validate-session]
```

If an interceptor depends on a coeffect, it must come after the injection that supplies it. This is not magic; it is a pipeline.

## Pitfall: using interceptors as secret handlers

An interceptor can do almost anything. That is not permission to hide feature logic in one. If the business rule is "a locked account cannot submit", the handler or machine should say that. If the infrastructure rule is "every auth event gets `:app/now`", an interceptor is perfect.

Use interceptors for cross-cutting mechanics. Keep domain decisions in domain code.
