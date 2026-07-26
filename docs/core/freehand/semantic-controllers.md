# Semantic controllers and reusable fields

Most Freehand apps **never** need this page. It sits under **When you need more**
in the nav for a reason.

If you only need a live field, or an app-specific draft that commits on blur and
Enter, ordinary Freehand plus re-frame is enough. Open this page only when
packaging a **hard, reusable multi-step protocol** — commit-on-blur, cancel races,
same-value reject — so many screens share one correct implementation.

!!! important "Skip this page unless you need it"

    Live field or app-specific draft + blur/Enter → **stop**.  
    Open here only for a **reusable** multi-event protocol.

!!! note "Not a Freehand built-in"

    Freehand does **not** ship `v/buffered-field` or a widget catalogue in v1.
    It supplies the **laws**: re-frame-only state, explicit `:control` addresses,
    one event per action, consult committed state. A library implements controls
    as normal `v/defview`s plus events and subs. Sketches below are illustrative.

## You can just use `on-blur`

This is valid Freehand and does **not** use a semantic controller:

```clojure
[:input {:value   (v/sub [:lines/draft id])
         :on-input [:lines/draft-changed id ::v/value]
         :on-blur  [:lines/label-committed id]
         :on-key-down (v/event [e]
                        (case (.-key e)
                          "Enter"  [:lines/label-committed id]
                          "Escape" [:lines/draft-cancelled id]
                          nil))}]
```

Use the same event on blur and Enter (commit). Use a **different** event on
`on-input` (draft) — keystroke is draft; commit is accept. A handler slot holds
**one** handler form, so the Enter/Escape branch is `v/event` at the site or
`[:lines/draft-key id ::v/key]` into a handler that branches — see
[Events](events-and-handlers.md#keyboard-branching).

A semantic controller is only worth it when that protocol must be **correct and
shared** across many call sites — for example stale blur after cancel, a required
reset generation, or library packaging — without every team reimplementing it.

## Start from the simple case

Most Freehand controls should **not** use a semantic controller.

A disclosure is enough as props-only / controlled:

```clojure
(v/defview disclosure [{:keys [open? on-toggle label children]}]
  [:section.disclosure
   [:button {:aria-expanded open? :on-click on-toggle} label]
   (when open? [:div.body children])])

[disclosure
 {:open?     (v/sub [:faq/open? question-id])
  :on-toggle [:faq/toggled question-id]
  :label     "Why?"}]
```

The parent owns `open?` in re-frame. The control is dumb: **value in, intent out.**

Use that pattern whenever the interaction is a single domain fact (or a single
domain event) and every caller can wire it without reimplementing a multi-step
protocol.

## When you need a controller

Only when the control owns a **multi-event protocol** that every caller should not
rebuild:

| Symptom | Example |
|---|---|
| Draft vs accepted baseline | commit on blur/Enter |
| Cancel must beat late blur | Escape then blur must not commit |
| Same-value rejection | parent reasserts old string; field must reset |
| Shared library UX | many screens, same rules |

Classic case: **buffered / revision text field.** Dropdown and typeahead are the
same class once the protocol is clear.

If every keystroke *is* product state (live filter, autosave), stay controlled and
dispatch domain events — no controller.

## Async search / typeahead (without a second state system)

Dropdown move/select and typeahead are the same *class* of multi-event protocol
as buffered fields — but many apps implement them as **ordinary re-frame** first
and only package a controller when reuse demands it.

### Debounce and supersession (app pattern)

```clojure
;; Each keystroke updates the query string (controlled field A) and kicks search.
;; re-frame2: :dispatch-later is an fx id; the map key for the event is :event
;; (not v1's :dispatch).
(rf/reg-event :search/query-typed
  (fn [{:keys [db]} [_ text]]
    (let [gen (inc (get-in db [:search :generation] 0))]
      {:db (-> db
               (assoc-in [:search :query] text)
               (assoc-in [:search :generation] gen)
               (assoc-in [:search :status] :pending))
       :fx [[:dispatch-later
             {:ms 200
              :event [:search/run gen text]}]]})))

(rf/reg-event :search/run
  (fn [{:keys [db]} [_ gen text]]
    (if (not= gen (get-in db [:search :generation]))
      {}   ; superseded — do nothing
      {:fx [[:search/fetch {:q text :gen gen}]]})))

(rf/reg-event :search/results-arrived
  (fn [{:keys [db]} [_ gen results]]
    (if (not= gen (get-in db [:search :generation]))
      {}   ; stale network reply
      {:db (-> db
               (assoc-in [:search :results] results)
               (assoc-in [:search :status] :ready))})))
```

```clojure
(v/defview search-box [_]
  [:div.search
   [:input {:value (v/sub [:search/query])
            :on-input [:search/query-typed ::v/value]
            :aria-autocomplete :list}]
   [:ul
    (for [id (v/sub [:search/result-ids])]
      [result-row {:key id :id id}])]])
```

| Law | Meaning |
|---|---|
| Generation / request id | later keystrokes and replies supersede earlier ones |
| Decide in the handler | never “trust the callback closed over gen 3” without checking db |
| Effects own HTTP | views only dispatch; cancel-and-replace lives in fx if you have real abort |
| Optional controller | package open/highlight/commit only when many screens share the same UX |

Debounce begins as **library or app policy**. It graduates into reserved re-frame
vocabulary only after independent UI and non-UI uses share identity, cancellation,
frame, SSR, and trace semantics (D017). Do not invent `v/debounce`.

## Where the data lives

Controller state is **ordinary frame-scoped re-frame data** (usually that frame’s
**app-db**). It is not React state, not a view-local cell, and not a Freehand-private side
channel.

**There is no single Freehand-mandated path** like “always under
`[:rf/controllers …]`.” The **library** chooses the root. Freehand fixes the
**identity model**: kind + caller `:control` address.

Identity is:

```text
(controller-kind, semantic-address)
```

- **Kind** — which protocol (buffered field, dropdown, …). Owned by the library.
- **Address** — which *instance*, as immutable caller-supplied EDN, e.g.
  `[:invoice invoice-id :amount]` or `[:line line-id :label]`.

Conceptual store (illustrative library choice only):

```clojure
;; only rows currently mid-edit need an entry
{:fh.buffer/by-control
 {[:line 5 :label]  {:reset-key 0 :draft "Alph"}
  [:line 12 :label] {:reset-key 2 :draft "…"}}}
```

You work through the library’s `reg-sub` / `reg-event`s, not by hand-walking that
map. Xray’s app-db view will show whatever root the library used.

### Edit-session lifecycle (buffered field)

| Moment | Controller record |
|---|---|
| Idle (showing external `:value`) | **Absent** (or ignored) |
| **First real keystroke** (not mere focus) | **Created** under `:control` |
| Further typing | **Updated** (`:draft`) — each keystroke is still a re-frame event |
| **Enter** or **blur** (commit) | **Removed**; domain gets `:on-commit` |
| **Escape** (cancel) | **Removed**; domain unchanged |
| Parent bumps `:reset-key` | Session invalid; show baseline again |
| Unmount without commit | Record may **linger** until owner clear |

So “created when I start editing, gone when I finish” is right — but finish means
**commit or cancel**, not only Return. Leave-without-finish needs an **owner**
that clears the session.

### Two identities (do not collapse them)

| Identity | Job |
|---|---|
| **Occurrence** — view id + parent + `:key` / position | reconciliation, callbacks, presence, “this mount” |
| **Semantic address** — `:control` | “this field’s protocol state” |

Freehand never derives a writable address from the React tree. Tools may *join*
occurrence ↔ controller for debugging. Your call sites always pass `:control`
explicitly.

### Cleanup (two layers)

| What | Cleaned how |
|---|---|
| View subs / callbacks / host | Automatic on unmount / disconnect |
| Controller draft session | Commit, cancel, or **owner clear** — **not** unmount |
| Domain value (saved label) | Your domain events / route leave |

If you forget owner clear, drafts can orphan in app-db. Dev tools should surface
that. Plan form/route teardown the same way you release resources on leave.

## Call site: one buffered field

```clojure
[buffered-field
 {:control   [:invoice invoice-id :amount]
  :value     (v/sub [:invoice/amount invoice-id])
  :reset-key (v/sub [:invoice/amount-revision invoice-id])
  :on-commit [:invoice/amount-committed invoice-id]}]
```

| Prop | Meaning |
|---|---|
| `:control` | semantic address for this instance’s draft session |
| `:value` | external baseline when not editing |
| `:reset-key` | **required** generation; new key drops the draft even if text equals the old baseline |
| `:on-commit` | caller intent prefix; library commit appends the draft and dispatches |

Design rules worth internalising:

- Editing starts on the **first real edit**, not mere focus.
- Commit (Enter/blur) and cancel (Escape) **consult committed state** at fire time
  and are idempotent for stale generations.
- IME composition suppresses Enter commit until composition ends.

## Table: 20 rows × one editable cell

### Controlled (no controller)

When the draft *is* domain truth:

```clojure
(v/defview line-row [{:keys [line-id]}]
  (let [label (v/sub [:lines/label line-id])]
    [:tr
     [:td
      [:input {:value    label
               :on-input [:lines/set-label line-id ::v/value]}]]]))

(v/defview lines-table [_]
  [:table
   [:tbody
    (for [id (v/sub [:lines/visible-ids])]
      [line-row {:key id :line-id id}])]])
```

Twenty domain paths; no `:control`.

### Buffered (controller per row)

```clojure
(v/defview line-row [{:keys [line-id]}]
  (let [label    (v/sub [:lines/label line-id])
        revision (v/sub [:lines/label-revision line-id])]
    [:tr
     [:td
      [buffered-field
       {:control   [:line line-id :label]   ;; unique per row
        :value     label
        :reset-key revision
        :on-commit [:lines/label-committed line-id]}]]]))

(v/defview lines-table [_]
  [:table
   [:tbody
    (for [id (v/sub [:lines/visible-ids])]
      [line-row {:key id :line-id id}])]])
```

| Row | `:key` (list identity) | `:control` (protocol state) |
|---|---|---|
| line 1 | `1` | `[:line 1 :label]` |
| line 5 | `5` | `[:line 5 :label]` |
| line 20 | `20` | `[:line 20 :label]` |

Use **domain ids** in addresses, not row indexes. Using the same `id` in `:key`
and `:control` is normal; the two props answer different questions. Do not reuse
one `:control` for two writable cells unless they should share one draft.

You only need buffer records for rows **currently editing** — not twenty permanent
slots.

## The three verbs a controller uses

A controller is **not a new kind of thing**: it is a `v/defview` plus ordinary
`reg-sub` and `reg-event` registrations, and its state is ordinary frame data an
epoch, a snapshot and a JVM test all see. What the substrate contributes is three
small functions, and they answer two questions a library would otherwise answer
twice and differently: **where** the record lives, and **when** it is still the one
the caller means.

### `v/controller-key` — where the record lives

```clojure
(v/controller-key ::buffered-field props)
;; => [::buffered-field [:invoice 42 :amount]]
```

The pair of your library's controller `kind` and the caller-supplied `:control`
address. **Asking for the key is what makes a controller writable** — a props-only
view never calls it and pays nothing.

The `kind` is half the key, so a dropdown and a field addressed at the same domain
identity read two records rather than each other's. The address is the caller's,
and it is immutable EDN naming the domain thing that owns the state — never a DOM
id, a React key, or anything derived from render position. A derived anchor turns a
sort, a view rename or a parent extraction into a silent state migration, while
`[:invoice 42 :amount]` survives all of them.

An absent `:control` is refused with `:rf.error/view-control-address-missing`
rather than defaulted, because every controller that skipped the address would
otherwise share one record keyed by `nil` — which presents as one field editing
another and has no local explanation. An explicit `nil` is refused *separately*,
with `:rf.error/view-control-address-nil`: an absent prop is a call site that
forgot the address, while a `nil` came from an expression that answered nothing —
an unresolved route parameter, a subscription that has not landed — so the repair
belongs upstream of the render. Presence decides which refusal you get, never
truthiness: `false`, `0` and `""` are ordinary addresses.

Where the record then lives is **your** choice. Freehand fixes the identity model
and no storage path, so there is no reserved app-db root to migrate off later.

### `v/controller-revision` — which generation is live

```clojure
(v/controller-revision ::buffered-field props)   ; the caller's :reset-key
```

Any EDN the caller likes — a counter, a timestamp, the id of the decision that set
the baseline — because the fence only ever asks whether two of them are equal.
**Asking for the revision is what makes a controller buffered.**

What it must **not** be is the value. The case this exists for is a caller
*rejecting* an edit by reasserting what it already had: the accepted value is
`"10"`, the user drafts `"bad"`, the caller refuses and stands by `"10"`. The value
before that decision and the value after it are identical, so value-equality sees
nothing happen and the refused draft survives on screen — the bug every hand-rolled
buffered input eventually acquires.

It is **required**. An absent `:reset-key` is
`:rf.error/view-control-reset-revision-missing`, because optional would be worse
than absent: a control with no generation buffers perfectly right up to the first
rejection, so omission ships a defect development never reproduces. A caller that
genuinely never resets says so with a stable literal — `:reset-key 0` reads as *do
not externally reset an active edit*, which is a statement rather than a silence.
`nil` is refused separately again, and for the same reason.

### `v/controller-current?` — the generation fence

```clojure
(v/controller-current? stamped revision) ; → boolean
```

One predicate, asked at **both** of a buffered controller's boundaries — the read,
where a draft is displayed only while it is current, and the write, where only a
current record may produce the caller's intent:

```clojure
;; the READ — display the draft only while it is current
(rf/reg-sub :acme.buffered/text
  (fn [db [_ k revision baseline]]
    (let [record (get-in db [records-root k])]
      (if (v/controller-current? (:reset-key record) revision)
        (:draft record)
        baseline))))

;; the WRITE — only a current record may produce the caller's intent
(when (v/controller-current? (:reset-key record) revision)
  {:fx [[:dispatch (conj on-commit (:draft record))]]})
```

They are the same question through the same function, because a control whose
display and whose commit disagreed about which generation is live would commit
something the user could not see.

It is **total, and safe in the missing direction**: an absent stamp is not
current, whatever `revision` is — work that cannot prove its currency does not have
it. That is the half a hand-rolled `(= a b)` gets wrong, since two `nil`s compare
equal and an unstamped record would read as current. It is also what makes a draft
written from a superseded render *born stale*: it carries the generation its own
render displayed, so if the caller has moved on it is neither shown nor committed.

A superseded draft is **invisible, not erased**, which is why a reset costs no
render-time dispatch, no render-phase mutation and no remount.

## What `buffered-field` looks like (library sketch)

Yes: it is a normal **`v/defview`**, plus library events/subs. It is not a
substrate special form. It reaches for all three verbs above and nothing else —
the view asks for its key and its generation, and both boundaries ask
`v/controller-current?`.

```clojure
(ns my.ui.buffered-field
  (:require [re-frame.core :as rf]
            [re-frame.freehand :as v :refer [sub]]))

;; ---- library dataflow ------------------------------------------------------
;; Storage and every read key on the FULL controller key — the (kind, address)
;; pair `v/controller-key` answers — never on the bare `:control` prop. Going
;; through the verb is also what buys the four refusals above; keying on the raw
;; prop bypasses every one of them and drops the kind half of the identity.

(rf/reg-sub :fh.buffer/value
  (fn [db [_ k revision baseline]]
    (let [record (get-in db [:fh.buffer/by-key k])]
      (if (v/controller-current? (:reset-key record) revision)
        (:draft record)
        (or baseline "")))))

(rf/reg-event :fh.buffer/edited
  (fn [{:keys [db]} [_ k revision text]]
    {:db (assoc-in db [:fh.buffer/by-key k]
                   {:reset-key revision :draft text})}))

(rf/reg-event :fh.buffer/committed
  (fn [{:keys [db]} [_ k revision on-commit]]
    (let [record (get-in db [:fh.buffer/by-key k])]
      (if (v/controller-current? (:reset-key record) revision)
        {:db (update db :fh.buffer/by-key dissoc k)
         :fx [[:dispatch (conj (vec on-commit) (:draft record))]]}
        {}))))   ; superseded generation / already cancelled → no-op

(rf/reg-event :fh.buffer/cancelled
  (fn [{:keys [db]} [_ k revision]]
    (let [record (get-in db [:fh.buffer/by-key k])]
      (if (v/controller-current? (:reset-key record) revision)
        {:db (update db :fh.buffer/by-key dissoc k)}
        {}))))

;; ---- the control -----------------------------------------------------------

(v/defview buffered-field
  [{:keys [value on-commit placeholder] :as props}]
  (let [k        (v/controller-key ::buffered-field props)
        revision (v/controller-revision ::buffered-field props)
        text     (sub [:fh.buffer/value k revision value])]
    [:input
     {:value       text
      :placeholder placeholder
      :on-input    [:fh.buffer/edited k revision ::v/value]
      :on-blur     [:fh.buffer/committed k revision on-commit]
      :on-key-down (v/event [e]
                     (when-not (.-isComposing e)
                       (case (.-key e)
                         "Enter"  [:fh.buffer/committed k revision on-commit]
                         "Escape" [:fh.buffer/cancelled k revision]
                         nil)))}]))
```

The view never destructures `:control` or `:reset-key` itself. That is the point:
the two verbs are the only door onto them, so a call site that forgot either one
is refused at the door rather than silently sharing a record keyed by `nil` or
buffering perfectly until the first rejection.

### Behaviour timeline

1. **Not editing** — sub falls through to external `:value`.
2. **First keystroke** — `edited` writes `{reset-key, draft}` under the
   controller key.
3. **Further keys** — update draft; controlled input rides Freehand’s
   [sync door](events-and-handlers.md#controlled-inputs).
4. **Enter / blur** — `committed` checks generation, clears record, dispatches
   caller `on-commit` + draft.
5. **Escape** — `cancelled` clears record; a later blur no-ops (generation check).
6. **Parent bumps `:reset-key`** — draft no longer matches → show external value
   again (including same-value rejection).

Namespace prefixes such as `:fh.buffer/*` are **library vocabulary**, not reserved
Freehand grammar. v1 does not reserve `:rf.field/*` at the framework layer.

## Who owns what

| Freehand (substrate) | Library |
|---|---|
| re-frame as the only reactive app state | control `v/defview` (e.g. `buffered-field`) |
| explicit `:control` addresses for writable protocol state | record schema under each address |
| one event (or `nil`) per user action | semantic events: edited / committed / cancelled… |
| consult committed state at fire time (law) | handler bodies that enforce the protocol |
| no mount-driven domain/controller cleanup | catalog metadata for tools (optional) |

Freehand does not ship generic view-level `put` / `merge` / `toggle` as the way to
build these protocols in v1.

## Decision cheat sheet

| Situation | Prefer |
|---|---|
| Single domain fact, simple event | props-only controlled control |
| Keystroke = domain write | controlled input + domain event |
| Commit / cancel / reject / shared field UX | semantic controller + library control |
| High-rate or opaque editor | a [registered behavior](host-boundaries.md), not a pretend local cell |

## Troubleshooting

| Symptom | Fix |
|---|---|
| Two fields share one draft silently | distinct caller-authored `:control` addresses |
| Reject same value after “bad” edit does nothing | bump `:reset-key` (generation), not only `:value` |
| Escape then blur commits anyway | generation fence on commit — superseded stamp is a no-op |
| Enter commits mid-IME | `(when-not (.-isComposing e) …)` on the key handler |
| Records pile up after navigation | owner `clear-under` on route leave — unmount does **not** clear controllers |
| “Need `local` / a ratom for the draft” | no — controller record in app-db, or keep the draft in domain state |

## When not

- Ordinary live field or one-off blur/Enter draft — stay on
  [Events](events-and-handlers.md) and [Forms](forms.md); skip this page.  
- Product truth every other view must see — domain events and subs, not a
  field controller.  
- Imperative host state (DOM node, third-party instance) — behavior, not a
  controller record.
