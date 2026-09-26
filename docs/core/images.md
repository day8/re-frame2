# Images: which registrations a frame runs

[Frames](frames.md) isolate state, but by default every frame runs every
registration you have loaded. Most apps never need anything else, and you can skip
this page until you do.

You need more when two examples on one page both register an event called
`:counter/inc`, when an inspection tool runs beside the app it inspects, or when a
test needs a fake HTTP effect instead of the real one. Each of these asks the same
question: which registrations does this frame use?

That set is the frame's [**image**](glossary.md#image). An image selects
registrations; a frame is a running instance that looks its handlers up in that
selection. One image can back several frames, and two images can each contain a
registration with the same id.

## You already use an image — the default one

Here is ordinary re-frame2. No image in sight:

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event]
    {:db (update db :count (fnil inc 0))}))

(rf/reg-sub :counter/value
  (fn [db _] (:count db 0)))
```

Those `reg-*` forms don't run anything. They add entries to the [**registrar**](glossary.md#registrar), the process-global table of every [registration](glossary.md#registration), each tagged with the namespace it was written in (its provenance, `:rf.provenance/*`).

When you create a frame *without* naming an image, that frame looks up every id in the whole registrar. That all-of-it selection is the **default image**. So an image is a *selection from the registrar*, and the default selects everything.

!!! note "The default image is a real sealed generation"

    `make-frame` with no `:images` key resolves a sealed **default generation** — a frozen `(kind, id)` → registration table — over the whole registrar, framework standards included. It goes through the same assembly checks as an explicit selection, so even the default path fails loud on a cross-namespace `[kind id]` collision (`:rf.error/image-duplicate-id`) instead of letting load order pick a winner. The one exception is a [replaceable framework default](#framework-standards-and-the-defaults-youre-meant-to-replace) such as `:rf.route/entry-denied`: when your app registers the same id, the framework's own copy is dropped.

In the common case you never name an image, new registrations are picked up as soon as you write them, and hot reload keeps working. You name an image only when "everything that's loaded, with globally unique ids" is not what a frame should run.

??? info "Coming from JavaScript modules?"

    Think of the registrar as your full set of module exports, and the default image as `import * from` everything. You don't normally think about it — until two modules export the same name and you need to be explicit about which one a particular consumer gets.

The default image is not a value you build with `rf/image`. In code it is simply a frame created with no `:images` key.

## When two registrations collide, it fails loud

The default image works only while ids are unique across everything that's loaded. When two loaded namespaces register the same `(kind, id)` with *different* implementations — two surfaces that both define `:counter/inc`, say — the default image fails to assemble with `:rf.error/image-duplicate-id`, naming the kind, the id and both source namespaces.

The registrar keeps *both* registrations, and assembly will not guess which one should win. You have three ways out, each covered below: rename one id, narrow each frame to its own slice with an explicit image, or compose a later image that overrides the other.

??? info "From re-frame v1"

    In v1 the second `reg-event` of `:counter/inc` replaced the first, and you found out when the wrong handler ran. re-frame2 keeps both and stops at assembly with a named error, before any event touches state ([fail loud, not silent](glossary.md#fail-loud-not-silent)).

## Naming an image: `rf/image`

When you do need to be explicit, `rf/image` builds an image *value*, and you hand it to a frame through `:images`:

```clojure
(def counter-image
  (rf/image {:select-ns {:include ["docs.quickstart.counter.basic"]}}))

(rf/make-frame {:id :counter/main
                :images [counter-image]})
```

`:select-ns` selects existing registrations by the namespace they were written in. This frame uses only the registrations written in `docs.quickstart.counter.basic`.

Notes:

1. Building an image registers nothing and runs nothing. `rf/image` returns plain data describing which registrations to select; nothing happens until a frame uses it.
2. Selection is by **provenance** — the namespace where a registration was written — not by the keyword namespace of its id. A registration with id `:counter/inc` written in `docs.quickstart.counter.basic` is selected because of the file it is in, not because its keyword starts with `counter`. This one trips people up.
3. The framework's own feature handlers are always included. Routing, managed HTTP, Resources, SSR and the `:rf/time-ms` coeffect are registered with no source namespace, so `:select-ns` cannot pick them; instead every explicit composition starts from a **framework base** of them (the framework's registrations under the reserved `:rf` root, plus the `:route/link` view). A later image can still override any of them, and the shadow report (below) names that base `:rf/framework`.
4. A registration made through a function alias or generated code, rather than a `reg-*` macro, has no source namespace either, so `:select-ns` cannot see it and only the default image includes it. Add `:ns` to its metadata to make it selectable.

??? info "Coming from a bundler's globs?"

    `:select-ns` is a glob over module paths, the way a bundler's `include`/`exclude` globs pick source files — except it selects *already-loaded* registrations rather than reading from disk. The namespace must already be `require`d through ordinary `ns` dependencies; the glob only chooses from what the runtime already knows. It never loads code for you.

### The three image keys

An `rf/image` spec takes three keys, all optional:

| Key | What it does |
|---|---|
| `:id` | The image's id, used in diagnostics and in the shadow report, which names overrides by image id. Ids must be unique within one `:images` vector. Anonymous images are fine for tests and one-off examples that never compose. |
| `:select-ns` | Selects existing registrations by their source namespace. |
| `:registrations` | Defines registrations inline, inside the image. |

An image with neither `:select-ns` nor `:registrations` is valid and empty, which is useful as a deliberate "no app registrations" image: `(rf/image {:id :test/empty})`.

Any other key fails with `:rf.error/invalid-image`, so a typo is caught when the image is built.

### Narrowing the glob: `:include` and `:exclude`

`:select-ns` is a `{:include [...] :exclude [...]}` map. `:include` is required; `:exclude` is optional and subtracts from the selection.

```clojure
(def page-image
  (rf/image {:select-ns {:include ["docs.*.counter.*"
                                   "docs.shared.widgets.*"]}}))
```

Use `:exclude` when a recursive glob (`**`) picks up namespaces a frame must not use. The usual case is a feature's test namespaces: in a dev build they are loaded, and they often re-register the ids the production sources define. Selecting both would be the collision described above, so `:include` the feature broadly and `:exclude` its tests:

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

    `:include` and `:exclude` treat an empty match differently. An `:include` pattern that matches nothing is an assembly error (`:rf.error/image-zero-match`), so a typo, a forgotten `require` or a dead-code-eliminated namespace fails loudly instead of leaving the frame incomplete. An `:exclude` pattern that matches nothing is ignored, so a production build that never loads the excluded test namespaces works unchanged. `:exclude` applies only to glob-selected registrations, never to inline `:registrations`.

### Defining registrations inline: `:registrations`

Most code should use ordinary `reg-*` forms and select them by namespace. For generated code, tests or library packaging, where a whole namespace for one fake is overkill, you can define registrations inside the image value. The keys mirror the `reg-*` names (`:reg-event`, `:reg-sub`, …), and each registration is a vector of the arguments you would pass to that `reg-*` call: the id, an optional metadata map, and the handler fn:

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

    Inline `:registrations` supports four kinds: `:reg-event`, `:reg-sub`, `:reg-fx` and `:reg-cofx`, the kinds a test double or generated slice needs. Any other section key (`:reg-interceptor`, `:reg-view`, `:reg-route`, `:reg-flow`, …) fails loud; those kinds are written in a namespace and selected with `:select-ns`. An inline `:reg-sub` has one body fn and declares its dependencies in the metadata, so a derived sub works inline: `{:inputs [[:cart/items]]}` (or an `:inputs` producer fn). Without `:inputs`, the body reads app-db as `(fn [db query] …)`. Each entry is parsed by the same code as its `reg-*` call, so a malformed entry fails the way that call would.

!!! warning "Gotcha — one id cannot come from both `:select-ns` and `:registrations`"

    An image may use both keys, but one `(kind, id)` may not be both selected by namespace and defined inline in the same image. That is an error (`:rf.error/image-within-image-collision`), not an override. To make an inline definition win over a selected one, put it in a **later** image (see [Overriding a registration is a later image](#overriding-a-registration-is-a-later-image)).

## The id rule that makes it all work

Two examples on one page can share ids because there are two id spaces with different scopes:

| Id space | Example | Scope | Rule |
|---|---|---|---|
| **Registration ids** | `:counter/inc`, `:counter/value` | the resolved image | reusable across images; must be unambiguous *within* one sealed image |
| **Frame ids** | `:counter/left`, `:counter/right` | the process-local frame registry | must be unique among live frames |

Two images may both contain a `:counter/inc` event. Two live frames may *not* both use the id `:counter/main`. So a docs page can reuse the same event and sub ids in every example, while each mounted example gets its own frame id:

```clojure
(def counter-basic  (rf/image {:select-ns {:include ["docs.quickstart.counter.basic"]}}))
(def counter-parity (rf/image {:select-ns {:include ["docs.quickstart.counter.parity"]}}))

;; Same ids inside each image (:counter/inc, :counter/value), different meaning.
;; Distinct frame ids, because frame ids are globally unique.
(rf/make-frame {:id :docs.counter/basic-frame  :images [counter-basic]  :initial-events [[:rf/set-db {:count 0}]]})
(rf/make-frame {:id :docs.counter/parity-frame :images [counter-parity] :initial-events [[:rf/set-db {:count 0}]]})
```

The reader sees the same ids across lessons instead of `:counter-v1/inc`, `:counter-v2/inc`, `:counter-v3/inc`. The image decides what each id means; the frame ids keep the running instances apart.

??? info "Coming from Redux?"

    An image is the set of reducers/selectors a store runs, lifted into a value you can name and compose — `combineReducers` if it returned *data* instead of a function, and could be assembled per-store. The key divergence: registration *names* are scoped to the image, so two stores can each define `:cart/add` meaning different things without a global collision. In Redux you'd reach for namespacing conventions or separate action-type constants; here the scope does it for you.

## The shape of every image decision

Every situation on this page reduces to one decision. An image holds *behaviour*: the registrations. A frame holds *state and history*: its own [app-db](glossary.md#app-db), evolving as events run. So:

**Different behaviour means a different image; the same behaviour with a different lived history means the same image, just a different frame.**

That rule covers the shapes you'll actually meet:

- **Two surfaces on one page.** A cart surface and a counter surface that both want simple local ids (`:boot/init`, `:item/add`). Give each its own image with disjoint `:select-ns` selectors; each frame resolves only its own.
- **An inspection tool beside its target.** [Xray](glossary.md#xray) is itself a running surface with its own events, subs, and app-db paths. Run it in its own image and frame, and let it inspect the target frame *as data* — the tool never has to coordinate ids with the thing it inspects.
- **Progressive docs examples.** Four versions of a counter, each a lesson, each its own image, all reusing `:counter/inc` / `:counter/value` / `:counter/view`.
- **A library slice you compose in.** A library ships an image value; you build a frame from your image plus theirs.

That last one introduces composition, which is the next step.

## Composing images: the later one wins

`:images` is a *vector*, and order matters. A frame can compose several images and still runs one sealed result:

```clojure
(rf/make-frame {:id :docs/main
                :images [cart-image routing-image checkout-image]})
```

**The later image in `:images` wins.** If two images provide the same `(kind, id)`, the later one *shadows* the earlier. Assembly records each override in the **shadow report** ([next section](#reading-what-a-frame-is-running)), and you decide what to do with it: assert there are none, assert a known set, or log them.

A collision *within* one image is still an error: an image must resolve to one registration per `(kind, id)`. To override, compose a later image.

??? note "Going deeper"

    Whatever the inputs, an image is always resolved into one **sealed image generation** before the frame runs an event: framework standard registrations added, collisions and references validated, the result frozen. Every lookup the frame makes resolves against that one sealed generation. Assembly is where a bad composition fails — and each failure carries a named error id you can `catch` and assert on:

    | What's wrong | Error id |
    |---|---|
    | Two selected registrations for one `(kind, id)` from different source namespaces inside one image (except an app registration of a [replaceable default](#framework-standards-and-the-defaults-youre-meant-to-replace), which replaces the framework's copy) | `:rf.error/image-duplicate-id` |
    | An inline entry colliding with a selected one (or two inline entries) in one image | `:rf.error/image-within-image-collision` |
    | An `:include` glob that matches no loaded source namespace | `:rf.error/image-zero-match` |
    | Two images sharing an `:id` in one composition | `:rf.error/image-duplicate-image-id` |
    | An app registration colliding with a protected framework standard | `:rf.error/image-standard-replacement-forbidden` |
    | A retired or unknown source key in an `rf/image` spec | `:rf.error/invalid-image` |
    | `:images []` (empty composition) or a non-vector `:images` | `:rf.error/make-frame-bad-images` |

    All of these are caught *before any event touches state*. `:images []` is an error because an empty vector almost always means "I meant to put images here and forgot". For a frame with no app registrations, pass one empty image: `(rf/make-frame {:images [(rf/image {:id :test/empty})]})`.

## Reading what a frame is running

A frame carries its resolved image as a sealed generation, so you can ask a live frame what it is running. This is useful in tests, in tools like Xray, and at the REPL when a composition didn't resolve the way you expected.

**`rf/frame-generation`** returns the whole generation as data. The key you will use most is **`:rf.gen/shadows`**, the override report: a flat vector with one entry per override, three keys each:

```clojure
(:rf.gen/shadows (rf/frame-generation :docs/main))
;; => [{:registration [:fx :checkout.http/post]   ;; the shadowed (kind, id)
;;      :image        :app/main                   ;; the image the loser was defined in
;;      :shadowed-by  :test/doubles}]             ;; the image of the final winner
```

An empty vector means nothing was overridden, so `(empty? (:rf.gen/shadows (rf/frame-generation frame)))` asserts that no image overrode another.

!!! warning "Gotcha — the read side needs a frame that carries a generation"

    Every `make-frame` frame carries one, from an explicit `:images` composition or the default image, so the shadow report is `[]` on a default-image frame. A target that is not a live frame with a generation (an unknown or destroyed frame id, say) makes `rf/frame-generation` **fail loud** with `:rf.error/frame-no-generation` rather than returning `[]` or `nil`. The same holds for the frame-targeted `{:frame …}` queries below.

??? note "Going deeper"

    The rest of the generation is inert data too — `:rf.gen/resolver` (one descriptor per `(kind, id)`), `:rf.gen/images` (in `:images` order, later wins), and `:rf.gen/kinds` (the kinds present). And **frame-targeted registrar queries** resolve *one* registration's metadata or the id set for *one* kind through this frame's image rather than the global registrar, by passing a `{:frame …}` map:

    ```clojure
    (keys (rf/registrations {:frame :docs/main :kind :event}))
    (rf/handler-meta {:frame :docs/main :kind :sub :id :counter/value})
    ```

    A query map must name *exactly one* source — `{:frame f …}` for this frame's image, or `{:source :store …}` for the process-global registrar — and a map naming neither (or both) is itself an error (`:rf.error/registrar-query-needs-source`), so you can never accidentally read the global registrar when you meant a frame. The metadata carries `:rf.provenance/ns` — which source namespace each registration came from — so when two images both define `:counter/inc` you can see in data which one won and where it was authored. Every read takes a frame **id** or a frame **value** interchangeably, and every read fails loud (`:rf.error/frame-no-generation`) if the target isn't a live frame carrying a generation. When images form a *chain* over one `(kind, id)` — `[base override-a override-b]` — every loser names the **final** winner, never an intermediate one, so an assertion never walks a chain.

## Tests and stories: behaviour is the image, state is the frame

A test needs to fix both behaviour and state. The image gives you the behaviour; the frame gives you the state. So to swap in a fake HTTP [effect handler](glossary.md#effect-handler), you don't change the process-global registrar under a running frame; you compose the fakes as a later image:

```clojure
(def checkout-image
  (rf/image {:id :checkout/core
             :select-ns {:include ["checkout.core.**"]}}))

(def checkout-doubles                 ;; re-registers :checkout.http/post as a fake
  (rf/image {:id :checkout/doubles
             :select-ns {:include ["checkout.test-doubles.**"]}}))

(let [frame (rf/make-frame {:images [checkout-image checkout-doubles]
                            :initial-events [[:rf/set-db {:cart/items []}]]})]
  (rf/dispatch-sync [:cart/add "SKU-1"] {:frame frame})
  @(rf/subscribe [:cart/items] {:frame frame}))
```

The doubles go in a separate, later image because selecting both namespaces in *one* image would make the two `:checkout.http/post` registrations collide.

State setup is a *frame* concern: `:initial-events` (e.g. a leading `[:rf/set-db {…}]`), a restored frame-state value, or setup events. Behaviour setup is an *image* concern: select or override registrations before the frame runs. Because the two are separate, a test can vary one without touching the other.

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
  (:rf.gen/shadows (rf/frame-generation frame)))
;; => [{:registration [:fx :checkout.http/post] :image :app/main :shadowed-by :test/doubles}]
```

The stub replaces `:checkout.http/post`, and the shadow report records which image lost and which won. An override is always a separate, later image, never a second definition in the same one.

**You cannot override a framework standard.** The one cross-image collision that still fails assembly is an app registration with the same id as a framework standard (`:rf.error/image-standard-replacement-forbidden`). Standards implement how a frame executes registrations — queue ordering, the interceptor algorithm, app-db commit semantics — so no app image may shadow them.

### Framework standards, and the defaults you're meant to replace

Not everything the framework registers is a standard, and the difference decides whether your registration of the same id is a violation or the documented recipe.

- A **framework standard** encodes an execution invariant — `:rf/set-db`, the interceptor algorithm, queue ordering. Protected: an app registration of the same id fails assembly with `:rf.error/image-standard-replacement-forbidden`. There is no opt-in.
- A **replaceable framework default** is the framework's placeholder for a decision *your application* makes, registered so the feature works when you register nothing. There are two: `:rf.route/entry-denied` and `:rf.route/navigation-blocked`. Both are no-ops, so a `:can-enter` denial or a `:can-leave` block always has a handler, and the [auth recipe](../routing/how-to/require-sign-in-on-a-route.md) has you register your own. The framework marks them with the reserved `:rf/framework-default?` metadata key.

So `(rf/reg-event :rf.route/entry-denied …)` in your own namespace is *not* a duplicate-id collision. Once your registration exists, assembly drops the framework's copy. Two details:

- *Two* app registrations of one framework-default id are still `:rf.error/image-duplicate-id`, like any other duplicate.
- Putting `:rf/framework-default?` on your own registration does nothing. The key counts only on a registration with no source namespace, which is how the framework's own copy is identified, and a `reg-*` macro always records the namespace it was written in.

## Hot reload swaps the image, keeps the memory

During development the registrar changes every time you save a file. Re-evaluating a `reg-*` form does not change any running generation. Instead the runtime marks every image that selects the changed namespace as dirty, resolves new sealed generations, and swaps them into the affected frames. The frames keep their app-db, [runtime-db](glossary.md#runtime-db), queues and still-valid subscription caches. You save a file and the live frames pick up the change without losing state; you call nothing.

To change a frame's composition outright — replace its whole `:images` vector — call **`rf/make-frame`** again with the same `:id` and a new `:images` vector. There is no separate reload function:

```clojure
(rf/make-frame {:id :docs/main :images [cart-image routing-image checkout-v2-image]})
```

The whole vector is assembled into a new sealed generation and installed on the existing frame. Only the generation changes; app-db, runtime-db, queues and still-valid subscription caches are kept. Config other than `:images` is replaced too, as with a Clojure `def`, so pass again anything you want to keep.

To see what changed, read `frame-generation` before and after the call and compare the two values with `generation-diff`:

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

    `generation-diff` partitions the `(kind, id)` space four ways — `:added`, `:changed`, `:removed`, `:retained` — between the old generation and the new, so you can invalidate only what actually moved (a sub whose definition didn't change keeps its cache). Read `(:rf.gen/shadows (rf/frame-generation f))` on the reloaded frame for its cross-image shadow report — the same shape it always returns — so if a reload changes the override set you see it there too. Reloading one frame never drags a sibling that happened to share a generation along with it — the swap is frame-targeted, and the old generation is never mutated. Re-assembling the new `:images` runs the same assembly gate as any `make-frame` call, so a bad new composition fails loud with the *same* error ids from the table above (a duplicate id, a zero-match include, …) — the swap is all-or-nothing, and a failed reload leaves the frame on its existing generation.

    **Gotcha — reload targets an `:id`-bearing frame.** Every `make-frame` frame qualifies, the sealed default included — re-pointing a default-image frame at an explicit composition is exactly the move it exists for. A frame with no `:id` (a direct, local-only object) has no id to re-`make-frame` against — discard it and make a new one.

You need the explicit call only when you want to swap a frame's whole composition on purpose: a test that points a running frame at a different image stack, a tool driving a frame through several configurations, or a story swapping one set of registrations for another.
