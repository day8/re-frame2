# Interceptors

Say you have three hundred event handlers — an event handler being the pure function that takes the current state plus an incoming event and returns the next state. You also have three cross-cutting chores: log every event, snapshot state for undo, validate input at the boundary. Writing each chore into each handler is nine hundred copies of code that isn't the handler's job. An **interceptor** holds a cross-cutting concern instead, so the handler stays focused on its one job. You register it once, under a name, and then wrap it around any handler — or around every handler in a frame — by *referencing that name*.

If you've written Express or Koa middleware, you already have most of the picture: layers around one core action, each touching the request on the way in and the response on the way out — Koa's "onion". Three things differ here, and they're worth holding in mind. First, there is no `next()`. The chain isn't control flow you thread by hand; it's a fixed vector the runtime sweeps forward and then backward. Second, what flows through isn't a mutable request/response object — it's an immutable two-key map. Third, Express middleware *does* things: writes headers, ends responses. A re-frame2 interceptor *describes* things and lets the runtime do them. That third difference is the rule this page builds toward:

> **Interceptors decide and decorate; effects do.**

## The sandwich

A handler is a pure function from inputs to outputs. The current `db` (your app's state map for this frame) plus the event go in; new state plus effect descriptions come out. An interceptor wraps it from outside with a pair of functions: `:before` runs on the way in, `:after` runs on the way out. You register each interceptor once, under a name, and then a handler's chain *refers* to it by that name. Stack three interceptors `A`, `B`, `C` around a handler `H` and the runtime makes two sweeps:

```text
declared:  [A B C]  + handler H

sweep 1 — :before, in declaration order:
    A:before → B:before → C:before → H   (the handler runs as the last :before)
sweep 2 — :after, in REVERSE order:
    C:after → B:after → A:after
```

The handler doesn't know it's wrapped, and the interceptor doesn't know what it wraps. That mutual ignorance is exactly why the pattern scales: any interceptor can decorate any handler, because they only ever talk through one shared value. They never reach into each other.

Two details in the diagram carry weight. First, the handler runs as the last `:before`. The runtime wraps it as an interceptor too, so there's exactly one kind of thing to execute, all the way down. Second, the trip out mirrors the trip in. Whatever `B:before` set up, `B:after` tears down — and teardown must happen *after* everything that ran inside the setup. Think of a sandwich: the outer slice goes on first and comes off last. Every cleanup interceptor you write leans on this symmetry.

## The context map: two keys

The one shared value threading through the sandwich is the **context map** — the single immutable map each stage reads from and returns. It has two load-bearing keys:

| Key | Holds | Filled by |
|---|---|---|
| `:coeffects` | The handler's **inputs**: `:event`, `:db`, plus each world fact the handler declared with `:rf.cofx/requires` — flat, under its own id | The runtime — completely, *before* the chain runs |
| `:effects` | The handler's **outputs**: the new `:db`, the `:fx` vector | The handler; then decorated by `:after` stages on the way out |

Caught mid-pipeline, after the handler has run, the map looks like this:

```clojure
{:coeffects {:event      [:todo/add {:id #uuid "..." :title "buy milk"}]
             :db         {:todos {...}}
             :rf/time-ms 1781078400123}      ;; declared fact, delivered flat
 :effects   {:db {:todos {... new-todo}}
             :fx [[:dispatch [:toast/show "Added"]]]}}
```

This is the same pair you met in [effects and coeffects](effects-and-coeffects.md) — coeffects are the world facts a handler reads, effects are the descriptions it writes. Interceptors live in the gap between them. A `:before` sees only `:coeffects`, because the outputs don't exist yet; an `:after` sees both. That's the entire mental model. The runtime also stages a few framework keys on the context — the dispatch envelope among them — for generic tooling; the normative inventory lives in [the frames spec](../../../spec/002-Frames.md).

## Inputs are complete before the chain runs

In re-frame v1, handing a handler a world fact was *itself* an interceptor. Coeffect injection rode the chain as a member, which meant an interceptor placed before it saw an incomplete `:coeffects` map. Ordering mattered, invisibly — and that's the kind of bug that costs an afternoon.

re-frame2 removes the wart by reframing the job: **coeffect satisfaction is context assembly, not a chain member.** The pipeline for one event is:

```text
envelope finalization → context assembly → :before pass → handler → :after pass
```

By the time the first `:before` runs, `:coeffects` is finished — `:db`, `:event`, and every fact the handler declared via [`:rf.cofx/requires`](effects-and-coeffects.md) are already delivered. Every interceptor sees the complete input. "An early interceptor blind to a later injection" simply can't be expressed anymore, because injection isn't a position in the chain. You can still *modify* an assembled `:coeffects` map; that's an ordinary transformation of a finished context, not a delivery mechanism.

> **Coming from re-frame v1?** v1's coeffect-injection rows in the interceptor vector are a hard error now — each fact moves to `:rf.cofx/requires` registration metadata, per [From re-frame v1](../25-from-re-frame-v1.md).

## `reg-interceptor`, and a logger

You register an interceptor the same way you register an event or a sub: name it, then hand the runtime its behaviour. The name is a **qualified keyword id**; the behaviour is a **descriptor** map carrying `:before`, `:after`, or both:

```clojure
(rf/reg-interceptor :my-app/logger
  {:doc "Log each event on the way in, and its timing on the way out."}
  {:before (fn [ctx]
             (let [event (get-in ctx [:coeffects :event])]
               (js/console.log "→" (pr-str event))
               (assoc ctx ::started-at (js/performance.now))))
   :after  (fn [ctx]
             (let [event   (get-in ctx [:coeffects :event])
                   elapsed (- (js/performance.now) (::started-at ctx))]
               (js/console.log "←" (pr-str event) (str elapsed "ms"))
               ctx))})
```

Three things in those lines tend to trip people up the first time:

- **The context is the only channel.** `:before` stashes the start time under a namespaced key, and its own `:after` reads it back — no closures, no side atoms. Each dispatch gets its own context map, which is why the same interceptor is safe on overlapping events.
- **The id is the handle.** Once registered, `:my-app/logger` *is* the interceptor everywhere — chains reference it by id, trace events and Xray name it by id, overrides find it by id (below), and `(rf/handler-meta :interceptor :my-app/logger)` reads back its `:doc` and source coordinates. There is no anonymous interceptor to lose track of.
- **Both slots return the context.** A slot that returns `nil` reads as "unchanged". That works by accident in a log-only slot — right up until you also `assoc` something and the accident becomes a heisenbug. Always end with `ctx`.

Both slots are optional — a descriptor is one of `{:before f}`, `{:after f}`, or `{:before f :after f}`. (A fourth shape, `{:factory f}`, builds a *parameterized family*; see [parameterized interceptors](#parameterized-interceptors-the-factory-descriptor) below, where the standard `path` interceptor lives.) Hand `reg-interceptor` anything that isn't one of those four shapes and registration fails loudly with `:rf.error/invalid-interceptor` — a typo'd slot key dies at the definition site, not at some later dispatch. `reg-interceptor` captures the definition-site coordinates for you, so tools jump straight to your source when a stage throws. v1's grab-bag of one-liner helpers (`debug`, `trim-v`, `enrich`, `after`, `on-changes`) is gone; anything else is a few lines of `reg-interceptor`.

> **Document it.** Like every other `reg-*`, an interceptor without a `:doc` draws a one-shot dev warning (`:rf.warning/missing-doc`, once per id, elided from production). The id is how the whole toolchain refers to your interceptor; `:doc` is what it shows when it does.

That `handler-meta` handle is worth seeing concretely — the same reflection you get on events and subs works on interceptors by `(kind, id)`:

```clojure
(rf/handler-meta :interceptor :my-app/logger)
;; => {:doc "Log each event on the way in, and its timing on the way out."
;;     :ns my-app.audit :line 12 :file "..." ...}
```

And reading an *event's* metadata gives you the chain as authored — a vector of references, not resolved interceptor values:

```clojure
(rf/handler-meta :event :cart.item/add)
;; => {:doc "Add an item to the cart." :interceptors [:my-app/logger] ...}
```

The two compose: a tool reads the refs off the event, then resolves each ref's source and `:doc` via `handler-meta :interceptor`. That's exactly how Xray draws a chain with jump-to-source links on every stage.

Now attach it where the event is registered — the metadata map's `:interceptors` key, **as a reference**. A chain carries interceptor *references*, never inline interceptor values: a bare keyword id names a registered interceptor.

```clojure
(rf/reg-event :cart.item/add
  {:doc          "Add an item to the cart."
   :interceptors [:my-app/logger]}        ;; a reference, not the interceptor value
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)}))
```

That split — register once, reference by id everywhere — is the whole shape. The handler's chain is plain data: a vector of keywords you can serialize, diff, and carry in an image. Drop an inline interceptor map into a public chain and the runtime rejects it (`:rf.error/inline-interceptor-removed`); the fix is always to register the behaviour and reference it. And because the chain stores a *reference*, re-registering `:my-app/logger` with new behaviour takes effect on the next dispatch — you don't re-register the event just because an interceptor's implementation changed.

Dispatch `[:cart.item/add ...]` and the console shows the trip in and the timed trip out. Now open Xray and focus the event's epoch: the pipeline lists your chain by `:id` with a jump-to-source link, and the after-interceptors stage shows the way out. (In a real app the [trace wire](observability.md) already records every event with timings — this logger is the teaching shape.)

## The one standard interceptor: `path`

The framework ships exactly one standard interceptor, and you reference it with the second kind of reference — an `[id arg]` vector. `[:rf.interceptor/path <path-vector>]` **focuses** a handler on an `app-db` sub-slice: on the way in it stages just that slice as the handler's `:db`, and on the way out it widens the returned slice back into the full `app-db`.

```clojure
(rf/reg-event :cart/add
  {:interceptors [[:rf.interceptor/path [:cart]]]}   ;; [id arg] reference — the arg is the path
  (fn [{:keys [db]} [_ sku]]
    {:db (update db :items conj sku)}))   ;; db here is the [:cart] slice, not the whole map
```

The handler reads and writes as if `[:cart]` were the entire world, and `path` re-widens the result for it. The bracket form is the general shape for any *parameterized* interceptor: the id names a registered `:factory`, and the one `arg` configures it (a factory that needs several inputs takes them as a single map or vector). There is no `rf/path` value constructor to import — the reference *is* the surface, which keeps every chain uniform: bare keywords and `[id arg]` vectors, all the way down. Two edge cases are worth knowing:

- **The root path `[]` focuses the whole `app-db`.** `[:rf.interceptor/path []]` stages the entire `app-db` as `:db` and installs whatever the handler returns as the new `app-db` wholesale — handy when you want focusing-style ergonomics over the full map. Hand `path` a non-vector and you get `:rf.error/path-interceptor-bad-path`.
- **An unchanged slice stays a true no-op.** If the handler emits no `:db` effect, `path` synthesizes none. And here's the subtle part that's the real reason `path` is a *framework* interceptor and not something you'd vendor yourself:

> **Gotcha.** re-frame2 skips the container write — and therefore all the downstream re-renders — when a handler returns an `app-db` that is `identical?` to the one it received. A hand-rolled `path` that naively does `(assoc-in original-db [:cart] returned-slice)` allocates a fresh top-level map *even when the slice didn't change*, defeating that identity check and re-rendering the world for nothing. The standard `path` knows both the original full `app-db` *and* the original slice, so when the returned slice is `identical?` to the one it staged, it re-emits the **original `app-db` object** — preserving the no-op all the way down. Getting this right by hand is fiddly; that's why there's exactly one, in the framework.

### Parameterized interceptors: the `:factory` descriptor

`path` is the standard `:factory` interceptor, but the mechanism is open — you can register your own parameterized family. A `:factory` descriptor's function receives the ref's **one** `arg` and returns a static descriptor for it:

```clojure
;; A stamp factory: each reference configures WHICH metadata key gets stamped
;; onto the event's :db write, so one registered interceptor serves many shapes.
(rf/reg-interceptor :app/stamp-meta
  {:doc "On the way out, stamp an audit key onto the handler's :db effect."}
  {:factory (fn [meta-key]
              {:after (fn [ctx]
                        (let [event (get-in ctx [:coeffects :event])]
                          (if (contains? (:effects ctx) :db)
                            (assoc-in ctx [:effects :db meta-key]
                                      {:by (first event) :at (get-in ctx [:coeffects :rf/time-ms])})
                            ctx)))})})
```

Reference it with the bracket form, passing the factory's single arg (need several inputs? pass one map or vector):

```clojure
(rf/reg-event :doc/save
  {:interceptors [[:app/stamp-meta :doc/last-touched]]}
  (fn [{:keys [db]} [_ doc]]
    {:db (assoc-in db [:docs (:id doc)] doc)}))
```

The factory runs once per chain assembly to build the executable interceptor for that arg. Reference a non-factory id with a bracket — or hand a factory an arg it can't build for — and you get `:rf.error/interceptor-factory-arity`. Two refs to the *same* factory with *different* args (`[:app/stamp-meta :a]` and `[:app/stamp-meta :b]`) are two distinct chain entries, each matchable on its own in overrides — which is exactly why override matching is by full reference, not by id.

## Two places to attach

Per-handler attachment, as above, fires for that event only. That's the right scope for event-specific concerns like `path` or undo tags (below). The second place is the frame — a frame being one isolated re-frame2 world, with its own `app-db` and handlers — and it carries the very same references:

```clojure
(rf/reg-frame :app/main
  {:interceptors [:my-app/logger]})   ;; a reference; wraps EVERY event handled in this frame
```

Per-frame interceptors are **prepended** to each event's own chain. Frame-wide concerns sit outermost, event-specific ones inside them, the handler in the middle, and the same forward-then-reverse sweep runs across all of it. This is the answer to the three hundred handlers: the boring chores become two or three frame interceptors, registered once, referenced by id, touching no handler code.

And because the id is the handle, a test can silence or swap one without unwiring anything. `:interceptor-overrides` matches a chain entry **by its exact reference** and either removes it (`nil`) or replaces it with another reference — per dispatch or per frame:

```clojure
(rf/dispatch-sync [:cart.item/add {:sku "abc-123" :qty 2}]
                  {:interceptor-overrides {:my-app/logger nil}})         ;; remove the logger for this dispatch
```

```clojure
(rf/reg-frame :story/cart
  {:interceptors          [:my-app/auth-guard]
   :interceptor-overrides {:my-app/auth-guard :story/skip-auth}})        ;; swap one reference for another
```

Matching is by the full reference, so a parameterized entry is named in full — `{[:rf.interceptor/path [:cart]] nil}` removes only *that* `path`, leaving a sibling `[:rf.interceptor/path [:cart :items]]` untouched. The override values are references too, never inline values, which keeps story, test, SSR, and tool override state serializable and inspectable. (Per-dispatch *additive* `:interceptors` is gone — authored behaviour has exactly two homes, event metadata and frame metadata, and per-call variation is expressed by overriding a named reference.)

When both a frame and a dispatch supply overrides, they **merge, and the per-call one wins** — frame overrides `<` dispatch-opts overrides. A frame might swap your auth guard for a permissive stub by default, and one test dispatch can still re-swap it for that single call. A malformed override (a key or replacement that isn't a valid reference) is rejected loudly with `:rf.error/interceptor-override-invalid`.

> **Coming from re-frame v1?** `reg-global-interceptor` is gone — per-frame `:interceptors` is the replacement, and in a multi-frame app each [frame](frames.md) stays independent: no bleed across SSR requests, story variants, or test fixtures.

## Contribute, don't perform

Here's the rule from the top of the page, made precise. The chain is part of the *step function* — the pure fold that replay, time-travel, and deterministic tests re-run against recorded inputs. This is where it pays to be disciplined.

> **Don't do work directly in an interceptor body.** Work performed directly in an interceptor body re-fires on every replay. It also escapes every seam: `:fx-overrides` redirects *registered effects*, not a stray `localStorage` write buried in an `:after`. So the sanctioned pattern is **contribute, don't perform** — append effect rows and let the interpreter execute them.

```clojure
;; ❌ performs — re-fires on replay, invisible to :fx-overrides and the trace
:after (fn [ctx]
         (.setItem js/localStorage "cart" (pr-str (get-in ctx [:effects :db :cart])))
         ctx)

;; ✅ contributes — a recorded, overridable, traceable effect row
:after (fn [ctx]
         (update-in ctx [:effects :fx] (fnil conj [])
                    [:localstorage/set {:key   "cart"
                                        :value (get-in ctx [:effects :db :cart])}]))
```

(`:localstorage/set` is the app-registered effect from [effects and coeffects](effects-and-coeffects.md) — its `reg-fx` handler stays the one place that touches the host.)

So what *are* interceptors allowed to do? They *decide*: a `:before` can take the handler out of play — the schema [boundary validator](../how-to/validate-with-schemas.md) does this on invalid input, marking the context so the handler becomes a no-op while every `:after` still runs. And they *decorate*: transform `:coeffects`, rewrite `[:effects :db]`, append `:fx` rows. The actual doing belongs to effect handlers. The one exemption is diagnostics — the logger's `console.log` may stay in the body, because re-executing it on replay is harmless. One more discipline rides along: never depend on chain position. An interceptor that only works when another one wraps it has encoded an ordering as a hidden precondition, and that's a trap for whoever reorders the chain next.

## Undo, included from a real example

The most satisfying interceptor is undo. Hand-rolled, it smears "remember the old value, push it, but only if it changed, and clear redo" across every mutating handler. The 7GUIs Circle Drawer example (`examples/reagent/seven_guis/circle_drawer/core.cljs`) registers it once, under a name:

```clojure
;; From examples/reagent/seven_guis/circle_drawer/core.cljs — registered once,
;; then referenced by id from the events that deserve it.
(rf/reg-interceptor :drawer/undoable
  {:doc "Snapshot the pre-handler circles; on a real change push to :undo and clear :redo."}
  {:before (fn before [ctx]
             ;; snapshot taken from coeffects (the pre-handler db).
             (let [db    (get-in ctx [:coeffects :db])
                   prior (get-in db [:drawer :circles])]
               (assoc-in ctx [:coeffects :prior-circles] prior)))
   :after  (fn after [ctx]
             ;; if the handler changed db, push the prior value to :undo.
             (let [prior    (get-in ctx [:coeffects :prior-circles])
                   db-after (get-in ctx [:effects :db])]
               (if (and db-after (not= prior (get-in db-after [:drawer :circles])))
                 (-> ctx
                     (update-in [:effects :db :drawer :undo] (fnil conj []) prior)
                     (assoc-in  [:effects :db :drawer :redo] []))
                 ctx)))})
```

Read it through the two-key lens. `:before` reads the *inputs* (`:coeffects`, where `:db` is the pre-handler value) and stashes the prior circles on the context. `:after` reads the *outputs* (`:effects`, where `:db` is the post-handler value — absent if nothing changed), compares, and only then pushes an undo step and clears redo. Which events are undoable is decided entirely by inclusion: both consuming events *reference* `:drawer/undoable` in their chain, and the continuous one opts out by omission.

```clojure
(rf/reg-event :drawer/add-circle
  {:doc "Click on canvas — add a circle of default radius."
   :interceptors [:drawer/undoable]}                      ;; reference by id
  (fn [{:keys [db]} [_ x y]]
    (let [id (get-in db [:drawer :next-id])]
      {:db (-> db
               (update-in [:drawer :circles] conj {:id id :x x :y y :radius 30})
               (assoc-in  [:drawer :next-id] (inc id)))})))

(rf/reg-event :drawer/dialog-drag
  {:doc "Slider movement — updates the draft radius only. Continuous; NOT undoable."}
  (fn [{:keys [db]} [_ new-radius]]
    {:db (assoc-in db [:drawer :dialog :draft-radius] new-radius)}))

(rf/reg-event :drawer/close-dialog
  {:doc "Commit the dialog's draft radius onto its circle. One undo step."
   :interceptors [:drawer/undoable]}                      ;; reference by id
  (fn [{:keys [db]} _]
    (let [{:keys [circle-id draft-radius]} (get-in db [:drawer :dialog])]
      {:db (-> db
               (update-in [:drawer :circles]
                          (fn [cs] (mapv #(if (= circle-id (:id %))
                                            (assoc % :radius draft-radius)
                                            %)
                                         cs)))
               (assoc-in [:drawer :dialog] nil))})))
```

The drag handler mutates only the dialog's draft, so a hundred slider moves never touch `:circles`. When `:drawer/close-dialog` commits, the snapshot `undoable` took is exactly the pre-dialog state, and the whole edit collapses into one undo step, for free. Closing the loop, undo itself is an ordinary event — no interceptor needed, just state moving between stacks (redo mirrors it with the stacks swapped):

```clojure
(rf/reg-event :drawer/undo
  {:doc "Pop one snapshot from :undo, push current :circles to :redo."}
  (fn [{:keys [db]} _]
    (let [{:keys [undo circles]} (:drawer db)]
      {:db (if (empty? undo)
             db
             (-> db
                 (assoc-in [:drawer :circles] (peek undo))
                 (update-in [:drawer :undo] pop)
                 (update-in [:drawer :redo] (fnil conj []) circles)))})))
```

One registered interceptor, plus which chains reference it: that's the entire undo feature.

## When a reference is wrong

Because a chain is just data, the runtime can check it *eagerly* — and it does. The single most common mistake, a misspelled id, dies at the earliest possible moment:

> **A typo'd reference fails at registration, not at the third dispatch in a demo.** Register an event whose `:interceptors` names an id that nobody has registered, and `reg-event` (or `reg-frame`) throws `:rf.error/unregistered-interceptor` right there at the registration site — naming the missing id. You find out when you load the namespace, not when an unlucky user trips the chain.

A handful of sibling errors cover the other ways a reference can be malformed. They all fire loudly — re-frame2 never silently drops a chain entry it can't make sense of:

| Error | What you did |
|---|---|
| `:rf.error/invalid-interceptor` | `reg-interceptor` got a descriptor that isn't `{:before}` / `{:after}` / `{:before :after}` / `{:factory}`. |
| `:rf.error/unregistered-interceptor` | A chain references an id with no registration. |
| `:rf.error/invalid-interceptor-ref` | A chain entry is neither a bare keyword nor an `[id arg]` 2-vector. |
| `:rf.error/inline-interceptor-removed` | A public chain holds an interceptor map / value / Var instead of a reference. Register it and reference it by id. |
| `:rf.error/interceptor-factory-arity` | A bracket ref targets a non-`:factory` interceptor, or the factory can't build for that arg. |
| `:rf.error/path-interceptor-bad-path` | `[:rf.interceptor/path …]` got a non-vector path. |
| `:rf.error/interceptor-override-invalid` | An `:interceptor-overrides` key or replacement isn't a valid reference. |

These are *static* failures — the chain is wrong before any event runs. The other family of failure is a slot that runs and throws, which behaves quite differently.

## When the chain throws

Every slot runs guarded, and two rules govern how throws compose.

> **Write your `:after` to survive error paths.** A throw in a `:before` (or in the handler) skips the remaining `:before` stages **and the handler** — nothing runs against a half-built context. But the `:after` pass always runs, in full, even after a `:before` failure, in the same reverse order. That's exactly why cleanup belongs in `:after`, and why your `:after` should be written to run on error paths, not just happy ones — an `:after` that assumes the handler always populated `[:effects :db]` will itself throw on the error path.

Errors collect on the context — the first throw under `:rf/interceptor-error`, every throw under `:rf/interceptor-errors`, so post-hoc inspection (Xray, Story) sees them all even though the trace stream emits just one. A throw anywhere means the event installs nothing: `app-db` unchanged, no `:fx` fired. That one emitted event is attributed to the **true culprit**, not just "something in the chain":

- `:rf.error/handler-exception` — the event handler itself threw.
- `:rf.error/coeffect-exception` — a coeffect supplier threw during context assembly (before any `:before` ran).
- `:rf.error/interceptor-exception` — one of *your* interceptor slots threw; it carries the failing interceptor's `:id` and a `:phase` tag that says `:before` or `:after`.

An `:after` that throws is recorded but does **not** abort the remaining `:after` stages — the runtime still drives the rest of the teardown, so one buggy cleanup can't strand the others. The error pages these feed are covered in [errors](errors.md); the normative chain-execution contract is in [the frames spec](../../../spec/002-Frames.md).

---

**You can now:**

- register an interceptor with `reg-interceptor` — `{:before}` / `{:after}` / `{:before :after}` for a static one, `{:factory}` for a parameterized family — and move data through the two-key context map (`:before` reads inputs, `:after` reads inputs *and* outputs)
- reference interceptors from a chain by id — a bare keyword for a static interceptor, `[id arg]` for any parameterized one (`[:rf.interceptor/path [:x]]` being the one standard interceptor) — and know that inline values are rejected
- predict any chain's order: frame interceptors prepended outermost, then event ones, `:before` in declaration order, handler as the last `:before`, `:after` in reverse
- rely on complete inputs: coeffect delivery is context assembly, so no interceptor ever sees a half-injected `:coeffects` map
- reference a concern from one event (`:interceptors` metadata) or a whole frame (`reg-frame :interceptors`), and remove or swap one by exact reference with `:interceptor-overrides` (frame `<` per-call) in a test
- introspect a chain with `handler-meta` — read an event's authored refs, then each ref's `:doc` and source — and trust that a typo'd reference fails loudly at registration, not at dispatch
- keep interceptors replay-safe by contributing `:fx` rows instead of performing work — and ship undo as one registered interceptor plus a reference on the events that deserve it
