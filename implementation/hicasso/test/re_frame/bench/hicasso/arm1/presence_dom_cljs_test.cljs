(ns re-frame.bench.hicasso.arm1.presence-dom-cljs-test
  "PRESENCE, MOUNTED — the toast tray written INLINE (rf2-2rtt6.37,
  HD-025).

  `front/presence_cljs_test` proves the machine and the phase transform
  with no clock, which is itself one of the ruling's claims. This file
  proves the part only a browser can: that React drives that machine,
  that the exit attributes reach the real DOM on the real node, that
  re-entry takes them off again, and that the node is gone after
  `:timeout-ms` with nothing retained behind it.

  **The whole point is that there is no child view here.** The
  predecessor's guide requires one — `[:div.toast …]` extracted into a
  declared, keyed `defview` purely so a dynamic var resolves against the
  right child, with reading the phase inline in the parent recorded as a
  trap that silently yields the parent's phase. The tray below is written
  inline, in the parent, in one form.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every DOM claim degrades to a stated
  skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.presence :refer [presence]]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(def ^:private frame-id ::arm1-presence)
(def ^:private timeout-ms 60)

;; Registered ABOVE `use-fixtures`, deliberately. The reset fixture captures
;; its source-store baseline when the `use-fixtures` form is EVALUATED and
;; restores that baseline before every test — so a `reg-sub` written below it
;; is erased before the first row runs, and the screen renders nothing.

(rf/reg-sub :toasts/visible (fn [db _] (:toasts db)))

(rf/reg-event :toasts/set (fn [{:keys [db]} [_ ts]] {:db (assoc db :toasts ts)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; `:timeout-ms` means two rows here wait on a real clock, and
     ;; `cljs.test` hard-errors on a fn-form fixture in a suite with an
     ;; `(async done …)` test.
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(defn- skip! [why] (is true (str "a presence claim needs a real React DOM — " why)))

(defn- fresh! [ts]
  (lane/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:toasts/set ts]))
  frame-id)

;; ---------------------------------------------------------------------------
;; The screen — one view, no child view, one override map
;; ---------------------------------------------------------------------------

(defview toast-tray
  "The census-real shape, ported. Compare the predecessor's: two views, an
  ambient `presence-phase` read, and three separate `(when exiting? …)`
  attributes on the extracted child."
  [_]
  [presence {:timeout-ms timeout-ms}
   (for [t (rt/sub [:toasts/visible])]
     [:div.toast {:key (:id t)
                  :data-id (:id t)
                  :re-frame.hicasso/unmounting {:class       "toast--exit"
                                                :inert       true
                                                :aria-hidden true}}
      (:message t)])])

(def ^:private !ref-fired
  "Every node a ref written in a phase override was handed. It must stay
  empty: a ref addresses node identity, and a phase override is about
  appearance."
  (atom []))

(defview hostile-tray
  "The same tray, with an unmounting override that reaches for both
  structural slots in spellings a raw `#{:key :ref}` dissoc does not see.
  `\"ref\"` and `:x/key` are the codec's own accepted spellings — it takes
  string, symbol and namespaced prop keys and emits them all under one
  React name — so this is the shape the deny has to be written against."
  [_]
  [presence {:timeout-ms timeout-ms}
   (for [t (rt/sub [:toasts/visible])]
     [:div.toast {:key (:id t)
                  :data-id (:id t)
                  :re-frame.hicasso/unmounting
                  {:class "toast--exit"
                   "ref"  (fn [node] (swap! !ref-fired conj node))
                   :x/key (str "stolen-" (:id t))}}
      (:message t)])])

(def ^:private two [{:id 1 :message "Saved"} {:id 2 :message "Copied"}])
(def ^:private one [{:id 1 :message "Saved"}])

(defn- node [handle id]
  (.querySelector (:container handle) (str ".toast[data-id=\"" id "\"]")))

(defn- toast-count [handle]
  (.-length (.querySelectorAll (:container handle) ".toast")))

(defn- settled!
  "Let the enter flip's macrotask land, then let React commit it."
  []
  (js/Promise. (fn [resolve]
                 (js/setTimeout (fn [] (mount/settle!) (resolve true)) 0))))

;; ---------------------------------------------------------------------------
;; 1 — the inline toast gets its exit attributes, and loses them on re-entry
;; ---------------------------------------------------------------------------

(deftest an-inline-toast-gets-the-exit-attributes-while-unmounting
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! two)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [toast-tray {}])]
        (try
          (is (= 2 (toast-count handle)) "both toasts are on screen")
          (is (nil? (.getAttribute (node handle 2) "inert"))
              "and neither is exiting")
          (mount/dispatch! handle [:toasts/set one])
          (is (= 2 (toast-count handle))
              "the removed toast is RETAINED — it is gone from app-db and
               still in the document, which is the whole of presence")
          (let [exiting (node handle 2)]
            (is (some? (.getAttribute exiting "inert"))
                "the override reached the real node")
            (is (= "true" (.getAttribute exiting "aria-hidden")))
            (is (.contains (.-classList exiting) "toast--exit"))
            (is (.contains (.-classList exiting) "toast")
                "and the tag's own class is still there — the override merged
                 onto the node rather than replacing it"))
          (is (nil? (.getAttribute (node handle 1) "inert"))
              "while its neighbour is untouched")
          (testing "and re-entry cancels the exit rather than finishing it"
            (mount/dispatch! handle [:toasts/set two])
            (let [back (node handle 2)]
              (is (nil? (.getAttribute back "inert")))
              (is (nil? (.getAttribute back "aria-hidden")))
              (is (not (.contains (.-classList back) "toast--exit")))
              (is (= 2 (toast-count handle)))))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 1b — a hostile override reaches neither structural slot on the real node
;; ---------------------------------------------------------------------------

(deftest a-hostile-override-reaches-neither-structural-slot-on-the-retained-node
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (reset! !ref-fired [])
      (fresh! two)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [hostile-tray {}])]
        (try
          (let [before (node handle 2)]
            (is (some? before))
            (is (= [] @!ref-fired) "nothing attached while the toast was present")
            (mount/dispatch! handle [:toasts/set one])
            (is (= 2 (toast-count handle)) "retained, as ever")
            (let [during (node handle 2)]
              (is (.contains (.-classList during) "toast--exit")
                  "the appearance half of the override still lands — this is a
                   deny on two slots, not a rejection of the override")
              (is (= [] @!ref-fired)
                  "and no ref was handed the retained node. React invokes a ref
                   in the commit phase, so an override that reaches this slot
                   hands a live DOM node to code the boundary never inspected —
                   the exact thing `:ref` is excluded from a merge for")
              (is (identical? before during)
                  "the retained node is the SAME element. Its identity is the
                   key the machine retains it under, and nothing in an override
                   can move that in any spelling — a remount here would restart
                   the exit of a node that is mid-animation")))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 2 — the node is gone after :timeout-ms, and nothing leaks
;; ---------------------------------------------------------------------------

(deftest the-retained-node-leaves-on-the-timeout
  ;; ONE `async` block covering both branches: `cljs.test` runs only the
  ;; first async block of a `deftest`, so a skip branch with its own block
  ;; would be a test that silently stopped running in the browser.
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh! two)
        (let [handle (mount/root! (mount/fresh-container!) frame-id [toast-tray {}])]
          (mount/dispatch! handle [:toasts/set one])
          (is (= 2 (toast-count handle)) "retained, for now")
          (js/setTimeout
            (fn []
              (mount/settle!)
              (try
                (is (= 1 (toast-count handle))
                    "removal is terminal: :timeout-ms is a clock, not a
                     transitionend listener, so the node leaves whether or not
                     any CSS ran")
                (is (nil? (node handle 2)))
                (is (some? (node handle 1))
                    "and the toast that never left is still there, so this is
                     removal and not a teardown")
                (finally (mount/release! handle) (done))))
            (* 4 timeout-ms)))))))

;; ---------------------------------------------------------------------------
;; 3 — the enter phase, and the honest limit on it
;; ---------------------------------------------------------------------------

(deftest a-mounting-child-flips-to-present-after-paint
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh! [])
        (let [handle (mount/root! (mount/fresh-container!) frame-id [toast-tray {}])]
          (mount/dispatch! handle [:toasts/set one])
          (is (= 1 (toast-count handle)))
          (-> (settled!)
              (.then (fn [_]
                       (try
                         (is (nil? (.getAttribute (node handle 1) "inert"))
                             "the child settled to :present — the enter override,
                              if it had one, would be off by now. This is the
                              weak half: a class flip can lose the race to
                              paint, which is why the guide teaches an
                              animation on insertion for enter and keeps the
                              override for exit")
                         (finally (mount/release! handle)))))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              (.catch (fn [e] (is false (str e)) nil))
              (.then (fn [_] (done)))))))))
