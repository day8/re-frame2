(ns day8.re-frame2-xray.acceptance.substrate-gap-dom-cljs-test
  "THE PUBLIC `open!` VERB ACROSS BOTH ADAPTER FAMILIES.

  The three arms beside this file — `acceptance.uix-dom-cljs-test`,
  `acceptance.reagent-dom-cljs-test` and
  `acceptance.reagent-slim-dom-cljs-test` — witness all six behavioural
  criteria through Xray's own React root, on an element-shaped adapter and
  on both ratom-family runtimes alike. This file covers the one thing they
  do not: the PUBLIC `open!` verb, the door a host app actually uses. The
  namespace is named for the seam it pins: whether the public mount
  crosses the gap between adapter families.

  ## THE ROWS

  [[the-public-mount-succeeds-on-an-element-shaped-substrate]] pins that
  `open!` MOUNTS on an element-shaped host. Xray paints through a root it
  owns, so an element-shaped `:render` is never handed the hiccup shell
  and there is nothing to refuse. Pinning the success, rather than merely
  having no refusal row, is what keeps a deliberate behaviour from
  becoming an accidental one later.

  [[the-public-mount-succeeds-on-a-ratom-family-substrate]] is the second
  FAMILY: the two rows together say the public `open!` mounts on a
  ratom-family host and on an element-shaped one, with the installed
  adapter the only difference between them, which is 'indifferent to the
  installed adapter' stated where a host app can see it. Nothing else in
  the suite covers the public verb — the six-criteria arms mount Fresco's
  root directly (`test-helpers.criteria/mount-xray!`), never `open!` — so
  it is coverage rather than ceremony.

  [[the-public-mount-succeeds-on-a-slim-ratom-family-substrate]] drives
  the same door on reagent-slim. That pairing fails if a hiccup->React
  crossing names stock Reagent's walk statically: a crossing loses its
  frame, `:rf.error/no-frame-context` raises during render, React takes
  the whole subtree down and Xray's chrome does not paint.
  `substrate/as-element` reads the walk off the INSTALLED adapter, and
  `static.shell-reagent-slim-crossing-dom-cljs-test` witnesses that
  crossing with an error boundary above it — an instrument a naked mount
  cannot supply, because the browser runner fails a whole run on an
  uncaught `pageerror` INDEPENDENTLY of the cljs.test summary.

  ## FIXTURE

  No `:adapter` in the fixture: each row installs its own with `rf/init!`,
  so all three rows sit in ONE namespace and differ in the installed
  adapter and in nothing else — which is the entire content of the claim."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.mount :as xray-mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:ambient-frame nil
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      (xray-mount/reset-for-test!)
                      (when (exists? js/globalThis)
                        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false))
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser? [] (rf.fresco.impl.mount/browser?))

(defn- with-layout-host
  "Give `f` a real `[data-rf-xray-host]` element — the host app's own slot,
  which is what `open!` looks for — and take it away afterwards however `f`
  ends. The host is returned to `f` so a row can measure it."
  [f]
  (let [host (.createElement js/document "div")]
    (.setAttribute host "data-rf-xray-host" "")
    (.appendChild (.-body js/document) host)
    (try
      (f host)
      (finally
        (xray-mount/teardown!)
        (.remove host)))))

;; ===========================================================================
;; The public mount paints on an element-shaped adapter
;; ===========================================================================

(deftest the-public-mount-succeeds-on-an-element-shaped-substrate
  (testing "with an element-shaped adapter installed, Xray's
            PUBLIC `open!` MOUNTS: a real root in the document, a node in
            the host's own slot, and a `status` reporting health rather
            than a refusal.

            `open!` does not paint through the INSTALLED adapter's
            `:render` — the shell is a `re-frame.fresco` boundary on a
            root Xray owns — so an element-shaped `:render` is never
            handed hiccup and there is nothing to refuse."
    (if-not (browser?)
      (is true "skipped: the :browser-test runner drives the real mount")
      (do
        (rf/init! rf.adapter.uix/adapter)
        (registry/register-xray-handlers!)
        (with-layout-host
          (fn [host]
            ;; Caught because an element-shaped host is exactly where a
            ;; regression in the
            ;; owned-root path surfaces as a render-phase THROW rather
            ;; than as a false return, and an uncaught one would swallow
            ;; every assertion below it.
            (let [ret (try (xray-mount/open!)
                           (catch :default e
                             (is false
                                 (str "`open!` THREW on an element-shaped "
                                      "substrate: "
                                      (pr-str (:rf.error/id (ex-data e)
                                                            (ex-message e)))
                                      ". Xray is meant to own its own root "
                                      "here and never touch the host's "
                                      ":render."))
                             ::threw))]
              (is (= true (xray-mount/mounted?))
                  (str "`open!` mounted on an element-shaped substrate. "
                       "Returned: " (pr-str (if (map? ret)
                                              (dissoc ret :node :unmount)
                                              ret))))
              (is (= :inline (:mode ret))
                  (str "on the inline surface. Got: " (pr-str (:mode ret))))
              ;; ---- and the refusal is gone, read through the PUBLIC
              ;; ---- surface a host would inspect -------------------------
              (is (= true (:ok? (:diagnostic (xray-mount/status))))
                  (str "the status diagnostic reports health. Got: "
                       (pr-str (:diagnostic (xray-mount/status)))))
              (is (nil? (:reason (:diagnostic (xray-mount/status))))
                  (str "naming no refusal reason — `:unsupported-substrate` "
                       "is a reserved id in that vocabulary and never "
                       "fires. Got: "
                       (pr-str (:reason (:diagnostic (xray-mount/status))))))
              ;; ---- and it really painted --------------------------------
              (is (some? (.getElementById js/document "rf-xray-root"))
                  "a real mount root is in the document")
              (is (= 1 (.-childElementCount host))
                  (str "inside the host's own slot, which now holds exactly "
                       "the one node Xray put there. Got: "
                       (.-childElementCount host))))))))))

;; ===========================================================================
;; The second family — the same verb, on a ratom-family substrate
;; ===========================================================================

(deftest the-public-mount-succeeds-on-a-ratom-family-substrate
  (testing "the OTHER adapter family through the same public
            verb, same host element, same registrations. It pairs with the
            row above to say the public `open!` mounts on both families
            with the installed adapter the only difference between the
            two — and it is the only place the ratom-family host is driven
            through the public verb at all, the six-criteria arms mounting
            Fresco's root directly instead."
    (if-not (browser?)
      (is true "skipped: the :browser-test runner drives the real mount")
      (do
        (rf/init! rf.adapter.reagent/adapter)
        (registry/register-xray-handlers!)
        (with-layout-host
          (fn [host]
            (let [ret (xray-mount/open!)]
              (is (= true (xray-mount/mounted?))
                  (str "`open!` mounted on a ratom-family substrate. Returned: "
                       (pr-str (dissoc ret :node :unmount))))
              ;; `clear-diagnostic!` leaves an `{:ok? true}` marker rather
              ;; than nil, so the literal to compare against is the FLAG,
              ;; not the slot's emptiness.
              (is (= true (:ok? (:diagnostic (xray-mount/status))))
                  (str "with the diagnostic slot reporting health rather than a "
                       "refusal. Got: "
                       (pr-str (:diagnostic (xray-mount/status)))))
              (is (nil? (:reason (:diagnostic (xray-mount/status))))
                  (str "and naming no refusal reason. Got: "
                       (pr-str (:reason (:diagnostic (xray-mount/status))))))
              (is (some? (.getElementById js/document "rf-xray-root"))
                  "and a real mount root is in the document")
              (is (= 1 (.-childElementCount host))
                  (str "inside the host's own slot, which now holds exactly the "
                       "one node Xray put there. Got: "
                       (.-childElementCount host))))))))))

;; ===========================================================================
;; The same family, the OTHER ratom runtime — reagent-slim through the
;; public verb
;; ===========================================================================

(deftest the-public-mount-succeeds-on-a-slim-ratom-family-substrate
  (testing "the ratom family's OTHER runtime through the same
            public verb, same host element, same registrations. The row
            above installs stock Reagent; this one installs reagent-slim,
            which `tools/xray/deps.edn` carries as Xray's DEFAULT
            transitive adapter and which is therefore the ratom build a
            host most easily ends up on without choosing it.

            It is coverage rather than a third helping of the same thing.
            A hiccup->React crossing that names stock Reagent's
            `as-element` STATICALLY paints the islands with the wrong
            ratom build under THIS adapter and the chrome comes up blank —
            a failure specific to the pairing and invisible to the Reagent
            row above it. The six-criteria arms witness the crossing on
            Xray's OWN root; this row is the only place it is witnessed
            through the PUBLIC door.

            Caught for the reason the element-shaped row catches: this
            adapter's failure mode is a render-phase THROW, and an
            uncaught one would swallow every assertion below it."
    (if-not (browser?)
      (is true "skipped: the :browser-test runner drives the real mount")
      (do
        (rf/init! rf.adapter.reagent-slim/adapter)
        (registry/register-xray-handlers!)
        (with-layout-host
          (fn [host]
            (let [ret (try (xray-mount/open!)
                           (catch :default e
                             (is false
                                 (str "`open!` THREW on the slim ratom-family "
                                      "substrate: "
                                      (pr-str (:rf.error/id (ex-data e)
                                                            (ex-message e)))
                                      ". `substrate/as-element` reads the "
                                      "hiccup->React walk off the INSTALLED "
                                      "adapter; a throw here says that "
                                      "crossing lost the frame."))
                             ::threw))]
              (is (= true (xray-mount/mounted?))
                  (str "`open!` mounted on the slim ratom-family substrate. "
                       "Returned: " (pr-str (if (map? ret)
                                              (dissoc ret :node :unmount)
                                              ret))))
              (is (= true (:ok? (:diagnostic (xray-mount/status))))
                  (str "with the diagnostic slot reporting health rather than a "
                       "refusal. Got: "
                       (pr-str (:diagnostic (xray-mount/status)))))
              (is (nil? (:reason (:diagnostic (xray-mount/status))))
                  (str "and naming no refusal reason. Got: "
                       (pr-str (:reason (:diagnostic (xray-mount/status))))))
              (is (some? (.getElementById js/document "rf-xray-root"))
                  "and a real mount root is in the document")
              (is (= 1 (.-childElementCount host))
                  (str "inside the host's own slot, which now holds exactly the "
                       "one node Xray put there. Got: "
                       (.-childElementCount host))))))))))
