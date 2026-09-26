# Interceptors

Some chores apply to many [event handlers](glossary.md#event-handler): save to
storage after every change, log every event, snapshot state for undo. Copying that
code into each handler repeats it everywhere.

An **interceptor** holds the chore instead. You write it once, register it under an
id, and wrap handlers, or every handler in a [frame](glossary.md#frame), by
referencing that id. Most apps have few interceptors or none.

| Situation | Prefer instead |
|---|---|
| Logic for **one** handler | Keep it in that handler |
| Something that **does** I/O | An [effect](effects.md) (`reg-fx`) |
| A **derived value** many places need | A [subscription](subscriptions.md) or [flow](flows.md) |

## A first interceptor: saving todos

On the [Effects](effects.md#saving-todos) page, every todo handler ended with the same
row, `[:todo.storage/save todos]`. An interceptor can add that row for them:

```clojure
(rf/reg-interceptor :todo/persist
  {:doc "After the handler, save the todos if it changed them."}
  {:after (fn [ctx]
            (let [before (get-in ctx [:coeffects :db :todos])
                  after  (get-in ctx [:effects :db :todos])]
              (if (and (contains? (:effects ctx) :db)
                       (not= before after))
                (update-in ctx [:effects :fx] (fnil conj []) [:todo.storage/save after])
                ctx)))})
```

`reg-interceptor` takes an id, an optional metadata map, and a **descriptor** map with
a `:before` function, an `:after` function, or both. `:before` runs before the
handler; `:after` runs after it.

Each function takes one argument, `ctx`, the **context**, and returns it, possibly
changed. Here `:after` compares the todos the handler received with the todos it
returned, and if they differ it appends a `:todo.storage/save` row to the handler's
`:fx`.

Notes:

1. **Return the context from every function.** Returning `nil` is treated as
   "unchanged", so an `assoc` you meant to keep is silently lost.
2. **Each dispatch gets a fresh context.** Nothing on it carries over to the next
   dispatch, so one registration is safe on any number of handlers and frames.
3. **The id is the handle.** Chains reference the interceptor by id, and the
   [trace stream](glossary.md#trace-stream), [Xray](glossary.md#xray), and overrides
   find it by id.

??? note "Document it"

    Like every other `reg-*`, an interceptor without a `:doc` gets a dev warning
    (`:rf.warning/missing-doc`, once per id, [elided](glossary.md#elide) from
    production). Tools show the `:doc` wherever they show the id.

## Attaching it to a handler

Registering an interceptor doesn't run it. You put its id in a handler's **chain**,
the `:interceptors` key of the event's metadata:

```clojure
(rf/reg-event :todo/toggle
  {:doc          "Flip one todo between done and not done."
   :interceptors [:todo/persist]}          ;; an id, not the interceptor itself
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:todos id :done?] not)}))

(rf/reg-event :todo/delete
  {:interceptors [:todo/persist]}
  (fn [{:keys [db]} [_ id]]
    {:db (update db :todos dissoc id)}))
```

The handlers are back to returning just `:db`, and every change to `:todos` is still
saved.

The runtime looks the id up at dispatch time, so re-registering `:todo/persist` with
new behaviour applies to the next dispatch without re-registering any event. And
because the chain is a vector of ids, it is plain data you can print and diff.

An interceptor *map* placed directly in a chain, as in `{:interceptors [{:after …}]}`,
raises `:rf.error/inline-interceptor-removed`. Register it and reference the id.

??? info "Coming from Express or Koa middleware?"

    Interceptors are layers around one core action, each acting on the way in and on
    the way out, like Koa's "onion". Three things differ. There is no `next()`: the
    chain is a fixed vector the runtime runs forward, then backward. What passes
    through is an immutable map you return a new version of. And middleware performs
    I/O itself, while an interceptor adds effect rows and lets the runtime perform
    them ([below](#contribute-dont-perform)).

## The context map: two keys

The context is one immutable map passed through the whole chain. Two keys matter:

| Key | Holds | Filled by |
|---|---|---|
| `:coeffects` | The handler's **inputs**: `:event`, `:db`, and each fact it declared with [`:rf.cofx/requires`](coeffects.md) | The runtime, completely, before the chain runs |
| `:effects` | The handler's **outputs**: the new `:db` and the `:fx` vector | The handler; then `:after` functions may change it |

Just after `:todo/toggle` runs, the context looks like this:

```clojure
{:coeffects {:event [:todo/toggle 1]
             :db    {:todos {1 {:id 1 :title "Buy milk" :done? false}} :showing :all}}
 :effects   {:db    {:todos {1 {:id 1 :title "Buy milk" :done? true}} :showing :all}}}
```

These are the [coeffects](coeffects.md) the handler reads and the
[effects](effects.md) it returns. So a `:before` sees only `:coeffects`, because the
outputs don't exist yet, and an `:after` sees both. That is why `:todo/persist` is an
`:after`: it compares the two.

## Before and after: a logger

This interceptor uses both functions. It logs each event on the way in and how long
the handler took on the way out:

```clojure
(rf/reg-interceptor :my-app/logger
  {:doc "Log each event on the way in, and its timing on the way out."}
  {:before (fn [ctx]
             (js/console.log "→" (pr-str (get-in ctx [:coeffects :event])))
             (assoc ctx ::started-at (js/performance.now)))
   :after  (fn [ctx]
             (let [elapsed (- (js/performance.now) (::started-at ctx))]
               (js/console.log "←" (pr-str (get-in ctx [:coeffects :event])) (str elapsed "ms"))
               ctx))})
```

The context is the only way an interceptor passes information from `:before` to
`:after`: `:before` stores the start time on it, and `:after` reads it back.
`::started-at`, with a double colon, is a keyword namespaced to the current file, so
it can't collide with anyone else's keys.

## The sandwich: how a chain runs

Several interceptors wrap each other like the layers of a sandwich: the first in the
chain is the outermost. With three interceptors `A`, `B`, `C` around a handler `H`,
the runtime makes two passes over one context:

```text
declared:  [A B C]  + handler H

pass 1, :before in declaration order:
    A:before → B:before → C:before → H   (the handler runs as the last :before)
pass 2, :after in REVERSE order:
    C:after → B:after → A:after
```

As code, the whole run is one threading expression:

```clojure
(-> context
    ((:before A)) ((:before B)) ((:before C))
    ((:before H))                              ;; the handler, wrapped
    ((:after  C)) ((:after  B)) ((:after  A)))
```

The runtime also resolves the ids and guards each call, but this is the shape. The
handler is wrapped as an interceptor too, and the way out mirrors the way in: what
`B:before` sets up, `B:after` sees after everything inside it has run.

The handler doesn't know it is wrapped, and an interceptor doesn't know what it
wraps. They communicate only through the context. Don't write an interceptor that
only works when another one wraps it; reordering the chain breaks it.

??? info "From re-frame v1: the chain no longer rewrites itself"

    v1's context carried `:queue` and `:stack`, and an interceptor could change them
    to rewrite the chain mid-run. re-frame2 has neither: the chain you declare is the
    chain that runs, so tools can print it without running it. A `:before` can still
    skip the handler ([below](#contribute-dont-perform)), and a
    [`:factory`](#parameterized-interceptors-the-factory-descriptor) builds a
    configured interceptor up front.

### Inputs are complete before the chain runs

By the time the first `:before` runs, `:db`, `:event`, and every declared fact are in
`:coeffects`. For one event the order is:

```text
envelope finalization → context assembly → :before pass → handler → :after pass
```

Envelope finalization is the runtime completing the dispatch's metadata record, the
[event envelope](glossary.md#event-envelope). Coeffects are delivered during context
assembly, before the chain. A `:before` can still change the `:coeffects` map, but it
never sees a half-filled one.

??? info "From re-frame v1: coeffect injection left the chain"

    In v1, coeffect injection was itself an interceptor, so one placed before it saw
    an incomplete `:coeffects` map. In re-frame2 injection happens before the chain.
    An `inject-cofx` entry in a chain raises `:rf.error/inject-cofx-removed`; declare
    the fact with `:rf.cofx/requires` instead. See
    [From re-frame v1](25-from-re-frame-v1.md).

## The one standard interceptor: `path`

Core ships one standard interceptor, attached with a second kind of reference, an
`[id arg]` vector. `[:rf.interceptor/path <path>]` focuses a handler on part of
[app-db](glossary.md#app-db): it hands the handler just that part as `:db`, and puts
the returned part back into the full app-db afterwards.

```clojure
(rf/reg-event :todo/toggle
  {:interceptors [:todo/persist [:rf.interceptor/path [:todos]]]}
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [id :done?] not)}))   ;; db here is the :todos map
```

The handler reads and writes as if `[:todos]` were all of app-db. Order matters when
you combine interceptors: `:todo/persist` comes first, so it is the outer layer, and
its `:after` runs once `path` has put the todos back into the full app-db. Placed
after `path`, it would see only the focused `:todos` map and find no `:todos` key in
it.

The `[id arg]` form works for any parameterized interceptor: the id names a
registered factory and `arg` configures it. There is no `rf/path` function: calling
it throws `:rf.error/path-removed`. Every chain entry is a keyword or an `[id arg]`
vector.

Two edge cases:

- **`[]` focuses the whole app-db.** `[:rf.interceptor/path []]` passes all of app-db
  as `:db` and installs whatever the handler returns.
- **An unchanged part stays a no-op.** If the handler returns no `:db`, `path` adds
  none.

!!! warning "Gotcha — a hand-written `path` causes needless re-renders"

    re-frame2 skips the [app-db commit](glossary.md#commit), and the re-renders after
    it, when a handler returns an app-db `identical?` to the one it received. A naive
    `path` that does `(assoc-in original-db [:todos] returned)` builds a new top-level
    map even when nothing changed, which defeats that check. The standard `path`
    returns the original app-db object when the returned part is `identical?` to the
    one it passed in.

### Parameterized interceptors: the `:factory` descriptor

You can write parameterized interceptors too. A descriptor can be `{:factory f}`,
where `f` takes the reference's one argument and returns an ordinary `:before` /
`:after` descriptor:

```clojure
(rf/reg-interceptor :my-app/stamp
  {:doc "After the handler, record which event last changed app-db, under the given key."}
  {:factory (fn [stamp-key]
              {:after (fn [ctx]
                        (if (contains? (:effects ctx) :db)
                          (assoc-in ctx [:effects :db stamp-key]
                                    {:by (first (get-in ctx [:coeffects :event]))
                                     :at (get-in ctx [:coeffects :rf/time-ms])})
                          ctx))})})
```

Reference it with the bracket form, passing the one argument. For several inputs,
pass a map or vector.

```clojure
(rf/reg-event :todo/add
  {:interceptors     [[:my-app/stamp :todo/last-change] :todo/persist]
   :rf.cofx/requires [:rf/time-ms]}          ;; the :after reads it from :coeffects
  (fn [{:keys [db]} [_ title]]
    (let [id (inc (apply max 0 (keys (:todos db))))]
      {:db (assoc-in db [:todos id] {:id id :title title :done? false})})))
```

An interceptor sees only the facts the *event* declared, because
`:rf.cofx/requires` belongs to the event. Leave that line off and `:at` is `nil`.

The factory runs when the chain is built. `[:my-app/stamp :a]` and
`[:my-app/stamp :b]` are two different chain entries, which is why overrides
([below](#removing-or-swapping-a-reference-interceptor-overrides)) match the whole
reference, not just the id.

??? info "From re-frame v1: the helper interceptors are gone"

    `debug`, `trim-v`, `enrich`, `after`, and `on-changes` do not exist in
    re-frame2. Each is a few lines of `reg-interceptor`. `path` remains, as
    `[:rf.interceptor/path …]`, and [flows](flows.md) replace `on-changes`.

## Two places to attach

Besides a handler's own chain, you can attach interceptors to a [frame](frames.md):

```clojure
(rf/make-frame
  {:id           :app
   :interceptors [:my-app/logger]})   ;; wraps EVERY event handled in this frame
```

Frame interceptors are **prepended** to each event's own chain, so they run
outermost: frame interceptors, then the event's, then the handler. A chore for every
event becomes one frame interceptor with no change to handler code.

??? info "From re-frame v1: `reg-global-interceptor` is gone"

    Per-frame `:interceptors` replace it. Each frame keeps its own list, so nothing
    leaks across SSR requests, Story variants, or test fixtures.

### Removing or swapping a reference: `:interceptor-overrides`

A test can remove or replace one interceptor without touching registrations.
`:interceptor-overrides` matches a chain entry by its exact reference and removes it
(`nil`) or replaces it with another reference, per dispatch or per frame:

```clojure
(rf/dispatch-sync [:todo/toggle 1]
                  {:frame                 :app
                   :interceptor-overrides {:todo/persist nil}})        ;; don't save in this test
```

```clojure
(rf/make-frame
  {:id                    :todos/story
   :interceptors          [:my-app/logger]
   :interceptor-overrides {:my-app/logger :story/quiet-logger}})      ;; swap one for another
```

A parameterized entry is matched in full: `{[:rf.interceptor/path [:todos]] nil}`
removes only that `path`. Replacements are references too, so override maps stay
plain data.

When a frame and a dispatch both supply overrides, they merge and the dispatch wins
on shared keys. A key or replacement that isn't a valid reference raises
`:rf.error/interceptor-override-invalid`.

## Contribute, don't perform

The chain is part of the pure event pipeline that replay, time-travel, and tests
re-run. So don't perform I/O in an interceptor. It would run again on every replay,
and `:fx-overrides` couldn't redirect it, because `:fx-overrides` replaces registered
effects, not a `localStorage` call inside an `:after`. Add an
[effect](glossary.md#effect) row instead, as `:todo/persist` does:

```clojure
;; Don't do this: runs again on replay, invisible to :fx-overrides and the trace
:after (fn [ctx]
         (.setItem js/localStorage "todos" (pr-str (get-in ctx [:effects :db :todos])))
         ctx)
```

The one exception is diagnostics: the logger's `console.log` can stay, because
repeating it on replay is harmless.

Interceptors do two kinds of work:

- **Decide.** A `:before` can skip the handler by setting `:rf/skip-handler? true` on
  the context; every `:after` still runs. An auth guard can skip the handler and add
  a redirect instead ([Require sign-in on a route](../routing/how-to/require-sign-in-on-a-route.md)
  shows it in full):

    ```clojure
    :before (fn [ctx]
              (if (get-in ctx [:coeffects :db :auth :user])
                ctx
                (-> ctx
                    (assoc :rf/skip-handler? true)
                    (assoc-in [:effects :fx] [[:dispatch [:auth/show-login]]]))))
    ```

    To validate untrusted input you don't need an interceptor: the `:boundary? true`
    registration flag checks the handler's own `:schema` before the chain runs (see
    [Validate with schemas](how-to/validate-with-schemas.md#in-production-what-goes-what-stays)).

- **Decorate.** Change `:coeffects`, rewrite `[:effects :db]`, or add `:fx` rows.

!!! warning "Gotcha — a frame interceptor runs on the server too"

    A frame interceptor runs on every event in that frame, [SSR](../ssr/glossary.md#ssr)
    included. The `:todo.storage/save` row is safe under SSR because it is only data,
    and its `reg-fx` declares `:platforms #{:client}`, so the server skips it. An
    interceptor that reads `js/localStorage` directly has no such check and fails
    during a server render. Keep host access in effect handlers.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `:rf.error/unregistered-interceptor` at load time | A chain names an unregistered id, usually a typo | Fix the id, or register it |
| `:rf.error/inline-interceptor-removed` | A chain holds an interceptor map instead of an id | Register the map and reference its id |
| An `:after` change is lost | The function returned `nil`, which counts as "unchanged" | End every `:before` / `:after` with the context |
| `:rf.error/interceptor-exception` with `:phase :after` | The `:after` assumed `[:effects :db]` exists | Check for it first ([When the chain throws](#when-the-chain-throws)) |
| A side effect repeats on replay, or fails during SSR | The interceptor performs I/O itself | Add an `:fx` row instead ([above](#contribute-dont-perform)) |

## Advanced

### A real interceptor: undo

Undo fits an interceptor well: "remember the old todos, push them if they changed"
would otherwise repeat in every handler that changes todos. Register it once, and
events opt in by referencing it:

```clojure
;; cf. examples/core/seven_guis/circle_drawer/core.cljs
(rf/reg-interceptor :todo/undoable
  {:doc "Remember the todos before the handler; if it changed them, push the old ones onto :undo."}
  {:before (fn [ctx]
             (assoc ctx ::prior (get-in ctx [:coeffects :db :todos])))
   :after  (fn [ctx]
             (let [prior (::prior ctx)
                   db    (get-in ctx [:effects :db])]
               (if (and db (not= prior (:todos db)))
                 (update-in ctx [:effects :db :undo] (fnil conj []) prior)
                 ctx)))})

(rf/reg-event :todo/delete
  {:interceptors [:todo/persist :todo/undoable]}
  (fn [{:keys [db]} [_ id]]
    {:db (update db :todos dissoc id)}))

(rf/reg-event :todo/undo
  {:interceptors [:todo/persist]}
  (fn [{:keys [db]} _]
    (if-let [prior (peek (:undo db))]
      {:db (-> db (assoc :todos prior) (update :undo pop))}
      {})))
```

`:before` stores the todos from `:coeffects` on the context. `:after` reads the new
`:db` from `:effects`, which is absent if the handler returned none, and pushes an
undo step only if the todos changed. Events that shouldn't create undo steps, such as
typing into a draft, just don't reference it. Undo itself is an ordinary event; redo
is the same with the stacks swapped.

### Introspecting a chain

A chain is data, so the introspection that works on events and subs works on
interceptors too. `handler-meta` reads an interceptor's metadata and source location:

```clojure
(rf/handler-meta {:source :store :kind :interceptor :id :todo/persist})
;; => {:doc "After the handler, save the todos if it changed them."
;;     :ns todo.events :line 12 :file "..." ...}
```

An *event's* metadata gives you its chain as written, a vector of references:

```clojure
(rf/handler-meta {:source :store :kind :event :id :todo/toggle})
;; => {:doc "Flip one todo between done and not done." :interceptors [:todo/persist] ...}
```

A tool reads the references off the event, then looks up each one's source and
`:doc`. That is how [Xray](glossary.md#xray) draws a chain with links to the source.
(The [trace stream](observability.md) already records every event with timings, so
a real app doesn't need the logger above.)

### Testing an interceptor

`:before` and `:after` are pure functions from context to context, so an interceptor
tests like [a handler](testing/event-handlers.md): call the function with a literal
context and check the result. Name the functions with `defn` so a test can reach
them:

```clojure
(defn persist-after [ctx]
  (let [before (get-in ctx [:coeffects :db :todos])
        after  (get-in ctx [:effects :db :todos])]
    (if (and (contains? (:effects ctx) :db) (not= before after))
      (update-in ctx [:effects :fx] (fnil conj []) [:todo.storage/save after])
      ctx)))

(rf/reg-interceptor :todo/persist
  {:doc "After the handler, save the todos if it changed them."}
  {:after persist-after})

(deftest persist-adds-a-save-row
  (let [ctx {:coeffects {:db {:todos {}}}
             :effects   {:db {:todos {1 {:id 1 :title "Buy milk" :done? false}}}}}]
    (is (= [[:todo.storage/save {1 {:id 1 :title "Buy milk" :done? false}}]]
           (get-in (persist-after ctx) [:effects :fx])))))
```

Build the context from the [two-key shape](#the-context-map-two-keys): a `:before`
test supplies only `:coeffects`, an `:after` test supplies both. Give each `:after`
a test with `[:effects :db]` missing, the shape it gets when the handler returns no
`:db` or throws ([When the chain throws](#when-the-chain-throws)).

To test the wiring, dispatch through a test frame and check the committed state
([a handler's runtime check](testing/event-handlers.md#3-when-you-want-the-runtime-a-fresh-frame-per-test)).
When another interceptor gets in the way,
[`:interceptor-overrides`](#removing-or-swapping-a-reference-interceptor-overrides)
removes it for one dispatch.

### When a reference is wrong

A chain is data, so the runtime checks it at registration. A chain naming an
unregistered id makes `reg-event` (or `make-frame`) throw
`:rf.error/unregistered-interceptor`, naming the id, when the namespace loads. The
other chain errors also [fail loud](glossary.md#fail-loud-not-silent):

| Error | What you did |
|---|---|
| `:rf.error/invalid-interceptor` | Gave `reg-interceptor` a descriptor that isn't `{:before}`, `{:after}`, `{:before :after}`, or `{:factory}` |
| `:rf.error/unregistered-interceptor` | Referenced an id with no registration |
| `:rf.error/invalid-interceptor-ref` | Wrote a chain entry that is neither a keyword nor an `[id arg]` vector |
| `:rf.error/inline-interceptor-removed` | Put an interceptor map, value, or Var in a chain instead of an id |
| `:rf.error/interceptor-factory-arity` | Used a bracket reference on a non-`:factory` interceptor, referenced a `:factory` interceptor as a bare keyword (`:rf.interceptor/path` with no argument), or the factory can't build for that argument |
| `:rf.error/reg-event-bad-middle-slot` | Passed the chain positionally, `(rf/reg-event :id [:todo/persist] f)`, instead of as `{:interceptors [:todo/persist]}` |
| `:rf.error/reg-event-bad-interceptors` | Gave `reg-event`'s `:interceptors` a value that isn't a vector, or an entry that isn't a reference |
| `:rf.error/path-interceptor-bad-path` | Gave `[:rf.interceptor/path …]` a path that isn't a vector |
| `:rf.error/interceptor-override-invalid` | Used an `:interceptor-overrides` key or replacement that isn't a valid reference |

### When the chain throws

Every `:before` and `:after` runs inside a guard.

!!! warning "Gotcha — your `:after` must survive error paths"

    A throw in a `:before` or in the handler skips the remaining `:before` functions
    and the handler. But the `:after` pass always runs in full, in reverse order,
    for every interceptor in the chain, even those whose `:before` never ran. So
    cleanup belongs in `:after`, and an `:after` that assumes the handler set
    `[:effects :db]` will itself throw.

Errors collect on the context: the first under `:rf/interceptor-error`, all of them
under `:rf/interceptor-errors`, so Xray and Story can show each one. A throw anywhere
means the event installs nothing: app-db is unchanged and no `:fx` runs. The reported
error names the source:

- `:rf.error/handler-exception`: the event handler threw.
- `:rf.error/coeffect-exception`: a coeffect supplier threw, before any `:before`
  ran.
- `:rf.error/interceptor-exception`: one of your interceptors threw. `:failing-id`
  names it and `:phase` says `:before` or `:after`.

An `:after` that throws is recorded but doesn't stop the remaining `:after`
functions, so one broken cleanup can't block the others. [Errors](errors.md) covers
error records.

??? info "Where this pattern comes from"

    Interceptors came to Clojure from the [Pedestal](https://github.com/pedestal/pedestal)
    team. The shape is older: Tomcat's interceptors, Netty's channel pipeline, and
    the J2EE intercepting-filter pattern.
