(ns re-frame.freehand.pilot-typeahead-dom-cljs-test
  "CASE C, the MOUNTED half — the typeahead against a real DOM.

  `pilot-typeahead-cljs-test` proves the four fences DECIDE correctly, and
  it proves the six races headlessly, which is where they belong. What it
  cannot prove is what a browser does to a live input while those
  decisions are taken:

  - a keystroke's state change reaching the host INSIDE the event, so
    React's end-of-event value restore finds what it just rendered rather
    than clobbering the caret;
  - a settle landing while the user is mid-word touching neither the value
    nor the caret nor the node;
  - a caller's refusal restoring the baseline on the SAME node, with focus
    intact — which is exactly what a key-remount destroys;
  - and the debounce actually debouncing, on a real host clock.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where it has no DOM to mount
  and says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand.pilot-typeahead :as ui]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(def ^:private fid :dom/pilot-typeahead)
(def ^:private doc-id :doc-1)
(def ^:private k [ui/typeahead-kind [:doc doc-id :reviewer]])
(def ^:private input-id "acme-typeahead-reviewer-doc-1")

(def ^:private results
  [{:value "anna" :label "Anna Novak"}
   {:value "amir" :label "Amir Haddad"}])

;; ---------------------------------------------------------------------------
;; Browser seams — the same ones the controlled-input suites use
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real browser mount is required — " why)))

(defn- act [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- live!
  "Leave React's act environment: typing has to reach React as a genuine
  DISCRETE event, which is the mechanism the draft rides."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(defn- after [ms]
  (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))

(defn- native-value-setter []
  (.-set (js/Object.getOwnPropertyDescriptor
           (.-prototype js/HTMLInputElement) "value")))

(defn- set-native-value! [node s]
  (.call (native-value-setter) node s))

(defn- fire-input! [node data]
  (.dispatchEvent node (js/InputEvent. "input"
                                       #js {:bubbles true :cancelable false :data data})))

(defn- insert-at!
  "Insert `ch` at offset `at`, the way a browser does it: the text grows at
  the caret, the caret follows it, and then `input` fires."
  [node at ch]
  (let [text (.-value node)]
    (.focus node)
    (.setSelectionRange node at at)
    (set-native-value! node (str (subs text 0 at) ch (subs text at)))
    (.setSelectionRange node (inc at) (inc at))
    (fire-input! node ch)))

(defn- type-at-end! [node s]
  (doseq [ch s]
    (insert-at! node (count (.-value node)) (str ch))))

(defn- caret [node] [(.-selectionStart node) (.-selectionEnd node)])

(defn- mount! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    [container (rdc/createRoot container)]))

(defn- teardown! [container root]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
  (.unmount root)
  (.remove container)
  nil)

(defn- setup! []
  (live-frame/make-frame {:id fid})
  (ui/register!)
  (ui/register-app!)
  (frame/replace-app-db! fid {:doc {doc-id {:reviewer ""
                                            :reviewer-revision 0
                                            :reviewer-confirmed? true}}})
  nil)

(defn- element [] (shell/provide-frame fid (fr/element [ui/reviewer-form {:id doc-id}])))
(defn- render! [root] (act #(.render root (element))))
(defn- send! [ev] (rf/dispatch-sync ev {:frame fid}))
(defn- app-db [] (frame/frame-app-db-value fid))
(defn- record [] (get-in (app-db) [ui/records-root k]))
(defn- requests [] (get (app-db) :re-frame.freehand.pilot-typeahead/requests []))
(defn- field [] (js/document.getElementById input-id))

(defn- reply!
  "The caller answering a request it was handed. `act`, because it moves
  ordinary application state and React has to be allowed to render it."
  [request outcome]
  (act #(send! (conj (vec (:reply-to request)) outcome))))

;; ===========================================================================
;; Typing through the draft, on a real controlled input
;; ===========================================================================

(deftest a-keystroke-reaches-the-draft-inside-the-event-and-keeps-the-caret
  (testing "The input is a controlled native carrying a LITERAL event
            vector at `:on-input`, so the site is inside the substrate's
            synchronous door: the keystroke's state change lands before the
            listener returns, and React's end-of-event value restore finds
            what it just rendered. The evidence is the DOM value after each
            character, the caret after a MID-STRING insert, and the node
            identity throughout — the three things an async round trip
            destroys."
    (if-not (browser?)
      (skip! "the browser job runs the typing assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (render! root)
              (.then (fn [_]
                       (live!)
                       (let [node   (field)
                             before node]
                         (is (= "" (.-value node)) "empty to begin with")
                         (type-at-end! node "anna")
                         (is (= "anna" (.-value node))
                             "every keystroke survived the round trip through the draft")
                         (is (= "anna" (:query (record)))
                             "and the draft is controller state, not host state")
                         (is (identical? before (field)) "on the same node throughout")

                         ;; A mid-string insert: the caret is what a lagging
                         ;; round trip moves to the end.
                         (insert-at! node 2 "X")
                         (is (= "anXna" (.-value node)) "the insert landed at the caret")
                         (is (= [3 3] (caret node)) "and the caret followed it")
                         (is (= "anXna" (:query (record))) "and reached the draft"))))
              (.catch (fn [e]
                        (is false (str "browser run failed: " e))
                        nil))
              (.then (fn [_]
                       (teardown! container root)
                       (done)))))))))

(deftest a-settle-arriving-mid-word-touches-neither-the-value-nor-the-caret
  (testing "R-C1, in a browser. The corpus baseline FAILS this: an accepted
            reply replaces the whole slice and the keystrokes typed since
            are discarded. Here a settle has nowhere to write the typed
            text — it writes a result set tagged with the query it answers
            — so the row asserts the three things a clobber would move: the
            DOM value, the caret, and the node."
    (if-not (browser?)
      (skip! "the browser job runs the mid-word settle assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (render! root)
              (.then (fn [_]
                       (live!)
                       (let [node (field)]
                         (type-at-end! node "an")
                         ;; The request the debounce would have sent, sent now.
                         (send! [:acme.ui.typeahead/due k (:token (record))
                                 [:app/search-requested]])
                         ;; The user types on while it is out.
                         (type-at-end! node "na")
                         (.setSelectionRange node 2 2)
                         (js/Promise.resolve node))))
              (.then (fn [node]
                       (let [before node
                             req    (first (requests))]
                         (is (= "anna" (.-value node)) "four characters typed")
                         (-> (reply! req {:results results})
                             (.then (fn [_]
                                      (is (= "anna" (.-value node))
                                          "the settle touched not one character")
                                      (is (= [2 2] (caret node))
                                          "nor the caret")
                                      (is (identical? before (field))
                                          "nor the node")
                                      (is (zero? (.-length (.querySelectorAll
                                                             (.-body js/document)
                                                             "[data-part='option']")))
                                          (str "and its results are NOT shown — they answer "
                                               "'an', and the user has typed 'anna'"))))))))
              (.catch (fn [e]
                        (is false (str "browser run failed: " e))
                        nil))
              (.then (fn [_]
                       (teardown! container root)
                       (done)))))))))

(deftest a-callers-refusal-restores-the-baseline-on-the-same-node
  (testing "The generation fence in a browser. The caller refuses the draft
            and stands by the value it already had, advancing the revision
            to say this is a new baseline decision. The baseline comes
            back, the node is the SAME node, and it still has focus — which
            is precisely what a key-remount destroys and what no structural
            assertion can see."
    (if-not (browser?)
      (skip! "the browser job runs the reject-and-restore assertions")
      (async done
        (setup!)
        (frame/replace-app-db! fid {:doc {doc-id {:reviewer "amir"
                                                  :reviewer-revision 3
                                                  :reviewer-confirmed? true}}})
        (let [[container root] (mount!)]
          (-> (render! root)
              (.then (fn [_]
                       (live!)
                       (let [node   (field)
                             before node]
                         (is (= "amir" (.-value node)) "the committed value shows")
                         (type-at-end! node "x")
                         (is (= "amirx" (.-value node)) "a draft is live")
                         (-> (act #(send! [:app/reviewer-refused doc-id "amir"]))
                             (.then (fn [_]
                                      (is (= "amir" (.-value node))
                                          "the baseline is back on screen")
                                      (is (identical? before (field))
                                          "restored on the SAME node, not remounted")
                                      (is (identical? before js/document.activeElement)
                                          "which still has focus")))))))
              (.catch (fn [e]
                        (is false (str "browser run failed: " e))
                        nil))
              (.then (fn [_]
                       (teardown! container root)
                       (done)))))))))

(deftest the-debounce-debounces-on-a-real-host-clock
  (testing "The headless rows deliver the delayed event by hand, which is
            the only way to make the six races deterministic. This row
            closes the loop the other way: four keystrokes inside one quiet
            period on a REAL `:dispatch-later` timer produce exactly ONE
            request, for the text that was typed last. The three superseded
            schedules fired and found they were not current."
    (if-not (browser?)
      (skip! "the browser job runs the real-clock debounce assertion")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (render! root)
              (.then (fn [_]
                       (live!)
                       (type-at-end! (field) "anna")
                       (is (empty? (requests)) "nothing has gone out yet")
                       (after 300)))
              (.then (fn [_]
                       (is (= 1 (count (requests)))
                           (str "exactly one request after the quiet period, not four "
                                "(got " (mapv :query (requests)) ")"))
                       (is (= "anna" (:query (first (requests))))
                           "for what the user actually typed")))
              (.catch (fn [e]
                        (is false (str "browser run failed: " e))
                        nil))
              (.then (fn [_]
                       (teardown! container root)
                       (done)))))))))

(deftest choosing-a-suggestion-commits-and-retires-the-control
  (testing "The whole loop, live: type, settle, click a suggestion. The
            caller's own value moves through the caller's own event, and
            the control retires itself with the commit rather than leaving
            a closed record behind."
    (if-not (browser?)
      (skip! "the browser job runs the selection assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (render! root)
              (.then (fn [_]
                       (live!)
                       (type-at-end! (field) "an")
                       (send! [:acme.ui.typeahead/due k (:token (record))
                               [:app/search-requested]])
                       (reply! (first (requests)) {:results results})))
              (.then (fn [_]
                       (let [opts (.querySelectorAll (.-body js/document) "[data-part='option']")]
                         (is (= 2 (.-length opts)) "the suggestions are on screen")
                         (act #(.click (aget opts 0))))))
              (.then (fn [_]
                       (is (= "anna" (get-in (app-db) [:doc doc-id :reviewer]))
                           "the caller's state moved")
                       (is (nil? (record)) "and the control retired itself")
                       (is (= "anna" (.-value (field)))
                           "with the committed value now the input's baseline")))
              (.catch (fn [e]
                        (is false (str "browser run failed: " e))
                        nil))
              (.then (fn [_]
                       (teardown! container root)
                       (done)))))))))
