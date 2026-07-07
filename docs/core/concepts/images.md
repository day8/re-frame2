# Images: which registrations a frame runs

You've registered a dozen [events](../glossary.md#event) and [subscriptions](../glossary.md#subscription). Your app works. And you have never once wondered *which* of them a given [frame](../glossary.md#frame) can see.

Good. That's the design working. The default path is deliberately invisible, and you can stay productive on it for a long time without learning the word on this page.

This page is for the day the default stops being enough. The day you want two examples on one page that both call their event `:counter/inc`. Or an inspection tool mounted right beside the app it inspects. Or a test that needs a *fake* HTTP effect instead of the real one. Three different wishes; one question underneath — *which registrations does this frame resolve against?*

The answer is the [**image**](../glossary.md#image):

> **An image is which registrations are loaded; a frame is the live run that resolves against them.**

Don't skim past that one — it's the sentence this page hangs off, and everything below is the same line in different costumes.

If you want something concrete to hold, hold a theatre. The image is the script: which characters exist, what their lines are. The frame is tonight's performance: live, on stage, with its own history of everything that has already happened. One script can play on two stages at once. Two different scripts can both contain a character called the King. Keep the theatre; the rest of the page will use it.

We'll build the idea up one step at a time, starting from code you've already written without realising an image was there at all.

## You already use an image — the default one

Here is ordinary re-frame2. No image in sight:

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event]
    {:db (update db :count (fnil inc 0))}))

(rf/reg-sub :counter/value
  (fn [db _] (:count db 0)))
```

Those `reg-*` forms don't *run* anything. They write entries into the [**registrar**](../glossary.md#registrar) — the process-global table holding every [registration](../glossary.md#registration) you've authored, each tagged with the namespace it was written in (its registration source, `:rf.provenance/*`). Nothing has executed yet. You've just filled the registrar.

When you then create a frame *without* naming an image, that frame resolves every lookup straight against the whole registrar — "everything I've registered." That implicit all-of-it selection is the **default image**: the conceptual zero-config selection you get for free. So an image, at its simplest, is just a *selection from the registrar*. The default selects all of it.

!!! note "Heads-up — the default image is a real sealed generation"

    "The whole registrar sealed into one set" is not just the concept — mechanically it is exactly what you get. `make-frame` with no `:images` key resolves a sealed **default generation** over the whole active store (framework standards included): the implicit selector over everything. It runs the same assembly gate as an explicit selection, so even the default path fails loud on a cross-namespace `[kind id]` collision (`:rf.error/image-duplicate-id`) rather than letting load order silently pick a survivor. The one frame that carries *no* generation is one declared with bare `reg-frame` — it resolves each lookup live against the shared registrar, which is why the read-side accessors below want a `make-frame`-built frame.

The common case stays boring, on purpose. You never name an image. New registrations show up the moment you write them. Hot reload keeps working. You meet the image concept *explicitly* only when "everything that's loaded, ids assumed globally unique" stops being the boundary you want.

??? info "Coming from JavaScript modules?"

    Think of the registrar as your full set of module exports, and the default image as `import * from` everything. You don't normally think about it — until two modules export the same name and you need to be explicit about which one a particular consumer gets.

One clarification before we move on, because the name invites the mistake: "default image" is the runtime's zero-config behaviour, not a value you author. Don't reach for `rf/image` to get the default — plain `reg-*` already gives it to you. In code, the default image is simply *a frame created with no `:images` key*. You never write the word "image" to get the default image.

## When two registrations collide, it fails loud

The default image works only while ids are globally unique across everything that's loaded. The moment two loaded namespaces register the same `(kind, id)` with *different* implementations — two surfaces that both define `:counter/inc`, say — the default image refuses to assemble. The error names the colliding kind/id and both source namespaces.

That refusal is a feature. Read it as one. The registrar kept *both* descriptors, and assembly won't guess which one wins. You have three explicit ways out, and we'll meet each below: rename one id; narrow each frame to its own slice with an explicit image; or compose a later image that declares an exact replacement winner. The one thing the framework will not do is silently pick a survivor and move on.

??? info "From re-frame v1"

    This is the deliberate hardening over v1's last-write-wins registrar. In v1 the second `reg-event` of `:counter/inc` quietly clobbered the first, and you found out weeks later when the wrong handler ran in production. v2 keeps both and stops at assembly with a named error, before any event touches state. It [fails loud, not silent](../glossary.md#fail-loud-not-silent).

## Naming an image: `rf/image`

When you do need to be explicit, `rf/image` builds an image *value*, and you hand it to a frame through `:images`:

```clojure
(def counter-image
  (rf/image {:select-ns {:include ["docs.quickstart.counter.basic"]}}))

(rf/make-frame {:id :counter/main
                :images [counter-image]})
```

`:select-ns` is the everyday move: *select existing registrations by the namespace they were authored in.* The frame now resolves against only the registrations written in `docs.quickstart.counter.basic` — not the whole registrar. Just that slice.

Notes:

1. Building an image registers nothing and runs nothing. `rf/image` produces a plain data description of "which registrations to select" — inert until a frame composes it.
2. The selection chooses by **provenance** — *where a registration was written* — not by the keyword namespace of its id. A registration with id `:counter/inc` authored in `docs.quickstart.counter.basic` is selected because of the *file it lives in*, not because the keyword starts with `counter`. This one trips people up, so file it away now.

??? info "Coming from a bundler's globs?"

    `:select-ns` is a glob over module paths, the way a bundler's `include`/`exclude` globs pick source files — except it selects *already-loaded* registrations rather than reading from disk. The namespace must already be `require`d through ordinary `ns` dependencies; the glob only chooses from what the runtime already knows. It never loads code for you.

### The three image keys

An `rf/image` value carries **exactly three** public source keys, and nothing else:

| Key | Required? | What it does |
|---|---|---|
| `:id` | optional | The image's stable id. Used in diagnostics and — crucially — in the shadow report (overrides are named *by image id*). Ids must be unique *within one `:images` composition*. Anonymous images are fine for local tests and one-off examples that never compose. |
| `:select-ns` | optional | Selects existing namespace-authored registrations by their source namespace. |
| `:registrations` | optional | Defines new registrations *inline*, image-locally. |

An image with neither `:select-ns` nor `:registrations` is valid but empty — useful as a deliberate "no app registrations" image: `(rf/image {:id :test/empty})`.

And "nothing else" is enforced, not aspirational: the image surface is *closed*, not open. An unknown top-level key fails loud (`:rf.error/invalid-image`) rather than being silently ignored — a typo'd key is a loud error you hear at construction, not a mystery you debug at runtime.

### Narrowing the glob: `:include` and `:exclude`

`:select-ns` is a `{:include [...] :exclude [...]}` map. `:include` is required; `:exclude` is optional and subtracts from the selection.

```clojure
(def page-image
  (rf/image {:select-ns {:include ["docs.*.counter.*"
                                   "docs.shared.widgets.*"]}}))
```

You reach for `:exclude` when a recursive glob (`**`) sweeps in sibling namespaces a frame must not load. The classic case is a feature's own `*-test` namespaces: in a dev build those *are* loaded, and they often re-register the same ids the production sources do (so they can exercise them). Selecting both at once would be exactly the collision we saw earlier — so you `:include` the feature broadly and `:exclude` its tests back out:

```clojure
(rf/image {:select-ns {:include ["day8.re-frame2-xray.**"]
                       :exclude ["day8.re-frame2-xray.**.*-cljs-test"
                                 "day8.re-frame2-xray.test-helpers.**"]}})
```

??? note "Going deeper"

    The glob grammar is small and case-sensitive, and works on the dotted namespace path:

    - a literal segment matches itself;
    - `*` matches exactly one dot-free segment;
    - `**` matches zero or more segments;
    - a `*` *inside* a segment matches zero or more characters within that one segment, never crossing a `.` — so `*-cljs-test` matches the leaf of `app.feature.mount-cljs-test`.

    Worked through: `docs.shared.widgets.*` matches `docs.shared.widgets.button` but not `docs.shared.widgets` (no leaf) or `docs.shared.widgets.forms.input` (two leaves); `docs.shared.**` matches all three.

    The two legs — `:include` and `:exclude` — are deliberately asymmetric about matching *nothing*. An `:include` that matches nothing is an assembly error (`:rf.error/image-zero-match`): a typo, a forgotten `require`, or a dead-code-eliminated namespace becomes a loud failure rather than a silently-incomplete frame. An `:exclude` that matches nothing is a harmless no-op — so a production build that never loads the excluded test namespaces is unaffected, while the dev build still gets its collision-free narrowing. (`:exclude` applies only to glob-selected registrations, never to inline `:registrations`.)

??? info "Coming from an older image spec?"

    There are no `:include-ns` / `:exclude-ns` sibling top-level keys, and no `:replace` / `:replace-standard` override keys. An `rf/image` carrying any of them fails loud (`:rf.error/invalid-image`) with a migration diagnostic. The mapping: `:include-ns` + `:exclude-ns` collapse into the one `:select-ns {:include … :exclude …}` map; `:replace` becomes "put the winner in a later image and read `rf/frame-shadows`" (the override story below); and there is no `:replace-standard` — framework standards are protected, and no app image may shadow them.

### Defining registrations inline: `:registrations`

Most human-authored code should stay with ordinary `reg-*` forms and select by provenance. But for generated code, tests, or library packaging — where standing up a whole namespace just to register one fake is overkill — you can define registrations *inline*, right inside the image value. The keys mirror the `reg-*` names (`:reg-event`, `:reg-sub`, …), and each registration is written as a vector holding the same arguments you'd pass to that `reg-*` call — the id, an optional metadata map, and the handler fn:

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

??? note "Going deeper"

    Inline `:registrations` covers **exactly four** registrar kinds: `:reg-event`, `:reg-sub`, `:reg-fx`, `:reg-cofx` — the kinds a test double or generated slice realistically needs without a namespace. Every other section key (`:reg-interceptor`, `:reg-view`, `:reg-frame`, `:reg-route`, `:reg-flow`, …) fails loud with an unsupported-inline-kind diagnostic; those stay namespace-authored and come in via `:select-ns`. Inline `:reg-sub` accepts only the layer-1 db-reader shape `(fn [db query] …)`; subs with declared inputs (the `:<-` and input-function forms) stay in source. Each entry lowers through its kind's own registrar parser, so a malformed inline descriptor fails exactly the way the corresponding `reg-*` call would. These are *descriptions* of registrations, never `reg-*` calls smuggled into a map.

!!! warning "Gotcha — `:select-ns` and `:registrations` must be disjoint"

    An image may carry both, but a single `(kind, id)` may not be *both* selected by provenance *and* defined inline in the same image — that's a within-image collision (`:rf.error/image-within-image-collision`), not an override. If you want an inline definition to *win over* a selected one, the inline definition goes in a **later** image and composes (see [Overriding a registration is a later image](#overriding-a-registration-is-a-later-image)), never as a second source for the same id in one image.

## The id rule that makes it all work

Here is the rule that makes two examples on one page possible, and it's worth stating sharply. There are two id spaces, with different scopes:

| Id space | Example | Scope | Rule |
|---|---|---|---|
| **Registration ids** | `:counter/inc`, `:counter/value` | the resolved image | reusable across images; must be unambiguous *within* one sealed image |
| **Frame ids** | `:counter/left`, `:counter/right` | the process-local frame registry | must be unique among live frames |

Two images may both contain a `:counter/inc` event. Two live frames may *not* both register as `:counter/main`. In theatre terms: every script is allowed a character called the King, but the building has exactly one Stage 3. So a docs page can reuse one teaching vocabulary across every example, while each mounted example still gets a distinct frame id:

```clojure
(def counter-basic  (rf/image {:select-ns {:include ["docs.quickstart.counter.basic"]}}))
(def counter-parity (rf/image {:select-ns {:include ["docs.quickstart.counter.parity"]}}))

;; Same ids inside each image (:counter/inc, :counter/value), different meaning.
;; Distinct frame ids, because frame ids are globally unique.
(rf/make-frame {:id :docs.counter/basic-frame  :images [counter-basic]  :initial-events [[:rf/set-db {:count 0}]]})
(rf/make-frame {:id :docs.counter/parity-frame :images [counter-parity] :initial-events [[:rf/set-db {:count 0}]]})
```

The reader sees one small vocabulary evolve across lessons instead of `:counter-v1/inc`, `:counter-v2/inc`, `:counter-v3/inc`. The image supplies the *meaning*; the frame ids keep the live *instances* apart.

??? info "Coming from Redux?"

    An image is the set of reducers/selectors a store runs, lifted into a value you can name and compose — `combineReducers` if it returned *data* instead of a function, and could be assembled per-store. The key divergence: registration *names* are scoped to the image, so two stores can each define `:cart/add` meaning different things without a global collision. In Redux you'd reach for namespacing conventions or separate action-type constants; here the scope does it for you.

## The shape of every image decision

Every situation on this page — every single one — reduces to one decision. An image holds *behaviour*: the registrations. A frame holds *state and history*: its own [app-db](../glossary.md#app-db), evolving as events run. So:

**Different behaviour means a different image; the same behaviour with a different lived history means the same image, just a different frame.**

Two surfaces on one page? That rule. A tool inspecting a live app? That rule. Test doubles, library packaging, story canvases, progressive lessons, the thing you're building right now that isn't on this list? The rule, the rule, the rule, probably the rule. ...All right. One rule doesn't need a chant. Watch it produce every shape instead:

- **Two surfaces on one page.** A cart surface and a counter surface that both want simple local ids (`:boot/init`, `:item/add`). Give each its own image with disjoint `:select-ns` selectors; each frame resolves only its own.
- **An inspection tool beside its target.** [Xray](../glossary.md#xray) is itself a running surface with its own events, subs, and app-db paths. Run it in its own image and frame, and let it inspect the target frame *as data* — the tool never has to coordinate ids with the thing it inspects.
- **Progressive docs examples.** Four versions of a counter, each a lesson, each its own image, all reusing `:counter/inc` / `:counter/value` / `:counter/view`.
- **A library slice you compose in.** A library ships an image value; you build a frame from your image plus theirs.

That last one introduces composition, which is the next step.

## Composing images: the later one wins

`:images` is a *vector*, and order is meaningful. You compose several images into one frame, and the frame still runs exactly one sealed result:

```clojure
(rf/make-frame {:id :docs/main
                :images [cart-image routing-image checkout-image]})
```

The rule is simple: **the later image in `:images` wins.** If two input images provide the same `(kind, id)`, the later one *shadows* the earlier — the assembly records it in the **shadow report** (which we read in the next section), and you apply whatever policy you like (assert none, assert a known set, log).

A *within*-image collision is still an error — an image must resolve cleanly to one descriptor per `(kind, id)`. So to override, you compose a later image; you never put two definitions of one id inside one image.

??? note "Going deeper"

    Whatever the inputs, an image is always resolved into one **sealed image generation** before the frame runs an event: framework standard registrations added, collisions and references validated, the result frozen. Every lookup the frame makes resolves against that one sealed generation. Assembly is where a bad composition fails — and each failure carries a named error id you can `catch` and assert on:

    | What's wrong | Error id |
    |---|---|
    | Two selected descriptors for one `(kind, id)` (different source namespaces) inside one image | `:rf.error/image-duplicate-id` |
    | An inline entry colliding with a selected one (or two inline entries) in one image | `:rf.error/image-within-image-collision` |
    | An `:include` glob that matches no loaded source namespace | `:rf.error/image-zero-match` |
    | Two images sharing an `:id` in one composition | `:rf.error/image-duplicate-image-id` |
    | An app registration colliding with a protected framework standard | `:rf.error/image-standard-replacement-forbidden` |
    | A retired or unknown source key in an `rf/image` spec | `:rf.error/invalid-image` |
    | `:images []` (empty composition) or a non-vector `:images` | `:rf.error/make-frame-bad-images` |

    All caught *before any event touches state* — the payoff of making the registration set a value the framework can inspect up front. (`:images []` is an error because an empty vector almost always means "I meant to put images here and forgot." If you genuinely want a frame with no app registrations, pass one real empty image: `(rf/make-frame {:images [(rf/image {:id :test/empty})]})`, so the intent is on the page.)

## Reading what a frame is running

Because an image is a value and a frame carries its *resolved* image as a sealed generation, you can ask a live frame what it ended up running. This is the read side of everything above — handy in tests, in tooling like Xray, and at the REPL when a composition didn't resolve the way you expected.

The most common read is **`rf/frame-shadows`** — the cross-image override report. A flat vector, one entry per shadow, three keys each:

```clojure
(rf/frame-shadows :docs/main)
;; => [{:registration [:fx :checkout.http/post]   ;; the shadowed (kind, id)
;;      :image        :app/main                   ;; the image the loser was defined in
;;      :shadowed-by  :test/doubles}]             ;; the image of the final winner
```

An empty vector means nothing was overridden — so `(empty? (rf/frame-shadows frame))` is the assertion "this composition stacked cleanly, no surprises."

!!! warning "Gotcha — the read side needs a frame that carries a generation"

    Every `make-frame` frame carries one — an explicit `:images` composition or the sealed default — so `rf/frame-shadows` answers `[]` on a default-image frame (nothing composed, nothing shadowed). The frame with nothing to read is one declared via bare `reg-frame`: it resolves live against the registrar, carries no generation, and the read side **fails loud** with `:rf.error/frame-no-generation` rather than returning `[]` or `nil`. The same holds for `rf/frame-generation` and the frame-targeted `{:frame …}` queries below.

??? note "Going deeper"

    Two more reads when the shadow report isn't enough. **`rf/frame-generation`** returns the whole sealed generation as inert data — `:rf.gen/resolver` (one descriptor per `(kind, id)`), `:rf.gen/images` (in `:images` order, later wins), `:rf.gen/kinds` (the kinds present), and `:rf.gen/shadows` (what `frame-shadows` reads). And **frame-targeted registrar queries** resolve *one* registration's metadata or the id set for *one* kind through this frame's image rather than the global registrar, by passing a `{:frame …}` map:

    ```clojure
    (rf/handler-ids {:frame :docs/main :kind :event})
    (rf/handler-meta {:frame :docs/main :kind :sub :id :counter/value})
    ```

    A map argument is *always* a frame-targeted read — a query map without a `:frame` key is itself an error (`:rf.error/registrar-query-needs-frame`), so you can never accidentally read the global registrar when you meant a frame. The metadata carries `:rf.provenance/ns` — which source namespace each registration came from — so when two images both define `:counter/inc` you can see in data which one won and where it was authored. Every read takes a frame **id** or a frame **value** interchangeably, and every read fails loud (`:rf.error/frame-no-generation`) if the target isn't a live frame carrying a generation. When images form a *chain* over one `(kind, id)` — `[base override-a override-b]` — every loser names the **final** winner, never an intermediate one, so an assertion never walks a chain.

## Tests and stories: behaviour is the image, state is the frame

Tests and stories want two things pinned down: behaviour *and* state. The image gives you the behaviour half; the frame gives you the state half. So to swap in a fake HTTP [effect handler](../glossary.md#effect-handler), you don't mutate a process-global registrar underneath a running frame — you build a different image:

```clojure
(def checkout-test-image
  (rf/image {:select-ns {:include ["checkout.core.**"
                                   "checkout.test-doubles.**"]}}))

(let [frame (rf/make-frame {:images [checkout-test-image]
                            :initial-events [[:rf/set-db {:cart/items []}]]})]
  (rf/dispatch-sync [:cart/add "SKU-1"] {:frame frame})
  @(rf/subscribe [:cart/items] {:frame frame}))
```

State setup is a *frame* concern — `:initial-events` (e.g. a leading `[:rf/set-db {…}]`), a restored frame-state value, or setup events. Behaviour setup is an *image* concern — select or override registrations before the frame runs. The two never tangle, which is exactly why a test can fix behaviour and history independently.

??? info "Coming from Jest mocks or MSW?"

    If you've used `jest.mock` module mocks or MSW handlers, you know the pain of mutating a shared module registry and remembering to reset it between tests. The image avoids that entirely: the test double is a *value* you compose into a *fresh* frame, so there's no global to dirty and nothing to reset — the frame is discarded when the test ends.

??? note "Going deeper"

    The frame rides in the `{:frame …}` opts map — the uniform last-argument envelope `dispatch-sync` / `subscribe` accept. There is no positional `(dispatch-sync frame event)` form; the frame travels in the opts map. The opt accepts a frame **value** (the token `make-frame` returns) or a frame **id** keyword interchangeably. Targeting the frame value directly is the test/harness path; mounted product code targets a frame *id* through the same opt. Both are covered in [Frames](frames.md).

## Overriding a registration is a later image

To override an existing `(kind, id)`, define the winning registration in a *later* image and compose. Order decides — the later image wins — and the assembly records what it shadowed, so the override is visible in data, not hidden in load order:

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

The stub is an understudy: same part — `:checkout.http/post` — different actor, and the stage manager's log (the shadow report) says exactly who went on. An override is always a *separate* image, never a second key in the same one. A cross-image shadow resolves (later wins) and is reported; you read the report and apply whatever policy you want.

One boundary is absolute, so hear it plainly: **you cannot override a framework standard.** The one cross-image collision that still fails assembly is an app registration colliding with a framework standard. If it's application-owned, define the winner in a later image and read the shadow report. But if it's *how the frame executes registered entries* — queue ordering, the interceptor algorithm, app-db commit semantics — that's a protected standard (`:rf.error/image-standard-replacement-forbidden`), and no app image may shadow it. A standard encodes an execution invariant, not an app policy choice.

## Hot reload swaps the image, keeps the memory

During development the registrar changes every time you save a file. A `reg-*` re-eval doesn't mutate any running sealed generation — it marks every image that selects the changed namespace *dirty*, resolves fresh sealed generations, and swaps them into the affected frames. The existing app-db, [runtime-db](../glossary.md#runtime-db), queues, and still-valid subscription caches continue. The code changed; the VM kept its memory.

That automatic path is the one you lean on day to day. You save a file. The live frames pick up the change without losing their state. You call nothing.

When you want to change a frame's image *composition* outright — swap one whole `:images` vector for another — that's an explicit, frame-targeted reload: re-call **`rf/make-frame`** against the SAME `:id` with a new `:images` vector. There is no dedicated reload verb — re-construction already refreshes the generation while preserving frame memory, so that IS the reload:

```clojure
(rf/make-frame {:id :docs/main :images [cart-image routing-image checkout-v2-image]})
```

You hand it the SAME `:id` and a new `:images` vector — exactly the shape `make-frame` always takes. It's composition-*replacing*, not member-patching: the whole vector is re-assembled into a fresh sealed generation and installed onto the frame's record via `reg-frame`'s surgical-update path. Only the generation slot moves; app-db, runtime-db, queues, and still-valid subscription caches continue untouched (config other than `:images` is refreshed too, Clojure-`def`-style — re-supply anything you want kept).

If you want the diff report the old `reload-images!` verb used to return, read `frame-generation` before and after the call and diff the two values with `generation-diff`:

```clojure
(let [before (rf/frame-generation :docs/main)
      _      (rf/make-frame {:id :docs/main :images [cart-image routing-image checkout-v2-image]})
      after  (rf/frame-generation :docs/main)]
  (rf/generation-diff before after))
;; => {:added    #{[:event :checkout/apply-discount]}
;;     :changed  #{[:sub :checkout/total]}
;;     :removed  #{[:event :checkout/legacy-init]}
;;     :retained #{[:event :cart/add] …}}
```

??? note "Going deeper"

    `generation-diff` partitions the `(kind, id)` space four ways — `:added`, `:changed`, `:removed`, `:retained` — between the old generation and the new, so you can invalidate only what actually moved (a sub whose definition didn't change keeps its cache). Read `rf/frame-shadows` on the reloaded frame for its cross-image shadow report — the same shape it always returns — so if a reload changes the override set you see it there too. Reloading one frame never drags a sibling that happened to share a generation along with it — the swap is frame-targeted, and the old generation is never mutated. Re-assembling the new `:images` runs the same assembly gate as any `make-frame` call, so a bad new composition fails loud with the *same* error ids from the table above (a duplicate id, a zero-match include, …) — the swap is all-or-nothing, and a failed reload leaves the frame on its existing generation.

    **Gotcha — reload targets an `:id`-bearing, generation-carrying frame.** Every `make-frame` frame qualifies, the sealed default included — re-pointing a default-image frame at an explicit composition is exactly the move it exists for. A frame declared via bare `reg-frame` (no generation to swap) has no image composition to reload — recreate it with `make-frame` carrying the `:images` you want instead. A frame with no `:id` (a direct, local-only object) has no id to re-`make-frame` against either — discard it and make a new one.

!!! note "Heads-up"

    Re-`make-frame`-ing is the explicit knob; the automatic `reg-*` re-eval path is what you actually lean on. Save a file and the affected frames pick up the change with no call from you. Reach for the explicit re-`make-frame` reload only when you want to swap a frame's *whole composition* deliberately — a test that re-points a running frame at a different image stack, a tool driving a frame through several configurations, a story canvas trading one deck of registrations for another.

And that's the whole shape. The same boundary that lets two examples on one page each own `:counter/inc` is the boundary that survives a file save: the image is a *value*, the runtime can diff two of them, and a frame can trade one for another without forgetting what it has lived through. The script can be revised mid-run; the performance keeps its memory.

Behaviour is the image. State is the frame. The events are the program. Once those three are separate things, every situation above is one move in different clothes: *different behaviour means a different image; the same behaviour with a different history means the same image with a different frame.*
