# Forms

One controlled field is not enough. You need multi-field drafts, validation that
does not scream early, submit that cannot double-fire, and **async loads that do
not wipe keystrokes**.

One field is ordinary Freehand wiring. This page covers a **form** — one re-frame
slice, a blessed shape, and seed-merge.

> **Draft + baseline + touched + errors + submit-attempted? Seed only untouched keys.**

## The blessed form-slice shape

Prefer one coherent map per form (or per form instance), not a scatter of unrelated
keys:

```clojure
{:draft             {…}      ; what the inputs show / edit
 :baseline          {…}      ; last known good server/domain values
 :touched           #{…}     ; fields the user has edited (or set of keys)
 :errors            {…}      ; field → message (or structured errors)
 :submit-attempted? false}
```

Names can vary; the **jobs** should not. The fitness harness “blessed form-slice”
is this five-slot shape (P3):

| Slot | Job |
|---|---|
| **draft** | current field values under edit |
| **baseline** | values to reset to / merge from server |
| **touched** | which fields have been interacted with |
| **errors** | validation messages (often derived or event-written) |
| **submit-attempted?** | user tried to submit — unlocks “show all errors” |

**Busy / pending** is not a required form-slice key. Derive it from your mutation
instance, resource, or a sibling flag, and fold it into submit-enabled? / disabled
UI (below).

Ordinary app-db under your path (`:editor`, `[:forms invoice-id]`, …). Freehand
does not ship a form macro — only this **shape** as shared convention.

## Field wiring (still A / B / C)

Each field is still a Freehand controlled input (live domain or app-owned draft),
and it reads **its own key** — never the whole draft map:

```clojure
(v/defview email-field [_]
  (let [text  (v/sub [:editor/field :email])
        error (v/sub [:editor/field-error :email])]
    [:label
     "Email"
     [:input {:value    (or text "")
              :on-input [:editor/edit-field :email ::v/value]
              :on-blur  [:editor/blur-field :email]}]
     (when error [:p.error error])]))
```

**Why parametric, and not `(:email (v/sub [:editor/draft]))`.** `:editor/field` is
the narrow read:
`(rf/reg-sub :editor/field (fn [db [_ k]] (get-in db [:editor :draft k])))`.
The two spellings look equivalent and are not. Every keystroke
writes a new `:draft` map, so a field that subscribed to the whole map is
invalidated by a character typed in any other field; and because a controlled
input's [synchronous door](events-and-handlers.md#controlled-inputs) drains
re-frame and flushes the dirty cells **on this frame** before returning into
React, those invalidated fields all re-render inside the keystroke rather than
after it. Whole-draft reads therefore make keystroke latency scale with the size
of the form. This is the narrow-graph rule from [State](state.md#shared-state-sub),
and on a form it is a cost model rather than a matter of taste.

Typical event duties:

- **edit-field** — update `:draft`, add key to `:touched`  
- **blur-field** — optional per-field validate into `:errors`  
- **submit** — set `:submit-attempted? true`, validate all, dispatch mutation if ok  

## Show errors only when it helps

A common corpus pattern: do **not** scream about empty required fields before the
user has touched them or tried to submit.

```clojure
(rf/reg-sub :editor/field-error
  (fn [db [_ field]]
    (let [{:keys [errors touched submit-attempted?]} (:editor db)]
      (when (or submit-attempted? (contains? touched field))
        (get errors field)))))
```

Derived **submit enabled?** gates usually combine:

- required draft fields present  
- no blocking errors (under the same touched/attempted policy)  
- not busy on a mutation  

Keep that logic in `reg-sub`, not in the view.

## Seed merge: never clobber typed fields (R-C1)

This is the live footgun in many apps (including this repo’s RealWorld editor
history): a late async load replaces the whole form slice and wipes keystrokes.

**Blessed spelling — seed only untouched fields:**

```clojure
(rf/reg-event :editor/article-loaded
  (fn [{:keys [db]} [_ slug article]]
    (let [editor (get db :editor)
          {:keys [draft touched errors submit-attempted?]} editor
          touched (or touched #{})
          seeded (reduce-kv
                  (fn [d k v]
                    (if (contains? touched k)
                      d                 ; user already typed here — keep it
                      (assoc d k v)))   ; otherwise take server value
                  (or draft {})
                  article)]
      ;; Merge into the slice — do not rebuild only the keys you remembered.
      {:db (assoc db :editor
                 (assoc editor
                        :slug              slug
                        :draft             seeded
                        :baseline          article
                        :touched           touched
                        :errors            (or errors {})
                        :submit-attempted? (boolean submit-attempted?)))})))
```

Rules of thumb:

- On first open, `touched` is empty → full seed from server.  
- After the user types, those keys stay in `touched` → late replies cannot
  overwrite them.  
- Untouched fields still pick up late server data (title arrived, user never
  focused it).  
- **Keep** `:errors` and `:submit-attempted?` (and any other slice keys). A seed
  that rebuilds only four keys silently drops the rest.  
- Reset / “reload form” is an explicit event that clears `touched` on purpose.  

If you use a single whole-map `assoc` of the server payload into `:draft`, you
will reintroduce the clobber bug.

## Recap


- One form-slice map (or instance-keyed path) for the form  
- Fields on **narrow** subs (not the whole draft map)  
- Errors gated on touched or submit-attempted  
- Late loads use **seed-merge** into the existing slice  
- Submit may be attempted while invalid (so errors can appear); disable only while busy  

## Troubleshooting

| Symptom | Fix |
|---|---|
| Typing wiped by slow GET | seed only keys not in `touched` |
| Errors / attempt flag vanish after seed | merge into the existing slice — do not rebuild a partial map |
| Errors on empty form before touch | gate on `touched` / `submit-attempted?` |
| Cannot reveal all errors on first Save | allow attempt while invalid; disable only while busy |
| Typing one field re-renders every field | parametric field subs — not the whole draft map |
| Double submit | busy/pending from mutation path |
| Two editors share one `:editor` | instance key in the path |
| “Need a form macro” | convention above — Freehand does not ship one |
## Submit, busy, and disabled

The view stays passive: draft, errors, and busy come from subs; submit is one
event vector (or form options map).

```clojure
;; Busy is commonly a mutation/resource fact, not a form-slice key
(rf/reg-sub :editor/busy?
  (fn [db _]
    (boolean (get-in db [:mutations :editor-save :pending?]))))

;; Attempt is allowed while invalid — that is how :submit-attempted? becomes true
;; and errors appear. Disable only for pending (or another non-remediable block).
(rf/reg-sub :editor/can-attempt-submit?
  (fn [db _]
    (not (get-in db [:mutations :editor-save :pending?]))))

(rf/reg-event :editor/submit
  (fn [{:keys [db]} _]
    (let [editor (assoc (:editor db) :submit-attempted? true)
          errors (validate-editor editor)]
      (if (seq errors)
        {:db (assoc db :editor (assoc editor :errors errors))}
        {:db (assoc db :editor (assoc editor :errors {}))
         :fx [[:dispatch [:editor/save (:draft editor)]]]}))))
```

```clojure
(v/defview editor-actions [_]
  (let [busy?      (v/sub [:editor/busy?])
        can-attempt (v/sub [:editor/can-attempt-submit?])]
    [:button {:disabled (not can-attempt)
              :on-click [:editor/submit]}
     (if busy? "Saving…" "Save")]))
```

Rules of thumb:

- **Validate in the submit event** (and show errors via `:submit-attempted?` /
  touched). Do not disable the button merely because the form is invalid — that
  makes “show all errors on attempt” unreachable.  
- **Busy / pending** owns double-submit; clear it on success *and* failure in the
  mutation path.  
- Form-level **options map** for native submit is fine:
  `{:on-submit {:event [:editor/submit] :prevent-default true}}`.  
- Server errors merge into `:errors` without wiping `:draft` or `:touched`.
## Dual-field live transform (no local state)

Some screens need two fields that reformat each other (temperature C/F, currency
pair). Keep **raw typing and which field is active** in re-frame — not in a
component atom — so mid-keystroke text does not reformat under the caret:

```clojure
;; Conceptual slice
{:c-text "22" :f-text "71.6" :active :c}

;; Subs: active field echoes raw text; the other shows derived display
(rf/reg-sub :temp/c-display
  (fn [db _]
    (let [{:keys [c-text f-text active]} (:temp db)]
      (if (= active :c) c-text (f->c-string f-text)))))

;; Events: typing sets :active + raw text; optional derive of the sibling raw
(rf/reg-event :temp/c-typed
  (fn [{:keys [db]} [_ text]]
    {:db (update db :temp
                 #(assoc % :active :c :c-text text
                           :f-text (c->f-string text)))}))
```

```clojure
[:input {:value (v/sub [:temp/c-display])
         :on-input [:temp/c-typed ::v/value]}]
[:input {:value (v/sub [:temp/f-display])
         :on-input [:temp/f-typed ::v/value]}]
```

That is ordinary Freehand pattern A with a careful sub graph — not a controller
and not host-local buffers.

## Async races beyond seed-merge

R-C1 protects **draft** from late seeds. Related races use the same rule: **decide
in the re-frame handler against committed state**, and carry identity/generation
in the intent when needed.

| Race | Blessed approach |
|---|---|
| Late article load vs typing | seed only untouched keys (above) |
| Typeahead: slow reply after newer query | store `request-id` / generation; ignore stale replies |
| Debounced search | cancel-and-replace in the effect layer; one semantic “query changed” event |
| Stale blur after Escape | commit handler consults live session; ignore retired generation |
| Unmount mid-flight | owner/route cleanup; do not couple domain success to view mount |

Typeahead / debounce stay ordinary re-frame (generation supersession); package a
controller only when reuse demands it.

## Multiple instances

Two open editors (or a list of line items) need **instance keys** in the path:

```clojure
;; e.g. [:forms invoice-id] or [:editor slug]
(get-in db [:forms invoice-id :draft :amount])
```

Do not store two forms under one global `:editor` key unless only one can exist.
Reusable field protocols that are not the whole form use explicit `:control`
addresses.

## Testing forms

Prefer headless Freehand tests:

1. Make a frame with init / seed events.  
2. `dispatch-sync` the **same** edit intents the tree would show
   (`[:editor/edit-field :email "a@b.c"]`).  
3. Render with `t/render` and assert values, error visibility, disabled submit.  
4. For R-C1: edit a field, then dispatch a late load; assert the typed field
   survived.  

## What Freehand does not ship

| Not in Freehand core | Your job / library |
|---|---|
| `form-slice` macro | the map shape above as convention |
| Automatic validation engine | your validate fn + subs |
| Built-in form component set | build with `v/defview` + data events |
| Mount-time fetch | routes / resources own loading |
