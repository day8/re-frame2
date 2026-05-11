# Pattern — NineStates

A page-level rendering convention that makes every legal UI state explicit and testable. The page's render axes are modelled as **parallel regions** of a single `reg-machine`, each region's states carry **tags**, and one **selector sub** consults a **render-priority** table to pick the single render-model keyword the root view's `case` branches on.

## When to load this leaf

The prompt mentions: "nine states", "empty / one / some / too-many", "loading vs error vs success render", "page-level rendering states", "render priority", or a page that needs to render the cardinality of its loaded data distinctly. Also load this leaf when the user wants to combine a data lifecycle with a form lifecycle and a read-only mode in one screen and is asking "where does each state go?".

## The re-frame2 features that implement it

The pattern composes four primitives. Knowing which feature does what is the load-bearing knowledge — the pattern itself is just discipline.

- **`:type :parallel` on a `reg-machine`** — declares the machine has independent **regions** active simultaneously. The snapshot's `:state` is a map keyed by region name. Use one region per orthogonal axis (typically `:data`, `:form`, `:mode`).
- **`:regions {...}`** — each region is a full state-tree (`:initial`, `:states`, optional `:always` / `:after` / `:invoke`). Transitions are **broadcast** to every region; the run-to-completion drain settles every region before commit.
- **Shared `:data`** — one `:data` map on the machine, read and written by every region. Region keys never collide; the regions slice into the same blob.
- **`:tags #{...}` on states** — every state may declare a set of tag keywords. The runtime maintains `(:tags snapshot)` as the union across every active region's active state's tags.
- **`:rf/machine-has-tag?` framework sub** — answers `(rf/has-tag? :machine-id :some/tag)` as a plain boolean reaction, so views can ask tag-shaped questions without naming any state-keyword.
- **The render-priority vector + one selector sub** — *application code*, not framework. A vector of `{:tag :render}` pairs consulted in order; the selector sub returns the first `:render` whose `:tag` is in the snapshot's tag union. The priority lives in **data**, not in a `cond` in the root view.

The single rule: declare the axes as regions; tag each state with its axis-level intent; resolve the render selection in **one** selector sub over a data-shaped priority table.

## Canonical declaration

Lifted from `examples/reagent/nine_states/core.cljs`. Three regions; tags on every state; an eventless `:always` cascade picks the cardinality bucket; the priority table is plain data.

```clojure
(rf/reg-machine :ui/nine-states
  {:type :parallel
   :data {:items [] :error nil}
   :guards
   {:empty?    (fn [d _] (zero?  (count (:items d))))
    :one?      (fn [d _] (= 1    (count (:items d))))
    :too-many? (fn [d _] (> (count (:items d)) too-many-threshold))}
   :actions
   {:set-items (fn [d [_ {:keys [items]}]] {:data (assoc d :items (vec items) :error nil)})
    :set-error (fn [d [_ {:keys [failure]}]] {:data (assoc d :error failure)})}
   :regions
   {:data
    {:initial :nothing
     :states
     {:nothing   {:tags #{:data/nothing} :on {:fetch-started :loading}}
      :loading   {:tags #{:data/loading :data/transient}
                  :on   {:fetch-succeeded {:target :resolving :action :set-items}
                         :fetch-failed    {:target :error     :action :set-error}}}
      :resolving {:always [{:guard :empty?    :target :empty}
                           {:guard :one?      :target :one}
                           {:guard :too-many? :target :too-many}
                           {:target :some}]}
      :empty     {:tags #{:data/empty}    :on {:fetch-started :loading}}
      :one       {:tags #{:data/one}      :on {:fetch-started :loading}}
      :some      {:tags #{:data/some}     :on {:fetch-started :loading}}
      :too-many  {:tags #{:data/too-many} :on {:fetch-started :loading}}
      :error     {:tags #{:data/error}    :on {:fetch-started :loading}}}}
    :form
    {:initial :neutral
     :states
     {:neutral   {:tags #{:form/neutral}
                  :on   {:submit-valid :correct :submit-invalid :incorrect :edit :neutral}}
      :incorrect {:tags #{:form/invalid}
                  :on   {:submit-valid :correct :edit :neutral}}
      :correct   {:tags #{:form/success :form/transient}
                  :on   {:edit :neutral}}}}
    :mode
    {:initial :active
     :states {:active {:tags #{:mode/active} :on {:archive :done}}
              :done   {:tags #{:mode/done :mode/read-only :mode/terminal}}}}}})
```

The render-priority table and selector sub:

```clojure
(def render-priority
  [{:tag :mode/done     :render :done}
   {:tag :form/success  :render :correct}
   {:tag :form/invalid  :render :incorrect}
   {:tag :data/loading  :render :loading}
   {:tag :data/error    :render :error}
   {:tag :data/nothing  :render :nothing}
   {:tag :data/empty    :render :empty}
   {:tag :data/one      :render :one}
   {:tag :data/some     :render :some}
   {:tag :data/too-many :render :too-many}])

(rf/reg-sub :ui/render
  :<- [:rf/machine :ui/nine-states]
  (fn [snap _]
    (let [tags (:tags snap)]
      (some (fn [{:keys [tag render]}]
              (when (contains? tags tag) render))
            render-priority))))
```

The root view branches once, in a `case`, on the resolved keyword. Disabled-attribute toggles read tags directly via `(rf/has-tag? :ui/nine-states :mode/read-only)` rather than going through `:ui/render`.

## Canonical rules — short form

1. **Identify the orthogonal axes** (typically `:data` × `:form` × `:mode`). Do not flatten them into one enum — the cross-product explodes.
2. **One region per axis** under `:regions` of one `:type :parallel` machine. Regions share `:data`; transitions are broadcast.
3. **Tag every state with its axis-level intent** (`:data/loading`, `:form/invalid`, `:mode/done`, ...). Tags let views ask query-shaped questions without enumerating state-keywords.
4. **Render selection lives in one selector sub** over a render-priority **vector** (not a priority `cond` in the view). The priority is data — printable, testable, reviewable.

## Common variations

- **Fewer axes.** A static settings panel may only have the `:data` axis. Drop the regions that don't apply; the render-priority vector loses the corresponding entries. The pattern scales down to one region as readily as it scales up.
- **More axes.** A CRUD panel may add a per-row `:permissions` axis with `:editable` / `:read-only` states. New region + new priority entry + new view branch.
- **Skip `:correct`.** If success navigates away immediately and there is no visible acknowledgement, the `:form` region collapses to two states (`:neutral ↔ :incorrect`) and the priority table simply never produces `:correct`.
- **Skip the `:mode` region.** If the page can never become read-only / archived / frozen, drop it entirely.
- **Worked HTTP integration.** When the `:data` region's lifecycle is fed by a real HTTP request, the `:rf.http/managed` fx's `:on-success` / `:on-failure` dispatches map directly onto `:fetch-succeeded` / `:fetch-failed`. See the spec for the cancellation-cascade and stale-detection composition.

## Worked example

`examples/reagent/nine_states/core.cljs` — the full machine declaration, render-priority table, `:ui/render` selector, per-state views, and headless tests per state. Read it in full for the implementation-as-shipped shape, including how the form slice composes with the `:form` region.

## Pillar 5 — why this shape vs. a `cond` in the view

The render-priority **vector** is the load-bearing move. A priority `cond` in the root view encodes the same priority in **control flow** (clause order); the vector encodes it in **data**. The data shape lets a test pretty-print it, a code review compare two pages side-by-side, and a tooling pass read the priority without parsing the view's body. The selector sub re-runs only when the tag union changes; Reagent dedups on the resolved keyword; the root view re-renders only when the winning render changes — not every time a non-winning region advances.

## Deeper pointers

- Spec: `SKILL-REDIRECT.md` → *Pattern — Nine states* (full rules, transition tables, managed-HTTP integration, render-priority during mid-flight cross-region overlaps).
- Substrate: `SKILL-REDIRECT.md` → *EP — State machines (005)* (§Parallel regions, §State tags).
- Lifecycle composition: `patterns/remote-data.md` (the `:data` region) and `patterns/forms.md` (the `:form` region).

---

*Derived from `examples/reagent/nine_states/core.cljs` @ main `89bd9c3`. Re-verify after substantial reshape of the nine-states example.*
