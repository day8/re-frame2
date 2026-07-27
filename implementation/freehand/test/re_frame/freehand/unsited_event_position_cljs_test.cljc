(ns re-frame.freehand.unsited-event-position-cljs-test
  "rf2-nzmuy — what an event position answers when NO candidate owns it.

  There is no boundary above the markup, so no commit a site could belong
  to. The roster still splits cleanly at that fork, and the split is the
  one [[re-frame.freehand.events/site]] already makes for the roles that
  need no committed site:

  - `v/raw-fn` and `v/render-fn` are OUTSIDE the committed-proxy scheme
    wherever they appear — a render callback may run during an uncommitted
    foreign render, and a raw function's identity is the caller's to own —
    so an unowned position hands each one its function back UNCHANGED,
    which is precisely what a sited position answers for them too;
  - a bare function passes for the same reason, its identity never having
    been Freehand's to stabilize;
  - every other role wants the committed body, retargeting and retirement
    a site is, so it answers nil and the position goes unwritten.

  The defect this file pins was the fallback testing `fn?` instead of
  classifying: a `Callback` is deliberately NOT `fn?` in ClojureScript, so
  the `fn?` test dropped the two roles that wanted nothing from a site.
  Both no-candidate arms — the interpreted walk's `handler-proxy` and the
  compiled tier's `reactive/event-site` — now read the one classifier.

  Node lane: `fr/element` builds a React element without touching the DOM,
  and the props object it answers is what React would be handed."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.events :as events]
            [re-frame.freehand.reactive :as reactive]
            #?(:cljs [goog.object :as gobj])
            #?(:cljs [re-frame.freehand.react :as fr])))

;; ---------------------------------------------------------------------------
;; The classifier itself
;; ---------------------------------------------------------------------------

(deftest unsited-hands-back-the-roles-that-need-no-committed-site
  (testing "`v/raw-fn` answers EXACTLY the supplied function — the whole
            point of the expert seam is that the identity an API receives
            is the identity it was given, and having no site cannot take
            that away."
    (let [f (fn [_])]
      (is (identical? f (events/unsited (v/raw-fn f))))))

  (testing "`v/render-fn` answers the function its body compiled to — the
            same value a sited position answers, since a render callback
            is excluded from the committed-proxy scheme either way."
    (let [carrier (v/render-fn [x] [:span x])
          f       (events/unsited carrier)]
      (is (fn? f))
      (is (identical? (events/callback-fn carrier) f))))

  (testing "a bare function passes through as itself."
    (let [f (fn [_])]
      (is (identical? f (events/unsited f))))))

(deftest unsited-drops-the-roles-that-genuinely-need-a-site
  (testing "declarative intent, in every spelling, needs a commit to
            dispatch into and there is none — so the position is unwritten
            rather than half-written."
    (is (nil? (events/unsited [:some/event])))
    (is (nil? (events/unsited {:event [:some/event] :once true})))
    (is (nil? (events/unsited (v/event [_] [:some/event]))))
    (is (nil? (events/unsited (v/handler [_] nil)))))

  (testing "an empty position is empty."
    (is (nil? (events/unsited nil)))))

(deftest unsited-refuses-a-value-outside-the-roster
  (testing "one classification, both sides of the fork: a value that is no
            event form raises here exactly as it does at a sited position,
            rather than being silently swallowed by a `fn?` test."
    (is (= :rf.error/view-bad-event
           (conf/caught-id #(events/unsited "not-an-event"))))))

;; ---------------------------------------------------------------------------
;; The two no-candidate arms
;; ---------------------------------------------------------------------------

(deftest the-compiled-lowering-with-no-candidate-reads-the-classifier
  (testing "`reactive/event-site` outside a render — an elided ViewCell's
            position — degrades the same way the interpreted walk does."
    (let [f (fn [_])]
      (is (identical? f (reactive/event-site "site/onClick" (v/raw-fn f) nil)))
      (is (identical? f (reactive/event-site "site/onClick" f nil)))
      (is (fn? (reactive/event-site "site/onRender" (v/render-fn [x] x) nil)))
      (is (nil? (reactive/event-site "site/onClick" [:some/event] nil)))
      (is (nil? (reactive/event-site "site/onClick" (v/event [_] [:some/event]) nil))))))

#?(:cljs
   (deftest the-interpreted-walk-with-no-candidate-writes-the-raw-prop
     (testing "`fr/element` is the walk with no candidate threaded, so it is
               the public stand-in for a boundary that owns no shell. A
               `v/raw-fn` at a native `:on-*` reaches the React props object
               with its supplied identity intact — the position it was always
               entitled to, and the one the `fn?` fallback never wrote."
       (let [f     (fn [_])
             props (.-props (fr/element [:button {:on-click (v/raw-fn f)} "go"]))]
         (is (identical? f (gobj/get props "onClick"))))

       (let [props (.-props (fr/element [:button {:on-click (v/render-fn [e] e)} "go"]))]
         (is (fn? (gobj/get props "onClick"))))

       (testing "while declarative intent stays dropped — it has nothing to
                 belong to, and that loss is the documented one."
         (let [props (.-props (fr/element
                                [:button {:on-click (v/event [_] [:some/event])} "go"]))]
           (is (nil? (gobj/get props "onClick"))))
         (let [props (.-props (fr/element [:button {:on-click [:some/event]} "go"]))]
           (is (nil? (gobj/get props "onClick"))))))))
