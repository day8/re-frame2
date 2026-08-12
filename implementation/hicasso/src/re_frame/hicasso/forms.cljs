(ns re-frame.hicasso.forms
  "FORMS — the optional module, and the one view it exists for (rf2-sh56).

      (ns my.app
        (:require [re-frame.hicasso :as h]
                  [re-frame.hicasso.forms :as forms]))

      [forms/buffered-field
       {:control     [:todo id :title]
        :value       (h/sub [:todo/title id])
        ::h/revision (h/sub [:todo/title-revision id])
        :on-commit   [:todo/title-committed id]
        :placeholder \"What needs doing?\"}]

  A controlled field writes every edit straight to `app-db`, and that is
  the first recommendation and stays it. Some fields cannot be that: a
  row edited in place, a field whose value the server may normalise or
  refuse, anything the user must be able to abandon. Those need a DRAFT
  in front of the committed value, and the whole of what makes a draft
  hard is not holding one — it is deciding, later, whether a commit that
  arrives is still the one the user meant.

  ## The ruling this module ships under

  Operator ruling (Mike, 2026-08-12): **the forms module is V0 scope**,
  and `docs/design/hicasso/draft-guide/05-forms.md` stands as its draft
  spec — the code catches up to the chapter rather than the chapter being
  rewritten onto the doors that existed. For this feature only, that
  overrides the second-caller extraction gate `specification.md` §7
  states and the post-v0 positioning in `decisions.md`; the extraction
  gate stands everywhere else. `rf2-hic-051` had refused the extraction
  hours earlier and its arithmetic was sound at the time — one caller,
  and `h/reg-state` already the addressed-draft door. The ruling is on
  different ground: a guide teaching a namespace the code lacks is the
  defect.

  The buffered/draft/baseline/revision LAW is D016
  (`docs/design/freehand/decisions/D016-buffered-and-revision-controls.md`),
  and it governs semantics wherever the chapter is silent. The three
  landed recipes at
  `implementation/hicasso/test/re_frame/hicasso/examples/forms/` are the
  substrate this view was extracted FROM; they stay, because they teach
  the underlying doors and this module is written on exactly those doors.

  ## Nothing new underneath — the module is an ARRANGEMENT

  There is no new runtime here. The draft lives at an `h/reg-state`
  concern, the reset is `::h/revision` (the controlled law's own, which
  the substrate already delivers), the commit protocol is three ordinary
  re-frame events, and the field is one `h/defview` boundary. Everything
  this module knows is written in this file and reads as application
  code, because that is what it was until this bead.

  Three consequences worth stating, because each is a property somebody
  would otherwise have to go and check:

  - **No hook.** `buffered-field` is a boundary, so it costs the shell's
    two and nothing else; the controlled `<input>` spends its own shadow
    `useState` exactly as it does under a hand-written field. Counted at
    React's own dispatcher in `re-frame.hicasso.forms-cljs-test` §hooks.
  - **No refusal id.** The chapter says so in terms — *the module's
    common failures are behavioural rather than separately named runtime
    errors* — and the two refusals this surface can raise are already
    shipped and catalogued: a bad `:control` is
    `:rf.error/hicasso-state-bad-key` (from `reg-state`, at the READ, on
    the field's first render), and `::h/revision` on something that is
    not a controlled text field is
    `:rf.error/hicasso-revision-not-controlled`.
  - **Absent when unused.** `re-frame.hicasso` names no optional module,
    so an application that never requires this namespace carries none of
    it. `hicasso/scripts/check_optional_module_reachability.py` decides
    that over the source and `hicasso/scripts/check_bundle_isolation.cjs`
    reads the release bundle for this module's two sentinel strings.

  ## The draft's address, and why the `:control` is a KEY rather than a path

  The chapter says the module *stores the draft under its own app-db key
  at the `:control` address*, and its own example addresses a field as
  `[:todo id :title]` — which is also where that todo's committed title
  lives. So the control cannot be a path the draft is written AT; it is
  an opaque ADDRESS, and the draft goes under this module's own key:

      [:ui :re-frame.hicasso.forms/drafts [:todo 7 :title]]
      ;; => {:revision 4 :draft \"Buy oat milk\"}

  That is [[drafts]] — an ordinary `h/reg-state` concern, which buys
  three things and mints nothing: the parametric subscription the body
  reads, the instance-key refusal (a `nil` `:control` is every instance
  sharing one draft, and is refused by name at the first render rather
  than discovered on screen), and the shared `[::h/clear …]` event, which
  is how an application's route entry or save reply ends a durable draft.

  ## The protocol, in one paragraph

  Focus creates nothing; the first edit writes `{:revision :draft}` under
  the caller's CURRENT revision. The field renders that draft while its
  revision still matches, and renders `:value` the moment it does not —
  no render-time dispatch, no comparison state, and no way for an
  external reset to leave a stale draft on screen. Enter and blur are one
  operation: consult the record, and only a matching one may append the
  candidate to `:on-commit` and dispatch it, clearing the record in the
  same turn. Escape clears the matching record and, if the caller asked
  for one, emits `:on-cancel`. Everything else — a second Enter, a blur
  after Escape, a blur carrying a revision the reset has moved past — is
  a no-op, because the model has already answered it. Unmount neither
  commits nor cancels: nothing here holds a lifecycle.

  ## The honest limit — THE FENCE IS THE RENDER

  A commit intent carries the revision of the render that produced it,
  and the record carries the revision of the edit that wrote it. That is
  every fact the module has: the caller's CURRENT revision is a prop, so
  it reaches this namespace only through a render, and D016 forbids the
  render-time dispatch that could carry it any other way.

  What follows is worth stating rather than leaving to be found. An
  external reset re-renders the field, so every commit produced after it
  carries the new revision, meets a record written under the old one, and
  is refused — which is the chapter's async fence and is the case a page
  produces. An intent captured BEFORE that render and delivered after it
  is indistinguishable from an ordinary commit at that generation, and it
  commits. React commits the reset render before it delivers a later user
  event, so a browser does not build that ordering; a synthetic dispatch
  does, and
  [[re-frame.hicasso.forms-cljs-test/the-fence-is-the-render-and-that-limit-is-stated]]
  pins it so a future change to the rule is a deliberate one. The
  residual case is the caller's own supersession policy to hold, which is
  what the chapter tells it to do anyway.

  ## What this module deliberately does not have

  It is one view. There is no form-level object, no validation DSL, no
  submit orchestrator and no field registry — validation gating and
  submit status are recipes on doors that already exist, taught in the
  chapter and written out in the forms recipes, and both were built
  without a line of module code. `buffered-field` renders an `<input>`
  and nothing else; a multi-line buffered draft is not in the chapter's
  surface and is not invented here."
  (:require [re-frame.events :as events]
            ;; Side-effecting: registers `:dispatch`, which is how a commit
            ;; hands the caller's own event to the runtime. Every real
            ;; application already has it through `re-frame.core`; naming
            ;; it here is what makes this module correct standing alone.
            [re-frame.fx]
            [re-frame.hicasso :as h]
            ;; For the `:ui` tier root alone — see [[draft-path]]. This is
            ;; the package's own private half, and the shared half at
            ;; that: `h/reg-state` is a door onto it, so naming it adds no
            ;; reachability an application does not already have.
            [re-frame.hicasso.impl.state :as impl-state]))

;; ---------------------------------------------------------------------------
;; The draft's home
;; ---------------------------------------------------------------------------

(def drafts
  "**The `h/reg-state` concern every buffered draft lives under**, and the
  address an application reaches for when it has to end one.

      (dispatch [::h/clear forms/drafts [:todo 7 :title]])

  A draft deliberately survives re-render, remount, virtualization and
  navigation — that is the point of putting it in `app-db` rather than in
  a closure — so every durable draft needs a causal owner and an end
  event. Route entry, an explicit cancel and the successful save reply
  are the usual three, and the line above is how each of them says so.

  The concern is spelled in FULL rather than as `::drafts`, and that is
  not decoration: `hicasso/scripts/check_bundle_isolation.cjs` scans a
  release bundle for this exact string to prove the module is absent from
  an application that never required it, and pins the spelling against
  this line. `::drafts` in this namespace is the same keyword —
  [[re-frame.hicasso.forms-cljs-test/the-concern-is-what-the-bundle-gate-pins]]
  asserts it — but a namespace rename would move the keyword while
  leaving a `::drafts` premise matching, and a gate scanning for a string
  nobody emits any more is a green that means nothing.

  The value under one control is `{:revision r :draft text}` and its
  absence means *no editing session*. One representation of unset, which
  is what makes every repeated commit idempotent for free."
  (h/reg-state :re-frame.hicasso.forms/drafts {:default nil}))

(defn- draft-path
  "`h/reg-state`'s documented `[:ui <concern> <ikey>]` layout, as a
  function of the control address.

  The handlers below write it directly instead of dispatching the setter
  `reg-state` mints, and that is this module's one sharp edge — the same
  one `rf2-hic-051`'s recipes met and wrote down. Ending a session must
  be ONE turn: the record has to be gone in the same `:db` that hands the
  caller its commit, or the turn in between renders a field whose session
  has ended and whose draft has not. `reg-state` states that the `:ui`
  tier is app-space and that an ordinary handler may read and write it;
  this is the case that needs the second half."
  [control]
  [impl-state/ui-root drafts control])

(defn- record-of [db control] (get-in db (draft-path control)))

(defn- live?
  "Is `record` this field's OWN live session? D016's eligibility rule:
  *a matching record means editing and renders `:draft`; absence or a
  reset-key mismatch renders `:value`.*

  Equality of the revision and nothing else. The `:value` is not
  consulted, because the whole reason a revision exists is that a caller
  may reject an edit by reasserting the value it already had — and two
  equal values cannot carry that decision."
  [record revision]
  (and (some? record) (= revision (:revision record))))

(defn- end-session
  "Remove the record at `control`, pruning an emptied concern map.

  Removal rather than a written sentinel, for `reg-state`'s own reason:
  absence is what *no session* means everywhere else in this file, and
  two representations of unset is one too many."
  [db control]
  (let [[root concern] (draft-path control)]
    (if-some [concerns (get db root)]
      (let [entries (dissoc (get concerns concern) control)]
        (assoc db root (if (seq entries)
                         (assoc concerns concern entries)
                         (dissoc concerns concern))))
      db)))

;; ---------------------------------------------------------------------------
;; The protocol — three events, and every one of them idempotent
;; ---------------------------------------------------------------------------

(def edit-id
  "The keystroke event. Public as an ID and not as a door: it is written
  into the field's `:on-input` intent, so it is already visible in the
  rendered tree, in Xray and in a captured intent — and a test that
  asserts on the tree needs to be able to spell it."
  ::edit)

(def commit-id
  "Enter and blur alike — D016 makes them aliases for one semantic
  operation, so there is one event and not two."
  ::commit)

(def cancel-id
  "Escape."
  ::cancel)

(events/reg-event edit-id
  {:doc "Write the draft for one control, under the revision the field was
         showing when the key was pressed.

         ATOMIC REPLACE rather than an update: a record left behind by a
         session an external reset made ineligible is replaced whole by
         the first edit after it, which is D016's own words for this
         transition. Nothing reads the old record to get here.

         The revision arrives from the render that produced this intent,
         which is what makes it the generation the user was editing under
         — and therefore the one every later commit is fenced against."}
  (fn [{:keys [db]} [_ control revision text]]
    {:db (assoc-in db (draft-path control) {:revision revision :draft text})}))

(events/reg-event commit-id
  {:doc "Finish the session at `control` — Enter, or blur, or both.

         It takes no text argument. The draft is already in `app-db`,
         every keystroke put it there, and reading it HERE rather than off
         the DOM event is the whole of why this handler is safe to fire
         twice: Enter then blur, double Enter, or a blur arriving after
         Escape all find no record the second time and do nothing.

         Two records are refused and they are different refusals. An
         ABSENT one is a session that already ended. A MISMATCHED one is
         a session an external reset has superseded — the field is
         already showing the caller's value, and committing the text it
         discarded is exactly the stale-write this fence exists to
         prevent.

         The candidate is APPENDED to `:on-commit` and dispatched, so a
         caller writes `[:todo/title-committed id]` and receives
         `[:todo/title-committed id candidate]`. The removal and the
         dispatch are one turn, so no render sits between them."}
  (fn [{:keys [db]} [_ control revision on-commit]]
    (let [record (record-of db control)]
      (if-not (live? record revision)
        {}
        (cond-> {:db (end-session db control)}
          (seq on-commit)
          (assoc :fx [[:dispatch (conj (vec on-commit) (:draft record))]]))))))

(events/reg-event cancel-id
  {:doc "Abandon the session at `control` — Escape.

         Idempotent for the same reason the commit is, and that is the
         whole of *cancel must beat the late blur*: the cancel removes
         the record, and the blur that was already queued behind it finds
         nothing to commit. Answered by the model rather than by ordering,
         so neither handler has to know what order it ran in.

         `:on-cancel` is optional and fires only when a session really
         ended. Cancelling nothing has no domain meaning, so it reports
         nothing."}
  (fn [{:keys [db]} [_ control revision on-cancel]]
    (let [record (record-of db control)]
      (if-not (live? record revision)
        {}
        (cond-> {:db (end-session db control)}
          (seq on-cancel)
          (assoc :fx [[:dispatch (vec on-cancel)]]))))))

;; ---------------------------------------------------------------------------
;; The view
;; ---------------------------------------------------------------------------

(def ^:private module-props
  "The props `buffered-field` consumes rather than forwards.

  Everything else on the map reaches the `<input>` — `:placeholder`,
  `:class`, `:name`, `:type`, `:aria-*`, a test id — because a field
  nobody can label or style is not a field. These five are the module's
  own configuration and would be meaningless (or, at `:key`, actively
  wrong) on a DOM node."
  [:control :value :on-commit :on-cancel :key ::h/revision])

(h/defview buffered-field
  "**A controlled input with an app-db draft in front of the committed
  value.** Supply a stable control address, the committed value, its
  revision, and the event that receives a candidate commit:

      [forms/buffered-field
       {:control     [:todo id :title]
        :value       (h/sub [:todo/title id])
        ::h/revision (h/sub [:todo/title-revision id])
        :on-commit   [:todo/title-committed id]
        :placeholder \"What needs doing?\"}]

  ## The interaction protocol is FIXED

  - Focus alone does not create a draft. The first edit starts the session.
  - Enter and blur both append the draft candidate to `:on-commit` and
    dispatch it.
  - Escape clears the draft and shows `:value` again. Add `:on-cancel`
    when cancellation has domain meaning.
  - Unmount neither commits nor cancels. A virtualized row can leave and
    return without losing its draft.

  ## `::h/revision` is the caller's, and it is what a rejection is made of

  The `:on-commit` handler decides the result: accept by writing the
  candidate, normalize by writing another value, or reject by leaving the
  committed value unchanged. When it rejects or rewrites, it advances the
  revision as well — because retaining `\"Buy milk\"` after a blank
  submission changes nothing the field reads, and a reset that only works
  when the value happens to move is not a reset. Advancing the revision
  explicitly ends the old edit and re-baselines the field.

  The same fence carries async acceptance. A later settle event writes the
  accepted value and advances the revision together; any commit still
  associated with the old revision is a no-op rather than a restoration of
  stale text.

  A field that will never be externally reset, rejected or rewritten may
  pass a constant such as `0`. That choice means an active draft is never
  replaced merely because `:value` changed — which is the correct default
  for a field whose value only ever moves because this field moved it.

  ## Addressing

  Use an address that identifies the form instance and field. Two fields
  with the same address intentionally share a draft, which is usually a
  bug and is the one thing about the address this module cannot decide
  for you. A `nil` or otherwise unusable address is a different case and
  IS decided: `reg-state` refuses it by name at this body's first read,
  before anything is written.

  ## What the field owns, and what passes through

  `:value`, the three handler slots, `:key` and `::h/revision` are the
  field's. Everything else on the props map reaches the `<input>`
  unchanged, and `:type` defaults to `\"text\"` where the caller does not
  say otherwise. Owned wins on collision — the guide's own owned-last
  merge, with nothing minted for it.

  ## The cost, stated

  A buffered field still writes its draft to `app-db` on every keystroke.
  Buffering changes when the committed domain value moves; it does not
  make the draft DOM-local, and that is deliberate — it is what keeps the
  edit visible to tests, to snapshots and to Xray. For a dense grid where
  that cost is too high, use an explicitly uncontrolled input or a
  measured native island. This is not a performance escape from
  controlled fields."
  [{:keys [control value on-commit on-cancel] :as props}]
  (let [revision (::h/revision props)
        record   (h/sub [drafts control])]
    [:input
     (merge {:type "text"}
            (apply dissoc props module-props)
            {:value       (if (live? record revision) (:draft record) value)
             ::h/revision revision
             :on-input    [edit-id control revision ::h/value]
             :on-blur     [commit-id control revision on-commit]
             ;; The key MAP rather than a callback reading `.key`: the
             ;; substrate's own composition gate answers a key event
             ;; there, so Enter cannot commit a partial IME composition
             ;; and this module spends nothing on it.
             :on-key-down {"Enter"  [commit-id control revision on-commit]
                           "Escape" [cancel-id control revision on-cancel]}})]))
