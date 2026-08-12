(ns re-frame.hicasso.examples.slice.l2-cljs-test
  "L2 — THE SLICE'S BODIES, AS SEMANTIC TREES (rf2-hic-025).

  `re-frame.hicasso.test/tree` runs one hook-free body under injected
  read fixtures and answers the Spec 004B structural tree it returned.
  No React, no element, no hook, no DOM — so what a row here proves is
  what the body MEANS, and nothing about what a user sees. The mounted
  suite is the other half and says so.

  ## Why the fixtures are exhaustive rather than convenient

  `:subs` refuses a read no fixture answers. That is the tier's sharpest
  instrument and this file leans on it deliberately: the fixture map for
  each body is the body's read set, written out, so **adding a read to a
  view reds this file** rather than passing quietly. A view's edge set is
  what decides when it re-renders, and a change to it is exactly the kind
  of change that should have to be acknowledged.

  ## A child boundary is a CALL, not a rendering

  `[editor {:slug …}]` inside `article-page` records a view-boundary node
  carrying the props the call site passed; its body does not run. So the
  rows below assert the CALL — that the page hands the editor the slug it
  read off the URL — and the editor's own contents are asserted by
  running the editor's own body. That is the ladder's definition of the
  tier rather than a limitation worked around."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso.examples.slice.events :as events]
            [re-frame.hicasso.examples.slice.routes :as routes]
            [re-frame.hicasso.examples.slice.subs :as subs]
            [re-frame.hicasso.examples.slice.views :as views]
            [re-frame.hicasso.test :as ht]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

(defn- tagged
  "The first node in `tree` with tag `tag`."
  [tree tag]
  (ht/find tree #(= tag (:tag %))))

(defn- classed
  "The first node in `tree` carrying `class` — the slice's own hook for
  finding a region, and the same hook the mounted suite uses, so the two
  tiers talk about the same parts of the page."
  [tree class]
  (ht/find tree #(= class (:class (ht/attrs %)))))

;; ---------------------------------------------------------------------------
;; The feed row — a link, a badge, a disclosure
;; ---------------------------------------------------------------------------

(def ^:private row-props
  {:slug "intents" :title "Intents are data" :published? true :tags ["intents" "data"]})

(defn- row-tree [{:keys [open?]}]
  (ht/tree [views/article-row row-props]
           {:subs {[::subs/tags-open? "intents"] (boolean open?)
                   [::subs/t :feed/tags]         "Tags"}}))

(deftest a-feed-row-links-through-routing-and-never-builds-a-url
  (let [a (tagged (row-tree {}) :a)]
    (is (= "/slice/article/intents" (:href (ht/attrs a)))
        "the href is the routing artefact's own synthesis — `views` names
         no URL anywhere, and this is the assertion that says so")
    (is (= "Intents are data" (ht/text a)))
    (is (= :re-frame.hicasso/navigate (first (:on-click (ht/attrs a))))
        "the click decision is DATA — a namespaced-keyword-headed vector
         `=` can see, which is what makes two renders of one link equal")))

(deftest a-published-row-carries-no-draft-badge
  (is (nil? (classed (row-tree {}) "draft-badge")))
  (is (some? (classed (ht/tree [views/article-row (assoc row-props :published? false)]
                               {:subs {[::subs/tags-open? "intents"] false
                                       [::subs/t :feed/tags]         "Tags"}})
                      "draft-badge"))))

(deftest the-disclosure-carries-its-own-instance-key
  (testing "closed: the toggle asks to open THIS row"
    (let [toggle (classed (row-tree {:open? false}) "tags-toggle")]
      (is (= "false" (:aria-expanded (ht/attrs toggle))))
      (is (= [::subs/tags-open? "intents" true] (:on-click (ht/attrs toggle)))
          "the slug is the instance key, so the intent names the row it
           came from — the whole of what h/reg-state buys")
      (is (nil? (classed (row-tree {:open? false}) "tag-list"))
          "and the tags themselves are not in the tree at all")))

  (testing "open: the toggle asks to close, and the tags are there"
    (let [tree   (row-tree {:open? true})
          toggle (classed tree "tags-toggle")]
      (is (= "true" (:aria-expanded (ht/attrs toggle))))
      (is (= [::subs/tags-open? "intents" false] (:on-click (ht/attrs toggle))))
      (let [tags (:children (classed tree "tag-list"))]
        (is (= ["intents" "data"] (mapv :key tags))
            "keyed by the tag itself")
        (is (= ["intents" "data"] (mapv ht/text tags)))))))

;; ---------------------------------------------------------------------------
;; The feed page — a keyed list, and the empty case
;; ---------------------------------------------------------------------------

(deftest the-list-keys-every-row-by-its-slug
  (let [rows [{:slug "a" :title "A" :published? true :tags []}
              {:slug "b" :title "B" :published? true :tags []}]
        tree (ht/tree [views/feed-page {}]
                      {:subs {[::subs/feed]           rows
                              [::subs/t :feed/heading] "Articles"}})
        kids (:children (tagged tree :ul))]
    (is (= ["a" "b"] (mapv :key kids))
        "the key is the domain id. A list keyed by its index reuses the
         wrong row the moment the order changes, and nothing on screen
         says so")
    (is (= ["a" "b"] (mapv (comp :slug :props) kids))
        "and each row is a CALL carrying its props — L2 records the call
         and does not run the child's body")))

(deftest an-empty-feed-reads-its-empty-string-and-nothing-else
  (let [tree (ht/tree [views/feed-page {}]
                      {:subs {[::subs/feed]            []
                              [::subs/t :feed/heading] "Articles"
                              [::subs/t :feed/empty]   "Nothing published yet."}})]
    (is (= "Nothing published yet." (ht/text (classed tree "feed-empty"))))
    (is (nil? (tagged tree :ul)))))

(deftest a-populated-feed-does-not-read-the-empty-string
  ;; The sharpest thing `:subs` does: the fixture map is the body's read
  ;; set, so LEAVING OUT `[::subs/t :feed/empty]` is an assertion that the
  ;; populated branch never reads it. If the string were hoisted out of
  ;; the `if`, this row would refuse.
  (is (some? (ht/tree [views/feed-page {}]
                      {:subs {[::subs/feed] [{:slug "a" :title "A"
                                              :published? true :tags []}]
                              [::subs/t :feed/heading] "Articles"}}))
      "a read inside a branch not taken contributes no edge"))

;; ---------------------------------------------------------------------------
;; The editor — controlled fields, the intent shapes, the error region
;; ---------------------------------------------------------------------------

(def ^:private editor-base-subs
  {[::subs/draft "intents"]      {:title "T" :body "B" :published? true}
   [::subs/revision "intents"]   0
   [::subs/dirty? "intents"]     false
   [::subs/t :editor/heading]    "Edit"
   [::subs/t :editor/title]      "Title"
   [::subs/t :editor/body]       "Body"
   [::subs/t :editor/published]  "Published"
   [::subs/t :editor/save]       "Save"
   [::subs/t :editor/discard]    "Discard changes"})

(defn- editor-tree [save extra]
  (ht/tree [views/editor {:slug "intents"}]
           {:subs (merge editor-base-subs
                         {[::subs/save-state "intents"] save}
                         extra)}))

(deftest the-text-fields-are-controlled-by-the-model-alone
  (let [tree  (editor-tree {:status :idle :problem nil} {})
        title (classed tree "field-title")
        body  (classed tree "field-body")]
    (is (= "T" (:value (ht/attrs title))))
    (is (= "B" (:value (ht/attrs body))))
    (is (= [::events/edit "intents" :title :re-frame.hicasso/value]
           (:on-input (ht/attrs title)))
        "POSITIONAL, and it has to be: `::h/value` substitutes at the
         intent vector's top level only, so the canonical `[<id> {<k>
         <v>}]` payload shape would carry the marker keyword itself into
         app-db — silently. See the events namespace docstring.")
    (is (= [::events/edit "intents" :body :re-frame.hicasso/value]
           (:on-input (ht/attrs body))))))

(deftest the-reset-trigger-rides-both-fields
  (is (= 0 (ht/revision [:input {:value "T" :re-frame.hicasso/revision 0}]))
      "the kit reads the trigger off an authored form")
  (let [tree (editor-tree {:status :idle :problem nil} {[::subs/revision "intents"] 3})]
    ;; The tree records the marker as an ordinary attribute, which is what
    ;; makes it assertable here: both fields carry the SAME revision, so a
    ;; discard re-baselines the whole form rather than half of it.
    (is (= [3 3] (mapv #(:re-frame.hicasso/revision (ht/attrs %))
                       [(classed tree "field-title") (classed tree "field-body")])))))

(deftest the-checkbox-takes-the-checked-marker
  (let [box (ht/find (editor-tree {:status :idle :problem nil} {})
                     #(= "checkbox" (:type (ht/attrs %))))]
    (is (true? (:checked (ht/attrs box))))
    (is (= [::events/toggle-published "intents" :re-frame.hicasso/checked]
           (:on-change (ht/attrs box))))))

(deftest submitting-the-form-is-the-save-intent
  (let [form (tagged (editor-tree {:status :idle :problem nil} {}) :form)]
    (is (= [::events/save {:slug "intents"}] (:on-submit (ht/attrs form)))
        "`:on-submit` auto-prevents (authoring.md's census-weighted
         default), so the page does not navigate and the handler is the
         whole of what a submit means here")))

(deftest discard-is-disabled-until-something-was-typed
  (is (true? (:disabled (ht/attrs (classed (editor-tree {:status :idle :problem nil} {})
                                           "discard")))))
  (is (false? (:disabled (ht/attrs (classed (editor-tree {:status :idle :problem nil}
                                                         {[::subs/dirty? "intents"] true})
                                            "discard"))))
      "`false` and not absent: 004B drops a nil attribute value and keeps a
       false one, so the enabled case is a value rather than a hole"))

(deftest the-error-region-appears-only-on-a-refusal-and-offers-a-retry
  (testing "idle: no region, and the danger token is never read"
    ;; Again the fixture map is the assertion: no `[::subs/token :danger]`
    ;; entry, so a body that read one would refuse.
    (is (nil? (classed (editor-tree {:status :idle :problem nil} {}) "save-problem"))))

  (testing "failed: the problem KEYWORD becomes a sentence here"
    (let [tree (editor-tree {:status :failed :problem :problem/title-taken}
                            {[::subs/token :danger] "rgb(176, 32, 32)"
                             [::subs/t :problem/title-taken] "That title is already used."
                             [::subs/t :editor/retry] "Try again"})
          region (classed tree "save-problem")]
      (is (= "alert" (:role (ht/attrs region)))
          "an error region a screen reader is not told about is an error
           region a screen reader user does not get")
      (is (= "rgb(176, 32, 32)" (:color (:style (ht/attrs region))))
          "the colour is a THEME TOKEN read through a sub, not a class
           name promising something about a stylesheet")
      (is (= [::events/save {:slug "intents"}]
             (:on-click (ht/attrs (classed tree "retry"))))
          "retry is the same intent as save — a failed mutation needs no
           second event, and inventing one is how the two drift"))))

(deftest saving-disables-the-button-and-renames-it
  (let [tree (editor-tree {:status :saving :problem nil}
                          {[::subs/t :editor/saving] "Saving…"})
        save (classed tree "save")]
    (is (true? (:disabled (ht/attrs save))))
    (is (= "Saving…" (ht/text save))))
  ;; And the idle fixture map carries `:editor/save` but NOT
  ;; `:editor/saving`, so the two branches are proved distinct by the
  ;; fixtures rather than by the strings.
  (is (= "Save" (ht/text (classed (editor-tree {:status :idle :problem nil} {}) "save")))))

;; ---------------------------------------------------------------------------
;; The article page — the route's params reach the editor
;; ---------------------------------------------------------------------------

(deftest the-page-hands-the-editor-the-slug-it-read-off-the-url
  (let [tree (ht/tree [views/article-page {}]
                      {:subs {[:rf.route/params]        {:slug "intents"}
                              [::subs/article "intents"] {:slug "intents" :title "Intents are data"}
                              [::subs/t :article/back]   "All articles"}})
        call (ht/find tree #(some? (:view-id %)))]
    (is (= "re-frame.hicasso.examples.slice.views/editor" (:view-id call)))
    (is (= {:slug "intents"} (:props call))
        "the call is recorded; the editor's body does not run here")))

(deftest a-slug-nobody-published-is-a-page-rather-than-an-error
  (let [tree (ht/tree [views/article-page {}]
                      {:subs {[:rf.route/params]         {:slug "nonsense"}
                              [::subs/article "nonsense"] nil
                              [::subs/t :article/back]    "All articles"
                              [::subs/t :article/missing] "No such article."}})]
    (is (= "No such article." (ht/text (classed tree "article-missing"))))
    (is (nil? (ht/find tree #(some? (:view-id %))))
        "and no editor is called for an article that does not exist")))

;; ---------------------------------------------------------------------------
;; The shell — theme tokens on the surface, and the routed pane
;; ---------------------------------------------------------------------------

(defn- shell-tree [route]
  (ht/tree [views/app {}]
           {:subs {[:rf.route/id]              route
                   [::subs/token :surface]     "rgb(18, 21, 26)"
                   [::subs/token :ink]         "rgb(232, 234, 237)"
                   [::subs/t :app/pane-error]  "This page could not be displayed."}}))

(deftest the-shell-paints-from-tokens-and-routes-the-pane
  (let [tree     (shell-tree routes/article)
        boundary (ht/find tree #(contains? (:props %) :reset-key))]
    (is (= {:background "rgb(18, 21, 26)" :color "rgb(232, 234, 237)"}
           (:style (ht/attrs (tagged tree :main)))))

    (testing "the routed pane sits under h/error-boundary"
      (is (some? boundary)
          "recorded as the CALL it is — h/error-boundary is a legal hiccup
           head and its own body does not run here")
      (is (= routes/article (:reset-key (ht/attrs boundary)))
          "the reset key is the ROUTE, so navigating away from a pane that
           threw clears the caught failure — the retry is the
           application's, taken when the user does something different,
           rather than the boundary's to guess")
      (is (= [:p.pane-error {:role "alert"} "This page could not be displayed."]
             (:fallback (ht/attrs boundary)))
          "the fallback is INERT MARKUP, and asserting it as data is the
           only honest thing this tier can say about it: driving it needs
           something to throw, which a testbed does not carry. Its
           sentence is READ THROUGH A SUB like every other — the fixture
           above is what proves it, because a hardcoded string would need
           no fixture and this row would refuse the one it was given"))

    (testing "the pane itself is the article page"
      (is (= "re-frame.hicasso.examples.slice.views/article-page"
             (:view-id (first (:children boundary)))))))

  (testing "an unresolved route falls back to the feed rather than blanking"
    (let [boundary (ht/find (shell-tree nil) #(contains? (:props %) :reset-key))]
      (is (= "re-frame.hicasso.examples.slice.views/feed-page"
             (:view-id (first (:children boundary))))))))
