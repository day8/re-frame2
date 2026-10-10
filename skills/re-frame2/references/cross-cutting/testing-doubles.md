# Replacing behaviour in re-frame2 tests

Load when a test must **replace registered behaviour**, not just stub one effect for one dispatch: run a different registration set, record which events a frame ran, mock an event or subscription, or guard that something is never touched. The fixture, `dispatch-sync`, the assertions and per-call fx stubs are [`testing.md`](testing.md); this leaf builds on them.

**Contents:** [behaviour isolation](#behaviour-isolation-in-tests--image-not-a-global-install) · [test doubles](#test-doubles) · [why `with-redefs` misses](#why-with-redefs-misses-macro-call-sites-and-compiled-arities) · [mocks under explicit images](#mocks-under-explicit-images) · [fail-if-called guards](#fail-if-called-guards-a-receipt-not-a-throw)

## Behaviour isolation in tests — image, not a global install

A test that needs a *different instruction set* (a fake HTTP fx, a swapped coeffect supplier, a narrower route table) wants a different **image**, not a process-global registrar mutation. Those are image changes — they produce another image generation rather than mutating shared state under the running frame. The shape (the one EP-0024 `make-frame` constructor over `:images` — returns the live frame value; see [`fundamentals/images.md`](../fundamentals/images.md)):

```clojure
(deftest cart-add-isolated
  (rf/with-new-frame [frame (rf/make-frame
                              {:images     [(rf/image {:select-ns {:include ["shop.cart.**"
                                                                             "shop.test-doubles.**"]}})]
                               :initial-events [[:rf/set-db {:cart/items []}]]})]
    (rf/dispatch-sync [:cart/add "SKU-1"] {:frame frame})
    (is (= ["SKU-1"] @(rf/subscribe [:cart/items] {:frame frame})))))
```

- **The frame is a local frame value** (no `:id`) — it never claims a *public* frame id, but it is **not** discarded when the test returns. `make-frame` mints a process-unique `:rf.frame/…` address and registers the frame, so it stays in `rf/frame-ids` **until destroyed**; a bare unreleased `make-frame` leaks and contaminates later tests. `make-frame` frames are **caller-owned** — wrap them in `rf/with-new-frame` (eval-bind-run-destroy; the value carries its exact incarnation token, so its `destroy-frame!` on exit — success or throw — tears down that incarnation only). `make-frame` returns the frame value (the lifecycle token); a test passes it directly (or its id) to `dispatch-sync` / `subscribe` — both accept either form, no accessor needed. That is the direct-frame-value test pattern (EP-0024).
- **Assert what you overrode.** Read the `:rf.gen/shadows` report on `rf/frame-generation` — a swap is then data the test states rather than last-writer-wins on a shared table. There is no `:replace` / `:replace-standard` declared-winner key; image order is the only mechanism.
- For an ordinary single-frame test, keep it on `make-reset-runtime-fixture` + `with-new-frame`; pass `:images [...]` only to isolate behaviour. The composition rules behind that — override through a later image, isolate *state* with a fresh frame — are [`fundamentals/images.md` §Composing patterns](../fundamentals/images.md).

## Test doubles

A double has to reach the code under test, and the test has to fail when it does not. Pick the public seam for what the test checks:

- *which events a frame ran* — a recorder interceptor on the frame's `:interceptors` ([Spec 008 §Recording dispatched events](https://github.com/day8/re-frame2/blob/main/spec/008-Testing.md#recording-dispatched-events));
- *what a handler would dispatch or effect, without running it* — a per-call `:fx-overrides` ([`testing.md` §`:fx-overrides` per-call function value](testing.md#fx-overrides-per-call-function-value));
- *what an event or subscription does* — a replacement registration in a final mock image ([§Mocks under explicit images](#mocks-under-explicit-images)).

Two rules make a double that intercepts nothing visible: each double **asserts that it was reached** — `(is (= [[:cart/save]] @seen))`, never only an absence — and the **compiled suite runs** before the test is trusted, because the arity trap below exists only in the ClojureScript build.

### Why `with-redefs` misses: macro call sites and compiled arities

`(rf/dispatch …)`, `(rf/dispatch-sync …)` and `(rf/subscribe …)` in call position are macros. Their expansion calls an internal `^:no-doc` alias rather than the var you named, so `(with-redefs [rf/dispatch …] …)` records nothing while the real event runs. The alias is not a seam to redef either; the comment above `dispatch-impl` in [`re_frame/core.cljc`](https://github.com/day8/re-frame2/blob/main/implementation/core/src/re_frame/core.cljc) keeps it internal. Use the seams above. This trap shows on both hosts.

A redef does reach code that reads a function as a value while the redef is live — a ClojureScript `rf/dispatch` passed as a callback, or the app's own `defn`. There the replacement needs the original's arity set. Compiled ClojureScript calls a multi-arity or variadic `defn` through its `cljs$core$IFn$_invoke$arity$N` or `$arity$variadic` entry, and a replacement of a different shape lacks that entry: the build compiles, then the call throws `…arity$N is not a function` or the double records nothing. Mirror the arglists — `(fn ([ev] …) ([ev opts] …))` for `([ev] [ev opts])`, and the same `&` rest for a variadic fn.

```clojure
(defn save! [] (rf/dispatch-sync [:cart/save]))   ; code under test: a macro call site

(deftest save-dispatches                         ; record through the frame's :interceptors
  (let [seen (atom [])]
    (rf/reg-interceptor :test/recorder
      {:before (fn [ctx] (swap! seen conj (-> ctx :coeffects :event)) ctx)})
    (rf/with-new-frame [_f (rf/make-frame {:interceptors [:test/recorder]})]
      (save!)
      (is (= [[:cart/save]] @seen)))))
```

The recorder observes and the real handler still runs. When the test must not run it, also register a mock handler in a final mock image.

### Mocks under explicit images

A mock registered in the test body reaches the reset fixture's ambient frame, which resolves the live registrar, and a default-image frame while no other loaded namespace registers the mock's id. It does not beat a product registration on a frame the test makes: a frame over explicit images ([`fundamentals/images.md`](../fundamentals/images.md)) resolves only what its images select, so the body registration never reaches it, and on the default image the duplicate fails a frame made afterwards with `:rf.error/image-duplicate-id` while a frame made before it keeps its prior generation — the product registration — and reports one `:rf.error/reprojection-failed`. Put the mock in a final image that selects no product namespace, and give the test frame an `:id`. To change the mock part-way through a test, re-call `make-frame` on that `:id` with the new image: same-id re-construction is the public image hot-reload — it keeps app-db, does not re-fire `:initial-events`, and evicts the cached subscriptions whose definition changed.

```clojure
(def product-image (rf/image {:id :shop/image :select-ns {:include ["shop.**"]}}))

(defn mock-image [price]                    ; final and disjoint: inline, selects no namespace
  (rf/image {:id :test/mocks :registrations {:reg-sub [[:shop/price (fn [_db _q] price)]]}}))

(defn shop-frame [price]                    ; the whole config, re-supplied on every call
  {:id :test/shop :preset :test
   :images [product-image (mock-image price)]
   :initial-events [[:rf/set-db {:shop/items []}]]})

(deftest price-follows-the-mock
  (rf/make-frame (shop-frame :a))
  (rf/dispatch-sync [:shop/add "SKU-1"] {:frame :test/shop})
  (is (= :a @(rf/subscribe [:shop/price] {:frame :test/shop})))    ; now cached
  (rf/make-frame (shop-frame :b))           ; same :id, new :images
  (is (= :b @(rf/subscribe [:shop/price] {:frame :test/shop})))    ; subscribe again after the swap
  (is (= ["SKU-1"] (:shop/items (rf/app-db-value :test/shop)))))   ; app-db kept
```

- **Order the mock last.** Selected before the product image it loses silently: the product definition resolves, and `(:rf.gen/shadows (rf/frame-generation :test/shop))` names the mock as the shadowed side. Assert that report when the override matters.
- **Keep the double out of the product image.** A namespace-authored double selected into the same image as the product registration fails frame creation with `:rf.error/image-duplicate-id`.
- **Cleanup has two halves.** The reset fixture, or a `snapshot-registrar` / `restore-registrar!` bracket, rolls back `reg-*` writes and leaves live frames alone — restoring the registrar after a re-image leaves the frame mocked. `make-reset-runtime-fixture` also clears every frame after each test (an `:async? true` suite does it in `:after`, once `done` has run); a test outside that fixture destroys its frame with `rf/destroy-frame!`.

**A recorder under explicit images** has to be selected too, or `make-frame` fails with `:rf.error/unregistered-interceptor`. It cannot go in a mock image's inline `:registrations`: those sections accept only `:reg-event`, `:reg-sub`, `:reg-fx` and `:reg-cofx`, and an inline `:reg-interceptor` section fails `rf/image` with `:rf.error/invalid-image`. Keep the `reg-interceptor` at the top level of the test namespace instead, and select that namespace in a final image:

```clojure
(def seen (atom []))

(rf/reg-interceptor :test/recorder          ; top level of the test namespace, here shop.cart-test
  {:before (fn [ctx] (swap! seen conj (-> ctx :coeffects :event)) ctx)})

(def recorder-image (rf/image {:id :test/recorder :select-ns {:include ["shop.cart-test"]}}))

(deftest save-dispatches-under-explicit-images
  (reset! seen [])
  (rf/with-new-frame [_f (rf/make-frame {:images [product-image recorder-image]
                                         :interceptors [:test/recorder]})]
    (save!)
    (is (= [[:cart/save]] @seen))))
```

The normative image-composition contract for tests is [Spec 008 §Hermetic-frame testing](https://github.com/day8/re-frame2/blob/main/spec/008-Testing.md#hermetic-frame-testing--a-fresh-frame-composed-from-images).

### Fail-if-called guards: a receipt, not a throw

A double whose job is to fail the test if a subscription is touched at all is an absence guard, not a value mock, and a throwing replacement registration does not implement it. The framework recovers the throw: a throwing computation reports `:rf.error/sub-exception` and the subscription reads `nil` ([errors.md §A subscription throws](https://github.com/day8/re-frame2/blob/main/docs/core/errors.md#a-subscription-throws-or-reads-one-that-isnt-there)), and a throw in a parametric `:inputs` fn is recovered too, as `:rf.error/sub-input-fn-exception` — so the forbidden access passes either way.

Keep the check as an observation. Give the guard a receipt in a final mock image, prove the receipt is reached with a forced positive control, then reset it before the product operation and assert it stayed empty. The receipt counts computation-body calls, not `subscribe` requests, and a cached slot answers without running its body ([subscriptions.md §Lifecycle](https://github.com/day8/re-frame2/blob/main/docs/core/subscriptions.md#lifecycle-a-sub-exists-only-while-something-watches)), so a cached `nil` reads as zero calls. Start the product operation from a fresh frame or after [`rf/clear-sub-cache!`](https://github.com/day8/re-frame2/blob/main/docs/api/re-frame.core.md#clear-sub-cache), because the positive control itself caches the slot.

```clojure
(defn line-total [frame qty price]          ; code under test: subscribes only when no price is supplied
  (* qty (or price @(rf/subscribe [:shop/price] {:frame frame}))))

(deftest supplied-price-skips-the-sub
  (let [calls (atom [])
        guard (rf/image {:id :test/guard    ; final and disjoint: records, returns nil, never throws
                         :registrations {:reg-sub [[:shop/price (fn [_db q] (swap! calls conj q) nil)]]}})]
    (rf/make-frame {:id :test/shop :preset :test :images [product-image guard]})
    @(rf/subscribe [:shop/price] {:frame :test/shop})   ; forced positive control
    (is (= [[:shop/price]] @calls))
    (rf/clear-sub-cache! :test/shop)                    ; the control cached the slot
    (reset! calls [])
    (is (= 30 (line-total :test/shop 3 10)))            ; the product operation: price supplied
    (is (= [] @calls))))                                ; no body call
```

A guard that keeps its throw can count its `:rf.error/sub-exception` records on a `:trace` listener instead ([errors.md §Test the structure](https://github.com/day8/re-frame2/blob/main/docs/core/errors.md#test-the-structure-not-the-string)); those are dev-only and count the same body calls. Either way an empty receipt is evidence only for the path the test exercised, never for a branch or a render that did not run. Keep direct tests of the application's own pure guard functions where their arguments or messages are part of the contract, and never expose registrar metadata or an internal alias to reach a registered function.

---

*Derived from `implementation/core/src/re_frame/test_support.cljc` (the reset fixture and registrar snapshot), `implementation/core/src/re_frame/image_assembly.cljc` (image assembly and `:rf.error/image-duplicate-id`) and `implementation/core/src/re_frame/live_frame.cljc` (reprojection of live frames on `reg-*`) @ main. Re-verify after test-support or image-assembly changes.*
