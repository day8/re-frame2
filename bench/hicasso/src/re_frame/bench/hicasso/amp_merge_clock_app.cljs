(ns re-frame.bench.hicasso.amp-merge-clock-app
  "THE `:&` ATTRIBUTE-MERGE SPELLING, ON THE CLOCK (rf2-pqyxz).

  HD-023 rules one attribute merge, spelled `:&`, and closes with a
  caveat it wrote against itself rather than let pass:

  > `:&` is an addition to the codec this programme took from
  > reagent-slim, and the ruling that Hicasso authors no codec carries a
  > caveat that such additions be measured rather than assumed.
  > Structurally this one is a single `contains?` per attribute map,
  > returning the map by identity when the key is absent, so it allocates
  > nothing on an element that does not use it — but its clock cost is
  > **unmeasured** and is named here rather than claimed away.

  This entry is that measurement. It prices the SPELLING, on the screen
  HD-023 demonstrates it on, against the same screen written the other
  way.

  ## What varies, and what deliberately does not

  ONE page, written twice. `expanded-body` writes the four attribute maps
  out per field, the way the corpus has them; `merged-body` writes the
  `field` helper plus four call sites, with `:&` carrying each call
  site's remainder. Both read the same two subscriptions per boundary,
  build the same four controlled inputs, and — the fairness gate proves
  it before a clock is read — mount the same DOM.

  **The helper is IN the measured difference, on purpose.** `:&` without
  a wrapper to forward into is not a spelling anybody writes, and HD-023's
  ergonomic claim is precisely *four repeated attribute maps become one
  helper plus four call sites*. So the difference this instrument reads is
  the whole authoring change: the caller map the call site builds, the
  `dissoc` the helper performs, and the codec's `merge-caller` — not
  `merge-caller` in isolation. A row that priced only the codec's share
  would understate what an author actually pays and would not answer the
  question the caveat asks.

  ## The arms

    :floor       the crossing carrying nothing — `floor-body` answers nil,
                 so the page is the wrapper and no more. Every ratio is
                 against the floor measured in THAT round, because this
                 box drifts across rounds by more than the effect being
                 measured.
    :expanded    the four attribute maps written out
    :expanded-b  THE NULL CONTROL. A second boundary head over the same
                 body — identical work, a different arm slot. Its ratio to
                 `:expanded` is what this instrument reads when there is
                 NOTHING to read, and it is the only honest answer to the
                 bead's own question, *state plainly whether it is inside
                 instrument resolution*. Without it, a `:merged/:expanded`
                 range that straddles 1.0 says the effect was not seen and
                 cannot say whether it could have been.
    :merged      the helper plus four `:&` call sites
    :ctl-2x      THE POSITIVE CONTROL — the expanded arm's own page,
                 mounted TWICE inside one window. Not a doubled page:
                 literally the same operation performed twice, so the
                 per-sample additive constants double with it and the
                 prediction is a clean 2.00x rather than a modelled one.
                 rf2-5yn9 records the same construction and its reason.
    :helper      LADDER RUNG 1 (rf2-z143r). The same page written with a
                 helper that takes EXPLICIT ARGUMENTS and writes no `:&`:
                 each call site builds a remainder map, the helper is
                 called with it, and the helper spells the five forwarded
                 attributes out as ordinary keys. Call-site map plus
                 helper call, no merge.
    :no-dissoc   LADDER RUNG 2 (rf2-z143r). `:helper`'s call sites
                 CHARACTER FOR CHARACTER, and `:helper`'s helper with its
                 five spelled-out keys replaced by one `:&`. Nothing else
                 differs between the two, which is what makes the pair a
                 price for `merge-caller` and for nothing else.
    :helper-lean RUNG (1) AGAIN, AS A CLEAN PAIR (rf2-v5oto). `:helper`
                 with the `:class` passenger taken out: no call site
                 carries a `:class` key, and the title's extra class
                 rides the TAG exactly as `:expanded` has it. Its
                 attribute map is `:expanded`'s key for key and in
                 `:expanded`'s order, so `:helper-lean`/`:expanded`
                 differ only by the wrapper.
    :no-dissoc-lean
                 RUNG (3) AGAIN, AS A CLEAN PAIR (rf2-v5oto).
                 `:no-dissoc` with the same passenger taken out: the
                 three classless call sites drop `:class nil`, so every
                 field's merged attribute map holds exactly what
                 `:merged`'s holds. `:merged`/`:no-dissoc-lean` differ
                 only by the `:k`/`:busy?` round trip.

  ## The decomposition ladder (rf2-z143r)

  The five arms above price the authoring change WHOLE and apportion
  nothing, and the three things inside it do not have the same standing:
  the caller map and the `dissoc` are the AUTHOR'S code and an author can
  change them, while `merge-caller` is the CODEC'S and only this
  programme can. `:helper` and `:no-dissoc` are two rungs placed BETWEEN
  `:expanded` and `:merged` so that each step changes exactly one thing.

      :expanded --(1)--> :helper --(2)--> :no-dissoc --(3)--> :merged

  (1) THE AUTHOR'S WRAPPER. A remainder map built at each call site, a
      function call, and the five forwarded attributes arriving as
      arguments rather than as literals in the element's own map.
  (2) THE CODEC'S MERGE. The same five attributes routed through `:&`
      instead of written as keys, so this rung is exactly what
      `merge-caller` does on a present remainder: the `dissoc` of the
      owned map, the `denied-slots` fold over it, the filter of the
      caller and the union. **The only term here that this programme
      owns.**
  (3) THE AUTHOR'S `:k`/`:busy?` ROUND TRIP. `:merged`'s call sites put
      the field key and the busy flag INTO the caller map and its helper
      `dissoc`s them back out; `:no-dissoc`'s pass them as arguments
      instead. An author owns both spellings, and the second is the
      repair for the first.

  **The three sum to the whole by construction, not by luck.** The rungs
  are a chain rather than three independent contrasts, so
  (1) + (2) + (3) is `:merged` − `:expanded` term by term and round by
  round. There is therefore no residual, and the absence of one is
  arithmetic rather than evidence: it corroborates nothing.

  **What a rung is NOT.** A rung is the difference between ITS TWO ARMS —
  a bundle, not a platonic component — and two of these bundles are known
  to carry a passenger. Both are named here rather than corrected,
  because correcting either would mean touching a frozen arm:

    - RUNG (1) ALSO CARRIES THE TITLE FIELD'S CLASS. `:expanded` writes
      it in the TAG (`.form-control.form-control-lg`, which
      `fold-shorthand!` takes by identity); every other arm DECLARES it,
      so `class-names` composes instead. That is 100 of the 400 fields,
      it is paid identically by both arms of rungs (2) and (3), and it
      therefore lands entirely in (1). It is also the only place
      HD-023(c″)'s shorthand fold appears in this ladder at all: the fold
      is not isolated here either, and rung (1) is an UPPER BOUND on the
      author's wrapper rather than a reading of it.
    - RUNGS (1) AND (3) SHARE A PASSENGER, WITH OPPOSITE SIGNS: three
      `:class nil` keys. One helper cannot omit a key for three fields
      out of four without an `assoc`, so `field-explicit` writes `:class`
      unconditionally and BOTH new arms' call sites carry `:class nil` on
      the three fields that have no class — which is exactly what keeps
      them key-for-key identical and rung (2) clean. `:expanded` and
      `:merged` are frozen and carry no such key. So the passenger is
      ADDED to rung (1) (`:helper` has it, `:expanded` does not) and
      SUBTRACTED from rung (3) (`:no-dissoc` has it, `:merged` does not),
      in equal measure. Neither rung is separately interpretable; their
      SUM is, and it is passenger-free.

      **The passenger is not small, and the reason is a cliff rather
      than a key.** `cljs.core/PersistentArrayMap`'s
      `HASHMAP-THRESHOLD` is 8 and its `-assoc` promotes to a
      `PersistentHashMap` on the entry that would make nine; the
      compiler's `array-map-threshold` is the same 8, so a nine-key map
      LITERAL is emitted straight as a `PersistentHashMap`. Both halves
      matter here, because `merge-caller` builds the merged map by
      `merge`ing the four owned entries onto the caller's remainder.
      Against `:merged` — nine entries on the title, eight on the other
      three — the nil class puts `:no-dissoc` a map REPRESENTATION
      apart on 300 of the page's 400 fields. Against `:expanded` it is
      all 400: `:expanded` carries no `:class` key at ALL, its four
      literals are eight entries each, and `:helper`'s are nine each.
      No reading of rung (1) or rung (3) alone should be quoted as the
      wrapper's or the round trip's price.

  So the three rungs answer TWO questions and not three: rung (2) is the
  CODEC'S share and is clean, and rungs (1) + (3) summed are the
  AUTHOR'S share and are clean. That sum is the quantity a reader of
  this ladder actually wants, and the record used to make them add two
  printed vectors to get it — so it is a key of its own now, `:author`,
  term by term and round by round over values already in the record.

  ## Splitting the author's share — the clean pairs (rf2-v5oto)

  A sum is not a split. `:helper-lean` and `:no-dissoc-lean` are the two
  arms that make each half a pair differing by ONE thing whose two
  members sit on the SAME SIDE of the array-map cliff on every one of
  the 400 fields.

    (1') THE AUTHOR'S WRAPPER, CLEANLY — `:helper-lean` / `:expanded`.
         The helper writes the eight keys `:expanded` writes, in
         `:expanded`'s order, and no ninth; the title's
         `form-control-lg` rides the TAG in both arms. Both are a
         `PersistentArrayMap` on all four fields, and what is left
         between them is a remainder map built at the call site, a
         function call, and the field key arriving as an argument rather
         than as a literal. This rung also sheds the SECOND passenger
         named above: the title's class reaches the emitted class slot
         by the same route in both arms, so `fold-shorthand!` takes it
         by identity in both and HD-023(c″)'s shorthand fold is no
         longer inside the rung.

         The lean arm spends TWO helpers to do it — `field-lean-lg` is
         `field-lean` with one extra class on the tag and nothing else —
         because the alternative is a branch inside one helper, and a
         per-field branch is a passenger in the very rung this arm
         exists to clean. Two functions cost the call site nothing: each
         site still writes one map and makes one call.

    (3') THE AUTHOR'S ROUND TRIP, CLEANLY — `:merged` /
         `:no-dissoc-lean`. Dropping `:class nil` from the three
         classless call sites makes each remainder map exactly the map
         `:merged`'s helper hands to `:&` after its `dissoc` — same
         keys, same order — so both arms hold nine entries on the title
         and eight on the other three. What is left between them is
         `:merged`'s call sites putting `:k` and `:busy?` INTO the
         caller map and its helper taking them back out.

  **THE TWO CLEAN RUNGS ARE NOT A CHAIN, and their sum is not
  `:author`.** Each is a pair against a frozen arm and nothing joins
  `:helper-lean` to `:no-dissoc-lean` in one step, so the record carries
  `:split-residual` — `:author` minus (1') minus (3') — precisely so a
  reader who subtracts is told the answer is NOT zero by construction.
  It reduces to `(:helper-lean − :helper) − (:no-dissoc-lean −
  :no-dissoc)`: the `:class` passenger costs a different amount on the
  spelled-keys path than on the `:&` path, and the title's class reaches
  the slot through the tag in (1') but through `:&` in (3'). A residual
  with contents, not a check.

  ## The control is adjudicated STRICTLY, and per round (rf2-egdaq)

  `rf.bench.hicasso.lane/control-verdict-strict` decides it: every round's `:ctl-2x` /
  `:expanded` ratio must sit inside ±25% of 2.00x, and one bad round
  refuses the run however good the others were. This instrument is
  entitled to that rule and the coarse-leg rows are not — the 2026-07-31
  ruling keeps the weaker OVERLAP rule for legs sitting on Chrome's
  100 µs clamp and names a batched window clear of the quantum as its own
  revisit trigger. This one reads ~4 ms judged and ~8 ms control, forty to
  eighty quanta clear, so a round outside the band here is not the clock.

  The adjudication is also PER ROUND rather than aggregate. The three runs
  published on 2026-08-15 compared a cross-round prediction against a
  cross-round range, which never puts a round beside its own denominator,
  and recorded only that aggregate — so their strict verdict is not
  recoverable and this file now records `:per-round` for the control as
  well as for the effect and the null. A run that cannot be
  re-adjudicated without re-running the window is a run that has to be
  re-run to answer a question it already had the data for.

  `:floor` and `:ctl-2x` are `:parity-exempt?`: one builds an empty page
  and one builds two pages, both on purpose, and folding either into the
  equality would make the fairness gate a permanent failure.
  `:expanded-b` is NOT exempt — it builds the judged page and belongs in
  the equality, which is one more thing the fairness gate then holds.

  ## What is published

  `rf.bench.hicasso.lane/ratio-between :merged :expanded` over the per-round
  floor-normalised ratios, with its range and its `:straddles-1?` flag —
  and beside it the same statistic for `:expanded-b`, which carries no
  effect by construction. A `:merged` range that straddles 1.0 means
  INDISTINGUISHABLE and the row must say so rather than quote the mean.

  Per element: the per-round difference of the two arms' raw p50 mount
  times, divided by the `:&` sites on the page, in nanoseconds — reported
  with its range and beside the null arm's, which is the same arithmetic
  over a difference known to be zero.

  **Both carry their per-round vector** (rf2-j7o9w). They did not, and the
  null was the single figure this instrument kept no round-by-round record
  of — so `rf2-adld3`, whose whole window turned on the null, had to
  reconstruct it from the ladder's rungs before it could state a
  resolution bound. Every ns/field row now comes through the one
  summariser [[ns-terms]], so nothing here needs a sibling row to be
  re-adjudicated.

  Owner: rf2-pqyxz. Witness: `front/census_article_editor_cljs_test`'s
  ported RealWorld article-editor fieldset (HD-023, *Demonstrated, not
  asserted*), scaled to [[boundaries]] boundaries because a page of one
  cannot be timed. Instrument: `lane.cljs`, ridden through `run.cjs` on
  the existing `:hicasso-bench` build id via `HICASSO_INIT_FN` — no
  `shadow-cljs.edn` edit."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]))

(def frame-id ::amp-merge-clock)

(def ^:private boundaries
  "Boundaries per page. Each is one article-editor fieldset — four
  controlled inputs and an error slot, ten elements. Large enough that the
  per-mount constants (`createRoot`, the container, the root crossing) are
  a small share of the window and that the window clears Chrome's 100 us
  `performance.now()` clamp by orders of magnitude; small enough that a
  round is seconds rather than minutes."
  100)

(def ^:private fields-per-boundary 4)

(def ^:private amp-sites
  "How many elements on the measured page carry a `:&` in the merged arm —
  the divisor of every per-element figure this file prints.

  It is also the FIELD count, which is what makes it the right divisor for
  the ladder's rungs too (rf2-z143r): `:helper` writes no `:&` at all, but
  every rung's cost is per field, and a field is a `:&` site in the arms
  that have one. A ladder row therefore reads `ns/field`, and the two
  published rows keep reading `ns/:& site` — the same number, named for
  what it divides in each case."
  (* fields-per-boundary boundaries))

;; ---------------------------------------------------------------------------
;; Subscriptions and data
;; ---------------------------------------------------------------------------
;;
;; Two reads per boundary, the same two in both arms. The ERROR TEXT is
;; per-boundary and lands in the DOM as text, which is what makes the
;; far-end read-back and the mutation gate able to see the page at all: a
;; controlled input's `value` is a DOM PROPERTY, and `rf.bench.hicasso.lane/canonical`
;; reads attributes.

(rf/reg-sub :amp/draft  (fn [db [_ id]] (get-in db [:drafts id])))
(rf/reg-sub :amp/errors (fn [db [_ id]] (get-in db [:errors id])))

(rf/reg-event :amp/seed (fn [_ [_ db]] {:db db}))

(def ^:private draft-for
  {:title "A title" :description "A description" :body "A body" :tagList "a,b"
   :busy? false})

(defn- default-error [i] (str "can't be blank " i))
(defn- mutated-error [i] (str "must be filled " i))

(defn- seed-db [n error-of]
  {:drafts (into {} (map (fn [i] [i draft-for])) (range n))
   :errors (into {} (map (fn [i] [i {:description (error-of i)}])) (range n))})

;; ---------------------------------------------------------------------------
;; BEFORE — the four attribute maps, written out
;; ---------------------------------------------------------------------------
;;
;; `front/census_article_editor_cljs_test`'s `inline-fieldset`, with the
;; constants lifted to per-boundary subscription reads so the page is a
;; page rather than a constant.

(defn expanded-body
  [{:keys [id]}]
  (let [draft  (rf.hicasso/sub [:amp/draft id])
        errors (rf.hicasso/sub [:amp/errors id])
        busy?  (:busy? draft)]
    [:fieldset
     [:fieldset.form-group
      [:input.form-control.form-control-lg
       {:type "text" :name "title" :placeholder "Article Title"
        :data-testid "editor-title"
        :value (:title draft) :disabled busy?
        :on-blur  [:amp/blur id :title]
        :on-input [:amp/edit id :title ::rf.hicasso/value]}]
      (when-some [e (:title errors)] [:div.error-messages e])]
     [:fieldset.form-group
      [:input.form-control
       {:type "text" :name "description" :placeholder "What's this article about?"
        :data-testid "editor-description"
        :value (:description draft) :disabled busy?
        :on-blur  [:amp/blur id :description]
        :on-input [:amp/edit id :description ::rf.hicasso/value]}]
      (when-some [e (:description errors)] [:div.error-messages e])]
     [:fieldset.form-group
      [:input.form-control
       {:type "text" :name "body" :placeholder "Write your article (in markdown)"
        :data-testid "editor-body"
        :value (:body draft) :disabled busy?
        :on-blur  [:amp/blur id :body]
        :on-input [:amp/edit id :body ::rf.hicasso/value]}]
      (when-some [e (:body errors)] [:div.error-messages e])]
     [:fieldset.form-group
      [:input.form-control
       {:type "text" :name "tags" :placeholder "Enter tags (comma-separated)"
        :data-testid "editor-tags"
        :value (:tagList draft) :disabled busy?
        :on-blur  [:amp/blur id :tagList]
        :on-input [:amp/edit id :tagList ::rf.hicasso/value]}]
      (when-some [e (:tagList errors)] [:div.error-messages e])]]))

;; ---------------------------------------------------------------------------
;; AFTER — one helper, `:&` carrying each call site's remainder
;; ---------------------------------------------------------------------------
;;
;; The helper owns exactly the things that must not vary: the controlled
;; pair, the busy rule, the blur intent and the error slot. Everything a
;; call site still needs to say rides through `:&` as ONE key, and the
;; owned-literal law means the helper does not have to defend itself.
;;
;; The title field's `form-control-lg` arrives THROUGH `:&` and composes
;; with the tag's `.form-control` on the emitted class slot (HD-023(c")),
;; where the expanded arm spells the composition itself. The fairness gate
;; is what proves the two land on the same string.

;; EVERY FIELD HELPER ON THIS PAGE IS PUBLIC, and only so that
;; `amp_merge_arms_cljs_test` can call the arms' OWN code rather than keep
;; a second copy of it — the second-authority shape this lane refuses
;; everywhere else. In ClojureScript `defn-` is `(defn ^:private …)` and
;; `:private` is analyzer metadata alone: the emitted function is the same
;; function, so no arm's behaviour, allocation or timing moves. The
;; frozen arms' bodies are otherwise untouched.

(defn field
  [draft errors id {:keys [k busy?] :as attrs}]
  [:fieldset.form-group
   [:input.form-control {:&        (dissoc attrs :k :busy?)
                         :value    (get draft k)
                         :disabled busy?
                         :on-blur  [:amp/blur id k]
                         :on-input [:amp/edit id k ::rf.hicasso/value]}]
   (when-some [e (get errors k)] [:div.error-messages e])])

(defn merged-body
  [{:keys [id]}]
  (let [draft  (rf.hicasso/sub [:amp/draft id])
        errors (rf.hicasso/sub [:amp/errors id])
        busy?  (:busy? draft)]
    [:fieldset
     (field draft errors id
            {:k :title :busy? busy? :class "form-control-lg"
             :type "text" :name "title" :placeholder "Article Title"
             :data-testid "editor-title"})
     (field draft errors id
            {:k :description :busy? busy?
             :type "text" :name "description" :placeholder "What's this article about?"
             :data-testid "editor-description"})
     (field draft errors id
            {:k :body :busy? busy?
             :type "text" :name "body" :placeholder "Write your article (in markdown)"
             :data-testid "editor-body"})
     (field draft errors id
            {:k :tagList :busy? busy?
             :type "text" :name "tags" :placeholder "Enter tags (comma-separated)"
             :data-testid "editor-tags"})]))

;; ---------------------------------------------------------------------------
;; THE TWO LADDER RUNGS — the same page a third and a fourth way (rf2-z143r)
;; ---------------------------------------------------------------------------
;;
;; These two exist to be DIFFERENCED, against `:expanded` below them and
;; `:merged` above, so what matters about them is what they share. They
;; share their call sites exactly — the same four remainder maps, written
;; out twice rather than passed through a common function, because a
;; higher-order call is itself a cost and it would land on the ladder
;; instead of the thing being priced. And they share every owned attribute
;; the helper writes. The ONE difference between them is whether the five
;; forwarded attributes are spelled as keys or handed to `:&`, which is
;; what makes rung (2) a price for `merge-caller`.
;;
;; `:class nil` on three of the four call sites is deliberate and is the
;; ladder's stated asymmetry: `field-explicit` writes `:class`
;; unconditionally because one helper cannot omit a key for three fields
;; out of four without an `assoc`, so the remainder maps carry the key
;; too. It costs one map entry and one `class-names` call that `:merged`
;; does not pay, and the docstring's rung (3) says which way that leans.

(defn field-explicit
  "RUNG 1's helper. Explicit arguments, no `:&`, no `dissoc` — the five
  forwarded attributes are read off the remainder map and written as
  ordinary keys, so the codec meets a plain attribute map and
  `merge-caller` returns it by identity.

  The three rebindings (`cls`, `typ`, `nm`) are there because `type` and
  `name` are `cljs.core` fns and a bench arm may not be the place a reader
  has to work out which one is in scope."
  [draft errors id k busy? {cls :class typ :type nm :name
                            :keys [placeholder data-testid]}]
  [:fieldset.form-group
   [:input.form-control {:class       cls
                         :type        typ
                         :name        nm
                         :placeholder placeholder
                         :data-testid data-testid
                         :value       (get draft k)
                         :disabled    busy?
                         :on-blur     [:amp/blur id k]
                         :on-input    [:amp/edit id k ::rf.hicasso/value]}]
   (when-some [e (get errors k)] [:div.error-messages e])])

(defn field-no-dissoc
  "RUNG 2's helper. `field-explicit` with its five spelled-out keys
  replaced by one `:&`, and nothing else changed. No `dissoc`: the field
  key and the busy flag arrive as arguments, so the remainder map is
  already the map the merge wants."
  [draft errors id k busy? attrs]
  [:fieldset.form-group
   [:input.form-control {:&        attrs
                         :value    (get draft k)
                         :disabled busy?
                         :on-blur  [:amp/blur id k]
                         :on-input [:amp/edit id k ::rf.hicasso/value]}]
   (when-some [e (get errors k)] [:div.error-messages e])])

(defn explicit-body
  [{:keys [id]}]
  (let [draft  (rf.hicasso/sub [:amp/draft id])
        errors (rf.hicasso/sub [:amp/errors id])
        busy?  (:busy? draft)]
    [:fieldset
     (field-explicit draft errors id :title busy?
                     {:class "form-control-lg"
                      :type "text" :name "title" :placeholder "Article Title"
                      :data-testid "editor-title"})
     (field-explicit draft errors id :description busy?
                     {:class nil
                      :type "text" :name "description"
                      :placeholder "What's this article about?"
                      :data-testid "editor-description"})
     (field-explicit draft errors id :body busy?
                     {:class nil
                      :type "text" :name "body"
                      :placeholder "Write your article (in markdown)"
                      :data-testid "editor-body"})
     (field-explicit draft errors id :tagList busy?
                     {:class nil
                      :type "text" :name "tags"
                      :placeholder "Enter tags (comma-separated)"
                      :data-testid "editor-tags"})]))

(defn no-dissoc-body
  [{:keys [id]}]
  (let [draft  (rf.hicasso/sub [:amp/draft id])
        errors (rf.hicasso/sub [:amp/errors id])
        busy?  (:busy? draft)]
    [:fieldset
     (field-no-dissoc draft errors id :title busy?
                      {:class "form-control-lg"
                       :type "text" :name "title" :placeholder "Article Title"
                       :data-testid "editor-title"})
     (field-no-dissoc draft errors id :description busy?
                      {:class nil
                       :type "text" :name "description"
                       :placeholder "What's this article about?"
                       :data-testid "editor-description"})
     (field-no-dissoc draft errors id :body busy?
                      {:class nil
                       :type "text" :name "body"
                       :placeholder "Write your article (in markdown)"
                       :data-testid "editor-body"})
     (field-no-dissoc draft errors id :tagList busy?
                      {:class nil
                       :type "text" :name "tags"
                       :placeholder "Enter tags (comma-separated)"
                       :data-testid "editor-tags"})]))

;; ---------------------------------------------------------------------------
;; THE TWO CLEAN PAIRS — the same page a fifth and a sixth way (rf2-v5oto)
;; ---------------------------------------------------------------------------
;;
;; Each of these is differenced against ONE FROZEN ARM, so what matters
;; about each is what it shares with that partner rather than with its
;; sibling here. `:helper-lean` holds `:expanded`'s eight attribute keys
;; in `:expanded`'s order and carries the title's class on the TAG the way
;; `:expanded` does. `:no-dissoc-lean` holds `:merged`'s remainder maps
;; key for key and in their order — which is `:no-dissoc`'s call sites
;; with `:class nil` gone from the three fields that have no class, the
;; only edit either arm makes to the rung it repairs.
;;
;; Neither pair crosses the array-map cliff on any of the 400 fields, and
;; that is the whole point of them: the ladder's rungs (1) and (3) each
;; compare an eight-entry map against a nine-entry one, which is a
;; `PersistentArrayMap` against a `PersistentHashMap`.

(defn field-lean
  "RUNG (1')'s helper. `field-explicit` with no `:class` key at all —
  eight keys, `:expanded`'s eight, written in `:expanded`'s order — so
  the codec meets an attribute map of the same size, the same shape and
  the same representation the frozen arm hands it.

  The two rebindings (`typ`, `nm`) are `field-explicit`'s and are there
  for its reason: `type` and `name` are `cljs.core` fns."
  [draft errors id k busy? {typ :type nm :name
                            :keys [placeholder data-testid]}]
  [:fieldset.form-group
   [:input.form-control {:type        typ
                         :name        nm
                         :placeholder placeholder
                         :data-testid data-testid
                         :value       (get draft k)
                         :disabled    busy?
                         :on-blur     [:amp/blur id k]
                         :on-input    [:amp/edit id k ::rf.hicasso/value]}]
   (when-some [e (get errors k)] [:div.error-messages e])])

(defn field-lean-lg
  "[[field-lean]] with `form-control-lg` on the TAG and nothing else
  changed — the title field's helper, and the reason no call site in this
  arm needs a `:class` key.

  A SECOND FUNCTION RATHER THAN A BRANCH, deliberately. One helper taking
  an `lg?` flag would run a per-field test `:expanded` does not run, and
  rung (1') exists to be free of exactly that class of passenger. The
  call site pays the same either way — one map, one call — so the branch
  would buy nothing and cost a term."
  [draft errors id k busy? {typ :type nm :name
                            :keys [placeholder data-testid]}]
  [:fieldset.form-group
   [:input.form-control.form-control-lg {:type        typ
                                         :name        nm
                                         :placeholder placeholder
                                         :data-testid data-testid
                                         :value       (get draft k)
                                         :disabled    busy?
                                         :on-blur     [:amp/blur id k]
                                         :on-input    [:amp/edit id k ::rf.hicasso/value]}]
   (when-some [e (get errors k)] [:div.error-messages e])])

(defn lean-body
  [{:keys [id]}]
  (let [draft  (rf.hicasso/sub [:amp/draft id])
        errors (rf.hicasso/sub [:amp/errors id])
        busy?  (:busy? draft)]
    [:fieldset
     (field-lean-lg draft errors id :title busy?
                    {:type "text" :name "title" :placeholder "Article Title"
                     :data-testid "editor-title"})
     (field-lean draft errors id :description busy?
                 {:type "text" :name "description"
                  :placeholder "What's this article about?"
                  :data-testid "editor-description"})
     (field-lean draft errors id :body busy?
                 {:type "text" :name "body"
                  :placeholder "Write your article (in markdown)"
                  :data-testid "editor-body"})
     (field-lean draft errors id :tagList busy?
                 {:type "text" :name "tags"
                  :placeholder "Enter tags (comma-separated)"
                  :data-testid "editor-tags"})]))

(defn no-dissoc-lean-body
  "RUNG (3')'s page: [[field-no-dissoc]] — `:no-dissoc`'s own helper,
  reused rather than re-spelled — over `:merged`'s remainder maps.

  Each map here is what `:merged`'s `(dissoc attrs :k :busy?)` answers,
  key for key and in the order `dissoc` leaves them: `:class` first on
  the title, absent everywhere else. So the only thing this arm and
  `:merged` do differently is where `:k` and `:busy?` travel."
  [{:keys [id]}]
  (let [draft  (rf.hicasso/sub [:amp/draft id])
        errors (rf.hicasso/sub [:amp/errors id])
        busy?  (:busy? draft)]
    [:fieldset
     (field-no-dissoc draft errors id :title busy?
                      {:class "form-control-lg"
                       :type "text" :name "title" :placeholder "Article Title"
                       :data-testid "editor-title"})
     (field-no-dissoc draft errors id :description busy?
                      {:type "text" :name "description"
                       :placeholder "What's this article about?"
                       :data-testid "editor-description"})
     (field-no-dissoc draft errors id :body busy?
                      {:type "text" :name "body"
                       :placeholder "Write your article (in markdown)"
                       :data-testid "editor-body"})
     (field-no-dissoc draft errors id :tagList busy?
                      {:type "text" :name "tags"
                       :placeholder "Enter tags (comma-separated)"
                       :data-testid "editor-tags"})]))

(defn floor-body
  "The crossing carrying nothing. A boundary is reached through a hiccup
  vector whatever its body answers, so the floor is the crossing and not
  zero."
  [_]
  nil)

(rf.hicasso/defview expanded-arm       [props] (expanded-body props))
(rf.hicasso/defview expanded-arm-b     [props] (expanded-body props))
(rf.hicasso/defview merged-arm         [props] (merged-body props))
(rf.hicasso/defview explicit-arm       [props] (explicit-body props))
(rf.hicasso/defview no-dissoc-arm      [props] (no-dissoc-body props))
(rf.hicasso/defview lean-arm           [props] (lean-body props))
(rf.hicasso/defview no-dissoc-lean-arm [props] (no-dissoc-lean-body props))
(rf.hicasso/defview floor-arm          [props] (floor-body props))

;; ---------------------------------------------------------------------------
;; The page — identical for every arm but the body
;; ---------------------------------------------------------------------------

(defn- page [view]
  (into [:div {:class "page"}]
        (map (fn [i] [view {:id i}]))
        (range boundaries)))

(def ^:private elements-per-boundary
  "One `fieldset`, four `fieldset.form-group`, four `input`, and the ONE
  error slot the seed puts in error — the arithmetic written down before
  the run, so a mount that rendered a prefix cannot be banked."
  (+ 1 fields-per-boundary fields-per-boundary 1))

(def ^:private page-elements (+ 1 (* elements-per-boundary boundaries)))
(def ^:private floor-elements 1)

;; ---------------------------------------------------------------------------
;; Arms
;; ---------------------------------------------------------------------------

(defn- mount-page
  [view]
  (fn [container _props _n] (rf.hicasso/mount! container {:frame frame-id} (page view))))

(defn- unmount-page [handle] (rf.hicasso/unmount! handle))

(def ^:private arms
  [{:id :floor :k 1 :elements floor-elements :parity-exempt? true
    :mount (mount-page floor-arm) :unmount unmount-page}
   {:id :expanded :k 1 :elements page-elements
    :mount (mount-page expanded-arm) :unmount unmount-page}
   {:id :expanded-b :k 1 :elements page-elements
    :mount (mount-page expanded-arm-b) :unmount unmount-page}
   {:id :merged :k 1 :elements page-elements
    :mount (mount-page merged-arm) :unmount unmount-page}
   {:id :ctl-2x :k 2 :elements page-elements :parity-exempt? true
    :mount (mount-page expanded-arm) :unmount unmount-page}
   ;; THE TWO LADDER RUNGS, appended so the five above keep their entries
   ;; exactly (rf2-z143r). Neither is `:parity-exempt?`: both build the
   ;; judged page and both belong in the equality, so the fairness gate
   ;; holds five arms to one 1,001-element page rather than three.
   {:id :helper :k 1 :elements page-elements
    :mount (mount-page explicit-arm) :unmount unmount-page}
   {:id :no-dissoc :k 1 :elements page-elements
    :mount (mount-page no-dissoc-arm) :unmount unmount-page}
   ;; THE TWO CLEAN PAIRS' arms, appended for the same reason (rf2-v5oto):
   ;; every entry above keeps its position, so no arm the published rows
   ;; or the ladder's rungs are read off changes slot. Neither is
   ;; `:parity-exempt?` — both build the judged page.
   {:id :helper-lean :k 1 :elements page-elements
    :mount (mount-page lean-arm) :unmount unmount-page}
   {:id :no-dissoc-lean :k 1 :elements page-elements
    :mount (mount-page no-dissoc-lean-arm) :unmount unmount-page}])

(defn- arm-named [id] (first (filter #(= id (:id %)) arms)))

(def ^:private sampling
  "THE LANE'S PAGE-MOUNT SAMPLING, which this arm now runs (rf2-6ta5r).

  It ran `{:warmup 3 :samples 6}`, and on that sampling the NULL arm
  degraded: `:expanded-b`/`:expanded` read `[0.9467 1.4737 1.0294 1
  0.9853]` on a quantity that is 1.0 BY CONSTRUCTION — the same body under
  a second boundary head. Four rounds sit within 5.3% of 1.0 and round two
  reads +47%, which on the per-field arithmetic is +4,500 ns on a
  difference of zero. An impossible reading bounds nothing, so that window
  claimed no term inside instrument resolution, its own included.

  ROUND TWO IS A WARM-UP ROUND, and that is the connection to `rf2-h904p`
  on `direct_return_clock_app`. Replaying [[rf.bench.hicasso.lane/rounds!]] against
  `order-guard/slot-order` puts every arm's FIRST-THIRD phase stratum at
  rounds one and two — 3 to 15 prior executions of the arm — and its
  LAST-THIRD at rounds four and five, 32 to 44. The null degraded inside
  exactly the span the other harness's guard refused on. `p50` over six
  samples cannot be moved 1.47x by one outlier: at least three of the six
  were elevated, so the round-two event was sustained across half that
  arm's round rather than a single pause, which is the shape of a ramp and
  not of a transient.

  THE ARM COUNT IS NOT THE LEVER, and this settles it for `rf2-v5oto`.
  `run.cjs` names `fewer arms per round` as one of the three moves, and
  `rf2-z143r` taking the schedule from five arms to seven is the lead the
  bead was filed on. The schedule arithmetic answers it: replaying
  [[rf.bench.hicasso.lane/rounds!]] against `order-guard/slot-order` at n = 5, 7, 8 and 9
  on the sampling that failed, every arm still banks 30 samples per run,
  every arm's phase strata are still rounds 1-2 against rounds 4-5 at the
  same prior-execution counts, and the null's true predecessor
  distribution is `{:expanded 20, :merged 10}` at every one of the four.
  The null's POSITION did not move when the ladder landed. Arm count buys
  wall-clock per round and a different set of neighbours; it does not
  touch the axis the null failed on. So the ladder stays whole, and
  `rf2-v5oto`'s two clean-pair arms — the eighth and the ninth — are not
  blocked by this either.

  WHAT IS LEFT IS WARM-UP, and three is below the step the lane itself
  records: `lane.cljs`'s live reproduction read one control `10.32 10.26
  10.26 10.26 10.33 10.28` and then `8.12` for ever, a +27% step falling
  after the SIXTH execution of the site. `{:warmup 8 :samples 12}` puts
  that step inside the warm-up, doubles each phase stratum to n = 20, and
  is what every other harness riding [[rf.bench.hicasso.lane/mount-batch!]] already runs.
  That set is checkable and small — `(rf.bench.hicasso.lane/mount-batch!` has five call
  sites on this lane, this file, `direct_return_clock_app`,
  `coldmount_app`, `p0_converge_app` and `p0_reagent_app` — and the last
  three all sample at 8/12, so the two clocks were the lane's only
  batched-mount outliers.

  WHAT THIS DOES NOT CLAIM. That warm-up PRODUCED the 1.4737. One round of
  five, no second window, and a box bracketed quiet at its two ends but
  never sampled inside a measured window cannot separate a ramp from
  anything else that decayed over the same run. What is established is
  that the seven-arm schedule is not implicated and that the reading
  landed inside the run's least-warmed span; the rest is the next window's
  to confirm, and it should read the null before it reads anything else."
  {:warmup 8 :samples 12})

(def ^:private rounds 5)
(def ^:private control-slack 0.25)

;; ---------------------------------------------------------------------------
;; Read-back — every measured mount, at its own far end
;; ---------------------------------------------------------------------------

(defn- far-end
  "The LAST error slot's text, or nil. Reading the far end and not the
  first is the difference between proving the page rendered and proving it
  started: a mount that rendered its prefix passes an element count taken
  at index 0 and fails here."
  [container]
  (let [ns' (.querySelectorAll container "div.error-messages")
        c   (.-length ns')]
    (when (pos? c) (.-textContent (.item ns' (dec c))))))

(defn- expected-far-end [] (default-error (dec boundaries)))

(defn- verified?
  "This arm's own arithmetic, on this mount. The floor has no error slot
  to read, so its far-end probe is its element count and nothing else — a
  probe that cannot pass manufactures a defect and hides real ones behind
  it."
  [arm container]
  (and (= (:elements arm) (rf.bench.hicasso.lane/element-count container))
       (or (= :floor (:id arm))
           (= (expected-far-end) (far-end container)))))

;; ---------------------------------------------------------------------------
;; The fairness gate
;; ---------------------------------------------------------------------------

(defn parity!
  "Mount every arm at once and compare the judged arms' canonical DOM.
  Answers `rf.bench.hicasso.lane/parity`'s verdict with the mounts already released."
  []
  (let [{:keys [mounts] :as p} (rf.bench.hicasso.lane/parity arms nil)]
    (doseq [m mounts] (rf.bench.hicasso.lane/release! m))
    p))

(defn parity-can-fail?
  "THE MUTATION. Move the data under one arm and the gate must part the
  two pages; an equality that cannot answer false is not a gate. Reseeds
  to the run's own data afterwards, so nothing downstream is measured on
  the mutated page."
  []
  (let [before (let [m (rf.bench.hicasso.lane/mount-arm! (arm-named :expanded) nil)
                     s (rf.bench.hicasso.lane/canonical (:container m))]
                 (rf.bench.hicasso.lane/release! m)
                 s)]
    (rf/with-frame frame-id
      (rf/dispatch-sync [:amp/seed (seed-db boundaries mutated-error)]))
    (let [after (let [m (rf.bench.hicasso.lane/mount-arm! (arm-named :merged) nil)
                      s (rf.bench.hicasso.lane/canonical (:container m))]
                  (rf.bench.hicasso.lane/release! m)
                  s)]
      (rf/with-frame frame-id
        (rf/dispatch-sync [:amp/seed (seed-db boundaries default-error)]))
      (not= before after))))

;; ---------------------------------------------------------------------------
;; The measured window
;; ---------------------------------------------------------------------------

(defn- measure-one!
  [tally arm]
  (let [{:keys [ms mounts]} (rf.bench.hicasso.lane/mount-batch! arm nil (:k arm))]
    (doseq [m mounts]
      (let [ok? (verified? arm (:container m))]
        (swap! tally (fn [{:keys [of bad]}]
                       {:of (inc of) :bad (if ok? bad (inc bad))}))))
    (doseq [m mounts] (rf.bench.hicasso.lane/release! m))
    ms))

(defn- fmt [x n] (.toFixed (double x) n))

(defn- ns-terms
  "The per-round difference of two arms' raw p50 mount times, in
  nanoseconds per `:&` site — the PER-ROUND VECTOR, and the summary over
  it. Every ns/field row this file prints comes through here.

  Raw and not floor-normalised, deliberately: the two terms are measured
  in the SAME round, so an additive drift differences out, and dividing by
  the floor would rescale a difference by a quantity that has nothing to
  do with it.

  ## THE VECTOR IS THE POINT, and this file has already paid for the
  ## version that kept only the summary (rf2-j7o9w)

  A second summariser used to serve the two published rows and answered
  `{:n :min :max :p50}` and nothing else — so the NULL, the quantity every
  resolution claim this instrument makes rests on, was the ONE quantity it
  did not retain round by round. `rf2-adld3`'s window recovered the null's
  five values anyway, by arithmetic over the ladder's rungs: each round's
  `:expanded` median falls out of a rung's own per-round ns and ratio, and
  the null's difference follows. The recovery was exact and checked against
  the rig's own printed `{min max p50}` in all three runs. **That it was
  NEEDED is the defect**, and it needed a SIBLING ROW to be possible at
  all — an instrument should not be re-adjudicable only by way of a
  neighbour that happens to keep better records. One summariser now, so
  the effect, the null and the four rungs are all re-adjudicable from what
  the run printed.

  The summary is taken over the ROUNDED vector rather than beside it, so a
  reader can check `{:min :max :p50}` against the printed `:per-round` and
  get an exact match instead of a near one."
  [p50s a b]
  (let [vs (mapv (fn [r] (rf.bench.hicasso.lane/round4 (/ (* 1e6 (- (get r a) (get r b))) amp-sites)))
                 p50s)]
    (assoc (rf.bench.hicasso.lane/summarise vs) :per-round vs)))

(defn- ns-combine
  "Combine two or more [[ns-terms]] records with `f`, term by term and
  round by round, and summarise the result the way [[ns-terms]]
  summarises its own — over the combined vector, so `{:min :max :p50}`
  matches the printed `:per-round` exactly rather than nearly.

  EXACT ARITHMETIC OVER VALUES ALREADY IN THE RECORD. No arm is read a
  second time and no estimator is re-run; a reader with the printed
  vectors can reproduce every key this builds with a pencil.

  Two callers, both of them quantities a reader was previously left to
  compute: the AUTHOR'S passenger-free share, which is the sum of two
  rungs that are individually uninterpretable, and the clean pairs'
  residual, which is a difference of two such sums."
  [f & terms]
  (let [vs (apply mapv
                  (fn [& xs] (rf.bench.hicasso.lane/round4 (apply f xs)))
                  (map :per-round terms))]
    (assoc (rf.bench.hicasso.lane/summarise vs) :per-round vs)))

(defn- ladder-rung
  "One rung: the ratio of two arms over the floor-normalised per-round
  ratios, and the same pair's raw per-round difference in nanoseconds per
  field. `:standing` records WHOSE code the rung prices, which is the
  whole question rf2-z143r was opened to answer."
  [ratios p50s a b standing]
  {:from      b
   :to        a
   :standing  standing
   :ratio     (rf.bench.hicasso.lane/ratio-between ratios a b)
   :ns-per-site (ns-terms p50s a b)})

(defn- derived-ns
  "A ladder entry with no arm pair behind it: `:ns-per-site` built by
  [[ns-combine]] from entries already in the record, and `:derived-from`
  naming them so the row can be checked rather than trusted."
  [standing from ns]
  {:standing standing :derived-from from :ns-per-site ns})

(defn- log-rung!
  "One printed ladder row: the ns/field summary, the ratio when the row
  has an arm pair behind it, and every per-round vector it carries. A
  derived row has no ratio — a ratio of a SUM of differences is not a
  ratio of anything — and prints without one rather than with a made-up
  one."
  [label {:keys [ratio ns-per-site]}]
  (js/console.log
    (str ";;   " label ": " (fmt (:p50 ns-per-site) 1) " ns/field ["
         (fmt (:min ns-per-site) 1) " - " (fmt (:max ns-per-site) 1) "]"
         (when ratio
           (str "  ratio " (fmt (:mean ratio) 4)
                " [" (fmt (:min ratio) 4) " - " (fmt (:max ratio) 4) "]"
                (when (:straddles-1? ratio) "  <- ratio STRADDLES 1.0")))))
  (js/console.log (str ";;     ns per-round:    " (pr-str (:per-round ns-per-site))))
  (when ratio
    (js/console.log (str ";;     ratio per-round: " (pr-str (:per-round ratio))))))

(defn- measurement-method []
  (str "a SAMPLE is " boundaries " boundaries mounted into a fresh container "
       "inside ONE react-dom/flushSync window, containers created and attached "
       "BEFORE the clock starts; the :ctl-2x arm's sample is the SAME operation "
       "performed TWICE inside one window, so its per-sample constants double "
       "with its work and its prediction is 2.00x by construction rather than by "
       "model — adjudicated by rf.bench.hicasso.lane/control-verdict-strict, which requires EVERY "
       "round's ctl-2x/expanded ratio to sit inside ±"
       (.toFixed (* 100.0 control-slack) 0)
       "% of it rather than merely the range, and records the per-round values so "
       "the run can be re-adjudicated without being re-run. :expanded-b is the "
       "NULL arm — a second boundary head over the same "
       "body, carrying no effect by construction, so its reading against "
       ":expanded is this instrument's resolution rather than a result. :helper "
       "and :no-dissoc are the DECOMPOSITION LADDER's two rungs (rf2-z143r), "
       "placed between :expanded and :merged so each step changes exactly one "
       "thing; they are judged by the fairness gate with the other three and are "
       "differenced, never published as a figure of their own. :helper-lean and "
       ":no-dissoc-lean are rf2-v5oto's CLEAN PAIRS — the same two author-side "
       "steps, each taken against a frozen arm, with the :class key that put "
       "rungs (1) and (3) on the far side of cljs.core/PersistentArrayMap's "
       "eight-entry HASHMAP-THRESHOLD from their partners removed, so no pair's "
       "two members differ in map REPRESENTATION on any field; they are "
       "differenced too and never published alone. Arms "
       "interleaved at the SAMPLE level under the lane's rotating AND REFLECTING "
       "schedule, so no arm always follows the same neighbour; " rounds
       " rounds of " (:warmup sampling) " warm-up + " (:samples sampling)
       " samples per arm per round; every measured mount read back out of the DOM "
       "against THAT ARM'S OWN element count and at its own FAR END; every ratio "
       "against the floor measured in THAT round. Nothing runs inside an `act` "
       "environment."))

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

(defn ^:export -main []
  (rf/init! rf.adapter.uix/adapter)
  (rf.bench.hicasso.lane/leave-act-environment!)
  (rf.bench.hicasso.lane/self-test!)
  (-> (js/Promise.resolve nil)
      (.then
        (fn [_]
          (rf/make-frame {:id frame-id
                          :initial-events [[:amp/seed (seed-db boundaries default-error)]]})
          (rf/with-frame frame-id
            (rf/dispatch-sync [:amp/seed (seed-db boundaries default-error)]))
          ;; THE FAIRNESS GATE, before a clock is read. Both halves, and
          ;; both fatal: the arms must agree, and the comparison must be
          ;; known able to disagree.
          (let [p         (parity!)
                can-fail? (parity-can-fail?)]
            (rf.bench.hicasso.lane/record! :amp-merge-clock-parity
                          {:agree?    (:agree? p)
                           :disagree  (:disagree p)
                           :counts    (:counts p)
                           :can-fail? can-fail?
                           :bytes     (rf.bench.hicasso.lane/utf8-bytes (or (:reference p) ""))})
            (when-not (:agree? p)
              (throw (ex-info (str "FAIRNESS GATE: the arms do not build the same "
                                   "mounted page, so no ratio between them is about the "
                                   "merge spelling. Disagreeing arms: "
                                   (pr-str (:disagree p)))
                              {:counts (:counts p)})))
            (when-not can-fail?
              (throw (ex-info (str "FAIRNESS GATE: the canonical-DOM comparison did NOT "
                                   "part two pages built from different data. An equality "
                                   "that cannot answer false is not a gate, and the "
                                   "agreement above is worth nothing.")
                              {}))))
          (rf.bench.hicasso.lane/assert-teardown-clean! "the fairness gate")
          (.then (rf.bench.hicasso.lane/settle!) (fn [_] nil))))
      (.then
        (fn [_]
          (let [baseline (rf.bench.hicasso.lane/residue frame-id)
                tally    (rf.bench.hicasso.lane/tally)
                {:keys [readings samples]}
                (rf.bench.hicasso.lane/rounds! arms sampling rounds (partial measure-one! tally))
                norm     (mapv #(rf.bench.hicasso.lane/normalise % :floor) readings)
                ratios   (mapv :ratio norm)
                summ     (rf.bench.hicasso.lane/across-rounds ratios)
                p50s     (mapv :p50 norm)
                abs      (into {}
                               (map (fn [{:keys [id]}]
                                      [id (rf.bench.hicasso.lane/summarise (mapv #(get % id) p50s))]))
                               arms)
                effect   (rf.bench.hicasso.lane/ratio-between ratios :merged :expanded)
                null     (rf.bench.hicasso.lane/ratio-between ratios :expanded-b :expanded)
                eff-ns   (ns-terms p50s :merged :expanded)
                null-ns  (ns-terms p50s :expanded-b :expanded)
                gv       (rf.bench.hicasso.lane/guard! samples "amp-merge clock arms (in-page ms)")
                ;; THE POSITIVE CONTROL, per round and strictly (rf2-egdaq).
                ;; `:ctl-2x` is `:expanded`'s own operation performed twice
                ;; in one window, so 2.00x is arithmetic rather than a
                ;; model, and dividing each round by ITS OWN `:expanded`
                ;; leaves the floor and the round's drift out of it.
                ctl-ratio (rf.bench.hicasso.lane/ratio-between ratios :ctl-2x :expanded)
                ctl      (rf.bench.hicasso.lane/control-verdict-strict
                           2.0 (:per-round ctl-ratio) control-slack)
                ;; THE APPORTIONMENT (rf2-z143r). A chain, so the three
                ;; rungs sum to `:whole` term by term and there is no
                ;; residual to report; `:standing` is what the reader
                ;; came for.
                rung     (fn [a b standing] (ladder-rung ratios p50s a b standing))
                whole    (rung :merged :expanded :author-and-codec)
                wrapper  (rung :helper :expanded :authors-code)
                merge-r  (rung :no-dissoc :helper :codecs-code)
                trip     (rung :merged :no-dissoc :authors-code)
                ;; THE CLEAN PAIRS (rf2-v5oto). Each is one author-side
                ;; step against a FROZEN arm, with the `:class` passenger
                ;; that put rungs (1) and (3) a map REPRESENTATION apart
                ;; taken out. They are two pairs and not a chain, so
                ;; their total is compared with the derived `:author`
                ;; sum rather than assumed equal to it.
                wrap-c   (rung :helper-lean :expanded :authors-code)
                trip-c   (rung :merged :no-dissoc-lean :authors-code)
                author   (ns-combine + (:ns-per-site wrapper) (:ns-per-site trip))
                author-c (ns-combine + (:ns-per-site wrap-c) (:ns-per-site trip-c))
                ladder   {:whole            whole
                          :wrapper          wrapper
                          :merge            merge-r
                          :round-trip       trip
                          ;; The quantity rungs (1) and (3) only imply:
                          ;; passenger-free by cancellation, and the one
                          ;; author-side figure the three-rung ladder is
                          ;; entitled to quote.
                          :author           (derived-ns :authors-code
                                                        [:wrapper :round-trip]
                                                        author)
                          :wrapper-clean    wrap-c
                          :round-trip-clean trip-c
                          :author-clean     (derived-ns :authors-code
                                                        [:wrapper-clean :round-trip-clean]
                                                        author-c)
                          ;; NOT ZERO BY CONSTRUCTION, unlike
                          ;; `:ladder-sum-residual` below. The flag is in
                          ;; the record so a reader who subtracts is told
                          ;; so by the run rather than by a docstring.
                          :split-residual   (assoc (derived-ns :authors-code
                                                               [:author :author-clean]
                                                               (ns-combine - author author-c))
                                                   :zero-by-construction? false)}
                sum-check (mapv (fn [w r m t] (rf.bench.hicasso.lane/round4 (- w (+ r m t))))
                                (:per-round (:ns-per-site whole))
                                (:per-round (:ns-per-site wrapper))
                                (:per-round (:ns-per-site merge-r))
                                (:per-round (:ns-per-site trip)))
                tv       (rf.bench.hicasso.lane/tally-value tally)]
            (rf.bench.hicasso.lane/assert-teardown-clean! "the measured rounds")
            (rf.bench.hicasso.lane/record! :amp-merge-clock
                          {:benchmark   :hicasso.HD-023/amp-merge-clock
                           :bead        "rf2-pqyxz"
                           :discharges  "HD-023 'Cost, stated' — the clock cost named there as unmeasured"
                           :grade       :distributional
                           :runtime     (rf.bench.hicasso.lane/runtime-label)
                           :boundaries  boundaries
                           :amp-sites   amp-sites
                           :elements    {:floor floor-elements :page page-elements}
                           :design      {:rounds rounds :sampling sampling}
                           :method      (measurement-method)
                           :absolute-ms abs
                           :ratio-to-floor summ
                           :effect      effect
                           :null        null
                           :per-element-ns {:effect eff-ns :null null-ns}
                           :control     ctl
                           :ladder      ladder
                           :ladder-sum-residual sum-check
                           :writes      tv})
            (js/console.log ";; ==== AMP-MERGE CLOCK (rf2-pqyxz) ====")
            (js/console.log (str ";;   " boundaries " boundaries/page, " page-elements
                                 " elements, " amp-sites " :& sites; " rounds
                                 " rounds x (" (:warmup sampling) "+" (:samples sampling) ")"))
            (doseq [{:keys [id]} arms]
              (let [a (get abs id)
                    r (get summ id)]
                (js/console.log
                  (str ";;   " (name id) ": " (fmt (:p50 a) 4) " ms/mount ["
                       (fmt (:min a) 4) " - " (fmt (:max a) 4) "]  ratio-to-floor "
                       (fmt (:mean r) 4) " [" (fmt (:min r) 4) " - " (fmt (:max r) 4) "]"))))
            (js/console.log
              (str ";;   EFFECT merged/expanded: " (fmt (:mean effect) 4)
                   "x [" (fmt (:min effect) 4) " - " (fmt (:max effect) 4) "]"
                   (if (:straddles-1? effect)
                     "  <- STRADDLES 1.0: INDISTINGUISHABLE"
                     "  <- range excludes 1.0")))
            (js/console.log
              (str ";;   NULL   expanded-b/expanded: " (fmt (:mean null) 4)
                   "x [" (fmt (:min null) 4) " - " (fmt (:max null) 4) "]"
                   (if (:straddles-1? null)
                     "  <- straddles 1.0, as a null must"
                     (str "  <- DOES NOT STRADDLE 1.0. Two arms doing identical work "
                          "read apart, so the effect row above is not attributable"))))
            (js/console.log (str ";;   effect per-round: " (pr-str (:per-round effect))))
            (js/console.log (str ";;   null   per-round: " (pr-str (:per-round null))))
            (js/console.log
              (str ";;   PER-ELEMENT effect: " (fmt (:p50 eff-ns) 1) " ns/:& site ["
                   (fmt (:min eff-ns) 1) " - " (fmt (:max eff-ns) 1) "]"))
            (js/console.log (str ";;     ns per-round: " (pr-str (:per-round eff-ns))))
            (js/console.log
              (str ";;   PER-ELEMENT null:   " (fmt (:p50 null-ns) 1) " ns/site ["
                   (fmt (:min null-ns) 1) " - " (fmt (:max null-ns) 1) "]"))
            (js/console.log (str ";;     ns per-round: " (pr-str (:per-round null-ns))))
            (js/console.log (str ";;   control (" (name (:rule ctl)) ", ctl-2x/expanded): "
                                 (:why ctl)))
            (js/console.log (str ";;   control per-round: " (pr-str (:per-round ctl))))
            ;; THE LADDER (rf2-z143r). Printed with every per-round value,
            ;; so the apportionment is re-adjudicable without a re-run.
            (js/console.log ";; ---- APPORTIONMENT (rf2-z143r) ----")
            (doseq [[k label] [[:whole      "WHOLE      merged/expanded    (author + codec)"]
                               [:wrapper    "(1) wrapper   helper/expanded    AUTHOR'S code"]
                               [:merge      "(2) merge     no-dissoc/helper   CODEC'S code"]
                               [:round-trip "(3) roundtrip merged/no-dissoc   AUTHOR'S code"]
                               ;; The derived key. (1) and (3) each carry the
                               ;; `:class` passenger with opposite signs, so
                               ;; only their sum is interpretable — and until
                               ;; now only a reader with a pencil had it.
                               [:author     "(1)+(3)  AUTHOR'S share, passenger-free (derived)"]]]
              (log-rung! label (get ladder k)))
            (js/console.log
              (str ";;   ladder sum residual (ns/field, ZERO BY CONSTRUCTION — the rungs "
                   "are a chain): " (pr-str sum-check)))
            ;; THE AUTHOR'S SHARE, SPLIT (rf2-v5oto). Two pairs, each one
            ;; step against a frozen arm, neither crossing the array-map
            ;; cliff on any field — so unlike (1) and (3) each is
            ;; separately interpretable.
            (js/console.log ";; ---- THE AUTHOR'S SHARE, SPLIT (rf2-v5oto) ----")
            (doseq [[k label] [[:wrapper-clean    "(1') wrapper   helper-lean/expanded    AUTHOR'S code"]
                               [:round-trip-clean "(3') roundtrip merged/no-dissoc-lean   AUTHOR'S code"]
                               [:author-clean     "(1')+(3') AUTHOR'S share, split total"]]]
              (log-rung! label (get ladder k)))
            (log-rung!
              (str "split residual (1)+(3) - (1')-(3') — NOT zero by construction; "
                   "the `:class` key prices differently on the spelled-keys path than "
                   "on the `:&` path, and the title's class rides the TAG in (1') but "
                   "`:&` in (3')")
              (:split-residual ladder))
            (js/console.log (str ";;   writes: " (:unverified tv) " unverified of "
                                 (:writes tv)))
            (when (pos? (:unverified tv))
              (throw (ex-info (str "READ-BACK: " (:unverified tv) " of " (:writes tv)
                                   " measured mounts did not match their own arithmetic")
                              tv)))
            (when (:refuse? gv)
              (set! (.-HICASSO_GUARD_REFUSED js/window) true))
            (when-not (:ok? ctl)
              (set! (.-HICASSO_CONTROL_FAILED js/window) true))
            (.then (rf.bench.hicasso.lane/settle!)
                   (fn [_]
                     (rf.bench.hicasso.lane/assert-residue! baseline frame-id "the measured rounds")
                     (rf.bench.hicasso.lane/done!))))))
      (.catch (fn [e]
                (rf.bench.hicasso.lane/fail! (or (some-> e .-message) (str e)))
                (rf.bench.hicasso.lane/done!)))))
