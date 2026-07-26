(ns re-frame.freehand.guide.events-cljs-test
  "Executable fixtures for the Freehand guide's EVENT chapters —
  `docs/core/freehand/authoring/events-and-handlers.md` and `forms.md`.

  This is the pair of pages with the most surface per line: the five named
  projection markers and the general `[::v/read path]` door, the options-map
  vocabulary, `v/event`, the controlled door, and the three ways to wire a
  text field. Every one of those is a spelling a consumer copies verbatim,
  and every one of them is invisible to prose review once it moves.

  Transcription conventions, as in
  `re-frame.freehand.guide.first-view-cljs-test`: a markup fragment that
  reads state is hosted in a one-line `v/defview` (a `def` would call
  `v/sub` at load time, outside any render); a fragment that reads nothing
  is held as data in a `def`; free names arrive as props.

  Filed under rf2-qwsmv."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; events-and-handlers.md — the paved path and its escapes
;; ---------------------------------------------------------------------------

;; events-and-handlers.md block 1 — the whole paved path, in one line.
(v/defview add-to-cart-button [{:keys [product-id]}]
  [:button {:on-click [:cart/add product-id]} "Add to cart"])

;; events-and-handlers.md block 2 — the three everyday named markers. Held as
;; data: nothing here reads state, and the block's claim is the spelling.
(def projection-marker-sites
  [[:input {:on-input  [:form/typed :email ::v/value]}]
   [:input {:type :checkbox :on-change [:prefs/set :dark ::v/checked]}]
   [:div   {:on-key-down [:editor/key ::v/key]}]])   ; "Enter", "a", …

;; events-and-handlers.md block 4 — the general read door the named markers
;; are sugar for.
(def general-read-sites
  [[:div {:on-scroll [:pane/scrolled [::v/read [:target :scrollLeft]]]}]
   [:div {:on-key-down [:editor/chord [::v/read :altKey]]}]])

;; events-and-handlers.md block 5 — listener mechanics as data.
(v/defview submit-options-map [_]
  [:form {:on-submit {:event [:signup/requested] :prevent-default true}}])

;; events-and-handlers.md block 6 — send the key, branch in the handler.
(v/defview key-pressed-site [_]
  [:input {:on-key-down [:picker/key-pressed ::v/key]}])

;; events-and-handlers.md block 7 — or branch at the site, when the browser
;; mechanics depend on which key it was.
(v/defview key-branch-site [_]
  [:input {:on-key-down (v/event [e]
                          (case (.-key e)
                            "Enter"  [:picker/accept]
                            "Escape" [:picker/close]
                            nil))}])

;; events-and-handlers.md block 8 — the controlled door.
(v/defview controlled-email-field [_]
  [:input {:value (v/sub [:form/email])
           :on-input [:form/typed :email ::v/value]}])

;; events-and-handlers.md block 9 — pattern A: the keystroke IS the value.
(v/defview live-domain-field [{:keys [id]}]
  [:input {:value    (v/sub [:lines/label id])
           :on-input [:lines/set-label id ::v/value]}])

;; events-and-handlers.md block 10 — pattern B: an app-owned draft, committed
;; on blur or Enter.
(v/defview draft-then-commit-field [{:keys [id]}]
  [:input {:value       (v/sub [:lines/draft id])
           :on-input    [:lines/draft-changed id ::v/value]
           :on-blur     [:lines/label-committed id]
           :on-key-down [:lines/draft-key id ::v/key]}])

;; events-and-handlers.md block 11 — pattern B's branch point is an ordinary
;; handler, and the decision sees application state.
(defn register-draft-key-handler!
  []
  (rf/reg-event :lines/draft-key
    (fn [{:keys [db]} [_ id k]]
      (case k
        "Enter"  {:fx [[:dispatch [:lines/label-committed id]]]}
        "Escape" {:fx [[:dispatch [:lines/draft-cancelled id]]]}
        {:db db}))))

;; events-and-handlers.md block 12 — the deliberate escape: uncontrolled.
(v/defview uncontrolled-cell-input [{:keys [id raw]}]
  ;; Uncontrolled: no :value key — not on the Freehand door
  [:input {:default-value raw
           :auto-focus    true
           :on-blur       [:cells/commit-edit id ::v/value]
           :on-key-down   (v/event [e]
                            (case (.-key e)
                              "Enter"  [:cells/commit-edit id (.. e -target -value)]
                              "Escape" [:cells/cancel-edit id]
                              nil))}])

;; events-and-handlers.md block 13 — a parent passes intent as data; the
;; child's own site dispatches it.
(v/defview icon-button [{:keys [event label]}]
  [:button {:aria-label label :on-click event}
   label])

(def icon-button-call
  (let [item-id 42]
    [icon-button {:event [:item/deleted item-id] :label "Delete"}]))

;; events-and-handlers.md block 14 — the event-PREFIX convention a library
;; control uses when it cannot know the caller's grammar.
(v/defview text-field [{:keys [value on-change]}]
  ;; inside text-field
  [:input {:value value
           :on-input (conj on-change ::v/value)}])

(def text-field-call
  (let [email "ada@example.com"]
    ;; caller
    [text-field {:value email :on-change [:form/set-email]}]))

;; ---------------------------------------------------------------------------
;; forms.md — drafts, errors, and the touched set
;; ---------------------------------------------------------------------------

;; forms.md block 2 — the field, reading its own key and its own error. The
;; parametric read is the block's claim: a whole-draft read would invalidate
;; every field on every keystroke, inside the controlled door's flush.
(v/defview email-field [_]
  (let [text  (v/sub [:editor/field :email])
        error (v/sub [:editor/field-error :email])]
    [:label
     "Email"
     [:input {:value    (or text "")
              :on-input [:editor/edit-field :email ::v/value]
              :on-blur  [:editor/blur-field :email]}]
     (when error [:p.error error])]))

;; forms.md block 3 — errors show only once the user has been there.
(defn register-field-error-sub!
  []
  (rf/reg-sub :editor/field-error
    (fn [db [_ field]]
      (let [{:keys [errors touched submit-attempted?]} (:editor db)]
        (when (or submit-attempted? (contains? touched field))
          (get errors field))))))

;; forms.md block 4 — a late server value must not clobber what the user typed.
(defn register-article-loaded!
  []
  (rf/reg-event :editor/article-loaded
    (fn [{:keys [db]} [_ slug article]]
      (let [{:keys [draft touched]} (get db :editor)
            seeded (reduce-kv
                    (fn [d k v]
                      (if (contains? touched k)
                        d                 ; user already typed here — keep it
                        (assoc d k v)))   ; otherwise take server value
                    draft
                    article)]
        {:db (assoc db :editor {:slug     slug
                                :draft    seeded
                                :baseline article
                                :touched  (or touched #{})})}))))

(defn- validate-editor
  "The page names this helper without showing it; a form's validation is the
  application's, not the substrate's."
  [{:keys [draft]}]
  (if (seq (:title draft)) {} {:title "Title is required"}))

;; forms.md block 5 — busy is a mutation fact, and submit is one event.
(defn register-editor-submit!
  []
  ;; Busy is commonly a mutation/resource fact, not a form-slice key
  (rf/reg-sub :editor/busy?
    (fn [db _]
      (boolean (get-in db [:mutations :editor-save :pending?]))))

  (rf/reg-sub :editor/can-submit?
    (fn [db _]
      (let [{:keys [draft errors]} (:editor db)
            busy? (get-in db [:mutations :editor-save :pending?])]
        (and (not busy?)
             (seq (:title draft))
             (empty? errors)))))

  (rf/reg-event :editor/submit
    (fn [{:keys [db]} _]
      (let [editor (assoc (:editor db) :submit-attempted? true)
            errors (validate-editor editor)]
        (if (seq errors)
          {:db (assoc db :editor (assoc editor :errors errors))}
          {:db (assoc db :editor (assoc editor :errors {}))
           :fx [[:dispatch [:editor/save (:draft editor)]]]})))))

;; forms.md block 6 — the submit button reads the two derived facts.
(v/defview editor-actions [_]
  (let [busy?    (v/sub [:editor/busy?])
        can-save (v/sub [:editor/can-submit?])]
    [:button {:disabled (or busy? (not can-save))
              :on-click [:editor/submit]}
     (if busy? "Saving…" "Save")]))

(defn- f->c-string [f-text] (str (/ (- (parse-long (str f-text)) 32) 2)))
(defn- c->f-string [c-text] (str (+ (* 2 (parse-long (str c-text))) 32)))

;; forms.md block 7 — the two-way conversion pair: the active field echoes
;; raw text, the other shows the derived display.
(def temperature-slice
  ;; Conceptual slice
  {:c-text "22" :f-text "71.6" :active :c})

(defn register-temperature-dataflow!
  []
  ;; Subs: active field echoes raw text; the other shows derived display
  (rf/reg-sub :temp/c-display
    (fn [db _]
      (let [{:keys [c-text f-text active]} (:temp db)]
        (if (= active :c) c-text (f->c-string f-text)))))

  ;; Events: typing sets :active + raw text; optional derive of the sibling raw
  (rf/reg-event :temp/c-typed
    (fn [{:keys [db]} [_ text]]
      {:db (update db :temp
                   #(assoc % :active :c :c-text text
                             :f-text (c->f-string text)))})))

;; forms.md block 8 — both fields, each controlled by its own display sub.
(v/defview temperature-inputs [_]
  [:<>
   [:input {:value (v/sub [:temp/c-display])
            :on-input [:temp/c-typed ::v/value]}]
   [:input {:value (v/sub [:temp/f-display])
            :on-input [:temp/f-typed ::v/value]}]])

;; ---------------------------------------------------------------------------
;; The samples, executed
;; ---------------------------------------------------------------------------

(defn- seed!
  [db]
  (rf/reg-sub :form/email (fn [d _] (:email d)))
  (rf/reg-sub :lines/label (fn [d [_ id]] (get-in d [:labels id])))
  (rf/reg-sub :lines/draft (fn [d [_ id]] (get-in d [:drafts id])))
  ;; forms.md's narrow field read, spelled inline on the page beside block 2.
  (rf/reg-sub :editor/field (fn [d [_ k]] (get-in d [:editor :draft k])))
  (rf/reg-sub :temp/c-display (fn [d _] (get-in d [:temp :c-text])))
  (rf/reg-sub :temp/f-display (fn [d _] (get-in d [:temp :f-text])))
  (register-field-error-sub!)
  (register-editor-submit!)
  (rf/dispatch-sync [:rf/set-db db]))

(deftest the-named-projection-roster-is-the-five-the-page-lists
  (testing "events-and-handlers.md block 3 states the whole named roster;
            `v/projections` is where the claim is settled, and this is the
            assertion that notices when it moves."
    (is (= #{::v/value ::v/checked ::v/key ::v/scroll-top ::v/new-state}
           v/projections)
        "five markers carry names — no more, no fewer")))

(deftest a-marker-rides-the-tree-unfilled-and-materializes-once
  (testing "the page's core mechanic: the site carries the marker as data,
            and `v/materialize-event` is the ONE thing that fills it."
    (seed! {:email "ada@example.com"})
    (let [tree  (t/with-render (t/render [controlled-email-field {}]))
          input (t/find tree #(= :input (:tag %)))
          site  (:on-input (t/attrs input))]
      (is (= [:form/typed :email ::v/value] site)
          "the tree holds the marker, unfilled")
      (is (= [:form/typed :email "hi"]
             (v/materialize-event site {::v/value "hi"}))
          "and materializing fills exactly the top-level marker"))))

(deftest listener-mechanics-ride-as-data-not-as-a-wrapper-function
  (testing "events-and-handlers.md's options map — the closed vocabulary
            sits beside the event rather than wrapping it in a closure."
    (let [tree (t/render [submit-options-map {}])
          site (:on-submit (t/attrs (t/find tree #(= :form (:tag %)))))]
      (is (= {:event [:signup/requested] :prevent-default true} site))
      (is (= [:signup/requested] (v/materialize-event (:event site) {}))
          "the event under :event is the ordinary vector every path materializes")
      (is (true? (:prevent-default site))
          "and the mechanics ride beside it as data, not inside a closure"))))

(deftest an-event-prefix-takes-the-marker-by-conj
  (testing "the library-control convention on the page's last block: the
            caller supplies a prefix, the control's own site appends the
            live scalar."
    (let [tree  (t/render text-field-call)
          input (t/find tree #(= :input (:tag %)))]
      (is (= [:form/set-email ::v/value] (:on-input (t/attrs input)))
          "the prefix and the marker are one vector at the site")
      (is (= [:form/set-email "typed"]
             (v/materialize-event (:on-input (t/attrs input))
                                  {::v/value "typed"}))))))

(deftest the-three-text-field-wirings-render-as-the-page-describes
  (testing "patterns A and B differ in exactly one way — B has draft, commit
            and cancel sites where A has one."
    (seed! {:labels {7 "live"} :drafts {7 "drafted"}})
    (let [a (t/attrs (t/find (t/with-render (t/render [live-domain-field {:id 7}]))
                             #(= :input (:tag %))))
          b (t/attrs (t/find (t/with-render (t/render [draft-then-commit-field {:id 7}]))
                             #(= :input (:tag %))))]
      (is (= "live" (:value a)))
      (is (= [:lines/set-label 7 ::v/value] (:on-input a)))
      (is (nil? (:on-blur a)) "pattern A has no commit transition")
      (is (= "drafted" (:value b)))
      (is (= [:lines/label-committed 7] (:on-blur b))
          "pattern B commits on blur")
      (is (= [:lines/draft-key 7 ::v/key] (:on-key-down b))
          "and branches on the key in the handler"))))

(deftest the-canonical-field-reads-its-own-key-not-the-draft-map
  (testing "forms.md block 2 — the field's value comes from the PARAMETRIC
            read `[:editor/field :email]`, so a keystroke in a sibling field
            cannot invalidate it. Rendering with a two-key draft is what makes
            this an assertion about the narrow read rather than about the
            value: a whole-draft read would answer the same string here and
            depend on both keys to do it."
    (seed! {:editor {:draft {:email "ada@example.com" :title "Ada"}
                     :errors {}
                     :touched #{}}})
    (let [input (t/find (t/with-render (t/render [email-field {}]))
                        #(= :input (:tag %)))]
      (is (= "ada@example.com" (:value (t/attrs input)))
          "the narrow read supplied the controlled value")
      (is (= [:editor/edit-field :email ::v/value] (:on-input (t/attrs input)))
          "and the site is still the ordinary controlled door"))))

(deftest an-error-shows-only-once-the-field-is-touched
  (testing "forms.md's touched/submit-attempted rule, run through the real
            subscription the page registers."
    (seed! {:editor {:draft {:email "x"}
                     :errors {:email "Not an email"}
                     :touched #{}}})
    (is (nil? (t/find (t/with-render (t/render [email-field {}]))
                      #(= :p (:tag %))))
        "untouched and unsubmitted — no error shown")
    (seed! {:editor {:draft {:email "x"}
                     :errors {:email "Not an email"}
                     :touched #{:email}}})
    (is (= "Not an email"
           (t/text (t/find (t/with-render (t/render [email-field {}]))
                           #(= :p (:tag %)))))
        "touched — the error appears")))

(deftest every-site-shape-on-the-page-renders-the-data-it-claims
  (testing "events-and-handlers.md spends a block per site shape. Each is
            rendered here rather than merely declared: a bad site is a
            RENDER-time refusal, so a fixture nothing renders proves nothing
            (rf2-kem4o)."
    (seed! {})

    (testing "block 1 — the paved path, whole"
      (is (= [:cart/add 42]
             (:on-click (t/attrs (t/find (t/render [add-to-cart-button {:product-id 42}])
                                         #(= :button (:tag %))))))))

    (testing "block 2 — the three everyday named markers ride unfilled"
      (is (= [[:form/typed :email ::v/value]
              [:prefs/set :dark ::v/checked]
              [:editor/key ::v/key]]
             (mapv (fn [site]
                     (let [attrs (t/attrs (t/render site))]
                       (or (:on-input attrs) (:on-change attrs) (:on-key-down attrs))))
                   projection-marker-sites))))

    (testing "block 4 — the general read door the markers are sugar for"
      (is (= [[:pane/scrolled [::v/read [:target :scrollLeft]]]
              [:editor/chord [::v/read :altKey]]]
             (mapv (fn [site]
                     (let [attrs (t/attrs (t/render site))]
                       (or (:on-scroll attrs) (:on-key-down attrs))))
                   general-read-sites))))

    (testing "blocks 6 and 7 — send the key, or branch at the site"
      (is (= [:picker/key-pressed ::v/key]
             (:on-key-down (t/attrs (t/find (t/render [key-pressed-site {}])
                                            #(= :input (:tag %)))))))
      (is (= {:rf.ui/opaque :fn}
             (:on-key-down (t/attrs (t/find (t/render [key-branch-site {}])
                                            #(= :input (:tag %))))))
          "a carrier is opaque in the tree — its EXISTENCE is the assertable fact"))

    (testing "block 12 — the uncontrolled escape has no :value on the door"
      (let [attrs (t/attrs (t/find (t/render [uncontrolled-cell-input {:id 3 :raw "old"}])
                                   #(= :input (:tag %))))]
        (is (= "old" (:default-value attrs)))
        (is (true? (:auto-focus attrs)))
        (is (nil? (:value attrs)) "no :value key — that is what uncontrolled means")
        (is (= [:cells/commit-edit 3 ::v/value] (:on-blur attrs)))))

    (testing "block 13 — the parent's intent reaches the child's own site"
      (is (= [:item/deleted 42]
             (:on-click (t/attrs (t/find (t/render icon-button-call)
                                         #(= :button (:tag %))))))))))

(deftest the-branch-handler-decides-with-application-state-in-hand
  (testing "events-and-handlers.md block 11 — pattern B's branch point is an
            ordinary event handler, so the page's claim is about what it
            DISPATCHES. Registering it and running it is the only way to know."
    (let [seen (atom [])]
      (rf/reg-fx :guide/captured-dispatch (fn [_ ev] (swap! seen conj ev)))
      (register-draft-key-handler!)
      (rf/with-fx-overrides {:dispatch :guide/captured-dispatch}
        (rf/dispatch-sync [:lines/draft-key 7 "Enter"])
        (rf/dispatch-sync [:lines/draft-key 7 "Escape"])
        (rf/dispatch-sync [:lines/draft-key 7 "x"]))
      (is (= [[:lines/label-committed 7] [:lines/draft-cancelled 7]] @seen)
          "Enter commits, Escape cancels, and an ordinary key does neither"))))

(deftest a-late-server-value-never-clobbers-what-the-user-typed
  (testing "forms.md block 4 — the whole point of the block is the `touched`
            check, and only running the handler shows it holds."
    (rf/reg-sub :guide/editor (fn [db _] (:editor db)))
    (register-article-loaded!)
    (rf/dispatch-sync [:rf/set-db {:editor {:draft {:title "mine"} :touched #{:title}}}])
    (rf/dispatch-sync [:editor/article-loaded "slug" {:title "server" :body "prose"}])
    (let [{:keys [draft baseline slug]} @(rf/subscribe [:guide/editor])]
      (is (= "mine" (:title draft)) "the touched key kept the user's text")
      (is (= "prose" (:body draft)) "the untouched key took the server value")
      (is (= {:title "server" :body "prose"} baseline) "and the server value is kept whole")
      (is (= "slug" slug)))))

(deftest the-submit-button-reads-the-two-derived-facts
  (testing "forms.md block 6 — `:disabled` is derived, never stored, and the
            label follows the busy fact."
    (seed! {:editor {:draft {:title "T"} :errors {}}})
    (let [ready (t/with-render (t/render [editor-actions {}]))]
      (is (= "Save" (t/text ready)))
      (is (false? (:disabled (t/attrs (t/find ready #(= :button (:tag %))))))
          "a valid, idle form submits"))
    (seed! {:editor {:draft {:title "T"} :errors {}}
            :mutations {:editor-save {:pending? true}}})
    (let [busy (t/with-render (t/render [editor-actions {}]))]
      (is (= "Saving…" (t/text busy)))
      (is (true? (:disabled (t/attrs (t/find busy #(= :button (:tag %))))))
          "and a save in flight closes the door"))))

(deftest the-conversion-pair-echoes-the-active-field-and-derives-the-other
  (testing "forms.md blocks 7 and 8 — the dataflow and the two fields are one
            claim, and it only holds when the registration actually runs."
    (rf/reg-sub :temp/f-display (fn [db _] (get-in db [:temp :f-text])))
    (register-temperature-dataflow!)
    (rf/dispatch-sync [:rf/set-db {:temp {:c-text "22" :f-text "71.6" :active :c}}])
    (let [inputs (t/find-all (t/with-render (t/render [temperature-inputs {}]))
                             #(= :input (:tag %)))]
      (is (= ["22" "71.6"] (mapv #(:value (t/attrs %)) inputs))
          "the active field echoes raw text; the other shows its own")
      (is (= [[:temp/c-typed ::v/value] [:temp/f-typed ::v/value]]
             (mapv #(:on-input (t/attrs %)) inputs))))
    (rf/reg-sub :guide/temp (fn [db _] (:temp db)))
    (rf/dispatch-sync [:temp/c-typed "30"])
    (is (= {:c-text "30" :f-text "92" :active :c} @(rf/subscribe [:guide/temp]))
        "typing sets the active field, its raw text, and the sibling's derived raw")))
