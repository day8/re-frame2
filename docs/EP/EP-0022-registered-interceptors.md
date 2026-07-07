# EP-0022: Registered Interceptors

Status: final
Type: standards-track

> **`final` means the decisions are settled.** The five open-issue decisions are
> ruled (see [§Open Issues](#open-issues) and the [§Recommendation](#recommendation))
> and the normative homes named below govern (where this EP and the spec differ,
> the spec governs).

> This EP makes interceptors a first-class registered program member:
> application-authored interceptor behavior is registered with
> `reg-interceptor`, and event/frame interceptor chains contain serializable
> interceptor references rather than inline interceptor values. Its normative
> home is `spec/001-Registration.md`, `spec/002-Frames.md`,
> `spec/Conventions.md`, `API.md`, and the v1 migration guide.
>
> **Graduated `accepted → final` 2026-06-15 (Mike, operator graduation).** The
> five open-issue decisions are ruled (see
> [§Open Issues](#open-issues)): **(1)** the standard set is path-only; **(2)**
> additive dispatch-opts `:interceptors` is removed; **(3)** inline interceptor
> values are rejected even for tiny local cases; **(4)** `->interceptor` is no
> longer public; **(5)** `path` no-op preservation is normative. The registered-
> interceptor surface has **shipped and is verified**: `reg-interceptor` is the
> one public authoring form; event/frame chains carry serializable refs only
> (inline values are removed and fail loudly with
> `:rf.error/inline-interceptor-removed`); the standard `:rf.interceptor/path`,
> exact-reference `:interceptor-overrides`, and `:rf.schema/at-boundary` are
> registered; `->interceptor` is internal-only; propagation across examples,
> skills, guide, and tools is done; the correctness review is **CLEAN**; and the
> coverage gaps are closed. `final` asserts the **decisions are settled** and the
> normative homes govern (where this EP and the spec differ, the spec governs).

## Abstract

EP-0018 collapses event registration to one public `reg-event` form and moves
application full-context work to interceptors. That makes anonymous inline
interceptor maps too important to remain anonymous: they affect the event
context, can short-circuit execution, can add or remove effects, and are the
mechanism users reach for when a two-argument event handler is not enough.

This EP proposes:

- a new registrar kind, `:interceptor`;
- one public authoring form, `reg-interceptor`;
- event and frame `:interceptors` vectors containing only interceptor
  references;
- exact-reference `:interceptor-overrides` for tests, stories, SSR, and tools;
- frame-level `:interceptors` as the "global within this frame" mechanism;
- one framework-standard interceptor, `[:rf.interceptor/path <path-vector>]`,
  implemented as the canonical standard `:factory` consumer and specified to
  preserve the frame commit `identical?` no-op optimization;
- no standard `unwrap` interceptor: payload extraction stays ordinary handler
  destructuring, and projects that want chain-wide event reshaping can register
  a project interceptor.

The result is one public shape: interceptor behavior is named at registration
time and applied by reference.

## Motivation

### EP-0018 makes interceptors load-bearing

EP-0018 removes `reg-event-db`, makes `reg-event` the single public event
registration form, and demotes `reg-event-ctx` to framework-internal machinery.
That is the right simplification, but it makes one consequence explicit:
application full-context work belongs to interceptors.

An interceptor is not decoration. It can rewrite coeffects, replace the event,
skip the handler, add effects, remove effects, rewrite `:db`, redact trace
payloads, and change the error surface. If that behavior is part of the
program, it should have the same properties as the rest of the program:

- an id;
- source coordinates;
- metadata;
- app-value representation;
- hot-reload behavior;
- trace/Xray visibility;
- test/story override semantics.

Inline interceptor values do not provide those properties reliably.

### The current override model is already trying to name interceptors

Spec 002 already has `:interceptor-overrides`:

```clojure
(rf/reg-frame :test/cart
  {:interceptor-overrides {:my-app/analytics nil}})
```

That model assumes an interceptor can be found by a stable name in the
assembled chain. It works only when the inline value happens to carry a stable
`:id`; anonymous values cannot be overridden cleanly, and value-valued
replacement does not serialize across stories, SSR artifacts, app values, or
agent-visible tooling.

The system is already behaving as if interceptors are named image members.
This EP makes that true.

### App-as-value needs named image members

EP-0013 turns applications into values that can be constructed, installed,
queried, patched, tested, and explained. Events, subs, effects, coeffects,
views, frames, routes, resources, mutations, flows, and machines all have
registration identities. Interceptors are the remaining important anonymous
program behavior.

An event descriptor like this is weak:

```clojure
{:interceptors [#object[...]]}
```

The same descriptor as data is much stronger:

```clojure
{:interceptors [:auth/required [:rf.interceptor/path [:cart]]]}
```

The second form can be printed, read, diffed, patched, inspected by Xray, moved
through a story, used in an app value, and overridden by an exact reference.

### `path` is framework behavior, not just convenience

The `path` interceptor is the one old standard interceptor that still earns a
framework home. It focuses an event handler on an app-db sub-slice, then
re-widens the returned slice back into full app-db. That is not just syntactic
sugar; it is coupled to the framework's commit semantics.

The frame commit boundary has an `identical?` no-op optimization: when an event
returns the same app-db object, the runtime skips the container write. A naive
`path` implementation can defeat that optimization. If the focused slice is
unchanged, `(assoc-in original-db path unchanged-slice)` still allocates a new
top-level map; the `identical?` check then fails even though the handler did no
real work.

The standard `:rf.interceptor/path` implementation can preserve the no-op
contract because it knows both facts:

- the original full app-db object;
- the original focused slice object.

If the handler emits a `:db` effect whose focused slice is `identical?` to the
original focused slice, the interceptor widens to the original full app-db
object, not to an `assoc-in` allocation. That keeps the EP-0018
no-op semantics intact for the most common focused-handler pattern.

This is the strongest reason `path` belongs in the framework: app-vendored
copies are likely to preserve value equality but miss the identity fast path.

### `unwrap` is not in the same class

The old `unwrap-interceptor` rewrites the event coeffect from:

```clojure
[:cart/add {:sku sku :qty qty}]
```

to:

```clojure
{:sku sku :qty qty}
```

That can be convenient for the handler, but the same local ergonomics are
available through ordinary destructuring:

```clojure
(rf/reg-event :cart/add
  (fn [{:keys [db]} [_ {:keys [sku qty]}]]
    {:db (add-cart-line db sku qty)}))
```

`unwrap` has only one extra power: it rewrites `:event` for the entire
interceptor chain. That power is not a general framework need, and it makes the
event fact less stable. The original event vector is the causal input; tools,
trace, replay, diagnostics, and other interceptors should not have to wonder
whether `:event` is still the dispatched vector or has become a payload map.

Projects that want chain-wide event reshaping can still register it:

```clojure
(rf/reg-interceptor :app/unwrap
  {:doc "Project-local chain-wide payload-map event reshaping."}
  {:before app.interceptors/unwrap-before
   :after  app.interceptors/unwrap-after})
```

It does not need to be a framework-standard interceptor.

## Goals / Non-Goals

### Goals

- Add `:interceptor` as a first-class registrar kind.
- Add `reg-interceptor` as the public registration surface for
  application-authored interceptors.
- Make event and frame `:interceptors` chains use interceptor references, not
  inline interceptor maps or Vars.
- Keep frame-level `:interceptors` as the "global within this frame" mechanism.
- Keep `:interceptor-overrides`, but make it exact-reference based and
  serializable.
- Define the effective chain ordering across frame refs, event refs, the
  framework handler wrapper, and framework-owned dispatch-time interceptors.
- Define validation timing for unknown refs and malformed refs.
- Keep `path` as the one framework-standard interceptor, referenced as
  `[:rf.interceptor/path <path-vector>]`.
- Make `:rf.interceptor/path` the canonical standard `:factory` consumer.
- Specify the `path` no-op rule so focused handlers preserve `identical?`
  app-db no-op commits.
- Make registered interceptors visible through `registrations`,
  `handler-meta`, app values, modules, Xray, stories, tests, and agents.
- Preserve the interceptor execution model: `:before` runs before the handler;
  `:after` runs after the handler in reverse order.
- Keep the v1 -> v2 migration mechanical where the old intent is clear.

### Non-Goals

- No realm patching API.
- No realm-level interceptor defaults in this EP.
- No process-global interceptor mechanism.
- No inline exception for tiny local interceptors.
- No public `->interceptor` application-authoring form.
- No standard `unwrap` interceptor.
- No broad standard interceptor library. Old helpers such as `debug`,
  `trim-v`, `on-changes`, `enrich`, `after`, `inject-cofx`, and
  `validate-at-boundary-interceptor` do not remain as public standard
  interceptors.
- No change to the event context shape, effects map, coeffects map, or
  interceptor execution algorithm.
- No attempt to statically prove which events an interceptor can affect.

## Relationships

- **[EP-0018](EP-0018-one-event-registration.md)** - this EP sequences behind
  EP-0018. EP-0018 makes interceptors the public application full-context
  mechanism by removing `reg-event-ctx` from the public authoring surface. This
  EP amends the inline interceptor examples in EP-0018: those become
  `reg-interceptor` plus refs in event/frame metadata.
- **[Spec 001](../../spec/001-Registration.md)** - gains the `:interceptor`
  registrar kind, `reg-interceptor`, source-coordinate capture, metadata, and
  `handler-meta :interceptor`.
- **[Spec 002](../../spec/002-Frames.md)** - event/frame `:interceptors`
  grammar, frame-level chain behavior, dispatch option restrictions,
  `:interceptor-overrides`, effective chain ordering, and standard path
  behavior graduate here.
- **[Conventions.md](../../spec/Conventions.md)** - reserves the
  `:rf.interceptor/*` id namespace for framework-owned standard interceptor
  refs. The initial reserved member is `:rf.interceptor/path`.
- **[EP-0012](EP-0012-path-optics-and-canonical-forms.md)** - parameterized
  interceptor refs use one EDN/CEDN-1 argument. Exact-reference override
  matching depends on canonical argument identity.
- **[EP-0013](EP-0013-app-values-and-runtime-realms.md)** - app/module values
  can carry `:reg-interceptor` descriptors, and event/frame descriptors carry
  interceptor refs. Realm-aware lookup is not specified here, but the ref model
  composes with per-realm registrars.
- **[EP-0017](EP-0017-recordable-coeffects.md)** - `inject-cofx` does not
  return as an interceptor. Recordable facts are declared by
  `:rf.cofx/requires` and delivered through the coeffect model.
- **[Spec 009](../../spec/009-Instrumentation.md)** - trace and error
  projection should report authored refs, resolved refs, override
  substitutions, and registration/validation failures.
- **[Migration guide](../../migration/from-re-frame-v1/README.md)** - M-17,
  M-21, M-70, and the EP-0018 event-registration migration need updates so old
  interceptor values rewrite to registered refs.

## Specification

### 1. New registrar kind: `:interceptor`

The registrar gains a new kind:

```clojure
:interceptor
```

An interceptor registration is keyed by a qualified keyword id and stores an
interceptor descriptor plus normal registration metadata.

The id lives in the same registry namespace discipline as other `reg-*` ids:
application ids are application-owned; framework ids under `:rf.interceptor/*`
are reserved.

### 2. `reg-interceptor`

The public authoring form is:

```clojure
(reg-interceptor id ?metadata descriptor)
```

Example:

```clojure
(rf/reg-interceptor :audit/record-event
  {:doc "Append each event id to the audit trail."}
  {:before
   (fn [ctx]
     (update-in ctx [:coeffects :db :audit/events]
                (fnil conj [])
                (first (rf/get-coeffect ctx :event))))})
```

`metadata` is the normal registration metadata map: `:doc`, `:schema`, `:tags`,
`:platforms`, source coordinates, and future common metadata keys.

`descriptor` is one of:

```clojure
{:before before-fn}
{:after after-fn}
{:before before-fn :after after-fn}
{:factory factory-fn}
```

The first three forms define a static interceptor. The `:factory` form defines
a parameterized interceptor family. A factory receives one argument and returns
a static descriptor or executable interceptor implementation for that argument.

The factory mechanism is not speculative. The framework's standard
`:rf.interceptor/path` is the canonical factory consumer:

```clojure
[:rf.interceptor/path [:cart :items]]
```

Registration is the invariant. Application ownership is not. A
framework-registered interceptor satisfies "if it is in the program, it is
registered" just as an application-registered interceptor does.

For migration, the descriptor MAY also be an existing interceptor value
carrying implementation-private slots. If it carries an `:id`, that id MUST
match the positional registration id:

```clojure
(rf/reg-interceptor :legacy.audit/record
  {:doc "Wrapped legacy audit interceptor."}
  legacy.audit/record-interceptor)
```

This compatibility is at the registration boundary only. Public chains still
carry refs, never inline interceptor values.

A programmatic `reg-interceptor*` MAY exist for tooling/REPL use without macro
source-coordinate capture, following the existing `*` suffix convention.

### 3. Interceptor references

An interceptor reference is one of:

```clojure
:auth/required
[:rf.interceptor/path [:cart]]
```

A bare keyword references a static registered interceptor. A two-element vector
references a parameterized interceptor factory:

```clojure
[interceptor-id arg]
```

Parameterized references take exactly one argument. If a factory needs multiple
inputs, the argument is a vector or map:

```clojure
[:rf.interceptor/path [:cart :items]]
[:app/role {:role :admin :redirect [:login/show]}]
```

The `arg` MUST be EDN-serializable when the reference appears in an app value,
frame config, story, replay fixture, SSR artifact, or other serialized
program-description surface. Exact-reference matching uses the EP-0012 /
CEDN-1 canonical form.

### 4. Event and frame chain grammar

The public `:interceptors` surfaces are event metadata and frame metadata. Both
accept a vector of interceptor references:

```clojure
(rf/reg-event :cart/add
  {:interceptors [[:rf.interceptor/path [:cart]]
                  :auth/required]}
  (fn [{:keys [db]} [_ sku]]
    {:db (update db :items conj sku)}))

(rf/reg-frame :story/cart
  {:interceptors [:story/record-events]})
```

Inline interceptor maps or Vars are registration errors:

```clojure
:rf.error/inline-interceptor-removed
```

The recovery is to register the interceptor and reference it by id.

Dispatch opts do not accept additive `:interceptors` under this EP.
Per-dispatch variation stays available through `:interceptor-overrides`, which
substitutes or removes named refs. That keeps one-off anonymous behavior out of
the dispatch call site while preserving the story/test need to disable or swap
behavior.

### 5. `:interceptor-overrides`

`:interceptor-overrides` remains the mechanism for replacing or removing
interceptors in a chain, but the map becomes reference-based:

```clojure
{:interceptor-overrides
 {:auth/required :story/skip-auth
  :audit/record-event nil}}
```

Keys are interceptor references. Values are:

- another interceptor reference, to replace the matched interceptor;
- `nil`, to remove the matched interceptor.

Value-valued overrides are retired from public surfaces. This keeps SSR, story,
test, and tool override state serializable and inspectable.

Override matching is by canonical interceptor reference, not just by id. A bare
keyword matches that keyword. A parameterized reference matches the full
`[id arg]` vector. This avoids the ambiguous case where one chain has multiple
instances of the same factory:

```clojure
{:interceptors [[:rf.interceptor/path [:cart]]
                [:rf.interceptor/path [:cart :items]]
                :auth/required]
 :interceptor-overrides
 {[:rf.interceptor/path [:cart]] nil
  :auth/required :story/skip-auth}}
```

The override removes only the exact `[:rf.interceptor/path [:cart]]` reference.

Precedence remains:

```text
frame overrides < dispatch opts overrides
```

Per-call overrides win over per-frame overrides.

### 6. Effective chain ordering

The effective chain for one dispatch is assembled in this order:

```text
1. frame metadata :interceptors refs
2. event metadata :interceptors refs
3. the framework event-handler wrapper
4. framework dispatch-time interceptors owned by their specs
```

The first two groups are authored references and resolve through the same
registrar that resolved the event handler. Today that is the default registrar.
When realm-aware live dispatch lands, it is the owning frame's realm registrar.
This EP records the lookup direction without adding a realm patching API.

After refs resolve, the runtime applies the merged override map:

```text
frame :interceptor-overrides < dispatch opts :interceptor-overrides
```

A replacement ref is resolved through the same registrar before execution. A
`nil` replacement removes the matching ref from the chain.

Framework dispatch-time interceptors that are not authored image members
remain governed by their owning specs. For example, flow transformation can
still wrap after the authored chain in the position required by Spec 013. This
EP changes authored interceptor naming, not subsystem-owned dispatch
machinery.

### 7. Validation and resolution timing

Event and frame metadata store interceptor references, not resolved interceptor
maps. The runtime resolves references when assembling the dispatch chain.

Live `reg-event` and `reg-frame` calls fail when they reference an unknown
interceptor:

```clojure
(rf/reg-event :cart/add
  {:interceptors [:auth/required]}
  cart-add)
;; throws :rf.error/unregistered-interceptor if :auth/required is absent
```

App values validate at construction or install, whichever already owns
cross-kind validation for the constructed program. A dispatch-time unknown-ref
failure should exist only as a defensive guard against corrupt state or a
hot-reload race.

Resolving at dispatch time preserves hot reload:

```clojure
(rf/reg-interceptor :auth/required ...v1...)
(rf/reg-event :cart/add {:interceptors [:auth/required]} cart-add)

;; hot reload
(rf/reg-interceptor :auth/required ...v2...)
```

The next dispatch of `:cart/add` uses the current `:auth/required`
registration. The event does not have to be re-registered just because an
interceptor implementation changed.

Implementations may cache resolved chains, but cache invalidation MUST observe
interceptor re-registration, event re-registration, frame re-registration, and
per-call override changes.

### 8. Standard `path`

v2 keeps exactly one standard interceptor:

```clojure
[:rf.interceptor/path path-vector]
```

`path-vector` is an EDN vector naming a concrete app-db path:

```clojure
(rf/reg-event :cart/add
  {:interceptors [[:rf.interceptor/path [:cart]]]}
  (fn [{:keys [db]} [_ sku]]
    {:db (update db :items conj sku)}))
```

The standard path interceptor:

1. records the original full app-db object and the original focused slice;
2. stages the focused slice as the handler's `:db` coeffect;
3. if the handler emits no `:db` effect, emits no synthetic `:db` effect;
4. if the handler emits a `:db` effect whose focused value is `identical?` to
   the original focused slice, rewrites the effect back to the original full
   app-db object;
5. otherwise widens the focused value into the original app-db at `path-vector`.

The fourth rule is normative. It preserves the frame commit no-op
optimization: a path-focused handler that returns its slice unchanged should
not force a new top-level app-db object solely because the interceptor widened
the slice.

There is no public `rf/path` value constructor. The public chain language stays
uniform: keywords and `[id arg]` refs.

### 9. No standard `unwrap`

The old `unwrap-interceptor` does not remain standard. The recommended shape is
ordinary handler destructuring:

```clojure
(rf/reg-event :cart/add
  (fn [{:keys [db]} [_ {:keys [sku qty]}]]
    {:db (add-cart-line db sku qty)}))
```

This keeps the `:event` coeffect stable as the original dispatched event vector
throughout the chain. Stability matters for tracing, replay, diagnostics, and
other interceptors.

Projects that need chain-wide event payload rewriting can register a
project-owned interceptor:

```clojure
(rf/reg-interceptor :app/unwrap
  {:doc "Project-local payload-map unwrapping."}
  {:before app.unwrap/before
   :after  app.unwrap/after})
```

### 10. `->interceptor`

`->interceptor` is no longer the application authoring surface. The public
replacement is `reg-interceptor`.

Before:

```clojure
(def auth-guard
  (rf/->interceptor
    :id :auth/required
    :before require-auth))

(rf/reg-event :cart/add
  {:interceptors [auth-guard]}
  cart-add)
```

After:

```clojure
(rf/reg-interceptor :auth/required
  {:doc "Require a logged-in user."}
  {:before require-auth})

(rf/reg-event :cart/add
  {:interceptors [:auth/required]}
  cart-add)
```

The implementation may retain an internal interceptor constructor for lowering
descriptors into executable chain entries. That constructor is not a public
app-authoring API and must not be accepted in public event/frame chains.

### 11. App values and modules

An app value may contain interceptor descriptors. A module can therefore own
interceptors exactly as it owns events and subs:

```clojure
(defmodule cart
  {:rf.module/owns
   {:app-db [[:cart]]}

   :reg-interceptor
   [[:cart/auth-required
     {:doc "Cart auth gate."}
     {:before require-cart-auth}]]

   :reg-event
   [[:cart/add
     {:interceptors [:cart/auth-required
                     [:rf.interceptor/path [:cart]]]}
     cart-add]]})
```

This keeps the app-as-value rule intact: the program contains named members,
not anonymous runtime objects embedded in other members.

### 12. Tooling and metadata

`handler-meta` for an event exposes the authored interceptor refs:

```clojure
(rf/handler-meta :event :cart/add)
;; => {:interceptors [:auth/required [:rf.interceptor/path [:cart]]] ...}
```

`handler-meta` for an interceptor exposes its metadata and source coordinate:

```clojure
(rf/handler-meta :interceptor :auth/required)
;; => {:doc "Require a logged-in user."
;;     :ns ...
;;     :line ...
;;     :file ...}
```

Trace/Xray surfaces should distinguish:

- authored refs;
- resolved executable chain;
- per-frame override substitutions;
- per-call override substitutions;
- removed refs;
- missing-ref failures.

### 13. Error model

The implementation should use structured errors at these sites:

| Error | Meaning |
|---|---|
| `:rf.error/invalid-interceptor` | `reg-interceptor` received a malformed descriptor. |
| `:rf.error/unregistered-interceptor` | A chain references an id not present in the registrar. |
| `:rf.error/invalid-interceptor-ref` | A chain entry is neither a keyword id nor `[id arg]`. |
| `:rf.error/inline-interceptor-removed` | A public chain contains an interceptor map/value/Var. |
| `:rf.error/interceptor-override-invalid` | An override map contains a malformed key or replacement. |
| `:rf.error/interceptor-factory-arity` | A parameterized ref targets a non-factory interceptor or a factory cannot build for the arg. |
| `:rf.error/path-interceptor-bad-path` | `:rf.interceptor/path` received a non-vector or malformed path argument. |

## Rationale

### Why inline handlers are okay but inline interceptors are not

EP-0018 keeps inline event handlers:

```clojure
(rf/reg-event :cart/add
  (fn [{:keys [db]} [_ sku]]
    {:db (update db :items conj sku)}))
```

That handler is not anonymous in the program. Its event id, `:cart/add`, is its
home. It is queried, overridden, traced, hot-reloaded, and documented through
that event registration.

An inline interceptor is different:

```clojure
{:interceptors [(rf/->interceptor :before f)]}
```

It has no independent program address. If it needs to be tested, patched,
overridden, inspected, or explained, tools must spelunk a value embedded inside
another value. That is the last unnamed behavior member after EP-0018. This EP
removes it.

### Why go all-in instead of allowing both refs and values

A mixed model looks ergonomic at first:

```clojure
{:interceptors [:auth/required
                (rf/->interceptor :id :local/audit :before audit)]}
```

But then every tool, app value, story, hot-reload path, migration worker, and
override surface has to support two shapes. Worse, the terse shape is the one
that fails under the most important use cases.

The project has repeatedly preferred one explicit primitive over parallel
conveniences when the behavior is real program structure. Interceptors should
follow that rule.

### Why frame-level global policy

Registration is naming, not application. A registered interceptor does nothing
until event or frame metadata references it.

Frame-level `:interceptors` remains the canonical "global within this frame"
mechanism:

```clojure
(rf/reg-frame :dev/main
  {:interceptors [:dev/record-events]})
```

Single-frame apps recover the old global feel by putting the interceptor on the
app frame. Multi-frame apps stay explicit.

### Why references rather than Vars

Vars are not portable across CLJS advanced compilation, SSR artifacts, app
values, trace data, or tool protocols. Qualified keyword ids are already the
language of the registrar.

### Why `path` but not `unwrap`

`path` is a data-focus primitive tied to app-db commit behavior. It has a real
framework invariant to preserve: path-focused no-op handlers should stay
no-op commits. It also justifies the `:factory` mechanism with a framework
consumer.

`unwrap` is mostly handler ergonomics. The event vector is already easy to
destructure locally, and keeping `:event` stable is more valuable than
framework-standardizing chain-wide event rewriting.

### Why exact-reference overrides

Parameterized interceptors can appear multiple times in one chain:

```clojure
[:rf.interceptor/path [:cart]]
[:rf.interceptor/path [:cart :items]]
```

An id-only override cannot say which one it means. Exact-reference matching is
slightly more verbose but precise, serializable, and stable under CEDN-1.

## Backwards Compatibility

re-frame2 is pre-alpha, so this EP is allowed to remove public shapes. The
compatibility goal is not "keep every old form working"; it is "make the v1 ->
v2 rewrite mechanical where intent is clear, and loud where a human decision is
required."

### Migration order

Recommended v1 -> v2 ordering:

1. Apply the existing forced compile-gate removals and dependency changes.
2. Convert event registrations per EP-0018: `reg-event-db` /
   `reg-event-fx` -> `reg-event`.
3. Convert interceptor definitions to `reg-interceptor`.
4. Convert every event/frame/dispatch interceptor chain from values to refs.
5. Convert global interceptors to frame-level refs.
6. Run a boot smoke test; missed inline chains fail at registration.

Steps 2-4 can be one codemod pass if the tool has an AST. They are separated
here to make the intent clear.

### Event chains

Before:

```clojure
(rf/reg-event :cart/add
  [auth-guard (rf/path :cart)]
  cart-add)
```

After:

```clojure
(rf/reg-event :cart/add
  {:interceptors [:auth/required
                  [:rf.interceptor/path [:cart]]]}
  cart-add)
```

The codemod must also ensure `:auth/required` is registered:

```clojure
(rf/reg-interceptor :auth/required
  {:doc "Require auth."}
  {:before require-auth})
```

### Existing `->interceptor` definitions

Clear mechanical case:

```clojure
(def auth-guard
  (rf/->interceptor
    :id :auth/required
    :before require-auth))
```

After:

```clojure
(rf/reg-interceptor :auth/required
  {:doc "Require auth."}
  {:before require-auth})
```

Then every chain reference to `auth-guard` becomes `:auth/required`.

If the interceptor has no stable `:id`, the migration should derive a qualified
id from the Var and flag it for review:

```clojure
;; v1
(def audit
  (rf/->interceptor :before audit-before))

;; v2 candidate, flagged for name review
(rf/reg-interceptor ::audit
  {:doc "TODO migrated interceptor; review generated id."}
  {:before audit-before})
```

Inline `->interceptor` forms inside a chain are Type B: the tool can lift them
to a nearby `reg-interceptor`, but the generated id should be reviewed because
it becomes a public program id.

### Existing interceptor Vars from libraries

If a library already exports an interceptor value and has not yet shipped
`reg-interceptor`, the app can wrap it at the boundary:

```clojure
(rf/reg-interceptor :legacy.audit/record
  {:doc "Wrapped legacy audit interceptor."}
  legacy.audit/record-interceptor)

(rf/reg-event :cart/add
  {:interceptors [:legacy.audit/record]}
  cart-add)
```

This is a migration bridge. It does not keep inline interceptor values legal in
chains.

### Standard helper migration

| v1 / old v2 chain entry | EP-0022 chain entry |
|---|---|
| `(rf/path :cart :items)` | `[:rf.interceptor/path [:cart :items]]` |
| `rf/unwrap-interceptor` | handler destructuring, or project-registered `:app/unwrap` if chain-wide rewriting is intentional |
| `rf/validate-at-boundary-interceptor` | project/library schema-validation interceptor if still needed |
| `debug` | trace/listener or a project-registered debug interceptor |
| `trim-v` | handler destructuring |
| `on-changes` | `reg-flow` |
| `enrich` | `reg-flow` or explicit event logic |
| `after` | `reg-interceptor` with `:after` |
| `inject-cofx` | EP-0017 `:rf.cofx/requires` |

Migration tooling may look up old helper implementations in the re-frame v1
codebase to confirm exact semantics. Only `path` rewrites to a framework
standard ref.

### Dispatch opts additive interceptors

Spec 002's current additive dispatch-opts `:interceptors` affordance is
removed. Existing users should move stable behavior to event/frame metadata and
use `:interceptor-overrides` for per-call substitution/removal.

This is a pre-alpha cleanup: per-dispatch anonymous program behavior is exactly
the shape this EP removes.

### Migration-document updates required

On graduation, `migration/from-re-frame-v1/README.md` received a focused
rewrite:

- M-70 becomes "event interceptor chains use registered interceptor refs", not
  merely "chains live in metadata `:interceptors`".
- M-17 examples change from frame-level interceptor values to frame-level refs.
- M-21 keeps `path` semantics but rewrites it to a standard ref.
- M-21 rewrites `unwrap-interceptor` to handler destructuring or flags
  intentional chain-wide reshaping for project registration.
- The "What stays the same" list calls out this carve-out alongside EP-0018's
  event-registration collapse.

## Bead Plan / Reference Implementation

The reference implementation shipped as the action wave below. The slices are
retained as the implementation record.

### B1. Spec and API contract

- Add `:interceptor` to Spec 001's registrar-kind list.
- Replace Spec 001's current `->interceptor` public-authoring language with
  `reg-interceptor`.
- Update Spec 002 event/frame interceptor grammar, override semantics, chain
  ordering, and dispatch-opts restrictions.
- Add the standard `:rf.interceptor/path` contract to Spec 002.
- Add `:rf.interceptor/*` to Conventions.md reserved framework namespaces.
- Update API.md for `reg-interceptor`, `handler-meta :interceptor`, and the
  removal of public `rf/path`, `unwrap-interceptor`, and `->interceptor`
  authoring.

Guide impact: update the interceptor and migration guide material
(`interceptors.md` and the v1 migration guide). The new teachable
payoff is "full-context work is named and inspectable."

### B2. Registrar and router implementation

- Add the registrar kind and `reg-interceptor` macro/function pair.
- Store metadata/source coordinates consistently with other `reg-*` forms.
- Validate refs at `reg-event` / `reg-frame` registration and app-value
  install.
- Resolve refs at dispatch-chain assembly.
- Implement exact-reference overrides.
- Reject inline interceptor maps/Vars in public chains.
- Reject additive dispatch-opts `:interceptors`.

Guide impact: no separate guide change beyond B1; this is runtime support for
the documented contract.

### B3. Standard path implementation

- Register `:rf.interceptor/path` as a standard framework factory.
- Implement path argument validation.
- Preserve no-synthetic-`:db` behavior when the handler emits no `:db`.
- Preserve `identical?` no-op commit semantics when the focused slice is
  unchanged.
- Add nested path-interceptor tests.
- Add flow-ordering tests to confirm path widening still happens before the
  outer flow transform.

Guide impact: focused handler examples use `[:rf.interceptor/path path]`.

### B4. Migration, examples, and skills

- Update v1 migration rules M-17, M-21, M-70, and EP-0018 migration text.
- Update examples and docs to stop using inline interceptor values.
- Update skills to teach `reg-interceptor` and path refs.
- Make the migration skill look up v1 helper implementations where needed.

Guide impact: the `quickstart.md` and event-handler guide material should show
direct destructuring instead of `unwrap`.

### B5. Tooling and conformance

- Add conformance tests for registration, validation, ref resolution, exact
  override matching, hot reload, app-value install, and path no-op preservation.
- Update trace/Xray projections to show authored refs, resolved chains, and
  override substitutions.
- Add error-catalogue coverage for the new error ids.

Guide impact: Xray/tooling docs can explain registered interceptors as named
program facts.

## Open Issues

The design is intentionally narrow. The following were the only operator
decisions this proposal needed; all five were **ruled as recommended** at
graduation (`accepted → final`, 2026-06-15, Mike).

1. **Should the standard set be path-only?**

   Recommendation: yes. `path` is coupled to framework commit semantics and
   justifies `:factory`; `unwrap` is handler destructuring sugar and should be
   project-owned when chain-wide event rewriting is intentional.

2. **Should additive dispatch-opts `:interceptors` be removed?**

   Recommendation: yes. Event metadata and frame metadata are the two homes for
   authored behavior; dispatch opts should only substitute/remove named refs.

3. **Should inline interceptor values be rejected even for tiny local cases?**

   Recommendation: yes. A tiny interceptor can still mutate coeffects, rewrite
   effects, skip handlers, and change trace/error behavior. If it is in the
   program, register it.

4. **Should `->interceptor` remain public?**

   Recommendation: no. An internal constructor may remain, but public authoring
   should be `reg-interceptor`.

5. **Should `path` no-op preservation be normative?**

   Recommendation: yes. The framework-owned path interceptor should preserve
   `identical?` no-op commit behavior. That is the clearest reason to keep path
   standard rather than vendored.

## Recommendation

Accept EP-0022 as a standards-track amendment after EP-0018:

- interceptors become registered program facts;
- event/frame chains contain refs only;
- exact-reference overrides replace value overrides;
- frame metadata remains the frame-local global mechanism;
- `:rf.interceptor/path` is the only standard interceptor;
- `unwrap` migrates to handler destructuring or project registration.

This gives re-frame2 one spelling for full-context application behavior without
keeping the old grab-bag interceptor surface.
