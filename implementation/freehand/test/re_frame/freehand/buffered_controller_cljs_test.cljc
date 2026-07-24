(ns re-frame.freehand.buffered-controller-cljs-test
  "FH-CTRL-006 … FH-CTRL-010 — the buffered controller: one control, one
  generation fence, one REQUIRED caller reset revision.

  FH-CTRL-001…005 proved WHERE a controller's record lives. These prove
  WHEN it is still the one the caller means — and the whole substrate
  contribution is two more pure functions beside `v/controller-key`:
  `v/controller-revision`, which takes the caller's `:reset-key` and
  refuses its absence, and `v/controller-current?`, the fence itself. There is
  still no controller DSL, no registry, and no public verb; the pilot
  below is a `v/defview` with four `reg-event`s and two `reg-sub`s.

  ## The case the whole design exists for

  The accepted amount is `\"10.00\"`. The user types `\"bad\"`. The caller
  refuses it and stands by `\"10.00\"`. The value before that decision and
  the value after it are IDENTICAL, so a control watching the value sees
  nothing happen and keeps the refused draft on screen — which is the
  defect every hand-rolled buffered input eventually acquires. FH-CTRL-007
  asserts both halves of that in one test: value-equality really is blind
  (the two baselines are `=`), and the generation is not.

  ## Why a SECOND pilot library

  `controllers-cljs-test` deliberately keeps its pilot minimal: it proves
  addressing, so its field has no generation and none of its laws needs
  one. Extending it would have made every one of those five laws carry a
  prop it does not use. This namespace registers its own library, under
  its own ids, and the two never share a registrar — the runtime fixture
  starts each test from a clean one.

  ## Why the render goes through a capture

  Same reason as FH-CTRL-001…005: `v/sub` is legal only during an active
  declared render, and the structural surface `t/render` walks a body but
  is not a host, so it opens no candidate. [[render-on!]] composes the
  two — one candidate, one walk, and NO commit. That is not a shortcut:
  an uncommitted candidate is precisely an ABANDONED render, so every
  assertion here is also a statement that rendering a buffered control
  publishes nothing, which FH-CTRL-009 then asserts directly."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [re-frame.freehand.compiler.check :as check])
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ===========================================================================
;; The pilot library — ordinary re-frame, owned by a component library
;; ===========================================================================
;;
;; `:my.ui/*` is LIBRARY vocabulary, not framework grammar (D017). The
;; substrate reserves no `:rf.field/*` namespace and fixes no storage
;; path: the root below, the record shape and every event id are the
;; library's choices.

(def kind
  "The buffered controller family.

  PUBLIC because the promoted twin in
  [[re-frame.freehand.buffered-views-compiled]] addresses the SAME family.
  A compiled control that restated its own kind would write its own
  records, and the parity suite would be comparing two independent
  controllers rather than one control in two modes."
  :my.ui/buffered-field)

(def ^:private disclosure-kind
  "A second family, which owns a protocol but holds no DRAFT. An open
  flag has nothing a caller can reject, so it asks for an address and
  never for a generation — which is what makes the generation a cost
  paid by buffered controls alone."
  :my.ui/disclosure)

(def records-root
  "Where the pilot library keeps its controller records. Public for the
  same reason [[kind]] is: the parity suite reads the ONE record both
  execution modes write."
  :my.ui/controllers)

(defn register-library!
  "The library's whole dataflow — two reads and four transitions — plus
  the caller-side events an application writes around it.

  Public so the parity suite starts from the same registrar these laws
  do. It knows nothing about execution mode, which is itself part of what
  promotion parity means: an interpreted control and its compiled twin
  reach these same registrations."
  []
  ;; --- Reads ---------------------------------------------------------

  (rf/reg-sub :my.ui/record
    (fn [db [_ record-key]] (get-in db [records-root record-key])))

  ;; What the control DISPLAYS. THE FENCE, read side: the draft while it
  ;; belongs to the caller's current generation, the caller's baseline
  ;; otherwise. A superseded draft is not erased here — it is invisible,
  ;; which is why a reset costs no write at all.
  (rf/reg-sub :my.ui.field/text
    (fn [db [_ record-key revision baseline]]
      (let [record (get-in db [records-root record-key])]
        (if (v/controller-current? (:reset-key record) revision)
          (:draft record)
          baseline))))

  ;; --- Transitions — named for what the user did ---------------------

  ;; ATOMIC create-or-replace, stamped with the generation the render
  ;; that produced this edit displayed. An edit that lands after the
  ;; caller has moved on is therefore BORN STALE rather than
  ;; authoritative.
  (rf/reg-event :my.ui.field/edited
    (fn [{:keys [db]} [_ record-key revision text]]
      {:db (assoc-in db [records-root record-key] {:reset-key revision :draft text})}))

  ;; THE FENCE, write side — the same predicate the display asks. One
  ;; semantic event with its own effects: retire the session AND produce
  ;; the caller's intent, so the commit and the domain event it causes
  ;; settle as one epoch.
  (rf/reg-event :my.ui.field/committed
    (fn [{:keys [db]} [_ record-key revision on-commit]]
      (let [record (get-in db [records-root record-key])]
        (if (v/controller-current? (:reset-key record) revision)
          {:db (update db records-root dissoc record-key)
           :fx [[:dispatch (conj (vec on-commit) (:draft record))]]}
          {}))))

  ;; Cancel clears the record, so a blur already on its way consults
  ;; committed state and finds no live edit. Escape BEATS a late blur
  ;; rather than racing it.
  (rf/reg-event :my.ui.field/cancelled
    (fn [{:keys [db]} [_ record-key revision]]
      (if (v/controller-current? (:reset-key (get-in db [records-root record-key])) revision)
        {:db (update db records-root dissoc record-key)}
        {})))

  (rf/reg-event :my.ui.disclosure/toggled
    (fn [{:keys [db]} [_ record-key]]
      {:db (update-in db [records-root record-key :open?] not)}))

  ;; --- The caller's side --------------------------------------------

  (rf/reg-sub :invoice/amount
    (fn [db [_ id]] (get-in db [:invoice id :amount])))

  (rf/reg-sub :invoice/amount-revision
    (fn [db [_ id]] (get-in db [:invoice id :amount-revision])))

  ;; The accepted draft's destination. The commit counter is the test's,
  ;; not the protocol's: it is how a repeated commit proves it dispatched
  ;; the caller's event ONCE rather than twice with the same value.
  (rf/reg-event :invoice/amount-committed
    (fn [{:keys [db]} [_ id amount]]
      {:db (-> db
               (assoc-in [:invoice id :amount] amount)
               (update ::commits (fnil inc 0)))}))

  ;; THE CALLER'S REJECTION. It asserts a baseline — which may be exactly
  ;; the one it already had — and advances the generation to say that
  ;; this is a NEW baseline decision.
  (rf/reg-event :invoice/baseline-replaced
    (fn [{:keys [db]} [_ id amount revision]]
      {:db (-> db
               (assoc-in [:invoice id :amount] amount)
               (assoc-in [:invoice id :amount-revision] revision))}))

  ;; An external update that is NOT a rejection: the value moves and the
  ;; generation does not, so a live draft continues.
  (rf/reg-event :invoice/amount-observed
    (fn [{:keys [db]} [_ id amount]]
      {:db (assoc-in db [:invoice id :amount] amount)})))

;; ===========================================================================
;; The pilot views
;; ===========================================================================

(v/defview buffered-field
  "The pilot control. It asks for its record key and its generation
  FIRST — which is what makes it a writable, buffered controller at all —
  then reads and emits through ordinary re-frame. Every intent it renders
  carries the generation the render that produced it displayed."
  [{:keys [value on-commit] :as props}]
  (let [k (v/controller-key kind props)
        g (v/controller-revision kind props)]
    [:span
     [:input {:value    (v/sub [:my.ui.field/text k g value])
              :on-input [:my.ui.field/edited k g ::v/value]
              :on-blur  [:my.ui.field/committed k g on-commit]}]
     [:button {:on-click [:my.ui.field/cancelled k g]} "Revert"]]))

(v/defview disclosure
  "An addressed controller that holds no draft. It never asks for a
  generation, so it has none to omit."
  [{:keys [label] :as props}]
  (let [k (v/controller-key disclosure-kind props)]
    [:button {:on-click [:my.ui.disclosure/toggled k]} label]))

(v/defview invoice-form
  "The caller. Both required props are ORDINARY FRAME DATA read the
  ordinary way — the amount the caller has accepted, and the revision it
  advances when it replaces that decision — so the whole exchange is
  visible in app-db without mounting anything."
  [{:keys [ids]}]
  [:form
   (for [id ids]
     [buffered-field {:key       id
                      :control   [:invoice id :amount]
                      :value     (v/sub [:invoice/amount id])
                      :reset-key (v/sub [:invoice/amount-revision id])
                      :on-commit [:invoice/amount-committed id]}])])

;; ===========================================================================
;; Seams
;; ===========================================================================

(def ^:private fid :rf/default)

(defn- seed!   [db] (frame/replace-app-db! fid db))
(defn- app-db  []   (frame/frame-app-db-value fid))
(defn- records []   (get (app-db) records-root))
(defn- record  [address] (get (records) [kind address]))
(defn- commits []   (get (app-db) ::commits 0))
(defn- send!   [ev] (rf/dispatch-sync ev {:frame fid}))

(defn- render-on!
  "Render `form` under `cell` — one candidate, one structural walk, and
  no commit — and answer the tree plus, per `:input` in document order,
  what that occurrence displays."
  [cell form]
  (let [cand (cell/candidate cell fid)
        tree (cell/with-capture cand (fn [] (t/render form)))
        ins  (t/find-all tree #(= :input (:tag %)))]
    {:tree    tree
     :inputs  ins
     :buttons (t/find-all tree #(= :button (:tag %)))
     :shown   (mapv #(:value (t/attrs %)) ins)}))

(defn- render! [form] (render-on! (cell/cell :my.ui/form) form))

(defn- shown [form] (:shown (render! form)))

(defn- intent
  "The event vector the occurrence `node` carries under `prop`."
  [node prop]
  (get (t/attrs node) prop))

(defn- send-intent!
  "Dispatch `event`, with `args` filling the projection positions a live
  payload would. With no args the intent is dispatched exactly as
  rendered."
  [event & args]
  (send! (if (seq args)
           (into (vec (butlast event)) args)
           event)))

(defn- fire!
  "Dispatch the intent occurrence `node` carries under `prop`, now."
  [node prop & args]
  (apply send-intent! (intent node prop) args))

(defn- moved-paths
  "The leaf paths at which two frame values differ, in a stable order —
  what an epoch diff shows a tool. Used to assert that a rejection moves
  exactly ONE location."
  ([a b] (moved-paths a b []))
  ([a b path]
   (cond
     (= a b)                  []
     (and (map? a) (map? b))  (into []
                                    (mapcat #(moved-paths (get a % ::absent)
                                                          (get b % ::absent)
                                                          (conj path %)))
                                    (sort-by str (distinct (concat (keys a) (keys b)))))
     :else                    [path])))

(defn- seed-invoices!
  "Seed `ids` with a baseline and a generation apiece — the caller state
  the form reads."
  [ids baselines revision]
  (seed! {:invoice (into {} (map (fn [id b] [id {:amount b :amount-revision revision}])
                                 ids baselines))}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :init-fn register-library!}))

;; ===========================================================================
;; FH-CTRL-006 — the reset generation is REQUIRED
;; ===========================================================================

(def ctrl-006 (conf/fixture :FH-CTRL-006))

(deftest fh-ctrl-006-a-buffered-controller-needs-a-reset-generation
  (testing "Per FH-CTRL-006: a buffered controller rendered with no
            :reset-key is refused loudly. Optional would be the worse
            design and not the friendlier one — a control with no
            generation buffers correctly right up to the first caller
            rejection and then loses it, which is a defect that reaches
            production because development never rejects anything."
    (let [{fixture-kind :kind
           :keys        [missing-revision-error-id recovery extra-keys message-mentions
                         control value reset-key stable-literal unbuffered-renders?
                         nil-revision-error-id nil-recovery nil-message-mentions
                         falsey-reset-key]} ctrl-006
          thunk #(t/render [buffered-field {:control control :value value}])]
      (seed! {})
      (is (= missing-revision-error-id (conf/caught-id thunk))
          "a stable id, not a silently unfenced control")
      (is (some? missing-revision-error-id)
          "non-vacuous: the law asserts an actual diagnostic id")

      (testing "carrying what locates the site: which controller family
                refused, and a bounded SHAPE summary of the props —
                exactly the slots the ADDRESS refusal carries, so a
                controller's two required props fail in one shape"
        (let [data (try (thunk) nil
                        (catch #?(:clj Throwable :cljs :default) e (ex-data e)))]
          (is (= fixture-kind (:kind data)) "the offending controller kind")
          (is (= recovery (:recovery data)))
          (is (= (set extra-keys) (set (keys (dissoc data :rf.error/id :where))))
              "exactly the catalogued ex-data slots")
          (is (map? (:props data)) "the props ride as a shape summary")
          (is (not (contains? (:props data) :value))
              "a SUMMARY, never the props themselves")))

      (testing "the message names the prop, why the value cannot serve as
                the signal, and the literal escape"
        (let [message (conf/caught-message thunk)]
          (doseq [fragment message-mentions]
            (is (str/includes? message fragment)
                (str "the message mentions " (pr-str fragment))))))

      (testing "an EXPLICIT nil revision is refused too — and refused as
                a DIFFERENT mistake. The prop is there, so nothing was
                forgotten at the call site; an uninitialised counter read
                before its baseline exists is the ordinary source, and
                the fence would read every draft under a nil generation
                as superseded while the control went on accepting
                keystrokes."
        (let [nil-thunk #(t/render [buffered-field {:control   control
                                                    :value     value
                                                    :reset-key nil}])
              data      (try (nil-thunk) nil
                             (catch #?(:clj Throwable :cljs :default) e (ex-data e)))]
          (is (= nil-revision-error-id (conf/caught-id nil-thunk))
              "its own catalogued id")
          (is (not= missing-revision-error-id nil-revision-error-id)
              "non-vacuous: the fixture's two ids really are different")
          (is (= nil-recovery (:recovery data))
              "and its own recovery, pointing at the uninitialised counter")
          (is (= fixture-kind (:kind data)) "still naming the controller family")
          (is (not= (conf/caught-message thunk) (conf/caught-message nil-thunk))
              "and a different sentence — an id nobody reads is not a distinction")
          (let [message (conf/caught-message nil-thunk)]
            (doseq [fragment nil-message-mentions]
              (is (str/includes? message fragment)
                  (str "the nil message mentions " (pr-str fragment)))))))

      (testing "PRESENCE decides, never truthiness: a falsey revision is
                an ordinary revision, because the fence only ever asks
                whether two of them are equal"
        (is (= [value] (shown [buffered-field {:control   control
                                               :value     value
                                               :reset-key falsey-reset-key}])))
        (is (= conf/no-throw
               (conf/caught-id #(shown [buffered-field {:control   control
                                                        :value     value
                                                        :reset-key falsey-reset-key}])))
            "a falsey revision raises nothing"))

      (testing "the control for the refusal: the SAME view, given both
                required props, renders — so the rejection above cannot
                be green because nothing renders at all"
        (is (= [value] (shown [buffered-field {:control   control
                                               :value     value
                                               :reset-key reset-key}]))))

      (testing "and a stable literal is a legitimate generation. It is
                how a caller says 'do not externally reset an active
                edit' — a statement rather than a silence"
        (is (= [value] (shown [buffered-field {:control   control
                                               :value     value
                                               :reset-key stable-literal}])))
        (is (= conf/no-throw
               (conf/caught-id #(shown [buffered-field {:control   control
                                                        :value     value
                                                        :reset-key stable-literal}])))
            "a literal reset key raises nothing"))

      (testing "while an addressed controller that holds no DRAFT never
                asks for a generation, so the cost is paid by buffered
                controls alone"
        (let [tree (:tree (render! [disclosure {:control control :label "Why?"}]))]
          (is (= unbuffered-renders? (some? (t/find tree #(= :button (:tag %)))))))))))

;; ===========================================================================
;; FH-CTRL-007 — same-value rejection: the case equality cannot see
;; ===========================================================================

(def ctrl-007 (conf/fixture :FH-CTRL-007))

(deftest fh-ctrl-007-a-rejection-lands-even-when-the-value-is-unchanged
  (testing "Per FH-CTRL-007: the caller rejects a live draft by
            advancing the generation, and the rejection lands even when
            the value it reasserts is EQUAL to the one it had. This is
            the exact case value-equality is blind to, and it is the
            entire reason the generation is a caller revision rather than
            the model value."
    (let [{:keys [control baseline revision typed displayed-while-editing
                  record-while-editing reasserted-value next-revision
                  displayed-after-rejection stale-record-after-rejection
                  changed-value displayed-after-external-change]} ctrl-007
          id   (second control)
          form [invoice-form {:ids [id]}]]
      (seed-invoices! [id] [baseline] revision)
      (is (= [baseline] (shown form)) "the control starts on the caller's baseline")

      ;; The user drafts. The record is stamped with the generation the
      ;; render that produced this edit displayed.
      (fire! (first (:inputs (render! form))) :on-input typed)
      (is (= [displayed-while-editing] (shown form)) "the draft is displayed")
      (is (= record-while-editing (record control))
          "and it carries the generation it was made under")

      ;; THE CASE. The caller refuses the draft and stands by the value
      ;; it already had, advancing the generation.
      (let [before (:amount (get-in (app-db) [:invoice id]))]
        (send! [:invoice/baseline-replaced id reasserted-value next-revision])
        (is (= before reasserted-value)
            "non-vacuous: the caller really did reassert an EQUAL value")
        (is (= before (:amount (get-in (app-db) [:invoice id])))
            "so nothing derived from the value could have observed the rejection"))

      (testing "the draft leaves the display at once"
        (is (= [displayed-after-rejection] (shown form)))
        (is (not= displayed-while-editing displayed-after-rejection)
            "non-vacuous: the display actually moved off the draft"))

      (testing "and the superseded record is INELIGIBLE, not erased —
                keeping the stamp is what lets a tool name the generation
                that orphaned it"
        (is (= stale-record-after-rejection (record control)))
        (is (false? (v/controller-current? (:reset-key (record control)) next-revision))
            "the fence, asked directly"))

      (testing "the counter-case: an external update that moves the VALUE
                and holds the generation is not a rejection, so a live
                draft continues"
        (seed-invoices! [id] [baseline] revision)
        (fire! (first (:inputs (render! form))) :on-input typed)
        (send! [:invoice/amount-observed id changed-value])
        (is (= [displayed-after-external-change] (shown form)))
        (is (not= changed-value (first (shown form)))
            "non-vacuous: the caller's new value is NOT what is displayed")))))

#?(:clj
   (deftest fh-ctrl-007-the-controller-shape-is-not-interpreted-only
     (testing "Per FH-CTRL-006…010, applicability `common`: the laws bind
               BOTH execution modes, and almost nothing about the fence
               could differ between them — `v/controller-revision` and
               `v/controller-current?` are ordinary functions, the second of
               them called from a `reg-sub` that no view mode ever sees,
               and the record is ordinary frame data. What promotion
               COULD move is the SHAPE the pilot renders, so what is
               asserted is that the compiled grammar ACCEPTS that shape
               unchanged: the control below is checked, as it stands, by
               the compiled tier's own analyzer, and is eligible with
               nothing to say. Pointed at THIS file, so the declaration
               checked is the one every law above renders — there is no
               copy to drift.

               JVM-only because the checker resolves heads against a
               loaded namespace, which only the JVM has."
       (let [path    (.getPath (io/file (io/resource
                                          "re_frame/freehand/buffered_controller_cljs_test.cljc")))
             reports (check/check-file path)
             report  (first (filter #(= ::buffered-field (:view-id %)) reports))]
         (is (<= 3 (count reports))
             "non-vacuous: the checker really read this file's declarations")
         (is (some? report) "and found the pilot control among them")
         (is (true? (:compile-eligible? report))
             "the buffered controller's shape is inside the compiled grammar")
         (is (= [] (:findings report))
             "with nothing to change on the way — promotion is a keyword, not a rewrite")
         (is (= :interpreted (:current-lowering report))
             "checked as it stands, before any promotion")))))

;; ===========================================================================
;; FH-CTRL-008 — the fence: superseded work lands in neither direction
;; ===========================================================================

(def ctrl-008 (conf/fixture :FH-CTRL-008))

(deftest fh-ctrl-008-superseded-work-does-not-land
  (testing "Per FH-CTRL-008: every stale-work case is decided in the
            handler against COMMITTED state — never by a guard captured
            during render — so a superseded commit produces nothing, a
            superseded edit is born stale, a cancel beats a late blur,
            and a repeated commit is an idempotent no-op."
    (let [{:keys [control baseline revision next-revision amount-path]} ctrl-008
          id      (second control)
          form    [invoice-form {:ids [id]}]
          amount  (fn [] (get-in (app-db) amount-path))
          reject! (fn [] (send! [:invoice/baseline-replaced id baseline next-revision]))
          start!  (fn [] (seed-invoices! [id] [baseline] revision))]

      (testing "the CONTROL — an ordinary current commit, so nothing
                below is green for want of a working commit at all"
        (let [{:keys [typed amount-after caller-events record-after]} (:ordinary ctrl-008)]
          (start!)
          (fire! (first (:inputs (render! form))) :on-input typed)
          (fire! (first (:inputs (render! form))) :on-blur)
          (is (= amount-after (amount)) "the caller's domain event received the draft")
          (is (not= baseline amount-after)
              "non-vacuous: an accepted commit really does move the caller's amount")
          (is (= record-after (record control)) "and the session was retired")
          (is (= caller-events (commits)) "exactly one caller event")))

      (testing "a commit whose generation the caller has replaced. The
                intent an event site fires always carries the LAST
                COMMITTED render's generation — a candidate the host
                never selected published no event bodies at all — so once
                the reset has rendered, the blur speaks for the new
                generation while the record still speaks for the old"
        (let [{:keys [typed amount-after caller-events record-after displayed-after]}
              (:superseded-commit ctrl-008)]
          (start!)
          (fire! (first (:inputs (render! form))) :on-input typed)
          (reject!)
          (let [live (first (:inputs (render! form)))]
            (is (= next-revision (nth (intent live :on-blur) 2))
                "non-vacuous: the re-rendered intent really carries the NEW generation")
            (fire! live :on-blur))
          (is (= amount-after (amount)) "the caller's amount is exactly as it was")
          (is (not= typed amount-after)
              "non-vacuous: the draft that was fenced differs from the amount")
          (is (= caller-events (commits)) "and the caller's event never fired")
          (is (= record-after (record control)) "the record is untouched")
          (is (= [displayed-after] (shown form)))))

      (testing "an edit made under a generation the caller has already
                replaced. It is materialized from the render at the old
                generation and lands after the reset, so it stamps the
                record with a generation nobody is displaying — BORN
                STALE, never shown and not committable"
        (let [{:keys [typed record-after displayed-after amount-after caller-events]}
              (:superseded-edit ctrl-008)]
          (start!)
          (let [stale (intent (first (:inputs (render! form))) :on-input)]
            (is (= revision (nth stale 2))
                "non-vacuous: the captured intent speaks for the OLD generation")
            (reject!)
            (apply send-intent! stale [typed]))
          (is (= record-after (record control)) "the record was written, and stamped stale")
          (is (= [displayed-after] (shown form)) "and it is not displayed")
          (fire! (first (:inputs (render! form))) :on-blur)
          (is (= amount-after (amount)) "nor can it be committed at the new generation")
          (is (= caller-events (commits)))))

      (testing "cancel clears the record before the blur behind it can
                resurrect the draft"
        (let [{:keys [typed record-after amount-after caller-events displayed-after]}
              (:cancel ctrl-008)]
          (start!)
          (fire! (first (:inputs (render! form))) :on-input typed)
          (let [{:keys [inputs buttons]} (render! form)
                late-blur (intent (first inputs) :on-blur)]
            (is (some? late-blur) "non-vacuous: a blur really was in flight")
            (fire! (first buttons) :on-click)
            (is (= record-after (record control)) "cancel retired the session")
            (send-intent! late-blur))
          (is (= amount-after (amount)) "and the late blur found no live edit")
          (is (= caller-events (commits)))
          (is (= [displayed-after] (shown form)))))

      (testing "a repeated commit is idempotent: the second finds no
                record, so a double blur does not dispatch the caller's
                event twice"
        (let [{:keys [typed amount-after caller-events] fires :commits}
              (:repeated ctrl-008)]
          (start!)
          (fire! (first (:inputs (render! form))) :on-input typed)
          (let [blur (intent (first (:inputs (render! form))) :on-blur)]
            (dotimes [_ fires] (send-intent! blur)))
          (is (< 1 fires) "non-vacuous: the intent really was fired more than once")
          (is (= amount-after (amount)))
          (is (= caller-events (commits))
              "the caller's event fired exactly once"))))))

;; ===========================================================================
;; FH-CTRL-009 — the reset target is CURRENT, and reaching it is free
;; ===========================================================================

(def ctrl-009 (conf/fixture :FH-CTRL-009))

(deftest fh-ctrl-009-the-reset-target-is-the-callers-current-value
  (testing "Per FH-CTRL-009: the control holds no snapshot to restore. It
            simply stops displaying the draft, and what is left is the
            :value expression evaluated by THIS render — so a caller that
            rejects by transforming rather than by refusing gets the
            transformed value with no extra machinery."
    (let [{:keys [control baseline revision typed reset-baseline next-revision
                  displayed-after-reset]} ctrl-009
          id   (second control)
          form [invoice-form {:ids [id]}]]
      (seed-invoices! [id] [baseline] revision)
      (fire! (first (:inputs (render! form))) :on-input typed)
      (is (= [typed] (shown form)) "the draft is live")

      (send! [:invoice/baseline-replaced id reset-baseline next-revision])
      (is (= [displayed-after-reset] (shown form))
          "the control shows what the caller asserts NOW")
      (is (not= baseline displayed-after-reset)
          "non-vacuous: a mount-time snapshot would have shown the ORIGINAL baseline"))))

(deftest fh-ctrl-009-rendering-a-buffered-control-changes-nothing
  (testing "Per FH-CTRL-009: the read is a pure function of (record,
            generation, baseline), so repeating it is free of
            consequence. That is what makes a concurrent restart, a
            StrictMode double-invoke and an abandoned candidate all
            uneventful rather than each needing its own rule — there is
            no render-time dispatch and no render-phase mutation to
            abandon."
    (let [{:keys [control baseline revision typed renders]} ctrl-009
          id   (second control)
          form [invoice-form {:ids [id]}]]
      (seed-invoices! [id] [baseline] revision)
      (fire! (first (:inputs (render! form))) :on-input typed)

      (let [before  (app-db)
            results (vec (repeatedly renders #(render! form)))]
        (is (< 1 renders) "non-vacuous: the render really was repeated")
        (is (= before (app-db))
            "the whole frame is byte-identical — rendering dispatched nothing")
        (is (apply = (map :shown results)) "and every pass displayed the same thing")
        (is (= [typed] (:shown (first results)))
            "non-vacuous: what they agreed on is the live draft, not nil"))

      (testing "and each of those renders was an ABANDONED candidate —
                never committed — which is exactly the shape a host
                restart leaves behind"
        (let [c (cell/cell :my.ui/form)]
          (render-on! c form)
          (render-on! c form)
          (is (= :new (cell/lifecycle c)) "the cell never connected")
          (is (empty? (cell/dependencies c)) "and owns no dependency")
          (is (= [typed] (:shown (render-on! c form)))
              "yet the control still displays the draft, because the draft is frame data"))))))

(deftest fh-ctrl-009-a-hot-reload-leaves-the-draft-alone
  (testing "Per FH-CTRL-009: hot reload advances the cell's BODY
            generation. A buffered draft lives in frame data with no host
            slot to index, so there is no slot order to drift and the
            record and its stamp are untouched — the two generations are
            unrelated, and that is the point of not holding the draft in
            the host."
    (let [{:keys [control baseline revision typed hot-reload-generation
                  record-after-hot-reload displayed-after-hot-reload]} ctrl-009
          id   (second control)
          form [invoice-form {:ids [id]}]
          c    (cell/cell :my.ui/form)]
      (seed-invoices! [id] [baseline] revision)
      (fire! (first (:inputs (render-on! c form))) :on-input typed)
      (is (= 0 (cell/generation c)) "the cell starts at body generation zero")

      (cell/advance-generation! c hot-reload-generation)
      (is (= hot-reload-generation (cell/generation c))
          "non-vacuous: the hot-reload seam really advanced the body generation")
      (is (= record-after-hot-reload (record control)) "the record is untouched")
      (is (= [displayed-after-hot-reload] (:shown (render-on! c form)))
          "and the reloaded body still displays the draft"))))

;; ===========================================================================
;; FH-CTRL-010 — many controls, one pass; and the evidence
;; ===========================================================================

(def ctrl-010 (conf/fixture :FH-CTRL-010))

(defn- names-a-reset-channel?
  "True when `s` reads as a controller reset / discard surface under the
  fixture's roster. Substring matching, so a differently-spelled sibling
  is caught by the same roster rather than needing its own entry."
  [spellings s]
  (boolean (some #(str/includes? (str/lower-case (str s)) %) spellings)))

(deftest fh-ctrl-010-many-controls-reset-independently-in-one-pass
  (testing "Per FH-CTRL-010: each control reads its own (address,
            generation) pair, so resets compose without ordering. One
            pass settles every control, in any combination, and repeating
            the pass changes nothing."
    (let [{:keys [invoice-ids baselines revision drafts
                  displayed-while-editing next-revision reset-one
                  displayed-after-one-reset reset-rest displayed-after-all-resets
                  passes]} ctrl-010
          form [invoice-form {:ids invoice-ids}]
          reset-at! (fn [i] (send! [:invoice/baseline-replaced (nth invoice-ids i)
                                    (nth baselines i) next-revision]))]
      (seed-invoices! invoice-ids baselines revision)
      (is (= baselines (shown form)) "all three start on their baselines")

      (doseq [[input text] (map vector (:inputs (render! form)) drafts)]
        (fire! input :on-input text))
      (is (= displayed-while-editing (shown form)) "all three are drafting")
      (is (= 3 (count (records))) "non-vacuous: three independent records")

      (testing "one control's generation advances; the other two keep
                drafting, in the SAME pass"
        (reset-at! reset-one)
        (is (= displayed-after-one-reset (shown form)))
        (is (apply not= displayed-after-one-reset)
            "non-vacuous: the pass produced a DIFFERENCE, not three equal values"))

      (testing "the rest follow, together"
        (doseq [i reset-rest] (reset-at! i))
        (is (= displayed-after-all-resets (shown form))))

      (testing "and the pass is deterministic"
        (is (apply = (repeatedly passes #(shown form)))
            "repeating it displays the same thing")
        (is (< 1 passes) "non-vacuous: it really was repeated")))))

(deftest fh-ctrl-010-the-discard-is-attributable-from-ordinary-data
  (testing "Per FH-CTRL-010: a reset is the caller's own event moving the
            caller's own data. The substrate publishes no reset channel,
            emits no discard event, and deletes nothing to tidy up — so
            an ordinary app-db diff across the caller's event names
            precisely which generation was superseded and which draft it
            orphaned."
    (let [{:keys [invoice-ids controls baselines revision drafts next-revision
                  reset-event-id reset-channel-spellings]} ctrl-010
          {:keys [invoice-id reasserted-value stale-record] expected-moved :moved-paths}
          (:evidence ctrl-010)
          address (nth controls (first (keep-indexed (fn [i id] (when (= id invoice-id) i))
                                                     invoice-ids)))
          form    [invoice-form {:ids invoice-ids}]]
      (seed-invoices! invoice-ids baselines revision)
      (doseq [[input text] (map vector (:inputs (render! form)) drafts)]
        (fire! input :on-input text))

      (let [before (app-db)]
        (send! [reset-event-id invoice-id reasserted-value next-revision])
        (let [after (app-db)
              moved (moved-paths before after)]
          (is (= expected-moved moved)
              "exactly one location moved — the caller's generation")
          (is (seq moved) "non-vacuous: something really did move")
          (is (= stale-record (get-in after [records-root [kind address]]))
              "and no record was deleted to hide the orphaning")))

      (testing "the reset is an ordinary registered event of the
                CALLER's, not a substrate call"
        (is (= "invoice" (namespace reset-event-id))
            "it lives in the application's own namespace"))

      #?(:clj
         (testing "and no published door var is a reset / discard
                   channel. JVM-only because ns-publics is a JVM
                   reflection API; the ClojureScript half of the same
                   surface is reconciled by the api-manifest probe."
           (let [published (mapv first (ns-publics 're-frame.freehand))]
             (is (seq published) "non-vacuous: the door publishes vars to examine")
             (is (empty? (filter #(names-a-reset-channel? reset-channel-spellings %)
                                 published))
                 "no published var resets, discards, reverts or invalidates a draft")
             (is (seq (filter #(names-a-reset-channel? reset-channel-spellings %)
                              (conj published 'reset-draft!)))
                 "and the probe can SEE such a name when one is present")))))))
