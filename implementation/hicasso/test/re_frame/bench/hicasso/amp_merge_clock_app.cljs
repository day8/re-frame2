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
    - RUNG (3) ALSO CARRIES THREE `:class nil` KEYS. One helper cannot
      omit a key for three fields out of four without an `assoc`, so
      `:helper` writes `:class` unconditionally and `:no-dissoc`'s call
      sites carry `:class nil` on the three fields that have no class —
      which is what keeps those two arms key-for-key identical. `:merged`
      is frozen and its call sites carry no such key, so `:no-dissoc`
      does slightly MORE work than `:merged` on those three fields and
      rung (3) is biased UP by that much.

  The two passengers were put where they do least harm on purpose. Rung
  (2) is the term this programme owns, and it is the one both are kept
  out of.

  ## The control is adjudicated STRICTLY, and per round (rf2-egdaq)

  `lane/control-verdict-strict` decides it: every round's `:ctl-2x` /
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

  `lane/ratio-between :merged :expanded` over the per-round
  floor-normalised ratios, with its range and its `:straddles-1?` flag —
  and beside it the same statistic for `:expanded-b`, which carries no
  effect by construction. A `:merged` range that straddles 1.0 means
  INDISTINGUISHABLE and the row must say so rather than quote the mean.

  Per element: the per-round difference of the two arms' raw p50 mount
  times, divided by the `:&` sites on the page, in nanoseconds — reported
  with its range and beside the null arm's, which is the same arithmetic
  over a difference known to be zero.

  Owner: rf2-pqyxz. Witness: `front/census_article_editor_cljs_test`'s
  ported RealWorld article-editor fieldset (HD-023, *Demonstrated, not
  asserted*), scaled to [[boundaries]] boundaries because a page of one
  cannot be timed. Instrument: `lane.cljs`, ridden through `run.cjs` on
  the existing `:hicasso-bench` build id via `HICASSO_INIT_FN` — no
  `shadow-cljs.edn` edit."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

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
;; controlled input's `value` is a DOM PROPERTY, and `lane/canonical`
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
  (let [draft  (h/sub [:amp/draft id])
        errors (h/sub [:amp/errors id])
        busy?  (:busy? draft)]
    [:fieldset
     [:fieldset.form-group
      [:input.form-control.form-control-lg
       {:type "text" :name "title" :placeholder "Article Title"
        :data-testid "editor-title"
        :value (:title draft) :disabled busy?
        :on-blur  [:amp/blur id :title]
        :on-input [:amp/edit id :title ::h/value]}]
      (when-some [e (:title errors)] [:div.error-messages e])]
     [:fieldset.form-group
      [:input.form-control
       {:type "text" :name "description" :placeholder "What's this article about?"
        :data-testid "editor-description"
        :value (:description draft) :disabled busy?
        :on-blur  [:amp/blur id :description]
        :on-input [:amp/edit id :description ::h/value]}]
      (when-some [e (:description errors)] [:div.error-messages e])]
     [:fieldset.form-group
      [:input.form-control
       {:type "text" :name "body" :placeholder "Write your article (in markdown)"
        :data-testid "editor-body"
        :value (:body draft) :disabled busy?
        :on-blur  [:amp/blur id :body]
        :on-input [:amp/edit id :body ::h/value]}]
      (when-some [e (:body errors)] [:div.error-messages e])]
     [:fieldset.form-group
      [:input.form-control
       {:type "text" :name "tags" :placeholder "Enter tags (comma-separated)"
        :data-testid "editor-tags"
        :value (:tagList draft) :disabled busy?
        :on-blur  [:amp/blur id :tagList]
        :on-input [:amp/edit id :tagList ::h/value]}]
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

(defn- field
  [draft errors id {:keys [k busy?] :as attrs}]
  [:fieldset.form-group
   [:input.form-control {:&        (dissoc attrs :k :busy?)
                         :value    (get draft k)
                         :disabled busy?
                         :on-blur  [:amp/blur id k]
                         :on-input [:amp/edit id k ::h/value]}]
   (when-some [e (get errors k)] [:div.error-messages e])])

(defn merged-body
  [{:keys [id]}]
  (let [draft  (h/sub [:amp/draft id])
        errors (h/sub [:amp/errors id])
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

(defn- field-explicit
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
                         :on-input    [:amp/edit id k ::h/value]}]
   (when-some [e (get errors k)] [:div.error-messages e])])

(defn- field-no-dissoc
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
                         :on-input [:amp/edit id k ::h/value]}]
   (when-some [e (get errors k)] [:div.error-messages e])])

(defn explicit-body
  [{:keys [id]}]
  (let [draft  (h/sub [:amp/draft id])
        errors (h/sub [:amp/errors id])
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
  (let [draft  (h/sub [:amp/draft id])
        errors (h/sub [:amp/errors id])
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

(defn floor-body
  "The crossing carrying nothing. A boundary is reached through a hiccup
  vector whatever its body answers, so the floor is the crossing and not
  zero."
  [_]
  nil)

(h/defview expanded-arm   [props] (expanded-body props))
(h/defview expanded-arm-b [props] (expanded-body props))
(h/defview merged-arm     [props] (merged-body props))
(h/defview explicit-arm   [props] (explicit-body props))
(h/defview no-dissoc-arm  [props] (no-dissoc-body props))
(h/defview floor-arm      [props] (floor-body props))

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
  (fn [container _props _n] (h/mount! container {:frame frame-id} (page view))))

(defn- unmount-page [handle] (h/unmount! handle))

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
    :mount (mount-page no-dissoc-arm) :unmount unmount-page}])

(defn- arm-named [id] (first (filter #(= id (:id %)) arms)))

(def ^:private sampling {:warmup 3 :samples 6})
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
  (and (= (:elements arm) (lane/element-count container))
       (or (= :floor (:id arm))
           (= (expected-far-end) (far-end container)))))

;; ---------------------------------------------------------------------------
;; The fairness gate
;; ---------------------------------------------------------------------------

(defn parity!
  "Mount every arm at once and compare the judged arms' canonical DOM.
  Answers `lane/parity`'s verdict with the mounts already released."
  []
  (let [{:keys [mounts] :as p} (lane/parity arms nil)]
    (doseq [m mounts] (lane/release! m))
    p))

(defn parity-can-fail?
  "THE MUTATION. Move the data under one arm and the gate must part the
  two pages; an equality that cannot answer false is not a gate. Reseeds
  to the run's own data afterwards, so nothing downstream is measured on
  the mutated page."
  []
  (let [before (let [m (lane/mount-arm! (arm-named :expanded) nil)
                     s (lane/canonical (:container m))]
                 (lane/release! m)
                 s)]
    (rf/with-frame frame-id
      (rf/dispatch-sync [:amp/seed (seed-db boundaries mutated-error)]))
    (let [after (let [m (lane/mount-arm! (arm-named :merged) nil)
                      s (lane/canonical (:container m))]
                  (lane/release! m)
                  s)]
      (rf/with-frame frame-id
        (rf/dispatch-sync [:amp/seed (seed-db boundaries default-error)]))
      (not= before after))))

;; ---------------------------------------------------------------------------
;; The measured window
;; ---------------------------------------------------------------------------

(defn- measure-one!
  [tally arm]
  (let [{:keys [ms mounts]} (lane/mount-batch! arm nil (:k arm))]
    (doseq [m mounts]
      (let [ok? (verified? arm (:container m))]
        (swap! tally (fn [{:keys [of bad]}]
                       {:of (inc of) :bad (if ok? bad (inc bad))}))))
    (doseq [m mounts] (lane/release! m))
    ms))

(defn- fmt [x n] (.toFixed (double x) n))

(defn- per-element-ns
  "The per-round difference of two arms' raw p50 mount times, in
  nanoseconds per `:&` site. Raw and not floor-normalised, deliberately:
  the two terms are measured in the SAME round, so an additive drift
  differences out, and dividing by the floor would rescale a difference by
  a quantity that has nothing to do with it."
  [p50s a b]
  (lane/summarise
    (mapv (fn [r] (/ (* 1e6 (- (get r a) (get r b))) amp-sites)) p50s)))

(defn- ns-terms
  "The same arithmetic as [[per-element-ns]], with the PER-ROUND vector
  kept beside the summary.

  It is kept because a ladder that prints only its summaries cannot be
  re-adjudicated without being re-run, and this instrument has already
  had one window re-taken for exactly that. `per-element-ns` is left
  alone so the two published rows keep the shape they were published in."
  [p50s a b]
  (let [vs (mapv (fn [r] (lane/round4 (/ (* 1e6 (- (get r a) (get r b))) amp-sites)))
                 p50s)]
    (assoc (lane/summarise vs) :per-round vs)))

(defn- ladder-rung
  "One rung: the ratio of two arms over the floor-normalised per-round
  ratios, and the same pair's raw per-round difference in nanoseconds per
  field. `:standing` records WHOSE code the rung prices, which is the
  whole question rf2-z143r was opened to answer."
  [ratios p50s a b standing]
  {:from      b
   :to        a
   :standing  standing
   :ratio     (lane/ratio-between ratios a b)
   :ns-per-site (ns-terms p50s a b)})

(defn- measurement-method []
  (str "a SAMPLE is " boundaries " boundaries mounted into a fresh container "
       "inside ONE react-dom/flushSync window, containers created and attached "
       "BEFORE the clock starts; the :ctl-2x arm's sample is the SAME operation "
       "performed TWICE inside one window, so its per-sample constants double "
       "with its work and its prediction is 2.00x by construction rather than by "
       "model — adjudicated by lane/control-verdict-strict, which requires EVERY "
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
       "differenced, never published as a figure of their own. Arms "
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
  (rf/init! uix-adapter/adapter)
  (lane/leave-act-environment!)
  (lane/self-test!)
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
            (lane/record! :amp-merge-clock-parity
                          {:agree?    (:agree? p)
                           :disagree  (:disagree p)
                           :counts    (:counts p)
                           :can-fail? can-fail?
                           :bytes     (lane/utf8-bytes (or (:reference p) ""))})
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
          (lane/assert-teardown-clean! "the fairness gate")
          (.then (lane/settle!) (fn [_] nil))))
      (.then
        (fn [_]
          (let [baseline (lane/residue frame-id)
                tally    (lane/tally)
                {:keys [readings samples]}
                (lane/rounds! arms sampling rounds (partial measure-one! tally))
                norm     (mapv #(lane/normalise % :floor) readings)
                ratios   (mapv :ratio norm)
                summ     (lane/across-rounds ratios)
                p50s     (mapv :p50 norm)
                abs      (into {}
                               (map (fn [{:keys [id]}]
                                      [id (lane/summarise (mapv #(get % id) p50s))]))
                               arms)
                effect   (lane/ratio-between ratios :merged :expanded)
                null     (lane/ratio-between ratios :expanded-b :expanded)
                eff-ns   (per-element-ns p50s :merged :expanded)
                null-ns  (per-element-ns p50s :expanded-b :expanded)
                gv       (lane/guard! samples "amp-merge clock arms (in-page ms)")
                ;; THE POSITIVE CONTROL, per round and strictly (rf2-egdaq).
                ;; `:ctl-2x` is `:expanded`'s own operation performed twice
                ;; in one window, so 2.00x is arithmetic rather than a
                ;; model, and dividing each round by ITS OWN `:expanded`
                ;; leaves the floor and the round's drift out of it.
                ctl-ratio (lane/ratio-between ratios :ctl-2x :expanded)
                ctl      (lane/control-verdict-strict
                           2.0 (:per-round ctl-ratio) control-slack)
                ;; THE APPORTIONMENT (rf2-z143r). A chain, so the three
                ;; rungs sum to `:whole` term by term and there is no
                ;; residual to report; `:standing` is what the reader
                ;; came for.
                ladder   {:whole      (ladder-rung ratios p50s :merged :expanded
                                                   :author-and-codec)
                          :wrapper    (ladder-rung ratios p50s :helper :expanded
                                                   :authors-code)
                          :merge      (ladder-rung ratios p50s :no-dissoc :helper
                                                   :codecs-code)
                          :round-trip (ladder-rung ratios p50s :merged :no-dissoc
                                                   :authors-code)}
                sum-check (mapv (fn [w r m t] (lane/round4 (- w (+ r m t))))
                                (:per-round (:ns-per-site (:whole ladder)))
                                (:per-round (:ns-per-site (:wrapper ladder)))
                                (:per-round (:ns-per-site (:merge ladder)))
                                (:per-round (:ns-per-site (:round-trip ladder))))
                tv       (lane/tally-value tally)]
            (lane/assert-teardown-clean! "the measured rounds")
            (lane/record! :amp-merge-clock
                          {:benchmark   :hicasso.HD-023/amp-merge-clock
                           :bead        "rf2-pqyxz"
                           :discharges  "HD-023 'Cost, stated' — the clock cost named there as unmeasured"
                           :grade       :distributional
                           :runtime     (lane/runtime-label)
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
            (js/console.log
              (str ";;   PER-ELEMENT null:   " (fmt (:p50 null-ns) 1) " ns/site ["
                   (fmt (:min null-ns) 1) " - " (fmt (:max null-ns) 1) "]"))
            (js/console.log (str ";;   control (" (name (:rule ctl)) ", ctl-2x/expanded): "
                                 (:why ctl)))
            (js/console.log (str ";;   control per-round: " (pr-str (:per-round ctl))))
            ;; THE LADDER (rf2-z143r). Printed with every per-round value,
            ;; so the apportionment is re-adjudicable without a re-run.
            (js/console.log ";; ---- APPORTIONMENT (rf2-z143r) ----")
            (doseq [[k label] [[:whole      "WHOLE      merged/expanded    (author + codec)"]
                               [:wrapper    "(1) wrapper   helper/expanded    AUTHOR'S code"]
                               [:merge      "(2) merge     no-dissoc/helper   CODEC'S code"]
                               [:round-trip "(3) roundtrip merged/no-dissoc   AUTHOR'S code"]]]
              (let [{:keys [ratio ns-per-site]} (get ladder k)]
                (js/console.log
                  (str ";;   " label ": " (fmt (:p50 ns-per-site) 1) " ns/field ["
                       (fmt (:min ns-per-site) 1) " - " (fmt (:max ns-per-site) 1)
                       "]  ratio " (fmt (:mean ratio) 4)
                       " [" (fmt (:min ratio) 4) " - " (fmt (:max ratio) 4) "]"
                       (when (:straddles-1? ratio) "  <- ratio STRADDLES 1.0")))
                (js/console.log (str ";;     ns per-round:    " (pr-str (:per-round ns-per-site))))
                (js/console.log (str ";;     ratio per-round: " (pr-str (:per-round ratio))))))
            (js/console.log
              (str ";;   ladder sum residual (ns/field, ZERO BY CONSTRUCTION — the rungs "
                   "are a chain): " (pr-str sum-check)))
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
            (.then (lane/settle!)
                   (fn [_]
                     (lane/assert-residue! baseline frame-id "the measured rounds")
                     (lane/done!))))))
      (.catch (fn [e]
                (lane/fail! (or (some-> e .-message) (str e)))
                (lane/done!)))))
