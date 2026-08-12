(ns re-frame.hicasso.forms-dom-cljs-test
  "THE FORMS MODULE ON A REAL REACT ROOT OVER A REAL DOM (rf2-sh56).

  The claims the node lane cannot decide: whether a commit keeps the
  node, where the focus and the caret are, what order a real blur arrives
  in, whether a draft survives a real remount — and the row this file
  exists for, whether `::h/revision` is doing any work.

  ## The revision row is an EXPERIMENT, and it is not the recipes' one

  `rf2-hic-051`'s mounted suite built a session whose draft ended EQUAL
  to the committed value, because in that application ending a session
  moved nothing else the field read. **That arrangement does not
  transplant**, and saying so is part of the deliverable: this module
  holds the draft in a record the field's own subscription reads, so
  ending a session always moves that read, always re-renders, and always
  re-commits. The recipes' experiment would test green here while proving
  nothing.

  What is left is the case the module cannot repair on its own:
  [[a-foreign-write-with-no-session-open-is-repaired-by-the-revision-and-by-nothing-else]].
  A password manager, an extension or an autofill writes into an
  untouched box; no draft exists, no `app-db` value moves, so nothing the
  field reads changes and nothing re-renders. A revision bump is the only
  thing left that can re-assert the model — which is exactly what the
  prop is for, and the row builds the arrangement where it is the only
  candidate rather than one of several.

  ## Browser lane

  Every row needs a real document and a real React DOM. `:node-test`
  compiles this namespace too (`cljs-test$` matches `-dom-cljs-test`), and
  each row degrades there to a STATED skip rather than a false green."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.fx]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.forms :as forms]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The screen — the chapter's own arrangement
;; ---------------------------------------------------------------------------
;;
;; Registered above the fixture, which captures its registrar baseline
;; when it is constructed.

(def ^:private todo 7)
(def ^:private committed "Buy milk")

(rf/reg-event ::seed
  (fn [_ _] {:db {:todo     {todo {:title committed :title-revision 0}}
                  :showing? true}}))

(rf/reg-sub ::title (fn [db [_ id]] (get-in db [:todo id :title])))
(rf/reg-sub ::title-revision (fn [db [_ id]] (get-in db [:todo id :title-revision])))
(rf/reg-sub ::showing? (fn [db _] (:showing? db)))

(rf/reg-event ::title-committed
  {:doc "The caller's `:on-commit`, in the chapter's shape: accept a
         trimmed candidate, refuse a blank one, advance the revision
         either way."}
  (fn [{:keys [db]} [_ id candidate]]
    (let [title (clojure.string/trim candidate)]
      (if (clojure.string/blank? title)
        {:db (update-in db [:todo id :title-revision] inc)}
        {:db (-> db
                 (assoc-in [:todo id :title] title)
                 (update-in [:todo id :title-revision] inc))}))))

(rf/reg-event ::settle
  {:doc "An external reset — value and revision together."}
  (fn [{:keys [db]} [_ id title]]
    {:db (-> db
             (assoc-in [:todo id :title] title)
             (update-in [:todo id :title-revision] inc))}))

(rf/reg-event ::bump-revision
  {:doc "A revision move with NOTHING else changing — the arrangement that
         isolates the prop."}
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:todo id :title-revision] inc)}))

(rf/reg-event ::hide (fn [{:keys [db]} _] {:db (assoc db :showing? false)}))
(rf/reg-event ::show (fn [{:keys [db]} _] {:db (assoc db :showing? true)}))

(h/defview title-field
  "The chapter's own call site: a parent boundary reads the committed
  value and its revision and hands both down as props."
  [{:keys [id]}]
  [forms/buffered-field
   {:control     [:todo id :title]
    :value       (h/sub [::title id])
    ::h/revision (h/sub [::title-revision id])
    :on-commit   [::title-committed id]
    :id          "todo-title"
    :class       "title"
    :placeholder "What needs doing?"}])

(h/defview screen
  "The field, and a switch that takes it off the page — its own boundary,
  so toggling the switch does not re-render the field's parent for a
  reason unrelated to the field."
  [_]
  [:main.screen
   (when (h/sub [::showing?])
     [title-field {:id todo}])])

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; The MAP shape, because every row is `async`: `cljs.test` refuses an
     ;; async test under a fn-form fixture and aborts the namespace.
     :async?        true
     :init-fn       (fn []
                      ;; React's `act` queue is not the browser's
                      ;; scheduler, and every reading here is taken
                      ;; outside it.
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false))}))

;; ---------------------------------------------------------------------------
;; Reading and driving the page
;; ---------------------------------------------------------------------------

(defn- browser? [] (exists? js/document))

(defn- skip! [why]
  (is true (str "a mounted buffered field needs a real React DOM — " why)))

(defn- field [m] (.querySelector (:container m) "#todo-title"))

(defn- value-setter []
  (.-set (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")))

(defn- type-into!
  "Type `v` into `n` — a foreign write followed by a real `input` event,
  which is what a keystroke is from React's side.

  Written through the PROTOTYPE's own `value` setter because React
  patches the instance setter to maintain its change tracker: a plain
  `set!` updates the tracker too, after which React reads the node as
  already agreeing and the `input` below reaches no handler."
  [m n v]
  (.call (value-setter) n v)
  (.dispatchEvent n (js/InputEvent. "input" #js {:bubbles true}))
  (hm/settle! m))

(defn- drift!
  "Move the field's value WITHOUT telling React — the foreign write an
  autofill, a password manager or a non-React script performs. The same
  prototype setter as [[type-into!]] and deliberately NO `input` event:
  nothing is dispatched, so no handler runs and the model never learns."
  [n v]
  (.call (value-setter) n v)
  nil)

(defn- press! [m n key]
  (.dispatchEvent n (js/KeyboardEvent. "keydown" #js {:key key :bubbles true}))
  (hm/settle! m))

(defn- blur! [m n] (.blur n) (hm/settle! m))

(defn- send! [m event-v] (rf/dispatch-sync event-v {:frame (:frame m)}))
(defn- title-of [m] (get-in (rf/app-db-value (:frame m)) [:todo todo :title]))
(defn- record-of [m]
  (rf/subscribe-once [forms/drafts [:todo todo :title]] {:frame (:frame m)}))

(defn- mount! [] (hm/mount! [screen {}] {:initial-events [[::seed]]}))

(defn- finish [m done]
  (-> (hm/unmount! m) (hm/assert-clean!) (.then done)))

;; ---------------------------------------------------------------------------
;; The commit, on a real node
;; ---------------------------------------------------------------------------

(deftest a-real-commit-moves-the-value-and-keeps-the-node
  (async done
    (if-not (browser?)
      (do (skip! "Enter on a real field") (done))
      (let [m (mount!)
            n (field m)]
        (is (= committed (.-value n))
            "populated before anybody types — the draft is absent, so the
             field shows `:value` and the first keystroke lands on top of
             the committed text")
        (is (= "What needs doing?" (.getAttribute n "placeholder"))
            "and the pass-through props really reached the element")
        (.focus n)
        (type-into! m n "Buy oat milk")
        (is (= "Buy oat milk" (.-value n)))
        (is (= committed (title-of m))
            "the committed title has NOT moved — the draft is in front of
             it, which is what buffered means")
        (press! m n "Enter")
        (is (= "Buy oat milk" (title-of m)))
        (is (nil? (record-of m)) "and the session is over")
        (is (identical? n (field m))
            "the SAME DOM node. A reset spelled as a remount would pass a
             value assertion and lose the focus, the caret and any IME
             composition with it")
        (is (identical? n js/document.activeElement)
            "and focus is still in the box")
        (finish m done)))))

(deftest escape-reverts-a-still-mounted-field-and-the-late-blur-commits-nothing
  ;; The cancel-then-blur race in its harder arrangement: this field STAYS
  ;; on the page after the cancel, so the blur that follows reaches a live
  ;; handler rather than a torn-down one.
  (async done
    (if-not (browser?)
      (do (skip! "Escape then blur, in order, on a live field") (done))
      (let [m (mount!)
            n (field m)]
        (.focus n)
        (type-into! m n "Buy oat milk")
        (press! m n "Escape")
        (is (= committed (.-value n))
            "the box shows `:value` again, on the node it already had")
        (is (nil? (record-of m)))
        (blur! m n)
        (is (= committed (title-of m))
            "and the trailing blur found no session to commit — *cancel
             beats the late blur*, answered by the model")
        (is (identical? n (field m)))
        (finish m done)))))

(deftest a-refused-commit-puts-the-committed-value-back-in-the-box
  ;; Same-value blindness, the SCREEN half. The user submits a blank
  ;; draft, the caller refuses it and keeps the title it had. Nothing the
  ;; caller wrote is different, and the box must still stop showing the
  ;; text that was refused.
  (async done
    (if-not (browser?)
      (do (skip! "a refused commit, read off a real box") (done))
      (let [m (mount!)
            n (field m)]
        (.focus n)
        (type-into! m n "   ")
        (is (= "   " (.-value n)))
        (press! m n "Enter")
        (is (= committed (title-of m)) "the caller refused it")
        (is (= committed (.-value n))
            "and the box is back to the committed value rather than left
             holding blank text that looks accepted")
        (is (identical? n (field m)))
        (finish m done)))))

(deftest an-external-reset-replaces-a-live-draft-on-the-same-node
  (async done
    (if-not (browser?)
      (do (skip! "an async settle landing under a live edit") (done))
      (let [m (mount!)
            n (field m)]
        (.focus n)
        (type-into! m n "half typed")
        (send! m [::settle todo "Buy almond milk"])
        (hm/settle! m)
        (is (= "Buy almond milk" (.-value n))
            "the reset made the draft ineligible immediately — no
             render-time dispatch, and no turn in between showing the
             text it discarded")
        (is (identical? n (field m)) "on the node it already had")
        (is (identical? n js/document.activeElement)
            "and without taking the focus out of the box")
        (blur! m n)
        (is (= "Buy almond milk" (title-of m))
            "the blur that follows carries the NEW revision, meets a
             record written under the old one, and commits nothing")
        (finish m done)))))

(deftest a-foreign-write-with-no-session-open-is-repaired-by-the-revision-and-by-nothing-else
  ;; THE EXPERIMENT. It is written so it can fail.
  ;;
  ;; Everywhere else in this module the record's own subscription does the
  ;; work: ending a session moves a value the field reads, so the boundary
  ;; re-renders and the commit re-asserts the model for free. This is the
  ;; one arrangement where that is not true — no session ever opened, and
  ;; the caller moves NOTHING except the revision. If the box repaired
  ;; itself here without the prop, `buffered-field` would be forwarding
  ;; `::h/revision` for nothing and the honest deliverable would be to say
  ;; so.
  (async done
    (if-not (browser?)
      (do (skip! "a foreign write React never saw") (done))
      (let [m (mount!)
            n (field m)]
        (drift! n "autofilled@example.com")
        (is (= "autofilled@example.com" (.-value n))
            "the box now disagrees with the model, and React does not know")
        (is (nil? (record-of m)) "no session — nothing here is a draft")
        (hm/settle! m)
        (is (= "autofilled@example.com" (.-value n))
            "and settling alone does not repair it: no subscription moved,
             so no boundary re-rendered")
        (send! m [::bump-revision todo])
        (hm/settle! m)
        (is (= committed (.-value n))
            "the revision is the only thing that changed, and it is what
             put the model back over the box. Delete `::h/revision` from
             the field's props and this row reds holding the autofilled
             text")
        (is (= committed (title-of m)) "with the model itself untouched")
        (is (identical? n (field m)) "and no remount")
        (finish m done)))))

(deftest a-draft-survives-the-field-leaving-the-page-and-coming-back
  ;; Trap 5, the SURVIVAL half — the one the node lane cannot decide. A
  ;; draft held in a render closure dies here; this one is in `app-db`, so
  ;; a virtualized row can scroll out of view and back without losing the
  ;; edit. Unmount neither commits nor cancels, and this is what that
  ;; sentence buys.
  (async done
    (if-not (browser?)
      (do (skip! "a real unmount and remount of the field") (done))
      (let [m (mount!)
            n (field m)]
        (.focus n)
        (type-into! m n "half typed")
        (send! m [::hide])
        (hm/settle! m)
        (is (nil? (field m)) "the field really left the page")
        (is (= {:revision 0 :draft "half typed"} (record-of m))
            "and the draft is still where it was — unmount neither
             commits nor cancels")
        (is (= committed (title-of m)) "nothing was committed on the way out")
        (send! m [::show])
        (hm/settle! m)
        (let [n2 (field m)]
          (is (some? n2))
          (is (not (identical? n n2)) "a genuinely new node")
          (is (= "half typed" (.-value n2))
              "holding the edit the user left in it")
          (finish m done))))))
