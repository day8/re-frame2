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

  What is left is the case nothing else on the page can repair:
  [[a-foreign-write-with-no-session-open-is-repaired-by-the-revision-and-by-nothing-else]].
  A password manager, an extension or an autofill writes into an
  untouched box; no draft exists, no `app-db` value moves, so nothing the
  field reads changes and nothing re-renders.

  That row carries a CONTROL ARM rather than a comment, because the first
  draft of it did not and was green for the wrong reason. It asserted
  that deleting `::h/revision` from the element the module emits would
  red it; the deletion was run, and the row stayed green — the caller's
  revision is also a PROP of the boundary, so a bump re-renders the field
  whether or not the prop is forwarded on to the `<input>`. The scope of
  what the module can decide here is therefore narrower than it looked,
  and [[constant-field]] is what makes it decidable: two fields, one page,
  one prop of difference, and the reset visible on exactly one of them.

  The other half — that forwarding the trigger to the element guarantees
  a commit through a `React.memo` wall — is the ELEMENT's law (HD-019),
  and `revision_dom_cljs_test` owns it, states its limit in terms (*the
  revision guarantees a commit through the wall; it is not the only
  source of one*) and proves it against a real wall. Nothing here
  restates it.

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

(h/defview constant-field
  "THE CONTROL ARM, and the whole reason the revision row below can fail.

  The chapter blesses a constant revision for a field that will never be
  externally reset — *that choice means an active draft is never replaced
  merely because `:value` changed*. This call site takes it: a literal
  `0`, and no read of `::title-revision` anywhere in its body.

  It sits beside [[title-field]] on the same page, over the same module,
  reading the same committed value, differing in exactly one prop. So
  when the revision moves and one box re-baselines while the other keeps
  its drift, the difference between them is the revision and cannot be
  anything else."
  [{:keys [id]}]
  [forms/buffered-field
   {:control     [:todo id :constant]
    :value       (h/sub [::title id])
    ::h/revision 0
    :on-commit   [::title-committed id]
    :id          "todo-constant"
    :class       "constant"}])

(h/defview screen
  "Both fields, and a switch that takes the first off the page. The switch
  is read HERE and nowhere else, so toggling it re-renders this body
  alone."
  [_]
  [:main.screen
   (when (h/sub [::showing?])
     [title-field {:id todo}])
   [constant-field {:id todo}]])

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
(defn- constant [m] (.querySelector (:container m) "#todo-constant"))

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
  ;; THE EXPERIMENT, and it carries its own control arm so it can fail
  ;; without anybody editing the module.
  ;;
  ;; Everywhere else here the record's own subscription does the work:
  ;; ending a session moves a value the field reads, so the boundary
  ;; re-renders and the commit re-asserts the model for free. This is the
  ;; one arrangement where nothing does — no session ever opened, and the
  ;; caller moves NOTHING except the revision.
  ;;
  ;; Two fields, side by side over the same module, differing in one prop:
  ;; `title-field` reads the revision, `constant-field` passes a literal
  ;; `0`. Both are drifted; the revision then moves. If the reset were
  ;; coming from anywhere else on the page, both boxes would repair and
  ;; this row would red on the control.
  ;;
  ;; THE SCOPE IS THE CALLER'S PROP, and it is worth naming exactly.
  ;; Forwarding `::h/revision` on to the `<input>` is the ELEMENT's law
  ;; (HD-019), it guarantees a commit through a `React.memo` wall, and it
  ;; is owned and proved by `revision_dom_cljs_test` — which also states
  ;; the limit this row respects: *the revision guarantees a commit
  ;; through the wall; it is not the only source of one*. Inside a
  ;; boundary tree there is no wall between these two views, so what this
  ;; row decides is the half that belongs to the module — whether the
  ;; caller's revision reaching a `buffered-field` resets it.
  (async done
    (if-not (browser?)
      (do (skip! "a foreign write React never saw") (done))
      (let [m (mount!)
            n (field m)
            c (constant m)]
        (is (= committed (.-value n)))
        (is (= committed (.-value c)) "both boxes start on the model")
        (drift! n "autofilled@example.com")
        (drift! c "autofilled@example.com")
        (is (nil? (record-of m)) "no session — neither box holds a draft")
        (hm/settle! m)
        (is (= "autofilled@example.com" (.-value n))
            "settling alone repairs nothing: no subscription moved, so no
             boundary re-rendered")
        (is (= "autofilled@example.com" (.-value c)))
        (send! m [::bump-revision todo])
        (hm/settle! m)
        (is (= committed (.-value n))
            "the revision is the only thing that changed, and it is what
             put the model back over the box")
        (is (= "autofilled@example.com" (.-value c))
            "THE CONTROL. Same page, same module, same committed value,
             same turn — and its revision did not move, so nothing reset
             it. Take the difference away and the row above is green for a
             reason that has nothing to do with the prop")
        (is (= committed (title-of m)) "with the model itself untouched")
        (is (identical? n (field m)) "and no remount, on either box")
        (is (identical? c (constant m)))
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
