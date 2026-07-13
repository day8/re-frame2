# DRAFT — The Reagent → `re-frame.ui` migrator: rewrite-rules catalogue (W1)

> **Status: DRAFT — not merged · 2026-07-12.** The rules catalogue the S6 W1 migrator
> implements. Written the night the template grammar, props ABI, and body-form set
> froze (S1 merged): every after-form below is checked against the shipped analyzer
> (`implementation/ui/src/re_frame/ui/compiler/analyze.cljc`) and header grammar
> (`implementation/ui/src/re_frame/ui/compiler/header.cljc`) — **rule outputs are
> accepted forms**, with stage notes where committed *behaviour* (not grammar) lands
> later (S3 handlers/local/effect). Consumers: the W1 migrator bead (08 §2 Stage-6
> deliverable; 10 Verdict), the repo's own examples/testbeds migration (its first
> consumer), the W2 migration-skill extension (the skill teaches the D/R tiers this
> catalogue flags — 11 W1 "ships with the skill"; generic-to-any-app, repo examples as
> illustrations only), and the 004A migration page. Sources are cited read-only with `⟨source⟩`
> tags: `10` = [../10-migration-from-reagent.md](../10-migration-from-reagent.md),
> `02` = [../02-programming-model.md](../02-programming-model.md), `004-rw` =
> [spec-004-rewrite-draft.md](spec-004-rewrite-draft.md), `boundary` =
> [reagent-compat-boundary.md](reagent-compat-boundary.md), `analyze`/`header`/`rules` =
> the shipped S1 compiler namespaces, `ex:*` = real Reagent source in this repo's
> `examples/` and `tools/` trees. House style: British "serialisable".

## 0. How to read a rule

Each rule carries:

- **id** — `R-<area><n>`; areas: C components · S state · T templates · H handlers ·
  Q subscriptions · M mount/roots · X residue.
- **class** — **AUTO** (fully mechanical; the migrator rewrites without judgment),
  **GUIDED** (mechanical rewrite + a *named* human check the migrator prints),
  **MANUAL** (flagged, never rewritten; every MANUAL rule names its compat-tier
  escape so migration is never all-or-nothing). ⟨10 headline⟩
- **before** — a real Reagent form from this repo.
- **after** — a compiling `re-frame.ui` form.
- **trap** — the concrete failure the rule's shape avoids.

MANUAL rules have no mechanical **after** by definition — for them the pair is
replaced by **pattern** (what the detector matches) + the **restructure menu** + the
**compat escape**; §7's residue table is their canonical form, and its closing
paragraph is the shared detector trap. Every AUTO and GUIDED rule carries the full
five fields.

Two standing facts every rule leans on:

1. **The dataflow layer does not change.** Events, subs, fx, machines, schemas,
   routes, resources are tier-independent; only rendering migrates. A rule never
   touches a `reg-event`/`reg-sub` body except to *add* a registration (R-S2/R-S3).
   ⟨10 Status, boundary §1⟩
2. **The compiler is the migrator's safety net.** Every rewrite the catalogue marks
   AUTO lands on grammar the analyzer *checks* — a wrong output is a didactic compile
   error (`:rf.ui.compile/*`), not a silent behaviour change. The migrator treats the
   compile-error roster as its own acceptance oracle. ⟨analyze, 004-rw §Template grammar⟩

Ordering: run C-rules first (they establish the `defview` bodies the T/H/Q rules
rewrite inside), then S, then T/H/Q per body, then M once per root. R-X flags are
emitted throughout.

---

## 1. Component forms

### R-C1 · Form-1 fn, single map argument → `defview` — AUTO

**before** ⟨ex: `examples/core/todomvc/views.cljs` `todo-item` header shape⟩

```clojure
(defn todo-card [{:keys [id title completed]}]
  [:li ...])
```

**after**

```clojure
(ui/defview todo-card [{:keys [id title completed]}]
  [:li ...])
```

Header synthesis is verbatim when the one argument is already a map-destructuring
form: the shipped header grammar accepts `:keys`, namespaced `:<ns>/keys`, `:or`,
`:as`, and `{pattern :prop-key}` entries — exactly Clojure's map destructuring minus
`:strs`/`:syms` (outside the props ABI — slots are keywords; the migrator flags those,
GUIDED). `:or` defaults apply iff the slot is absent, mirroring Clojure. ⟨header Q2 pins⟩

**Call-site sub-cases** (the analyzer requires a **literal** props map at component
call sites — `:rf.ui.compile/dynamic-props-map`):

- caller passes a literal map — `[todo-card {:id 1 :title "x"}]` — unchanged, AUTO.
- caller passes a map **value** — `[todo-item todo]` ⟨ex: todomvc `task-list`⟩ — the
  migrator re-headers to one named slot and wraps the call:

  ```clojure
  (ui/defview todo-item [{:keys [todo]}]
    (let [{:keys [id title completed]} todo] ...))
  ;; call site:
  [todo-item {:key id :todo todo}]
  ```

  Still AUTO: the slot name is the fn's parameter name; memoisation is unharmed —
  `rf=` compares CLJS data by value, so the whole-map slot compares by value.
  ⟨004-rw §`ui/defview` rf= ruling, analyze `analyze-component-props`⟩

**trap** — positional call sites silently passing a non-map (Reagent renders it;
`defview` has no positional args at all — `:rf.ui.compile/positional-args` fires on
the definition, `dynamic-props-map` on the call). The rule converts both ends in one
change so no intermediate state exists. ⟨10 Tier M, 004-rw §Removed forms⟩

### R-C2 · Form-1 fn, multiple positional arguments → `defview` + call-site map — GUIDED

**before** ⟨ex: todomvc `filter-link`⟩

```clojure
(defn- filter-link [showing filter-kw label]
  [rf/route-link {:to    (filter->route filter-kw)
                  :class (when (= showing filter-kw) "selected")}
   label])
;; call site (function call producing hiccup):
[:li (filter-link showing :all "All")]
```

**after**

```clojure
(ui/defview filter-link [{:keys [showing filter-kw label]}]
  [route-link {:to    (filter->route filter-kw)
               :class (when (= showing filter-kw) "selected")}
   label])
;; call site:
[:li [filter-link {:showing showing :filter-kw :all :label "All"}]]
```

Positional parameter names become prop keys, in order; **every call site in the
codebase rewrites in the same change** — including round-paren call shapes
(`(filter-link …)` inline function calls) which become square-bracket view sites.
**Named human check:** parameter names become public prop names — review them (a
parameter named `x` or `props2` deserves a rename before it becomes ABI), and confirm
no call site computes the argument list dynamically (`apply` — flag MANUAL).
⟨10 Tier M "two rules need care", 02 §1⟩

**trap** — half-converted call sites: Reagent tolerated `[my-view a b]` and
`(my-view a b)` interchangeably; after conversion a missed site is a compile error
(literal-props-map requirement), never a silently-nil prop.

### R-C3 · Form-2 `(let [x (r/atom …)] (fn …))` → `defview` + `local` — GUIDED

**before** ⟨ex: `tools/machines-viz/src/day8/re_frame2_machines_viz/viewer.cljs`
`chart-view`⟩

```clojure
(defn- chart-view [props]
  (let [show-idle? (r/atom false)]
    (fn [props]
      [:div
       [:input {:type "checkbox"
                :checked @show-idle?
                :on-change #(swap! show-idle? not)}]
       [chart/MachineChart (cond-> props @show-idle? (dissoc :current-state))]])))
```

**after**

```clojure
(ui/defview chart-view [{:keys [props]}]
  (let [[show-idle? set-show-idle!] (local false)]
    [:div
     [:input {:type "checkbox"
              :checked show-idle?
              :on-change #(set-show-idle! (not show-idle?))}]
     [MachineChart ...]]))
```

Mechanics (all mechanical): outer/inner arg vectors unify (the migrator flags a
Form-2 whose inner fn ignores the outer args — a live Reagent footgun — and unifies
on the inner vector); each `(r/atom init)` binding becomes the `[value set!]` tuple
`(local init)`; derefs of the atom drop the `@`; `(reset! a v)` → `(set-a! v)`;
`(swap! a f args)` → `(set-a! (f a args))` reading the committed value in scope.
⟨02 §5, 10 Tier D⟩

**The narrow local law makes the converted handlers legal as-is:** a same-view
committed handler MAY read a `local` value — handlers read committed slots, and a
view's local ephemera are committed slots of that view. The bare fn stays legal
because `:on-change` is a known native event property (the narrow bare-fn law).
⟨02 §5 placement law (ruled 2026-07-12), 004-rw §Local state, 02 §3⟩

**Named human check (the doc-10 decision):** is this state genuinely keystroke-latency
view ephemera? If the value needs cross-view observation, replay/persistence, schema
or tool inspection, durable navigation semantics, or subscription-derived computation,
it is forbidden from `local` and goes to app-db behind an event instead — the migrator
converts to `local` and prints the check with the forbidden-tier list. ⟨02 §5, 10 Tier D⟩

**trap** — the classic Form-2 bugs die in conversion: the stale-outer-args render
(inner fn closing over first-render args) cannot be expressed; and state with product
meaning hiding in a ratom gets a named decision instead of silently surviving as
invisible-to-tools state. Grammar note: `local` is S3 *behaviour*; the template
analyzer already accepts the form (an ordinary call in expression position —
value-opaque, lexically audited), so W1 output compiles today
and animates when S3 lands — moot in practice, since the migrator itself is S6.

### R-C4 · `r/with-let` → `local` / `effect` split — GUIDED

`with-let` fuses two things `re-frame.ui` deliberately separates: bindings that live
across renders (→ `local`) and a `finally` cleanup (→ `effect` cleanup, or better).

**before** ⟨ex: `examples/patterns/long_running_work/views.cljs` `work-bench`⟩

```clojure
(rf/reg-view work-bench []
  (r/with-let [_ nil]
    [:div.work-bench ...]
    (finally
      (dispatch [:work/flow [:cancel]]))))
```

**after — the mechanical split** (host-sync cleanups):

```clojure
(ui/defview work-bench []
  (let [dispatch! (ui/dispatch-fn)]
    (effect :connect
      nil
      #(dispatch! [:work/flow [:cancel]]))
    [:div.work-bench ...]))
```

Value bindings in the `with-let` vector become `local` tuples (per R-C3); the
`finally` body becomes the effect's cleanup fn. Exact `effect` arity per the S3
surface (Spec 004 rewrite §Effects); the analyzer treats the site as an ordinary
call in expression position (value-opaque, lexically audited), so the form
compiles from S1.

**Named human checks (two):**

1. **Cleanup semantics shift.** `finally` ran once at unmount; `effect :connect`
   cleanup runs at **each disconnect**, and StrictMode dev replay makes
   connect/disconnect cycles expected — the cleanup must be disconnect-idempotent.
   ⟨004-rw §Effects, 02 §5⟩
2. **Domain work in `finally` should usually move upstream.** The work-bench's
   cancel-on-unmount is *domain* work riding a mechanical lifecycle — the prescribed
   re-frame2 shape is to attach the domain consequence to the event that *causes* the
   unmount (here `:ui/toggle-bench`), or a route/domain transition. `:on-unmount`
   deliberately does not exist for exactly this reason. The migrator offers the
   mechanical split and prints this check. ⟨02 §1 deliberately-absent, 10 Tier D⟩

**trap** — "once" semantics that were never real: under StrictMode replay, HMR, and
error recovery a `with-let` `finally` could fire at surprising times in Reagent too;
the rewrite forces the cleanup to be named for what it is (per-disconnect) or moved
to the causal event where it is replayable and tool-visible.

### R-C5 · Form-3 / `r/create-class` / class components — MANUAL

Flagged, never rewritten. The residue detector (§7) prints the site with the
decomposition menu: `:component-did-mount`/`-did-update` doing DOM/library work →
`(effect [deps] … cleanup)` with a ref; doing domain work → route/domain event;
`:should-component-update` → delete (memo-by-default covers it); render body →
`defview`. **Compat escape:** the component stays on the frozen Reagent tier
(`reg-view*` for computed ids / Form-3 per the checked-in contract) and mounts inside
migrated trees through `ui/raw` (§7). ⟨10 Tier D lifecycle, boundary §2, 004-rw
§Removed forms⟩

**trap** — mechanically translating lifecycle methods invents semantics the substrate
rejected on purpose; a flag with a menu beats a wrong rewrite.

### R-C6 · `reg-view` / `reg-view*` → `defview` — AUTO

**before** ⟨ex: todomvc⟩

```clojure
(rf/reg-view task-entry []
  [:header.header ...])
```

**after**

```clojure
(ui/defview task-entry []
  [:header.header ...])
```

The registration macro swaps; the id does not move: `reg-view`'s auto-id derivation
`(keyword (str *ns*) (str sym))` **is** the `defview` id rule — one derivation, stated
once each side — so Story scenes, Xray registry queries, and Pair hot-swap targets
keep resolving. `^{:doc …}` metadata becomes the docstring position. The injected
lexical `dispatch`/`subscribe` that `reg-view` provided disappear — their uses rewrite
under R-H1/R-H2/R-Q1 (event vectors and `sub` need no injection; frames arrive by
context). `reg-view*` with a *computed* id: GUIDED (confirm the id is a compile-time
literal for `:id`, else it is a Form-3-adjacent MANUAL flag). ⟨004-rw §`ui/defview`
Registration, boundary §8 retained-rows, 02 §1⟩

**trap** — losing view identity across the migration: tools address views by id;
because the derivation is shared, a converted view keeps its address.

---

## 2. State

### R-S1 · `r/atom` mechanics → the `[value set!]` tuple — AUTO (post R-C3/R-C4 gate)

Once R-C3/R-C4's GUIDED decision has ruled a given atom ephemeral, the operation
rewrites are fully mechanical:

| before | after |
|---|---|
| `@a` | `a` (the value binding) |
| `(reset! a v)` | `(set-a! v)` |
| `(swap! a f x y)` | `(set-a! (f a x y))` |
| `(swap! a not)` | `(set-a! (not a))` |

The read in the `set-a!` argument is the committed render's value — in handlers that
is exactly the committed-slot promise, and same-view handler reads of `local` are
legal (the narrow local law). `set!` during render is a dev error
(`:rf.warning/render-phase-set!`) — a render-phase `swap!` (rare, always a bug) gets
flagged MANUAL rather than rewritten. ⟨02 §5, 004-rw §Local state⟩

**trap** — compare-and-set/atomic-update idioms (`swap!` racing between handlers)
have no `local` equivalent by design: state updated from more than one causal path
is product state; the migrator flags `swap!` sites whose atom is touched by more
than one handler as the app-db check.

### R-S2 · `reaction` / `r/track` → registered sub + `(sub …)` — GUIDED

**before** (the taxonomy shape; no live example survives in `examples/` — the repo's
own guidance already routed derivations to subs ⟨ex: realworld_resources
`article_editor.cljs` docstring contrasts "Form-3 `reagent.ratom/run!` reactions"⟩)

```clojure
(def visible (r/track #(filter :active? @all-items)))
;; view: @visible
```

**after**

```clojure
;; dataflow layer (new registration — the one place a migration ADDS dataflow):
(rf/reg-sub :items/visible
  :<- [:items/all]
  (fn [all _] (filter :active? all)))
;; view body:
(sub [:items/visible])
```

Usually a copy-paste of the compute fn into `reg-sub`, where it gains caching, Xray
visibility, and headless testability. **Named human check:** the sub id (a new public
name in the app's query vocabulary) and the input wiring (`:<-` chain vs db fn).
⟨10 Tier D, 02 §4⟩

**trap** — a `track` capturing component-local values cannot become a global sub
verbatim; the migrator parameterises captured values into the query vector and flags
any capture that is itself a ratom (that is R-X3 territory).

### R-S3 · `r/cursor` → sub over a path (+ writes become events) — GUIDED

Read side: `(r/cursor state [:a :b])` → a layer-1 extractor sub
`(rf/reg-sub :a/b (fn [db _] (get-in db [:a :b])))` and `(sub [:a/b])` at the view.
Write side: `(reset! the-cursor v)` → a registered event `[:a/set-b v]`. **Named
human check:** cursor writes were silent state mutation from anywhere; each write
site needs an event name (that is the point). ⟨10 Tier R adjacency, 02 §4⟩

**trap** — cursors over the *frame's own app-db mirror* (v1 apps sometimes cursor
into a ratom that mirrors app-db) create a second write path; the event rewrite
collapses it back to one.

### R-S4 · `add-watch` on ratoms — MANUAL

Flagged with the site and the watcher body. Watchers are render-phase-adjacent side
effects with no home in the substrate: state-change reactions belong to events (the
handler that made the change carries the consequence), external-world sync belongs to
`effect`/registered fx. **Compat escape:** the watching component stays on the frozen
tier until restructured. ⟨10 Tier R, 004-rw §Removed forms "a second state model"⟩

**trap** — a mechanical watcher→effect rewrite silently changes *when* the body runs
(watchers fired synchronously mid-swap!, before render; effects run after commit) and
loses multi-writer ordering — behaviour changes no diff review would catch. Flagging
forces the causal-event restructure instead.

---

## 3. Templates

### R-T1 · Tag sugar `:div.cls#id` — AUTO (no-op, plus two new checks)

Hiccup tags, `.class`/`#id` sugar (either order), `:style` maps, `:class`
string/vector/flag-map all carry over verbatim — this is the point of keeping hiccup.
Two spots the analyzer is stricter than Reagent, auto-fixed where provable:

- `#id` sugar **and** an `:id` prop on one element → keep one
  (`:rf.ui.compile/id-sugar-conflict`).
- two `#id` segments → keep one (`:rf.ui.compile/duplicate-id-sugar`).

⟨10 Tier M, analyze `parse-tag`/`analyze-element-props`⟩

**trap** — none to avoid; the rule exists so the migrator's diff is empty here and
reviewers can trust "no change" means "no change".

### R-T2 · `^{:key k}` metadata → `{:key k}` prop — AUTO

**before** ⟨ex: todomvc `task-list`⟩

```clojure
(for [{:keys [id] :as todo} @(subscribe [:todo/visible-todos])]
  ^{:key id}
  [todo-item todo])
```

**after** (composed with R-C1's named-slot wrap and R-Q1)

```clojure
(for [{:keys [id] :as todo} (sub [:todo/visible-todos])]
  [todo-item {:key id :todo todo}])
```

Key metadata has no home in the compiled grammar; the props-map `:key` feeds React's
key slot and is reserved (never a prop the view sees). Note the `sub` in the for's
**first collection position is legal** — it evaluates once per render (the in-loop
rejection applies to later collections, modifiers, and the body). ⟨02 §2, header
`:key` reservation, analyze `analyze-for`⟩

**trap** — keys that silently vanished when a component was wrapped (`^{:key …}` on
the wrong form) — the compiled grammar fails the build if the for body ends up
unkeyed, so a mis-moved key cannot ship.

### R-T3 · Missing `:key` on list items → synthesise from a unique field — GUIDED

Unkeyed `for` bodies are a build failure (`:rf.ui.compile/unkeyed-list-item`), and a
*constant* key is too (`:rf.ui.compile/constant-list-key` — a constant guarantees
duplicates). The migrator proposes `{:key (:id x)}` when the row map has an `:id`-like
field. **Named human check:** the migrator cannot know the identity field — the human
confirms which field is stable-and-unique per row (index keys are the classic wrong
answer and the migrator never proposes them). ⟨analyze `analyze-for`, 02 §2⟩

**trap** — Reagent's console-warning-then-reconcile-by-index behaviour becoming
committed-slot corruption; here identity is compile-enforced before the first render.

### R-T4 · Lazy-seq / `map` children → `for` — AUTO (inline fn) / GUIDED (fn value)

The analyzer rejects the whole markup-generating seq family in child position:
`map`/`map-indexed`/`mapcat`/`keep`(`-indexed`) (`:rf.ui.compile/markup-returning-map`)
and the raw-seq producers `filter`/`take`/`concat`/`sort-by`/… in rendered-content
positions (`:rf.ui.compile/lazy-seq-child`). Both errors name the escape; the
migrator applies it:

```clojure
;; before:                                      after:
(map (fn [t] [toast {:t t}]) toasts)        (for [t toasts] [toast {:key (:id t) :t t}])
(doall (for [x xs] ...))                    (for [x xs] ...)      ; doall wrapper stripped
```

AUTO when the mapped fn is an inline literal `fn`/`#(…)` (its body inlines as the
`for` body, then R-T3 keys it). GUIDED when the fn is a var or `comp`/`partial` value
(the migrator inlines a call — `(for [x xs] [row-view {:key … :x x}])` — but the human
confirms the var was a view, not a data transform). Seq heads stay legal in *expression*
positions (prop values, `for` collections, `if` tests) — ordinary function calls
are always accepted there; only rendered-content
positions rewrite. Chained transforms move into the collection position:
`(map f (filter p xs))` → `(for [x (filter p xs)] …)`. ⟨analyze markup-map/lazy-seq
rosters, 02 §2⟩

**trap** — laziness and key-blindness: a lazy child seq derefing subs outside the
render tracking scope was Reagent's classic heisenbug; the compiled grammar makes the
whole class unrepresentable.

### R-T5 · Keyword in child position → string coercion check — GUIDED

`[:td status-kw]` where the value is a literal keyword is a compile error
(`:rf.ui.compile/keyword-child` — keywords are element heads, not content). The
migrator proposes the coercion but the human picks WHICH: `(name status)` (renders
`"active"`) vs `(str status)` (renders `":active"`) — the two differ visibly and the
source cannot say which was meant. Non-literal expressions that *evaluate* to
keywords are runtime content and pass through untouched (they stringify at render).
⟨analyze `analyze`, 02 §2⟩

**trap** — a keyword head that was *meant* as a dynamic tag (`[status-kw …]` with
children) is a different bug — that is R-T6, and the migrator distinguishes by
position (head vs child).

### R-T6 · Dynamic tag heads — MANUAL (two-branch rewrite offered; wave-2 caveat)

```clojure
;; before (rejected: :rf.ui.compile/dynamic-head):
[(if big? :h1 :h3) {:class "title"} text]
```

Flagged, with the two-branch rewrite offered as the copy-paste fix:

```clojure
(if big? [:h1 {:class "title"} text]
         [:h3 {:class "title"} text])
```

Small closed tag sets take the branch rewrite (or `case`); genuinely runtime-chosen
heads have **no v1 authoring form** — `ui/element`/`ui/view` are wave-2,
demand-gated, so the flag says so honestly: bridge via `ui/raw` (a runtime React
element) or keep the subtree on the frozen tier; `re-frame.ui.data` is the answer for
genuinely data-driven UI (CMS trees). MANUAL because the branch explosion is a design
choice, not a transform. ⟨10 Tier R, 02 §6 wave-2 rows, 004-rw §Interop⟩

**trap** — auto-generating a 30-branch `case` over a tag table nobody reviews;
the flag forces the "is this actually data-driven UI?" question.

### R-T7 · `[:> ReactComp props]` / `adapt-react-class` → foreign head — AUTO

```clojure
;; before:                          after:
[:> DatePicker {:selected d}]       [DatePicker {:selected d}]
[(r/adapt-react-class Grid) {...}]  [Grid {...}]
```

Foreign heads are direct: open props, JS values pass through, no adapter call. The
props map itself must be literal (a computed props map at a component boundary is
`:rf.ui.compile/dynamic-props-map` — flag GUIDED, hoist into named props). Prop-name
conversion note: Reagent's `[:>` camelised kebab props; the foreign boundary passes
names through — the migrator keeps whatever spelling the JS component's API declares
(usually already camelCase in source). Fn-valued props at the boundary hit R-H6.
⟨10 Tier M, 02 §2/§6⟩

**trap** — double-conversion: running Reagent's `convert-prop-value` habits (auto
camelising) against a boundary that deliberately does not convert.

### R-T8 · Computed DOM props maps (`merge`/`dissoc` idioms) → `ui/spread` — GUIDED

**before** ⟨ex: todomvc `todo-input`⟩

```clojure
[:input (merge (dissoc props :draft :on-change :on-commit :on-cancel :autofocus?)
               {:type "text" :value (or draft "") ...}
               (when autofocus? {:ref ...}))]
```

**after**

```clojure
[:input (ui/spread passthrough {:type "text" :value (or draft "") ...})]
```

`ui/spread` is the one generic runtime prop-map conversion, DOM elements only
(component call sites stay literal-map — the analyzer enforces both). **Named human
check:** a spread site forfeits static analysis (manifest marks it `:dynamic`; the
controlled-input synchrony door needs a provable literal `:value` co-present, which a
spread hides) — the migrator asks the human to shrink the spread to genuinely
pass-through props and lift `:value`/handlers back to literals. The conditional-`:ref`
arm moves out of the map into an explicit prop with R-H5's `ui/raw-fn`. ⟨02 §2/§3
synchrony predicate, analyze `analyze-element-props`, 004-rw §Interop `ui/spread`⟩

**trap** — losing the sync-door on a controlled input by burying `:value` in a merged
map: caret jumps and dropped characters that only appear under load. The named check
exists to keep text inputs on the literal path.

### R-T9 · Prop-spelling normalisation — AUTO

The migrator applies the pinned DOM spelling table: hyphenated lowercase mirroring
React's camelCase — `:on-click`, `:on-key-down`, `:on-double-click` (never
`:on-keydown`). Rejected spellings rewrite from the shipped map: `:class-name` →
`:class`, `:html-for` → `:for`, `:dangerouslySetInnerHTML`/`:inner-html` →
`(ui/html s)` (the explicit trusted-markup spelling — the migrator flags the *string
source* for review even though the rename is AUTO), `:children` as a prop →
positional children. CamelCase leftovers (`:readOnly`) → kebab (`:read-only`); the
compiler's name table resolves recognised names from kebab or collapsed forms, so the
normalised spelling is always accepted. Collection values on non-`:class`/`:style`
attributes → `(str/join " " xs)` (`:rf.ui.compile/collection-attr-value`). ⟨rules
`rejected-prop-spellings`/`react-prop-name`, 02 §2⟩

**trap** — `dangerouslySetInnerHTML` silently surviving as an ignored unknown prop;
here it either becomes the visible `ui/html` call or fails the build.

### R-T10 · Multi-form control bodies → single body + fragment — AUTO

Reagent tolerated `(when x [:a] [:b])` (rendering only the last) and side-effecting
`do` bodies; the grammar takes exactly ONE body form per control form
(`:rf.ui.compile/multi-form-body` — "side effects don't belong in templates").
Sibling markup wraps in `[:<> …]`; a side-effect form in a template body is flagged
MANUAL (it was dead-or-worse in Reagent too). Directly-nested `for`s become multiple
binding pairs in one `for` (`:rf.ui.compile/nested-for-body`). ⟨analyze
`single-body!`/`analyze-for`⟩

**trap** — the silently-dropped sibling: Reagent rendered only the last body form and
authors rarely knew; the AUTO fragment wrap preserves what the author *meant* while
the compile error catches what they *wrote*.

---

## 4. Handlers

### R-H1 · Dispatch-only closures → event vectors — AUTO

**before** ⟨ex: todomvc `todo-item`⟩

```clojure
[:button.destroy {:on-click #(dispatch [:todo/delete id])}]
```

**after**

```clojure
[:button.destroy {:on-click [:todo/delete id]}]
```

The overwhelmingly common case. Preconditions the migrator proves before lifting
(all mechanical): the closure body is exactly one `dispatch` of a vector literal
whose head is a literal keyword (the analyzer requires it —
`:rf.ui.compile/bad-event-vector`); the closure ignores its event argument; the site
is a DOM/custom-element `:on-*` position. `(fn [e] (.preventDefault e) (dispatch …))`
lifts to the options map instead:
`{:on-click {:event [:evt …] :prevent-default true}}` — closed vocabulary
`{:event :prevent-default :stop-propagation :capture :passive :once}` (`:passive`/
`:once` are S1-rejected until S3; the migrator emits them only under an S3+ target).
⟨10 Tier M, 02 §3, analyze `analyze-handler`⟩

**Loop sub-case:** a lifted vector that captures the `for` binding
(`[:cart/add (:id item)]` in a row) is a compile error
(`:rf.ui.compile/loop-capturing-handler`) — per-row committed slots need per-row
instances. The fix is R-H7's extract-a-keyed-child-view; the migrator does it in the
same pass when the row body is small, else flags GUIDED. Note todomvc never hits
this: its row handlers live *inside* `todo-item`, already a per-row view — the
already-well-factored codebase converts clean, which is representative. ⟨02 §3 loops⟩

**trap** — closures with a second body form getting "lifted" and losing behaviour:
the single-dispatch proof is the rule; anything else falls through to R-H3/R-H4.

### R-H2 · `%`-extraction closures matching the scalar vocabulary → placeholders — AUTO

**before** ⟨ex: `examples/core/seven_guis/temperature/core.cljs`⟩

```clojure
[:input {:value (or c-text "")
         :on-change #(dispatch [:temp/edit-celsius (.. % -target -value)])}]
```

**after**

```clojure
[:input {:value (or c-text "")
         :on-change [:temp/edit-celsius :rf.ui/value]}]
```

The recognised extraction shapes map 1:1 onto the closed scalar vocabulary
(top-level positions of the literal vector only):

| closure argument usage | placeholder |
|---|---|
| `(.. % -target -value)` / `(-> % .-target .-value)` / `(.-value (.-target %))` | `:rf.ui/value` |
| `(.. % -target -checked)` | `:rf.ui/checked` |
| `(.-key %)` / `(.. % -key)` | `:rf.ui/key` |

`:rf.ui/form-data` and `:rf.ui/event` do not exist — those shapes go to R-H3. The
migrator never emits a placeholder nested inside a collection argument (they splice
top-level only; nested ones dispatch as ordinary keywords — the analyzer warns,
`:rf.ui.compile/placeholder-not-top-level`), and never into a runtime-forwarded
vector (placeholders are compiled — literal vectors only). Bonus: on a controlled
element with a literal `:value`/`:checked` (both examples above), the lifted vector
lands on the synchrony door — dispatch drains synchronously within the DOM event, so
the app-db-round-trip input pattern (temperature's raw-`:typing` echo, todomvc's
drafts) keeps caret/IME correctness by construction. ⟨02 §3, rules `placeholders`,
004-rw §Handlers synchrony law⟩

**trap** — the controlled-input death spiral (async dispatch → stale `:value` →
dropped characters) that made v1 apps hold text in ratoms "for performance": the
door removes the reason the workaround existed.

### R-H3 · Event-mechanics closures (non-scalar payloads, filtering) → `ui/event` — GUIDED

**before** ⟨ex: todomvc `todo-input` `handle-keydown`⟩

```clojure
(fn [event]
  (case (.-key event)
    "Enter"  (do (.preventDefault event) (on-commit))
    "Escape" (do (.preventDefault event) (on-cancel))
    nil))
```

**after** (as rewritten inside the converted `todo-input` view, with commit/cancel
intents arriving as data props — see R-H6)

```clojure
(ui/event [e]
  (case (.-key e)
    "Enter"  (do (.preventDefault e) on-commit)   ; a forwarded event vector
    "Escape" (do (.preventDefault e) on-cancel)
    nil))                                          ; nil ⇒ no dispatch
```

`ui/event` sees committed slots + the live event and returns the vector to dispatch
(nil filters). It is the home for everything the placeholder vocabulary refuses:
form payloads, files, key filtering, composed extraction. GUIDED: the migrator
assembles the body but the human confirms the returned-vector-vs-side-effect split
(imperative work with no dispatch belongs in `ui/handler`). S3 surface; the site
compiles from S1 as a dynamic handler expression. ⟨02 §3 decision table⟩

**trap** — flattening a filtering handler into an unconditional dispatch (Enter and
every other key both dispatching); `ui/event`'s nil-filter contract carries the
conditional structure intact.

### R-H4 · Closures doing local work + dispatch → the split rule — GUIDED

**before** (doc-10's canonical shape)

```clojure
{:on-click #(do (set-open! false) (dispatch [:save id]))}
```

**after — either** (both legal; the migrator proposes (a)):

```clojure
;; (a) stay a bare fn on the DOM site (legal = ui/handler shorthand):
{:on-click #(do (set-open! false) (dispatch! [:save id]))}
;; (b) split: intent as data on the natural element, local work stays a fn
```

Bare fns are legal exactly in known native event properties — invoker and phase are
known; the closure sees the committed render's values, including `local` values (the
narrow local law again). Inside the fn, ambient `dispatch` is gone (no injection):
use the view's `(ui/dispatch-fn)` or lift the intent to a data vector and keep only
the local work in the fn. GUIDED: which element "naturally" owns the intent when
splitting is a human call. Teams wanting explicit-everywhere flip the day-one strict
lint `{:re-frame.ui/bare-handlers :warn|:error}` instead of asking the migrator to
police style. ⟨10 Tier D, 02 §3 bare-fn row + lint, 02 §5⟩

**trap** — "helpfully" splitting a handler whose local work and dispatch were order-
dependent; option (a) preserves exact ordering, which is why it is the default.

### R-H5 · Callback refs → `ui/raw-fn` — AUTO

**before** ⟨ex: todomvc `todo-input` autofocus⟩

```clojure
{:ref (fn [node] (when node (.focus node)))}
```

**after**

```clojure
{:ref (ui/raw-fn (fn [node] (when node (.focus node))))}
```

A bare fn in `:ref` is a compile error (`:rf.ui.compile/bare-fn-ref`): React invokes
callback refs during commit *before* the owning view's layout publication, so no
committed-slot promise can be made — the explicit form marks that. The migrator
wraps mechanically; a ref whose body reads view state (not just the node) gets a
GUIDED downgrade with the object-refs-preferred note. `:ref` at *internal-view* call
sites is S1-rejected (declared forwarding lands S3) — flagged with the stage note.
⟨02 §3 refs, analyze `analyze-ref`⟩

**trap** — a focus ref silently reading a stale slot; the wrapper is the visible
"no committed-slot promise here" marker.

### R-H6 · Fn props handed to (now-)view children → data-vector props — GUIDED

**before** ⟨ex: todomvc `todo-item` → `todo-input`⟩

```clojure
[todo-input {:draft     @(subscribe [:todo.ui/draft :edit])
             :on-change #(dispatch [:todo.ui/edit-field :edit %])
             :on-commit #(dispatch [:todo.ui/commit-edit])
             :on-cancel #(dispatch [:todo.ui/stop-edit])}]
```

**after**

```clojure
[todo-input {:draft     (sub [:todo.ui/draft :edit])
             :on-change [:todo.ui/edit-field :edit]     ; payload appended by the child
             :on-commit [:todo.ui/commit-edit]
             :on-cancel [:todo.ui/stop-edit]}]
```

Once the helper is a `defview`, bare fn props at its boundary are compile errors
(`:rf.ui.compile/bare-fn-prop` — invoker and phase unknown). The blessed migration
shape: **forward the intent as data.** Event vectors are values; the child places a
forwarded vector in its own `:on-*` position, where runtime classification
(vector → dispatch) makes it live. For payload-carrying callbacks the child owns the
extraction at its DOM site — placeholders are recognised in literal vectors only, so
the child builds the literal or uses `ui/event`:

```clojure
(ui/defview todo-input [{:keys [draft on-change on-commit on-cancel]}]
  [:input {:value    (or draft "")
           :on-input (ui/event [e] (conj on-change (.. e -target -value)))
           :on-blur  on-commit
           :on-key-down (ui/event [e] (case (.-key e)
                                        "Enter" on-commit "Escape" on-cancel nil))}])
```

GUIDED: the migrator proposes the vector-prop protocol; the human confirms the
parent/child payload contract (which end appends the payload) and renames props that
no longer take fns if the team wants (`:on-commit` holding a vector is fine — the
name describes the seam, not the type). Fn props that must *stay* fns (a foreign
API's comparator, identity-as-protocol callbacks) → `ui/raw-fn` (AUTO wrap);
render-prop shapes → `ui/render-fn` (GUIDED, S3). ⟨02 §3 dynamic handler
classification + decision table, analyze `analyze-component-props`⟩

**trap** — the dev warning `:rf.warning/placeholder-in-dynamic-vector`: a parent
"helpfully" forwarding `[:evt :rf.ui/value]` as a prop dispatches the literal keyword
`:rf.ui/value` as an argument. The protocol above keeps placeholders at literal DOM
sites only, so the trap is unrepresentable in migrator output.

### R-H7 · Loop-capturing handlers → extract a keyed child view — GUIDED

```clojure
;; before (compiles in Reagent; :rf.ui.compile/loop-capturing-handler here):
(for [t tabs]
  [:li {:key (:id t) :on-click #(dispatch [::open (:id t)])} (:label t)])
;; after:
(ui/defview tab-item [{:keys [t]}]
  [:li {:on-click [::open (:id t)]} (:label t)])
(for [t tabs] [tab-item {:key (:id t) :t t}])
```

Per-row committed slots need per-row instances; vector *data props* may capture the
loop binding (passing row data into the keyed child IS the fix — the analyzer only
capture-checks handler sites). Capture-free vectors in loops stay put (they share one
callback across rows — better than Reagent's per-row closures). GUIDED: the extracted
view needs a name. The same rule covers `ui/event`/`ui/handler` in loops; bare fns in
loops survive with a dev warning (`:rf.ui.compile/bare-fn-in-loop`) and the migrator
nudges them into the child view too. ⟨02 §3 loops, analyze `check-loop-capture!` +
component-props NOTE⟩

**trap** — per-row closure identity churn defeating memoisation, invisible in
Reagent; the extraction makes rows independently memoised views with stable handlers.

---

## 5. Subscriptions

### R-Q1 · `@(subscribe [:q])` → `(sub [:q])` — AUTO

**before / after** ⟨ex: todomvc `footer-controls`⟩

```clojure
(let [[active completed] @(subscribe [:todo/footer-counts])   ; before
      showing            @(subscribe [:todo/showing])]        ; before
(let [[active completed] (sub [:todo/footer-counts])          ; after
      showing            (sub [:todo/showing])]               ; after
```

Drop the deref, rename. Both the ambient `subscribe` in `reg-view` bodies (injected
lexical — disappears with R-C6) and `rf/subscribe` in plain fns rewrite identically;
the query vector is untouched — the sub registrations do not change at all. Legal
positions carry over: conditional reads fine; the `for` **first-collection** position
fine (once per render); anywhere per-row is a compile error (`:rf.ui.compile/sub-in-loop`)
whose fix is R-H7's keyed child view reading its own sub — the error message names
it, and the migrator applies R-H7 in the same pass. Destructuring of sub values,
`(or … "")` wrappers, threading — all carried over: destructuring, `or`/`and`/
`when`/`cond`, and the `->`/`->>`/`some->`/`some->>`/`cond->`/`cond->>` family are
inside the compiler's closed expression grammar (values stay ordinary Clojure;
the lexical syntax is audited — rf2-vxgfnd.100). A **custom or unaudited macro**
wrapper does NOT carry over: it is rejected at compile time
(`:rf.ui.compile/unsupported-form`) because its expansion could hide a reactive
call from site analysis — rewrite it with ordinary functions/core forms, or hoist
the computation. ⟨10 Tier M,
02 §4, analyze `walk-expr`/`analyze-for`, Spec 004 §Template grammar⟩

**trap** — subs-in-loops was legal-but-quadratic in Reagent (N reactions re-derefed
per render); the finite-sites law converts it to per-row views with per-row cache
lines instead of letting it through slower.

### R-Q2 · Dynamic query construction in render — GUIDED

```clojure
;; before:
@(subscribe (conj base-query filter-kw))
;; after (preferred): lift to a literal parametric site
(sub [:items/filtered filter-kw])
```

Parametric **literal** vectors are the optimised path (module-constant queries;
`rf=` arg reuse). A genuinely computed query expression still compiles (`sub` takes
the expression; the site indexes with the dynamic query shape) but forfeits the
static manifest row. GUIDED: the human either lifts to a literal with args (usually a
sub-signature tweak) or accepts the dynamic site knowingly. Sub bodies with side
effects discovered during this pass are R-X3, MANUAL. ⟨02 §4, 004-rw §Reactive reads⟩

**trap** — query objects rebuilt per render defeating site-level caching; the literal
lift restores value-stable queries without touching the sub graph.

---

## 6. Mount and roots

### R-M1 · `reagent.dom(.client)/render` → `ui/mount` — GUIDED

**before** ⟨ex: todomvc `core.cljs`⟩

```clojure
(defonce react-root (atom nil))
(defn ^:dev/after-load mount! []
  (when-let [el (js/document.getElementById "app")]
    (when-not @react-root (reset! react-root (rdc/create-root el)))
    (rdc/render @react-root
                [rf/frame-root {:id app-frame   ; (def app-frame :rf/default)
                                :url-bound? true
                                :url-strategy routing/hash-url-strategy
                                :initial-events [[:todo/initialise]]}
                 [views/root-view]])))
```

**after**

```clojure
(defn ^:dev/after-load mount! []
  (when-let [el (js/document.getElementById "app")]
    (ui/mount [ui/frame-root {:id :rf/default
                              :url-bound? true
                              :url-strategy routing/hash-url-strategy
                              :initial-events [[:todo/initialise]]}
               [root-view]]
              el)))
```

One-time, per root. `ui/mount` is a macro over a **literal** root form (the compiler
extracts frame plans; a runtime-assembled vector is a compile error) and is an
idempotent one-shot — the `defonce`-atom/`create-root`-once dance deletes; hot reload
re-runs `mount!` safely. `frame-root` keeps its shape and must sit in the static top
region (unconditional wrappers only); its `:id` must be a compile-time literal
keyword — the todomvc source spells `:id app-frame` over `(def app-frame :rf/default)`,
so the migrator inlines a def'd literal keyword (mechanical when the def is a literal;
a genuinely computed frame id is a MANUAL flag — plans are static identity, per
`:rf.ui.compile/bad-frame-root`); frame preflight ENSURE runs before render, exactly once, so
`:initial-events` semantics carry over StrictMode/HMR-immune. Hosts needing control
map to `create-root`/`render!`/`hydrate-root`/`unmount!`. **Named human check —
root identity is new information:** the root-id derives from the mounted view's
registered id by default (a single-root page needs nothing); pages mounting the same
view twice need `:disambiguator` or an authored `:root-id`, and the migrator cannot
know the page's root inventory — the human confirms per mount site. `rf/init!` +
adapter installation deletes on fully-migrated pages (no adapter under the compiled
substrate) — but see R-M2 for mixed pages. ⟨02 §6 Roots, 004-rw §Roots and mounting,
analyze `analyze-frame-root`, ui.cljc mount surface⟩

**trap** — frames created from render: the old adapter path ran ENSURE inside a
React effect; the compiled path runs it at host preflight, so abandoned renders,
StrictMode replay, and error recovery can never double-seed `:initial-events`.

### R-M2 · Mixed-page wiring — frame scoping across the boundary — GUIDED

Per-subtree migration leaves pages with both tiers; frames are shared (one registry,
one epoch stream), rendering is not. The migrator wires the coarsest granularity that
fits ⟨boundary §1⟩:

1. **Sibling roots** (default first cut): the legacy root keeps its existing mount;
   the migrated subtree gets its own `ui/mount`. They share frames and nothing else.
2. **Inward** (legacy widget inside a `ui` tree): `(ui/raw (r/as-element [legacy …]))`
   — same-root embedding, never a nested React root. Until the shared-context-object
   confirmation lands (**[S6-CONFIRM]**), the conservative authoring rule applies and
   the migrator emits it: an explicit frozen-tier
   `[rf/frame-provider {:frame the-frame-id}]` at the top of the embedded hiccup.
   Callbacks into the raw subtree: pass `(ui/dispatch-fn)` down as a prop.
3. **Outward** (migrated leaf inside a legacy shell): `(ui/->react view)` — v1, lands
   S6 with the migration wave. A frozen-tier `frame-provider` above the exported
   component scopes it with zero extra spelling; with none, it fails loud
   (`:rf.error/no-frame-context` — never a silent default). Props from Reagent hiccup:
   single-segment unqualified names, or `[:r> Exported #js {…ABI slots…}]`, per the
   boundary's blessed spellings (Reagent's `[:>` camelises and breaks the ABI
   encoding otherwise).

GUIDED: granularity choice and frame-id plumbing are per-page decisions; the migrator
proposes sibling-first and prints the SSR/HMR limits table (inward needs
`client-only` fallback on SSR paths; outward SSR unsupported v1). Step-1 plain-fn
adjustments (ambient ops in unregistered fns) are assumed already done — they precede
view migration and are the step-1 checklist's job, not this catalogue's.
⟨boundary §§1–3, §6; 10 step 1⟩

**trap** — a second React root at an inward boundary (breaks context propagation,
event delegation, StrictMode/Activity semantics): the contract explicitly forbids it
and the migrator never emits one.

---

## 7. The residue detector — flagged, never touched

The consolidated MANUAL list. Every entry prints its site, its restructure menu, and
its **compat-tier escape**: the code stays on frozen stock Reagent (live contract:
`spec/004A-Reagent-Compat.md` at the S7 wave; correct-but-frozen, contract suite +
one smoke in CI) and co-mounts with migrated trees per R-M2 — migration is never
all-or-nothing. ⟨boundary §8, 004-rw [TRANSITION]⟩

| id | pattern (detector greps + AST checks) | why not rewritten | escape / eventual home |
|---|---|---|---|
| R-X1 | Form-3 / `r/create-class` / lifecycle methods (= R-C5) | lifecycle semantics are a design decision (`effect`+refs vs domain event vs delete) | frozen tier via `reg-view*`; inward `ui/raw` |
| R-X2 | def-level shared ratoms used as a store between components | it was already wrong in re-frame; the fix is app-db discipline (events + subs) — naming is human work | frozen tier meanwhile; app-db is the destination ⟨10 Tier M/R⟩ |
| R-X3 | `reaction`/`r/track!`/`ratom/run!` driving side effects; side-effecting sub bodies | render-phase effects are compile errors here by design; restructure as events/effects (each was a concurrency hazard in Reagent too) | frozen tier; leases/events per guide 03 |
| R-X4 | `add-watch` on ratoms (= R-S4) | watcher semantics have no substrate home | frozen tier; causal event carries the consequence |
| R-X5 | dynamic tag heads / runtime-assembled hiccup beyond R-T6's branch rewrite | v1 has no runtime-head authoring form (wave-2 `ui/element`/`ui/view`) | `ui/raw`; `re-frame.ui.data` for data-driven UI |
| R-X6 | third-party Reagent wrapper libs (re-com et al.) | per-library decision; last movers by design | embed under `ui/raw` + `as-element` (no second root) until a `ui`-native answer exists ⟨10 headline, boundary §2⟩ |
| R-X7 | `r/current-component`, `r/props`/`r/children`, `r/force-update`, `r/next-tick`, `r/after-render` | component-introspection and scheduler pokes have no compiled equivalent; each use encodes a Reagent-specific assumption | frozen tier; usually dissolves under `effect` + committed handlers |
| R-X8 | `reagent.dom.server/render-to-string` call sites | server rendering moves to the JVM emitter path with the 06 §1 subset check (refs/effects need `client-only`/restructure) — a per-page audit, not a rewrite | whole-root granularity: unmigrated pages keep the frozen tier's own SSR path ⟨boundary §2 SSR, 10 Tier D⟩ |

**trap the detector itself avoids** — silent scope creep: everything the migrator
cannot prove is printed, classed, and escorted to an escape hatch; nothing is
"best-effort rewritten".

---

## 8. Verification harness sketch — the per-rule acceptance check

What the S6 migrator bead wires per converted view (and per rule fixture in the
migrator's own test corpus):

1. **Compile gate (every rule).** The migrated namespace compiles: the analyzer *is*
   the grammar check, and the S1e roster ids double as the migrator's negative
   fixtures (each AUTO rule has a deliberately-broken variant asserting the expected
   `:rf.ui.compile/*` id, proving the safety net catches the near-miss).
2. **Structural baseline match (where feasible — the Tier-1 check).** Render the
   *before* form through the frozen tier's serialisable render path and the *after*
   view through `ui.test/render` (Tier-1 JVM structural tree; frame or
   `:sub-overrides` supply sub values identically on both sides), then compare under
   a semantic normalisation of the same species as the parity contract's `N`:
   tag + attribute names/values (post conversion-table), child order, keyed order,
   text. Known, enumerated deltas are normalised out, not fudged: handler encoding
   (Reagent emits attached listeners; the tree retains event vectors as data under
   `:events` — the check asserts the *event intent* matches the closure's lifted
   vector instead), `:class` token order (sugar-first + sorted flag names), number
   stringification, void/boolean attribute forms. Feasible for R-C1/C2/C6, R-T1–T5,
   T7, T9, T10, R-H1/H2 (intent equality), R-Q1 — i.e. the AUTO spine.
   ⟨jvm-tree-and-conversion-contract via 004-rw §Portability, ui.test/render + attrs⟩
3. **Behavioural spot-checks (host-bearing rules).** `local`/`effect`/refs/synchrony
   sites (R-C3/C4, R-H3–H5, R-T8) cannot be proven by tree diffing — they get Tier-3
   mounted fixtures on the migrated side only (dispatch through the converted handler,
   assert app-db / DOM), plus the one Reagent adapter smoke that survives for the
   compat tier. No per-example Playwright: the corpus is CLJS unit tests per repo
   testing policy.
4. **Migration-inventory report.** Per namespace: rules fired by id/class, GUIDED
   checks awaiting sign-off, MANUAL flags with escapes chosen — the artefact the
   repo's own examples migration reviews PR-by-PR.

**trap** — a harness that "passes" by routing around the failing path (the
operator-reproduced lesson): rule 2's baseline is generated from the *actual before
form*, and rule 3 drives the *actual converted handler sites*, never synthetic
lookalikes.

---

## Tally and openness

**Rule count: 36** — **AUTO 13** (R-C1, R-C6, R-S1, R-T1, R-T2, R-T4, R-T7, R-T9,
R-T10, R-H1, R-H2, R-H5, R-Q1) · **GUIDED 15** (R-C2, R-C3, R-C4, R-S2, R-S3, R-T3, R-T5,
R-T8, R-H3, R-H4, R-H6, R-H7, R-Q2, R-M1, R-M2) · **MANUAL 8** (R-C5, R-S4, R-T6,
R-X2, R-X3, R-X6, R-X7, R-X8; R-X1/X4/X5 are cross-references, counted once).
Consistent with doc 10's ~80–90% mechanical verdict: the AUTO set covers the
high-frequency spine (Form-1 headers, sub derefs, dispatch closures, hiccup
carry-over), GUIDED the named-decision middle, MANUAL the residue that was unsound
in Reagent too.

**Rules not writable because semantics are open: none.** The three surfaces this
catalogue leans on hardest all froze with S1 (template grammar + compile-error
roster, props ABI/header grammar, event-vector-as-data + placeholder vocabulary);
committed-handler behaviour (S3) and `->react` (S6) are staged, not open — their
grammar is final and the affected rules carry stage notes. Two watch items ride
existing [S6-CONFIRM] rows, not open semantics: the shared-context-object rule and
the outward prop-encoding identity claim (R-M2 emits the conservative spellings
either way, so no rule blocks on them). ⟨boundary §9⟩

*Drafted 2026-07-12 (09:19 AUSEST), against S1-merged compiler sources read the same
morning.*
