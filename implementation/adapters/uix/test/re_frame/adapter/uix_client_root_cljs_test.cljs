(ns re-frame.adapter.uix-client-root-cljs-test
  "rf2-kuky.56 — the UIx adapter's reusable client root: `client-root`,
  `render!`, `unmount!` (Spec 006 §The client root). The node-safe half:
  inert allocation, the inert-handle no-ops, and the element-slot guard on
  the trio path. The behaviour that needs a real React Root — create-once /
  update-later, hydrate-once / update-later, unmount idempotence and the
  `dispose-adapter!` drain — is `re-frame.adapter.uix-client-root-dom-cljs-test`.

  WHY THE NODE HALF IS SHAPED DIFFERENTLY FROM REAGENT'S. The Reagent twin
  (`re-frame.adapter-client-root-cljs-test`) pins the call sequence by
  `with-redefs`-ing `reagent.dom.client`'s four fns. The React-hook spine
  mounts through the `react-dom/client` MODULE directly — a JS namespace,
  not a Var — so there is nothing to rebind, and the constructor-count
  proofs are read off the DOM in the browser twin instead.

  ns ends in `-cljs-test` so shadow-cljs's `:node-test` build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            ["react" :as React]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter :ambient-frame nil}))

;; ---- 1. inert allocation ---------------------------------------------------

(deftest client-root-does-no-dom-work
  (testing "allocating a handle touches no DOM API — it runs on Node, where
            there is no `document` to touch, which is the whole point of the
            `defonce` boot idiom"
    (let [h (rf.adapter.uix/client-root)]
      (is (some? h) "client-root returns a handle")
      (is (not (identical? h (rf.adapter.uix/client-root)))
          "each call allocates its own handle — there is no process-global
           registry of handles or mount points")))
  (testing "an inert handle's unmount! is a no-op returning nil"
    (let [h (rf.adapter.uix/client-root)]
      (is (nil? (rf.adapter.uix/unmount! h)))
      (is (nil? (rf.adapter.uix/unmount! h))
          "and stays a no-op however many times it is called"))))

;; ---- 2. the trio is on the public surface, in Reagent's shapes -------------

(deftest the-trio-is-published-with-the-reagent-shapes
  (testing "all three names resolve on re-frame.adapter.uix"
    (is (fn? rf.adapter.uix/client-root))
    (is (fn? rf.adapter.uix/render!))
    (is (fn? rf.adapter.uix/unmount!)))
  (testing "render! takes BOTH the 3- and 4-arities Spec 006 §The client root
            names, so `{:hydrate? true}` is an optional trailing arg"
    ;; Exercised rather than introspected: each arity is called with hiccup,
    ;; which the element-slot guard refuses BEFORE any Root is created. A
    ;; missing arity would raise a DIFFERENT error (arity mismatch), so the
    ;; guard id arriving from both calls is what pins both arities.
    (doseq [[label thrown]
            [["3-arity" (try (rf.adapter.uix/render!
                               (rf.adapter.uix/client-root) [:div] nil) nil
                             (catch :default e e))]
             ["4-arity" (try (rf.adapter.uix/render!
                               (rf.adapter.uix/client-root) [:div] nil
                               {:hydrate? true}) nil
                             (catch :default e e))]]]
      (is (= :rf.error/hiccup-on-element-render-slot
             (:rf.error/id (ex-data thrown)))
          (str label " reaches the element-slot guard, so the arity exists")))))

;; ---- 3. the element-slot guard rides the trio path ------------------------

(deftest render-bang-refuses-cljs-data-in-the-element-slot
  (testing "hiccup / seq / map through render! raises ONE structured
            :rf.error/hiccup-on-element-render-slot, thrown BEFORE any Root is
            created (so this is node-safe) and carrying no tree content"
    (doseq [[label tree] [["hiccup vector" [:div "hiccup-secret-xyzzy"]]
                          ["seq"           (list [:div "hiccup-secret-xyzzy"])]
                          ["map"           {:hiccup "hiccup-secret-xyzzy"}]]]
      (let [h      (rf.adapter.uix/client-root)
            thrown (try (rf.adapter.uix/render! h tree nil) nil
                        (catch :default e e))]
        (is (some? thrown) (str label " is rejected on the client-root path"))
        (when thrown
          (let [data (ex-data thrown)]
            (is (= :rf.error/hiccup-on-element-render-slot (:rf.error/id data))
                ":rf.error/id names the canonical error discriminator")
            (is (str/includes? (ex-message thrown)
                               "[:rf.error/hiccup-on-element-render-slot]")
                "the message carries the greppability token")
            (is (not (re-find #"xyzzy" (pr-str data)))
                "EP-0015: no tree content leaked into the ex-data")))
        (is (nil? (rf.adapter.uix/unmount! h))
            "the refused render left the handle inert — nothing to release"))))
  (testing "a legitimate React element passes the guard"
    ;; The positive leg stops at the guard: mounting needs a real container,
    ;; which the browser twin supplies.
    (let [h      (rf.adapter.uix/client-root)
          thrown (try (rf.adapter.uix/render!
                        h (React/createElement "div" nil "ok") nil)
                      nil
                      (catch :default e e))]
      (is (or (nil? thrown)
              (not= :rf.error/hiccup-on-element-render-slot
                    (:rf.error/id (ex-data thrown))))
          "a React element is never refused by the element-slot guard"))))
