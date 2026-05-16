# 04a — Schemas

## TL;DR

You want to catch shape-of-data bugs early — wrong key, wrong type, typo in a path — the kind CLJS won't flag and tests often miss. This page shows how Malli schemas attach to `app-db` slices and event vectors so the dev runtime catches them at the boundary, with zero production cost.

A re-frame2 app's runtime story is "events change `app-db`, views read it." It works because every handler is a pure function over plain Clojure data — but "plain Clojure data" is also a category roomy enough to hide bugs. Did `:status` end up as `:submitted` or `"submitted"`? Did `:user/id` arrive as an `int` or a `string`? Did the form handler clobber `:auth/user` with a typo'd path? These are the kinds of mistake the type system doesn't catch (CLJS doesn't have one), the test suite often misses (you didn't think to assert it), and that surface six weeks later as "the page renders but the avatar is gone."

**Schemas** are the answer. A schema is a piece of data that says *what shape another piece of data must have*. Bind a schema to an `app-db` path and the runtime checks the slice after every handler. Bind a schema to an event via `:spec` and the runtime checks the event vector before the handler runs. In dev, you find out the moment a write goes wrong, with the explanation pointing at the exact key and value. In production, every validation call disappears at compile time.

This chapter is the **Malli warmup**. The forms chapter ([08](08-forms.md)) and the HTTP chapter ([10](10-doing-http-requests.md)) lean on schemas heavily; rather than introduce the vocabulary mid-paragraph there, we cover it once here, before forms need it.

## Why schemas

Three reasons, in roughly the order you'll notice them:

1. **Runtime validation in dev.** A handler that returns the wrong shape — a `:status` outside the enum, a `:count` that became a string, an `:errors` map keyed by strings instead of keywords — surfaces immediately, with the explanation pointing at the bad path. No more "the bug shipped to staging and the avatar disappeared."

2. **Documentation that doesn't drift.** The schema is the canonical answer to "what's this slice supposed to look like?" Comments rot; schemas can't, because the runtime checks them. A teammate (or an AI scaffolding a new handler) reads the schema and knows the shape.

3. **AI introspection.** Tools and agents can query `(rf/app-schemas)` and `(rf/handler-meta :event :foo)` and *know* the registered shape of every slice and event in the app. A pair-tool can pre-check "what would happen if I dispatched `[:auth/login {...}]`?" against the event's `:spec` before firing it. The schema is the agent-facing contract.

The cost is small. Schemas are plain Clojure data — vectors of keywords — that you write once when you create a slice or an event. The runtime does the rest.

## The Malli vocabulary

re-frame2 ships with [Malli](https://github.com/metosin/malli) as the default validator. (The validator is pluggable — Spec 010 covers the extension point — but every example in the guide uses Malli, and unless you have a reason to swap, you should too.)

A Malli schema is a *vector* whose first element is a keyword naming the schema kind, optionally followed by a properties map, then the body. The seven shapes you'll meet most often:

```clojure
;; A map with two required keys.
[:map
 [:email    :string]
 [:password :string]]

;; A keyword from a fixed set.
[:enum :idle :submitting :submitted :error]

;; An integer with a lower bound.
[:int {:min 0}]

;; A non-empty string.
[:string {:min 1}]

;; A regex predicate.
[:re #".+@.+"]

;; The leaf scalars.
:keyword            ;; any keyword
:uuid               ;; a UUID
:boolean            ;; true or false
:any                ;; anything; useful for "I haven't figured out the shape yet"
[:maybe :string]    ;; either a string, or nil
```

These compose. A form's draft schema is `[:map [:email [:re #".+@.+"]] [:password [:string {:min 8}]]]`. A status field is `[:enum :idle :submitting :submitted :error]`. A nested slice is a `[:map ...]` containing other `[:map ...]` entries. There's more vocabulary — `[:vector ...]`, `[:set ...]`, `[:map-of ...]`, `[:fn pred]`, custom registries — but the seven shapes above carry 90% of an app's schemas.

A key in a `[:map ...]` is **required** by default. To make one optional, add `{:optional true}` in its properties map:

```clojure
[:map
 [:email                       :string]
 [:phone   {:optional true}    :string]]    ;; either present or absent
```

A `[:map ...]` is **open by default** — extra keys not named in the schema are tolerated. This is deliberate (Spec 010 explains why) and matches re-frame2's "consumers tolerate unknown keys" convention. To reject extra keys, opt into `{:closed true}` in the properties map — typically only at system boundaries, where you want strict validation of incoming payloads.

## Framework-reserved per-slot metadata: `:large?` and `:sensitive?`

The slot properties map is also where you declare two framework-aware runtime flags that have nothing to do with validation and everything to do with the wire-boundary trace stream:

```clojure
(rf/reg-app-schema
  [:user]
  [:map
   [:profile      [:map [:name :string] [:email :string]]]
   [:auth-token   {:sensitive? true}                          :string]   ;; redacted in traces
   [:uploaded-pdf {:large? true :hint "Upload preview blob"}  :string]]) ;; elided in traces
```

- `:large? true` declares the slot's value is large enough that the framework should **elide** it from the wire — every trace event that would otherwise carry the value substitutes a `:rf.size/large-elided` marker (`{:path :bytes :type :hint :handle}`). The runtime walks every registered schema at boot, populates `[:rf/elision :declarations]`, and `rf/elide-wire-value` consults it on every emit.
- `:sensitive? true` declares the slot's value is **sensitive** — the schema-validation error path redacts the value before it rides the trace stream (the sentinel `:rf/redacted` appears in place of the value), and consumers route on a top-level `:sensitive?` flag. The two compose; sensitive wins on both-flagged slots.

Both flags accept the same two structural positions as `{:optional true}`: per-slot inside a `:map` (path is the slot's path), or container-level when the schema is registered at the path directly (e.g. `[:string {:sensitive? true}]`). The optional `:hint` string on the same props map rides through to the wire marker, orienting AI consumers without forcing a drill-down. **The schema is the one primary declaration site** for both axes — the framework auto-installs the trace scrub for `:sensitive?` slots and the wire marker for `:large?` slots; you do not write a separate interceptor or runtime registration. The full picture from the app-writer's side — the schema as the canonical truth, the one handler-meta `:sensitive?` escape hatch for cross-cutting cases, HTTP redaction, and the consumer-side composition rules — is split across [chapter 23a — Privacy](23a-privacy-secrets.md) (the `:sensitive?` half) and [chapter 23b — Large blobs](23b-large-blobs.md) (the `:large?` half); this section is just the discoverability hook from the schemas side.

## `reg-app-schema` — binding a schema to a path

The everyday API. You point it at an `app-db` path and hand it a schema:

```clojure
(def AuthSlice
  [:map
   [:user    [:maybe [:map [:id :uuid] [:email :string]]]]
   [:status  [:enum :anonymous :authenticated]]])

(rf/reg-app-schema [:auth] AuthSlice)
```

Now, after every event handler, the runtime extracts whatever's at `[:auth]` in the new `app-db` and validates it against `AuthSlice`. If the handler wrote `:status :loggedin` (a typo not in the enum), the runtime emits `:rf.error/schema-validation-failure` with `:where :app-db :path [:auth] :value {:status :loggedin ...} :explain {...}` and **rolls back the `:db` effect** — the pre-handler `app-db` is restored, the dispatch is treated as failed.

Rollback is load-bearing. The handler that produced the bad shape is named in the error, the value that failed is in the error, the *app* is still in the last good state. You don't end up debugging a corrupted `app-db` at 2am.

The path is `get-in`-shaped, so nested slices compose:

```clojure
(rf/reg-app-schema [:auth :login]       FormSlice)        ;; the form's lifecycle shape
(rf/reg-app-schema [:auth :login :draft] LoginForm)       ;; the value the user is filling in
```

Two schemas at two paths — both validate. A write through `[:auth :login :draft]` is checked against `LoginForm`; the surrounding `[:auth :login]` slice is checked against `FormSlice`. They compose without you wiring anything.

For features that declare several schemas at once, the plural form takes a `{path -> schema}` map:

```clojure
(rf/reg-app-schemas {[:auth]                AuthSlice
                     [:auth :login]         FormSlice
                     [:auth :login :draft]  LoginForm})
```

## Event schemas via `:spec` metadata

The other everyday surface. Every `reg-event-*` accepts an optional metadata map between the id and the handler; `:spec` in that map is the event vector's schema:

```clojure
(rf/reg-event-db :form.login/edit-field
  {:doc  "User changed a single field."
   :spec [:cat [:= :form.login/edit-field] :keyword :string]}
  (fn [db [_ field value]]
    (-> db
        (assoc-in  [:auth :login :draft field] value)
        (update-in [:auth :login :touched] conj field))))
```

`[:cat ...]` is "a sequence of these, in order." The first element is the event id itself (constrained by `[:= :form.login/edit-field]`), the second is the field keyword, the third is the string value. Dispatching `[:form.login/edit-field :email "user@host"]` passes; `[:form.login/edit-field "email" 42]` fails fast with `:where :event`, the handler is **not invoked**, and the cascade halts at this event (downstream events in the queue continue to drain).

`:spec` works for every `reg-*` family — `reg-sub` for the return value, `reg-fx` for the fx args, `reg-cofx` for the injected value. The shape is the same: a Malli schema in the registration's metadata map. The everyday rule for failure handling: event-vector and cofx failures skip the handler; `app-db` failures roll back; fx-args failures skip the offending fx only; sub-return failures default the sub to `nil`.

## Dev vs production — when schemas fire

In **dev builds**, every registered schema is checked at its registered validation point. The cost is real — every event runs through one event-vector check, possibly several cofx checks, and one `app-db` check per registered path — but tolerable, and you want to be paying it during development.

In **production builds**, every validation site is **elided at compile time**. The mechanism: every validation call is wrapped in `(when re-frame.interop/debug-enabled? ...)`, which is `goog.DEBUG` on CLJS — `true` in dev, `false` under `:advanced` production (`{:closure-defines {goog.DEBUG false}}`). The closure compiler constant-folds and DCEs every validator call, the trace envelope, and the explanation string. Production cost: zero.

Schemas stay **registered** in production — tooling can still introspect them — but they're not *checked*. The implication is the one you'd hope for: write schemas freely, in volume, without thinking about hot-path cost.

If you want validation at **system boundaries** in production — incoming HTTP responses, websocket messages, postMessage payloads — re-frame2 ships a `:spec/at-boundary` interceptor:

```clojure
(rf/reg-event-fx :api/response-received
  {:spec ApiResponseSchema}
  [rf/at-boundary]
  (fn [{:keys [db]} [_ response]] ...))
```

The interceptor forces a check against the handler's `:spec` regardless of the global elision flag — every incoming payload from untrusted sources is validated even in production. The other 99% of handlers stay zero-cost.

## A tiny worked example

A counter that remembers its history, with both the `app-db` slice and the increment event schema-bound:

```clojure
(def CounterSlice
  [:map
   [:count   [:int {:min 0}]]
   [:history [:vector [:int {:min 0}]]]])

(rf/reg-app-schema [:counter] CounterSlice)

(rf/reg-event-db :counter/initialise
  {:spec [:cat [:= :counter/initialise]]}
  (fn [db _]
    (assoc db :counter {:count 5 :history [5]})))

(rf/reg-event-db :counter/inc
  {:spec [:cat [:= :counter/inc]]}
  (fn [db _]
    (-> db
        (update-in [:counter :count]   inc)
        (update-in [:counter :history] conj (inc (get-in db [:counter :count]))))))
```

Three things to notice:

1. **`CounterSlice` is two lines.** The schema is shorter than the handler. That's normal — schemas are dense.

2. **`:count` can't go negative.** `[:int {:min 0}]` rejects `-1`. A buggy handler that did `(update db :count dec)` past zero would write `-1`, the `app-db` schema check would fail, the `:db` effect would roll back, the trace event would say `:rf.error/schema-validation-failure :path [:counter] :value {:count -1 :history [...]}`. You see the bug the first time it happens.

3. **Both events have a `:spec`.** Both are nullary (no payload), so the schema is `[:cat [:= :counter/initialise]]` — "a vector containing exactly the event id, nothing else." Dispatching `[:counter/inc :something]` fails the event-vector check; the handler isn't invoked. Trivial schemas like this exist for the same reason as the slice schema — they catch typos in dispatch sites that no test would think to write.

## When to reach for schemas, and when not to

**Reach for a schema** when:

- A slice has more than two or three keys (the typo surface widens).
- A field has a *constrained* shape — an enum status, a positive int, a regex'd string, a uuid.
- The slice's shape is the contract between two features (one writes, another reads).
- An event has a payload more complex than "the id and one keyword."
- The slice or event is being authored or maintained by an AI agent — agents read schemas to know what to write.

**Don't reach for a schema** when:

- The slice is a single scalar — `{:counter/show? true}` doesn't need `[:map [:show? :boolean]]`; it's not catching anything a typo couldn't surface immediately.
- The shape is *genuinely* `:any` — a slot holding arbitrary user-supplied data with no constraints. Don't write a schema that's just `:any`; the schema's job is to say "what shape this *must* have", not to be a placeholder.

The discriminator is *can a schema catch something a normal test wouldn't?* If the answer is yes, write the schema. If the answer is "this is just shape-checking that the handler obviously does correctly," skip it.

## Conformance checklist

A schema-aware feature matches the convention when:

- Every non-trivial `app-db` slice the feature owns has a `reg-app-schema` binding.
- Every event with a payload more complex than the event id has a `:spec` in its metadata.
- The schemas live next to the handlers and `reg-app-schema` calls that use them, not in a far-away "schemas" namespace — the schema is the *documentation* of the slice, and documentation should live with the thing it describes.
- Enums are `[:enum ...]`, not `:keyword` ("oh, it'll be one of these four values" loses leverage as `:keyword`).
- Closed-map semantics (`{:closed true}`) appear only at system boundaries; everything else is open by default.

## Cross-references

- [chapter 08 — Forms](08-forms.md) — the first heavy schema user: `FormSlice` for the slice shape, `LoginForm` for the value shape, both bound with `reg-app-schema`.
- [chapter 10 — Doing HTTP requests](10-doing-http-requests.md) — schemas as the canonical `:decode` for response bodies.
- [chapter 14 — Errors](14-errors.md) — what `:rf.error/schema-validation-failure` looks like as it flows through the trace stream.
- [Malli's README](https://github.com/metosin/malli) — the full schema vocabulary, registries, custom schemas, generators.

## Next

- [05 — Coeffects](05-coeffects.md) — the matching *inputs* half of the handler's contract (`:db`, `:event`, and the side-causes `inject-cofx` injects). Optional side-track.
- [06 — Views and frames](06-views-and-frames.md) — what you put on the screen, and how you isolate state per frame.
