# app-db: the one place

Your application's state lives in one place. It is a single immutable map called [**app-db**](../glossary.md#app-db) — your app's whole state, in one value. That is the whole answer, and almost everything else in re-frame2 stays simple *because* of it.

This page builds that idea up one step at a time: first the map and the one thing allowed to change it, then where the first value comes from, then the handful of properties you get for free, and finally — once you are already productive — the framework's own bookkeeping that lives next door and the surgical lane that tests and tools use.

## One map, one writer

Everything your app knows sits in one map — the logged-in user, the cart, which modal is open:

```clojure
{:user {:id 42 :name "Mike" :email "mike@example.com"}
 :cart {:items [] :status :draft}
 :ui   {:active-panel :cart :modal nil}}
```

Nested maps, vectors, sets, keywords. Ordinary data, no imposed schema. And exactly one thing ever changes it: an [event handler](../glossary.md#event-handler) — the function a [dispatched event](../glossary.md#event) runs — returning a new version of the map.

```clojure
(rf/reg-event :cart/add
  (fn [{:keys [db]} [_event item]]
    {:db (update-in db [:cart :items] conj item)}))
```

Read that carefully, because it is the whole immutability story in four lines. The handler receives the current map as `db` (pulled out of the argument map by `{:keys [db]}`, Clojure's way of saying "bind the `:db` key to a local named `db`"). It does not *change* that map. `update-in` doesn't edit `db`; it returns a *new* map with one nested spot updated — here, `conj` (add an element) appended onto the items vector. The handler hands that new map back as its `:db` effect rather than editing anything in place.

Then the runtime does the only mutable step in the whole story. The map itself is immutable, but the name `app-db` is a cell that *points* at a map, and the runtime atomically swaps which map it points at — old value out, new value in, in one indivisible move. The old value still exists, untouched. The new one shares almost all of its structure with the old one — Clojure's persistent data structures don't copy, so the new map just points at the unchanged parts of the old one. Nobody ever observes anything halfway.

That is the entire shape of state in re-frame2: **structured data in one map; events in, new map out.** You can hold that and start writing apps. The rest of this page is what follows from it.

??? info "Coming from Redux?"

    You already have the shape: app-db is the single store, and an [event handler](../glossary.md#event-handler) is a reducer that returns the next state. Three things differ, and they're worth naming. There are no combined reducers and no prescribed slice shape — app-db is one ordinary Clojure map, arranged however your domain wants. Handlers don't return slice updates; they return the whole next value of the map. And immutability isn't a discipline you maintain with the spread operator (`{...state, cart}`) — Clojure's data structures are immutable by construction, so `update-in` *can't* mutate the old value even if you tried.

??? info "From re-frame v1"

    This is unchanged from v1 in spirit — one app-db, one writer, handlers return a new value. What changed is what app-db is *allowed to hold*. In v1 the framework and its libraries colonised app-db with their own bookkeeping under a `:rf/runtime` root, so an ordinary fresh `:db` return could silently delete it. v2 retires that (see [Yours, and the framework's next door](#yours-and-the-frameworks-next-door) below): app-db is now *only* your application data, and the old `:rf/runtime` root is structurally gone.

Not every event has to return a new map, though. A handler that returns no `:db` key — or returns the *same* `db` object it was handed — changes nothing, and that is a first-class, intended outcome. An event that only fires effects (a `:fx` with no `:db`) leaves app-db exactly as it was; so does the common short-circuit `{:db (if changed? (assoc db …) db)}`, whose `else` arm hands back the unchanged object. The runtime notices that object is the one it already holds (an `identical?` check, cheaper than comparing values) and skips the write entirely — no commit, no subscription recompute, nothing downstream re-renders. "Events in, new map out" is the shape; "no new map" is a legitimate special case of it.

!!! warning "Gotcha — `{:db nil}` wipes app-db; it doesn't error"

    app-db is *always* a map, never `nil`. So a handler that accidentally computes `{:db nil}` — a `get-in` that missed, a threading macro that fell off the end — doesn't throw: the runtime coerces the `nil` to `{}` and emits a dev-mode `:rf.warning/db-nil-coerced` diagnostic, because that pattern is almost always a bug quietly erasing your state. If you *mean* to clear app-db, say so explicitly with `{:db {}}` (which fires no warning). Watch for this one: the symptom is "my whole app went blank after that event," and the warning in the console is the thread to pull.

Here's the "one map" claim, live. Two views below share one value — the input edits it, the badge reads it — and neither owns a copy, so there is nothing to drift out of sync. Click into the cell, press **`Ctrl-Enter`** (**`Cmd-Enter`** on macOS) to evaluate, then type:

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

(rf/reg-event :appdb.demo/initialise
  (fn [_cofx _event] {:db {:appdb.demo/name "Ada"}}))

(rf/reg-event :appdb.demo/name-typed
  (fn [{:keys [db]} [_ v]] {:db (assoc db :appdb.demo/name v)}))

(rf/reg-sub :appdb.demo/name
  (fn [db _query] (:appdb.demo/name db)))

;; Two views, one truth — neither owns the value; both read the same path.
(defn name-editor []
  [:input {:value     @(rf/subscribe [:appdb.demo/name])
           :on-change #(rf/dispatch [:appdb.demo/name-typed (.. % -target -value)])}])

(defn name-badge []
  [:p "Hello, " [:strong @(rf/subscribe [:appdb.demo/name])] "!"])

(rf/dispatch-sync [:appdb.demo/initialise])
[:div [name-editor] [name-badge]]
```

!!! tip "Try it"

    Add a third view reading the same subscription — `(defn greeter [] [:p "Welcome back, " @(rf/subscribe [:appdb.demo/name])])`, mounted beside the others — and re-evaluate. Three windows, still one value, and you wrote no synchronisation code to keep them agreeing.

### Where the first value comes from

If a handler only ever *transforms* app-db, what hands it the very first one? Every [frame](../glossary.md#frame) — one isolated, independently-running instance of your app, the thing app-db belongs to — starts with `app-db` equal to the empty map `{}`. There is no `:db` config slot to seed it with. Seeding the initial state is itself an event, so it runs through the same [event cascade](../glossary.md#event-cascade) as every later change. You list the setup events when you register the frame, as `:initial-events`, and the conventional first step seeds app-db with the built-in `:rf/set-db` event:

```clojure
(rf/reg-frame :app
  {:initial-events [[:rf/set-db {:user nil
                                 :cart {:items [] :status :draft}
                                 :ui   {:active-panel :cart :modal nil}}]
                    [:app/restore-session]
                    [:app/load-preferences]]})
```

The steps dispatch synchronously, in order, each one run to completion before the next, so by the time `reg-frame` returns app-db is in whatever state they produced. The point is consistency: there is no special "initial state" mechanism off to one side. The first value of app-db is built by the same dispatch you use for the millionth. [Frames](frames.md) covers `:initial-events` and the rest of the registration grammar in full.

`:rf/set-db` is an ordinary event in every respect — it commits a `:db`, rides schema validation, shows up in the trace — with one thing worth knowing: it *replaces* the whole of app-db with the map you give it, rather than merging into what's there. That is exactly what you want for a fresh `{}` frame, and it is the right tool for a test fixture that wants a clean known state. For an ordinary in-place change you still write your own event; `:rf/set-db` is the wholesale-replace one. (It takes exactly one map argument — a non-map, or extra arguments, raise `:rf.error/set-db-bad-value`; and because the `:rf/*` namespace is the framework's, re-registering `:rf/set-db` in your own code is a loud `:rf.error/reserved-event-id` collision.)

??? info "From re-frame v1"

    v1 seeded app-db with an `:initial-db` config value (or an `:on-create` hook). Both are retired. The new way is `:initial-events` with a leading `[:rf/set-db {…}]` step — so even your *first* state arrives by dispatch through the event cascade, not a side channel. One mechanism, used everywhere.

## A database, on purpose

The name is `app-db`, not `app-state`, and the `db` carries weight. Not in the storage sense — it is all in memory, and nothing survives a reload unless you make it. The weight is in the mindset. Think how much care you give data in PostgreSQL: a schema, invariants, deliberate queries, atomic transactions, and no random function scribbling on a row as a side effect. Now think how much care the average frontend gives data scattered across thirty `useState`s. app-db asks you to treat your in-memory state with that same database care: structured data in, reads out through [subscriptions](../glossary.md#subscription) (named, cached, pure derivations of the map), and changes only through events. The name is a discipline disguised as a noun.

You can [add a schema](../how-to/validate-with-schemas.md) when you want the app to scream the instant the shape goes wrong — but you never have to, and nothing forces one on you.

> *Well-formed data at rest is as close to perfection in programming as it gets.* — [Fogus](https://twitter.com/fogus/status/454582953067438080)

## Why one place pays for itself

Most frameworks let state live anywhere: any component's `useState`, a context, a ref, an external store. Every one of those is a place state can hide, and a way two copies can quietly disagree. re-frame2 makes the opposite bet, and you get four properties that are genuinely hard to buy any other way:

1. **No synchronisation code.** When a piece of data has exactly one home, no code copies it to a second home and keeps the two in step. The "these two parts of the screen disagree" bug can't occur, because there are no copies to drift. You don't write the sync code, you don't debug it — you just have less app.

2. **State changes are transactional.** Each event handler returns one new app-db, and the runtime [commits](../glossary.md#commit) it atomically. There is no instant where the cart total has updated but the items haven't, so there's no intermediate inconsistency for a subscription to read and render. Either the whole transition happened or none of it did.

3. **One schema validates the whole app.** All state is one map, so a single [schema](../glossary.md#schema) can describe the entire application and run in one place: after every event, in dev. Because it spans the *whole* map, that schema can state relationships *between* values ("if logged in, a token must be present") — something thirty scattered state cells never could. [Validate with schemas](../how-to/validate-with-schemas.md) shows how.

4. **Undo and time-travel come for almost nothing.** Snapshotting an immutable map takes a *reference*, not a copy, so structural sharing makes a ring buffer of hundreds of past values nearly free. Undo is "swap the reference back". [Xray](../glossary.md#xray)'s [epoch](../glossary.md#epoch) history — the thing that lets you scrub a running app backwards — is literally this. You don't build undo; you discover you already have it.

That is the trade in full view. You give up stashing state wherever is convenient, and you give up sneaking a mutation in from some corner of the app — which is the *name of the bug you spent last Thursday on*. Less flexibility, more inspectability.

??? note "Going deeper — the app *is* the value"

    The sentence under all four payoffs is exactly that. A re-frame2 app at any instant is *defined* by a value: its app-db, together with the framework's bookkeeping next door. Two apps holding equal values are, observably, the same app at that moment — same screen, same behaviour. An event is then just a pure function `value → value`, and a session is a *fold* over a stream of events: `(reduce step initial-db events)`. Every property above is a corollary of that one algebraic fact. Transactionality is "`step` returns before the value is published"; undo is "keep the old summand"; replay is "re-run the fold". You don't have to think in these terms to be productive — but if you wondered *why* the four payoffs cluster together, this is why: they are all the same equation seen from different sides.

## Missing is not nil

One small distinction matters everywhere in re-frame2, so meet it here. A key that is *absent* from a map is a different fact from a key *present with the value `nil`*:

```clojure
(get-in {}          [:page])   ;; => nil — the key isn't there
(get-in {:page nil} [:page])   ;; => nil — the key is there, holding nil
```

A bare `get` can't tell them apart, and the framework preserves the difference wherever it matters. Did the server send `null`, or send nothing? Is this form field cleared, or never touched? Those are different questions, and the answers shouldn't collapse into one.

!!! warning "Gotcha"

    A few surfaces treat `nil` as absence on purpose — routing drops a `nil` query parameter from the URL, for example. But that is always a declared, local policy, never an accidental erasure. Where the distinction matters, it survives.

## Paths, in five lines

A **path** names a place inside app-db. It is the same vector you hand to `get-in` — Clojure's built-in for reaching into a nested map, `(get-in m [:a :b])` being the data-structure cousin of `m.a.b`:

1. A path is a vector of segments: `[:cart :items 0 :qty]`. Vectors are the canonical form.
2. The empty path `[]` is the root — it addresses the entire map.
3. Segments are portable data — keywords, strings, integers, UUIDs — never live host objects like functions or DOM nodes. A path prints, diffs, and round-trips, because it's data all the way down.
4. Equal data is the same address wherever data names things — resource cache keys, route params: `{:a 1 :b 2}` and `{:b 2 :a 1}` are one identity (you never hand-craft cache-key strings), and the missing-vs-`nil` distinction above survives the comparison.

??? note "Going deeper"

    Every feature that takes a path — schemas, [flows](../glossary.md#flow), routing, [resources](../../resources/glossary.md#resource) — obeys this one definition, which is why a path means the same thing everywhere you can write one.

## Yours, and the framework's next door

So far app-db has been the *only* state in the app, and for everything you write that is true: app-db holds your data, events change it, subscriptions read it. You can stop here and be productive. This section is the one piece of the picture you've been promised but not yet seen — and it is worth knowing, because it's the reason your data stays clean.

There is exactly one category of state in a running re-frame2 app that is *not* yours: the bookkeeping the framework keeps for running processes. A [machine](../../machines/glossary.md#machine)'s [snapshot](../../machines/glossary.md#snapshot), the current route, the [resource](../../resources/glossary.md#resource) cache and its in-flight work ledger. This is real, per-[frame](../glossary.md#frame) state — it belongs to a frame just as app-db does — and it must time-travel and survive the wire like everything else. But it is not application data, and hand-editing it corrupts the process that owns it.

So it doesn't live in app-db at all. A frame holds [**two partitions**](../glossary.md#the-two-partitions):

- [**app-db**](../glossary.md#app-db) — yours. Application data and *nothing else*. Every event handler receives it (as the `:db` [coeffect](../glossary.md#coeffect)) and replaces it (by returning a `:db` effect).
- [**runtime-db**](../glossary.md#runtime-db) — the framework's. [Machine](../../machines/concepts.md) snapshots, the [route](../../routing/concepts.md) slice, the [resource](../../resources/concepts.md) cache, in-flight work records, all under reserved `:rf.runtime/*` keys. The relevant runtime writes it; you read it through that feature's subscriptions, like `[:rf/machine :checkout/flow]` or `[:rf.route/id]`.

!!! note "Why the partition is structural, not a convention"

    Why a separate partition instead of a reserved key in one map? Because a single map invites a footgun: a handler returning a fresh `{:user ...}` would silently wipe a machine snapshot living beside it. With two partitions that footgun is *structurally* gone: a `:db` effect replaces **only** app-db, so no ordinary `:db` return can touch runtime-db, no matter how careless. (The framework supplies runtime-db to a handler as a read-only coeffect, and the write *prohibition* is enforced by convention plus a dev diagnostic rather than a hard capability wall — but the thing that actually bites, an accidental clobber via a plain `:db` return, simply cannot happen.) The seam you must not cross by hand is the one the structure already keeps you from crossing by accident.

The doctrine for the framework's partition is **read, don't write**. You read a managed slice through subscriptions, and you influence it by [dispatching](../glossary.md#dispatch) — handing off — the events its process understands. You never write it directly, because the process that put it there is what keeps it correct:

```clojure
;; WRONG — forging the route by hand. This writes app-db; the real route
;; slice lives in runtime-db, and no navigation actually happens.
(rf/reg-event :go-to-cart
  (fn [{:keys [db]} _] {:db (assoc db :route :route/cart)}))

;; RIGHT — speak the process's language; the routing runtime writes its own slice.
(rf/dispatch [:rf.route/navigate :route/cart])
```

The wrong version writes a decoy. It sets an app-db `:route` key the routing runtime neither reads nor maintains, and it skips everything navigation entails: no URL push, no transition lifecycle, no view change. The right version hands the work to the slice's only legitimate author. The same rule covers machines (send a trigger, don't edit the snapshot) and server data (dispatch the request, don't flip a `:loading?` flag).

The two partitions compose into one **frame-state** value — `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`. That composite is what time-travel reverts and server-side rendering ships, as one coherent unit. Day to day you never name it: you write events against app-db, you subscribe to read either partition, and the seam stays invisible.

??? note "Going deeper"

    "Read, don't write" is the doctrine for *application* code, and it holds without exception there. The framework's own subsystems *do* write runtime-db — and they do it through the same pipeline you use for app-db: a privileged handler returns a `:rf.db/runtime` effect alongside (or instead of) its `:db` effect, and the commit step installs both partitions in one atomic frame-state transition. That is how a machine snapshot or route slice lands without a value ever being observed halfway. You will not write `:rf.db/runtime` in application code — the routing, machine, and resource runtimes own those keys — but it is worth knowing the seam is the event pipeline on both sides, not a back door.

## Classifying a path you own

app-db is *your* data, which means you are also the one who knows when a piece of it is a secret. A path holding an auth token, a session cookie, or scanned PII should not leak into a trace dump, an SSR payload, or a bug report a teammate pastes into chat.

So re-frame2 lets you mark such a path as sensitive — its [data classification](../glossary.md#data-classification) surface. Marking is a fact *about* a path in app-db, so you declare it where you already own that path — in the handler, returning a **classification effect** right beside the handler's `:db` write:

```clojure
;; a known secret, classified the moment the frame initialises
(rf/reg-event :auth/init
  (fn [{:keys [db]} _]
    {:db        (assoc db :auth {})
     :sensitive [[:auth :token]]}))

;; a secret discovered at runtime, classified in the handler that writes it
(rf/reg-event :doc/scanned
  (fn [{:keys [db]} [_ doc-id raw]]
    (cond-> {:db (assoc-in db [:docs doc-id] {:body raw})}
      (contains-pii? raw) (assoc :sensitive [[:docs doc-id :body]]))))
```

There are four of these classification effects — they ride along with the `:db` write at commit time, hence "commit-plane" — each taking a **vector of paths**:

- `:sensitive` — redact each path's value at egress (traces, SSR, tool projections see a marker, never the value)
- `:large` — flag each path as too big to ship inline; egress carries a size marker instead
- `:clear-sensitive` / `:clear-large` — un-classify, the rare mirror for when a path is reused for non-secret data

Two properties matter at the write site. The classification is **applied with the `:db` write** — it lands in the same atomic transition, so a path classified in an event is redacted from its *very first* egress, never a beat late. And it is **value-independent**: you can classify `[:auth :token]` before any token exists there, and the marker redacts whatever later occupies the path — classifying an absent path is a harmless no-op. Everything after that — what redaction looks like at each boundary, why the marker time-travels with the frame, how subsystems and transient HTTP payloads are classified — is the egress story, told in full in [Keep secrets out of traces](../how-to/keep-secrets-out-of-traces.md).

## Two lanes: the front door, and the surgeon's table

Everything above is the **front door** — the one lane application code ever uses to change state. An event handler returns a new app-db, the runtime swaps it atomically, subscriptions recompute, and a snapshot of any path is yours to read. Handlers, effects, subscriptions, snapshots: that is the whole of how a re-frame2 app moves from one value to the next, and almost nothing you write will ever touch anything else.

There is a second lane, and it is not for app code. The runtime exposes a small set of operations that *overwrite* a frame's state wholesale, bypassing the event pipeline entirely:

```clojure
(rf/replace-app-db!      frame-id app-db)        ;; swap only the app partition
(rf/replace-runtime-db!  frame-id runtime-db)    ;; swap only the framework partition
(rf/replace-frame-state! frame-id frame-state)   ;; swap both, atomically
(rf/reset-app-db!        frame-id)               ;; clear the app partition
(rf/restore-epoch!       frame-id epoch-id)      ;; rewind to a captured past state
```

Call these **state surgery**. They don't run a handler, fire no effects, and carry no event through the cascade — they reach past the front door and write the value directly. That makes them exactly the right tool for three jobs, and exactly the wrong tool for everything else:

- **Tests.** A fixture installs a known app-db before assertions, instead of dispatching a dozen setup events to arrive there. `replace-frame-state!` (not app-db-only) is what an epoch-history test needs, because machine actors and the route slice live in runtime-db.
- **Tooling.** [Xray](observability.md) time-travel and the pair MCP rewind a running frame with `restore-epoch!`; an inspector may install a captured state to reproduce a bug.
- **Framework internals.** Restore, SSR hydration, and frame reset replace whole partitions — privileged runtime code, never your handlers.

!!! warning "Gotcha — never reach for state surgery in application code"

    These are not a faster `assoc`. Calling `replace-app-db!` from a handler or a view forges a value with no event behind it: nothing appears in the [trace](observability.md), the [epoch](../glossary.md#epoch) ledger has no cascade to show, none of the per-event commit machinery runs, and any subscription or machine that assumed an event caused the change is now looking at a state nobody can explain. The very inspectability you bought by putting state in one place evaporates the moment a write skips the pipeline. If app code wants to change state, it dispatches an event — full stop. The surgery lane exists so tools and tests can set up or rewind state *around* your app, not so your app can mutate itself behind its own back.

!!! warning "Gotcha"

    State surgery is gated on debug mode and [elided](../glossary.md#elide) from a release artefact (`:advanced` + `goog.DEBUG=false`) along with the rest of the epoch machinery — the functions are simply not in the shipped bundle. That is deliberate: a production app has no business overwriting its own state out-of-band, and a tooling surface that could is an attack surface. The practical consequence is for *tests* — run a test that calls `replace-frame-state!` (or `restore-epoch!`) against a production-elided build and it will not behave; these belong to dev/test builds. If the epoch artefact itself isn't on the classpath, the epoch-backed surfaces raise `:rf.error/epoch-artefact-missing` rather than silently doing nothing.

The contrast is the point. The front door is auditable because every change is an event with a cause; surgery is powerful because it answers to no cause — which is exactly why it belongs to the test harness and the debugger, not the application. Reach for it only when you are *operating on* a frame from outside (a fixture, a tool, the REPL), never when you are *writing* the app that runs inside one.

A note on what each one targets, since the names are deliberately exact. `replace-app-db!` and `reset-app-db!` touch *only* the app partition and leave runtime-db live — so the route and machine snapshots survive. `replace-frame-state!` is the full-frame install: it replaces both partitions atomically from a `{:rf.db/app … :rf.db/runtime …}` value, which is what an epoch-history test or a tool-driven replay needs. `restore-epoch!` is the same full-frame replace, but sourced from a captured past epoch rather than a value you hand it — it revives app-db *and* runtime-db together, so machine actors and the route slice come back exactly as they were, not just the app-db projection. Every one of these mutators returns a boolean — `true` on success, `false` on a refusal — and a refusal is always a clean **no-op**: the frame is left exactly as it was. They refuse, and tell you why through an error trace, for an unknown or destroyed frame, for a call made *mid-drain* (while a run-to-completion cascade is still in flight — you retry once it settles), and — for the partition-replace surfaces — when the value you handed in fails the frame's registered [schema](../glossary.md#schema). So even though surgery skips the *event-time* validation gate, the `replace-*!` surfaces still won't install a value that violates a schema you declared; they decline rather than corrupt.

`restore-epoch!` has a wider set of refusals than the others, because it isn't installing a value you hand it — it's reviving a *recorded* one out of a finite, evolving history, and several things can make a past epoch no longer restorable. On any of them it is a no-op and names the reason in an error trace (under `:rf.epoch/*`, except the unknown-frame case):

- the epoch id isn't in the frame's retained history (`:rf.epoch/restore-unknown-epoch`) — the ring is finite; old epochs roll off
- the frame is mid-drain (`:rf.epoch/restore-during-drain`) — rejected; retry once the cascade settles
- the recorded state no longer validates against your *current* schemas (`:rf.epoch/restore-schema-mismatch`) — you tightened or changed a schema since that epoch was captured, so rewinding to it would land an app-db your present rules reject
- the target epoch is the record of a *halted* cascade (`:rf.epoch/restore-non-ok-record`) — a cascade that errored or was cut short carries partial state for devtools to inspect, not a settled value, so it is not a valid rewind target

So a failed rewind never half-restores; it leaves you where you were and tells you why. Two rarer refusals round out the set and behave the same way — a machine snapshot recorded under an older machine definition (version drift), and a recorded state that references a registration which no longer exists. Each is a clean no-op that names its reason.

## See it move

Don't take the "one inspectable value" claim on faith. It is the most useful property you now own, so try it on a running app (the [quickstart](../quickstart.md) gives you one in five minutes). At the REPL:

```clojure
(require '[clojure.pprint :refer [pprint]])
(rf/frame-ids)                          ;; the registered frame ids — e.g. #{:app}
(pprint (rf/app-db-value :app))         ;; read the app partition — your data
```

Your entire application state, printed top to bottom, readable as a map. `app-db-value` is the non-reactive snapshot read — the one for tools, tests, and the REPL, distinct from the front-door `subscribe`. Two sibling readers complete the set when you want to see the framework's side too:

```clojure
(pprint (rf/runtime-db-value :app))     ;; the framework partition — routes, machines, …
(pprint (rf/frame-state-value :app))    ;; both at once: {:rf.db/app … :rf.db/runtime …}
```

`frame-state-value` is the coherent full-frame projection — the same shape SSR ships and time-travel reverts. All three return `nil` for an unknown or destroyed frame, so a read against a torn-down frame degrades quietly rather than throwing. (Dispatching or subscribing to a destroyed frame is the same story — `dispatch` no-ops, `subscribe` returns `nil`, and the framework emits a production-survivable `:rf.error/frame-destroyed` so the diagnostic reaches your error monitor instead of vanishing.)

Now open Xray and watch it move. Dispatch an event — say `[:cart/add {:id 22 :qty 1}]` — and the App-db tab shows exactly the slices that changed in that cascade, each with its before and after value, marked added, modified, or removed. That diff is the complete story of what the event did to your data. There is nowhere else application state could have changed, so when something is wrong, this is where you look first. [Debug with Xray](../how-to/debug-with-xray.md) makes a workflow of it.
