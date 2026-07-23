(ns re-frame.freehand.controllers-cljs-test
  "FH-CTRL-001 … FH-CTRL-005 — semantic controllers: ordinary re-frame
  state keyed by the library's controller KIND plus an explicit
  caller-supplied `:control` address.

  The pilot below is deliberately unremarkable. `buffered-field` is a
  `v/defview` and its protocol is two `reg-sub`s and two `reg-event`s —
  the whole of what a component library writes to own a cross-event
  control. Nothing in it registers with the substrate, and the only
  Freehand contribution is `v/controller-key`: the pair
  `(kind, :control address)` a record lives under, plus the loud refusal
  when the address is absent. If this suite could only be written against
  a controller DSL, that would be evidence the DSL had been built too
  early.

  Five laws, one fixture apiece, on the JVM and in ClojureScript from the
  same bytes: two occurrences at different addresses stay independent
  (001); two at ONE address share a record on purpose (002); a writable
  controller with no address is refused (003); the causal owner clears
  the record and no lifecycle cleanup hook exists (004); and the state is
  ordinary frame data driven and read through the ordinary path (005).

  ## Why the render goes through a capture

  A controller reads its own record with `v/sub`, which is legal only
  during an active declared render (Spec 006 §The render-only rule). The
  structural surface `t/render` walks a body but is not a host, so it
  opens no candidate of its own; a host shell renders the body under
  `cell/with-capture` and walks the result. [[render-occurrences]] is
  that same composition, done headlessly: one candidate, one walk, and no
  commit — an abandoned render owns nothing, and these laws assert on
  VALUES rather than on ownership. The tree it answers is the tree the
  shell would walk, so the occurrences under test are real boundary nodes
  carrying real recorded props."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ===========================================================================
;; The pilot library — ordinary re-frame, owned by a component library
;; ===========================================================================
;;
;; `:my.ui/*` is LIBRARY vocabulary, not framework grammar (D017): the
;; substrate reserves no `:rf.field/*` namespace, and the library chooses
;; both its event ids and the app-db root its records live under.

(def ^:private kind
  "The controller family. Half of every record key, so a dropdown
  addressed at the same domain identity reads a different record."
  :my.ui/buffered-field)

(def ^:private records-root
  "Where THIS library keeps its records. Freehand fixes the identity
  model and not the storage path."
  :my.ui/controllers)

(defn- register-library!
  "The library's whole dataflow — two reads and two transitions — plus
  the one application event a commit produces. Run by the runtime
  fixture's `:init-fn`, so every test starts from these registrations
  against a clean registrar."
  []
  ;; Read — the record itself, for a tool or a test inspecting state.
  (rf/reg-sub :my.ui/record
    (fn [db [_ record-key]] (get-in db [records-root record-key])))

  ;; Read — what the control DISPLAYS: the draft while a session is live,
  ;; the caller's external value otherwise.
  (rf/reg-sub :my.ui.field/text
    (fn [db [_ record-key baseline]]
      (if-some [draft (get-in db [records-root record-key :draft])]
        draft
        baseline)))

  ;; Transitions — named for what the user did, never for where the data
  ;; went. A trace of `put :draft` cannot explain a protocol.
  (rf/reg-event :my.ui.field/edited
    (fn [{:keys [db]} [_ record-key text]]
      {:db (assoc-in db [records-root record-key :draft] text)}))

  ;; One semantic event, its own effects: retire the session AND produce
  ;; the caller's intent, so the commit and the domain event it causes
  ;; settle together rather than as two racing steps.
  (rf/reg-event :my.ui.field/committed
    (fn [{:keys [db]} [_ record-key on-commit]]
      (if-some [draft (get-in db [records-root record-key :draft])]
        {:db (update db records-root dissoc record-key)
         :fx [[:dispatch (conj (vec on-commit) draft)]]}
        {})))

  ;; The CAUSAL OWNER's event — the application's, not the library's. It
  ;; names the addresses whose lifetime it owns and clears exactly those.
  (rf/reg-event :invoice/closed
    (fn [{:keys [db]} [_ & addresses]]
      {:db (update db records-root
                   #(apply dissoc % (map (fn [address] [kind address]) addresses)))}))

  ;; The APPLICATION's domain event, the one a commit hands the draft to.
  (rf/reg-event :invoice/amount-committed
    (fn [{:keys [db]} [_ invoice-id amount]]
      {:db (assoc-in db [:invoice invoice-id :amount] amount)})))

(v/defview buffered-field
  "The pilot control. It asks for its record key FIRST — which is what
  makes it a writable controller at all — then reads and emits through
  ordinary re-frame."
  [{:keys [value on-commit] :as props}]
  (let [k (v/controller-key kind props)]
    [:input {:value    (v/sub [:my.ui.field/text k value])
             :on-input [:my.ui.field/edited k ::v/value]
             :on-blur  [:my.ui.field/committed k on-commit]}]))

(v/defview props-only-field
  "The DEFAULT a reusable view should be: value in, intent out. It never
  asks for a record key, so it has no address to omit."
  [{:keys [value on-change]}]
  [:input {:value value :on-input on-change}])

(v/defview invoice-panel
  "Two occurrences of ONE view, side by side. What differs between them
  is the props their call sites pass — nothing is derived from the
  position they occupy here."
  [{:keys [a b]}]
  [:form
   [buffered-field a]
   [buffered-field b]])

;; ===========================================================================
;; Seams
;; ===========================================================================

(def ^:private fid :rf/default)

(defn- seed!    [db] (frame/replace-app-db! fid db))
(defn- app-db   []   (frame/frame-app-db-value fid))
(defn- records  []   (get (app-db) records-root))
(defn- send!    [ev] (rf/dispatch-sync ev {:frame fid}))
(defn- read-sub [q]  (rf/subscribe-once q {:frame fid}))

(defn- render!
  "Render `form` headlessly and answer the tree plus, per `:input` in
  document order, what that occurrence displays and the intents it
  carries. See §Why the render goes through a capture."
  [form]
  (let [cand (cell/candidate (cell/cell :my.ui/panel) fid)
        tree (cell/with-capture cand (fn [] (t/render form)))
        ins  (t/find-all tree #(= :input (:tag %)))]
    {:tree   tree
     :inputs ins
     :shown  (mapv #(:value (t/attrs %)) ins)}))

(defn- fire!
  "Dispatch the intent occurrence `input` carries under `prop`, with
  `args` filling the projection positions a live payload would."
  [input prop & args]
  (let [event (get (t/attrs input) prop)]
    (send! (into (vec (butlast event)) args))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :init-fn register-library!}))

;; ===========================================================================
;; FH-CTRL-001 — different addresses, independent records
;; ===========================================================================

(def ctrl-001 (conf/fixture :FH-CTRL-001))

(deftest fh-ctrl-001-different-addresses-hold-independent-records
  (testing "Per FH-CTRL-001: a record is keyed by controller KIND plus the
            caller-supplied :control address, so two occurrences of one
            view given DIFFERENT addresses hold independent records — an
            edit at one moves only its own, and the other keeps showing
            its baseline."
    (let [{:keys [control-a control-b value-a value-b typed
                  displayed-after-edit record-keys-after-edit]} ctrl-001
          form [invoice-panel {:a {:control control-a :value value-a}
                               :b {:control control-b :value value-b}}]]
      (seed! {})
      (testing "both occurrences start on their own external baseline"
        (is (= [value-a value-b] (:shown (render! form)))))

      (testing "and the tree really carries two occurrences of ONE view,
                each recording the address its call site passed"
        (let [boundaries (t/find-all (:tree (render! form))
                                     #(= ::buffered-field (:view-id %)))]
          (is (= 2 (count boundaries)) "two occurrences of the same view")
          (is (= [control-a control-b] (mapv #(:control (t/attrs %)) boundaries))
              "each occurrence records the address its CALL SITE passed")))

      ;; The edit fires the intent occurrence A's own input carries — the
      ;; address travels in the event, as data.
      (fire! (first (:inputs (render! form))) :on-input typed)

      (testing "the edit moved exactly one record"
        (is (= record-keys-after-edit (vec (keys (records))))
            "one record, under the (kind, address) pair — B never acquired one")
        (is (= 1 (count (records)))
            "non-vacuous: the law is asserted against a non-empty record table"))

      (testing "so the two occurrences now show different things"
        (is (= displayed-after-edit (:shown (render! form)))
            "A shows its draft; B is untouched")
        (is (apply not= displayed-after-edit)
            "non-vacuous: independence is a DIFFERENCE, not two equal values")))))

;; ===========================================================================
;; FH-CTRL-002 — one address, one record, shared on purpose
;; ===========================================================================

(def ctrl-002 (conf/fixture :FH-CTRL-002))

(deftest fh-ctrl-002-one-address-is-deliberate-sharing
  (testing "Per FH-CTRL-002: two occurrences given the SAME :control
            address share ONE record deliberately. This is precisely why
            the address is caller-supplied rather than derived: an
            occurrence-derived anchor could not express sharing at all,
            because two occurrences necessarily differ. Nothing about the
            second occupant is diagnosed."
    (let [{:keys [control value typed displayed-after-edit
                  record-keys-after-edit diagnosed?]} ctrl-002
          props {:control control :value value}
          form  [invoice-panel {:a props :b props}]]
      (seed! {})
      (testing "both occurrences start on the shared baseline"
        (is (= [value value] (:shown (render! form)))))

      ;; Occurrence A edits. Occurrence B does nothing at all.
      (fire! (first (:inputs (render! form))) :on-input typed)

      (testing "there is exactly ONE record — sharing is one record read
                twice, not two records kept in step"
        (is (= record-keys-after-edit (vec (keys (records)))))
        (is (= 1 (count (records)))
            "non-vacuous: a shared address yields a single record"))

      (testing "and BOTH occurrences read the draft the other one typed"
        (let [shown (:shown (render! form))]
          (is (= displayed-after-edit shown))
          (is (apply = shown) "the occurrences agree, because they are one state")
          (is (not= value (first shown))
              "non-vacuous: the shared value actually moved off the baseline")))

      (testing "and sharing is a FEATURE — rendering both occupants of one
                address raises nothing"
        (is (false? (boolean diagnosed?)) "the fixture claims no diagnostic")
        (is (= conf/no-throw (conf/caught-id #(render! form)))
            "two writable occupants of one address render cleanly")))))

;; ===========================================================================
;; FH-CTRL-003 — a writable controller with no address is refused
;; ===========================================================================

(def ctrl-003 (conf/fixture :FH-CTRL-003))

(deftest fh-ctrl-003-a-writable-controller-needs-an-address
  (testing "Per FH-CTRL-003: a writable controller rendered with no
            :control address is refused loudly — no default, no
            synthesised address — because every controller that skipped
            one would otherwise share a record keyed by nil."
    (let [{fixture-kind :kind
           :keys        [missing-address-error-id recovery extra-keys message-mentions
                         control value props-only-renders?]} ctrl-003
          thunk #(t/render [buffered-field {:value value}])]
      (seed! {})
      (is (= missing-address-error-id (conf/caught-id thunk))
          "a stable id, not a silently nil address")
      (is (some? missing-address-error-id)
          "non-vacuous: the law asserts an actual diagnostic id")

      (testing "carrying what locates the site: which controller family
                refused, and a bounded SHAPE summary of the props"
        (let [data (try (thunk) nil
                        (catch #?(:clj Throwable :cljs :default) e (ex-data e)))]
          (is (= fixture-kind (:kind data)) "the offending controller kind")
          (is (= recovery (:recovery data)))
          (is (= (set extra-keys) (set (keys (dissoc data :rf.error/id :where))))
              "exactly the catalogued ex-data slots")
          (is (map? (:props data)) "the props ride as a shape summary")
          (is (not (contains? (:props data) :value))
              "a SUMMARY, never the props themselves")))

      (testing "the message names the prop and the fix"
        (let [message (conf/caught-message thunk)]
          (doseq [fragment message-mentions]
            (is (str/includes? message fragment)
                (str "the message mentions " (pr-str fragment))))))

      (testing "the control for the refusal: the SAME view, given an
                address, renders — so the rejection above cannot be green
                because nothing renders at all"
        (is (= [value] (:shown (render! [buffered-field {:control control
                                                         :value   value}])))))

      (testing "and a props-only view never asks for a record key, so the
                cost of addressing is paid only by controls that write"
        (is (= props-only-renders?
               (= [value] (:shown (render! [props-only-field
                                            {:value     value
                                             :on-change [:app/typed]}])))))))))

;; ===========================================================================
;; FH-CTRL-004 — owner cleanup, and the absent lifecycle hook
;; ===========================================================================

(def ctrl-004 (conf/fixture :FH-CTRL-004))

(defn- names-a-cleanup-hook?
  "True when `s` reads as a lifecycle / cleanup callback surface under the
  fixture's roster. Substring matching, so a differently-spelled sibling
  (`on-unmount`, `use-effect`, `component-will-unmount`) is caught by the
  same roster rather than needing its own entry."
  [spellings s]
  (boolean (some #(str/includes? (str/lower-case (str s)) %) spellings)))

(deftest fh-ctrl-004-the-owner-clears-and-no-lifecycle-hook-exists
  (testing "Per FH-CTRL-004: the causal owner's ordinary event clears the
            addresses it owns; an unrelated record survives; and the
            substrate exposes NO lifecycle cleanup hook, so retention
            follows the owner rather than what is currently on screen."
    (let [{:keys [owned-control unrelated-control owned-draft unrelated-draft
                  record-keys-before owner-event record-keys-after
                  cleanup-hook-spellings view-abi-keys]} ctrl-004]
      (seed! {})
      ;; Two live drafts, seeded the way the control seeds them: through
      ;; the library's own semantic transition.
      (send! [:my.ui.field/edited [kind owned-control] owned-draft])
      (send! [:my.ui.field/edited [kind unrelated-control] unrelated-draft])
      (is (= record-keys-before (vec (sort-by str (keys (records)))))
          "two records, one per address")

      (send! owner-event)

      (testing "the owner cleared exactly the addresses it named"
        (is (= record-keys-after (vec (keys (records)))))
        (is (= 1 (count (records)))
            "non-vacuous: cleanup is scoped, not a wipe of everything this kind holds")
        (is (= unrelated-draft (get-in (records) [[kind unrelated-control] :draft]))
            "the unrelated record is untouched — its owner has not closed"))

      (testing "no lifecycle cleanup hook exists in the declared-view ABI —
                what a tool reads off a view is identity, source, lowering
                and policy, nothing invocable at teardown"
        (let [abi (keys (v/describe buffered-field))]
          (is (seq abi) "non-vacuous: the projection has keys to examine")
          (is (= (set view-abi-keys) (set abi)) "the ABI is closed")
          (is (empty? (filter #(names-a-cleanup-hook? cleanup-hook-spellings (name %)) abi))
              "and no key of it names a cleanup callback")))

      #?(:clj
         (testing "nor on the published Freehand door. JVM-only because
                   ns-publics is a JVM reflection API; the ClojureScript
                   half of the same surface is reconciled by the
                   api-manifest probe, which carries re-frame.freehand in
                   both directions."
           (let [published (mapv first (ns-publics 're-frame.freehand))]
             (is (seq published) "non-vacuous: the door publishes vars to examine")
             (is (empty? (filter #(names-a-cleanup-hook? cleanup-hook-spellings %) published))
                 "no published var is an unmount / dispose / teardown hook")
             (is (seq (filter #(names-a-cleanup-hook? cleanup-hook-spellings %)
                              (conj published 'on-unmount)))
                 "and the probe can SEE such a name when one is present")))))))

;; ===========================================================================
;; FH-CTRL-005 — ordinary frame data, driven and read the ordinary way
;; ===========================================================================

(def ctrl-005 (conf/fixture :FH-CTRL-005))

(deftest fh-ctrl-005-controller-state-is-ordinary-frame-data
  (testing "Per FH-CTRL-005: a controller is ordinary re-frame, so the
            whole exchange runs with NO view mounted — dispatch the
            semantic transition, read the record back through a plain
            subscription — and the record sits at an ordinary app-db path
            an epoch, a snapshot and a tool already see."
    (let [{:keys [control typed inspection-query record record-path on-commit
                  committed-event committed-path committed-value]} ctrl-005
          k (v/controller-key kind {:control control})]
      (seed! {})
      (send! [:my.ui.field/edited k typed])

      (testing "read back through the NORMAL path — a plain subscription
                over app-db, not a controller-specific reader"
        (is (= record (read-sub (conj inspection-query k)))))

      (testing "and it is plain data at a plain path"
        (is (= record (get-in (app-db) record-path))
            "the same value, reached by get-in on the frame's app-db")
        (is (= [kind control] (last record-path))
            "the path's leaf IS the (kind, address) pair"))

      (testing "a semantic transition is an ordinary registered event, and
                one transition both retires the session and produces the
                caller's intent"
        (send! (conj committed-event k on-commit))
        (is (nil? (get-in (app-db) record-path)) "the session is retired")
        (is (= committed-value (get-in (app-db) committed-path))
            "and the caller's domain event received the draft")))))
