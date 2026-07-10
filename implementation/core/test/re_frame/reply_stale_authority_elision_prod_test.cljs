(ns re-frame.reply-stale-authority-elision-prod-test
  "Per rf2-3fc89f.13 — the stale-delivery AUTHORITY boundary is a correctness
  boundary, so it MUST survive production elision (`:advanced` +
  `goog.DEBUG=false`). The capability is a provenance-bearing, opaque,
  non-serializable object compared by IDENTITY — it does not rely on any
  dev-gated assertion or `goog.DEBUG` branch, so the forgery must still fail
  loud and the legitimate framework/tool path must still deliver under an
  advanced-compiled bundle.

  Naming convention: files ending in `-elision-prod-test.cljs` are picked up
  ONLY by the `:browser-test-prod-elision` build (`:advanced` +
  `{goog.DEBUG false}`), via the shared `re-frame.prod-elision-runner`. Pure
  substrate — no runtime fixture needed."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.reply :as reply]))

(def ^:private carried {:g 1})
(def ^:private current {:g 2})

(deftest forged-authority-fails-loud-under-prod
  (testing "rf2-3fc89f.13: under `:advanced` + `goog.DEBUG=false`, a FORGED
            authority datum (an app/EDN/wire target spelling the namespaced key
            truthy) is NOT the capability, so `suppress` fails loud rather than
            deliver a stale envelope to app state — the identity boundary holds
            with no dev-gated code to elide"
    (let [forged {:event [:app/replied]
                  :dispatch-stale? true
                  :re-frame.reply/stale-authority true}]
      (is (false? (reply/stale-authority? forged))
          "a forged truthy datum is not identical? to the capability under prod")
      (let [id (try (reply/suppress forged carried current)
                    ::no-throw
                    (catch ExceptionInfo e (:rf.error/id (ex-data e))))]
        (is (= :rf.error/reply-unauthorized-stale-delivery id)
            "the forged app target fails loud under prod — it never delivers")))))

(deftest legitimate-capability-delivers-under-prod
  (testing "rf2-3fc89f.13: the framework/tool capability still authorises stale
            delivery under prod — `with-stale-authority` attaches the opaque
            capability object and `suppress` grants delivery by identity"
    (let [authd (reply/with-stale-authority {:event [:x] :dispatch-stale? true})]
      (is (true? (reply/stale-authority? authd))
          "the capability survives advanced compilation (identity intact)")
      (is (true? (:deliver? (reply/suppress authd carried current)))
          "the authorised framework/tool target receives the stale envelope under prod"))))

(deftest capability-stripped-from-durable-target-under-prod
  (testing "rf2-3fc89f.13: durable projection drops the capability under prod —
            a persisted target re-acquires no authority"
    (let [authd (reply/with-stale-authority {:event [:x] :dispatch-stale? true})]
      (is (false? (reply/stale-authority? (reply/durable-target authd)))
          "durable-target strips the capability under advanced compilation"))))
