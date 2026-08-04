(ns re-frame.bench.hicasso.arm1.state-dom-cljs-test
  "`h/reg-state`, MOUNTED — two disclosures on one page (rf2-2rtt6.98).

  `front/state-cljs-test` proves the sugar against a real frame with no
  React at all. This file proves the three claims only a browser can, and
  the first two are the architectural ones:

  1. **It costs no hook.** The widget's shell is counted at React's own
     dispatcher (`arm1/hook-probe`), and it is the same two calls a
     boundary that had never heard of `reg-state` makes. That is the
     whole \"ordinary core artefacts\" claim, measured: the sugar mints a
     sub and an event, and a sub read through the ambient collector is not
     a hook.
  2. **HD-028's memo bail-out still bails.** A parent re-render rebuilds
     its children's key vectors — `(child-key row :detail)` allocates a
     fresh vector every time — and `React.memo`'s comparator is `=` on the
     props map, so a fresh-but-equal vector must NOT look like a changed
     prop. If it did, this sugar would have re-introduced the 300-of-300
     cascade `arm1/props-bailout-dom-cljs-test` exists to keep dead, and
     it would have done it through the very helper that makes nesting
     work.
  3. **Two instances on one page are independent**, clicked in a real
     browser, read back out of the real DOM.

  ## Why a `console` capture rides the ordinary path

  React routes a handler exception to `reportError` — a `window` `error`
  event — and reports other faults by `console.error`, and NEITHER
  reaches `cljs.test`. A row here asserting the page rendered correctly
  could therefore be green over a live exception. So the ordinary path is
  driven inside `hydration-support/capture-console!`, which watches both
  channels, and the row asserts the capture is EMPTY. That helper is the
  lane's one copy of this refusal; a second copy is the copy that quietly
  weakens.

  And because a bad instance key is *recovered* rather than thrown (the
  sub body's throw is caught, fanned on the always-on error channel, and
  the read answers `nil`), the refusal row reads that channel — the same
  reasoning `front/state-cljs-test` sets out at length.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every DOM claim degrades to a stated
  skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.hook-probe :as probe]
            [re-frame.bench.hicasso.arm1.hydration-support :as support]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.state :as state]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::arm1-state-dom)

(def ^:private open? ::open?)
(def ^:private label ::label)

;; ---------------------------------------------------------------------------
;; The screen — one widget, mounted twice
;; ---------------------------------------------------------------------------

(def ^:private !panel-runs (atom 0))
(def ^:private !page-runs (atom 0))

(defn- reset-runs! [] (reset! !panel-runs 0) (reset! !page-runs 0) nil)

(defview disclosure
  "A disclosure panel. It holds its own open flag and knows nothing about
  where it is on the page beyond the instance key it was handed — which
  is the ergonomic claim: the widget is written ONCE and mounted twice."
  [{:keys [ikey title]}]
  (swap! !panel-runs inc)
  (let [shown? (rt/sub [open? ikey])]
    [:section.panel {:data-ikey (pr-str ikey)}
     [:button.toggle {:on-click [open? ikey (not shown?)]} title]
     (when shown? [:div.body "body of " title])]))

(defview page
  "Two disclosures, and a page-level read of its own so a chrome write
  can re-render the page without touching either panel. Every child key
  is REBUILT here on every render — `child-key` allocates — which is
  exactly the condition claim 2 is about."
  [_]
  (swap! !page-runs inc)
  [:div.page
   [:h1.chrome (str (rt/sub [label :page]))]
   [disclosure {:key "billing"  :ikey (state/child-key :panel :billing)  :title "Billing"}]
   [disclosure {:key "shipping" :ikey (state/child-key :panel :shipping) :title "Shipping"}]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip! [why] (is true (str "a reg-state DOM claim needs a real React DOM — " why)))

(defn- fresh! []
  (lane/leave-act-environment!)
  (state/reg-state open? {:default false})
  (state/reg-state label {:default ""})
  (rf/make-frame {:id frame-id})
  (reset-runs!)
  (rt/reset-body-runs!)
  frame-id)

(defn- panels [handle]
  (array-seq (.querySelectorAll (:container handle) "section.panel")))

(defn- bodies [handle]
  (mapv #(some-> (.querySelector % "div.body") (.-textContent))
        (panels handle)))

(defn- click! [handle i]
  (-> (nth (vec (panels handle)) i)
      (.querySelector "button.toggle")
      (.click))
  (mount/settle!)
  nil)

;; ---------------------------------------------------------------------------
;; 1 — two instances on one page, independent, in a real browser
;; ---------------------------------------------------------------------------

(deftest two-mounted-instances-of-one-widget-are-independent
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [[result captured]
            (support/capture-console!
              (fn []
                (let [handle (mount/root! (mount/fresh-container!) frame-id [page {}])]
                  (try
                    (let [before (bodies handle)]
                      (click! handle 0)
                      {:before before
                       :after  (bodies handle)
                       :db     (rf/app-db-value frame-id)})
                    (finally (mount/release! handle))))))]
        (is (= [nil nil] (:before result)) "both panels start closed — the default")
        (is (= ["body of Billing" nil] (:after result))
            "one click opened ONE panel. Before the explicit-key ruling the
             guide's own pitfall opened both, silently.")
        (is (= {:ui {open? {[:panel :billing] true}}} (:db result))
            "one entry, at the documented app-space path, under the composed key")
        (is (= [] @captured)
            "and React complained on NEITHER channel — without this the rows
             above could be green over a live exception cljs.test never sees")))))

;; ---------------------------------------------------------------------------
;; 2 — no hook. The ≤2-hook ledger is untouched.
;; ---------------------------------------------------------------------------

(deftest a-reg-state-widget-shell-still-calls-exactly-two-hooks
  (cond
    (not (mount/browser?)) (skip! ":node-test has no DOM")

    (not (probe/install!))
    (is false (str "React's internals slot was not found, so the ≤2-hook budget "
                   "is UNWITNESSED for reg-state. A gate nobody has watched fire "
                   "is not evidence — fix "
                   (pr-str 're-frame.bench.hicasso.arm1.hook-probe)
                   " rather than reading this as a pass."))

    :else
    (do
      (fresh!)
      (let [container (mount/fresh-container!)
            !handle   (volatile! nil)
            names     (probe/record!
                        (fn []
                          (vreset! !handle
                                   (mount/root! container frame-id
                                                [disclosure {:ikey :solo :title "Solo"}]))))]
        (mount/release! @!handle)
        (is (= ["useContext" "useSyncExternalStore"] names)
            (str "the shell called " (pr-str names) " — reg-state mints a sub "
                 "and an event, and a sub read through the ambient collector is "
                 "not a hook. A third call here is a budget breach (HD-020(b)) "
                 "and would mean this sugar had grown machinery."))
        (is (= (count rt/shell-hook-ledger) (count names))
            "and the declared ledger is still the measured one — reg-state
             added nothing to it")
        (is (not-any? #{"useRef" "useState"} names)
            "neither hook is per-instance React state: the value lives in
             app-db, which is the whole point of keying it explicitly")))))

;; ---------------------------------------------------------------------------
;; 3 — HD-028's bail-out survives a key vector rebuilt every render
;; ---------------------------------------------------------------------------

(deftest a-fresh-but-equal-key-vector-does-not-defeat-the-memo-bail-out
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [page {}])]
        (try
          (let [nodes-before (vec (panels handle))]
            (reset-runs!)
            (mount/dispatch! handle [label :page "chrome moved"])
            (is (= 1 @!page-runs) "the page re-ran — it reads the chrome")
            (is (= 0 @!panel-runs)
                "and NEITHER panel did, although the page rebuilt both their
                 `ikey` vectors from scratch on the way through. `child-key`
                 allocates; `React.memo`'s comparator is `=`; a fresh-but-equal
                 vector is an EQUAL prop. Had it been compared by identity this
                 sugar would have re-introduced the cascade rf2-2rtt6.52 killed.")
            (is (= "chrome moved"
                   (.-textContent (.querySelector (:container handle) "h1.chrome")))
                "and the write really landed — without this the row above could
                 pass by doing nothing at all")
            (is (= nodes-before (vec (panels handle)))
                "the panels are the IDENTICAL DOM nodes"))
          (testing "and a bail-out is not a disconnection: the panel still
                   answers its own write afterwards"
            (reset-runs!)
            (click! handle 1)
            (is (= ["" "body of Shipping"] (mapv #(or % "") (bodies handle))))
            (is (= 1 @!panel-runs) "exactly the panel that moved, and no other"))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 4 — a bad key refuses on the page, and the page keeps working
;; ---------------------------------------------------------------------------

(deftest a-nil-keyed-widget-refuses-loudly-and-its-sibling-keeps-rendering
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [records (volatile! [])]
        (error-emit/register-error-listener! ::state-dom (fn [r] (vswap! records conj r)))
        (let [handle (mount/root! (mount/fresh-container!) frame-id [:div.empty])]
          (try
            (mount/render! handle
                           [:div.page
                            [disclosure {:key "good" :ikey :billing :title "Billing"}]
                            [disclosure {:key "bad" :ikey nil :title "Broken"}]])
            (let [refusals (->> @records
                                (mapcat (juxt (comp ex-data :exception)
                                              (comp ex-data ex-cause :exception)))
                                (filter #(= :rf.error/hicasso-state-bad-key
                                            (:rf.error/id %))))]
              (is (seq refusals)
                  "a nil instance key refused — this is the guide's silent
                   every-instance-shares pitfall converted into a loud error")
              (is (some #(= open? (:concern %)) refusals)
                  "and the refusal NAMES the concern, which is what makes it
                   actionable on a page with several of them"))
            (is (= 2 (count (panels handle)))
                "the well-keyed sibling still rendered — one widget's bad key
                 is not the page's problem")
            (finally
              (error-emit/unregister-error-listener! ::state-dom)
              (mount/release! handle))))))))
