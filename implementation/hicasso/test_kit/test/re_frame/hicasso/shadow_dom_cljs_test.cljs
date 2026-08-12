(ns re-frame.hicasso.shadow-dom-cljs-test
  "SHADOW COMPARISON, AND THE PROOF THAT IT CAN REDDEN (rf2-kyum).

  `hm/shadow!` mounts a reference implementation and a candidate against
  isolated copies of one seeded frame, drives both with one script, and
  compares canonical DOM and the intent stream at each checkpoint. This
  namespace is that door's witness, and it is written the way the door
  itself tells a consumer to write one: a proved-same pair, and then a
  **sabotage twin** for every class of difference the comparator claims
  to see.

  ## The sabotage twins are the point, not the decoration

  A comparator that has never been seen to fail is not evidence. It is
  the same defect as a census that cannot fail — three of which the first
  half of this bead (rf2-hic-055, the migration reporter) found by
  building controls exactly like these. So every green row here has a red
  row one line away from it:

  | Row | Twin | What the red must NAME |
  |---|---|---|
  | the pair is green over three steps | — | `{:status :green :checkpoints 4}` |
  | — | an intent that drifted | the checkpoint, the step, and both vectors |
  | — | one attribute that drifted | the checkpoint and the exact node |
  | — | a step that matched nothing | the selector, and RED rather than green |
  | one mount driven, the other not | — | `:only-in-reference` |

  The three drifted views differ from [[candidate-row]] by **one form
  each**, so a red proves the comparator saw that form and not the shape
  of the view around it.

  ## Two frames, and the one slot the comparison is blind at

  Isolation is the harness's whole premise — subscriptions may not reach
  across frames — and it is also what makes raw equality unfair at one
  slot: an intent can carry the rendering frame's keyword as ordinary
  data, and two isolated mounts have two different keywords.
  `capsule-replay-verdict.md` found that result and names shadow
  comparison while stating it. [[a-mounts-own-address-normalises]] drives
  both directions of the remedy: a mount's OWN address normalises, and a
  FOREIGN one still reddens.

  ## Lane

  Every row that mounts needs a real React DOM and runs under
  `:browser-test`; in `:node-test` (which compiles this namespace too,
  `cljs-test$` matching `-dom-cljs-test`) each degrades to a stated skip
  rather than a false green. The refusal rows mount nothing — shadow!
  validates its options before it allocates — so they run in both lanes."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The application under comparison
;; ---------------------------------------------------------------------------
;;
;; Registered ABOVE `use-fixtures`, deliberately: the reset fixture captures
;; its source-store baseline when the `use-fixtures` form is EVALUATED and
;; restores it before every row, so a registration written below it is erased
;; before the first row runs.

(rf/reg-sub :hicasso.shadow/title (fn [db _] (:title db)))
(rf/reg-sub :hicasso.shadow/editing? (fn [db _] (:editing? db)))
(rf/reg-sub :hicasso.shadow/saved (fn [db _] (:saved db)))

(rf/reg-event :hicasso.shadow/seed
              (fn [_ _] {:db {:title "Original title" :editing? false :saved nil}}))

(rf/reg-event :hicasso.shadow/edit
              (fn [{:keys [db]} _] {:db (assoc db :editing? true)}))

(rf/reg-event :hicasso.shadow/set-title
              (fn [{:keys [db]} [_ v]] {:db (assoc db :title v)}))

;; The extra argument the drifted twin sends is accepted and ignored, so the
;; two implementations end in the SAME app-db and the same DOM. That is what
;; makes the twin a test of the INTENT stream: nothing else about the page
;; moves, and a comparator reading only the DOM would call the pair identical.
(rf/reg-event :hicasso.shadow/save
              (fn [{:keys [db]} _] {:db (assoc db :saved (:title db) :editing? false)}))

;; Written by the address rows only, and read by no view — so the intent
;; stream moves and the page does not.
(rf/reg-event :hicasso.shadow/noted
              (fn [{:keys [db]} [_ x]] {:db (update db :notes (fnil conj []) x)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     ;; No ambient dynamic-var frame stamp: the only thing that may name a
     ;; frame here is the root each mount sits under, which is the isolation
     ;; the whole door rests on.
     :ambient-frame nil
     :init-fn       (fn []
                      ;; React's `act` queue is not the browser's scheduler,
                      ;; and every reading here is taken outside it.
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The reference, the candidate, and three twins one form apart
;; ---------------------------------------------------------------------------

(h/defview reference-row
  "The original: one body, everything read at the top."
  [_]
  (let [title    (h/sub [:hicasso.shadow/title])
        editing? (h/sub [:hicasso.shadow/editing?])
        saved    (h/sub [:hicasso.shadow/saved])]
    [:div.row
     [:h3.title title]
     (if editing?
       [:span.editor
        [:input.title {:value       title
                       :placeholder "Title"
                       :on-input    [:hicasso.shadow/set-title ::h/value]}]
        [:button.save {:on-click [:hicasso.shadow/save]} "Save"]]
       [:button.edit {:on-click [:hicasso.shadow/edit]} "Edit"])
     [:p.saved (str "saved: " (or saved "—"))]]))

(defn- editor-markup
  "A plain helper called inside the body — a read inside one is a read
  inside the body, which is half of what the pair proves: two views can
  be organised differently and mean the same thing."
  [title]
  [:span.editor
   [:input.title {:value       title
                  :placeholder "Title"
                  :on-input    [:hicasso.shadow/set-title ::h/value]}]
   [:button.save {:on-click [:hicasso.shadow/save]} "Save"]])

(h/defview candidate-row
  "The port: the same screen, written differently — reads pushed down
  into the branches that need them, and the editor lifted into a helper."
  [_]
  [:div.row
   [:h3.title (h/sub [:hicasso.shadow/title])]
   (if (h/sub [:hicasso.shadow/editing?])
     (editor-markup (h/sub [:hicasso.shadow/title]))
     [:button.edit {:on-click [:hicasso.shadow/edit]} "Edit"])
   [:p.saved (str "saved: " (or (h/sub [:hicasso.shadow/saved]) "—"))]])

(h/defview drifted-intent-row
  "[[candidate-row]] with ONE form changed: the save button's intent
  carries an extra argument. The app-db lands in the same place and the
  page is identical, so only the intent stream can see it."
  [_]
  [:div.row
   [:h3.title (h/sub [:hicasso.shadow/title])]
   (if (h/sub [:hicasso.shadow/editing?])
     [:span.editor
      [:input.title {:value       (h/sub [:hicasso.shadow/title])
                     :placeholder "Title"
                     :on-input    [:hicasso.shadow/set-title ::h/value]}]
      [:button.save {:on-click [:hicasso.shadow/save :force]} "Save"]]
     [:button.edit {:on-click [:hicasso.shadow/edit]} "Edit"])
   [:p.saved (str "saved: " (or (h/sub [:hicasso.shadow/saved]) "—"))]])

(h/defview drifted-dom-row
  "[[candidate-row]] with ONE attribute changed, and inside the branch
  the first script step opens — so the difference cannot be seen at the
  mount and the run must actually drive to reach it."
  [_]
  [:div.row
   [:h3.title (h/sub [:hicasso.shadow/title])]
   (if (h/sub [:hicasso.shadow/editing?])
     [:span.editor
      [:input.title {:value       (h/sub [:hicasso.shadow/title])
                     :placeholder "Article title"
                     :on-input    [:hicasso.shadow/set-title ::h/value]}]
      [:button.save {:on-click [:hicasso.shadow/save]} "Save"]]
     [:button.edit {:on-click [:hicasso.shadow/edit]} "Edit"])
   [:p.saved (str "saved: " (or (h/sub [:hicasso.shadow/saved]) "—"))]])

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(defn- browser? [] (exists? js/document))

(defn- skip! [why]
  (is true (str "a shadow run mounts two React roots — " why)))

(def ^:private seed [[:hicasso.shadow/seed]])

(def ^:private script
  "The migration page's own worked script: open the editor, type a new
  title, save. Three steps, so a green run takes four checkpoints."
  [{:click "button.edit"}
   {:type  ["input.title" "Better title"]}
   {:click "button.save"}])

(defn- run
  [candidate]
  (hm/shadow! {:reference      [reference-row {}]
               :candidate      [candidate {}]
               :initial-events seed
               :script         script}))

;; ---------------------------------------------------------------------------
;; The proved-same pair
;; ---------------------------------------------------------------------------

(deftest a-proved-same-pair-is-green
  (if-not (browser?)
    (skip! "the pair is two mounted implementations")
    (is (= {:status :green :checkpoints 4} (run candidate-row))
        (str "two implementations of one screen, driven by one script through "
             "three steps, agreed on canonical DOM and on every intent at all "
             "four checkpoints"))))

;; ---------------------------------------------------------------------------
;; The sabotage twins
;; ---------------------------------------------------------------------------

(deftest a-drifted-intent-names-its-checkpoint-and-both-vectors
  (if-not (browser?)
    (skip! "an intent stream needs a real click")
    (let [{:keys [status checkpoint checkpoints step difference]}
          (run drifted-intent-row)]
      (testing "the run is red, and it stops at the checkpoint that caused it"
        (is (= :red status))
        (is (= 3 checkpoint))
        (is (= 3 (:checkpoint (run drifted-intent-row)))
            "and deterministically so")
        (is (= 4 checkpoints) "four taken: the mount, and one per step")
        (is (= {:click "button.save"} step)))
      (testing "and it names the intent rather than the page it changed"
        (is (= :intent (:kind difference)))
        (is (= :differs (:reason difference)))
        (is (= 0 (:index difference)))
        (is (= [:hicasso.shadow/save] (:reference difference)))
        (is (= [:hicasso.shadow/save :force] (:candidate difference)))))))

(deftest a-drifted-attribute-names-the-exact-node
  (if-not (browser?)
    (skip! "an attribute is read off a real element")
    (let [{:keys [status checkpoint step difference]} (run drifted-dom-row)]
      (testing "the difference is inside the branch the first step opens"
        (is (= :red status))
        (is (= 1 checkpoint))
        (is (= {:click "button.edit"} step)))
      (testing "and the report names the node, the attribute and both values"
        (is (= :dom (:kind difference)))
        (is (= :attribute (:reason difference)))
        (is (= "placeholder" (:attribute difference)))
        (is (= "Title" (:reference difference)))
        (is (= "Article title" (:candidate difference)))
        (is (re-find #"input" (:at difference))
            (str "the path leads to the offending element: " (:at difference)))))))

(deftest a-step-that-matches-nothing-is-red-and-not-green
  (if-not (browser?)
    (skip! "a selector is resolved against a real container")
    (let [{:keys [status checkpoint difference]}
          (hm/shadow! {:reference      [reference-row {}]
                       :candidate      [candidate-row {}]
                       :initial-events seed
                       :script         [{:click "button.no-such-control"}]})]
      (is (= :red status)
          (str "a checkpoint reached by a step that did not happen has proved "
               "nothing, and green here means proved"))
      (is (= 1 checkpoint))
      (is (= {:kind     :script
              :reason   :selector-matched-nothing
              :selector "button.no-such-control"
              :matched  :neither}
             difference)))))

;; ---------------------------------------------------------------------------
;; Isolation, and the one slot the comparison is blind at
;; ---------------------------------------------------------------------------

(deftest each-mount-owns-its-own-frame
  (if-not (browser?)
    (skip! "two frames need two roots")
    (let [s (hm/shadow! {:reference      [reference-row {}]
                         :candidate      [candidate-row {}]
                         :initial-events seed})]
      (try
        (is (= :live (:status s)) "no :script means both mounts stay live")
        (let [ref-frame (:frame (:reference s))
              can-frame (:frame (:candidate s))]
          (is (not= ref-frame can-frame))
          (is (= {:status :green :checkpoints 1} ((:checkpoint! s)))
              "checkpoint 0 — the same seed produced the same page twice")

          (hm/dispatch-and-settle! (:reference s) [:hicasso.shadow/edit])
          (testing "a write on one side does not reach the other"
            (is (true? (rf/subscribe-once [:hicasso.shadow/editing?]
                                          {:frame ref-frame})))
            (is (false? (rf/subscribe-once [:hicasso.shadow/editing?]
                                           {:frame can-frame}))))
          (testing "and the comparator sees the divergence it caused"
            (let [{:keys [status difference]} ((:checkpoint! s))]
              (is (= :red status))
              (is (= :intent (:kind difference)))
              (is (= :only-in-reference (:reason difference)))
              (is (= [:hicasso.shadow/edit] (:reference difference))))))
        (finally ((:stop! s)))))))

(deftest a-mounts-own-address-normalises
  (if-not (browser?)
    (skip! "an address is a live frame's keyword")
    (let [s (hm/shadow! {:reference      [reference-row {}]
                         :candidate      [candidate-row {}]
                         :initial-events seed})]
      (try
        ((:checkpoint! s))                                  ;; drain the seed
        (testing "each side's OWN frame keyword compares equal"
          (hm/dispatch-and-settle! (:reference s)
                                   [:hicasso.shadow/noted (:frame (:reference s))])
          (hm/dispatch-and-settle! (:candidate s)
                                   [:hicasso.shadow/noted (:frame (:candidate s))])
          (is (= :green (:status ((:checkpoint! s))))
              (str "two isolated mounts have two frame keywords by construction; "
                   "an intent carrying its own is an ADDRESS, and comparing it "
                   "raw would redden every view holding an h/route-link")))
        (testing "and a FOREIGN address still reddens — the rule is narrow"
          (hm/dispatch-and-settle! (:reference s) [:hicasso.shadow/noted ::alien-a])
          (hm/dispatch-and-settle! (:candidate s) [:hicasso.shadow/noted ::alien-b])
          (let [{:keys [status difference]} ((:checkpoint! s))]
            (is (= :red status))
            (is (= :intent (:kind difference)))
            (is (= [:hicasso.shadow/noted ::alien-a] (:reference difference)))
            (is (= [:hicasso.shadow/noted ::alien-b] (:candidate difference)))))
        (finally ((:stop! s)))))))

;; ---------------------------------------------------------------------------
;; The closed rosters — refused before anything is mounted, so these rows
;; run in the node lane too
;; ---------------------------------------------------------------------------

(defn- refusal
  [f]
  (try (f) ::no-refusal (catch :default e (ex-data e))))

(deftest the-option-roster-is-closed
  (let [d (refusal #(hm/shadow! {:reference [reference-row {}]
                                 :candidate [candidate-row {}]
                                 :seed      seed}))]
    (is (= :rf.error/hicasso-test-bad-option (:rf.error/id d))
        (str "`:seed` was this surface's own earlier spelling for "
             ":initial-events; accepted in silence it would compare two "
             "UNSEEDED views and call them the same"))
    (is (= :remove-the-unknown-option (:recovery d)))
    (is (= [:seed] (:unknown d)))))

(deftest the-options-must-be-a-map
  (let [d (refusal #(hm/shadow! [:reference [reference-row {}]]))]
    (is (= :rf.error/hicasso-test-bad-option (:rf.error/id d)))
    (is (= :pass-a-map-of-options (:recovery d)))))

(deftest the-script-verb-roster-is-closed
  (let [d (refusal #(hm/shadow! {:reference [reference-row {}]
                                 :candidate [candidate-row {}]
                                 :script    [{:clik "button.edit"}]}))]
    (is (= :rf.error/hicasso-test-bad-option (:rf.error/id d))
        (str "a verb nothing recognises would drive neither side, and the "
             "checkpoint after it would go green for a step that never ran"))
    (is (= {:clik "button.edit"} (:step d))))
  (testing "and each verb's own argument shape"
    (is (= :rf.error/hicasso-test-bad-option
           (:rf.error/id (refusal #(hm/shadow! {:reference [reference-row {}]
                                                :candidate [candidate-row {}]
                                                :script    [{:click :button}]})))))
    (is (= :rf.error/hicasso-test-bad-option
           (:rf.error/id (refusal #(hm/shadow! {:reference [reference-row {}]
                                                :candidate [candidate-row {}]
                                                :script    [{:type "input.title"}]})))))))
