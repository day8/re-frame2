# Pricing the provider SSR hole (`rf2-l0wfx`)

> **A costed proposal. No contract changed, no ruling taken.** `defhost`'s `:ssr`
> is public surface on a shipped declaration form, so the choice below is the
> operator's. This page exists to make it cheap and safe to take: what actually
> breaks, what a reader sees, what each remedy costs, how each could be wrong,
> and the co-edit bill a real fix would present.
>
> Written 2026-08-05 09:44 AUSEST against `origin/main`. Everything under
> "Reproduced" was run; everything under "Reasoned" is argued from React's
> reconciliation law and says so.

`rf2-l0wfx` was filed by raw-escape design C
([`studio/raw-escape-design-c-ergonomics.md`](studio/raw-escape-design-c-ergonomics.md)
§4 and §Needs a ruling R1) and escalated as R2 in
[`studio/raw-escape-spec.md`](studio/raw-escape-spec.md). It is `defhost`'s
question; the `[:>]` escape only inherits the answer.

---

## The failure, reproduced

A `defhost` crossing whose component is a **context provider** — a transparent
wrapper that contributes no markup of its own and exists solely to carry a
subtree — deletes that subtree from the server response.

The witness is [the namespace printed at the end of this page](#the-witness),
dropped into `implementation/freehand/test/re_frame/bench/hicasso/arm1/` and run
with `node out/node-test.js --test=<ns>` after
`node scripts/compile-node-test.cjs node-test out/node-test.js` from
`implementation/`. It was run, then removed — nothing under `implementation/` is
committed by this proposal.

The page under test is the guide's own provider shape with a subtree under it:

```clojure
(def theme-context (react/createContext "unset"))

(defhost themed (.-Provider theme-context))          ; :client-only, the default

(defview provider-page [_]
  [:div.page
   [:h1.title (rt/sub [:l0wfx/title])]
   [themed {:value "dark"}
    [:p.body "THE ENTIRE APPLICATION"]]])
```

`react-dom/server`'s `renderToString`, through the same
`mount/provider` + `codec/root-element` path the shipping SSR rows use:

```
=== default :client-only provider server HTML ===
<div class="page"><h1 class="title">quarterly</h1></div>
```

The `<h1>` sibling is there, so the page rendered. `<p class="body">` and its
text are gone. **The provider took its whole subtree with it.**

Declaring a fallback does not recover it — the fallback *replaces* the subtree
rather than wrapping it:

```
=== {:fallback [:div.theme-skeleton "loading"]} provider server HTML ===
<div class="page"><h1 class="title">quarterly</h1><div class="theme-skeleton">loading</div></div>
```

And no `:ssr` value expresses "render the children". All four spellings an
author would reach for are refused at the declaration:

```
  :ssr :children    -> :rf.error/hicasso-host-bad-ssr-policy
  :ssr :transparent -> :rf.error/hicasso-host-bad-ssr-policy
  :ssr :passthrough -> :rf.error/hicasso-host-bad-ssr-policy
  :ssr :server      -> :rf.error/hicasso-host-bad-ssr-policy
```

### Why

`mint-host-gate!` (`front/codec.cljs`) walks the declared fallback into a single
element once, at the declaration, and the gate returns *that element* whenever
the crossing is unadopted. `props` is never consulted on that arm, so
`props.children` is dropped:

```clojure
(let [placeholder (when (some? fallback) (as-element fallback))
      gate        (fn [props]
                    (if (react/useSyncExternalStore
                          gate-no-subscribe gate-adopted gate-unadopted)
                      (react/createElement component props)
                      placeholder))]
  ...)
```

For a **leaf** widget — a chart, a date picker — dropping children is right;
there are none. For a **transparent wrapper** it deletes the application.

---

## What a reader sees today

**Silent.** Not a hydration mismatch, not a warning, not a diagnostic — a
correct-looking client render sitting on top of server HTML that is missing the
page.

The gate's `useSyncExternalStore` answers `false` from its **server** snapshot
and React reads that same server snapshot again on hydration's first client
pass. So the server HTML and the first client pass agree *by construction*:
there is nothing for React to reconcile and nothing for `onRecoverableError` to
report. The existing green row
`client-only-hydrates-with-nothing-there-and-mounts-after-adoption` asserts
exactly that — `(is (empty? seen))` against React's own error channels — for the
same gate under the same policy.

So the sequence a reader gets is:

| Moment | What exists |
|---|---|
| Server response | The page minus every provider subtree |
| First client pass after `hydrateRoot` | The same — matching, so no mismatch is reported |
| After adoption | The full, correct page appears |

Nothing on the diagnostic bus. No `:rf.ssr/hydration-mismatch`, because there is
no mismatch. **The severity is the silence**: SSR was turned on to put content
in the first response, and the one signal that the content is not there is
looking at the HTML.

No argument on this page rests on `:rf/render-hash` — this tier deliberately
carries none (`rf2-2rtt6.91`), and React's adoption is the only check there is.

---

## Three places the record is stronger than the facts

The bead, design C and `raw-escape-spec.md` all say the provider case has **no
recovery at all**. For `[:>]` that is exactly right — it carries no declaration,
so there is nothing to set. For `defhost` it is too strong.

### 1. A workaround exists, and it is bad rather than absent

A `defview` head inside a fallback mints and renders. Measured:

```clojure
(defhost themed (.-Provider theme-context)
  {:ssr {:fallback [shell {}]}})           ; the subtree, written a second time
```

```
=== subtree smuggled into the fallback ===
<div class="page"><section class="shell"><p class="body">SUBTREE</p><h2 class="sub">quarterly</h2></section></div>
```

The subtree is in the server HTML **and its subscription read** (`quarterly`).
So the recovery today is: write your subtree twice, once as the provider's
children and once as its fallback. The price is duplication, no context value
server-side, and a full remount at adoption (below). That is a bad answer, not a
missing one — which lowers the *urgency* without changing the *shape* of the
ruling.

### 2. The fallback is not inert markup

`mint-host-gate!`'s docstring states the rule the guide teaches: *"a fallback is
inert markup — it is not a body, so a subscription or an intent written there is
the same loud error it would be anywhere outside a boundary."* Half of that is
enforced and half is not. Measured:

| Written in a fallback | What happens |
|---|---|
| `[:button {:on-click [:x/y]} "go"]` | refused — `:rf.error/hicasso-intent-outside-boundary` |
| `[shell {}]` (a `defview` head) | mints; renders a live boundary that reads subscriptions |

A boundary head defers its body to render time, where the frame comes from React
context — so it is frame-correct, and it is the mechanism the workaround above
rides on. Nobody has ruled whether that is a feature or a hole. Filed
separately as `rf2-nv07k`; it is not part of this proposal.

> **Addendum, 2026-08-05 (`rf2-nv07k`, PR #7525).** Two measurements taken since
> make the workaround **worse than priced above**, and both bear on which
> candidate wins here. First, the workaround's placeholder **is not a value**:
> one declaration, walked once into one element, renders `ALPHA` in one frame,
> `BRAVO` in another and `ALPHA-TWO` after a write — so `mint-host-gate!`'s own
> justification for walking once (*"a placeholder that differs per site is not a
> placeholder"*) is falsified by what it permits. Second, it **does not survive
> this arm's other boundary variant**: a frame-fed head (`mark-frame-prop!`,
> `rf2-2rtt6.39`) reads `intent/*frame*` at element-creation time, which in a
> fallback is mint time, where the var is `nil` — so it bakes `nil` in and throws
> `:rf.error/no-frame-prop` one render into the server response. Whether the
> workaround works at all is therefore a property of which mint the head came
> from. Every row is pinned in
> `arm1/fallback_contents_cljs_test`. **Candidate D's cost rises accordingly** —
> what D would document is this workaround, and it is variant-fragile rather than
> merely ugly.

### 3. The guide citation is stale

The provider example is at **`draft-guide/05-interop.md:153`**, under
`## Providers` at line 148. `:120` — the line the bead, design C §4, design C R1
and `raw-escape-spec.md` R2 all cite — is now inside the `:handler` paragraph.
Worth correcting whenever those pages are next touched; not worth a PR of its
own, and this proposal does not touch them.

Separately: **Spec 011 and Spec 009 do not reason about `defhost`'s `:ssr` at
all.** `spec/009-Instrumentation.md` carries no `hicasso-host-*` error id — the
whole family, `:rf.error/hicasso-host-bad-ssr-policy` included, is uncatalogued —
and `spec/011-SSR.md` names neither `hicasso` nor `defhost`. The spec surface
that *does* reason about a host `:ssr` policy is Spec 004/004B/API/008 and the
Freehand conformance fixtures, and that is a **different door**. See
[The bill](#the-bill-what-a-real-fix-touches).

---

## The reconciliation law that prices every candidate

React reconciles a position by element **type**. The gate *is* the element's
type, and the policy is expressed by what the gate returns. So:

> **Any policy whose unadopted branch returns something other than the component
> pays a full subtree destroy-and-rebuild at adoption.**

Today that is `{:fallback …}`, and it costs nothing worth naming: the thing torn
down is inert skeleton markup. The existing green row
`a-fallback-hydrates-as-the-placeholder-and-is-swapped-after-adoption` witnesses
the swap directly — `.chart-skeleton` gone, `.chart` present.

The moment the thing being torn down is the **application subtree**, that same
law stops being free: the server DOM is adopted cleanly and then immediately
discarded, taking focus, scroll position, uncontrolled input state and every
effect in the subtree with it. This is *reasoned* from React's law plus the
witnessed fallback swap, not separately measured — it needs a real DOM, and the
hydration rows that would carry it live in the browser lane.

It is the single fact that separates the candidates.

---

## The candidates, priced

### A — a third value, `:ssr :children`

Render `props.children` in place of the component until adoption. Design C's
recommendation, and the bead's option (a).

**Costs the programmer** one keyword on the declaration. It reads honestly:
`:children` is what a transparent wrapper *is*.

**Costs the implementation** one arm in `declared-ssr`, one branch in
`mint-host-gate!`'s unadopted path, and the end of "the placeholder is walked
once at mint and reused at every site" as a total description of the gate — the
unadopted arm now reads `props`.

**Forecloses** little. The escape stays declaration-free and inherits it the
same way it inherits everything else, which is precisely Design C's argument:
with A, "declare it" becomes a real answer to "my provider vanished".

**How it could be wrong** — three ways, and the first is measured:

1. **It restores the markup and not the value.** A consumer with no Provider
   above it reads the context **default**. Measured, both arms:

   ```
   consumer with NO provider above it:  <span class="theme-reader">unset</span>
   consumer WITH provider above it:     <span class="theme-reader">dark</span>
   ```

   `:ssr :children` builds exactly the first tree. So a themed subtree renders
   server-side under the *default* theme and flips at adoption. For a theme that
   is a flash; for a provider carrying auth, locale or a router it is a wrong
   first paint. **Silent-absent becomes silent-wrong**, and silent-wrong is the
   harder defect to find.

2. **The remount.** Per the law above, the position's type goes from the
   children to the Provider at adoption, so React destroys and rebuilds the
   whole subtree immediately after hydrating it. Every provider that wraps the
   app root remounts the app.

3. **It is a lie for a non-transparent wrapper.** `:ssr :children` on a
   component that puts its children inside its own markup emits HTML that is
   structurally not what the client will show — and because both passes agree,
   nothing reports it. The value asks the author to assert transparency and
   gives no way to check the assertion.

### B — a third value, `:ssr :render`

Run the component on the server. The author asserts "this component is
server-safe"; the declaration mints the component as the element's own type and
no gate at all.

**Costs the programmer** one keyword — but it is an *assertion about the
component*, which is a heavier thing to ask than a choice of placeholder.

**Costs the implementation** arguably less than A. For this policy there is no
gate to mint, which is HD-011's original zero-wrapper, zero-fiber, zero-hook
shape restored for the hosts that can take it. One arm in `declared-ssr`, one
branch at the head-minting site.

**What it buys over A**: the context value is correct server-side (the `dark`
control above), the element type never changes, so **no remount** — and the
first paint is the real thing rather than a stand-in.

**How it could be wrong:**

1. **The assertion can be false**, and then the server throws
   `window is not defined`. But that is *loud*, at the crossing, naming the
   host — the guide already has the troubleshooting row for it. Contrast A,
   which fails silently.
2. **It partially reverses the 2026-08-04 ruling's stated reason.** The
   conservative default exists because "a foreign React component is exactly the
   node whose render may reach for `window` — the door cannot know, so it does
   not guess." B does not make the door guess; it lets the author say. But
   `{:ssr :server}` is today a *named refusal row* in
   `host_ssr_dom_cljs_test`, so B rewrites an assertion rather than adding one.
3. **"One mechanism, three places" becomes "one mechanism, and one policy that
   needs none."** `host-ssr`'s docstring — *"the policy is enforced by the gate,
   which is the element's type"* — restates as "enforced by which type the
   declaration mints". True either way, but it is a sentence three documents
   repeat.
4. **It does nothing for a provider whose value is genuinely client-only** (a
   provider holding a `window`-derived value). Such a provider still has to
   choose between A's answer and disappearing.

### C — make the existing values render children

Two shapes: `:client-only` renders children when the crossing has any (C1), or
`{:fallback …}` wraps the children rather than replacing them (C2).

**Costs the programmer** nothing — there is nothing to write. **Costs the
implementation** the least of any candidate: one branch, no new value, no new
refusal, no new doc row. And nothing currently green goes red: no host in the
`arm1` server-render rows carries children, and no host in the `ssr/` fixture
corpus does either — so not even the pinned byte budgets move. **C is the
cheapest candidate on every axis a gate can measure**, which is exactly why the
objections below have to be the ones that decide it.

**How it could be wrong** — and this is the strongest objection on the page:

1. **It is the door guessing**, which is the one thing this codec's every law
   exists to refuse. The same declaration would behave two ways depending on
   whether a call site happened to pass children.
2. **It silently redefines `:client-only`.** The ruling, the macro docstring,
   the codec comment, the guide and the SSR chapter all say it renders
   *nothing*. C1 makes that false without anyone writing anything.
3. **It removes an expressible intent.** "Drop this subtree server-side" is a
   real thing to want — a modal, a portal, a client-only shell — and C1 takes
   away the only way to say it.
4. C2 is incoherent on its own terms: a fallback is *instead of*, by definition.
   A fallback that also wraps is two features on one key.
5. It inherits every one of A's wrongness modes (default context value, remount,
   the transparency lie) **without the opt-in that makes them the author's
   choice.**

### D — do nothing; document the trap

Rule providers out of SSR and say so: a sentence in `05-interop.md`'s Providers
section, a troubleshooting row in `10-server-side-rendering.md`. The bead's
option (b).

**Costs** two doc edits and no implementation. **Forecloses** nothing that a
later ruling could not reopen.

**How it could be wrong:** it rules out one of HD-011's five *named* use cases —
"providers an ecosystem library hands you" — rather than documenting it. And
what it would document is the workaround above: write the subtree twice, get no
context server-side, remount at adoption. Documenting that honestly is close to
documenting that Hicasso does not do providers under SSR. A provider wrapping a
subtree is not exotic; it is what an ecosystem library hands you.

### The comparison

| | Programmer cost | Implementation cost | Subtree in server HTML | Context correct server-side | Remount at adoption |
|---|---|---|---|---|---|
| A `:ssr :children` | one keyword | one branch, one arm | yes | **no** | **yes** |
| B `:ssr :render` | one keyword, an assertion | one arm, no gate for it | yes | yes | no |
| C existing values | none | one branch | yes | no | yes |
| D document it | write the subtree twice | none | only via the workaround | no | yes |

Six columns, six header cells, six cells per row.

**What this page recommends is that A and B be ruled together rather than A
alone.** Design C reached A without the context measurement or the remount law
in hand; with both, A fixes the deletion and leaves a wrong value and a discarded
first paint behind it. B fixes all three for the provider case specifically —
which is the case that filed the bead — and does it with less machinery, at the
price of asking the author for an assertion the door cannot check. Neither is
obviously right, and that is the ruling.

**What this page recommends against** is C, on the door-does-not-guess law, and
any two-key `:ssr` map. The remedy has to be the smallest thing that makes the
case *expressible*, not a general SSR-policy language.

---

## The bill: what a real fix touches

The number that usually decides a ruling. Two rosters, because there are **two
independent `defhost` doors** with an `:ssr` option and a "there is no third
value" clause, and the operator's first choice is whether they stay in step.

### If the fix is scoped to Hicasso (the bench lane): ~23 co-edits

| Surface | Sites |
|---|---|
| `front/codec.cljs` | `mint-host-gate!`, `declared-ssr`, the §`:ssr` policy comment ("TWO VALUES"), `mint-host!`'s docstring, `host-ssr`'s "enforced by the gate" docstring |
| `arm1/lang.clj` | the `defhost` macro docstring — "There is no third value" |
| `arm1/host_ssr_dom_cljs_test.cljs` | ns docstring, the refusal suite, the mutation-witness paragraph, **plus a new server-render children row** — the only children-through-the-door row today runs post-adoption, which is why this defect has no failing witness |
| `ssr/fixtures.cljs`, `ssr/entry_cljs_test.cljs` | a corpus row for the new policy |
| `ssr/driver.cjs`, `ssr/bake_bytes.test.cjs` | pinned byte budgets move if the fixture corpus grows |
| `arm1/hydrate_dom_cljs_test.cljs` | the boundary-count-vs-body-runs row, per `raw-escape-spec.md`'s standing instruction to restate rather than loosen it |
| `decisions.md` | a **new dated addendum** under HD-011 (the existing one is a dated record and stands as written); optionally the stale HD-020(d) "inert in v0" line |
| `draft-guide/05-interop.md` | the Defaults table's SSR cell, the Providers section, the "Not settled" row |
| `draft-guide/10-server-side-rendering.md` | the three-line policy block, the prose under it, the troubleshooting row |
| `draft-guide/11-performance.md` | the `:ssr` code sample |
| `studio/raw-escape-spec.md` | the comparison table and R2 |
| `production-server-arm.md` | the pieces table, and a line-range citation into `codec.cljs` that moves |
| `EP-0038` | R5's status line |

The three `studio/raw-escape-design-{a,b,c}` documents are dated design records,
not live surfaces; a dated addendum is the most any of them should take.

**Spec 011, Spec 009 and the Spec 009 error catalogue take zero edits** — they
say nothing about this door. That is worth stating plainly, because it is the
opposite of what the surface reads like from outside.

### If the two doors stay in step: 50+ more

Freehand's `v/defhost` is the shipped, spec-normative door, and it has the same
hole by explicit ruling rather than by oversight. `spec/004-Views.md` §Structure
and SSR says the SSR projection is *"the declared fallback, or empty"* and that
*"the caller's trailing children are **COUNTED, not walked**"*, with a stated
rationale: Freehand never executes the registered React component on the JVM, so
where that component would place the children is unknowable and a server tree
showing them would invite assertions on content the server never emits.

**That rationale does not transfer.** Hicasso's server render *is* React —
`renderToString` over the same runtime — so the component either runs or does
not, and there is no structural tree to lie in. The two doors can honestly
diverge, and scoping the fix to Hicasso is defensible on those grounds.

If they are kept in step anyway, the additional bill is `descriptor.cljc`'s
`host-ssr-policies` set and spelling classifier, `freehand.cljc`'s option roster
and two refusals and the `defhost` docstring, `react.cljs`'s host phase,
`tree.cljc`'s structural walk, `node.cljc`, `test.cljc`, **Spec 004 (three
passages), Spec 004B (three passages), Spec 008, Spec API.md, Spec
Conventions.md**, the FH-REACT-006/007/008 conformance index entries and their
three EDN fixtures, four `docs/core/freehand/` pages, two `docs/design/freehand/`
pages, `docs/api/re-frame.freehand.md`, and nine Freehand test namespaces
asserting `:rf.ui/host-ssr`.

---

## What this proposal does not do

- **It changes no contract.** No `implementation/` file, no `spec/` file, no
  guide page, no `decisions.md` entry, no `studio/` document was edited. The
  witness namespace was created, run, and removed.
- **It takes no ruling.** A, B, C and D are all live.
- **It does not price the `[:>]` escape's own behaviour.** The escape carries no
  declaration and inherits whatever lands here; `raw-escape-spec.md`'s clause 6
  (renders nothing) is unchanged by every candidate above, and its standing
  instruction — the escape must never grow an `:ssr` spelling of its own — is
  untouched.
- **It does not measure the remount.** That needs a real DOM and belongs with
  whoever lands the fix, in the browser lane, beside the hydration rows.

---

## The witness

Drop this at
`implementation/freehand/test/re_frame/bench/hicasso/arm1/l0wfx_provider_ssr_dom_cljs_test.cljs`,
compile the `node-test` build, and run it with
`--test=re-frame.bench.hicasso.arm1.l0wfx-provider-ssr-dom-cljs-test`. Four of
its twelve assertions fail on `origin/main`; each failure message carries the
server HTML that produced it.

```clojure
(ns re-frame.bench.hicasso.arm1.l0wfx-provider-ssr-dom-cljs-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview defhost]]))

(def ^:private frame-id ::l0wfx)

(rf/reg-sub :l0wfx/title (fn [db _] (:title db)))
(rf/reg-event :l0wfx/seed (fn [_ _] {:db {:title "quarterly"}}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter :ambient-frame nil
     :init-fn (fn [] (rt/reset-runtime!))}))

(defn- fresh! []
  (lane/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:l0wfx/seed]))
  frame-id)

(def ^:private theme-context (react/createContext "unset"))

(defhost themed (.-Provider theme-context))

(defhost themed-fallback (.-Provider theme-context)
  {:ssr {:fallback [:div.theme-skeleton "loading"]}})

(defview provider-page [_]
  [:div.page
   [:h1.title (rt/sub [:l0wfx/title])]
   [themed {:value "dark"} [:p.body "THE ENTIRE APPLICATION"]]])

(defview provider-fallback-page [_]
  [:div.page
   [:h1.title (rt/sub [:l0wfx/title])]
   [themed-fallback {:value "dark"} [:p.body "THE ENTIRE APPLICATION"]]])

(defn- server-html [hiccup]
  (react-dom-server/renderToString
    (mount/provider frame-id (codec/root-element frame-id hiccup))))

(defn- error-id [f]
  (try (f) ::did-not-throw (catch :default e (:rf.error/id (ex-data e)))))

(deftest a-provider-crossing-deletes-its-subtree-from-the-server-render
  (fresh!)
  (testing "DEFAULT :client-only — the guide's own provider example"
    (let [html (server-html [provider-page {}])]
      (is (re-find #"quarterly" html) (str "sanity: the page DID render — " html))
      (is (re-find #"THE ENTIRE APPLICATION" html)
          (str "the provider's SUBTREE should be in the server HTML: " html))
      (is (re-find #"class=\"body\"" html)
          (str "the child element should be there: " html))))
  (testing "{:fallback …} — does the fallback WRAP the subtree or REPLACE it?"
    (let [html (server-html [provider-fallback-page {}])]
      (is (re-find #"theme-skeleton" html) (str "the fallback is there: " html))
      (is (re-find #"THE ENTIRE APPLICATION" html)
          (str "and the subtree should survive alongside it: " html)))))

;; A faithful PROXY for what candidate A would emit: the children rendered
;; with NO provider above them.
(defn- theme-reader [_props]
  (react/createElement "span" #js {:className "theme-reader"}
                       (react/useContext theme-context)))

(deftest what-candidate-a-would-emit-for-the-context-value
  (fresh!)
  (testing "a consumer with no Provider above it reads the context DEFAULT"
    (let [html (react-dom-server/renderToString
                 (react/createElement theme-reader #js {}))]
      (is (re-find #"dark" html)
          (str "under candidate A a consumer reads the DEFAULT: " html))))
  (testing "and with the provider present it reads the declared value"
    (let [html (react-dom-server/renderToString
                 (react/createElement (.-Provider theme-context) #js {:value "dark"}
                                      (react/createElement theme-reader #js {})))]
      (is (re-find #"dark" html) (str "control: " html)))))

(defview shell [_]
  [:section.shell [:p.body "SUBTREE"] [:h2.sub (rt/sub [:l0wfx/title])]])

(deftest can-the-subtree-be-smuggled-through-the-fallback
  (fresh!)
  (testing "an INTENT in a fallback is refused — walked at MINT, outside a boundary"
    (is (= :rf.error/hicasso-intent-outside-boundary
           (error-id #(codec/mint-host! "l0wfx/intent-fb" (.-Provider theme-context)
                                        {:ssr {:fallback [:button {:on-click [:x/y]}
                                                          "go"]}})))))
  (testing "but a defview in a fallback renders a LIVE subtree server-side"
    (let [h    (codec/mint-host! "l0wfx/view-fb" (.-Provider theme-context)
                                 {:ssr {:fallback [shell {}]}})
          html (react-dom-server/renderToString
                 (mount/provider frame-id
                   (codec/root-element frame-id
                     [:div.page [h {:value "dark"} [:p.body "SUBTREE"]]])))]
      (is (re-find #"SUBTREE" html) (str "workaround verdict: " html))
      (is (re-find #"quarterly" html) (str "and its subscription read: " html)))))

(deftest no-ssr-value-expresses-render-the-children
  (testing "the four spellings an author would reach for are all refused"
    (doseq [v [:children :transparent :passthrough :server]]
      (is (= :rf.error/hicasso-host-bad-ssr-policy
             (error-id #(codec/mint-host! (str "l0wfx/" (name v))
                                          (.-Provider theme-context)
                                          {:ssr v})))))))
```

---

## Further reading

- [`decisions.md`](decisions.md) — HD-011 and its 2026-08-04 `:ssr` addendum
  (the "there is no third value" ruling), HD-020(b)/(d)
- [`studio/raw-escape-design-c-ergonomics.md`](studio/raw-escape-design-c-ergonomics.md)
  — §4 (the provider use case) and §Needs a ruling R1, where this was found
- [`studio/raw-escape-spec.md`](studio/raw-escape-spec.md) — R2, and the
  standing instruction that the escape grows no `:ssr` spelling of its own
- [`draft-guide/05-interop.md`](draft-guide/05-interop.md) — the Defaults table
  and the Providers section
- [`draft-guide/10-server-side-rendering.md`](draft-guide/10-server-side-rendering.md)
  — the tier's mismatch story and the `:ssr` teaching block
