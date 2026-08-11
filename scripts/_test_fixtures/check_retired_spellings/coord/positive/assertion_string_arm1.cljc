(ns fixture.coord.positive.assertion-string-arm1
  "POSITIVE fixture (d/string): the `arm1.*` half, and a FULL coordinate rather
  than the bare `front.codec/` prefix — the rule must not depend on the member
  after `/` being absent.")

(deftest the-refusal-names-its-raising-site
  (is (= "arm1.mount/render!" (str (:where refusal)))))
