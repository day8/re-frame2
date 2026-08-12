(ns re-frame.hicasso.combobox-keyboard-dom-cljs-test
  "L4 — THE SELECT-ONLY DROPDOWN'S KEYBOARD CONDUCT, AND THE ACTIVE-
  DESCENDANT MODEL IT RESTS ON (rf2-hic-049; merged-PR audit #7914).

  A dropdown built from `overlay/popover` is the one overlay pattern
  whose accessibility is entirely the APPLICATION's. The module supplies
  the top layer, the light dismiss and the anchor; every part a screen
  reader announces — the role, the expanded state, which option is
  active, which option is selected — is markup the author writes, and
  the audit on this bead found the guide's own example writing it
  wrongly:

  > The focused native button carries `aria-activedescendant` for a
  > sibling listbox but has neither a combobox role nor
  > `aria-controls`/ownership, so its active-descendant model is
  > invalid. `aria-selected` also follows transient `:active` although
  > the app's committed value changes only on Enter/click.
  >
  > — merged-PR audit #7914, on rf2-hic-049

  Both halves are defects a reader cannot see and a click-driven test
  cannot reach. `aria-activedescendant` is a POINTER, and a pointer is
  only meaningful if the pointing element owns what it points at: the
  attribute tells assistive technology *the user's position is on that
  element over there*, and the platform resolves *over there* through
  the combobox's `aria-controls`/`aria-owns`. Written on an element with
  no combobox role and no ownership edge, it names an id in a vacuum —
  the announcement is simply not made, and the dropdown reads as a
  button whose label changes for no stated reason.

  ## What this file is, and its relationship to the guide

  [[select-dropdown]] is the guide's example
  (`docs/design/hicasso/draft-guide/13-overlays-and-focus.md`,
  *Build a dropdown from a popover*) with the audit's bounded repair
  applied: the trigger carries `role=\"combobox\"`; `aria-controls`
  names the listbox and is emitted only while one exists;
  `aria-activedescendant` names an option INSIDE that listbox; and
  `aria-selected` follows the COMMITTED value while
  `aria-activedescendant` follows the keyboard's transient position.

  [[the-guides-dropdown-as-written]] is the same view with the repair
  taken back out, and it is not decoration: every row below is an
  emptiness claim or a stability claim, and each one is shown going the
  other way over that view. It is also what makes this file the audit's
  *witness covering the exact guide example* rather than a witness over
  a nearby one.

  **The guide chapters are another worker's fence on this wave** —
  chapter 13 carries the example and chapter 22 calls it the contract —
  so this file does not edit them. The follow-up bead named in the PR
  body carries the text change, with this namespace as its source of
  truth.

  ## Keyboard conduct is measurable here, and only here

  A synthetic `KeyboardEvent` performs no DEFAULT action — no Tab moves
  focus, no Escape closes a dialog — which is why
  `examples.ledger.keyboard-dom-cljs-test` witnesses traversal as the
  engine's focusability answer rather than as a keypress. But
  `:on-key-down` is a React handler, and React delivers a synthetic
  keydown to it exactly as it delivers a trusted one. So the conduct the
  APPLICATION owns — arrow keys moving the active option, Enter
  committing it, DOM focus never leaving the trigger — is genuinely
  driven below, by real key events, and is the one place in this bead
  where a key press is the instrument rather than the gap.

  Escape remains the platform's: the popover owns light dismiss and the
  close request, and `overlay-dom-cljs-test` drives it through
  `HTMLDialogElement.requestClose` for want of trusted input.

  ## Lanes

  The structural rows run everywhere — a role, a pointer and its
  referent are facts about markup, so they are decided on rf2-hic-043's
  projections and need no engine. The conduct rows need a real React DOM
  and state a skip in the node lane. Nothing here is `async` (rf2-u0j8:
  an async row under a positional fixture aborts the whole run)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.overlay :as overlay]
            [re-frame.hicasso.test :as ht]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The model — the guide's, with a COMMITTED value beside the active one
;; ---------------------------------------------------------------------------

(def ^:private items
  [{:value "en" :label "English"}
   {:value "fr" :label "Français"}
   {:value "ja" :label "日本語"}])

(def ^:private values (mapv :value items))

(def ^:private label-id "combo-locale-label")
(def ^:private trigger-id "combo-locale-trigger")
(def ^:private listbox-id "combo-locale-listbox")

(defn- option-id [v] (str "combo-locale-opt-" v))

(rf/reg-sub ::open? (fn [db _] (boolean (:open? db))))
(rf/reg-sub ::active (fn [db _] (:active db)))
(rf/reg-sub ::value (fn [db _] (:value db "en")))

(rf/reg-event ::toggled (fn [{:keys [db]} _] {:db (update db :open? not)}))
(rf/reg-event ::dismissed (fn [{:keys [db]} _] {:db (assoc db :open? false)}))

(rf/reg-event ::moved
  {:doc "An arrow key. Opens the list if it is shut and moves the ACTIVE
  option, which is the keyboard's transient position and not a
  selection: nothing about `:value` moves here, and that is the audit's
  *keep active focus distinct from committed selection* stated in the
  model rather than only in the markup."}
  (fn [{:keys [db]} [_ step]]
    (let [at   (:active db)
          i    (get (zipmap values (range)) at -1)
          next (nth values (-> (+ i step) (max 0) (min (dec (count values)))))]
      {:db (assoc db :open? true :active next)})))

(rf/reg-event ::committed
  {:doc "Enter. The active option becomes the committed value and the
  list shuts."}
  (fn [{:keys [db]} _]
    (let [{:keys [open? active]} db]
      {:db (cond-> (assoc db :open? false)
             (and open? active) (assoc :value active))})))

(rf/reg-event ::selected
  {:doc "A pointer click on an option — the same commit by the other
  door."}
  (fn [{:keys [db]} [_ v]]
    {:db (assoc db :open? false :value v)}))

;; ---------------------------------------------------------------------------
;; The view — the guide's example with the audit's repair
;; ---------------------------------------------------------------------------

(h/defview select-dropdown
  "The guide's *Build a dropdown from a popover*, repaired.

  Four changes, and each is one clause of the audit:

  1. `role=\"combobox\"` on the trigger. Without it the element is a
     button, and `aria-activedescendant` is not an attribute a button
     may carry — ARIA allows it on `combobox`, `textbox`, `searchbox`,
     `listbox`, `menu`, `tree`, `grid`, `group` and `application`, and
     nowhere else.
  2. `aria-controls` naming the listbox. This is the OWNERSHIP edge the
     pointer is resolved through; without it the id in
     `aria-activedescendant` names an element the combobox has no stated
     relationship to.
  3. Both are emitted only while the list is OPEN, because the module's
     posture is that a closed overlay has no DOM node — so a
     permanently-emitted `aria-controls` would spend most of its life
     pointing at nothing, which is the same invalidity by a different
     route.
  4. `aria-selected` follows the COMMITTED value. The active option is
     where the keyboard is; the selected option is what the application
     will act on. In a list where selection does not follow focus those
     are two different options for as long as the user is looking
     around, and collapsing them announces a choice nobody has made."
  [_]
  (let [open?  (h/sub [::open?])
        active (h/sub [::active])
        value  (h/sub [::value])
        label  (some #(when (= value (:value %)) (:label %)) items)]
    [:div.combo
     [:span {:id label-id} "Language"]
     [:button
      {:id                    trigger-id
       :type                  "button"
       :role                  "combobox"
       :aria-labelledby       (str label-id " " trigger-id)
       :aria-haspopup         "listbox"
       :aria-expanded         open?
       :aria-controls         (when open? listbox-id)
       :aria-activedescendant (when (and open? active) (option-id active))
       :on-click              [::toggled]
       :on-key-down           {"ArrowDown" [::h/prevent [::moved 1]]
                               "ArrowUp"   [::h/prevent [::moved -1]]
                               "Enter"     [::h/prevent [::committed]]}}
      label]
     [overlay/popover {:open?      open?
                       :on-dismiss [::dismissed]
                       :anchor     trigger-id
                       :placement  :bottom-start}
      [:ul {:id listbox-id :role "listbox" :aria-labelledby label-id}
       (for [{v :value l :label} items]
         [:li {:key           v
               :id            (option-id v)
               :role          "option"
               :aria-selected (= v value)
               :on-click      [::selected v]}
          l])]]]))

(h/defview the-guides-dropdown-as-written
  "THE POSITIVE CONTROL — the same dropdown with the repair taken back
  out, which is the shape the guide ships today.

  Everything a reviewer looks at is here: the trigger is a real button,
  it is named, `aria-expanded` tracks the flag, the options carry
  `role=\"option\"` and the panel is a real listbox. What is missing is
  the pointer's ownership, and what is wrong is which state
  `aria-selected` follows — neither of which shows on screen, in a
  click-driven test, or in a structural sweep that only asks whether
  every control has a name."
  [_]
  (let [open?  (h/sub [::open?])
        active (h/sub [::active])
        value  (h/sub [::value])
        label  (some #(when (= value (:value %)) (:label %)) items)]
    [:div.combo
     [:span {:id label-id} "Language"]
     [:button
      {:id                    trigger-id
       :type                  "button"
       :aria-labelledby       (str label-id " " trigger-id)
       :aria-haspopup         "listbox"
       :aria-expanded         open?
       :aria-activedescendant (when (and open? active) (option-id active))
       :on-click              [::toggled]
       :on-key-down           {"ArrowDown" [::h/prevent [::moved 1]]
                               "ArrowUp"   [::h/prevent [::moved -1]]
                               "Enter"     [::h/prevent [::committed]]}}
      label]
     [overlay/popover {:open?      open?
                       :on-dismiss [::dismissed]
                       :anchor     trigger-id
                       :placement  :bottom-start}
      [:ul {:role "listbox"}
       (for [{v :value l :label} items]
         [:li {:key           v
               :id            (option-id v)
               :role          "option"
               ;; The transient position, announced as the selection.
               :aria-selected (= v active)
               :on-click      [::selected v]}
          l])]]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

;; ---------------------------------------------------------------------------
;; The auditor — an active-descendant model, checked the way a platform
;; resolves one
;; ---------------------------------------------------------------------------

(def ^:private activedescendant-roles
  "The roles ARIA permits `aria-activedescendant` on. A pointer on
  anything else is not a pointer: the attribute is defined against these
  roles, so a user agent reading it on a plain button has nothing to do
  with it."
  #{"combobox" "textbox" "searchbox" "listbox" "menu" "menubar" "tree"
    "treegrid" "grid" "radiogroup" "group" "toolbar" "application"})

(defn- attr [node k] (get (ht/attrs node) k))

(defn- ids-of [s] (remove str/blank? (str/split (or s "") #"\s+")))

(defn- activedescendant-findings
  "Every way `tree`'s active-descendant model can be broken, in the
  order a platform would trip over them.

  Written as a sweep over the tree rather than as four assertions about
  one element, so a second combobox added tomorrow is audited the day it
  lands — the shape rf2-hic-043's `ht/unnamed-controls` established, and
  the reason this file's claims are `= []` rather than a list of
  attribute readings.

  | finding | what a platform does with it |
  |---|---|
  | `:role-may-not-carry-activedescendant` | ignores the attribute — the position is never announced |
  | `:no-ownership-edge` | has no stated relationship to resolve the id through |
  | `:activedescendant-names-nothing` | resolves the id to no element |
  | `:activedescendant-outside-the-owned-subtree` | resolves it outside what the combobox controls |"
  [tree]
  (let [by-id (into {} (map (juxt #(attr % :id) identity))
                    (ht/find-all tree #(some? (attr % :id))))]
    (into []
          (mapcat
            (fn [node]
              (let [target (attr node :aria-activedescendant)
                    owned  (concat (ids-of (attr node :aria-controls))
                                   (ids-of (attr node :aria-owns)))
                    owner  (some by-id owned)
                    referent (get by-id target)]
                (cond-> []
                  (not (contains? activedescendant-roles (name (or (ht/role node) :none))))
                  (conj :role-may-not-carry-activedescendant)

                  (empty? owned)
                  (conj :no-ownership-edge)

                  (nil? referent)
                  (conj :activedescendant-names-nothing)

                  (and (some? referent) (some? owner)
                       (not (some #(identical? referent %)
                                  (ht/find-all owner (constantly true)))))
                  (conj :activedescendant-outside-the-owned-subtree)))))
          (ht/find-all tree #(some? (attr % :aria-activedescendant))))))

(defn- selected-options
  "The values of the options carrying `aria-selected=\"true\"`, in
  document order."
  [tree]
  (into []
        (comp (filter #(= :option (ht/role %)))
              (filter #(true? (attr % :aria-selected)))
              (map #(attr % :id)))
        (ht/find-all tree (constantly true))))

(defn- open-tree
  "The named view, rendered with the list open and the keyboard resting
  on `active` while `value` is committed."
  [view {:keys [active value]}]
  (ht/tree [view {}]
           {:subs {[::open?] true
                   [::active] active
                   [::value] (or value "en")}}))

;; ---------------------------------------------------------------------------
;; The model, structurally — both views, both ways
;; ---------------------------------------------------------------------------

(deftest the-repaired-dropdown-has-a-resolvable-active-descendant-model
  (let [t (open-tree select-dropdown {:active "ja" :value "en"})]
    (is (= [] (activedescendant-findings t))
        "THE ACCEPTANCE. The pointer is on a `combobox`, the combobox
         controls the listbox, and the id it names is an option inside
         it — the four things a platform needs before it will announce
         *3 of 3, 日本語* at all")

    (testing "and the pieces, named, so a failure above says which"
      (let [trigger (ht/find t #(= :combobox (ht/role %)))]
        (is (= trigger-id (attr trigger :id)))
        (is (= listbox-id (attr trigger :aria-controls)))
        (is (= (option-id "ja") (attr trigger :aria-activedescendant)))
        (is (true? (attr trigger :aria-expanded)))))))

(deftest the-guides-dropdown-fails-the-model-in-exactly-two-ways
  ;; THE POSITIVE CONTROL, and the audit finding reproduced. Without it
  ;; the row above is green having proved only that the sweep can return
  ;; an empty vector, which it would do just as readily over a tree it
  ;; could not read at all.
  (let [t (open-tree the-guides-dropdown-as-written {:active "ja" :value "en"})]
    (is (= [:role-may-not-carry-activedescendant :no-ownership-edge]
           (activedescendant-findings t))
        "the two halves of merged-PR audit #7914's first sentence. Note
         what is NOT reported: the id resolves perfectly — the option
         really is in the document, with that exact id — which is why
         this defect survives review and why an `is the referent there?`
         check alone would call it fine")

    (testing "and the trigger reads as an ordinary button, which is
              precisely the problem: everything about it is legal markup"
      (let [trigger (ht/find t #(some? (attr % :aria-activedescendant)))]
        (is (= :button (ht/role trigger)))
        (is (nil? (attr trigger :aria-controls)))))))

(deftest aria-selected-follows-the-commitment-and-not-the-keyboard
  (testing "the repaired view: the keyboard is on `ja`, the application
            holds `en`, and it is `en` that is announced as selected —
            exactly one option, and the committed one"
    (let [t (open-tree select-dropdown {:active "ja" :value "en"})]
      (is (= [(option-id "en")] (selected-options t)))))

  (testing "moving the keyboard does not move it — the same view, the
            same commitment, the active option somewhere else again"
    (let [t (open-tree select-dropdown {:active "fr" :value "en"})]
      (is (= [(option-id "en")] (selected-options t)))))

  (testing "and committing does: `aria-selected` moves when the value
            moves, so the row above is a distinction rather than an
            attribute that never changes"
    (let [t (open-tree select-dropdown {:active "fr" :value "ja"})]
      (is (= [(option-id "ja")] (selected-options t)))))

  (testing "THE CONTROL — the guide's view announces the TRANSIENT
            position as the selection. A screen-reader user arrowing
            through three options is told they have selected all three,
            one after another, having chosen none"
    (is (= [(option-id "ja")]
           (selected-options (open-tree the-guides-dropdown-as-written
                                        {:active "ja" :value "en"}))))
    (is (= [(option-id "fr")]
           (selected-options (open-tree the-guides-dropdown-as-written
                                        {:active "fr" :value "en"}))))))

;; ---------------------------------------------------------------------------
;; The conduct — real key events, on a real React DOM
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "a keyboard round trip needs a real React DOM — " why)))

(defn- q [m sel] (.querySelector (:container m) sel))
(defn- active-el [] (.-activeElement js/document))
(defn- attr-of [m sel a] (some-> (q m sel) (.getAttribute a)))

(defn- key!
  "A real `keydown` carrying `key`, on the trigger. React's synthetic
  event system delivers it to `:on-key-down` exactly as it delivers a
  trusted one — the DEFAULT ACTION is what an untrusted event does not
  get, and this handler is not one."
  [m k]
  (.dispatchEvent (q m (str "#" trigger-id))
                  (js/KeyboardEvent. "keydown" #js {:key k :bubbles true}))
  (hm/settle! m)
  nil)

(deftest the-arrow-keys-move-the-active-option-and-nothing-else
  (if-not (browser?)
    (skip! "the arrow keys")
    (let [m (hm/mount! [select-dropdown {}])]
      (try
        (.focus (q m (str "#" trigger-id)))
        (testing "premise: shut, with no pointer and nothing to point at"
          (is (= "false" (attr-of m (str "#" trigger-id) "aria-expanded")))
          (is (nil? (attr-of m (str "#" trigger-id) "aria-activedescendant")))
          (is (nil? (attr-of m (str "#" trigger-id) "aria-controls")))
          (is (nil? (q m (str "#" listbox-id)))
              "and no listbox in the document, which is the module's
               closed-cost posture and the reason `aria-controls` is
               conditional"))

        (key! m "ArrowDown")
        (testing "one arrow opens the list and puts the pointer on the
                  first option"
          (is (= "true" (attr-of m (str "#" trigger-id) "aria-expanded")))
          (is (= listbox-id (attr-of m (str "#" trigger-id) "aria-controls")))
          (is (= (option-id "en") (attr-of m (str "#" trigger-id) "aria-activedescendant")))
          (is (some? (q m (str "#" listbox-id)))))

        (key! m "ArrowDown")
        (key! m "ArrowDown")
        (testing "two more move it to the last, and stop there — a
                  clamp rather than a wrap, which is the guide's model
                  and is asserted so a later wrap is a decision rather
                  than a drift"
          (is (= (option-id "ja") (attr-of m (str "#" trigger-id) "aria-activedescendant")))
          (key! m "ArrowDown")
          (is (= (option-id "ja") (attr-of m (str "#" trigger-id) "aria-activedescendant"))))

        (testing "AND THE COMMITMENT NEVER MOVED. Three keystrokes later
                  the selected option is still the one the application
                  holds, and the trigger still shows its label — the
                  audit's *keep active focus distinct from committed
                  selection*, driven rather than described"
          (is (= "true" (.getAttribute (q m (str "#" (option-id "en"))) "aria-selected")))
          (is (= "false" (.getAttribute (q m (str "#" (option-id "ja"))) "aria-selected")))
          (is (= "English" (.-textContent (q m (str "#" trigger-id))))))

        (testing "and DOM focus never left the trigger, which is the
                  whole reason an active-descendant model is used
                  instead of moving focus into the list"
          (is (identical? (q m (str "#" trigger-id)) (active-el))))

        (finally (hm/unmount! m))))))

(deftest enter-commits-the-active-option-and-shuts-the-list
  (if-not (browser?)
    (skip! "the commit")
    (let [m (hm/mount! [select-dropdown {}])]
      (try
        (.focus (q m (str "#" trigger-id)))
        (key! m "ArrowDown")
        (key! m "ArrowDown")
        (is (= (option-id "fr") (attr-of m (str "#" trigger-id) "aria-activedescendant"))
            "premise: the keyboard is on the second option and the
             application still holds the first")
        (is (= "English" (.-textContent (q m (str "#" trigger-id)))))

        (key! m "Enter")

        (testing "THE ROUND TRIP: the active option became the committed
                  one, the list shut, and both attributes that only make
                  sense while it is open went with it"
          (is (= "Français" (.-textContent (q m (str "#" trigger-id)))))
          (is (= "false" (attr-of m (str "#" trigger-id) "aria-expanded")))
          (is (nil? (attr-of m (str "#" trigger-id) "aria-activedescendant")))
          (is (nil? (attr-of m (str "#" trigger-id) "aria-controls")))
          (is (nil? (q m (str "#" listbox-id)))))

        (is (identical? (q m (str "#" trigger-id)) (active-el))
            "and focus is still on the trigger — nothing had to be
             restored, because nothing ever moved")

        (finally (hm/unmount! m))))))
