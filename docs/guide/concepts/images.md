# Images: which registrations a frame runs

You've registered a dozen events and subscriptions and your app works — and you've never had to think about *which* of them a given frame can see. That's the default path, and it's deliberately invisible. This page is for the day it stops being enough: two examples on one page that both want a `:counter/inc` event, an inspection tool mounted beside the app it inspects, a test that needs a fake HTTP effect instead of the real one, a docs page showing four versions of the same counter. Each of those is the same question — *which registrations does this frame resolve against?* — and the answer is the **image**.

> **Coming from Redux?** An image is the set of reducers/selectors a store runs, lifted into a value you can name and compose — `combineReducers` if it returned data instead of a function, and could be assembled per-store. The divergence: registration *names* are scoped to the image, so two stores can each define `:cart/add` meaning different things without a global collision.

If you quote one sentence from this page, quote this one:

> **An image is which registrations are loaded; a frame is the live run that resolves against them.**

## The model in one line

```text
image  →  frame  →  event stream
```

Read left to right. An **image** selects a set of registrations. A **frame** is the isolated execution context that runs one resolved image — its app-db, its queue, its subscription cache, all the live memory ([Frames](frames.md) is the full tour). The **event stream** is the ordered sequence of events that frame processes over its lifetime. The image says what the frame *can* do; the frame accumulates what *has* happened; the events are the program.

That split is the whole point. Two frames running the same image share behaviour but not state — the same handlers, two independent app-dbs. Two frames running *different* images can reuse the same registration ids for different meanings, because each id is scoped to its image, not to one global registry.

## You already use an image — the default one

Here is ordinary re-frame2. No image in sight:

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event]
    {:db (update db :count (fnil inc 0))}))

(rf/reg-sub :counter/value
  (fn [db _] (:count db 0)))
```

Those `reg-*` forms don't run anything. They write entries into a **registration source store** — a record of every registration, tagged with the namespace it was authored in. When a frame is created without naming an image, the runtime projects *the whole source store* into one sealed set and hands it to the frame. That projection is the **default image**: the implicit selector over everything you've registered.

```text
reg-*  writes the source store
default image  =  the implicit selector over that source
frame creation  resolves the selector into one sealed image
```

So the common case stays boring. You never name an image, additions show up as you register them, and hot reload keeps working — the runtime just re-projects the source store into a fresh sealed image and swaps it under the live frame, preserving the frame's memory. You meet the image concept explicitly only when the default — *everything that's loaded, ids assumed globally unique* — stops being the boundary you want.

> **Heads-up.** "Default image" is a runtime projection, not a value you author. Don't reach for `rf/image` to get the default behaviour — plain `reg-*` already gives it to you. In code, the default is *the shape of a frame created with no `:images` key* — `(rf/make-frame {})` — not a value you can pass around. (One implementation note worth knowing today: the omit→default-image *projection* — where `(make-frame {})` resolves a sealed default generation — is still being wired in the reference implementation, `rf2-59orj0`. Until it lands, a frame created without `:images` runs as an ordinary configured frame resolving against the shared registrar, which is the same observable behaviour. You don't need to track this; it changes nothing you write.)

## The default image fails loud on a collision

The default image works only while ids are globally unique across everything loaded. The moment two loaded namespaces register the same `(kind, id)` with different implementations — two surfaces that both define `:counter/inc`, say — the default image **fails to assemble**, with an error that names the colliding kind/id and the two source namespaces.

This is a deliberate hardening over the old last-write-wins registrar, where the second registration silently clobbered the first and you found out weeks later when the wrong handler ran. The source store keeps *both* descriptors; assembly refuses to guess which one wins. Your three options are all explicit: rename one id, narrow the selector with explicit images so each frame sees only one, or declare an exact replacement winner. Silence is the one thing it won't do.

## Naming an image: `rf/image`

When you do need to be explicit, `rf/image` builds an image value. It's pure data — no registrar, no side effect — and you hand it to a frame through `:images`:

```clojure
(def counter-image
  (rf/image {:select-ns {:include ["docs.quickstart.counter.basic"]}}))

(rf/make-frame {:id :counter/main
                :images [counter-image]})
```

An `rf/image` value carries **exactly three** public source keys, and nothing else:

| Key | Required? | What it does |
|---|---|---|
| `:id` | optional | The image's stable id. Used in diagnostics and, crucially, in the shadow report (overrides are named *by image id*). Image ids must be unique *within one `:images` composition*. Anonymous images are fine for local tests and one-off examples that never compose. |
| `:select-ns` | optional | Selects existing namespace-authored registrations by their source namespace. The `{:include [...] :exclude [...]}` map below. |
| `:registrations` | optional | Defines new registrations *inline*, image-locally. The map below. |

An unknown top-level key fails loud (`:rf.error/invalid-image`) — the image surface is closed, not open, so a typo'd key is a loud error rather than a silently-ignored option. An image with neither `:select-ns` nor `:registrations` is valid but empty (it selects nothing and defines nothing — useful as a deliberate "no app registrations" image: `(rf/image {:id :test/empty})`).

Two ways to put registrations into an image:

**Select by source namespace with `:select-ns`.** The selector is a *query over the source store*, choosing registrations by the namespace they were authored in (their recorded provenance) — not by the keyword namespace of their id. A registration with id `:counter/inc` authored in `docs.quickstart.counter.basic` is selected because of *where it was written*, not because the keyword starts with `counter`. `:select-ns` is one map with an `:include` vector (required) and an optional `:exclude` vector.

```clojure
(def page-image
  (rf/image {:select-ns {:include ["docs.*.counter.*"
                                   "docs.shared.widgets.*"]}}))
```

The glob grammar is small and case-sensitive: a literal segment matches itself, `*` matches exactly one dot-free segment, and `**` matches zero or more segments. A segment may also carry an intra-segment `*` (each matching zero or more characters *within* that one segment, never crossing a `.`), so `*-cljs-test` matches the leaf of `app.feature.mount-cljs-test`. So `docs.shared.widgets.*` matches `docs.shared.widgets.button` but not `docs.shared.widgets` or `docs.shared.widgets.forms.input`; `docs.shared.**` matches all three. The selector does **not** load code — namespaces must already be required through normal `ns` dependencies; the glob only chooses from what the runtime already knows. And an `:include` pattern that matches *nothing* is an assembly error (`:rf.error/image-zero-match`), not an empty image: that turns a typo, a forgotten `require`, or a dead-code-eliminated namespace into a loud failure at assembly time instead of a silently incomplete frame.

**Narrow a broad glob with `:select-ns :exclude`.** A recursive `**` glob sometimes sweeps in sibling namespaces a frame must not load — classically a feature's own `*-test` namespaces, which in a dev/test build co-register the same ids the production sources do (an image selecting both fails assembly with a duplicate-id collision). The `:exclude` leg subtracts from the `:include` selection by provenance namespace, same grammar. Exclusion is global to the image: a namespace matched by any `:exclude` pattern is never selected, regardless of which `:include` caught it.

```clojure
(rf/image {:select-ns {:include ["day8.re-frame2-xray.**"]
                       :exclude ["day8.re-frame2-xray.**.*-cljs-test"
                                 "day8.re-frame2-xray.test-helpers.**"]}})
```

Unlike `:include`, an `:exclude` pattern that matches nothing is a no-op (a defensive guard, not a fail-loud error) — so a production build that never loads the excluded namespaces is unaffected, while the dev/test build gets the collision-free narrowing. `:exclude` applies only to the glob-selected registrations, never to inline `:registrations`.

> **Migrating from the earlier image keys?** If you've seen an older image spec using `:include-ns` / `:exclude-ns` as *sibling top-level keys*, or `:replace` / `:replace-standard` to declare an override winner, those are **retired** (EP-0026) — and they don't degrade gracefully: an `rf/image` spec carrying any of them fails loud (`:rf.error/invalid-image`) with a migration diagnostic, rather than being silently accepted or ignored. The mapping is: `:include-ns` + `:exclude-ns` collapse into the one `:select-ns {:include … :exclude …}` map; `:replace` becomes "put the winner in a later image and read `rf/frame-shadows`"; and `:replace-standard` is simply gone (framework standards are protected and no app image may shadow them).

**Or supply registrations inline with `:registrations`.** Useful for generated code, tests, or library packaging where authoring a whole namespace is overkill. The sections mirror the `reg-*` names, and entries are call-shaped tuples:

```clojure
(def small-image
  (rf/image
    {:id :test/small
     :registrations
     {:reg-event [[:counter/inc
                   {:doc "Increment."}
                   (fn [{:keys [db]} _] {:db (update db :count (fnil inc 0))})]]
      :reg-sub   [[:counter/value
                   {:doc "Current value."}
                   (fn [db _] (:count db 0))]]}}))
```

Inline `:registrations` covers **exactly four registrar kinds**: `:reg-event`, `:reg-sub`, `:reg-fx`, and `:reg-cofx`. Those are the kinds a test double or a generated slice realistically needs to define without a namespace. Every other section key — `:reg-interceptor`, `:reg-view`, `:reg-frame`, `:reg-route`, `:reg-flow`, and the rest — **fails loud** with an unsupported-inline-kind diagnostic; those kinds stay namespace-authored and you bring them into an image via `:select-ns`. (And `:reg-sub` inline accepts only the layer-1 db-reader shape `(fn [db query] …)`, as above — the signal-graph sub forms stay in source.) Each entry lowers through that kind's own registrar parser, so a malformed inline descriptor fails the same way the corresponding `reg-*` call would.

> **Gotcha — `:select-ns` and `:registrations` must be disjoint.** An image may carry both, but a single `(kind, id)` may not be *both* selected by provenance *and* defined inline in the same image — that's a within-image collision (`:rf.error/image-within-image-collision`), not an override. If you want an inline definition to *win over* a selected one, the inline definition goes in a **later** image and composes (the override story below), never as a second source for the same id in one image.

Most human-authored code should stay with ordinary `reg-*` forms and select by provenance. Inline descriptors are an option, not the main road — and they're descriptions of registrations, never `reg-*` calls smuggled into a map.

Whatever the inputs, an image is always resolved into one **sealed image generation** before the frame runs — framework standard registrations added, collisions and references validated, the result frozen. The frame resolves every lookup against that one sealed generation. Assembly is where a bad image fails, and each failure has a named error id you can catch and assert on:

| What's wrong | Error id |
|---|---|
| Two selected descriptors for one `(kind, id)` (different source namespaces) inside one image | `:rf.error/image-duplicate-id` |
| An inline entry colliding with a selected one (or two inline entries) in one image | `:rf.error/image-within-image-collision` |
| An `:include` glob that matches no loaded source namespace | `:rf.error/image-zero-match` |
| Two images sharing an `:id` in one composition | `:rf.error/image-duplicate-image-id` |
| An app registration colliding with a protected framework standard | `:rf.error/image-standard-replacement-forbidden` |
| A retired or unknown source key in an `rf/image` spec | `:rf.error/invalid-image` |
| `:images []` (empty composition) or a non-vector `:images` | `:rf.error/make-frame-bad-images` |

All caught *before any event touches state*, which is the payoff of making the registration set a value the framework can inspect up front. (`:images []` is an error because an empty vector almost always means "I meant to put images here and forgot"; if you genuinely want a frame with no app registrations, pass one real empty image — `(rf/make-frame {:images [(rf/image {:id :test/empty})]})` — so the intent is on the page.)

## The id story: reuse registration ids, never frame ids

This is the rule that makes same-on-one-page examples work, so it's worth stating sharply. There are two id spaces with different scopes:

| Id space | Example | Scope | Rule |
|---|---|---|---|
| **Registration ids** | `:counter/inc`, `:counter/value` | the resolved image | reusable across images; must be unambiguous *within* one sealed image |
| **Frame ids** | `:counter/left`, `:counter/right` | the process-local frame registry | must be unique among live frames |

Two images may both contain a `:counter/inc` event. Two live frames may not both register as `:counter/main`. So a docs page can reuse one teaching vocabulary across every example, while each mounted example still gets a distinct frame id.

```clojure
(def counter-basic  (rf/image {:select-ns {:include ["docs.quickstart.counter.basic"]}}))
(def counter-parity (rf/image {:select-ns {:include ["docs.quickstart.counter.parity"]}}))

;; Same ids inside each image (:counter/inc, :counter/value), different meaning.
;; Distinct frame ids, because frame ids are globally unique.
(rf/make-frame {:id :docs.counter/basic-frame  :images [counter-basic]  :initial-events [[:rf/set-db {:count 0}]]})
(rf/make-frame {:id :docs.counter/parity-frame :images [counter-parity] :initial-events [[:rf/set-db {:count 0}]]})
```

The reader sees one small vocabulary evolve across lessons instead of `:counter-v1/inc`, `:counter-v2/inc`, `:counter-v3/inc`. The image supplies the meaning; the frame ids keep the live instances apart.

## When you reach for an explicit image

The recurring shapes, all the same move — *different behaviour ⇒ different image; same behaviour, different history ⇒ same image, different frames*:

- **Two surfaces on one page.** A todo surface and a counter surface that both want simple local ids (`:boot/init`, `:item/add`). Give each its own image with disjoint `:select-ns` selectors; each frame resolves only its own.
- **An inspection tool beside its target.** Xray is itself a running surface with its own events, subs, and app-db paths. Run it in its own image and frame, and let it inspect the target frame as data — the tool never has to coordinate ids with the thing it inspects.
- **Progressive docs examples.** Four versions of a counter, each a lesson, each its own image, all reusing `:counter/inc` / `:counter/value` / `:counter/view`.
- **A library slice you compose in.** Eventually a library ships an image value; you build a frame from your image plus theirs. `:images` is the assembly input; the frame still runs one sealed generation:

```clojure
(rf/make-frame {:id :docs/main
                :images [widgets-image routing-image product-image]})
```

  Composition is deterministic and ordered: image order decides — the later image in `:images` wins. If two input images provide the same `(kind, id)`, the later one shadows the earlier, the assembly records it in the **shadow report** (`rf/frame-shadows`), and you apply whatever policy you want (assert none, assert a known set, log). A *within*-image collision is still an error: an image must resolve cleanly to one descriptor per `(kind, id)`, so to override you compose a later image, never two definitions in one.

## Reading what a frame is running

Because an image is a value and a frame carries its *resolved* image as a sealed generation, you can ask a live frame what it ended up running. This is the read side of everything above — useful in tests, in tooling like Xray, and at the REPL when a composition didn't resolve the way you expected. Every read takes a frame **id** *or* a frame **value** interchangeably, and every read **fails loud** (`:rf.error/frame-no-generation`) if the target isn't a live frame carrying a generation — there is no silent fall-through to a default registrar.

**`rf/frame-shadows`** — the cross-image override report, the most common read. A flat vector, one entry per cross-image shadow, three keys each:

```clojure
(rf/frame-shadows :docs/main)
;; => [{:registration [:fx :checkout.http/post]   ;; the shadowed (kind, id)
;;      :image        :app/main                   ;; the image the loser was defined in
;;      :shadowed-by  :test/doubles}]             ;; the image of the final winner
```

An empty vector means nothing was overridden — so `(empty? (rf/frame-shadows frame))` is the assertion "this composition stacked cleanly, no surprises." When images form a *chain* over one `(kind, id)` — `[base override-a override-b]` — every loser names the **final** winner (`override-b`), never an intermediate one, so an assertion never has to walk a chain.

**`rf/frame-generation`** — the whole sealed generation as inert data, when you want more than the shadow report. It has four stable public keys:

```clojure
(rf/frame-generation :docs/main)
;; => {:rf.gen/resolver {[<kind> <id>] <descriptor>, …}  ;; one descriptor per (kind, id)
;;     :rf.gen/images   [<image> <image> …]              ;; in :images order — later wins
;;     :rf.gen/kinds    #{:event :sub :fx :cofx …}        ;; kinds present
;;     :rf.gen/shadows  [{:registration … :image … :shadowed-by …} …]}  ;; what frame-shadows reads
```

**Frame-targeted registrar queries** — when you want *one* registration's metadata or the id set for *one* kind, resolved through this frame's image rather than the global registrar, pass a `{:frame …}` map to the ordinary introspection functions:

```clojure
(rf/handler-ids {:frame :docs/main :kind :event})        ;; the event ids THIS frame's image carries
(rf/handler-meta {:frame :docs/main :kind :sub :id :counter/value})  ;; that sub's metadata, with provenance
```

A map argument is *always* a frame-targeted read: a query map without a `:frame` key is itself an error (`:rf.error/registrar-query-needs-frame`), so you can never accidentally read the global registrar when you meant a frame. The metadata these return carries `:rf.provenance/ns` — *which source namespace* each registration came from — so when two images both define `:counter/inc` you can see, in data, which one won and where it was authored.

## Tests and stories: behaviour is an image change, state is a frame change

Tests and stories want controlled behaviour *and* controlled state. The image gives you the behaviour side; the frame gives you the state side. To swap in a fake HTTP effect or a story-recording navigation, you don't mutate a process-global registrar under the running frame — you build a different image:

```clojure
(def checkout-test-image
  (rf/image {:select-ns {:include ["checkout.core.**"
                                   "checkout.test-doubles.**"]}}))

(let [frame (rf/make-frame {:images [checkout-test-image]
                            :initial-events [[:rf/set-db {:cart/items []}]]})]
  (rf/dispatch-sync [:cart/add "SKU-1"] {:frame frame})
  @(rf/subscribe [:cart/items] {:frame frame}))
```

The frame is the target, but it rides in the **`{:frame …}` opts map** — the uniform last-argument envelope `dispatch-sync` / `subscribe` accept (per [EP-0024](../../../spec/002-Frames.md#the-multi-frame-surface--choose-by-intent); the older positional `(dispatch-sync frame event)` form is retired). The opt accepts a frame **value** (as here, the token `make-frame` returns) or a frame **id** keyword interchangeably.

State setup stays a frame concern — `:initial-events` (e.g. a leading `[:rf/set-db {…}]`), a restored frame-state value, or setup events. Behaviour setup is an image concern — select or override registrations before the frame runs. (Targeting the frame *value* directly, as above, is the test/harness path; mounted product code targets a frame *id* via the same `{:frame …}` opt. Both are in [Frames](frames.md).)

### Overriding a registration is a later image

To override an existing `(kind, id)`, define the winning registration in a *later* image and compose. Image order decides — the later image wins — and the assembly records what it shadowed in the **shadow report** so the override is visible in data, not hidden in load order:

```clojure
(def app-image
  (rf/image {:id :app/main
             :select-ns {:include ["checkout.core.**"]}}))

(def test-doubles
  (rf/image {:id :test/doubles
             :registrations
             {:reg-fx [[:checkout.http/post recording-post]]}}))  ;; stub the effect

(let [frame (rf/make-frame {:images [app-image test-doubles]})]   ;; test-doubles wins (later)
  (rf/frame-shadows frame))
;; => [{:registration [:fx :checkout.http/post] :image :app/main :shadowed-by :test/doubles}]
```

An override is always a *separate* image, never a second key in the same one: within one image two definitions of one `(kind, id)` are an error. A cross-image shadow resolves (later wins) and is reported; you read the report and apply whatever policy you want. The one cross-image collision that still fails assembly is an app registration colliding with a framework **standard**:

> If it's application-owned, define the winner in a later image and read the shadow report. If it's how the frame executes registered entries — queue ordering, the interceptor algorithm, app-db commit semantics — it's a protected framework standard, and no app image may shadow it.

## Hot reload swaps the image, keeps the memory

During development the source store changes as you save files. A `reg-*` re-eval doesn't mutate any running sealed generation — it marks every image that selects the changed namespace dirty, resolves fresh sealed generations, and swaps them into the affected frames. Existing app-db, runtime-db, queues, and still-valid subscription caches continue. The code changed; the VM kept its memory. That automatic path is the one you lean on day to day; you save a file and the live frames pick up the change without losing their state.

When you want to change a frame's image *composition* outright — swap one whole `:images` vector for another — that's an explicit, frame-targeted reload via **`rf/reload-images!`**. You hand it a frame (id or value) and a new `:images` vector, exactly the shape `make-frame` takes. It's composition-*replacing*, not member-patching: the whole `:images` vector is replaced, re-assembled into a fresh sealed generation, and swapped onto the frame's record — only the generation slot moves; app-db, runtime-db, queues, and still-valid subscription caches continue untouched.

```clojure
(rf/reload-images! :docs/main {:images [widgets-image routing-image product-v2-image]})
;; => {:rf.frame/frame   <frame value for the reloaded frame>
;;     :rf.reload/diff   {:added    #{[:event :product/discount]}
;;                        :changed  #{[:sub :product/price]}
;;                        :removed  #{[:event :product/legacy-init]}
;;                        :retained #{[:event :cart/add] …}}
;;     :rf.reload/shadows [{:registration … :image … :shadowed-by …} …]}
```

The return value is a **reload report**. Its `:rf.reload/diff` partitions the `(kind, id)` space four ways — `:added`, `:changed`, `:removed`, `:retained` — between the old generation and the new, so the runtime invalidates only what actually moved (a sub whose definition didn't change keeps its cache). `:rf.reload/shadows` is the new composition's shadow report, the same shape `rf/frame-shadows` returns, so if a reload changes the override set you see it right there alongside the diff. Reloading one frame never drags a sibling that happened to share a generation along with it — the swap is frame-targeted, and the old generation is never mutated.

> **`reload-images!` is the explicit knob; the automatic `reg-*` re-eval path above is what you actually lean on.** Save a file and the affected frames pick up the change with no call from you. Reach for `reload-images!` when you want to swap a frame's *whole composition* deliberately — a test that re-points a running frame at a different image stack, a tool driving a frame through several image configurations, a story canvas trading one deck of registrations for another.

So the same boundary that lets two examples on one page each own `:counter/inc` is the boundary that survives a file save: the image is a *value*, the runtime can diff two of them, and a frame can trade one for another without forgetting what it has lived through. Behaviour is the image; state is the frame; the events are the program — and once those three are separate things, every shape on this page (two surfaces side by side, a tool inspecting its target, a test double, four versions of a counter) is the same single move dressed in different clothes: *different behaviour means a different image; same behaviour with a different history means the same image with a different frame.*
