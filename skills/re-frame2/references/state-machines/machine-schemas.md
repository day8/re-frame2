# Machine schemas and snapshot redaction

## When to load

Reach for this leaf when declaring a machine's optional `:schemas` map — the `[:schemas :data]` context validator or the `[:schemas :output]` completion-payload validator — or when redacting a machine's durable `:data` at snapshot egress. For the declaration grammar, guards/actions, and dispatch, see [`reg-machine.md`](reg-machine.md).

## `[:schemas :data]` — the re-frame2 analog of XState typed context

A machine's `:data` slot is its *context* in xstate terms — the value it carries across transitions. A machine spec MAY declare an optional machine-level **`:schemas`** map (EP-0029 A3); its **`[:schemas :data]`** entry is the validator for that `:data`. The map is unqualified, like `:data` / `:guards` / `:actions`:

```clojure
(def AuthData
  [:map
   [:retries :int]
   [:token   {:sensitive? true} [:maybe :string]]])

(rf/reg-machine :session/auth
  {:initial :anon
   :data    {:retries 0 :token nil}
   :schemas {:data AuthData}
   :states  {:anon           {:on {:login :authenticating}}
             :authenticating {...}
             :authed         {...}}})
```

The data-context schema lives at `[:schemas :data]` (not XState v6's `schemas.context`) because re-frame2 calls the slot `:data`, not `context` — the schema names exactly what it validates. The schema governs the user-domain `:data` only: the snapshot's `:state` is validated structurally at registration (an unknown transition target fails with `:rf.error/machine-unresolved-target`), and the reserved `:rf/*` snapshot slots are framework-owned.

`:schemas` is a **closed** sub-key map. `:data` and `:output` are the live, wired categories; `:events`, `:tags`, and `:meta` are accepted declaration-only categories (abstract values, no wired behaviour yet). An unknown sub-key — including `:input` — fails loud at registration with `:rf.error/machine-bad-schemas-key`; a non-map `:schemas` fails with `:rf.error/machine-bad-schemas`.

**Validation is optional and schema-library-agnostic.** The `[:schemas :data]` value is opaque: machine core requires neither Malli nor any other schema library. Validation runs through an optional registered validator adapter (Malli is the framework default); a project with no adapter still uses the `:schemas` grammar at zero validation cost.

**What it buys you — two things (and a third surface declares snapshot redaction):**

1. **Validation.** In dev builds (`re-frame.interop/debug-enabled?` true) the runtime validates `:data` against the schema at every macrostep-commit boundary, at bootstrap, and at spawn. A violation emits `:rf.error/schema-validation-failure` with `:where :machine-data` and rolls back the whole cascade (same lifecycle position and rollback as the `:where :app-db` check). Under `:advanced` + `goog.DEBUG=false` the validation site DCEs to a no-op — dev-only by default; for production validation at a system boundary (e.g. an SSR-hydrate restoring a machine snapshot from the wire) reach for the `:rf.schema/at-boundary` interceptor on that event.

2. **Declared context shape.** With `[:schemas :data]` present, a machine visualiser renders the context shape **authoritatively** from the declared `[:map [k type] …]` entries — the re-frame2 analog of XState's typed context (Stately's `Context:` header). Without a schema, a viz can only *infer* key→type from one sample of the initial `:data`, which a partial map can mislead.

> **The `[:schemas :data]` `:sensitive?` prop does NOT redact snapshot egress.** A `:sensitive?` / `:large?` Malli prop on a `:data` slot drives **only** the schema's own *validation-failure-trace* redaction — when a `:rf.error/schema-validation-failure` record ships, the marked slot is redacted in *that record*. It does **not** redact `:data` in the `:before` / `:after` / `:snapshot` slots of a normal transition trace. Durable machine `:data` snapshot redaction is declared on the machine definition (below).

## `[:schemas :output]` — the completion payload

The other wired `:schemas` category is **`:output`** — it validates a machine's **completion-output payload**: the value a finishing machine selects from its final state's `:data` via `:output-key` (the `result` its parent's `:spawn :on-done` receives). This is the xstate **`output`** concept (v6 `schemas.output`), but re-frame2 keeps completion *event-shaped* — there is **no** long-lived `snapshot.output` slot — so `[:schemas :output]` schemas the value *as it flows*, validated at the moment the machine reaches its final state:

```clojure
(rf/reg-machine :auth-flow
  {:initial :running
   :schemas {:output :string}                 ;; the :output-key payload must be a string
   :states  {:running {:on {:ok {:target :done
                                  :action (fn [{data :data ev :event}]
                                            {:data (assoc data :token (second ev))})}}}
             :done    {:final?     true
                       :output-key :token}}})  ;; ← this :token value is validated
```

Unlike the `:data` boundary, output validation is **best-effort fail-loud**: the machine has *already* finished when its output is checked, so a violation emits `:rf.error/schema-validation-failure` with `:where :machine-output` (and `:phase :completion`) **loudly**, but the completion still flows with **nothing to roll back** (`:rollback? false`) — a schema typo surfaces without deadlocking a finishing machine. Same dev-only posture as `:data` (`debug-enabled?`-gated, DCE'd in production) and the same optional validator adapter. A machine with no `[:schemas :output]` reports its `:output-key` payload unvalidated.

## Redacting `:data` at snapshot egress — the machine declaration

Durable machine `:data` classification travels with the **machine definition**, declared as top-level `:sensitive` / `:large` keys on the `reg-machine` spec, **projection-relative to one actor snapshot's `:data`**:

```clojure
(rf/reg-machine :session/auth
  {:sensitive [[:data :token]]        ;; redacts :token in every actor's snapshot egress
   :large     [[:data :avatar]]
   :initial   :anon
   :data      {:retries 0 :token nil}
   :schemas   {:data AuthData}        ;; still VALIDATES :data (and drives validation-FAILURE-trace redaction)
   :states    {...}})
```

The runtime **lowers** each declared path per spawned actor at spawn / first-boot — re-rooting `[:data :token]` to the instance's absolute snapshot path in the per-frame elision registry — and **drops** it on destroy (any cause). So a `:spawn`-generated `<type>#n` is classified with **zero per-instance author code**, exactly as XState carries `context` shape on the definition and applies it per actor. The marked slot renders as `:rf/redacted` (sensitive) or `:rf.size/large-elided` (large) in every `:rf.machine/transition` / `:rf.machine/snapshot-updated` egress (the `:before` / `:after` / `:snapshot` slots) before crossing the trace bus / epoch-capture / AI-MCP boundary. A malformed declaration is rejected fail-loud with `:rf.error/invalid-machine-classification`.

The `:sensitive` / `:large` declaration is a **top-level key on the machine spec map** — projection-relative, value-independent, per-instance; there is no framework-wide handler-metadata `:sensitive?` annotation stamping a whole cascade. Classification is **path-based, owner-declared, and fail-open** — an undeclared slot ships raw, with no propagation. The full model (fail-open, no-taint, the three-owner table) is [`../cross-cutting/privacy-and-elision.md`](../cross-cutting/privacy-and-elision.md).

A machine with **no** `[:schemas :data]` schema is unchanged: its `:data` is free-form and unvalidated, and a viz infers (and badges as inferred) its context shape.

See Spec 005 §Schema validation and §`[:schemas :data]` is the re-frame2 analog of XState typed context for the full contract.

---

*Derived from the `re-frame.machines.*` sub-namespaces (`transition`, `lifecycle-fx.registration` / `validation`) and `spec/005-StateMachines.md` (EP-0029) @ main `89bd9c3`. Citations are symbol-level; re-verify after machine-schema or classification changes.*
