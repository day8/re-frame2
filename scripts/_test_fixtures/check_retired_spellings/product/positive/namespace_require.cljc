;; POSITIVE fixture (rule g) — the retired namespace, in live source.
;;
;; The rename moved the product's namespace to `re-frame.fresco` and its munged
;; path to `re_frame/fresco/`. A require naming the old namespace resolves to
;; nothing and is the plainest reintroduction there is.
;;
;; EXACTLY ONE line below may carry the retired name. This header deliberately
;; does not spell it: rule (g) reads RAW lines and masks nothing, so a comment
;; naming the product is a finding like any other — which is itself the claim
;; this fixture's expectation of 1 is making.
(ns re-frame.example.view
  (:require [re-frame.hicasso :as h]))
