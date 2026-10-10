(ns re-frame.bench.fresco.front.census-article-editor-cljs-test
  "THE `:&` MERGE, DEMONSTRATED ON A CENSUS-REAL SCREEN.

  HD-023's stated risk is a taste ruling with no control arm left to
  falsify it; this file is its mitigation. It does not assert that one
  merge spelling is better: it ports the RealWorld article editor's four
  controlled fields (`examples/real-apps/realworld_resources/article_editor.cljs`)
  into one helper that carries the call site's remainder through `:&`, and
  asserts both renderings build the same elements and dispatch the same
  intents, so the side by side is about authoring and nothing else."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.bench.fresco.front.codec :as rf.bench.fresco.front.codec]
            [re-frame.bench.fresco.front.controlled :as rf.bench.fresco.front.controlled]
            [re-frame.bench.fresco.front.intent :as rf.bench.fresco.front.intent]))

(use-fixtures :each {:before (fn [] (rf.bench.fresco.front.codec/reset-caches!))})

(def ^:private draft
  {:title "A title" :description "A description" :body "A body" :tagList "a,b"})

(def ^:private errors
  {:title nil :description "can't be blank" :body nil :tagList nil})

;; ---------------------------------------------------------------------------
;; BEFORE — the census's own shape, transliterated
;; ---------------------------------------------------------------------------
;;
;; Intent vectors replace the four `#(dispatch …)` closures, because that
;; substitution is a separate question and not what this file is measuring. Everything
;; else is the corpus's shape: one attribute map per field, written out per field.

(defn- inline-fieldset [busy?]
  [:fieldset
   [:fieldset.form-group
    [:input.form-control.form-control-lg
     {:type "text" :name "title" :placeholder "Article Title" :data-testid "editor-title"
      :value (:title draft) :disabled busy?
      :on-blur  [:editor/blur-field :title]
      :on-input [:editor/edit-field :title :re-frame.fresco/value]}]
    (when (:title errors) [:div.error-messages (:title errors)])]
   [:fieldset.form-group
    [:input.form-control
     {:type "text" :name "description" :placeholder "What's this article about?"
      :data-testid "editor-description"
      :value (:description draft) :disabled busy?
      :on-blur  [:editor/blur-field :description]
      :on-input [:editor/edit-field :description :re-frame.fresco/value]}]
    (when (:description errors) [:div.error-messages (:description errors)])]
   [:fieldset.form-group
    [:input.form-control
     {:type "text" :name "body" :placeholder "Write your article (in markdown)"
      :data-testid "editor-body"
      :value (:body draft) :disabled busy?
      :on-blur  [:editor/blur-field :body]
      :on-input [:editor/edit-field :body :re-frame.fresco/value]}]
    (when (:body errors) [:div.error-messages (:body errors)])]
   [:fieldset.form-group
    [:input.form-control
     {:type "text" :name "tags" :placeholder "Enter tags (comma-separated)"
      :data-testid "editor-tags"
      :value (:tagList draft) :disabled busy?
      :on-blur  [:editor/blur-field :tagList]
      :on-input [:editor/edit-field :tagList :re-frame.fresco/value]}]
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
                         :on-input [:editor/edit-field id :re-frame.fresco/value]}]
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

  Read through [[re-frame.bench.fresco.front.controlled/element-tag]]
  rather than `.-type`, because a controlled field's element type is the
  composition shadow's component and the tag it renders is what this
  question is about. Every prop these rows go on to read —
  the static attributes, `onInput`, `onBlur` — is on that element as
  written; only the element type is the shadow's rather than the tag."
  [e]
  (into [] (mapcat (fn [group] (filter #(and (some? %)
                                             (= "input" (rf.bench.fresco.front.controlled/element-tag %)))
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
  (rf.bench.fresco.front.intent/with-frame (fn [ev] (swap! dispatched conj ev))
                     (fn [] (rf.bench.fresco.front.codec/as-element hiccup))))

;; ---------------------------------------------------------------------------
;; The port is the same screen
;; ---------------------------------------------------------------------------

(deftest the-two-renderings-produce-the-same-four-controlled-inputs
  ;; Every static attribute, including the class the tag shorthand composed
  ;; and the busy rule, idle and busy.
  (doseq [busy? [false true]]
    (let [seen   (atom [])
          before (inputs (render (inline-fieldset busy?) seen))
          after  (inputs (render (merged-fieldset busy?) seen))]
      (is (= [4 (mapv static-attrs before)] [(count after) (mapv static-attrs after)])
          (str "busy? = " busy?)))))

(deftest the-two-renderings-dispatch-the-same-intents
  (let [fire! (fn [fieldset]
                (let [seen (atom [])
                      els  (inputs (render fieldset seen))]
                  (doseq [e els] ((aget (.-props e) "onInput") #js {:target #js {:value "typed"}}))
                  (doseq [e els] ((aget (.-props e) "onBlur") #js {:target #js {}}))
                  @seen))]
    (is (= [[:editor/edit-field :title "typed"]
            [:editor/edit-field :description "typed"]
            [:editor/edit-field :body "typed"]
            [:editor/edit-field :tagList "typed"]
            [:editor/blur-field :title]
            [:editor/blur-field :description]
            [:editor/blur-field :body]
            [:editor/blur-field :tagList]]
           (fire! (inline-fieldset false))
           (fire! (merged-fieldset false))))))

(deftest the-error-slot-still-appears-for-exactly-the-field-that-has-one
  ;; R-A5: one field is in error, so one slot renders.
  (let [errs (into [] (mapcat (fn [g] (filter #(and (some? %) (= "div" (.-type %))) (children-of g))))
                   (children-of (render (merged-fieldset false) (atom []))))]
    (is (= ["can't be blank"] (mapv #(aget (.-props %) "children") errs)))))

(deftest the-helper-does-not-have-to-defend-itself
  ;; A call site forwarding a whole remainder cannot reach the controlled
  ;; contract, because the helper wrote those keys as literals — and
  ;; everything the caller was entitled to still arrives.
  (let [seen (atom [])
        e    (first (inputs (render [:fieldset
                                     (field {:id :title :busy? false
                                             :value      "CLOBBER"
                                             :disabled   true
                                             :on-input   [:hostile/edit]
                                             :data-testid "editor-title"})]
                                    seen)))]
    ((aget (.-props e) "onInput") #js {:target #js {:value "typed"}})
    (is (= ["A title" false "editor-title" [[:editor/edit-field :title "typed"]]]
           [(aget (.-props e) "value") (aget (.-props e) "disabled") (aget (.-props e) "data-testid") @seen]))))
