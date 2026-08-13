# The routing and async-resource recipes

The evidence for [specification.md](specification.md#7-complete-use-case-coverage) §7's *Validation and async normalization* row (**late result cannot clobber newer edits**), the *Async resources and mutations* row's mutation-status and settle-merge half, and the dirty-leave half of its *Routing and navigation* row.

`rf2-hic-054` **changes no runtime and adds no namespace to any artefact.** It is three recipes, written as one small application on the shipped public doors, plus the two suites that hold them and the one witness that holds *this page* to them.

They are the **standing** answer rather than the residual one. [`resource-demand-verdict.md`](resource-demand-verdict.md) returned **STOP** on committed-read resource demand — four criteria met, two ambiguous, and ambiguity resolves to STOP by the frozen rule — so these recipes carry the whole of acquiring and releasing resources against read liveness. Their future shape is an ordinary evolution of these doors, not a holding pattern awaiting a mechanism: a reopen needs new evidence of a kind the [`rf2-hic-044` witness](resource-demand-witness.md) could not supply, and its nine-site ownership census is the population they should be readable against.

## What was built, and where it runs

Four files under `implementation/routing/test/re_frame/recipes/` — one application namespace and three suites. `routing/test` is already a `:source-paths` entry both CLJS test lanes compile from *and* an `:extra-paths` entry on the routing artefact's JVM `:test` alias, so no build id, no `:dev-http` port and no roster entry were added, and no hot-zone file was touched.

| suite | lane | what it owns |
|---|---|---|
| `re-frame.recipes.async-nav-l0-cljs-test` | `:node-test` | every rule of all three recipes — first as pure functions, then through a real frame — plus the clobber control and the structural row that names the guard on the route |
| `re-frame.recipes.async-nav-guard-dom-cljs-test` | `:browser-test` | the one claim no model row can make: the browser's own Back button, and the address bar the guard puts back |
| `re-frame.recipes.async-nav-doc-test` | JVM `:test` | **this page.** Every `:optimistic` target printed below is read as data and matched against the ones the application registers, so a snippet teaching a shape the runtime rejects reds here rather than in a reader's application |

The application reaches **four** foreign namespaces — `re-frame.core`, `re-frame.http.managed`, `re-frame.resources` and `re-frame.routing` — and nothing else. There is no view-substrate dependency in the model at all; the view exists for the browser suite and is thirty lines of Reagent-adapter hiccup.

## Why the three sit in one application

They are not three independent tricks. **Recipe 3's guard reads the state recipe 1's merge produces.** A settle-merge that clobbered a touched field leaves the draft equal to the baseline — so `can-leave?` answers `true`, the guard opens, and the user walks away from work that had already been silently discarded. Two defects composing into a third, which neither witness alone would catch, and which has no home if the recipes are written apart.

That is also why the application is an editor rather than three fragments: the corpus shape these recipes are read off is one screen where a load seeds a form, a write reports its own status, and leaving is guarded.

## The three recipes, and the door each is written on

### 1. Settle-merge and reply correlation

Two gates, in order, answering different questions — and the ordering is the recipe.

**Correlation** is one `not=`. The reply target carries the slug (`:on-success [::article-arrived slug]`), so the handler can ask whether this reply is about the article the editor is still holding. The runtime suppresses a reply superseded within one `:request-id`; it does not and cannot suppress a reply for a *different* article the user has since navigated away from, because that request was never superseded — it was **abandoned**, and an abandoned request still replies. That half is the receiver's, and R-C2 is the requirement that makes it possible: a reply that cannot say which request it answers is a reply the receiver cannot refuse.

**The merge** is field-wise. Of the fields the accepted reply carries, seed only those the user has not touched:

```clojure
(defn settle-merge [draft payload touched]
  (reduce-kv (fn [acc field value]
               (if (contains? touched field) acc (assoc acc field value)))
             draft payload))
```

`(merge draft payload)` is not the same function and is not a shortcut for it: `merge` lets the payload win every key it carries, which is precisely the fields the user has been typing into. The defect this replaces is the whole-slice write — `(assoc db :editor (editor-slice payload))` — which is correct on every load that beats the user to the keyboard, which is most of them in development and rather fewer of them on a slow connection.

**The baseline takes the payload whole regardless.** That asymmetry is deliberate and it is the half a first attempt gets wrong: the baseline is what the *server* said, and a half-updated baseline would report saved work as dirty for the rest of the session — which, through recipe 3, is a prompt the user cannot dismiss.

`:touched` is a set of field keys written by the edit handler and read by the merge. Nothing else writes it, and **an arriving payload never clears it**: the fact that the user has touched a field outlives the load that was in flight when they did.

### 2. Per-instance mutation status, with an optimistic plan

The write is a `reg-mutation` run under a per-row **instance** id — `[::favourite slug]` — and the row's spinner, its error slot and its "already showing your change" affordance are all projections of `[:rf/mutation {:instance …}]`. There is no `:saving?` key in this application for a failure branch to forget to clear, and no completion callback whose arity anybody has to sniff: completion arrives at a named event because `:reply-to` said so.

Per **instance** rather than per mutation is the whole of R-C5. A shared instance makes every row spin because any row is, and paints one row's rejection on its neighbour.

The optimistic half is one registration key:

```clojure
:optimistic (fn [{:keys [slug favourite?]}]
              {{:resource articles-resource
                :params   {}
                :scope    :rf.scope/global}
               (fn [articles] …)})
```

**The target is a map — `{:resource :params :scope}` — and the `[id params]` vector a reader of `[:rf/resource …]` reaches for is not a near-miss.** Optimistic arms run *before* the request lowers, so a target that could write the cache under a wrong identity is rejected outright rather than dropped-and-warned, and it takes the request with it: no instance, no request, `:idle` afterwards. That much is the *ruled* answer and not a hole — an instance minted and left `:pending` with no request behind it would be a write reporting itself in flight forever, which is `rf2-06lp`'s granularity line drawn one registrar up.

**The refusal itself is loud, at source, and carries its reason.** `validate-target-key!` throws the catalogued `:rf.error/mutation-invalid-target` under the strict pre-write policy, naming `:recovery :fix-mutation-target`, the offending `:arm`, and the target the caller actually typed; the router captures it and fans it on the always-on `:errors` axis as `:rf.error/handler-exception`, which is where it is legible in a development build *and* in a production one. `rf2-e4y9` established that — its row is `vector-shaped-optimistic-target-refuses-readably` in `resources-optimistic-validation-cljs-test` — and no runtime source had to change for it.

**What the refusal does not do is print.** re-frame2 ships no default console sink, and the router captures the throw rather than re-throwing it to `dispatch-sync`'s caller, so a developer who has attached no `:errors` listener sees nothing on stdout, stderr or `window.onerror`. That is framework-wide rather than this arm's — every framework refusal, down to an unregistered event id, is invisible on the same terms — and it is the open question `rf2-fu75` holds. So the first reading of this defect as *silent* was a reading taken from the console, the one channel a re-frame2 refusal never reaches on its own.

None of which makes a wrong snippet cheap, and that is why `re-frame.recipes.async-nav-doc-test` exists. A code block is the one part of a page a reader copies, this one was wrong from birth, and both suites beside it were green throughout — correctly, because they exercise the *application*, which was right. The gate's subject is the snippet's shape and its match to the application, not the runtime's volume.

`:on-conflict` is left at its `:invalidate` default deliberately. If a concurrent write landed on the entry between the optimistic apply and the rollback, a blind restore would clobber newer truth, so the entry is marked stale and the read path fetches the authoritative answer.

**The application writes no rollback code at all.** What it demonstrates is the *consumer composition* — two rows in flight at once that do not share a status, and `:optimistic?` going false in the same read the view already had when the write is rejected. The apply / rollback / reconcile contract and its `:on-conflict` enum belong to the resources artefact's own `resources-optimistic-apply-cljs-test` / `-settle-` / `-validation-` suites, and are not re-measured here.

### 3. The dirty-navigation guard

One boolean sub on one route key:

```clojure
(rf/reg-sub ::can-leave? (fn [db _] (not (dirty? (:editor db)))))

(routing/reg-route ::editor {:can-leave [::can-leave?] …} "/…/:slug/edit")
```

Read **positively** — `true` means leaving is fine. A guard that answered the dirty flag directly is the classic polarity bug: it reads as though it works and blocks exactly when it should allow. And **strictly boolean**, because a non-boolean fails closed with `:rf.error/can-leave-non-boolean`, so a guard answering `nil` for "no editor open" would deny every navigation in the application. `not` is written rather than `if` for that reason.

`dirty?` is one *definition*, called by the guard sub, by the badge's sub and by the save handler. R-A6's failure in its navigation form is two recomputations of "is this dirty?" drifting apart, and one definition is what makes that impossible. A materialised flow is the other honest answer, buys a value a tool can see, and costs a registration and a boot event; it is not paid for here.

`:bypass-leave? true` on the save-and-close path states an intent that saving would have satisfied anyway. It skips *this* route's `:can-leave` for *this* navigation; the target's `:can-enter` still runs, because an "enter anyway" flag would be a hole straight through the auth gate.

The reader-facing version of this recipe already ships at [`guard-unsaved-changes.md`](../../../routing/how-to/guard-unsaved-changes.md), including its zero-DOM test. What this bead adds is not the prose — it is the half that page's test cannot reach.

## The Back button, and why it needed a browser

A **programmatic** navigation is a forward door: the address bar has not moved when the guard runs, so blocking it leaves nothing to undo. The Back button is not. By the time `popstate` reaches the application the browser has **already** changed the URL, so a guard that merely declines to commit leaves the user in the editor with the list's address in the bar — and the next reload, copy or bookmark takes that address at its word and discards the draft.

Routing answers that with `:rf.nav/replace-url` on the leave-block path (`re-frame.routing.decisions/decide`, the `url-driven?` arm), a replace rather than a push so no history entry is added, and it is emitted **before** the user-facing event so a dialog reading `current-url` sees the restored value. Whether the address bar actually goes back is a fact about `window.history`, and the only instrument that can read it is a browser.

The browser row therefore asserts both sides of the asymmetry: after a real `history.back()` the route has not moved *and* `location.pathname` reads the editor's URL again, with `:url-restored?` recorded on the pending value; after a programmatic leave the address bar never moved and there is nothing to put back.

## What was built on rather than rebuilt

**The `:url-bound?` real-history seam is `rf2-hic-042`'s** (`re-frame.routing-conduct-dom-cljs-test`, PR #8031). It established the arrangement this bead reuses: a frame whose registration installs routing's real `popstate` listener; a deep link that is a URL the browser is genuinely sitting on rather than an `:initial-events` dispatch; `history.back()` and `history.forward()` rather than a hand-written `:rf.route/handle-url-change` carrying a synthetic cause; and the borrowing discipline — `location.href` captured at namespace load, `replaceState`d back in the single trailing step both paths reach, and no row ever going back past the entry it started on. Focus-on-route and scroll restoration are that file's subject and are not re-measured here.

**The runtime's own stale fence is not re-witnessed.** A reply superseded within one mutation instance never reaches `:reply-to` at all; that is proved in `re-frame.hicasso.examples.forms.l0-cljs-test` and in the typeahead's `a-late-reply-cannot-clobber-a-newer-term`. What recipe 1 guards is the half the runtime explicitly does not.

**Per-instance mutation status already landed** with [`forms-recipes.md`](forms-recipes.md) §3. This page does not re-argue it; recipe 2 exists for the *optimistic* composition on top of it, and says so.

## The trap classes, and where each is decided

| trap | decided by | how it reds |
|---|---|---|
| late settle clobbering a touched field (R-C1) | `l0`, guarded row plus the control | the control writes the very same payload as a whole slice and shows the keystrokes gone |
| a reply that cannot name its request (R-C2) | `l0`, structurally and behaviourally | the reply target is asserted to carry the slug; drop the slug and the cross-article row cannot distinguish anything |
| status colliding across instances (R-C5) | `l0` | two rows in flight, one rejected — the neighbour must stay pending and error-free |
| a guard read negatively, or non-boolean | `l0` | `can-leave?` is asserted `boolean?` in both positions, and the route's `:can-leave` key is asserted present on the editor and absent on the list |
| a blocked Back leaving the URL moved | `browser` | `location.pathname` after a real `history.back()` |
| a prompt that is not ordinary view code | `browser` | the negative control asserts NO prompt node exists until something is pending |
| **this page** printing an `:optimistic` target the runtime rejects | `doc-test`, JVM | the published target is read as data; the `[id params]` vector fails the shape row *and* is absent from the set the application registers |

Every one of those has its second direction. The clobber row's twin asserts that with nothing touched the recipe **is** the naive write — which is why the defect survives every load that beats the typist, and why a witness that only tested the happy path would be green forever. The prompt rows assert both presence and absence. The `Stay` row asserts the work is still in the field afterwards, because cancelling the leave must not also cancel the edits it was protecting.

## What did not hold at source

1. **There is no "census article-editor witness" for late arrival.** The bead's deliverable line says the settle-merge recipe should *elevate the census article-editor witness*. The file that carries that name — `implementation/freehand/test/re_frame/bench/hicasso/front/census_article_editor_cljs_test.cljs` — is about the `:&` merge spelling on a census-real screen, and asserts nothing about late replies or touched fields. Its only use of the word *clobber* is attribute-merge clobber, which is a different thing with the same name.

    The actual R-C1 law lives in two places, neither of them a witness that could be elevated: `spec/conformance/freehand/fixtures/fh-ctrl-013.edn` with `implementation/freehand/test/re_frame/freehand/form_cljs_test.cljc`, which is the *freehand* substrate's leafwise seed rather than a consumer recipe; and `docs/design/freehand/studio/fitness-harness.md`, which records at its own §C.2 that the same-slug typing clobber is **still live** in the corpus and *"a harness case no green suite currently covers."*

    So the settle-merge recipe was **written**, not elevated, and its control row is what makes the class reachable. The corpus defect itself (`examples/real-apps/realworld_resources/article_editor.cljs`, bd `rf2-y4mgw`) is untouched — fixing an example is that bead's, and this one is a recipe.

2. **The dirty-nav guard's "wiring point" in `rf2-hic-042` is the real-history seam, not a guard slot.** The `:can-leave` key has shipped since Spec 012 and its semantics are covered by `routing_can_leave_test.clj`. What PR #8031 actually contributed to this bead is the `:url-bound?` browser arrangement described above — which is the wiring point that matters, because it is the only one that can hold a *real* Back button. Nothing in `routing_conduct_dom_cljs_test.cljs` was edited.

3. **A mutation whose id is not registered aborts before it mints anything, and is legible only to a listener** (`rf2-06lp`, filed by `rf2-hic-051`). `require-mutation-spec!` is the first statement of the execute handler and throws `:rf.error/mutation-not-registered` naming the id, so no instance and no request follow and `[:rf/mutation …]` reads `:idle` afterwards, byte-identical to never having written. The refusal reaches the `:errors` axis and nowhere else — the same absent console sink as recipe 2's target refusal, and the same `rf2-fu75`. This application takes the workaround the symptom calls for either way — a named `register-resources!` called from each suite's fixture rather than a namespace-load registration — because a reset fixture restores the registrar to a captured baseline and the resources artefact clears the mutation kind outright.

## What this page does not claim

It re-measures nothing. Focus-on-route, scroll restoration and prefetch belong to `rf2-hic-042` and `rf2-2n3p`; the debounce, supersession and cancellation figures belong to [the typeahead witness](resource-demand-witness.md); the optimistic apply/settle contract belongs to the resources artefact; the touched/submit-attempt display gating belongs to [`forms-recipes.md`](forms-recipes.md). A second source for one number is a second thing to drift.

It also claims nothing about the *guide*. [`../draft-guide/08-async-resources.md`](../draft-guide/08-async-resources.md) and [`../draft-guide/07-routing-and-navigation.md`](../draft-guide/07-routing-and-navigation.md) teach these three shapes already; the recipes here are written on the same doors those chapters name, and the guide is under active authoring elsewhere.
