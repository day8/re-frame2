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
  the divisor of every per-element figure this file prints."
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

(defn floor-body
  "The crossing carrying nothing. A boundary is reached through a hiccup
  vector whatever its body answers, so the floor is the crossing and not
  zero."
  [_]
  nil)

(h/defview expanded-arm   [props] (expanded-body props))
(h/defview expanded-arm-b [props] (expanded-body props))
(h/defview merged-arm     [props] (merged-body props))
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
    :mount (mount-page expanded-arm) :unmount unmount-page}])

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

(defn- measurement-method []
  (str "a SAMPLE is " boundaries " boundaries mounted into a fresh container "
       "inside ONE react-dom/flushSync window, containers created and attached "
       "BEFORE the clock starts; the :ctl-2x arm's sample is the SAME operation "
       "performed TWICE inside one window, so its per-sample constants double "
       "with its work and its prediction is 2.00x by construction rather than by "
       "model. :expanded-b is the NULL arm — a second boundary head over the same "
       "body, carrying no effect by construction, so its reading against "
       ":expanded is this instrument's resolution rather than a result. Arms "
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
                ctl      (lane/control-verdict
                           (* 2.0 (:p50 (get abs :expanded)))
                           (let [s (get abs :ctl-2x)]
                             {:min (:min s) :max (:max s) :mean (:p50 s)})
                           control-slack)
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
            (js/console.log (str ";;   control: " (:why ctl)))
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
