(ns re-frame.freehand.controller-surface-cljs-test
  "THE DOOR-ONLY CLAIM — a component library can build the writable,
  buffered controller Spec 004 §Semantic controllers designs without
  reaching into a single substrate-internal namespace.

  That claim is what publishing `v/controller-key`,
  `v/controller-revision` and `v/controller-current?` is FOR, so it is
  asserted rather than asserted-in-prose, and in two halves.

  ## The structural half

  [[door-only?]] reads a namespace's own `ns` form off the classpath and
  answers whether every `re-frame.freehand.*` namespace it requires is
  one of the two sanctioned public ones (`re-frame.freehand` and its test
  sibling `re-frame.freehand.test`, per Conventions §Freehand — one
  public namespace). It is asked of THIS namespace, and of
  `re-frame.freehand.pilot-field` — the shipped exemplar component
  library, which builds exactly the props-only `field` and buffered
  `buffered-field` pair a real library ships. Before the three verbs
  crossed the door both answered NO: the mechanism a controller cannot
  exist without lived behind `^:no-doc`, so consumer code had to require
  it directly and no public-API gate watched the signature it depended
  on.

  JVM-only, because reading a source resource is a JVM capability; the
  ClojureScript half of the same surface is reconciled by the
  api-manifest probe, which sees the three verbs on the door and reddens
  on a rename either way.

  ## The behavioural half

  Everything below the seams builds a working buffered controller — the
  record, the generation, the read fence, the write fence, both
  refusals — out of `re-frame.core` and `re-frame.freehand` and nothing
  else. It is deliberately a SECOND library rather than a reuse of the
  pilot's: a surface that only its own author can drive is not a
  surface.

  One honest note on shape. The control below takes its displayed `:text`
  as an ordinary prop and the caller resolves it through the library's
  registered subscription, rather than reading it with `v/sub` from
  inside the body. That is a test-harness accommodation and not a
  controller one: a `v/sub` in a HEADLESS structural render needs a host
  shell to open a candidate for the read to belong to, and the shell is
  internal test machinery (`buffered-controller-cljs-test` exercises that
  path). The fence itself is unaffected — it is asked in the registered
  subscription and in the registered event, which is exactly where Spec
  004 §The generation fence puts it.

  Per [Spec 004 §Semantic controllers](../../../../spec/004-Views.md#semantic-controllers)
  and §The published surface — three verbs, and no more."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            #?(:clj [clojure.java.io :as io])
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support])
  #?(:clj (:import [java.io PushbackReader])))

;; ===========================================================================
;; The structural half — what a namespace is allowed to require
;; ===========================================================================

(def sanctioned-freehand-namespaces
  "The Freehand namespaces consumer code MAY require. Conventions
  §Freehand — one public namespace fixes the door and its test sibling as
  the only sanctioned public spellings; everything else under
  `re-frame.freehand.*` is substrate internals."
  '#{re-frame.freehand re-frame.freehand.test})

#?(:clj
   (defn- required-namespaces
     "Every namespace symbol the `ns` form at `resource-path` requires.

      Read off the SOURCE rather than off `ns-aliases`, because an
      unaliased `[re-frame.freehand.reactive]` require is a reach into
      internals exactly as an aliased one is and carries no alias to
      find. `:read-cond :allow` so a `.cljc` ns form carrying reader
      conditionals parses."
     [resource-path]
     (with-open [r (PushbackReader. (io/reader (io/resource resource-path)))]
       (let [form    (read {:read-cond :allow :eof nil} r)
             clauses (->> form
                          (filter (every-pred seq? #(= :require (first %))))
                          (mapcat rest))]
         (into #{}
               (keep (fn [clause]
                       (cond
                         (symbol? clause)     clause
                         (sequential? clause) (first clause))))
               clauses)))))

#?(:clj
   (defn- door-only?
     "True when no namespace in `required` is a Freehand internal."
     [required]
     (empty? (remove sanctioned-freehand-namespaces
                     (filter #(re-find #"^re-frame\.freehand(\.|$)" (str %))
                             required)))))

#?(:clj
   (deftest a-component-library-reaches-only-the-door
     (testing "A component library builds a writable buffered controller
               out of the PUBLIC door alone. Before the three verbs
               crossed it, the mechanism lived behind `^:no-doc` and
               consumer code had to require an internal namespace to
               exist at all."
       (let [this    (required-namespaces "re_frame/freehand/controller_surface_cljs_test.cljc")
             library (required-namespaces "re_frame/freehand/pilot_field.cljc")]

         (testing "non-vacuous: the reader really found both ns forms"
           (is (contains? this 're-frame.freehand)
               "this suite requires the door")
           (is (contains? library 're-frame.freehand)
               "the exemplar library requires the door")
           (is (contains? library 're-frame.core)
               "and ordinary re-frame, which is the whole of its dataflow"))

         (testing "and the probe can SEE an internal reach when one is
                   present — otherwise the two verdicts below would be
                   green for a predicate that never says no"
           (is (false? (door-only? (conj library 're-frame.freehand.control))))
           (is (false? (door-only? '#{re-frame.freehand.cell}))))

         (is (door-only? library)
             "the exemplar component library reaches no Freehand internal")
         (is (door-only? this)
             "and neither does this suite, which builds a second one")))))

;; ===========================================================================
;; A SECOND component library — `acme.ui`, built from the door alone
;; ===========================================================================
;;
;; `:acme.ui/*` is LIBRARY vocabulary (D017). The substrate reserves no
;; control namespace and fixes no storage path: the root, the record shape
;; and every event id below are this library's choices.

(def ^:private kind
  "The buffered controller family — half of every record key this library
  writes."
  :acme.ui/buffered-field)

(def ^:private other-kind
  "A SECOND family. Addressed at the same domain identity it must read its
  own record, which is why the kind is in the key at all."
  :acme.ui/stepper)

(def ^:private records-root :acme.ui/controllers)

(defn- register-library!
  "The library's whole dataflow: one read, two transitions, and the
  caller-side events an application writes around it. Ordinary `reg-sub`
  and `reg-event` — there is no registry to join and no controller to
  instantiate."
  []
  ;; THE FENCE, READ SIDE. The draft while it belongs to the caller's
  ;; current generation; the caller's baseline otherwise. A superseded
  ;; draft is not erased — it is invisible.
  (rf/reg-sub :acme.ui.buffered/text
    (fn [db [_ record-key revision baseline]]
      (let [record (get-in db [records-root record-key])]
        (if (v/controller-current? (:reset-key record) revision)
          (:draft record)
          baseline))))

  ;; Atomic create-or-replace, STAMPED with the generation the render that
  ;; produced this edit displayed. An edit landing after the caller has
  ;; moved on is born stale.
  (rf/reg-event :acme.ui.buffered/edited
    (fn [{:keys [db]} [_ record-key revision text]]
      {:db (assoc-in db [records-root record-key] {:reset-key revision :draft text})}))

  ;; THE FENCE, WRITE SIDE — the same predicate, decided against COMMITTED
  ;; state. A current record retires and produces the caller's intent; a
  ;; superseded one produces nothing at all.
  (rf/reg-event :acme.ui.buffered/committed
    (fn [{:keys [db]} [_ record-key revision on-commit]]
      (let [record (get-in db [records-root record-key])]
        (if (v/controller-current? (:reset-key record) revision)
          {:db (update db records-root dissoc record-key)
           :fx [[:dispatch (conj (vec on-commit) (:draft record))]]}
          {}))))

  ;; --- The caller's side ---------------------------------------------

  (rf/reg-sub :acme.invoice/reference
    (fn [db [_ id]] (get-in db [:invoice id :reference])))

  (rf/reg-sub :acme.invoice/reference-revision
    (fn [db [_ id]] (get-in db [:invoice id :reference-revision])))

  (rf/reg-event :acme.invoice/reference-accepted
    (fn [{:keys [db]} [_ id text]]
      {:db (-> db
               (assoc-in [:invoice id :reference] text)
               (update-in [:invoice id :reference-revision] inc)
               (update ::accepted (fnil conj []) text))}))

  ;; THE REJECTION. The caller refuses the committed draft and stands by
  ;; the value it already had — advancing the revision is what says "a NEW
  ;; baseline decision" in the one case value-equality cannot see.
  (rf/reg-event :acme.invoice/reference-refused
    (fn [{:keys [db]} [_ id]]
      {:db (update-in db [:invoice id :reference-revision] inc)})))

(v/defview buffered-field
  "The library's buffered control. It asks for its record key and its
  generation FIRST — which is what makes it a writable, buffered
  controller at all — and every intent it renders carries the generation
  the render that produced it displayed."
  {:props [:map
           [:control :any]
           [:reset-key :any]
           [:text :string]
           [:on-commit :vector]]}
  [{:keys [text on-commit] :as props}]
  (let [k (v/controller-key kind props)
        g (v/controller-revision kind props)]
    [:input {:value    text
             :on-input [:acme.ui.buffered/edited k g ::v/value]
             :on-blur  [:acme.ui.buffered/committed k g on-commit]}]))

;; ===========================================================================
;; Seams — public verbs only
;; ===========================================================================

(def ^:private fid :rf/default)
(def ^:private invoice-id 42)
(def ^:private address [:invoice invoice-id :reference])
(def ^:private on-commit [:acme.invoice/reference-accepted invoice-id])

(defn- send!    [ev]  (rf/dispatch-sync ev {:frame fid}))
(defn- seed!    [db]  (send! [:rf/set-db db]))
(defn- app-db   []    (rf/app-db-value fid))
(defn- record   []    (get-in (app-db) [records-root [kind address]]))
(defn- accepted []    (get (app-db) ::accepted []))
(defn- revision []    (rf/subscribe-once [:acme.invoice/reference-revision invoice-id]
                                         {:frame fid}))
(defn- baseline []    (rf/subscribe-once [:acme.invoice/reference invoice-id] {:frame fid}))

(defn- displayed
  "What the control would show under generation `g` — the library's own
  read, asked exactly as the control asks it."
  [g]
  (rf/subscribe-once [:acme.ui.buffered/text [kind address] g (baseline)] {:frame fid}))

(defn- input
  "The control's `:input` node, rendered with the caller's current
  baseline and generation."
  []
  (t/find (t/render [buffered-field {:control   address
                                     :reset-key (revision)
                                     :text      (displayed (revision))
                                     :on-commit on-commit}])
          #(= :input (:tag %))))

(defn- fire!
  "Dispatch the intent `node` carries under `prop`, with `payload`
  filling the reserved projection positions a live callback would — the
  published materializer, so the vector dispatched is the production
  one."
  ([node prop] (fire! node prop {}))
  ([node prop payload]
   (send! (v/materialize-event (get (t/attrs node) prop) payload))))

(defn- caught-data
  "The `ex-data` of the diagnostic `thunk` raises, or nil when it does
  not raise."
  [thunk]
  (try (thunk) nil (catch #?(:clj Throwable :cljs :default) e (ex-data e))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :init-fn register-library!}))

;; ===========================================================================
;; `v/controller-key` — identity
;; ===========================================================================

(deftest controller-key-forms-the-pair-and-refuses-an-absent-address
  (testing "The key is the PAIR of the library's kind and the caller's
            `:control` address, so two families addressed at one domain
            identity read two records rather than each other's."
    (let [props {:control address :reset-key 0}]
      (is (= [kind address] (v/controller-key kind props)))
      (is (= [other-kind address] (v/controller-key other-kind props)))
      (is (not= (v/controller-key kind props) (v/controller-key other-kind props))
          "the kind is half the key — a second family never reads this one's record")))

  (testing "An absent `:control` is REFUSED rather than defaulted: every
            controller that skipped it would otherwise share one record
            keyed by nil, which presents as one field editing another."
    (let [data (caught-data #(v/controller-key kind {:reset-key 0}))]
      (is (= :rf.error/view-control-address-missing (:rf.error/id data))
          "a stable diagnostic id, not a silently shared record")
      (is (= kind (:kind data)) "naming the offending controller family")
      (is (= :supply-a-control-address (:recovery data)))))

  (testing "the control for that refusal: the same call WITH an address
            returns, so the rejection above cannot be green because
            nothing works at all"
    (is (= [kind address] (v/controller-key kind {:control address})))))

;; ===========================================================================
;; `v/controller-revision` — currency, taken
;; ===========================================================================

(deftest controller-revision-takes-the-generation-and-refuses-its-absence
  (testing "The generation is the CALLER's `:reset-key`, and it is any
            EDN the caller likes — the fence only asks whether two of
            them are equal."
    (is (= 7 (v/controller-revision kind {:control address :reset-key 7})))
    (is (= [:accepted 3] (v/controller-revision kind {:control address
                                                      :reset-key [:accepted 3]}))
        "a collection is a legitimate generation")
    (is (= 0 (v/controller-revision kind {:control address :reset-key 0}))
        "and so is the stable literal a caller that never resets passes"))

  (testing "REQUIRED, because a control with no generation buffers
            correctly right up to the first rejection — a defect that
            reaches production because development never rejects
            anything."
    (let [data (caught-data #(v/controller-revision kind {:control address}))]
      (is (= :rf.error/view-control-reset-revision-missing (:rf.error/id data)))
      (is (= kind (:kind data)))
      (is (= :supply-a-reset-key (:recovery data))))))

;; ===========================================================================
;; `v/controller-current?` — the fence, as a predicate
;; ===========================================================================

(deftest controller-current-is-total-and-safe-in-the-missing-direction
  (testing "Work that cannot prove its currency does not have it."
    (is (true?  (v/controller-current? 7 7)))
    (is (false? (v/controller-current? 6 7)) "a superseded generation is not current")
    (is (false? (v/controller-current? nil 7))
        "an UNSTAMPED record is not current — the record has nothing to prove with")
    (is (false? (v/controller-current? 7 nil)))
    (is (false? (v/controller-current? nil nil))
        "and neither is a missing stamp against a missing generation — the exact
         case a hand-rolled `(= a b)` gets wrong, because two nils compare equal")
    (is (true? (= nil nil))
        "non-vacuous: bare equality really would have said `current` there"))

  (testing "and comparison is by VALUE, so a revision may be a collection
            without a controller having to remember that"
    (is (true?  (v/controller-current? [:accepted 3] [:accepted 3])))
    (is (false? (v/controller-current? [:accepted 3] [:accepted 4])))))

;; ===========================================================================
;; The whole protocol, driven through the door
;; ===========================================================================

(deftest a-buffered-controller-built-from-the-door-alone
  (seed! {:invoice {invoice-id {:reference "10" :reference-revision 1}}})

  (testing "an idle control shows the caller's baseline and owns no record"
    (is (= "10" (displayed (revision))))
    (is (nil? (record)) "a record appears on the first real edit, not before")
    (is (= "10" (:value (t/attrs (input))))
        "and the rendered control shows it"))

  (testing "a keystroke writes a record STAMPED with the generation the
            render that produced it displayed, and the draft is what the
            control now shows"
    (fire! (input) :on-input {::v/value "bad"})
    (is (= {:reset-key 1 :draft "bad"} (record)))
    (is (= "bad" (displayed (revision)))))

  (testing "THE CASE VALUE-EQUALITY IS BLIND TO. The caller refuses the
            draft and stands by the value it already had. The value
            before that decision and the value after it are IDENTICAL, so
            nothing derived from the value can see it — but the caller
            advanced its generation, and the draft stops being displayed
            at once."
    (send! [:acme.invoice/reference-refused invoice-id])
    (is (= "10" (baseline)) "the caller's value did not change — that is the point")
    (is (= 2 (revision))    "its generation did")
    (is (= "10" (displayed (revision))) "and the refused draft is no longer shown")
    (is (= {:reset-key 1 :draft "bad"} (record))
        "a superseded draft is INVISIBLE, not erased — nothing was written to hide it"))

  (testing "and it is not committable either: a blur arriving after the
            rejection speaks for the new generation, finds a record that
            speaks for the old, and produces nothing"
    (fire! (input) :on-blur)
    (is (= [] (accepted)) "no caller intent was produced")
    (is (some? (record)) "and the superseded record still carries its stamp"))

  (testing "editing resumes under the NEW generation"
    (fire! (input) :on-input {::v/value "REF-99"})
    (is (= {:reset-key 2 :draft "REF-99"} (record)))
    (is (= "REF-99" (displayed (revision)))))

  (testing "and a commit under the current generation retires the record
            and produces the caller's intent carrying the draft — one
            semantic transition, settling as one epoch"
    (fire! (input) :on-blur)
    (is (= ["REF-99"] (accepted)) "the caller's own event ran, with the draft")
    (is (nil? (record))           "the record retired")
    (is (= "REF-99" (baseline))   "and the caller's value is the committed one"))

  (testing "a repeated commit is an idempotent no-op — decided in the
            handler against COMMITTED state, never by a guard captured
            during render"
    (fire! (input) :on-blur)
    (is (= ["REF-99"] (accepted)) "still exactly one domain event")))
