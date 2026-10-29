# API cheatsheet

One-line signatures for the public `re-frame.core` surface and the owned-namespace surfaces an app reaches for. **This page is a glance, not a manual**: each row is the call shape plus the one fact that bites, and the linked leaf owns the contract. Full docstrings: `SKILL-REDIRECT.md` → *Definitive API reference*. Aliases: `rf` is `[re-frame.core :as rf]`; `ts` is `[re-frame.test-support :as ts]`.

## Registration

| Surface | Shape |
|---|---|
| `rf/reg-event` | `(id meta? (fn [cofx ev] effects-map-or-nil))` — the **one** event form; `{:db ...}` is the db write. Full-context work is a registered interceptor referenced by id in `meta?`'s `:interceptors` ([events](../fundamentals/events.md)) |
| `rf/reg-fx` | `(id [metadata?] (fn [ctx args] ...))` — `ctx` is `{:frame :event}`; `args` is the `:fx` entry's 2nd slot ([fx](../fundamentals/fx.md)) |
| `rf/reg-cofx` | `(id [metadata?] (fn [] value) \| (fn [arg] value))` — returns the value; consumed via `:rf.cofx/requires`, not `inject-cofx` ([cofx](../fundamentals/cofx.md)) |
| `rf/reg-sub` | `(id meta? (fn [db query-v] value))` — inputs are DECLARED under `meta?`'s `:inputs` (a literal `[[:q…] …]` or a producer `(fn [query-v] [[:q…] …])` returning query vectors) and arrive as a vector: `(fn [[a b] query-v] value)` ([subs](../fundamentals/subs.md)) |
| `rf/reg-view` / `rf/reg-view*` | `(sym [args] body)` — defn-shape, injects `dispatch` / `subscribe` · runtime form `(id metadata? render-fn)` ([views](../fundamentals/views.md)) |
| `rf/reg-state` | `(concern)` / `(concern {:default v})` — a sub `[concern ikey]` and a setter event `[concern ikey v]` over `[:ui concern ikey]`; clear with `[:rf.state/clear concern ikey]` ([reusable-components](../../patterns/reusable-components.md#per-instance-view-state--rfreg-state)) |
| `rf/reg-app-schema` / `rf/reg-app-schemas` | `(path schema)` / `(path {:doc "…"} schema)` · bulk `(path->schema)` / `(path->schema opts)` → the paths registered. Names no frame; a **dev-build assertion**, never checked in production ([schemas](../fundamentals/schemas.md)) |
| `rf/reg-machine` | `(id metadata? machine-spec)` — the plain-fn form is `re-frame.machines/reg-machine*`, not on `rf/` ([reg-machine](../state-machines/reg-machine.md)) |
| `rf/reg-flow` | `(flow-id metadata derive-fn)` — `:inputs` / `:output-path` required; a frame runs it when its `:flows` names it, e.g. `(rf/make-frame {:id :rf/default :flows [:cart/total]})` ([flows](../fundamentals/flows.md)) |
| `rf/reg-route` | `(id metadata-map path)` ([routing](../tooling/routing.md)) |
| `rf/reg-error-projector` | `(id metadata? (fn [trace-event] public-error))` — `nil` defers to the default projector ([ssr-authoring](ssr-authoring.md)) |
| `rf/reg-http-interceptor` | `(id interceptor-map)` — runs only for a frame naming it in `:http-interceptors` `[id …]` (`:before` in order, `:after` reversed), resolved per request; an unregistered id fails that request (`:rf.error/unregistered-interceptor`) ([managed-http](../../patterns/managed-http.md)) |

There is no positional interceptor vector — `(reg-event :id [i1 i2] handler)` and `(reg-event :id {:doc ...} [i1 i2] handler)` are registration errors — and no `reg-event-db` / `reg-event-fx` / `reg-event-ctx`.

## Dispatch, subscribe, frames ([frames](../fundamentals/frames.md))

| Surface | Shape |
|---|---|
| `rf/dispatch` / `rf/dispatch-sync` | `(event)` / `(event opts)` — queued / drained to fixed point. `opts` may carry `:rf.cofx`, a flat `fact-name → value` map of recordable facts (`:rf/time-ms` is stamped when omitted). There is no `:rf.world/inputs` opt |
| `rf/subscribe` / `rf/subscribe-once` | `(query-v)` / `(query-v opts)` → reaction / one-shot value; `opts` may carry `:frame`. No frame-first `(frame-id query-v)` form |
| `rf/unsubscribe` | `(r)` — hands back one share of the reaction `subscribe` returned; a reaction evicted since is a no-op |
| `rf/compute-sub` | `(query-v db)` — pure, bypasses the cache (preferred in tests) |
| `rf/with-frame` / `rf/with-new-frame` | `(frame-id body)` pins `body` to an existing frame · `([sym expr] body)` creates, owns and destroys one |
| `rf/frame-provider` / `rf/frame-root` | (CLJS) `[frame-provider {:frame ...} & children]` scopes an existing frame · `[frame-root {:id ... :images ... :initial-events ...} & children]` ensures one; each fails loud on the other's key |
| `rf/capture-frame` | `()` / `(frame-id)` → `{:frame :dispatch :dispatch-sync :subscribe}` — the carry primitive for async work |
| `rf/current-frame-id` | `()` — raises `:rf.error/no-frame-context` outside a frame scope |
| `rf/app-db-value` / `rf/frame-state-value` | `(frame-id)` → the app-db map · `{:rf.db/app … :rf.db/runtime …}` |
| `rf/make-frame` / `rf/destroy-frame!` | `(make-frame {:id … :initial-events … :images …})` — the one constructor. Re-calling it on a live `:id` with new `:images` swaps the image generation and keeps memory; a reset is destroy + make |

## Machines — `day8/re-frame2-machines` ([reg-machine](../state-machines/reg-machine.md))

| Surface | Shape |
|---|---|
| `rf/defmachine` | `(name [doc] spec)` — macro, `def`-shape, stamps per-element source; does **not** register — pair it with `(reg-machine :id name)`. A plain `(def m {…})` + `(reg-machine :id m)` captures nothing per-element |
| `[:rf/machine machine-id]` | sub → `{:state :data :tags}` |
| `[:rf.machine/has-tag? machine-id tag]` | sub → boolean |
| machine ids · one spec | `(keys (into {} (filter (fn [[_ m]] (:rf/machine? m))) (rf/registrations {:kind :event})))` · `(:rf/machine (rf/handler-meta {:kind :event :id id}))` — there is no `machines` / `machine-meta` accessor |
| `re-frame.machines/machine-transition` | `(machine snapshot event)` → `{:status :ok :snapshot snapshot' :fx fx}` / `{:status :error :error {:kind …}}` — pure, not on `rf/` |

## Images ([images](../fundamentals/images.md))

| Surface | Shape |
|---|---|
| `rf/image` | `({:id … :select-ns {:include [<ns-glob> …] :exclude [<ns-glob> …]} :registrations {…}})` → an inert image value, supplied through a frame's `:images`; the later image wins. No `:include-ns` / `:exclude-ns` / `:replace` keys |
| `rf/generation-diff` | `(before after)` → `{:added :changed :removed :retained}` over two `frame-generation` reads |
| registrar-query map | `{:kind k}` reads the process store · `{:kind k :frame f}` that frame's view · plus `:id` for `handler-meta`; any other key is an error |

## Routing — `day8/re-frame2-routing` ([routing](../tooling/routing.md))

| Surface | Shape |
|---|---|
| `re-frame.routing/match-url` | `(url)` → `{:route-id :params :query :fragment ...}` or `nil` — not on `rf/` |
| `re-frame.routing/route-url` | `({:to route-id :params … :query … :fragment …})` → `"/url"` — not on `rf/` |
| `rf/route-link` | `[rf/route-link {:to :route-id :params {} :query {} :fragment "" & html-attrs} & children]` — the view at `:route/link`; one `<a href>` that dispatches `[:rf.route/navigate {:url <href>}]` on a plain click |

## HTTP — `day8/re-frame2-http` ([managed-http](../../patterns/managed-http.md))

The fx is `:rf.http/managed` (`re-frame.http.managed`). A stub transport from `re-frame.http.stub-transport`, required explicitly by tests and demos, answers it in place of the network, so decode, retry, the privacy scrub and the reply envelope still run for real. None of it is on `rf/`.

| Surface | Shape |
|---|---|
| `rf.http.stub-transport/route-transport` | `(routes)` / `(routes {:delay-ms n})` — `routes` is `{[method url] response}` or `(fn [wire-request] response)`; a response is `{:status :headers :body}` (a non-string `:body` goes out as JSON) or `{:failure {:kind :rf.http/transport}}`; an unmatched request fails as `:rf.http/transport` |
| `rf.http.stub-transport/pending-transport` | `()` → `{:transport t :exchanges a}` — holds each request until its exchange's `:respond!` / `:fail!` |
| `rf.http.stub-transport/with-transport` | `(transport thunk)` — the test scope |
| `rf.http.stub-transport/install-transport!` | `(frame transport)` — a frame's own transport (a demo backend), dropped with the frame; `nil` uninstalls |

## Resources and mutations — `day8/re-frame2-resources` ([resources](../../patterns/resources.md), [mutations](../../patterns/resources-mutations.md))

| Surface | Shape |
|---|---|
| `rf/reg-resource` | `(resource-id metadata request-fn)` — macro; `metadata` carries the REQUIRED fail-closed `:scope` |
| `rf/reg-resource-scope` | `(scope-id metadata resolve-fn)` — macro, a pure named resolver; `:inputs` `{name [:db <rf-path>]}` is REQUIRED (whole db: `{:inputs {:db [:db []]}}`) |
| `rf/reg-mutation` | `(mutation-id metadata request-fn)` — macro; `:params-schema` REQUIRED. Run one with `[:rf.mutation/execute …]` |
| `rf/resolve-resource-scope` | `(db scope-id)` — **db first**; pure, emits no trace. Resolve the departing scopes with it for `[:rf.resource/change-principal {:from [old-scope …] :cause …}]` |
| `rf.resources/resource-state` · `rf.resources/mutation-state` | `({:resource :scope :params :frame})` · `({:instance :frame})` → the durable entry / row `{:status :result :error …}`, or `nil`; the frame is explicit |
| enumeration · spec · live tables | `(rf/registrations {:kind :resource})` (or `{:kind :mutation}`) · `(:rf/resource (rf/handler-meta {:kind :resource :id id}))` / `(:rf/mutation (rf/handler-meta {:kind :mutation :id id}))` — no `resource-meta` / `mutation-meta` · `[:rf.db/runtime :rf.runtime/resources :entries]` / `[:rf.db/runtime :rf.runtime/mutations]` off `rf/frame-state-value` |
| `:revalidate-on` (frame config) | `#{:focus :reconnect}` — the frame lifecycle installs and removes the listeners; CLJS-only |

## Test support — `re-frame.test-support` ([testing](testing.md))

| Surface | Shape |
|---|---|
| `ts/make-reset-runtime-fixture` | `(opts?)` → fixture for `(use-fixtures :each ...)` |
| `ts/snapshot-registrar` / `ts/restore-registrar!` | low-level bracket; compose in `try` / `finally` |
| `ts/assert-path-equals` | `(path expected-val)` / `(path expected-val opts)`; whole db → `(is (= expected-db (rf/app-db-value f)))` |
| `ts/poll-until` | `(pred)` / `(pred opts)` — `:timeout-ms` (2000) `:interval-ms` (5) `:label`; JVM throws, CLJS rejects on timeout |
| `ts/with-trace-recorder!` / `ts/with-emit-recorder!` | macros `([recs opts?] body+)` — capture trace events / raw `:rf.error/*` records (or `:stream :events`) for the body |
| `ts/with-fx-overrides` | `(overrides-map & body)` — macro; per-call opt > lexical > per-frame |
| fire N events in order | `(doseq [ev evs] (rf/dispatch-sync ev))` |

## View tests — `re-frame.test-helpers` ([testing-views](testing-views.md))

Aliased `:as th` — not `h`, which is Fresco.

| Surface | Shape |
|---|---|
| `th/find-by-testid` / `th/find-all-by-testid` / `th/find-by-testid-prefix` | `(tree testid)` → node(s) |
| `th/find-by-attr` / `th/find-all-by-attr` / `th/find-by-attr-prefix` | `(tree attr val)` → node(s) |
| `th/text-content` / `th/invoke-handler` | `(node)` → text · `(node event-key & args)` calls the handler |
| `th/testid` | `(testid id)` / `(testid id attrs)` — the attrs fragment for a view |

## SSR — `day8/re-frame2-ssr` ([ssr-authoring](ssr-authoring.md))

`ssr/` is `[re-frame.ssr :as ssr]`. Only the registrars `rf/reg-head` and `rf/reg-error-projector` are on `rf/`.

| Surface | Shape |
|---|---|
| `re-frame.ssr.ring/ssr-handler` / `ssr-middleware` | `({:frame … :root-view … :payload […]})` → a Ring handler · Ring middleware — not on `rf/` ([ssr-authoring](ssr-authoring.md#wiring-the-two-halves)) |
| `ssr/hydrate!` | `({:frame f})` → the applied payload, or `nil` on a plain client load — call before the first render |
| `ssr/render-to-string` | `(tree)` / `(tree opts)` — `:doctype?`; unknown opts are ignored |
| `ssr/project-error` | `(frame-id trace-event)` → public-error map |
| `rf/reg-head` | `(id metadata? (fn [db route] head-model))` — a route names its head via `:head` |
| `ssr/head-model` / `ssr/head-model->html` | `(frame-id)` / `(frame-id {:head-id :route})` — the frame is carried, never ambient · `(head-model)` → inner-head HTML |

## Schemas — `day8/re-frame2-schemas` ([schemas](../fundamentals/schemas.md))

| Surface | Shape |
|---|---|
| `re-frame.schemas/app-schemas` / `app-schema-meta` / `app-schemas-digest` | one map each, `:frame` required — not on `rf/` |
| `re-frame.schemas/set-schema-fns!` / `schema-fns` / `default-schema-fns` | the non-Malli validator port — not on `rf/` |
| `:boundary? true` (`reg-event` metadata) | the handler's own `:schema` is checked in production too; needs a `:schema` (`:rf.error/at-boundary-missing-schema`) |

## Privacy and egress — Spec 015 ([privacy-and-elision](privacy-and-elision.md), [production-observability](production-observability.md))

Classification keys are declarations in registration, subsystem and schema maps, never calls.

| Surface | Shape |
|---|---|
| `rf/reg-classification` | `(reg-classification :auth {:sensitive [[:auth :token]] :large [[:docs :csv]] :doc "…"})` — durable app-db paths, declared beside the feature; `(clear :classification id)` removes it |
| registration `:sensitive` / `:large` | `{:sensitive [[:password]] :large [[:blob]]}` on `reg-event` / `reg-sub` / `reg-fx` / `reg-flow` — transient payload paths; `[[]]` is the whole shape |
| subsystem `:sensitive` / `:large` | `(reg-machine id {:sensitive [[:data :token]] …})` / `(reg-resource id {:sensitive [[:data :ssn]] …})` — projection-relative, applied to each instance at egress |
| schema `:sensitive?` / `:large?` | `[:token {:sensitive? true} :string]` — an HTTP `:decode` body and validation-failure traces only |
| `:rf.http/managed` `:carriers` | `(reg-fx :rf.http/managed {:carriers {:headers […] :query-params […]}} h)` — the app's HTTP carrier names |
| frame `:observability` | `{:handled-events [{:sink <id> :rf.egress/profile :rf.egress/off-box-observability}] :errors [...]}` — a closed entry map; an omitted stream inherits the `rf/configure!` default, `[]` opts out |
| `rf/project-egress` | `(record-or-value)` / `(record-or-value opts)` — the one record-level boundary primitive, required before any off-box sink. It dispatches on a record's `:kind` (`:rf.observe/handled-event` / `:rf.observe/error` / `:rf.observe/derived-tree` / `:rf/epoch-record`) and walks a kindless input as a value; an epoch record in a build that elides epoch throws `:rf.error/epoch-elided`. `opts` is closed: `:rf.egress/profile`, `:frame`, `:path`, `:query-v`, the `:rf.egress/*` overrides and the epoch-only `:rf.egress/include-fx-args?` / `:rf.egress/include-runtime-db?` / `:rf.egress/include-event-args?` |
| `rf/register-observability-sink!` / `rf/unregister-observability-sink!` | `(sink-id fn)` / `(sink-id)` — the fn receives an already-projected record |

The frame has NO `:sensitive` / `:large` key; a frame config carrying one fails loud. `:rf.egress/profile` is a closed six-member enum: `:rf.egress/off-box-observability` · `off-box-tool` · `local-redacted` (local default) · `local-raw` · `ssr-hydration` · `public-error`. **Not the API:** `add-marks` / `set-marks`, `redact-interceptor`, `declare-sensitive-header!` / `declare-sensitive-query-param!`, `:rf.egress/output-sensitivity`, `projected-record`, and `re-frame.elision/elide-wire-value` (framework-internal). `register-listener!` has no `:events` / `:errors` stream; production observation is the sink above.

## Trace and epoch — dev-only

| Surface | Shape |
|---|---|
| `rf/register-listener!` / `rf/unregister-listener!` / `rf/emit-trace-event!` | trace plumbing; the streams are `:trace` and `:epoch` |
| `rf/trace-buffer` / `rf/clear-trace-buffer!` | `(rf/trace-buffer frame-id)` / `(rf/trace-buffer frame-id opts)` — homed in `re-frame.trace.tooling`, not `re-frame.epoch` |
| `rf.epoch/epoch-history` / `rf.epoch/restore-epoch!` | `(frame-id)` → `[epoch-records]` · `(frame-id epoch-id)` → bool |
| `rf/register-listener! :epoch` | one `:rf/epoch-record` per event's run-to-completion, re-published on post-settle backfill and for `:rf.epoch/db-replaced` / halt records — reconcile by `[(:frame record) (:epoch-id record)]` and replace, don't count. `:halted-destroy` is listener-only, never retained. There is no `rf/register-epoch-listener!` |
| `rf.epoch/replace-frame-state!` | `(frame-id frame-state)` → bool — `frame-state` is partial: `{:rf.db/app v}` replaces app-db only (`{:rf.db/app {}}` resets it), `{:rf.db/runtime v}` runtime-db only, both keys the whole frame |

## Interceptors, boot, introspection

| Surface | Shape |
|---|---|
| `rf/reg-interceptor` | `(id ?metadata descriptor)` — descriptor `{:before}` / `{:after}` / `{:before :after}` / `{:factory}`; chains reference it by id |
| interceptor ctx | `(get-in ctx [:coeffects k])` / `(get-in ctx [:effects k])`, `assoc-in` to write. There is no `rf/get-coeffect` / `assoc-coeffect` / `get-effect` / `assoc-effect` |
| `:rf.cofx/requires` | `{:rf.cofx/requires [:rf/time-ms [:ui/local-theme "k"]]}` on `reg-event`; the values arrive flat: `(fn [{:keys [rf/time-ms]} ev] …)` |
| `[:rf.interceptor/path path-vector]` | the one standard interceptor ref. No `rf/path`, no standard `unwrap` (destructure, or register an `:app/unwrap`) |
| `rf/init!` | `(adapter-map)` — installs the adapter; creates no frame |
| `rf/destroy-adapter!` / `rf/current-adapter` | `(:kind (rf/current-adapter))` is the discriminator; `install-adapter!` lives on `re-frame.substrate.adapter` |
| `rf/clear` | `(rf/clear kind id)` → id — the one registrar inverse, for every registrar kind except `:frame` (an unknown kind's error names the set). It removes the registration for every frame (to stop one frame running a flow or HTTP interceptor, re-make that frame without it) and takes no opts (`:rf.error/registrar-clear-bad-request`). No per-kind names (`rf/clear-http-interceptor`, `rf/clear-resource`, …), no clear-all (tests: `re-frame.registrar/clear-kind!`). On `:resource` / `:mutation` it removes the registration, not cached data — that is the `:rf.resource/invalidate-tags` / `:rf.resource/remove` / `:rf.resource/clear-scope` fxs, and `[:rf.mutation/clear …]` |
| `rf/clear-sub-cache!` | `(frame-id?)` — evicts a frame's cached subscriptions, not a registration |
| `rf/configure!` | `({:epoch-history {...} :trace-buffer {...} :elision {...} :observability {...}})` — only the subsystems that change, e.g. `{:elision {:rf.egress/threshold-bytes N}}`; `{:observability nil}` clears the process default |
| `rf/registrations` / `rf/handler-meta` | registrar reads; ids via `(-> (registrations …) keys set)` — there is no `rf/handler-ids` |
| `rf/features` | `()` → `{feature {:maven :require :spec :loaded?}}` for `:schemas` `:machines` `:routing` `:flows` `:http` `:ssr` `:resources`. Check with `(get-in (rf/features) [:routing :loaded?])`; a boot guard is `(when-not (get-in (rf/features) [:routing :loaded?]) (throw (ex-info "…" (get (rf/features) :routing))))`, never an elidable `assert` |
| `rf/frame-ids` / `rf/view` / `rf/frame-meta` | registry reads; `frame-meta` → a flat map (`:id`, the preset expansion, `:observability`, `:created-at` `:destroyed?` `:listeners`) with no `:on-error` slot |
| `re-frame.subs.tooling/sub-cache-snapshot` / `sub-topology` | dynamic / static sub-graph reads — not on `rf/` |

An optional-artefact surface raises `:rf.error/<artefact>-artefact-missing` on a registration or write, and degrades to `nil` / `[]` / `false` on a read, when its artefact is absent.

---

*Derived from `implementation/core/src/re_frame/core.cljc` and the artefact trees under `implementation/`, cross-checked against `spec/api-manifest.edn`. The public-surface glance, not an export inventory.*
