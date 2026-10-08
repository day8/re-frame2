(ns re-frame.route-link-cljs-test
  "CLJS tests for the `:route/link` registered view's click and intent
  handlers, which only run in a JS environment:

  - a plain left-click (button 0, no modifiers) calls preventDefault and
    dispatches `:rf.route/url-requested` with the synthesised URL and the
    link's navigation policy;
  - modifier-key and middle-button clicks, and anchors with native-handling
    attributes (`target=_blank`, `download`), defer to the browser;
  - a caller `:on-click` runs first, and pre-empts the framework only by
    calling preventDefault;
  - the click and the `:prefetch :intent` warm-ups dispatch into the frame
    that RENDERED the link, however long after render they fire.

  These cases call the bare `route-link-render` fn against a synthetic event
  object, so the test has no DOM dependency.

  Per Spec 012 §Linking from views and API.md `route-link` row's
  click-rules paragraph."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.link :as rf.routing.link]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

;; The snapshot/restore fixture, not registrar/clear-all!: CLJS has no
;; `require :reload` to resurrect routing's ns-load-time registrations.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn rf.routing/reset-counters!}))

;; ---- synthetic event helper --------------------------------------------

(defn- mk-event
  "Hand-build a JS object the handler can poke at. `:preventDefault`
  flips `:defaultPrevented` to true so subsequent reads see the change."
  [{:keys [button meta ctrl shift alt]
    :or {button 0 meta false ctrl false shift false alt false}}]
  (let [o #js {:button           button
               :metaKey          meta
               :ctrlKey          ctrl
               :shiftKey         shift
               :altKey           alt
               :defaultPrevented false}]
    (set! (.-preventDefault o)
          (fn [] (set! (.-defaultPrevented o) true)))
    o))

(defn- click!
  "Render route-link with `props`, invoke its on-click handler against
  `event`, and return `{:dispatched <the :rf.route/url-requested event or nil>
  :source <its trace :source> :prevented? <boolean>}`. The dispatch is read
  off the `:rf.event/dispatched` trace, which fires at enqueue, so the result
  does not depend on the queue's drain timing."
  [props event]
  (let [dispatched (atom nil)
        source     (atom nil)
        cb-key     (keyword (gensym "click-capture-"))]
    (rf.trace.tooling/register-listener!
      cb-key
      (fn [ev]
        (when (and (= :rf.event/dispatched (:operation ev))
                   (vector? (-> ev :tags :rf.event/v))
                   (= :rf.route/url-requested (-> ev :tags :rf.event/v first)))
          (reset! dispatched (-> ev :tags :rf.event/v))
          (reset! source     (:source ev)))))
    (try
      (let [[_ attrs] (rf.routing.link/route-link-render props)
            on-click (:on-click attrs)]
        (on-click event)
        {:dispatched @dispatched
         :source     @source
         :prevented? (.-defaultPrevented event)})
      (finally
        (rf.trace.tooling/unregister-listener! cb-key)))))

;; ---- plain left-click → preventDefault + dispatch ----------------------

(deftest plain-left-click-intercepts
  (testing "button 0 + no modifiers → preventDefault + :rf.route/url-requested"
    (rf/reg-route :route/cart {} "/cart")
    (let [{:keys [dispatched source prevented?]}
          (click! {:to :route/cart} (mk-event {}))]
      (is prevented? "preventDefault was called on plain left-click")
      (is (= :router source)
          "the route-link dispatch stamps :source :router (not :unknown / :ui)")
      ;; The whole payload: an address key beside :url fails here.
      (is (= [:rf.route/url-requested {:url "/cart"}] dispatched)))))

(deftest plain-left-click-carries-the-link-navigation-policy
  (testing ":replace?, :scroll and :bypass-leave? ride the click's dispatch"
    (rf/reg-route :route/cart {} "/cart")
    (is (= [:rf.route/url-requested
            {:url "/cart" :replace? true :scroll :preserve :bypass-leave? true}]
           (:dispatched (click! {:to :route/cart :replace? true :scroll :preserve :bypass-leave? true}
                                (mk-event {})))))))

;; ---- modifier-key clicks defer to browser ------------------------------

(deftest modified-and-middle-clicks-defer
  (testing "a modified or middle click does NOT preventDefault and does NOT
            dispatch — the browser owns it"
    (rf/reg-route :route/cart {} "/cart")
    (doseq [[label event-opts] [["cmd-click"               {:meta true}]
                                ["ctrl-click"              {:ctrl true}]
                                ["shift-click"             {:shift true}]
                                ["alt-click"               {:alt true}]
                                ["middle-click (button 1)" {:button 1}]]]
      (let [{:keys [dispatched prevented?]}
            (click! {:to :route/cart} (mk-event event-opts))]
        (is (= [false nil] [prevented? dispatched])
            (str label " leaves the click for the browser and dispatches nothing"))))))

;; ---- native-anchor attributes defer to the browser ---------------------
;;
;; An off-document `:target` or a requested `:download` keeps the browser's
;; new-tab / download behaviour; `_self` and a false / nil `:download` are
;; same-document and still intercept.

(deftest native-anchor-attributes-decide-interception
  (rf/reg-route :route/cart {} "/cart")
  (doseq [[props native?] [[{:target "_blank"}       true]
                           [{:download "report.pdf"} true]
                           [{:target "_self"}        false]
                           [{:download false}        false]
                           [{:download nil}          false]]]
    (let [{:keys [dispatched prevented?]}
          (click! (merge {:to :route/cart} props) (mk-event {}))]
      (is (= (if native? [false nil] [true :rf.route/url-requested])
             [prevented? (first dispatched)])
          (pr-str props)))))

;; ---- caller-supplied :on-click can pre-empt ----------------------------

(deftest caller-on-click-pre-empts-when-preventing-default
  (testing "if the caller's :on-click calls preventDefault, the framework's interception is skipped"
    (rf/reg-route :route/cart {} "/cart")
    (is (nil? (:dispatched (click! {:to :route/cart :on-click (fn [e] (.preventDefault e))}
                                   (mk-event {})))))))

(deftest caller-on-click-runs-but-does-not-block
  (testing "if the caller's :on-click does NOT preventDefault, the framework still intercepts"
    (rf/reg-route :route/cart {} "/cart")
    (let [custom-fired? (atom false)
          {:keys [dispatched]}
          (click! {:to :route/cart :on-click (fn [_e] (reset! custom-fired? true))}
                  (mk-event {}))]
      (is @custom-fired? "the caller's on-click ran")
      (is (= :rf.route/url-requested (first dispatched))
          "the framework dispatched :rf.route/url-requested"))))

;; ---- the click must carry the RENDER-TIME frame -------------------------
;;
;; A real browser click runs long after render, when the render-time
;; `with-frame` / frame-provider scope has unwound. `:route/link` is
;; registered via `reg-view*`, so it gets no injected render-time frame
;; capture: `route-link-render` captures the rendering frame itself and
;; dispatches into it. Resolving the frame at click time would raise
;; `:rf.error/no-frame-context` with no scope, or route to the wrong frame
;; under a different one.

(defn- click-after-scope-unwound!
  "Render `route-link` with `props` while a `with-frame` scope pins
  `render-frame`, capture the on-click closure, THEN invoke it with the
  ambient frame scope set to `click-scope-frame` (nil ⇒ no scope at all).
  Returns the target frame the `:rf.route/url-requested` dispatch routed to
  (off the `:rf.event/dispatched` trace), its `:source`, and the error id if
  the click raised."
  [props render-frame click-scope-frame]
  (let [target (atom nil)
        source (atom nil)
        cb-key (keyword (gensym "delayed-click-"))]
    (rf.trace.tooling/register-listener!
      cb-key
      (fn [ev]
        (when (and (= :rf.event/dispatched (:operation ev))
                   (vector? (-> ev :tags :rf.event/v))
                   (= :rf.route/url-requested (-> ev :tags :rf.event/v first)))
          ;; :source is hoisted to the trace's top level; :frame stays in :tags.
          (reset! target (-> ev :tags :frame))
          (reset! source (:source ev)))))
    (try
      (let [on-click (rf/with-frame render-frame
                       (let [[_ attrs] (rf.routing.link/route-link-render props)]
                         (:on-click attrs)))
            raised (try
                     (binding [rf.frame/*current-frame* click-scope-frame]
                       (on-click (mk-event {})))
                     nil
                     (catch :default e
                       (or (:rf.error/id (ex-data e)) :threw)))]
        {:target-frame @target
         :source       @source
         :raised       raised})
      (finally
        (rf.trace.tooling/unregister-listener! cb-key)))))

(deftest delayed-click-ignores-wrong-ambient-frame-rf2-o3nam4
  (testing "a link rendered under :route/owner and clicked after the render
            scope unwound dispatches into :route/owner, with no ambient frame
            at click time and with a different one"
    (rf/make-frame {:id :route/owner})
    (rf/make-frame {:id :route/other})
    (rf/reg-route :route/cart {} "/cart")
    (doseq [ambient [nil :route/other]]
      (is (= {:target-frame :route/owner :source :router :raised nil}
             (click-after-scope-unwound! {:to :route/cart} :route/owner ambient))
          (str "ambient frame at click time: " (pr-str ambient))))))

;; ---- `:prefetch :intent` — the DOM intent arm -----------------------------
;;
;; Hover, focus and touch-start each dispatch `[:rf.route/prefetch {address}]`
;; to the render-time frame, composing with a caller handler of the same name.
;; A render alone dispatches nothing (Governing Law 1).

(defn- fire-intent!
  "Render `route-link` with `props` under `render-frame` (nil ⇒ ambient), invoke
  the handler at `attr-key` (none when absent) with a synthetic event, and
  report what was dispatched: the `[:rf.route/prefetch …]` vector, the
  `:source` tag, and the TARGET frame the dispatch routed to."
  ([props attr-key] (fire-intent! props attr-key nil))
  ([props attr-key render-frame]
   (let [dispatched (atom nil)
         source     (atom nil)
         target     (atom nil)
         cb-key     (keyword (gensym "intent-capture-"))]
     (rf.trace.tooling/register-listener!
       cb-key
       (fn [ev]
         (when (and (= :rf.event/dispatched (:operation ev))
                    (vector? (-> ev :tags :rf.event/v))
                    (= :rf.route/prefetch (-> ev :tags :rf.event/v first)))
           (reset! dispatched (-> ev :tags :rf.event/v))
           (reset! source     (:source ev))
           (reset! target     (-> ev :tags :frame)))))
     (try
       (let [attrs (second (if render-frame
                             (rf/with-frame render-frame (rf.routing.link/route-link-render props))
                             (rf.routing.link/route-link-render props)))]
         (when-let [h (get attrs attr-key)]
           (h (mk-event {})))
         {:dispatched @dispatched
          :source     @source
          :target     @target})
       (finally
         (rf.trace.tooling/unregister-listener! cb-key))))))

(deftest prefetch-intent-dispatches-on-each-credible-intent-position
  (testing "hover, focus and touch-start each warm the link's own destination"
    (rf/reg-route :route/article {:params [:map [:slug :string]]} "/articles/:slug")
    (doseq [pos [:on-mouse-enter :on-focus :on-touch-start]]
      (is (= {:dispatched [:rf.route/prefetch {:to :route/article :params {:slug "x"}}]
              :source     :router}
             (select-keys (fire-intent! {:to :route/article :params {:slug "x"} :prefetch :intent} pos)
                          [:dispatched :source]))
          (str pos " dispatched the address-only prefetch event")))))

(deftest a-link-without-prefetch-installs-no-intent-handlers
  (testing "a passive link installs none of the intent positions, so a caller's
            own hover handler is the only thing on the anchor"
    (rf/reg-route :route/cart {} "/cart")
    (let [own   (fn [_] nil)
          attrs (second (rf.routing.link/route-link-render {:to :route/cart :on-mouse-enter own}))]
      (is (identical? own (:on-mouse-enter attrs))
          "the caller's handler is passed through untouched — not wrapped")
      (is (not-any? #(contains? attrs %) [:on-focus :on-touch-start])))))

(deftest prefetch-intent-composes-with-a-caller-handler
  (testing "the framework handler runs the caller's handler of the same name
            and still dispatches — compose, not replace"
    (rf/reg-route :route/cart {} "/cart")
    (let [ran (atom [])
          {:keys [dispatched]}
          (fire-intent! {:to :route/cart :prefetch :intent
                         :on-mouse-enter (fn [_] (swap! ran conj :caller))}
                        :on-mouse-enter)]
      (is (= [:caller] @ran) "the caller's hover handler ran")
      (is (= [:rf.route/prefetch {:to :route/cart}] dispatched)
          "and the prefetch still dispatched"))))

(deftest prefetch-intent-dispatches-to-the-render-time-frame
  (testing "the warm-up targets the frame that RENDERED the link, exactly as the
            click handler does (Spec 012 §Route-plan prefetch)"
    (rf/make-frame {:id :route/owner})
    (rf/reg-route :route/cart {} "/cart")
    (is (= :route/owner
           (:target (fire-intent! {:to :route/cart :prefetch :intent} :on-mouse-enter :route/owner))))))

(deftest a-passive-render-dispatches-nothing
  (testing "Governing Law 1 — rendering a :prefetch :intent link dispatches
            NOTHING until an intent actually fires"
    (rf/reg-route :route/cart {} "/cart")
    ;; attr-key nil: render only, fire no handler.
    (is (nil? (:dispatched (fire-intent! {:to :route/cart :prefetch :intent} nil)))
        "a render is not an intent")))

(deftest an-unsupported-prefetch-value-fails-loud-at-render
  (testing ":intent is the only accepted value — an unsupported mode, nil
            included, is a caller bug at the render site, not a silently
            passive link"
    (rf/reg-route :route/cart {} "/cart")
    (doseq [v [true :render nil]]
      (let [data (try (rf.routing.link/route-link-render {:to :route/cart :prefetch v}) nil
                      (catch :default e (ex-data e)))]
        (is (= [:rf.error/route-link-bad-prefetch v] ((juxt :rf.error/id :value) data))
            (str "prefetch " (pr-str v) " must throw"))))))
