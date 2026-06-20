;;;; tests/runtime/parse_view_id_test.clj
;;;;
;;;; Babashka-runnable verification of `parse-view-id` from
;;;; `preload/re_frame2_pair/runtime.cljs`.
;;;;
;;;; Why a parallel implementation lives here:
;;;;
;;;;   `preload/re_frame2_pair/runtime.cljs` is a CLJS-only file loaded
;;;;   into the consumer app via shadow-cljs `:devtools :preloads`. It
;;;;   aliases the CANONICAL reader `re-frame.source-coords/parse-view-id`
;;;;   (the source-coord contract owner — the inverse of `format-view-id`,
;;;;   the data-rf-view analogue of `parse-source-coord`). Loading that core
;;;;   `.cljc` ns under bb would drag in re-frame.interop + the editor-uri
;;;;   sibling, so this script keeps a
;;;;   dependency-free mirror of the read rule (leading-colon -> keyword;
;;;;   first `/` -> namespaced keyword; else raw string) and asserts the
;;;;   round-trip against a mirror of `format-view-id` so format drift shows
;;;;   up under `bb`. The canonical CLJS-side coverage lives next to the
;;;;   reader in `implementation/core/test/re_frame/source_coords_cljs_test.cljs`.
;;;;
;;;; Run:    bb tests/runtime/parse_view_id_test.clj
;;;; Exit:   0 = pass, non-zero = fail.

(ns parse-view-id-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

;; ---------------------------------------------------------------------------
;; Mirror of the canonical `re-frame.source-coords/parse-view-id`
;; (which `preload/re_frame2_pair/runtime.cljs` aliases as `parse-view-id`).
;;
;; KEEP IN SYNC WITH implementation/core/src/re_frame/source_coords.cljc.
;; Logic is identical (both use clojure.string/index-of, which is portable
;; CLJ/CLJS). The leading-colon test, first-slash split, and raw-string
;; fallthrough are the contract under test.
;; ---------------------------------------------------------------------------

(defn parse-view-id
  "See re-frame.source-coords/parse-view-id for the canonical version.
   Returns the registry id keyword, the raw string for a non-keyword id,
   or nil for nil / non-string input."
  [attr-val]
  (cond
    (or (nil? attr-val) (not (string? attr-val)))
    nil

    (and (pos? (count attr-val)) (= ":" (subs attr-val 0 1)))
    (let [body  (subs attr-val 1)
          slash (str/index-of body "/")]
      (if (nil? slash)
        (keyword body)
        (keyword (subs body 0 slash) (subs body (inc slash)))))

    :else
    attr-val))

;; ---------------------------------------------------------------------------
;; Stringified keyword ids — the common case (`(str id)` formats them).
;; ---------------------------------------------------------------------------

(deftest namespaced-keyword
  (testing "leading colon + slash → namespaced keyword (split at first /)"
    (is (= :rf.foo/bar (parse-view-id ":rf.foo/bar")))))

(deftest bare-keyword
  (testing "leading colon, no slash → unqualified keyword (legal at registrar)"
    (is (= :bare (parse-view-id ":bare")))))

(deftest dotted-and-hyphenated
  (testing "dotted ns + hyphenated name parse cleanly"
    (is (= :my-app.cart.view/apply-coupon-button
           (parse-view-id ":my-app.cart.view/apply-coupon-button")))))

;; ---------------------------------------------------------------------------
;; Non-keyword id — returned verbatim (unusual but legal).
;; ---------------------------------------------------------------------------

(deftest raw-string
  (testing "no leading colon → non-keyword id, returned verbatim"
    (is (= "raw-string" (parse-view-id "raw-string")))))

;; ---------------------------------------------------------------------------
;; Malformed / edge cases — must never throw; return nil.
;; ---------------------------------------------------------------------------

(deftest nil-and-non-string
  (testing "nil / non-string input returns nil — never throws"
    (is (nil? (parse-view-id nil)))
    (is (nil? (parse-view-id 42)))
    (is (nil? (parse-view-id :keyword)))
    (is (nil? (parse-view-id ["v" "e" "c"])))))

;; ---------------------------------------------------------------------------
;; Round-trip — (parse-view-id (format-view-id id)) recovers the id.
;; `format-view-id` is `(str id)`; mirror it here so format drift surfaces.
;; ---------------------------------------------------------------------------

(defn- format-view-id
  "Mirror of re-frame.adapter.context/format-view-id: `(str id)`."
  [id]
  (str id))

(deftest round-trip-keyword-ids
  (testing "parse(format id) recovers the registry id keyword"
    (doseq [id [:rf.foo/bar
                :bare
                :my-app.cart.view/apply-coupon-button]]
      (is (= id (parse-view-id (format-view-id id)))))))

;; ---------------------------------------------------------------------------
;; Run.
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'parse-view-id-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
