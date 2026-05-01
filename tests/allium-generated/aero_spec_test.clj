(ns allium-generated.aero-spec-test
  "Tests generated from specs/aero.allium. Each deftest exercises one or more
  rules/invariants of the spec. Sources are passed as StringReader/File so
  tests are hermetic."
  (:require
   [aero.core :as aero :refer [read-config reader]]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import [java.io StringReader File]))

(defn- sr [s] (StringReader. s))

(defn- tmp-edn [s]
  (let [f (File/createTempFile "aero-gen" ".edn")]
    (.deleteOnExit f)
    (spit f s)
    f))

;; -------- env_tag --------------------------------------------------------

(deftest env-tag-resolves-to-os-env
  (let [present (some (fn [[k v]] (when (and v (seq v)) [k v]))
                      (System/getenv))]
    (testing "#env on a set variable returns its value"
      (when present
        (let [[k v] present
              cfg (read-config (sr (str "{:x #env " k "}")))]
          (is (= v (:x cfg))))))
    (testing "#env on an unset variable returns nil"
      (let [cfg (read-config (sr "{:x #env DEFINITELY_NOT_SET_AERO_TEST_VAR_XYZZY}"))]
        (is (nil? (:x cfg)))))))

;; -------- envf_tag -------------------------------------------------------

(deftest envf-tag-formats-with-env-vars
  (testing "#envf substitutes env vars into the format string"
    (let [cfg (read-config (sr "{:x #envf [\"hello %s\" DEFINITELY_NOT_SET_AERO_TEST_VAR_XYZZY]}"))]
      (is (= "hello " (:x cfg))))))

;; -------- prop_tag -------------------------------------------------------

(deftest prop-tag-resolves-jvm-property
  (try
    (System/setProperty "AERO_GEN_PROP" "value-42")
    (let [cfg (read-config (sr "{:x #prop AERO_GEN_PROP}"))]
      (is (= "value-42" (:x cfg))))
    (finally
      (System/clearProperty "AERO_GEN_PROP"))))

;; -------- coercion_tags --------------------------------------------------

(deftest coercion-tags-parse-strings
  (testing "#long parses to integer"
    (is (= 42 (:x (read-config (sr "{:x #long \"42\"}"))))))
  (testing "#double parses to double"
    (is (= 3.14 (:x (read-config (sr "{:x #double \"3.14\"}"))))))
  (testing "#keyword parses to keyword"
    (is (= :foo (:x (read-config (sr "{:x #keyword \"foo\"}"))))))
  (testing "#boolean parses to boolean"
    (is (true? (:x (read-config (sr "{:x #boolean \"true\"}")))))
    (is (false? (:x (read-config (sr "{:x #boolean \"false\"}")))))))

;; -------- include_tag ----------------------------------------------------

(deftest include-tag-splices-and-refs-resolve-against-root
  (let [included (tmp-edn "{:inner \"hello\" :back #ref [:top]}")
        top (tmp-edn (str "{:top 99 :sub #include \"" (.getAbsolutePath included) "\"}"))
        cfg (read-config (.getAbsolutePath top))]
    (testing "#include splices in the included value"
      (is (= "hello" (get-in cfg [:sub :inner]))))
    (testing "#ref inside an included file resolves against the top-level root"
      (is (= 99 (get-in cfg [:sub :back]))))))

;; -------- join_tag -------------------------------------------------------

(deftest join-tag-concatenates-parts
  (let [cfg (read-config (sr "{:x #join [\"a\" \"-\" \"b\" \"-\" 3]}"))]
    (is (= "a-b-3" (:x cfg)))))

;; -------- read_edn_tag ---------------------------------------------------

(deftest read-edn-tag-parses-edn-string
  (let [cfg (read-config (sr "{:x #read-edn \"[1 2 3]\"}"))]
    (is (= [1 2 3] (:x cfg)))))

;; -------- merge_tag ------------------------------------------------------

(deftest merge-tag-left-to-right-merge
  (let [cfg (read-config (sr "{:x #merge [{:a 1 :b 1} {:b 2 :c 3}]}"))]
    (is (= {:a 1 :b 2 :c 3} (:x cfg)))))

;; -------- or_tag ---------------------------------------------------------

(deftest or-tag-first-non-nil
  (testing "first non-nil wins"
    (is (= 5 (:x (read-config (sr "{:x #or [nil nil 5 6]}"))))))
  (testing "all nils -> nil"
    (is (nil? (:x (read-config (sr "{:x #or [nil nil]}")))))))

;; -------- ref_tag --------------------------------------------------------

(deftest ref-tag-resolves-against-root
  (let [cfg (read-config (sr "{:a 1 :b #ref [:a] :c #ref [:b]}"))]
    (is (= 1 (:a cfg)))
    (is (= 1 (:b cfg)))
    (testing "refs are recursive"
      (is (= 1 (:c cfg))))))

;; -------- profile_tag ----------------------------------------------------

(deftest profile-tag-selects-by-opts-profile
  (let [src #(sr "{:x #profile {:dev :d :prod :p :default :def}}")]
    (is (= :d (:x (read-config (src) {:profile :dev}))))
    (is (= :p (:x (read-config (src) {:profile :prod}))))
    (testing "falls back to :default when profile not matched"
      (is (= :def (:x (read-config (src) {:profile :other})))))
    (testing "no profile -> :default"
      (is (= :def (:x (read-config (src))))))))

;; -------- hostname_tag ---------------------------------------------------

(deftest hostname-tag-selects-by-host
  (let [src #(sr "{:x #hostname {\"alpha\" :a #{\"beta\" \"gamma\"} :bg :default :def}}")]
    (is (= :a (:x (read-config (src) {:hostname "alpha"}))))
    (testing "set membership matches"
      (is (= :bg (:x (read-config (src) {:hostname "beta"}))))
      (is (= :bg (:x (read-config (src) {:hostname "gamma"})))))
    (testing "fallback to :default"
      (is (= :def (:x (read-config (src) {:hostname "delta"})))))))

;; -------- user_tag -------------------------------------------------------

(deftest user-tag-selects-by-user
  (let [src #(sr "{:x #user {\"alice\" :a \"bob\" :b :default :def}}")]
    (is (= :a (:x (read-config (src) {:user "alice"}))))
    (is (= :b (:x (read-config (src) {:user "bob"}))))
    (is (= :def (:x (read-config (src) {:user "nobody"}))))))

;; -------- invariant resolution_is_pure -----------------------------------

(deftest resolution-is-pure
  (let [edn "{:a 1 :b #ref [:a] :c #join [\"x\" \"-\" \"y\"] :d #profile {:dev 1 :default 0}}"
        opts {:profile :dev}
        a (read-config (sr edn) opts)
        b (read-config (sr edn) opts)]
    (is (= a b))))

;; -------- invariant config_is_data ---------------------------------------

(deftest config-is-plain-data
  (let [cfg (read-config (sr "{:a 1 :b [1 2 3] :c {:d :e} :f #join [\"x\" \"y\"]}"))]
    (testing "no executable code; returned value is plain Clojure data"
      (letfn [(walk-no-fn? [v]
                (cond
                  (fn? v) false
                  (map? v) (every? walk-no-fn? (concat (keys v) (vals v)))
                  (coll? v) (every? walk-no-fn? v)
                  :else true))]
        (is (walk-no-fn? cfg))
        (is (= "xy" (:f cfg)))))))

;; -------- invariant tag_dispatch_is_open ---------------------------------

(defmethod reader 'allium-gen/double-it
  [_ _ value]
  (* 2 value))

(deftest tag-dispatch-is-open-for-extension
  (let [cfg (read-config (sr "{:x #allium-gen/double-it 21}"))]
    (is (= 42 (:x cfg)))))
