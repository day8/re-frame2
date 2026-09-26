# Images: which registrations a frame runs

[Frames](frames.md) isolate state, but by default every frame runs every
registration you have loaded. Most apps never need anything else, and you can skip
this page until you do.

You need more when two examples on one page both register an event called
`:todo/add`, when an inspection tool runs beside the app it inspects, or when a test
needs a fake storage effect instead of the real one. Each of these asks the same
question: which registrations does this frame use?

That set is the frame's [image](glossary.md#image). An image selects registrations; a
frame is a running instance that looks its handlers up in that selection. One image
can back several frames, and two images can each contain a registration with the same
id.

## The default image

Here is ordinary re-frame2, with no image in sight:

```clojure
(ns app.todos
  (:require [re-frame.core :as rf]))

(rf/reg-event :todo/add
  (fn [{:keys [db]} [_ title]]
    (let [id (inc (apply max 0 (keys (:todos db))))]
      {:db (assoc-in db [:todos id] {:id id :title title :done? false})})))

(rf/reg-sub :todo/todos
  (fn [db _] (:todos db)))
```

Those `reg-*` forms don't run anything. They add entries to the
[registrar](glossary.md#registrar), the process-global table of every
[registration](glossary.md#registration), each tagged with the namespace it was
written in (its provenance, `:rf.provenance/*`).

A frame created without naming an image looks up every id in the whole registrar.
That selection of everything is the **default image**. In code it is simply a frame
created with no `:images` key. New registrations are picked up as soon as you write
them, and hot reload keeps working. You name an image only when "everything that's
loaded, with globally unique ids" is not what a frame should run.

??? info "Coming from JavaScript modules?"

    Think of the registrar as your full set of module exports, and the default image as
    `import * from` everything. You don't normally think about it, until two modules
    export the same name and you need to say which one a particular consumer gets.

## When two registrations collide

The default image works only while ids are unique across everything that's loaded.
When two loaded namespaces register the same `(kind, id)` with different
implementations (two surfaces that both define `:todo/add`, say), the frame fails to
assemble with `:rf.error/image-duplicate-id`, naming the kind, the id and both source
namespaces.

The registrar keeps both registrations, and assembly will not guess which one should
win. You have three ways out, each covered below: rename one id, narrow each frame to
its own slice with an explicit image, or compose a later image that overrides the
other.

??? info "From re-frame v1"

    In v1 the second `reg-event` of an id replaced the first, and you found out when the
    wrong handler ran. re-frame2 keeps both and stops at assembly with a named error,
    before any event touches state ([fail loud, not silent](glossary.md#fail-loud-not-silent)).

## Naming an image: `rf/image`

`rf/image` builds an image value, and you hand it to a frame through `:images`:

```clojure
(def todos-image
  (rf/image {:select-ns {:include ["app.todos"]}}))

(rf/make-frame {:id :app
                :images [todos-image]})
```

`:select-ns` selects existing registrations by the namespace they were written in.
This frame uses only the registrations written in `app.todos`.

Notes:

1. **Building an image registers nothing and runs nothing.** `rf/image` returns plain
   data describing which registrations to select; nothing happens until a frame uses
   it.
2. **Selection is by provenance**, the namespace where a registration was written,
   not by the keyword namespace of its id. `:todo/add` written in `app.todos` is
   selected because of the file it is in, not because its keyword starts with `todo`.
   This one trips people up.
3. **The framework's own feature handlers are always included.** Routing, managed
   HTTP, Resources, SSR and the `:rf/time-ms` coeffect are registered with no source
   namespace, so `:select-ns` cannot pick them. Every explicit composition starts from
   a **framework base** of them (the framework's registrations under the reserved `:rf`
   root, plus the `:route/link` view). A later image can still override them, and the
   shadow report (below) names that base `:rf/framework`.
4. **A registration made through a function alias or generated code** rather than a
   `reg-*` macro has no source namespace either, so `:select-ns` cannot see it and
   only the default image includes it. Add `:ns` to its metadata to make it
   selectable.

??? info "Coming from a bundler's globs?"

    `:select-ns` is like a bundler's `include`/`exclude` globs, except it selects
    already-loaded registrations rather than reading from disk. The namespace must
    already be `require`d through ordinary `ns` dependencies; the glob only chooses
    from what the runtime already knows.

### The three image keys

An `rf/image` spec takes three keys, all optional:

| Key | What it does |
|---|---|
| `:id` | Names the image in diagnostics and in the shadow report. Ids must be unique within one `:images` vector. Anonymous images are fine for tests and one-off examples. |
| `:select-ns` | Selects existing registrations by their source namespace. |
| `:registrations` | Defines registrations inline, inside the image. |

An image with neither `:select-ns` nor `:registrations` is valid and empty, which is
useful as a deliberate "no app registrations" image: `(rf/image {:id :test/empty})`.
Any other key fails with `:rf.error/invalid-image`, so a typo is caught when the image
is built.

### Narrowing the glob: `:include` and `:exclude`

`:select-ns` is a `{:include [...] :exclude [...]}` map. `:include` is required;
`:exclude` is optional and subtracts from the selection.

```clojure
(def page-image
  (rf/image {:select-ns {:include ["docs.*.todos.*"
                                   "docs.shared.widgets.*"]}}))
```

Use `:exclude` when a recursive glob (`**`) picks up namespaces a frame must not use.
The usual case is a feature's test namespaces: in a dev build they are loaded, and
they often re-register the ids the production sources define. Selecting both would be
a collision, so `:include` the feature broadly and `:exclude` its tests:

```clojure
(rf/image {:select-ns {:include ["day8.re-frame2-xray.**"]
                       :exclude ["day8.re-frame2-xray.**.*-cljs-test"
                                 "day8.re-frame2-xray.test-helpers.**"]}})
```

??? note "The glob grammar"

    The grammar is small and case-sensitive, and works on the dotted namespace path:

    - a literal segment matches itself;
    - `*` matches exactly one dot-free segment;
    - `**` matches zero or more segments;
    - a `*` inside a segment matches zero or more characters within that segment,
      never crossing a `.`, so `*-cljs-test` matches the leaf of
      `app.feature.mount-cljs-test`.

    So `docs.shared.widgets.*` matches `docs.shared.widgets.button` but not
    `docs.shared.widgets` (no leaf) or `docs.shared.widgets.forms.input` (two leaves);
    `docs.shared.**` matches all three.

    An `:include` pattern that matches nothing is an assembly error
    (`:rf.error/image-zero-match`), so a typo, a forgotten `require` or a
    dead-code-eliminated namespace fails loudly instead of leaving the frame
    incomplete. An `:exclude` pattern that matches nothing is ignored, so a production
    build that never loads the excluded test namespaces works unchanged. `:exclude`
    applies only to glob-selected registrations, never to inline `:registrations`.

### Defining registrations inline: `:registrations`

Most code should use ordinary `reg-*` forms and select them by namespace. For
generated code, tests or library packaging, where a whole namespace for one fake is
overkill, you can define registrations inside the image. The keys mirror the `reg-*`
names, and each registration is a vector of the arguments you would pass to that
`reg-*` call: the id, an optional metadata map, and the handler fn:

```clojure
(def small-image
  (rf/image
    {:id :test/small
     :registrations
     {:reg-event [[:todo/add
                   {:doc "Add a todo."}
                   (fn [{:keys [db]} [_ title]]
                     {:db (assoc-in db [:todos 1] {:id 1 :title title :done? false})})]]
      :reg-sub   [[:todo/todos
                   {:doc "The todos map."}
                   (fn [db _] (:todos db))]]}}))
```

Inline `:registrations` supports four kinds: `:reg-event`, `:reg-sub`, `:reg-fx` and
`:reg-cofx`, the kinds a test double or generated slice needs. Any other section key
(`:reg-interceptor`, `:reg-view`, `:reg-route`, `:reg-flow`, …) fails loud; write
those in a namespace and select them with `:select-ns`. An inline `:reg-sub` has one
body fn and declares its dependencies in the metadata, so a derived sub works inline
with `{:inputs [[:todo/todos]]}` (or an `:inputs` producer fn). Without `:inputs`, the
body reads app-db as `(fn [db query] …)`. Each entry is parsed by the same code as its
`reg-*` call, so a malformed entry fails the way that call would.

!!! warning "Gotcha: one id cannot come from both `:select-ns` and `:registrations`"

    An image may use both keys, but one `(kind, id)` may not be both selected by
    namespace and defined inline in the same image. That is an error
    (`:rf.error/image-within-image-collision`), not an override. To make an inline
    definition win over a selected one, put it in a later image (see
    [Overriding a registration is a later image](#overriding-a-registration-is-a-later-image)).

## Registration ids and frame ids

Two examples on one page can share ids because there are two id spaces with
different scopes:

| Id space | Example | Scope | Rule |
|---|---|---|---|
| Registration ids | `:todo/add`, `:todo/todos` | the resolved image | reusable across images; unambiguous within one image |
| Frame ids | `:todos/work`, `:todos/home` | the process's frame registry | unique among live frames |

Two images may both contain a `:todo/add` event. Two live frames may not both use the
id `:app`. So a docs page can reuse the same event and sub ids in every example, while
each mounted example gets its own frame id:

```clojure
(def todos-basic     (rf/image {:select-ns {:include ["docs.todos.basic"]}}))
(def todos-persisted (rf/image {:select-ns {:include ["docs.todos.persisted"]}}))

;; Same ids inside each image (:todo/add, :todo/todos), different behaviour.
;; Distinct frame ids, because frame ids are globally unique.
(rf/make-frame {:id :docs.todos/basic     :images [todos-basic]     :initial-events [[:rf/set-db {:todos {}}]]})
(rf/make-frame {:id :docs.todos/persisted :images [todos-persisted] :initial-events [[:rf/set-db {:todos {}}]]})
```

The reader sees the same ids across lessons instead of `:todo-v1/add`,
`:todo-v2/add`. The image decides what each id means; the frame ids keep the running
instances apart.

??? info "Coming from Redux?"

    An image is the set of reducers and selectors a store runs, as a value you can name
    and compose: `combineReducers` if it returned data instead of a function. The
    difference is that registration names are scoped to the image, so two stores can
    each define `:todo/add` meaning different things without a global collision.

## Choosing between an image and a frame

An image holds behaviour: the registrations. A frame holds state and history: its own
[app-db](glossary.md#app-db), changing as events run. So different behaviour means a
different image, and the same behaviour with different state means the same image in
a different frame.

The shapes you'll meet:

- **Two surfaces on one page.** A todo list and a settings panel that both want
  simple local ids such as `:boot/init`. Give each its own image with disjoint
  `:select-ns` selectors; each frame resolves only its own.
- **An inspection tool beside its target.** [Xray](glossary.md#xray) is itself a
  running surface with its own events, subs and app-db. It runs in its own image and
  frame and inspects the target frame as data, so it never has to coordinate ids with
  the app.
- **Progressive docs examples.** Four versions of the todo list, one per lesson, each
  its own image, all reusing `:todo/add` and `:todo/todos`.
- **A library slice you compose in.** A library ships an image value; you build a
  frame from your image plus theirs.

## Composing images: the later one wins

`:images` is a vector, and order matters. A frame can compose several images and
still runs one resolved result:

```clojure
(rf/make-frame {:id :app
                :images [todos-image routing-image sync-image]})
```

If two images provide the same `(kind, id)`, the later one **shadows** the earlier.
Assembly records each override in the **shadow report**
([next section](#reading-what-a-frame-is-running)), and you decide what to do with
it: assert there are none, assert a known set, or log them.

A collision within one image is still an error: an image must resolve to one
registration per `(kind, id)`. To override, compose a later image.

Before a frame runs an event, its images are resolved into one sealed
[generation](glossary.md#generation): framework registrations added, collisions and
references checked, the result frozen. Every lookup the frame makes goes through that
generation, and a bad composition fails here, with a named error id, before any event
touches state. The ids are listed under [Assembly errors](#assembly-errors).

## Reading what a frame is running

You can ask a live frame what it is running. This is useful in tests, in tools like
Xray, and at the REPL when a composition didn't resolve the way you expected.

`rf/frame-generation` returns the frame's generation as data. The key you will use
most is `:rf.gen/shadows`, the override report: a vector with one entry per
override:

```clojure
(:rf.gen/shadows (rf/frame-generation :app))
;; => [{:registration [:fx :todo.storage/save]   ;; the shadowed (kind, id)
;;      :image        :app/main                  ;; the image the loser was defined in
;;      :shadowed-by  :test/doubles}]            ;; the image of the final winner
```

An empty vector means nothing was overridden, so
`(empty? (:rf.gen/shadows (rf/frame-generation frame)))` asserts that no image
overrode another. A default-image frame has a generation too, and its shadow report is
`[]`. For a target that is not a live frame (an unknown or destroyed frame id),
`rf/frame-generation` throws `:rf.error/frame-no-generation` rather than returning
`[]` or `nil`.

??? note "The rest of the generation, and frame-targeted queries"

    The rest of the generation is data too: `:rf.gen/resolver` (one descriptor per
    `(kind, id)`), `:rf.gen/images` (in `:images` order, later wins), and
    `:rf.gen/kinds` (the kinds present).

    Registrar queries can resolve through one frame's image instead of the global
    registrar, by passing a `{:frame …}` map:

    ```clojure
    (keys (rf/registrations {:frame :app :kind :event}))
    (rf/handler-meta {:frame :app :kind :sub :id :todo/todos})
    ```

    A query map must name exactly one source: `{:frame f …}` for this frame's image,
    or `{:source :store …}` for the process-global registrar. A map naming neither, or
    both, throws `:rf.error/registrar-query-needs-source`, so you never read the global
    registrar by accident. The metadata includes `:rf.provenance/ns`, so when two
    images both define `:todo/add` you can see which one won and where it was written.
    Every read accepts a frame id or a frame value, and throws
    `:rf.error/frame-no-generation` if the target isn't a live frame. When images form
    a chain over one `(kind, id)`, such as `[base override-a override-b]`, every loser
    names the final winner, so an assertion never walks a chain.

## Tests: behaviour is the image, state is the frame

A test fixes both behaviour and state. The image gives you the behaviour; the frame
gives you the state. So to swap in a fake [effect handler](glossary.md#effect-handler),
you don't change the process-global registrar under a running frame; you compose the
fakes as a later image:

```clojure
(def todos-image
  (rf/image {:id :app/main
             :select-ns {:include ["app.todos.**"]}}))

(def test-doubles
  (rf/image {:id :test/doubles
             :registrations
             {:reg-fx [[:todo.storage/save (fn [_ctx _todos] nil)]]}}))  ;; no localStorage

(let [frame (rf/make-frame {:images [todos-image test-doubles]
                            :initial-events [[:rf/set-db {:todos {}}]]})]
  (rf/dispatch-sync [:todo/add "Buy milk"] {:frame frame})
  @(rf/subscribe [:todo/todos] {:frame frame}))
```

The fake goes in a separate, later image because defining it in the same image as the
real `:todo.storage/save` would be a collision.

State setup is a frame concern: `:initial-events` (such as a leading
`[:rf/set-db {…}]`), a restored frame-state value, or setup events. Behaviour setup is
an image concern: select or override registrations before the frame runs. Because the
two are separate, a test can vary one without touching the other.

The frame goes in the `{:frame …}` opts map, the last argument `dispatch-sync` and
`subscribe` accept. It takes a frame value (what `make-frame` returns) or a frame id.
[Frames](frames.md) covers both.

??? info "Coming from Jest mocks or MSW?"

    With `jest.mock` or MSW handlers you mutate a shared module registry and remember
    to reset it between tests. Here the test double is a value you compose into a fresh
    frame, so there is no global to dirty and nothing to reset.

## Overriding a registration is a later image

The test above is the general rule. To override an existing `(kind, id)`, define the
winning registration in a later image and compose. The later image wins, and the
shadow report records what it replaced, so the override is visible in data rather
than hidden in load order:

```clojure
(let [frame (rf/make-frame {:images [todos-image test-doubles]})]
  (:rf.gen/shadows (rf/frame-generation frame)))
;; => [{:registration [:fx :todo.storage/save] :image :app/main :shadowed-by :test/doubles}]
```

An override is always a separate, later image, never a second definition in the same
one.

You cannot override a framework standard such as `:rf/set-db`: that collision fails
assembly with `:rf.error/image-standard-replacement-forbidden`. Two routing
placeholders are the exception, meant to be replaced; see
[Framework standards, and the defaults you're meant to replace](#framework-standards-and-the-defaults-youre-meant-to-replace).

## Hot reload swaps the image, keeps the state

During development the registrar changes every time you save a file. Re-evaluating a
`reg-*` form does not change any running generation. Instead the runtime marks every
image that selects the changed namespace as dirty, resolves new generations, and swaps
them into the affected frames. The frames keep their app-db,
[runtime-db](glossary.md#runtime-db), queues and still-valid subscription caches. You
save a file and the live frames pick up the change without losing state.

To change a frame's composition outright, call `rf/make-frame` again with the same
`:id` and a new `:images` vector. There is no separate reload function:

```clojure
(rf/make-frame {:id :app :images [todos-image routing-image sync-v2-image]})
```

The new vector is assembled into a new generation and installed on the existing
frame. App-db, runtime-db, queues and still-valid subscription caches are kept. Config
other than `:images` is replaced too, as with a Clojure `def`, so pass again anything
you want to keep. You need this only when you want to swap a frame's whole
composition on purpose: a test that points a running frame at a different image
stack, a tool driving a frame through several configurations, or a story swapping one
set of registrations for another.

To see what changed, read `frame-generation` before and after and compare them with
`generation-diff`:

```clojure
(let [before (rf/frame-generation :app)
      _      (rf/make-frame {:id :app :images [todos-image routing-image sync-v2-image]})
      after  (rf/frame-generation :app)]
  (rf/generation-diff before after))
;; => {:added    #{[:event :todo/sync-now]}
;;     :changed  #{[:sub :todo/visible]}
;;     :removed  #{[:event :todo/legacy-sync]}
;;     :retained #{[:event :todo/add] …}}
```

`generation-diff` partitions the `(kind, id)` space four ways, so you can invalidate
only what moved (a sub whose definition didn't change keeps its cache). The reloaded
frame's `:rf.gen/shadows` shows its new override set.

## Advanced

### Assembly errors

Each assembly failure has a named error id you can `catch` and assert on:

| What's wrong | Error id |
|---|---|
| Two selected registrations for one `(kind, id)` from different source namespaces inside one image (except an app registration of a [replaceable default](#framework-standards-and-the-defaults-youre-meant-to-replace)) | `:rf.error/image-duplicate-id` |
| An inline entry colliding with a selected one, or two inline entries, in one image | `:rf.error/image-within-image-collision` |
| An `:include` glob that matches no loaded source namespace | `:rf.error/image-zero-match` |
| Two images sharing an `:id` in one composition | `:rf.error/image-duplicate-image-id` |
| An app registration colliding with a protected framework standard | `:rf.error/image-standard-replacement-forbidden` |
| A retired or unknown key in an `rf/image` spec | `:rf.error/invalid-image` |
| `:images []`, or a non-vector `:images` | `:rf.error/make-frame-bad-images` |

`:images []` is an error because an empty vector almost always means "I meant to put
images here and forgot". For a frame with no app registrations, pass one empty image:
`(rf/make-frame {:images [(rf/image {:id :test/empty})]})`.

A reload runs the same checks, and the swap is all-or-nothing: a bad new composition
fails with the same error ids and leaves the frame on its existing generation.
Reloading one frame never changes a sibling that shared its generation, and the old
generation is never mutated. Only a frame with an `:id` can be re-made this way; a
frame created without one is a local object, so discard it and make a new one.

### The default image is a sealed generation

`make-frame` with no `:images` key resolves a sealed default generation over the whole
registrar, framework standards included. It goes through the same assembly checks as
an explicit selection, which is why the default path also fails on a cross-namespace
duplicate id instead of letting load order pick a winner. The one exception is a
replaceable framework default, described next: when your app registers the same id,
the framework's own copy is dropped.

### Framework standards, and the defaults you're meant to replace

Not everything the framework registers is a standard, and the difference decides
whether your registration of the same id is an error or the documented recipe.

- A **framework standard** implements how a frame executes registrations:
  `:rf/set-db`, the interceptor algorithm, queue ordering, app-db commit semantics.
  An app registration of the same id fails assembly with
  `:rf.error/image-standard-replacement-forbidden`. There is no opt-in.
- A **replaceable framework default** is the framework's placeholder for a decision
  your application makes, registered so the feature works when you register nothing.
  There are two: `:rf.route/entry-denied` and `:rf.route/navigation-blocked`. Both are
  no-ops, so a `:can-enter` denial or a `:can-leave` block always has a handler, and
  the [auth recipe](../routing/how-to/require-sign-in-on-a-route.md) has you register
  your own. The framework marks them with the reserved `:rf/framework-default?`
  metadata key.

So `(rf/reg-event :rf.route/entry-denied …)` in your own namespace is not a
duplicate-id collision. Once your registration exists, assembly drops the framework's
copy. Two details:

- Two app registrations of one framework-default id are still
  `:rf.error/image-duplicate-id`, like any other duplicate.
- Putting `:rf/framework-default?` on your own registration does nothing. The key
  counts only on a registration with no source namespace, which is how the
  framework's own copy is identified, and a `reg-*` macro always records the namespace
  it was written in.
