# W1 prep — migrator rewrite-rule table (Reagent → `re-frame.ui`)

**Status:** prep draft · 2026-07-13, staging corrected 2026-07-19. Machine-actionable rule table
for the W1 migrator (doc-10 mechanical rewriter; build + first repo consumer S6). The W1 worker
starts here, not from re-derivation.

> **THE CANONICAL W1/W2/S6 RULEBOOK (rf2-vxgfnd.188, 2026-07-19).** MIG-01…35 below are the
> single normative rule set for the migrator. W1, W2, S6, the migration skill extension, and
> every open implementation bead name this table and no other. The pedagogical catalogue
> [`../drafts/migrator-rewrite-rules.md`](../drafts/migrator-rewrite-rules.md) (R-* ids) is
> **historical/supporting** — it explains the rewrites, it does not define the obligation.
> Every R-id is dispositioned in the [§R → MIG crosswalk](#r--mig-crosswalk-historical-source-r-rules--canonical-mig-rules)
> at the foot of this page. Do not maintain two synchronized rule systems: a rule change lands
> here, and the crosswalk records where it came from.

**Sources (normative, in precedence order):** `ai/findings/new-substrate-synthesis/10-migration-from-reagent.md`
(verdict table, M/D/R tiers) · `11-adoption-workstreams.md` W1 row (named transforms) ·
`spec/004-Views.md` (output template grammar, handler law, removed forms) ·
`spec/004B-UI-Tree-and-Conversion.md` (prop spelling/conversion legality) ·
`spec/004C-Roots-and-Mount.md` (mount grammar, root identity) ·
`implementation/ui/src/re_frame/ui.cljc` (shipped S1/S2 surface).

**Staged-target caveat (corrected 2026-07-19, rf2-vxgfnd.188).** The shipped rewrite-target
surface is verified against the exported vars of
`implementation/ui/src/re_frame/ui.cljc`, not against a stage plan. **Available as targets
today:** `defview`, `sub`, `lease`, event-vector/options-map handler grammar, `raw`, `html`,
`raw-fn`, `spread`, `spread-safe`, `mount`/`frame-root`/`frame-provider`, `custom-element`
(export S1, classification asserts S4), the `(frame)` ops-map body form (rf2-vxgfnd.184 —
MIG-31's target; supersedes the earlier rf2-y6dz8t HOLD framing), `error-boundary`,
`client-only`, `presence`/`presence-phase`, `slot`, **and the whole committed-callback /
view-local family: `local`, `effect`, `ui/event`, `ui/handler`, `ui/render-fn`,
`ui/dispatch-fn`, plus `ui/adapter`.**

> **Corrected claim.** This caveat previously declared those seven verbs — `ui/adapter`,
> `local`, `effect`, `ui/event`, `ui/handler`, `ui/render-fn`, `ui/dispatch-fn` — "NOT yet
> available as targets", pending S2/S3. **All seven are exported from `ui.cljc` and
> compiler-implemented today** (`re_frame/ui/compiler/analyze.cljc`; behavioural coverage in
> `implementation/ui/test/re_frame/ui/local_effect_dispatch_fn_{dom_cljs,jvm}_test.*` and
> `committed_events_dom_cljs_test.cljs`). Every `TARGET-STAGED:S3` tier and every "pre-S3 the
> site gates" clause that rode this sentence has been retiered below: those rules now emit
> their real targets. **The gating that survives is semantic, not staging** — a rule still
> gates when the *decision* is the human's (MIG-16's state-placement question), never merely
> because a verb was thought unshipped.

**Still genuinely unavailable:** `emit-ui-tree` SSR path (S5); `ui/->react` outward bridge (S6).
Rules whose target is staged say `TARGET-STAGED:Sn` and name the interim answer — the migrator
emits **no staged form** before its stage ships. A `TARGET-STAGED` marker is a claim about
`ui.cljc`'s exports; re-verify it against the file before trusting it.

## The rule table

| Rule id | Input construct (Reagent, minimal example) | Detector (syntactic match) | Transform (mechanical output) | Tier | Notes |
|---|---|---|---|---|---|
| MIG-01 | Form-1 view: `(defn price [amt cur] [:span.price …])`; call site `[price a c]` (or the fn-call shape `(price a c)`) | `defn`/`defn-` — or a defn-shaped `re-frame.core/reg-view`/`reg-view*` registration form, the dominant post-step-1 idiom (`(rf/reg-view name [args] body)`; unwrap it) — whose tail expr (through the grammar's branching control forms: `let`/`letfn`/`if`/`if-not`/`when`/`when-not`/`cond`/`case`/statically-pure `do` — MIG-14's set minus `for`, whose tail is a seq, never a single hiccup vector) is a literal hiccup vector in EVERY tail branch (a defn mixing literal-markup branches with computed/runtime-built markup fails this detector → MIG-30), AND used as a component elsewhere: a vector head `[price …]`, OR a hiccup-returning function call `(price …)` in child/template position (the todomvc `(filter-link showing …)` idiom — the compiled grammar can't see through a fn call to a raw hiccup return, so these MUST become view sites), OR registered | `(ui/defview price [{:keys [amt cur]}] [:span.price …])`; every call site → `[price {:amt a :cur c}]` | M | Prop keys = param names. Signature + ALL call sites atomic in one changeset — a `(price a c)` fn-call site rewrites to a `[price {…}]` view site alongside the header change. Zero-param views: call sites emit the explicit empty props map (`[status-pill]` → `[status-pill {}]` — the call-site grammar is `[view-sym {…}]`, 004 §Removed forms; MIG-15's `[app {}]` is the same spelling). `^{:doc …}`/name metadata on a `reg-view` → the `defview` docstring/opts position. Already-map single arg: keep, verify keys. `[a & rest]`, multi-arity bodies, or a param named `key` (reserved `:key` prop = compile error) → D flag. Gate rule: run the §Ordering gate first — a view tripping any gating D/R body rule (§Ordering 1) is left whole on the compat tier |
| MIG-02 | `@(subscribe [:q])` / `(deref (subscribe [:q]))` | deref of a call resolving (via ns aliases/refers) to `re-frame.core/subscribe` — or, inside a `reg-view`/`reg-view*` body, the injected lexical `subscribe` (unshadowed; doc-10 §Step 1) — ambient (no opts map) | `(sub [:q])` | M | Deref-drop. Dynamic query args pass through. A `subscribe` NOT immediately deref'd (stored reaction) is NOT this rule → MIG-19/20. A post-rewrite `sub` site inside a loop → MIG-08 flag. Legal positions carry over: conditional reads, and the `for` **first-collection** position (it evaluates once per render); per-row reads are `:rf.ui.compile/sub-in-loop` → MIG-08. Destructuring, `or`/`and`/`when`/`cond` and the `->`/`->>`/`some->`/`cond->` family are inside the compiler's closed expression grammar and carry over; **a custom or unaudited MACRO wrapper does not — it is `:rf.ui.compile/unsupported-form`, because its expansion could hide a reactive call from site analysis → D flag: rewrite with ordinary fns/core forms, or hoist the computation. A COMPUTED query expression** (`@(subscribe (conj base-query kw))`) still compiles — `sub` takes the expression — but **forfeits the static manifest row**, so it carries a D downgrade: the human either lifts it to a literal parametric site (`(sub [:items/filtered kw])`, usually a sub-signature tweak, which restores value-stable queries without touching the sub graph) or accepts the dynamic site knowingly |
| MIG-03 | `@(subscribe [:q] {:frame f})` / `(dispatch e {:frame f})` | same resolution as MIG-02/04 but with an explicit-frame opts map | — flag | D | Flag: "explicit-frame op: shipped `ui/sub` is arity-1 (no pin spelling exported yet); scope the subtree with `ui/frame-provider {:frame f}` or hold the site". See §Open items (pin spelling) |
| MIG-04 | `{:on-click #(dispatch [:ev x])}` (also `(fn [] (dispatch …))`) | fn literal in an `:on-*` props position **on a DOM or custom element** (the narrow bare-fn law is DOM/custom-element-only — an `:on-*`-named prop on an internal-view head → MIG-27, on a foreign head → MIG-10; never lifted here, lifting a child callback into a data vector breaks the child's `(on-x)` fn call); body-proof: body is EXACTLY one form, a call resolving to `re-frame.core/dispatch` — or, inside a `reg-view`/`reg-view*` body, the injected lexical `dispatch` (unshadowed) — of a LITERAL vector and nothing else (an explicit-frame opts-map arg → MIG-03); params unused | `{:on-click [:ev x]}` | M | Dispatch-lifting. Vector grammar is S1 data; committed dispatch behaviour asserts S3 (legal output today). In a `for` body: a lifted vector capturing the loop binding is a compile error in the new grammar → do NOT lift; MIG-08 flag (extract keyed child view). Capture-free literal vectors in loops are fine |
| MIG-05 | `#(dispatch [:typed (-> % .-target .-value)])` | MIG-04's body-proof with the params-unused clause replaced: the single param IS used, but ONLY inside a recognized extraction: `(-> e .-target .-value)` / `(.. e -target -value)` / `(.-value (.-target e))` → `:rf.ui/value`; `…-checked` → `:rf.ui/checked`; `(.-key e)` → `:rf.ui/key` | `[:typed :rf.ui/value]` replacing the fn in the same `:on-*` position (`{:on-input [:typed :rf.ui/value]}`) | M | Placeholders splice at TOP-LEVEL vector positions only (004 §Handlers): extraction nested in a subexpression (`(str p (-> % …))`) → D flag. Closed vocabulary — any other event access → MIG-06/MIG-18 |
| MIG-06 | `(fn [e] (.preventDefault e) (dispatch [:save]))` | MIG-04/05 body plus ONE-or-more `(.preventDefault e)`/`(.stopPropagation e)` calls, nothing else (zero wrapper calls is exactly MIG-04/05 — the three detectors partition, no double match) | `{:on-click {:event [:save] :prevent-default true}}` (`:stop-propagation true` when matched) | M | Options-map grammar per 004 §Handlers; retained as data in tree/manifest (004B `:events` case 2). Same loop-capture guard as MIG-04 |
| MIG-07 | `^{:key (:id t)} [item t]` / `(with-meta [item t] {:key k})` | metadata map carrying `:key` on a hiccup vector (typically a `for` body) | `[item {:key (:id t) …}]` — key merged into the props map (created at position 2 if absent) | M | key-meta→prop. Runs with MIG-01's call-site conversion so the map is built once. `:key` is React's reserved slot — never also a data prop |
| MIG-08 | loop-body legality: `(for [t ts] [item t])` unkeyed; `(sub …)`/`(lease …)` in a `for`; handler vector capturing the loop binding | `for` whose body item has neither key-meta nor `:key` prop; any `sub`/`lease` call inside a loop body; MIG-04's capture case | — flag | D | Flag: "new grammar: missing key = build failure; `sub` in loops = compile error; loop-capturing handler vectors = compile error — extract a keyed child view (per-row instances)". Extraction is a code-structuring move the tool does not perform |
| MIG-09 | `[:> Button {:label "x"}]`; `(r/adapt-react-class Button)` | vector head literally `:>`; call resolving to `reagent.core/adapt-react-class` | `[Button {:label "x"}]` — hoist the component to head; delete the `adapt-react-class` wrapper | M | Foreign heads are direct (004 §Template grammar). Props pass through as JS values. Fn-valued props on the result → MIG-10. Sibling interop heads are NOT this transform and MUST NOT pass through MIG-14 as tags: `:f>` (fn component — positional args, may use hooks) and `:r>` (raw JS props) → D flag naming the foreign-head + callback/props choice; `r/as-element` → §Open items 2 |
| MIG-10 | fn-valued prop at a foreign-component boundary: `[Picker {:on-select #(…)}]` | fn literal/fn-valued expression in ANY prop position on a foreign head — `:on-*`-named props included (the DOM bare-fn law never covers foreign heads); internal-view heads → MIG-27 | — flag | D | Bare fns at foreign boundaries are compile errors (004 decision table). Flag: "choose a callback form — identity-as-protocol / callback-ref → `(ui/raw-fn f)` (shipped); needs the event object / a payload → `ui/event`; imperative work / stable-identity change-callback → `ui/handler`; render prop (pure, fires during the foreign render) → `ui/render-fn` (all three shipped). Event VECTORS are not a foreign-boundary form — foreign components invoke their props themselves; nothing intercepts a vector there". Only `raw-fn` may be auto-suggested; classification needs the human |
| MIG-11 | camelCase / alias prop spellings: `:onClick`, `:className`, `:htmlFor`, `:tabIndex`, `:viewBox` | DOM-element prop keyword matching React's published event/attr name tables in camelCase or `:class-name`/`:html-for` alias form | pinned kebab spelling: `:on-click`, `:class`, `:for`, `:tab-index`, `:view-box` (004B name rows; `:class-name`/`:html-for` are compile errors — one spelling per name) | M | Table-driven from React's published names (finite). Unknown names pass verbatim (custom-element events keep authored spelling). `:on-keydown`-style lowercase resolves through the same event-name table → `:on-key-down`. EXCLUSION: `:dangerouslySetInnerHTML` never respells through the name table — it is not a prop in this grammar at all (004B name rows) → MIG-34 |
| MIG-12 | `(doall (for [x xs] …))` in a template | `doall` wrapping a `for` in template position | `(for [x xs] …)` — strip the wrapper | M | `for` is a native control form in the new grammar; Reagent's laziness workaround is dead weight. A `doall` wrapping a markup-returning `map` routes to MIG-13 |
| MIG-13 | `(map (fn [t] ^{:key (:id t)} [item t]) ts)` producing markup | call to `map` in child position of a template | — flag (suggested rewrite: `(for [t ts] [item {:key (:id t) :t t}])`) | D | Markup-returning `map` + raw lazy seqs are rejected at compile time. Mechanical only when the fn is a literal with a keyed hiccup body — the flag carries the candidate `for`; human confirms |
| MIG-14 | plain hiccup: tags, `:div.cls#id` sugar, fragments `[:<> …]`, `:style` maps, `:class` string/vector/flag-map, control forms + expression children in template position | literal keyword head — incl. the fragment head `:<>` — (except the interop heads `:>`/`:f>`/`:r>` → MIG-09); props map, WHEN PRESENT, a literal map (a non-literal props-map expression → MIG-28); computed values inside a literal map are fine | unchanged — this is the point of keeping hiccup | M | Pass-through of the STRUCTURE — the in-map entry rules (MIG-04/05/06/11/29/34, key rule MIG-07) still rewrite entries inside a passed-through literal map; "unchanged" is the hiccup shape. The grammar's control forms in child/template position (`let`/`letfn`/`if`/`if-not`/`when`/`when-not`/`cond`/`case`/statically-pure `do`/`for`) normalize into the AST (004 §Template grammar) — pass through; expression children evaluating to text/number/nil/false pass through; a markup-RETURNING call child is NOT pass-through → MIG-01 (convertible callee) / MIG-30 (runtime-built). Sub-rules (each a shipped compile error the pass-through must not walk past): `#id` sugar + explicit `:id` on one element (`:rf.ui.compile/id-sugar-conflict`) → D, keep one; **two `#id` segments on one tag (`:rf.ui.compile/duplicate-id-sugar`) → M, keep the first**; collection OR fn value in a DOM attr outside the handled partition (`:data-foo {:a 1}`; a fn in a non-`:on-*`/non-`:ref` DOM attr — no rule owns it and no legal spelling exists) → D; **a collection value on a non-`:class`/`:style` attribute (`:rf.ui.compile/collection-attr-value`) → M, `(str/join " " xs)`**; **a LITERAL keyword in child position (`:rf.ui.compile/keyword-child` — keywords are element heads, not content) → D: propose the coercion but the human picks WHICH, `(name status)` renders `"active"` and `(str status)` renders `":active"` and the source cannot say which was meant; a non-literal expression that merely *evaluates* to a keyword is runtime content and passes through**; **a multi-form control body (`:rf.ui.compile/multi-form-body` — the grammar takes exactly ONE body form per control form) → M: sibling markup wraps in `[:<> …]`, restoring what the author meant where Reagent silently rendered only the last form; a side-effecting form in a template body is D instead (it was dead-or-worse in Reagent too)**; **directly-nested `for`s (`:rf.ui.compile/nested-for-body`) → M, collapse into multiple binding pairs in one `for`**. A NON-literal props-map expression (`merge`/`assoc`/`into`/a bound symbol) is not pass-through → MIG-28 (`ui/spread`) |
| MIG-15 | `(rdom/render [app] el)` / `(r/render …)` / `(rdc/create-root el)` + `(rdc/render root [app])` | call resolving to `reagent.dom/render`, `reagent.core/render`, or the `reagent.dom.client` pair — `create-root` + `render`, the root often threaded through a `defonce`/atom between the two (the React-18+ idiom; every mount in this repo's examples is this shape) — with a literal root vector; `reagent.dom.client/hydrate-root` is the SSR family → MIG-23 flag (hydration behaviour lands S5) | `(ui/mount [ui/frame-root {:id <frame-id> :initial-events […]} [app {}]] el)` | M | One-time, per root. Frame id + `:initial-events` lift from the app's step-1 frame setup (existing ensure/`frame-root` config — an inline `[rf/frame-root {…}]` in the render call lifts wholesale, head respelled `ui/frame-root`, config entries carried); none found → D flag. The matched `defonce`-atom/`create-root`-once dance DELETES with the rewrite — `ui/mount` is idempotent per root, so a `^:dev/after-load mount!` re-runs safely with no root threading. `mount` compiles S1c; ENSURE preflight + `:initial-events` drain are the S2 frame wiring |
| MIG-16 | Form-2 / `with-let` local state: `(defn dd [] (let [open? (r/atom false)] (fn [] …@open?…(reset! open? true)…)))` | inner-fn return (Form-2) with `r/atom` bindings, or ANY `reagent.core/with-let` (its bindings are init-once state even without `r/atom`, and a `finally` clause is unmount cleanup — cleanup-only `(r/with-let [_ nil] … (finally …))` wrappers are real idiom) | rewrite + flag: `(let [[open? set-open! update-open!] (local false)] …)` with `@open?`→`open?`, `(reset! open? v)`→`(set-open! v)`, and **`(swap! open? f args)`→`(update-open! f args)`** | D | **`local` returns the THREE-tuple `[value set! update!]` (shipped).** The atomic-updater rule is load-bearing and the migrator MUST honour it: a multi-writer `swap!` targets `update!`, which applies `(f current & args)` to the LATEST host state. **Never emit `(set-open! (f open? …))`** over a committed render value — that is last-write-wins across same-turn writers, and `set!` stores its argument EXACTLY (a stored function is a value, never an updater — no useState fn-overload). Multi-writer *ephemera* are therefore expressible in `local`; the old MANUAL flag on multi-writer `swap!` is retired. Setter and updater are HOST-ONLY — a render-phase call fails loud, so a render-phase `swap!` (rare, always a bug) flags rather than rewrites. Still D, and the flag is the reason: "Form-2 state — decide: product meaning → app-db (event+sub; human move) vs ephemeral → `local` (placement rule 004 §Local state)". Only genuinely *product* state moves to app-db. `with-let` finally-cleanup maps to `effect` cleanup (shipped). GATES on the placement decision, not on staging |
| MIG-17 | Form-3 / `r/create-class` lifecycle: `{:component-did-mount #(init! …) :should-component-update … :reagent-render (fn […] …)}` | call resolving to `reagent.core/create-class`; lifecycle keyword keys | mechanical parts: DELETE `:should-component-update` (memo-by-default); extract `:reagent-render` body for the other rules. Lifecycle bodies → flag | D | Flag: "host/DOM work → `(effect :connect …)` (shipped); domain work ('mark viewed') → route/domain event (dataflow); `:on-mount` deliberately does not exist". Gates on the decomposition decision (which lifecycle body is host work vs domain work is the human's), not on staging. `:connect` cleanup runs at each DISCONNECT, not once at unmount — StrictMode dev replay makes connect/disconnect cycles expected, so the flag must name the disconnect-idempotence requirement |
| MIG-18 | non-conforming `:on-*` handler fn: mixed `#(do (set-open! false) (dispatch [:save]))`; guarded dispatch `(fn [e] (.preventDefault e) (when ok? (dispatch […])))`; no-dispatch imperative work `#(seam-call! handle)` | fn literal in an `:on-*` position **on a DOM or custom element** whose body fails the MIG-04/05/06 body-proof for ANY reason: local work mixed with a dispatch, a dispatch under a conditional guard, a dispatch of a non-literal vector or through a helper fn, or NO dispatch at all (pure local/imperative work) — except a body failing ONLY by the explicit-frame opts-map arg, which is MIG-03's. A NON-literal fn-valued `:on-*` expression is not this rule: MIG-14 computed-value pass-through (004's runtime classification) — unless it resolves to a local fn carrying ambient ops, §Ordering 1's residual net | — flag | D | Flag: "legal as a bare fn on a DOM `:on-*` (narrow bare-fn law) — or split: local work stays a fn, intent becomes a vector on the natural element; a GUARDED dispatch maps to `ui/event`'s nil ⇒ no-dispatch filtering (shipped)". The bare-fn landing still respells any dispatch: a converted `defview` has no injected/ambient `dispatch` — imperative dispatch inside a bare fn is `(ui/dispatch-fn)` (shipped; per-view stable, reads the committed frame at CALL time, fails loud with `:rf.error/dispatch-disconnected` when the view is disconnected). Pure imperative work whose return is irrelevant is `ui/handler`; work returning a vector to dispatch is `ui/event`. Coupled to MIG-16's state decision — that coupling is why a hit still gates the view (§Ordering 1); the targets themselves are shipped |
| MIG-19 | derived state: `(r/track compute a)` / `r/cursor` / `reagent.ratom/reaction` | call resolving to `reagent.core/track`/`cursor` or `ratom/reaction`/`make-reaction` | — flag; suggested: copy compute fn into `(reg-sub :ns/name …)` (dataflow layer — available today), call sites → `(sub [:ns/name a])` | D | Usually a copy-paste (doc-10), but sub naming/query design + call-site rewiring need the human. Gains caching + Xray visibility. **`r/cursor` has a WRITE side the read-side rewrite must not leave behind:** `(reset! the-cursor v)` is silent state mutation from anywhere, and each write site needs a registered event (`[:a/set-b v]`) — naming it is the point of the move, and the flag must enumerate the write sites alongside the read sites. A cursor over the frame's own app-db mirror (v1 apps sometimes cursor into a ratom mirroring app-db) creates a second write path; the event rewrite collapses it back to one. A `track` capturing component-local values cannot become a global sub verbatim — parameterize the captured value into the query vector; a capture that is itself a ratom is MIG-20 |
| MIG-20 | ratom as store / reactive side effects: top-level `(def state (r/atom {}))`; `r/track!` / `run!` / reactions driving effects; **`(add-watch a k f)` on a ratom** | top-level def of `r/atom` read by multiple views; `track!`/`run!`; reaction bodies with side effects; **any `add-watch` whose target resolves to a ratom** | — reject | R | Reason: "second state model / render-phase side effects — compile errors here by design; restructure as app-db + events/subs (or props). Every one of these was a latent concurrency bug in Reagent". **The `add-watch` arm is reported with its watcher body and is never mechanically rewritten to `effect`:** a watcher fired synchronously mid-`swap!`, *before* render; an effect runs *after* commit — and the rewrite would also drop multi-writer ordering. That is a behaviour change no diff review catches, so the restructure is the causal-event move (the handler that made the change carries the consequence) or registered fx for external-world sync. Compat escape: the watching component stays on the frozen tier until restructured |
| MIG-21 | dynamic tag head: `[(if big? :h1 :h2) props …]` | hiccup head is a non-literal expression (not a keyword literal, not a compile-resolvable view/foreign symbol) | — reject | R | Reason: "dynamic tag heads rejected at compile time; bind attrs + split branches, or `re-frame.ui.data` (separate artifact) for genuinely data-driven UI" |
| MIG-22 | third-party Reagent wrapper components: `[rc/single-dropdown …]` (re-com etc.) | vector head resolving into a require of a known Reagent-wrapper lib (configurable roster: `re-com.core`, …) | — reject (interim embed named in the flag) | R | Reason: "third-party Reagent wrapper — per-library decision; last movers. Interim: keep the subtree on the compat tier, or embed under `(ui/raw (r/as-element […]))` per the boundary contract §2 (same root, frame scoping/teardown rules there)". Outward direction `ui/->react` is S6 |
| MIG-23 | SSR: `(reagent.dom.server/render-to-string [app])` | call resolving to `reagent.dom.server/render-to-string`/`render-to-static-markup`; a `re-frame.ssr/render-to-string` site is already the frozen compat path — flag it only when its subtree converts; `reagent.dom.client/hydrate-root` mounts route here from MIG-15 | — flag | D · TARGET-STAGED:S5 | Flag: "the SSR serialisation path (`re-frame.ssr/emit-ui-tree` + `render-static` — the JVM emitter itself ships S1) lands S5; until then keep the frozen `re-frame.ssr` compat path. Views using refs/effects need `client-only`/restructure for SSR (06 §1 subset)" |
| MIG-24 | ns requires | `(:require [reagent.core :as r] [reagent.dom :as rdom] …)` | add `[re-frame.ui :as ui :refer [defview sub]]`; DROP reagent requires only when zero uses remain in the namespace (D/R residue keeps them) | M | Runs LAST per namespace. Never remove a require a flagged site still needs |
| MIG-25 | render-phase `subscribe` over a side-effecting sub body (doc-10 R) | none at the view tier — a render-phase `subscribe` is syntactically indistinguishable from a pure one; sub bodies live in the dataflow layer. Best-effort: grep `reg-sub` bodies for `dispatch`/fx/host-mutation calls and flag the registration site | — reject | R | Reason: "subs are pure here; the effectful part moves to leases/events (guide 03)". Dataflow-side finding surfaced in the report, never a view rewrite; the view site itself converts per MIG-02 once the sub body is pure |
| MIG-26 | step-1 mode — ambient frame ops in plain unregistered fns: `(defn helper [] … @(rf/subscribe …) …)` | plain `defn` (no `reg-view`, not in this run's conversion set) whose body makes ambient `re-frame.core/subscribe`/`dispatch` calls | — flag | D | Doc-10 §Step 1: these raise `:rf.error/no-frame-context` at first render (derefs) or at interaction time (dispatch closures) — "grep for them, don't discover them by clicking". Flag carries the prescribed rewrites in preference order: (1) register (`reg-view`/`reg-view*`), (2) hoist the op to the nearest registered ancestor and pass values/ops down, (3) explicit `{:frame f}`. A step-1 checklist mode, runnable before the dataflow move — independent of the §Ordering step-2 pipeline |
| MIG-27 | fn-valued prop on an internal-view call site: `[todo-input {:on-change #(dispatch [:edit :edit %]) :on-commit #(dispatch [:commit])}]` | fn literal / fn-valued expression in ANY prop position (`:on-*` or otherwise) on an INTERNAL-view head (a compile-resolved Var — not a DOM/custom element, not a foreign head) | — flag; suggested: forward the intent as DATA — a dispatch-only closure → the bare vector (`:on-commit [:commit]`), and the child view places the forwarded vector in its own DOM `:on-*` position (runtime classification: vector → dispatch). A payload-carrying closure (`:on-change #(dispatch [:edit %])`) → the child owns the extraction at its DOM site: `ui/event` (the S3 landing), or a literal placeholder vector — legal NOW but only when the WHOLE vector, event id included, goes literal at the child's DOM site (a human restructure of the child's contract; a placeholder never rides a forwarded vector) | D | **C-13a (shipped S3 ruling, 2026-07-17 — binding on this rule).** An ordinary function prop on an INTERNAL `defview` call site is a **legal, opaque, identity-compared value with no implicit invocation phase**. MIG-27 MUST NOT call every such prop a compile error, and MUST NOT gate or rewrite one automatically. What it may do is *recommend*: a data intent where serializability/tool-visibility is wanted, or `ui/handler` / `ui/render-fn` where a phase or a stable identity is actually required. Two things this rule still rejects, unchanged: a bare fn at a **foreign** head (MIG-10), and the narrow native-event shorthand's boundary (a bare fn is legal on a DOM/custom-element `:on-*` and nowhere else by shorthand). The dispatch-only-vector case is NON-gating: rewrite + flag (doc-10 Tier D), coordinated with the child view's conversion in the same closed-subtree pass. The payload-carrying case is likewise **non-gating now** — `ui/event` is shipped, so the child's extraction site exists; what remains human is the parent/child payload contract (which end appends the payload). `:rf.ui/value`-style placeholders live at literal DOM sites only, never forwarded as props (`:rf.warning/placeholder-in-dynamic-vector`). The pervasive callback-helper pattern (todomvc `todo-input`) routes here — this is where MIG-04/05/06's excluded internal-view `:on-*` props land |
| MIG-28 | computed/dynamic DOM props map: `[:input (merge (dissoc props …) {:type "text" :value (or draft "")})]` | a NON-literal props-map expression (`merge`/`assoc`/`into`/a bound symbol) in a DOM element's props position (MIG-14 handles only LITERAL props maps) | rewrite + flag: `[:input (ui/spread base {:type "text" :value (or draft "")})]` — the one generic runtime prop-map conversion (shipped S1, DOM elements only); the rewrite is emitted, the flag carries the named check | D | Non-gating (the emitted `ui/spread` is legal shipped grammar — hence its seat in §Ordering 3's in-view set). `ui/spread` routes the merged map through the conversion rule table (004 §2; 004B). Named check: a spread site forfeits the static manifest row and the controlled-input synchrony door (which needs a provable literal `:value`/`:checked` co-present on the element) — shrink the spread to genuinely pass-through props and lift `:value`/handlers back to literals. Component call sites stay literal-map (MIG-01), never `ui/spread`. A conditional-`:ref` arm buried in the map lifts out to an explicit prop per MIG-29 |
| MIG-29 | callback ref: `{:ref (fn [node] (when node (.focus node)))}` | a fn literal / fn-valued expression in the `:ref` slot of a DOM element | `{:ref (ui/raw-fn (fn [node] (when node (.focus node))))}` — wrap in `ui/raw-fn` (shipped S1) | M | `:ref` is a reserved React slot, never an event property — the bare-fn shorthand does NOT apply; a bare fn in `:ref` is a compile error (004 §Handlers Refs). React invokes callback refs during commit *before* the owning view's layout publication, so no committed-slot promise holds — `ui/raw-fn` is the explicit marker. Object refs are preferred: a ref body that reads view state (not just the node) → D downgrade with the object-ref note. `:ref` at an INTERNAL-view call site is a separate concern (declared forwarding lands S3) → D flag with the stage note. Both D cases GATE (§Ordering 1): un-rewritten output is a bare fn in `:ref` / an undeclared forwarded ref — compile errors |
| MIG-30 | runtime-built markup helper call in child position: `[:div (md/render (:body article))]` — the callee assembles hiccup at runtime (walking an AST, threading transforms) | call in child/template position resolving to a defn that returns markup but FAILS MIG-01's defn detector: some tail branch is a literal hiccup vector while others are computed, or the tree is built at runtime (`walk`/`reduce`/`->` over data); best-effort, same resolver as MIG-01 — a call the resolver cannot classify as text-valued is reported, not rewritten | — flag | D | Runtime hiccup DATA has no compiled spelling: the compiled grammar cannot see through fn calls (MIG-01's own premise), and expression children are values, not markup. Flag carries the options: template-ize the callee into `defview` branches (then MIG-01 applies); genuinely data-driven markup → `re-frame.ui.data` (separate artifact — MIG-21's escape); interim: the whole view stays on the compat tier. Gates (§Ordering 1) |
| MIG-31 | render-phase frame-ops capture: `(let [handle (rf/capture-frame)] … {:on-click #(seam-call! handle …)} …)` — the captured bundle rides into deferred/out-of-render dispatch paths | call resolving to `re-frame.core/capture-frame` inside a converting view body | `(let [handle (frame)] …)` — the shipped S2 ops-map body form (rf2-vxgfnd.184): the same `{:frame :dispatch :dispatch-sync :subscribe}` bundle, locked to the committed frame; the zero-arg render-body capture rewrites in place | M | Shipped target (supersedes the rf2-y6dz8t HOLD framing per the 12 §2 blessed table's S2 `frame` row). Semantics note the rewrite carries: the compiled bundle is incarnation-fenced — ops fail loud (`:rf.error/frame-destroyed`) once the captured frame is destroyed or replaced, where core `capture-frame` stays id-locked; bundle identity is stable per live frame incarnation. Two D downgrades: (a) explicit 1-arity `(rf/capture-frame frame-id)` has no compiled pin spelling → flag per MIG-03's explicit-frame guidance; (b) a capture sited INSIDE a fn handler / deferred callback / loop cannot land — `(frame)` is a finite render-time site like `sub` → flag: hoist the capture to the view body and let the closure capture the bundle. The D cases gate (§Ordering 1); the zero-arg body-position rewrite does not |
| MIG-32 | framework-shipped Reagent-tier component head: `[rf/route-link {:to :route-id :params {…}} …]` | vector head resolving to a framework-exported REGISTERED view that is not `defview`-compiled — `re-frame.core/route-link` (the known member; registered at `:route/link` via `reg-view`, late-bound `:routing/route-link` — 012 §route-link, API.md registered-view rows) — excluding `frame-root`/`frame-provider` (MIG-15's mount grammar / the shipped scope form) | — flag | D | No compiled-substrate counterpart of `route-link` is ruled anywhere (spec 012 ships the `reg-view` body; 000-Vision says "per host's view idiom"; the synthesis names no target). A plain `[:a {:href …}]` is NOT an equivalent rewrite — 012 §Link handling: the runtime does not intercept plain anchors. Flag: "framework component with no ruled compiled spelling — hold the view on the compat tier pending the rf2-5yovjt ruling (compiled `route-link` counterpart; W5/spec-012 rider)". Gates (§Ordering 1) |
| MIG-33 | substrate-adapter boot: `(rf/init! reagent-adapter/adapter)` + `(:require [re-frame.adapter.reagent :as reagent-adapter])` | call resolving to `re-frame.core/init!` whose argument resolves to a Reagent adapter spec | `(rf/init! ui/adapter)`; drop the Reagent-adapter require when nothing else uses it | D | The compiled substrate still boots through `rf/init!` — `ui/adapter` is the observation-port consumer, **exported from `ui.cljc` today** (CLJS installs the retained watchable React substrate; JVM the headless atom realization; both report `:rf.adapter/ui`). Mixed pages: KEEP the Reagent adapter install — any compat-tier subtree still renders through it; swap only when the page's every root is converted (the migrator cannot know the page's root inventory — same human confirm as MIG-15's root-identity check). Root-level, once per app; never gates view bodies (§Ordering 1 exclusion) |
| MIG-34 | trusted-markup prop: `[:div {:dangerouslySetInnerHTML {:__html s}}]` | DOM-element props map carrying `:dangerouslySetInnerHTML` with a literal `{:__html expr}` map value | `[:div (ui/html expr)]` — delete the prop; `expr` becomes the element's SOLE child wrapped in `ui/html` (shipped S1) | M | `dangerouslySetInnerHTML` does not exist in the new grammar — `ui/html` is the one trusted-markup spelling, a node variant not a prop (004B name rows); MIG-11 carries the matching never-respell exclusion. React requires the prop-form element childless, so the sole-child grammar (004 §`ui/html`) always fits. A NON-literal `{:__html …}` value (computed map, inside a spread) → D flag |
| MIG-35 | Reagent component-introspection / scheduler API: `(r/current-component)`, `(r/props c)` / `(r/children c)`, `(r/force-update c)`, `(r/next-tick f)`, `(r/after-render f)` | call resolving to any of `reagent.core/current-component`, `props`, `children`, `force-update`, `next-tick`, `after-render` inside a converting view body | — reject | R | **Added 2026-07-19 (rf2-vxgfnd.188) — the one R-rule obligation with no prior MIG home** (historical R-X7). Reason: "component introspection and scheduler pokes have no compiled equivalent — each use encodes a Reagent-specific assumption about *this* renderer's component object and render scheduling". The danger this row exists to stop is that these calls **still compile** after conversion (they are ordinary calls in expression position, so nothing in the analyzer rejects them) and then fail or return nil at runtime outside a Reagent render — a silent breakage the compile-gate safety net does NOT catch, which is exactly why the migrator must detect them itself. Restructure: `props`/`children` are the props map and positional children the view already receives; `force-update` dissolves under memo-by-default + committed slots; `next-tick`/`after-render` are `effect` (post-commit, with cleanup). Compat escape: the component stays on the frozen Reagent tier |

## §Ordering

1. **Gate before body.** Per candidate view, evaluate every gating body rule FIRST — MIG-16/17
   (state/lifecycle: the placement and decomposition DECISIONS are the human's), MIG-20/21/22/35
   (rejects), MIG-30/32 (no compiled or ruled spelling exists),
   and any body hit of MIG-03/08/10/13/18/19, a MIG-14 D sub-rule, a
   MIG-29 D case, a MIG-31 D case (explicit-frame arity / deferred-position capture — the zero-arg
   body-position rewrite is non-gating M), or the residual net — an ambient/injected `dispatch`/`subscribe` op inside a fn
   literal no `:on-*` rule owns (a let-bound handler fn, a fn passed to a helper call) or a bare
   render-position `dispatch` call; a converted `defview` has no ambient/injected ops (MIG-18's
   note), and gate-before-body keeps MIG-02 from rewriting subscribe derefs inside interaction-time
   fns: each is a compile error in the converted grammar or has no legal shipped
   spelling. Any hit → the whole view is left unconverted on the compat tier and reported; the unit
   of migration is the whole view — no half-migrated bodies. (MIG-15/23/24/33 are root-, call-site-,
   and ns-level rules; MIG-25/26 are dataflow-/step-1-side — none of these gate view bodies.
   MIG-14's M sub-rules, MIG-27 and MIG-28 are non-gating rewrite + flag.)

   > **What gating means, and what it does not (corrected 2026-07-19, rf2-vxgfnd.188).** A rule
   > gates because a *decision* belongs to the human, or because no legal spelling exists — never
   > merely because a target verb was believed unshipped. MIG-27's payload-carrying case used to
   > gate on the second ground ("the child's `ui/event` site is the S3 landing"); `ui/event` has
   > shipped, and C-13a additionally rules a plain fn prop on an internal view legal and opaque, so
   > **MIG-27 no longer gates at all**. MIG-16/17/18 still gate — on the first ground, which the
   > stale staging note was obscuring.
2. **Closed subtrees only.** A converted `defview` cannot be referenced from an unconverted Reagent
   body until `ui/->react` (S6). The tool takes a caller-closed subtree per run (leaf → root, shared
   components last, per doc-10 §Suggested mechanics) and flags any inbound Reagent call site it
   cannot include. The OUTBOUND direction gates too: a converting view whose template references a
   view staying Reagent (gated by rule 1, or pinned unconverted by an out-of-run caller) would emit
   an unresolvable head — flag with the two answers (embed the child under MIG-22's interim
   `(ui/raw (r/as-element […]))` spelling, or the caller stays compat with it); the choice, like
   co-mount boundary granularity, is the human's, not the tool's.
3. **Within a converting view:** loop-context analysis before MIG-04/05/06 (capture check);
   MIG-02/04/05/06/07/09/11/12/28/29/34 in any order after that (one exception: MIG-07 on an
   INTERNAL-VIEW call site rides MIG-01's atomic call-site pass — the props map is built once, per
   MIG-07's note; on DOM/foreign heads it is order-free); MIG-27 fn-prop forwarding is
   coordinated with the child view's own conversion (same closed-subtree pass — the parent emits
   the data vector, the child places it in its DOM `:on-*` site); MIG-01 signature + call-site
   rewrite atomic across the whole changeset (a `(view a b)` fn-call site rewrites to a
   `[view {…}]` view site in the same change); MIG-24 requires-fixup last; MIG-15 mount rewrite
   once per root, MIG-33 adapter-boot swap once per app alongside it.
4. **Idempotency.** `ui/defview` bodies and already-pinned spellings are skipped — re-running the
   tool over migrated code is a no-op.

## §Non-goals (deliberate)

- **No behavior inference.** The tool never decides whether Form-2 state has product meaning,
  what a list key should be, or which callback form a foreign boundary wants — those are the D flags.
- **No cross-file moves.** No hoisting state to app-db, no creating events/subs (MIG-19's `reg-sub`
  is a suggestion in the flag), no extracting keyed child views, no moving views between namespaces.
- **Dataflow untouched.** Events, subs, fx, machines, schemas, routes are step-1 territory; the
  migrator rewrites the view tier only (MIG-25/26 flag dataflow-/step-1-side sites, never rewrite
  them).
- **No staged output.** Nothing S3+ is ever emitted before its stage ships (see caveat).
- **No re-com replacement, no formatting churn** beyond the rewrite sites, no registry/id management
  beyond `defview` defaults.

## §Open items for the W1 worker

1. **Explicit-frame pin spelling** (MIG-03): 004 names "explicit pin" in the resolution chain but the
   shipped `sub` is arity-1 — confirm the S2/S3 pin surface before upgrading MIG-03 to M.
2. **`r/as-element` in foreign render-prop positions** has no ruled target; propose flag text (likely
   MIG-10 family) and check the boundary contract.
3. **MIG-13 auto-apply threshold**: decide whether the keyed-literal-fn case ships as an M sub-rule.
4. **Detection substrate**: rewrite-clj + a small alias/refer resolver (MIG-01/02/04 need var
   resolution through `:as`/`:refer`/`:rename` AND `:require-macros`/`:refer-macros` — the
   realworld files reach `reg-view` via `(:require-macros [re-frame.core :refer [reg-view]])`),
   vs full analyzer. Bias: smallest thing that proves.
5. **Flag-report format**: one machine-readable report the W2 migration skill teaches from (rule id,
   coords, flag text, suggested rewrite) — align with W2 before freezing.
6. **MIG-11 name table provenance**: pin the React version whose event/attr name tables the rule is
   generated from (004B pins the conversion table to the targeted React release; its S1b probes ran
   against react-dom/server 19.2.0 — the natural pin).
7. **`route-link` and framework-owned Reagent components need a ruled target** (MIG-32) — filed as
   **rf2-5yovjt** (open, P3, decision-flagged; likely a W5/spec-012 rider) — before the migrator can
   claim coverage of routed apps. Spec 012 ships
   `route-link` as a `reg-view` body and 000-Vision says "per host's view idiom"; nothing rules the
   compiled-substrate counterpart. Pervasive: every realworld example view and todomvc's filter
   links gate on it today. Also confirm the roster is really a one-member set (sweep the API.md
   registered-view rows for other framework-shipped view components).

## R → MIG crosswalk (historical source R-rules → canonical MIG rules)

Added 2026-07-19 (rf2-vxgfnd.188) to collapse the two competing W1 authorities into this one.
The superseded catalogue [`../drafts/migrator-rewrite-rules.md`](../drafts/migrator-rewrite-rules.md)
partitioned the same work into 36 `R-*` rules grouped by **Reagent construct area**
(C components · S state · T templates · H handlers · Q subscriptions · M mount · X residue);
this table partitions it into 35 `MIG-*` rules grouped by **detector**. The counts differ because
the cuts differ, not because either book knows a rule the other does not — the mapping below is
many-to-many in both directions.

**This crosswalk is temporary.** It exists so nothing was silently dropped during the collapse.
Once the S6 migrator ships against MIG ids it can go; new rules are added to the table above and
do not need an R ancestor.

### Completeness check

- All **36** R rules appear exactly once as a source row below (the catalogue's own tally:
  AUTO 13 + GUIDED 15 + MANUAL 8; `R-X1`/`R-X4`/`R-X5` are cross-references to `R-C5`/`R-S4`/`R-T6`
  and are listed separately, not double-counted).
- All **35** MIG rules appear as a target (`MIG-01`…`MIG-35`), each reachable from the reverse index.
- **Retirements: zero.** Every R obligation has a live home. Six rules were provisionally
  predicted as retirements when this bead was ruled (`R-T5`, `R-T10`, `R-Q2`, `R-M2`, `R-S3`,
  `R-X7`); checking each against the shipped compiler refuted all six — see the notes column.
- **One rule was added** to close the single genuine gap: `MIG-35` (from `R-X7`).
- Split/merge cardinality is stated per row. No R rule maps to two incompatible tiers.

### Source direction — every R rule to its canonical home

| R id | class | → canonical | cardinality | disposition, checked against the shipped compiler |
|---|---|---|---|---|
| R-C1 | AUTO | MIG-01 | 3→1 merge | Form-1 single-map-arg header synthesis. MIG-01's detector subsumes it |
| R-C2 | GUIDED | MIG-01 | 3→1 merge | Positional params → prop keys. MIG-01 carries it as the `[price a c]` case, and keeps the GUIDED force as named D flags (`[a & rest]`, multi-arity, a param named `key`). **Its worked example is unsound** — see §Corrections |
| R-C6 | AUTO | MIG-01 | 3→1 merge | `reg-view`/`reg-view*` → `defview`. MIG-01's detector names the unwrap explicitly |
| R-C3 | GUIDED | MIG-16 | 3→1 merge | Form-2 `(let [x (r/atom …)] (fn …))` |
| R-C4 | GUIDED | MIG-16 | 3→1 merge | `r/with-let` (bindings → `local`, `finally` → `effect` cleanup) |
| R-S1 | AUTO | MIG-16 | 3→1 merge | The `@a` / `reset!` / `swap!` operation table. **Amended:** `local` ships as the three-tuple, so `swap!` targets `update!`, not `set!` over a committed value |
| R-C5 | MANUAL | MIG-17 | 1→1 | Form-3 / `r/create-class`. (= R-X1) |
| R-S2 | GUIDED | MIG-19 | 2→1 merge | `reaction` / `r/track` → `reg-sub` + `(sub …)` |
| R-S3 | GUIDED | MIG-19 | 2→1 merge | **Not retirable.** `r/cursor`'s READ side was already MIG-19's; its WRITE side (`(reset! cursor v)` → a named event) was absent from the flag text and has been added |
| R-S4 | MANUAL | MIG-20 | 1→1 | **Not retirable.** `add-watch` on a ratom matched no MIG detector at all; added to MIG-20 with the synchronous-mid-`swap!` vs after-commit hazard stated. (= R-X4) |
| R-T1 | AUTO | MIG-14 | 1→1 | Tag/`.class`/`#id` sugar pass-through. Its `duplicate-id-sugar` check was missing from MIG-14's sub-rules; added |
| R-T2 | AUTO | MIG-07 | 1→1 | `^{:key k}` → `{:key k}` prop |
| R-T3 | GUIDED | MIG-08 | 3→1 merge | Unkeyed `for` bodies (`:rf.ui.compile/unkeyed-list-item`, `constant-list-key`) |
| R-T4 | AUTO/GUIDED | MIG-12 + MIG-13 | 1→2 split | `doall` wrapper strip → MIG-12; markup-returning `map` → MIG-13. **Tier tightened:** R-T4 was AUTO for an inline `fn` literal, MIG-13 is D. Deliberate and already recorded as §Open items 3 — not a silent downgrade |
| R-T5 | GUIDED | MIG-14 | 1→1 | **Not retirable.** `:rf.ui.compile/keyword-child` is a shipped error and the `(name x)`-vs-`(str x)` choice is genuinely the human's. Added as a MIG-14 sub-rule |
| R-T6 | MANUAL | MIG-21 | 1→1 | Dynamic tag heads (`:rf.ui.compile/dynamic-head`). (= R-X5, head clause) |
| R-T7 | AUTO | MIG-09 | 1→1 | `[:> …]` / `adapt-react-class` → foreign head |
| R-T8 | GUIDED | MIG-28 | 1→1 | Computed props maps → `ui/spread` |
| R-T9 | AUTO | MIG-11 + MIG-34 + MIG-14 | 1→3 split | Name-table respelling → MIG-11; `:dangerouslySetInnerHTML` → MIG-34 (with MIG-11 carrying the never-respell exclusion); `collection-attr-value` → MIG-14 sub-rule |
| R-T10 | AUTO | MIG-14 | 1→1 | **Not retirable.** `:rf.ui.compile/multi-form-body` and `nested-for-body` are shipped errors with real mechanical rewrites (fragment wrap; binding-pair collapse). Added as MIG-14 sub-rules |
| R-H1 | AUTO | MIG-04 + MIG-06 | 1→2 split | Bare dispatch-only lift → MIG-04; the `.preventDefault` options-map form → MIG-06. The three detectors partition cleanly (MIG-06's note). Loop-capture case → MIG-08 |
| R-H2 | AUTO | MIG-05 | 1→1 | `%`-extraction → the closed placeholder vocabulary |
| R-H3 | GUIDED | MIG-18 | 2→1 merge | Event-mechanics closures → `ui/event` |
| R-H4 | GUIDED | MIG-18 | 2→1 merge | Local-work-plus-dispatch → the split rule |
| R-H5 | AUTO | MIG-29 | 1→1 | Callback refs → `ui/raw-fn`. MIG-29's internal-view-`:ref` stage note **stands** — `:rf.ui.compile/ref-on-view-s1` / `ref-prop-declared-s1` are still shipped rejections |
| R-H6 | GUIDED | MIG-27 + MIG-10 | 1→2 split | Internal-view fn props → MIG-27; foreign-boundary fn props → MIG-10. The split is the point: the two boundaries have different laws. **MIG-27 amended by C-13a** — a plain fn prop on an internal view is legal and opaque, not a compile error |
| R-H7 | GUIDED | MIG-08 | 3→1 merge | Loop-capturing handlers → extract a keyed child view |
| R-Q1 | AUTO | MIG-02 | 2→1 merge | `@(subscribe …)` → `(sub …)`. Loop facet → MIG-08 |
| R-Q2 | GUIDED | MIG-02 | 2→1 merge | **Not retirable.** MIG-02's "dynamic query args pass through" covered the *rewrite* but dropped the named check (a computed query forfeits the static manifest row); restored as a D downgrade |
| R-M1 | GUIDED | MIG-15 + MIG-33 | 1→2 split | Mount rewrite → MIG-15; the `rf/init!`-plus-adapter clause → MIG-33 |
| R-M2 | GUIDED | MIG-22 + MIG-33 + §Ordering 2 | 1→3 split | **Not retirable.** Inward embed (`ui/raw` + `as-element`) → MIG-22; mixed-page adapter retention → MIG-33; sibling-roots default and the outward `ui/->react` direction → §Ordering 2's closed-subtree law |
| R-X2 | MANUAL | MIG-20 | 1→1 | Def-level shared ratoms as a store |
| R-X3 | MANUAL | MIG-20 + MIG-25 | 1→2 split | `track!`/`run!`/effectful reactions → MIG-20; side-effecting **sub bodies** → MIG-25 |
| R-X6 | MANUAL | MIG-22 | 1→1 | Third-party Reagent wrapper libs (re-com et al.) |
| R-X7 | MANUAL | **MIG-35** | 1→1 (new) | **Not retirable — the one genuine gap.** `r/current-component`, `r/props`/`children`, `r/force-update`, `r/next-tick`, `r/after-render` matched no MIG detector. These calls still **compile** after conversion and fail at runtime, so the compile-gate safety net does not catch them: the migrator must. Added as MIG-35 per this bead's escalation trigger |
| R-X8 | MANUAL | MIG-23 | 1→1 | `reagent.dom.server/render-to-string` call sites |

Cross-references in the catalogue's §7 residue table, not counted in its 36:
`R-X1` = R-C5 → MIG-17 · `R-X4` = R-S4 → MIG-20 · `R-X5` = R-T6 → MIG-21, plus its
"runtime-assembled hiccup" clause → MIG-30.

### Reverse index — every canonical rule to its source

| MIG | source | MIG | source |
|---|---|---|---|
| MIG-01 | R-C1 + R-C2 + R-C6 | MIG-19 | R-S2 + R-S3 |
| MIG-02 | R-Q1 + R-Q2 | MIG-20 | R-X2 + R-X3 (part) + R-S4 |
| MIG-03 | **MIG-only** | MIG-21 | R-T6 (= R-X5) |
| MIG-04 | R-H1 (bare-lift half) | MIG-22 | R-X6 + R-M2 (inward) |
| MIG-05 | R-H2 | MIG-23 | R-X8 |
| MIG-06 | R-H1 (options-map half) | MIG-24 | **MIG-only** |
| MIG-07 | R-T2 | MIG-25 | R-X3 (sub-body clause) |
| MIG-08 | R-T3 + R-H7 + loop facets of R-H1/R-Q1 | MIG-26 | **MIG-only** |
| MIG-09 | R-T7 | MIG-27 | R-H6 (internal half) |
| MIG-10 | R-H6 (foreign half) | MIG-28 | R-T8 |
| MIG-11 | R-T9 (name-table half) | MIG-29 | R-H5 |
| MIG-12 | R-T4 (`doall` half) | MIG-30 | R-X5 (runtime-hiccup clause) |
| MIG-13 | R-T4 (markup-`map` half) | MIG-31 | **MIG-only** |
| MIG-14 | R-T1 + R-T5 + R-T10 + R-T9 (collection-attr) | MIG-32 | **MIG-only** |
| MIG-15 | R-M1 (mount half) | MIG-33 | R-M1 (adapter clause) + R-M2 (mixed pages) |
| MIG-16 | R-C3 + R-C4 + R-S1 | MIG-34 | R-T9 (`dangerouslySetInnerHTML` clause) |
| MIG-17 | R-C5 (= R-X1) | MIG-35 | R-X7 |
| MIG-18 | R-H3 + R-H4 | | |

Five rules are genuinely **MIG-only** — they have no ancestor in the catalogue, and each closes a
real hole rather than restating one:

- **MIG-03** explicit-frame ops (`@(subscribe [:q] {:frame f})`). The catalogue has no rule.
- **MIG-24** ns-requires fixup. Mechanical, must run last, and easy to forget.
- **MIG-26** step-1 ambient ops in plain unregistered fns. A deliberate **scope expansion**: the
  catalogue's R-M2 explicitly pushed this out of scope ("step-1 plain-fn adjustments … are the
  step-1 checklist's job, not this catalogue's"). Making it a runnable rule is the improvement.
- **MIG-31** `capture-frame` → the `(frame)` ops-map body form.
- **MIG-32** framework-shipped Reagent-tier component heads (`route-link`).

### Corrections this collapse made to the historical catalogue

1. **R-C2's worked example emits an unruled target.** Its "after" block rewrites
   `[rf/route-link {…}]` to `[route-link {…}]`. The *conclusion* is correct — positional params
   become prop keys, every call site converts in one change — but the demonstration silently
   asserts a compiled `route-link` that **is not ruled anywhere** (MIG-32; open decision
   rf2-5yovjt). The rewrite is right and its example is not: a reader extending R-C2 would keep
   re-deriving a migration target that does not exist. MIG-01 carries the rule; MIG-32 carries the
   correction; the historical page keeps the example under its historical banner.
2. **The staged-target caveat was stale, and its tier assignments outlived their premise.**
   `local`, `effect`, `ui/event`, `ui/handler`, `ui/render-fn`, `ui/dispatch-fn` and `ui/adapter`
   were all declared unavailable; all seven are exported from `ui.cljc` and compiler-implemented.
   MIG-16/17/18's "this gates" conclusions remain **true** — but the reason changed completely,
   from "the target verb has not shipped" to "the placement/decomposition decision is the
   human's". Re-tiering without rewriting the reason would have left the next reader to check
   whether S3 had shipped, find that it had, and ungate rules that must stay gated. The reasons
   are rewritten, not just the tiers.
