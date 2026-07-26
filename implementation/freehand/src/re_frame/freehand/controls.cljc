(ns re-frame.freehand.controls
  "The first-party Freehand CONTROL KIT — the DC-04 slice of the product
  completion setpoint, grown one serious witness at a time rather than
  promised as a catalogue.

  It ships with the substrate ([ER-04]) rather than as a second artefact,
  and it is deliberately thin. Skins, layouts and application
  compositions are meant to be copied and adapted; what is NOT copy-only
  is the correctness machinery — the narrow read, the generation fence,
  the composing-Enter rule and the exact release — because those are the
  parts every hand-rolled copy gets wrong in the same way.

  Today the kit is two controls and one owner transition:

  | | |
  |---|---|
  | [[field]] | the ordinary control over one form leaf |
  | [[buffered-field]] | the same, committing on blur / Enter behind a generation fence |
  | [[release]] | the causal owner's clear |

  ## Both controls read a LEAF, and there is no way to read a container

  Neither control has a `:value` prop. The only value either accepts is a
  [[re-frame.freehand.form/field]] projection, passed as `:field`, and
  that projection is about ONE leaf:

      [c/field {:field   (v/sub [:editor/field [:contact :email]])
                :on-edit [:editor/edited      [:contact :email]]}]

  That is the whole call site, and the subscription behind it is ONE
  `reg-sub` for a form of any size. The alternative spelling — subscribe
  to the draft map and `get` the key out of it — is not merely
  discouraged here, it is UNSPELLABLE: a caller's forwarded attributes go
  through `v/spread-safe`, whose deny law refuses `value` on a component's
  own controlled element in every build. There is nowhere to put a
  container.

  This matters more on a form than anywhere else. Every keystroke
  publishes a new draft map, so a field reading the container is
  invalidated by a character typed in any OTHER field; and because a
  controlled input's synchronous door (D009) drains re-frame and flushes
  the dirty cells on THIS frame before returning into React, those fields
  re-render INSIDE the keystroke rather than after it. Whole-container
  reads make keystroke latency scale with the size of the form, which is
  a cost model rather than a matter of taste.

  ## Where the state is

  In the form, which is ordinary application data at an application-chosen
  path. Neither control owns a record, a host slot or a local buffer:
  [[buffered-field]]'s draft IS the form's draft leaf, written by an
  ordinary `reg-event` calling
  [[re-frame.freehand.form/edit]]. What makes it *buffered* is that the
  DOMAIN hears about it only on commit — a blur, or an Enter that is not
  finishing an IME composition — not that the text is hidden somewhere
  re-frame cannot see it.

  That is also why [[release]] is exact and why nothing here can be
  orphaned: there is no second store to forget. The controller-addressing
  verbs (`v/controller-key`, `v/controller-revision`,
  `v/controller-current?`) stay available for kit members that DO own a
  protocol record — a typeahead's settled query, a disclosure's open flag
  — and neither control here needs them, because the caller's leaf path
  already IS a caller-supplied address and the leaf's reset revision
  already IS its generation.

  Normative owner:
  [`spec/004-Views.md` §The first-party control kit](../../../../../spec/004-Views.md#the-first-party-control-kit)."
  (:require [re-frame.freehand :as v]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The keyboard law, as a pure function
;; ---------------------------------------------------------------------------

(def commit-key
  "The key that commits a buffered draft."
  "Enter")

(def cancel-key
  "The key that abandons a buffered draft."
  "Escape")

(defn key-intent
  "[[buffered-field]]'s KEYBOARD LAW, as a pure function of two scalars:
  the `key` the user pressed and whether an IME composition was in flight
  (`composing?`). Answers `:commit`, `:cancel`, or `nil` for every other
  key.

      (key-intent \"Enter\"  false)   ;=> :commit
      (key-intent \"Enter\"  true)    ;=> nil
      (key-intent \"Escape\" false)   ;=> :cancel
      (key-intent \"a\"      false)   ;=> nil

  **A COMPOSING ENTER DOES NOT COMMIT**, and that is the whole reason
  this is a function worth naming. Typing Japanese, Chinese or Korean
  through an input method ends each phrase with Enter — an Enter that
  ACCEPTS THE CANDIDATE and belongs entirely to the IME. A control that
  reads it as a commit fires the domain event mid-word, with half a
  phrase in the draft, on every phrase the user types. It is invisible to
  every keyboard the author owns and it makes the control unusable for a
  large fraction of the world's writing systems.

  A composing ESCAPE is likewise the IME's — it cancels the candidate —
  so it is `nil` here too: the draft the user is still building is not
  abandoned because they rejected one suggestion.

  Public and pure so the law is provable by CALLING it, with no browser
  and no host event, and so a kit member with its own keyboard protocol
  answers the composition question through this function rather than
  re-deciding it."
  [key composing?]
  (when-not composing?
    (condp = key
      commit-key :commit
      cancel-key :cancel
      nil)))

;; ---------------------------------------------------------------------------
;; Reading the two scalars off a host key event
;; ---------------------------------------------------------------------------

(defn- event-key
  "The `key` of a host keyboard event. The JVM arm reads a map, so the
  structural host — and a test that has no browser — speaks the same
  shape."
  [e]
  #?(:cljs (.-key e)
     :clj  (:key e)))

(defn- composing?
  "Is an IME composition in flight for this host keyboard event?

  TWO signals, ORed, because the one that is standard is missing on the
  engine that matters most.

  - **The standard `isComposing` flag**, asked of the NATIVE event where
    there is one and of the event itself otherwise. Those are two surfaces
    of ONE fact rather than two signals: React's synthetic keyboard event
    does not carry `isComposing` at all, so a React host has to be asked
    through `nativeEvent`, while a host handing over a raw DOM event has
    no `nativeEvent` and carries the flag directly.
  - **`keyCode` 229**, the sentinel every engine has emitted for two
    decades and what Chromium ACTUALLY fires on the Enter that accepts a
    candidate — with `isComposing` false. Without it the control is broken
    for every input method on Chromium.

  The two are ORed and never ANDed, and FH-CTRL-018 presses them SEPARATELY
  for that reason: an event carrying both cannot fail for either one, so a
  reader of only the standard flag — the reader that breaks on Chromium —
  would pass a proof that set both at once.

  Answering `true` on either signal is the safe direction: a commit wrongly
  withheld is one more Enter, and a commit wrongly taken is a domain event
  carrying half a word."
  [e]
  #?(:cljs (let [flag (or (some-> (.-nativeEvent e) (.-isComposing))
                          (.-isComposing e))]
             (boolean (or flag (= 229 (.-keyCode e)))))
     :clj  (boolean (:composing? e))))

;; ---------------------------------------------------------------------------
;; The controls
;; ---------------------------------------------------------------------------

(v/defview field
  "(v/defview) The ordinary control over ONE form leaf: a native
  `<input>` inside the controlled-input door, publishing every keystroke.

      [c/field {:field    (v/sub [:editor/field [:contact :email]])
                :on-edit  [:editor/edited      [:contact :email]]
                :on-visit [:editor/visited     [:contact :email]]
                :type     \"email\"
                :class    \"input\"}]

  | prop | |
  |---|---|
  | `:field` | REQUIRED — a [[re-frame.freehand.form/field]] projection |
  | `:on-edit` | REQUIRED — the intent the keystroke produces; the live value is appended |
  | `:on-visit` | optional — the intent the blur produces |
  | anything else | forwarded to the `<input>` through `v/spread-safe` |

  `:on-edit` is completed with `::v/value`, so the handler receives
  `[… path text]` and calls [[re-frame.freehand.form/edit]]. The site is
  a literal event vector on a native `<input>` carrying `value`, which is
  exactly the shape D009's door recognises — so the round trip is
  synchronous and the caret, the selection and any composition in flight
  survive it.

  `:on-visit` is the blur, and it is OPTIONAL because a form with no
  reveal-on-blur policy should not have to invent an event to satisfy a
  component. Supplied, it calls [[re-frame.freehand.form/visit]] and is
  what lets that leaf's error appear.

  It renders ONE element and stamps ONE attribute of its own beyond the
  contract: `aria-invalid`, from the projection's `:show-error?`. The
  label, the message and the layout are the application's — this is a
  control, not a widget catalogue, and a form kit that owned the markup
  would be un-adaptable exactly where every design system differs.

  There is NO `:value` prop, and `v/spread-safe` denies a caller-supplied
  `value` in every build. Reading the whole draft map into a control is
  the one mistake this API is shaped to make impossible."
  {:props [:map {:closed false}
           [:field :map]
           [:on-edit :vector]
           [:on-visit {:optional true} [:maybe :vector]]]}
  [{:keys [field on-edit on-visit] :as props}]
  [:input (v/spread-safe {:value        (:value field)
                          :on-input     (conj on-edit ::v/value)
                          :on-blur      on-visit
                          :aria-invalid (when (:show-error? field) "true")}
                         (dissoc props :field :on-edit :on-visit))])

(v/defview buffered-field
  "(v/defview) The same leaf, committed on BLUR or ENTER instead of on
  every keystroke — and fenced against the caller's rejection.

      [c/buffered-field {:field     (v/sub [:editor/field [:invoice :amount]])
                         :on-edit   [:editor/edited   [:invoice :amount]]
                         :on-commit [:editor/committed [:invoice :amount]]
                         :on-cancel [:editor/reverted  [:invoice :amount]]}]

  | prop | |
  |---|---|
  | `:field` | REQUIRED — a [[re-frame.freehand.form/field]] projection |
  | `:on-edit` | REQUIRED — every keystroke, with the live value appended |
  | `:on-commit` | REQUIRED — blur and non-composing Enter, with the leaf's `:reset-key` appended |
  | `:on-cancel` | optional — non-composing Escape |
  | anything else | forwarded through `v/spread-safe` |

  ## The draft is in the form, not in the control

  `:on-edit` writes the form's draft leaf on every keystroke, exactly as
  [[field]] does. What is buffered is the DOMAIN's view of it: the
  application's own state moves continuously, and the value the domain
  accepts moves only at a commit. Nothing is held in a host slot, a local
  atom or a controller record, so a re-render, an abandoned candidate, a
  StrictMode double-invoke and a hot reload are all uneventful — there is
  no snapshot to restore and nothing to reconcile.

  ## The fence

  `:on-commit` carries the leaf's `:reset-key` — the generation
  [[re-frame.freehand.form/reset]] advances — as its last argument, and
  the receiving handler compares it against the committed form before
  acting:

      (rf/reg-event :editor/committed
        (fn [{:keys [db]} [_ path revision]]
          (let [f (:editor db)]
            (when (v/controller-current? revision (form/reset-key f path))
              {:fx [[:dispatch [:invoice/amount-set (get-in f (into [:draft] path))]]]}))))

  The decision is taken IN THE HANDLER against committed state, never by
  a guard captured during render, which is what lets a cancel BEAT a late
  blur rather than race it. And the generation is a revision rather than
  the value because the case that matters is a caller REJECTING a draft
  by reasserting what it already had: the value before that decision and
  the value after it are equal, so nothing derived from the value can see
  it happen.

  ## Enter, Escape, and the IME

  The keyboard branch is [[key-intent]] — a pure function of the key and
  whether a composition is in flight — so a COMPOSING ENTER commits
  nothing. That is not an edge case: it is every phrase a Japanese,
  Chinese or Korean user types.

  Keydown is outside the synchronous door by construction (the door is
  `onInput`/`onChange` on a controlled node), so the commit takes the
  ordinary batched path. Nothing about it needs to be synchronous — the
  value it commits is already in the frame."
  {:props [:map {:closed false}
           [:field :map]
           [:on-edit :vector]
           [:on-commit :vector]
           [:on-cancel {:optional true} [:maybe :vector]]]}
  [{:keys [field on-edit on-commit on-cancel] :as props}]
  (let [revision (:reset-key field)
        commit   (conj on-commit revision)]
    [:input (v/spread-safe {:value        (:value field)
                            :on-input     (conj on-edit ::v/value)
                            :on-blur      commit
                            :aria-invalid (when (:show-error? field) "true")
                            :on-key-down  (v/event [e]
                                            (case (key-intent (event-key e) (composing? e))
                                              :commit commit
                                              :cancel on-cancel
                                              nil))}
                           (dissoc props :field :on-edit :on-commit :on-cancel))]))

;; ---------------------------------------------------------------------------
;; The causal owner's release
;; ---------------------------------------------------------------------------

(defn release
  "The CAUSAL OWNER's clear: remove the form slices at `form-paths` from
  `db`, together, in one value.

      (rf/reg-event :editor/closed
        (fn [{:keys [db]} [_ id]]
          {:db (c/release db [:forms id] [:forms id :attachments])}))

  Cleanup follows the OWNER, never the render. Freehand exposes no
  unmount callback, no dispose registration and no per-occurrence
  teardown slot, and it never will: unmount-driven cleanup destroys a
  draft when a virtualized row scrolls out of view, and makes retention a
  property of what is on screen rather than of what the work is. The
  route, the workflow or the record whose lifetime the form actually
  follows dispatches an ordinary event, and that event calls this.

  **Exact, and atomic.** Only the named paths go — never \"every form\",
  never \"everything this kind holds\" — and they go in ONE value, so no
  intermediate state exists in which the owner is gone and its form is
  not. A path whose parent is not there removes nothing and CREATES
  nothing: releasing twice is the same as releasing once, which matters
  because a route leaving and a record closing are two events that
  legitimately both fire.

  There is nothing else for it to clear. The kit's controls keep their
  state IN the form — [[buffered-field]]'s draft is the form's draft leaf
  — so releasing the form releases the controls, and there is no second
  store to leave orphaned. A kit member that DOES own a protocol record
  keeps it at a path of its own, and the owner names that path here too."
  [db & form-paths]
  (reduce
    (fn [db path]
      (let [path (vec path)]
        (cond
          (empty? path)        db
          (= 1 (count path))   (dissoc db (first path))
          :else (let [parent (pop path)]
                  (if (map? (get-in db parent))
                    (update-in db parent dissoc (peek path))
                    db)))))
    db
    form-paths))
