(ns re-frame.freehand.form
  "PURE form transitions over ordinary application data — the DC-03 slice
  of the Freehand product-completion setpoint.

  A form is the shape every application rebuilds and every application
  gets wrong in the same four places: a late load wipes keystrokes, a
  reset and a rebase are conflated, errors scream before the user has
  been anywhere near the field, and a slow save's reply lands after the
  user has already submitted again. None of that is a rendering problem
  and none of it needs a runtime, so this namespace is **functions over a
  map** and nothing else.

  It registers NO events and NO subscriptions, owns no atom, mounts
  nothing, and reads no frame — it requires nothing from the substrate
  but its equality. Every operation here is `(f form …) -> form`, which
  is what lets the laws be proven by CALLING them — no frame, no app-db,
  no host — and what lets an application put the form wherever it already
  keeps state.

      (rf/reg-event :editor/edited
        (fn [{:keys [db]} [_ path text]]
          {:db (update db :editor form/edit path text)}))

  ## The value

  A form is a plain map with seven documented slots. It is ordinary EDN,
  so an epoch carries it, a snapshot restores it and a tool reads it with
  no special knowledge:

      {:baseline {…}   ; the last values the DOMAIN accepted
       :draft    {…}   ; what the controls show and edit
       :visited  #{…}  ; leaf paths the user has focused and left
       :edited   #{…}  ; leaf paths the user has CHANGED
       :resets   {…}   ; leaf path -> reset revision (a generation)
       :errors   {…}   ; leaf path -> structured message
       :submit   {:attempted? false :attempts 0 :pending nil}}

  The slots are public and are read the ordinary way — `(:errors form)`,
  `(get-in form [:submit :pending])`. There is no reader zoo over a map
  whose shape is documented; the two functions that ARE readers,
  [[field]] and [[reset-key]], are there because they DERIVE something a
  key lookup does not give you.

  **A leaf path is a VECTOR**, always — `[:email]`, `[:contact :email]`,
  `[:lines \"line-7\" :qty]`. Vector paths are what make `:visited`,
  `:edited`, `:resets` and `:errors` flat sets and maps keyed by one
  comparable value instead of four parallel nested trees, and they are
  what lets a row keep its identity: a row addressed by its STABLE DOMAIN
  ID survives an insert, a delete and a re-sort, where a row addressed by
  its index silently becomes a different row.

  ## Why `:visited` and `:edited` are two sets

  They answer two different questions, and a single `:touched` set gets
  one of them wrong.

  - `:edited` is *the user changed this*, and it is what [[seed]]
    protects: a late server reply must not overwrite a leaf the user has
    typed into.
  - `:visited` is *the user has been here and left*, and it is what
    reveals an error: complaining that a required field is empty before
    the user has reached it is the single most common bad form there is.

  A field the user tabbed through and did not change is visited and not
  edited; a field a paste-and-blur filled is both. Collapsing them either
  makes a tab-through wipe-protected, or makes an untouched required
  field scream.

  ## Why the reset revision is PER LEAF

  A caller rejects a draft by REASSERTING what it already had — the
  accepted amount is `\"10\"`, the user types `\"bad\"`, the caller refuses
  and stands by `\"10\"`. The value before that decision and the value
  after it are identical, so nothing derived from the value can observe
  it (Spec 004 §The generation fence). [[reset]] therefore ADVANCES a
  revision, and [[reset-key]] hands it to the control, which fences
  against it through `v/controller-current?`.

  Per LEAF, because a form-wide generation would make rejecting one field
  discard every other field's in-flight edit — the reset of `:amount`
  would silently abandon what the user was typing in `:notes`.

  ## The narrow read

  A form read through its CONTAINER — `(:draft form)`, then `get` the key
  — is invalidated by a character typed in ANY other field, and because a
  controlled input's synchronous door drains re-frame and flushes the
  dirty cells on THIS frame before returning into React, every one of
  those fields re-renders inside the keystroke rather than after it.
  Whole-container reads make keystroke latency scale with the size of the
  form.

  [[field]] is the narrow read. It is ONE `reg-sub` for a form of any
  size, and [[re-frame.freehand.controls/field]] accepts nothing else —
  so the ergonomic path and the correct path are the same path.

  Normative owner:
  [`spec/004-Views.md` §Pure form transitions](../../../../../spec/004-Views.md#pure-form-transitions)."
  (:require [re-frame.freehand.eq :as eq]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The reserved projection key
;; ---------------------------------------------------------------------------

(def path-key
  "The key a [[field]] projection carries its LEAF PATH under —
  `:re-frame.freehand/path`, under Freehand's reserved keyword root.

  It is the projection's identity as well as its data. A control that
  takes a field asks for THIS key, so a caller who hands it the whole
  draft map — the one mistake the narrow-read rule exists to prevent — is
  refused at the site with the recovery, rather than rendering an input
  whose value is silently `nil`."
  :re-frame.freehand/path)

;; ---------------------------------------------------------------------------
;; The leafwise walk
;; ---------------------------------------------------------------------------

(defn- leaf-entries
  "Every `[leaf-path value]` pair in the nested map `m`, prefixed by
  `prefix`.

  A LEAF is any value that is not a NON-EMPTY MAP: scalars, vectors,
  sets, `nil`, and the empty map all stop the walk. A vector value is
  therefore one leaf and not a sub-tree, which is what a multi-select's
  value or a list of tags actually is — and what keeps a leaf path from
  depending on the number of items a field currently holds."
  [prefix m]
  (reduce-kv
    (fn [acc k v]
      (let [path (conj prefix k)]
        (if (and (map? v) (seq v))
          (into acc (leaf-entries path v))
          (conj acc [path v]))))
    []
    m))

(defn- leaves
  "Every `[leaf-path value]` pair in the nested map `values`."
  [values]
  (leaf-entries [] values))

;; ---------------------------------------------------------------------------
;; The empty form
;; ---------------------------------------------------------------------------

(def ^:private empty-submit
  {:attempted? false :attempts 0 :pending nil})

(defn init
  "A fresh form whose baseline AND draft are `baseline` (a nested map;
  omitted or `nil` for an empty form).

      (form/init {:contact {:email \"a@b.c\"} :notes nil})

  Nothing is visited, nothing is edited, no leaf has been reset, there
  are no errors and no submit has been attempted. `init` is how a form
  BEGINS; a later authoritative load is [[seed]] (which protects typed
  leaves) or [[rebase]] (which moves the baseline under a live draft) —
  re-`init`ing on a late reply is the wipe this model exists to stop."
  ([] (init nil))
  ([baseline]
   (let [b (or baseline {})]
     {:baseline b
      :draft    b
      :visited  #{}
      :edited   #{}
      :resets   {}
      :errors   {}
      :submit   empty-submit})))

;; ---------------------------------------------------------------------------
;; What the user did
;; ---------------------------------------------------------------------------

(defn edit
  "The user changed the leaf at `path` to `value`.

      (form/edit f [:contact :email] \"mike@example.com\")

  Writes the draft leaf and marks the leaf EDITED — unconditionally, even
  when `value` equals the baseline. Edited means *the user has been
  typing here*, not *the value currently differs*: a leaf the user typed
  into and corrected back is still theirs, and a late [[seed]] must not
  overwrite it. A leaf stops being edited only when the caller says so,
  through [[reset]] or [[rebase]]."
  [form path value]
  (-> form
      (assoc-in (into [:draft] path) value)
      (update :edited conj path)))

(defn visit
  "The user has been at the leaf at `path` and left it — the blur.

      (form/visit f [:contact :email])

  Marks the leaf VISITED, which is what lets its error be revealed
  without the form shouting at fields nobody has reached yet. It does not
  touch the draft, so a tab-through changes no value and protects nothing
  from [[seed]]."
  [form path]
  (update form :visited conj path))

;; ---------------------------------------------------------------------------
;; What the outside world did
;; ---------------------------------------------------------------------------

(defn seed
  "A late authoritative load: take `values` LEAFWISE, and skip every leaf
  the user has already edited.

      (form/seed f {:contact {:email \"server@example.com\"}
                    :title   \"From the server\"})

  This is the live footgun in most applications: a slow GET returns while
  the user is typing, the handler `assoc`s the whole payload over the
  draft, and the keystrokes are gone. Seeding LEAFWISE is the fix, and it
  has three properties worth stating separately because each is a bug
  somebody has shipped:

  - **Edited leaves survive.** A leaf in `:edited` keeps its draft AND
    keeps its baseline unmoved, so the user's work is neither displayed
    over nor silently re-based under.
  - **Unrelated state survives.** A leaf `values` does not mention is
    untouched — including a leaf nested beside one that IS mentioned, and
    including a whole sub-tree the payload omits. Seeding a partial
    payload is ordinary, not a special case.
  - **Nested and keyed rows work the same way.** `{:lines {\"line-7\"
    {:qty 3}}}` seeds `[:lines \"line-7\" :qty]`; a row keyed by its
    stable domain id is addressed by the same path before and after its
    neighbours move.

  A seeded leaf moves BOTH the baseline and the draft, because an
  unedited leaf is showing the baseline by definition. Seeding clears the
  leaf's error — the value it was about is gone — and marks nothing
  visited or edited: the server typing is not the user typing."
  [form values]
  (let [edited (:edited form #{})]
    (reduce
      (fn [f [path value]]
        (if (contains? edited path)
          f
          (-> f
              (assoc-in (into [:baseline] path) value)
              (assoc-in (into [:draft] path) value)
              (update :errors dissoc path))))
      form
      (leaves values))))

(defn rebase
  "The BASELINE moved and the draft stands: take `values` leafwise as the
  new accepted truth, and keep what the user is editing.

      (form/rebase f saved-article)

  [[reset]] and `rebase` are the two halves of \"the form is no longer
  what it was\", and conflating them is the mistake this pair exists to
  prevent:

  | | draft | edited marks | reset revisions |
  |---|---|---|---|
  | [[reset]] | discarded, back to baseline | cleared | ADVANCED |
  | `rebase`  | KEPT | cleared where the draft now agrees | untouched |

  `rebase` is a save landing, or an authoritative refresh: the domain
  accepted something, so the baseline is new, but the user's unsaved work
  is not a draft to be rejected. A leaf whose draft now EQUALS the new
  baseline stops being edited — there is nothing left to protect there,
  so a subsequent [[seed]] may move it again. Revisions are NOT advanced,
  because a rebase is not a rejection: a control holding a live buffer
  keeps it.

  A pending submit is settled — the reply that carried these values is
  the reply the submit was waiting for."
  [form values]
  (let [entries (leaves values)
        rebased (reduce (fn [f [path value]]
                          (assoc-in f (into [:baseline] path) value))
                        form
                        entries)
        settled (reduce (fn [edited [path _]]
                          (if (eq/rf= (get-in rebased (into [:draft] path))
                                      (get-in rebased (into [:baseline] path)))
                            (disj edited path)
                            edited))
                        (:edited rebased #{})
                        entries)]
    (-> rebased
        (assoc :edited settled)
        (assoc-in [:submit :pending] nil))))

(defn reset
  "Discard the user's work — the whole form, or one leaf — and ADVANCE
  the reset revision of every leaf discarded.

      (form/reset f)                        ; the whole form
      (form/reset f [:contact :email])      ; one leaf

  The draft goes back to the baseline, the leaf stops being edited and
  visited, its error goes, and its revision advances. The revision is the
  point: a caller REJECTS a draft by reasserting the value it already
  had, so a control watching the value would see nothing happen and keep
  the refused text on screen. Advancing the generation is the one signal
  equality cannot be blind to, and [[reset-key]] is how a control reads
  it.

  Scoped to a leaf, `reset` advances THAT leaf's revision and no other,
  so rejecting one field does not abandon what the user is typing in the
  next. The whole-form arity advances every leaf mentioned anywhere in
  the form — baseline, draft, visited, edited or errors — so a leaf the
  user typed into that the baseline never had is discarded too.

  A pending submit is settled: the work it was for is gone."
  ([form]
   (let [paths (into #{}
                     (concat (map first (leaves (:baseline form)))
                             (map first (leaves (:draft form)))
                             (:visited form)
                             (:edited form)
                             (keys (:errors form))))]
     (-> form
         (assoc :draft (:baseline form))
         (assoc :visited #{})
         (assoc :edited #{})
         (assoc :errors {})
         (update :resets #(reduce (fn [rs p] (update rs p (fnil inc 0))) % paths))
         (assoc-in [:submit :pending] nil))))
  ([form path]
   (-> form
       (assoc-in (into [:draft] path) (get-in form (into [:baseline] path)))
       (update :visited disj path)
       (update :edited disj path)
       (update :errors dissoc path)
       (update-in [:resets path] (fnil inc 0))
       (assoc-in [:submit :pending] nil))))

;; ---------------------------------------------------------------------------
;; Validation and submission — the policy stays the application's
;; ---------------------------------------------------------------------------

(defn set-errors
  "Replace the form's errors with `errors`, a map of LEAF PATH to
  structured message.

      (form/set-errors f {[:contact :email] {:code :malformed}})
      (form/set-errors f {})                 ; everything validated

  Replacement rather than merge, because that is what a validation pass
  produces: merging would leave yesterday's error on a leaf today's pass
  found clean, and \"and clear the ones that passed\" is a second thing
  every caller would have to remember. A server rejection that must not
  lose a client-side error merges its own map and passes the result.

  What counts as an error, and what a message says, is the
  APPLICATION's: Freehand ships no validator, no schema renderer and no
  rule language. A message is any EDN — a string, a keyword, a map with a
  code and arguments for translation.

  A pending submit is settled, because errors arriving IS a reply."
  [form errors]
  (-> form
      (assoc :errors (or errors {}))
      (assoc-in [:submit :pending] nil)))

(defn attempt-submit
  "The user tried to submit.

      (form/attempt-submit f)

  Always marks the form ATTEMPTED, which reveals every error at once —
  including on leaves the user never visited, which is exactly what a
  submit is for. An invalid form remains attemptable: attempting again
  after a failed attempt is an ordinary thing users do, and refusing it
  would leave the form with no way to re-surface its errors.

  When there are no errors it also opens a PENDING submit and stamps it
  with the attempt number, at `[:submit :pending]`. That number is the
  staleness fence for the reply: the application carries it out with the
  request and compares it on the way back, so a slow first reply landing
  after a second attempt is INERT rather than authoritative.

      (rf/reg-event :editor/submitted
        (fn [{:keys [db]} _]
          (let [f     (form/attempt-submit (:editor db))
                token (get-in f [:submit :pending])]
            (cond-> {:db (assoc db :editor f)}
              token (assoc :fx [[:http {:on-success [:editor/saved token]}]])))))

  [[rebase]], [[reset]] and [[set-errors]] all settle a pending submit —
  each of them IS the reply landing, in one of its three shapes."
  [form]
  (let [n     (inc (get-in form [:submit :attempts] 0))
        valid (empty? (:errors form))]
    (assoc form :submit {:attempted? true
                         :attempts   n
                         :pending    (when valid n)})))

;; ---------------------------------------------------------------------------
;; The two derivations
;; ---------------------------------------------------------------------------

(defn reset-key
  "The leaf's current RESET REVISION — the generation a control fences
  against.

      (form/reset-key f [:contact :email])   ;=> 0, then 1 after a reset

  `0` for a leaf that has never been reset, so a control is fenced from
  its first render rather than from its first rejection. Advanced only by
  [[reset]], and only for the leaves that reset touched."
  [form path]
  (get (:resets form) path 0))

(defn field
  "The NARROW read: everything ONE control needs about ONE leaf, and
  nothing about any other.

      (rf/reg-sub :editor/field
        (fn [db [_ path]] (form/field (:editor db) path)))

  That is ONE subscription for a form of any size. It recomputes on every
  keystroke — every subscription over app-db does — but its VALUE changes
  only when THIS leaf's does, so a character typed in `:notes` does not
  re-render `:email`. Reading the container instead
  (`(:email (v/sub [:editor/draft]))`) inverts that: every keystroke
  publishes a new draft map, every field's read changes value, and
  because a controlled input's door flushes on the same frame, all of
  them re-render INSIDE the keystroke.

  The projection:

  | key | |
  |---|---|
  | [[path-key]] | the leaf path — the projection's identity |
  | `:value` | what the control shows and edits |
  | `:baseline` | what the domain last accepted here |
  | `:error` | the structured message, or `nil` |
  | `:visited?` | the user has been here and left |
  | `:edited?` | the user changed it |
  | `:show-error?` | there is an error AND it is time to say so |
  | `:reset-key` | the leaf's generation ([[reset-key]]) |

  `:show-error?` is the REVEAL policy, and it is not the validation
  policy: an error shows once the user has visited the leaf, or once a
  submit has been attempted. Which errors exist is the application's to
  decide ([[set-errors]]); when to stop hiding them is a property of the
  form."
  [form path]
  (let [error     (get (:errors form) path)
        visited?  (contains? (:visited form #{}) path)
        attempted (get-in form [:submit :attempted?] false)]
    {path-key     path
     :value       (get-in form (into [:draft] path))
     :baseline    (get-in form (into [:baseline] path))
     :error       error
     :visited?    visited?
     :edited?     (contains? (:edited form #{}) path)
     :show-error? (boolean (and (some? error) (or visited? attempted)))
     :reset-key   (get (:resets form) path 0)}))
