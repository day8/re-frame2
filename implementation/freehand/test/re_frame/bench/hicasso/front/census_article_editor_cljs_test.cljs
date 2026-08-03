(ns re-frame.bench.hicasso.front.census-article-editor-cljs-test
  "THE `:&` MERGE, DEMONSTRATED ON A CENSUS-REAL SCREEN (rf2-2rtt6.36).

  The design review that produced HD-023 carried its own stated risk, and
  it is worth repeating rather than softening: *all four proposals are
  taste rulings dressed as deletions, and the instrument that would
  falsify them lost its control arm.* Its mitigation is this file. The
  claim 'one merge spelling is better' is not asserted here; a real
  screen is ported and the two renderings are put side by side, with the
  DOM they produce asserted identical so the comparison is about
  authoring and nothing else.

  ## The screen

  `examples/real-apps/realworld_resources/article_editor.cljs:496-522` —
  the RealWorld article editor's four form fields, which the fitness
  harness names in five separate rows (R-A5 validation-display gating,
  R-A10 busy discipline, census row 3a event-value extraction, 3b
  `preventDefault` handlers, 1b parameterised reads). Four fields, each
  controlled, each disabled off the same in-flight write, each with its
  own error slot. Verbatim, one field of four:

      [:input.form-control
       {:type \"text\" :name \"description\" :placeholder \"What's this article about?\"
        :data-testid \"editor-description\"
        :value (:description draft) :disabled busy?
        :on-blur #(dispatch [:editor/blur-field :description])
        :on-change #(dispatch [:editor/edit-field :description (.. % -target -value)])}]

  Four of those, differing in **three tokens each** — the field key, the
  placeholder, and the test id — and repeating the controlled contract,
  the busy rule and both handlers at every site. That repetition is the
  thing a wrapper deletes, and it is the reason the corpus has it: the
  wrapper is not free to write.

  ## Why the predecessor cannot write the wrapper cheaply

  Forwarding a caller's remainder onto an internal controlled `input`
  needs the door-preserving spread form, and choosing the ordinary one
  instead is a **silent** loss of caret and IME protection — recorded
  twice in the predecessor's own docs as a wall with no error attached
  ('Dynamic map on controlled input without spread-safe | forfeits door
  proof'). So the author must know that a third form exists, know which
  of three applies here, and get it right with nothing checking. A
  wrapper whose correctness depends on the caller picking the right merge
  syntax is a wrapper most authors correctly decline to write.

  ## What is asserted

  Both renderings are built and their elements compared attribute by
  attribute, and both handlers are fired and their dispatched intents
  compared. If the ported screen were not the same screen, the diff in
  the PR would be measuring two different things."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.controlled :as controlled]
            [re-frame.bench.hicasso.front.intent :as intent]))

(use-fixtures :each {:before (fn [] (codec/reset-caches!))})

(def ^:private draft
  {:title "A title" :description "A description" :body "A body" :tagList "a,b"})

(def ^:private errors
  {:title nil :description "can't be blank" :body nil :tagList nil})

;; ---------------------------------------------------------------------------
;; BEFORE — the census's own shape, transliterated
;; ---------------------------------------------------------------------------
;;
;; Intent vectors replace the four `#(dispatch …)` closures, because that
;; substitution is rf2-2rtt6.8's and is not what this file is measuring. Everything
;; else is the corpus's shape: one attribute map per field, written out per field.

(defn- inline-fieldset [busy?]
  [:fieldset
   [:fieldset.form-group
    [:input.form-control.form-control-lg
     {:type "text" :name "title" :placeholder "Article Title" :data-testid "editor-title"
      :value (:title draft) :disabled busy?
      :on-blur  [:editor/blur-field :title]
      :on-input [:editor/edit-field :title :re-frame.hicasso/value]}]
    (when (:title errors) [:div.error-messages (:title errors)])]
   [:fieldset.form-group
    [:input.form-control
     {:type "text" :name "description" :placeholder "What's this article about?"
      :data-testid "editor-description"
      :value (:description draft) :disabled busy?
      :on-blur  [:editor/blur-field :description]
      :on-input [:editor/edit-field :description :re-frame.hicasso/value]}]
    (when (:description errors) [:div.error-messages (:description errors)])]
   [:fieldset.form-group
    [:input.form-control
     {:type "text" :name "body" :placeholder "Write your article (in markdown)"
      :data-testid "editor-body"
      :value (:body draft) :disabled busy?
      :on-blur  [:editor/blur-field :body]
      :on-input [:editor/edit-field :body :re-frame.hicasso/value]}]
    (when (:body errors) [:div.error-messages (:body errors)])]
   [:fieldset.form-group
    [:input.form-control
     {:type "text" :name "tags" :placeholder "Enter tags (comma-separated)"
      :data-testid "editor-tags"
      :value (:tagList draft) :disabled busy?
      :on-blur  [:editor/blur-field :tagList]
      :on-input [:editor/edit-field :tagList :re-frame.hicasso/value]}]
    (when (:tagList errors) [:div.error-messages (:tagList errors)])]])

;; ---------------------------------------------------------------------------
;; AFTER — one helper that owns the contract, `:&` carrying the remainder
;; ---------------------------------------------------------------------------
;;
;; `field` owns exactly the things that must not vary: the controlled pair, the
;; busy rule, the blur intent and the error slot. Everything a call site still
;; needs to say rides through `:&` as ONE key, and the owned-literal law means the
;; helper does not have to defend itself against what arrives there.

(defn- field [{:keys [id busy?] :as attrs}]
  [:fieldset.form-group
   [:input.form-control {:& (dissoc attrs :id :busy?)
                         :value    (get draft id)
                         :disabled busy?
                         :on-blur  [:editor/blur-field id]
                         :on-input [:editor/edit-field id :re-frame.hicasso/value]}]
   (when-some [e (get errors id)] [:div.error-messages e])])

(defn- merged-fieldset [busy?]
  [:fieldset
   (field {:id :title :busy? busy? :class "form-control-lg"
           :type "text" :name "title" :placeholder "Article Title"
           :data-testid "editor-title"})
   (field {:id :description :busy? busy?
           :type "text" :name "description" :placeholder "What's this article about?"
           :data-testid "editor-description"})
   (field {:id :body :busy? busy?
           :type "text" :name "body" :placeholder "Write your article (in markdown)"
           :data-testid "editor-body"})
   (field {:id :tagList :busy? busy?
           :type "text" :name "tags" :placeholder "Enter tags (comma-separated)"
           :data-testid "editor-tags"})])

;; ---------------------------------------------------------------------------
;; Reading elements back
;; ---------------------------------------------------------------------------

(defn- children-of [e]
  (let [c (aget (.-props e) "children")]
    (cond (array? c) (vec c) (nil? c) [] :else [c])))

(defn- inputs
  "Every `<input>` element in a rendered fieldset, in document order.

  Read through [[re-frame.bench.hicasso.front.controlled/element-tag]]
  rather than `.-type`, because a controlled field's element type is the
  composition shadow's component and the tag it renders is what this
  question is about (rf2-digtt). Every prop these rows go on to read —
  the static attributes, `onInput`, `onBlur` — is on that element
  unchanged; only the type moved."
  [e]
  (into [] (mapcat (fn [group] (filter #(and (some? %)
                                             (= "input" (controlled/element-tag %)))
                                       (children-of group))))
        (children-of e)))

(defn- static-attrs
  "An element's props with the handlers removed — the half that is comparable
  by value. The handlers are compared separately, by what they dispatch."
  [e]
  (into (sorted-map)
        (remove (fn [[_ v]] (fn? v)))
        (js->clj (.-props e))))

(defn- render [hiccup dispatched]
  (intent/with-frame (fn [ev] (swap! dispatched conj ev))
                     (fn [] (codec/as-element hiccup))))

;; ---------------------------------------------------------------------------
;; The port is the same screen
;; ---------------------------------------------------------------------------

(deftest the-two-renderings-produce-the-same-four-controlled-inputs
  (doseq [busy? [false true]]
    (testing (str "busy? = " busy?)
      (let [seen   (atom [])
            before (inputs (render (inline-fieldset busy?) seen))
            after  (inputs (render (merged-fieldset busy?) seen))]
        (is (= 4 (count before) (count after)))
        (doseq [[b a] (map vector before after)]
          (is (= (static-attrs b) (static-attrs a))
              "every static attribute, including the class the tag shorthand
               composed and the busy rule, is identical"))))))

(deftest the-two-renderings-dispatch-the-same-intents
  (let [seen-b (atom [])
        seen-a (atom [])
        before (inputs (render (inline-fieldset false) seen-b))
        after  (inputs (render (merged-fieldset false) seen-a))]
    (doseq [e before] ((aget (.-props e) "onInput") #js {:target #js {:value "typed"}}))
    (doseq [e before] ((aget (.-props e) "onBlur") #js {:target #js {}}))
    (doseq [e after] ((aget (.-props e) "onInput") #js {:target #js {:value "typed"}}))
    (doseq [e after] ((aget (.-props e) "onBlur") #js {:target #js {}}))
    (is (= @seen-b @seen-a))
    (is (= [[:editor/edit-field :title "typed"]
            [:editor/edit-field :description "typed"]
            [:editor/edit-field :body "typed"]
            [:editor/edit-field :tagList "typed"]
            [:editor/blur-field :title]
            [:editor/blur-field :description]
            [:editor/blur-field :body]
            [:editor/blur-field :tagList]]
           @seen-a))))

(deftest the-error-slot-still-appears-for-exactly-the-field-that-has-one
  (let [seen  (atom [])
        after (children-of (render (merged-fieldset false) seen))
        errs  (into [] (mapcat (fn [g] (filter #(and (some? %)
                                                     (= "div" (.-type %)))
                                               (children-of g))))
                    after)]
    (is (= 1 (count errs)) "R-A5: one field is in error, so one slot renders")
    (is (= "can't be blank" (aget (.-props (first errs)) "children")))))

;; ---------------------------------------------------------------------------
;; The property the predecessor's wrapper cannot have
;; ---------------------------------------------------------------------------

(deftest the-helper-does-not-have-to-defend-itself
  (testing "the reason this wrapper is writable at all. A call site that
            forwards a whole remainder — a props map it received, a theme's
            part attrs, anything dynamic — cannot reach the controlled
            contract, because the law is unconditional and the helper wrote
            those keys as literals. There is no third form to pick, and no
            silent forfeit for picking wrong."
    (let [seen (atom [])
          e    (first (inputs (render [:fieldset
                                       (field {:id :title :busy? false
                                               :value      "CLOBBER"
                                               :disabled   true
                                               :on-input   [:hostile/edit]
                                               :data-testid "editor-title"})]
                                      seen)))]
      (is (= "A title" (aget (.-props e) "value")))
      (is (false? (aget (.-props e) "disabled")))
      (is (= "editor-title" (aget (.-props e) "data-testid"))
          "and everything the caller was entitled to still arrives")
      ((aget (.-props e) "onInput") #js {:target #js {:value "typed"}})
      (is (= [[:editor/edit-field :title "typed"]] @seen)))))
