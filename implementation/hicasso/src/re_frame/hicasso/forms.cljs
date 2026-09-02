(ns re-frame.hicasso.forms
  "The optional forms module: one view, `buffered-field`, that keeps an
  app-db DRAFT in front of a committed value and decides at commit time
  whether the commit still belongs to the edit the user made.

      (ns my.app
        (:require [re-frame.hicasso :as h]
                  [re-frame.hicasso.forms :as forms]))

      [forms/buffered-field
       {:control     [:todo id :title]
        :value       (h/sub [:todo/title id])
        ::h/revision (h/sub [:todo/title-revision id])
        :on-commit   [:todo/title-committed id]
        :placeholder \"What needs doing?\"}]

  Nothing new underneath: the draft is an `h/reg-state` concern
  (`drafts`), the reset is `::h/revision`, the protocol is three ordinary
  events (`::edit`, `::commit`, `::cancel` — named for tests by
  `re-frame.hicasso.test.forms`) and the field is one `h/defview`
  boundary. So it costs no hook beyond the shell's two and the
  controlled `<input>`'s own; it mints no refusal id — a bad `:control` is
  `reg-state`'s `:rf.error/hicasso-state-bad-argument` at the field's first
  render, and `::h/revision` on a non-text field is
  `:rf.error/hicasso-revision-not-controlled`; and an application that
  never requires this namespace carries none of it
  (`implementation/hicasso/scripts/check_optional_module_reachability.py`,
  `implementation/hicasso/scripts/check_bundle_isolation.cjs`).

  The chapter `docs/core/hicasso/05-forms.md` governs the surface and the
  buffered/draft/revision law is D016
  (`docs/design/freehand/decisions/D016-buffered-and-revision-controls.md`).
  The scope ruling is naming-ledger row 16
  (`docs/design/hicasso/product/naming-ledger.md`); the recipes the module
  is written on, and the validation and submit orchestration it
  deliberately leaves to them, are
  `docs/design/hicasso/product/forms-recipes.md`."
  (:require [re-frame.events :as events]
            ;; Side-effecting: registers `:dispatch`, which the commit and
            ;; cancel handlers emit. Named here so the module is correct
            ;; standing alone rather than through `re-frame.core`.
            [re-frame.fx]
            [re-frame.hicasso :as h]
            ;; For `ui-root` alone — see `draft-path`.
            [re-frame.hicasso.impl.state :as impl-state]))

;; ---------------------------------------------------------------------------
;; The draft's home
;; ---------------------------------------------------------------------------

(def drafts
  "The `h/reg-state` concern every buffered draft lives under, and the
  address an application clears to end one:

      (dispatch [::h/clear forms/drafts [:todo 7 :title]])

  The value under one control is `{:revision r :draft text}`; absence
  means no editing session, and it is the ONLY spelling of none, which is
  what makes every repeated commit idempotent. A draft survives re-render,
  remount, virtualization and navigation on purpose, so every durable
  draft needs an owner that ends it — route entry, cancel or the save
  reply.

  Spelled in full rather than as `::drafts` because
  `implementation/hicasso/scripts/check_bundle_isolation.cjs` scans a
  release bundle for this exact string; a namespace rename would move a
  `::drafts` keyword while the scan went on matching nothing
  (`forms-cljs-test/the-concern-is-what-the-bundle-gate-pins`)."
  (h/reg-state :re-frame.hicasso.forms/drafts {:default nil}))

(defn- draft-path
  "`reg-state`'s `[:ui <concern> <ikey>]` layout for `control`. The
  handlers write it directly rather than dispatching the minted setter
  because ending a session must be ONE turn: the record has to be gone in
  the same `:db` that hands the caller its commit, or a render in between
  shows a field whose session has ended and whose draft has not. The `:ui`
  tier is app-space by `reg-state`'s own contract."
  [control]
  [impl-state/ui-root drafts control])

(defn- record-of [db control] (get-in db (draft-path control)))

(defn- live?
  "Is `record` this field's own live session — present, and written under
  `revision`? Revision equality and nothing else: a caller rejects an edit
  by reasserting the value it already had, and two equal values cannot
  carry that decision (D016's eligibility rule)."
  [record revision]
  (and (some? record) (= revision (:revision record))))

(defn- end-session
  "Remove the record at `control`, pruning an emptied concern map —
  removal rather than a written sentinel, so absence stays the one
  spelling of no session."
  [db control]
  (let [[root concern] (draft-path control)]
    (if-some [concerns (get db root)]
      (let [entries (dissoc (get concerns concern) control)]
        (assoc db root (if (seq entries)
                         (assoc concerns concern entries)
                         (dissoc concerns concern))))
      db)))

;; ---------------------------------------------------------------------------
;; The protocol — three events, and every one of them idempotent. The ids
;; are this namespace's own keywords, written into the field's intents
;; below; `re-frame.hicasso.test.forms` names them for a test that drives
;; the field by hand.
;; ---------------------------------------------------------------------------

(events/reg-event ::edit
  {:doc "`[::edit control revision text]` — the keystroke event, on
         `:on-input`. Write `{:revision revision :draft text}` at `control`, replacing
         whole whatever was there: a record a reset made ineligible is
         replaced by the first edit after it, never updated. The revision
         is the one the render that produced this intent was showing,
         which is the generation every later commit is fenced against."}
  (fn [{:keys [db]} [_ control revision text]]
    {:db (assoc-in db (draft-path control) {:revision revision :draft text})}))

(events/reg-event ::commit
  {:doc "`[::commit control revision on-commit]` — Enter and blur alike,
         since D016 makes them one operation.
         End the session at `control` and hand the caller its candidate:
         when the record is live under `revision`, remove it and dispatch
         `(conj on-commit draft)` in the same turn; otherwise do nothing.
         The draft is read from `app-db`, not off the DOM event, which is
         what makes a second Enter, Enter-then-blur, or a blur after
         Escape a no-op. Two records are refused: an absent one (the
         session already ended) and a mismatched one (an external reset
         superseded it, and the text it discarded must not be committed).

         The fence is the render. A commit produced after a reset render
         carries the new revision and is refused; an intent captured
         BEFORE that render and delivered after it is indistinguishable
         from an ordinary commit and commits. A browser never builds that
         ordering, a synthetic dispatch can, and
         `forms-cljs-test/the-fence-is-the-render-and-that-limit-is-stated`
         pins the limit; the residual is the caller's supersession policy."}
  (fn [{:keys [db]} [_ control revision on-commit]]
    (let [record (record-of db control)]
      (if-not (live? record revision)
        {}
        (cond-> {:db (end-session db control)}
          (seq on-commit)
          (assoc :fx [[:dispatch (conj (vec on-commit) (:draft record))]]))))))

(events/reg-event ::cancel
  {:doc "`[::cancel control revision on-cancel]` — Escape.
         Abandon the session at `control`: when the record is live under
         `revision`, remove it and dispatch `on-cancel` if one was given;
         otherwise do nothing. The same idempotence answers *cancel beats
         the late blur* — the queued blur finds no record — so neither
         handler needs to know the order it ran in."}
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
  "The props `buffered-field` consumes rather than forwards to the
  `<input>`; `:key` is here because it would be actively wrong on a DOM
  node."
  [:control :value :on-commit :on-cancel :key ::h/revision])

(h/defview buffered-field
  "A controlled `<input>` with an app-db draft in front of the committed
  value. Takes a stable `:control` address, the committed `:value`, its
  `::h/revision`, and the `:on-commit` event that receives a candidate:

      [forms/buffered-field
       {:control     [:todo id :title]
        :value       (h/sub [:todo/title id])
        ::h/revision (h/sub [:todo/title-revision id])
        :on-commit   [:todo/title-committed id]
        :placeholder \"What needs doing?\"}]

  Protocol (fixed): focus creates nothing and the first edit starts the
  session; Enter and blur both dispatch `(conj on-commit candidate)`;
  Escape clears the draft, shows `:value` again and dispatches
  `:on-cancel` if given; unmount neither commits nor cancels, so a
  virtualized row keeps its draft. The field shows the draft while its
  revision matches `::h/revision` and `:value` the moment it does not.

  `::h/revision` is the caller's and is what a rejection is made of: the
  `:on-commit` handler accepts by writing the candidate, normalises by
  writing another value, or rejects by leaving the value alone — and in
  the last two cases advances the revision, because a reset that only
  works when the value happens to move is not a reset. Async acceptance
  rides the same fence: a later settle writes the value and advances the
  revision together, and a commit still carrying the old revision is a
  no-op. A field never reset, rejected or rewritten may pass a constant
  such as `0`.

  Addressing: the address identifies the form instance and field; two
  fields sharing one address share one draft, which is usually a bug and
  the one thing about the address this module cannot decide. A `nil` or
  unusable address is refused by name at the first read
  (`reg-state`'s `:rf.error/hicasso-state-bad-argument`).

  Ownership: `:control`, `:value`, `:on-commit`, `:on-cancel`, `:key` and
  `::h/revision` are the field's; everything else reaches the `<input>`
  unchanged, `:type` defaulting to `\"text\"`, and owned wins on
  collision.

  Cost: every keystroke still writes the draft to `app-db`. Buffering
  changes when the domain value moves, not where the draft lives, which
  is what keeps the edit visible to tests, snapshots and Xray; for a dense
  grid where that is too much, use an uncontrolled input or a native
  island. Chapter: `docs/core/hicasso/05-forms.md`."
  [{:keys [control value on-commit on-cancel] :as props}]
  (let [revision (::h/revision props)
        record   (h/sub [drafts control])]
    [:input
     (merge {:type "text"}
            (apply dissoc props module-props)
            {:value       (if (live? record revision) (:draft record) value)
             ::h/revision revision
             :on-input    [::edit control revision ::h/value]
             :on-blur     [::commit control revision on-commit]
             ;; The key MAP rather than a callback reading `.key`: the
             ;; substrate's composition gate answers a key event there, so
             ;; Enter cannot commit a partial IME composition.
             :on-key-down {"Enter"  [::commit control revision on-commit]
                           "Escape" [::cancel control revision on-cancel]}})]))
