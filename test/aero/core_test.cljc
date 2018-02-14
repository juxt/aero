;; Copyright © 2015-2017, JUXT LTD.

(ns aero.core-test
  #?(:clj (:require [aero.core :refer :all]
                    [clojure.edn :as edn]
                    [clojure.test :refer :all]
                    [clojure.java.io :as io]))
  #?(:clj (:import [aero.core Deferred]))
  #?(:cljs (:require [aero.core :refer [read-config reader Deferred] :refer-macros [deferred]]
                     [cljs.tools.reader :as edn]
                     [cljs.test :refer [deftest is testing]]
                     [goog.string :as gstring]
                     goog.string.format)))

(def network-call-count (atom 0))

(defn env [s]
  #?(:clj (System/getenv (str s)))
  #?(:cljs (aget js/process.env s)))

(defmethod reader 'my/expensive-network-call
  [_ tag value]
  (deferred
    (swap! network-call-count inc)
    (inc value)))

(defmethod reader 'my/flavor
  [opts tag value]
  (if (= value :favorite) :chocolate :vanilla))

(deftest basic-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= "Hello World!" (:greeting config))))
  (testing "Reading empty config returns nil"
    (is (= nil (read-config "test/aero/empty-config.edn")))))

(defn source [path]
  #?(:clj (io/reader path)
     :cljs path))

(deftest hostname-test
  (is (= {:color "green" :weight 10}
         (read-config
          (source "test/aero/hosts.edn")
          {:profile :default :hostname "emerald"})))
  (is (= {:color "black" :weight nil}
         (read-config (source "test/aero/hosts.edn")
                      {:profile :default :hostname "granite"})))
  (is (= {:color "white" :weight nil}
         (read-config (source "test/aero/hosts.edn")
                      {:profile :default :hostname "diamond"}))))

(deftest define-new-type-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= :chocolate (:flavor config)))))

(deftest join-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= (#?(:clj format :cljs gstring/format) "Terminal is %s" (env "TERM"))
           (:dumb-term config)))
    (is (= (#?(:clj format :cljs gstring/format) "Terminal is %s" "smart")
           (:smart-term config)))))

#?(:clj
   (deftest test-read
     (let [x [:foo :bar :baz]
           _ (System/setProperty "DUMMY_READ" (str x))
           config (read-config "test/aero/config.edn")]
       (is (= x (:test-read-str config)))
       (is (= x (:test-read-env config)))
       (System/clearProperty "DUMMY_READ"))))

(deftest envf-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= (#?(:clj format :cljs gstring/format) "Terminal is %s" (env "TERM"))
           (:dumb-term-envf config)))))

#?(:clj
   (deftest prop-test
     (let [config (read-config "test/aero/config.edn")]
       (is (= "dummy" (:triple-or config)))
       (is (nil? (:prop config))))
     (System/setProperty "DUMMY" "ABC123")
     (let [config (read-config "test/aero/config.edn")]
       (is (= "ABC123" (:triple-or config)))
       (is (= "ABC123" (:prop config))))
     (System/clearProperty "DUMMY")))

(deftest numeric-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= 1234 (:long config)))
    (is (= 4567.8 (:double config))))
  #?@(:clj [(System/setProperty "FOO" "123")
           (let [config (read-config "test/aero/long_prop.edn")]
             (is (= 123 (:long-prop config))))
           (System/clearProperty "FOO")]))

(deftest keyword-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= :foo/bar (:keyword config)))
    (is (= :foo/bar (:already-a-keyword config)))
    (is (= :abc (:env-keyword config)))))

(deftest boolean-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= true (:True-boolean config)))
    (is (= true (:true-boolean config)))
    (is (= false (:trivial-false-boolean config)))
    (is (= false (:nil-false-boolean config)))
    (is (= false (:false-boolean config)))))

(deftest format-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= (#?(:clj format :cljs gstring/format) "My favorite flavor is %s %s" (env "TERM") :chocolate)
           (:flavor-string config)))))

(deftest ref-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= (get-in config [:greeting])
           (:test config)))))

(deftest complex-ref-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= (get-in config [:refer-me :a :b 1234])
           (:complex-ref config)))))

(deftest remote-file-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= (get-in config [:remote :greeting])
           "str"))))

(deftest nested-ref-test
  (let [config (read-config "test/aero/config.edn" {})]
    (is (= "Hello World!" (get-in config [:test-nested])))))

(deftest profile-test
  (let [config (read-config "test/aero/config.edn" {:profile :dev})]
    (is (= 8000 (:port config))))
  (let [config (read-config "test/aero/config.edn" {:profile :prod})]
    (is (= 80 (:port config)))))

(deftest dummy-test
  (let [config (read-config "test/aero/config.edn" {:profile :dev})]
    (is (= "dummy" (:dummy config)))))

#?(:clj
   (deftest resolver-tests
     (let [source (io/resource "aero/includes.edn")]
       (is (read-config source {:profile :relative}))
       (is (read-config source {:profile :relative-abs}))
       (is (read-config source {:profile :resource :resolver resource-resolver}))
       (is (read-config source {:profile :file :resolver root-resolver}))
       (is (read-config (-> source slurp java.io.StringReader.)
                        {:profile :relative-abs}))
       (is (read-config source {:profile  :map
                                :resolver {:sub-includes (io/resource "aero/sub/includes.edn")
                                           :valid-file   (io/resource "aero/valid.edn")}}))
       (is (:aero/missing-include (read-config source {:profile :file-does-not-exist}))))))

#?(:clj
   (deftest dangling-ref-test
     (is
      (=
       {:user {:favorite-color :blue}
        :gardner {:favorite-color :blue}
        :karl {:favorite-color :blue}
        :color :blue}
       (read-config (java.io.StringReader.
                     (binding [*print-meta* true]
                       (pr-str {:user ^:ref [:karl]
                                :gardner {:favorite-color ^:ref [:color]}
                                :karl ^:ref [:gardner]
                                :color :blue}))))))))

(deftest deferred-test
  (is
   (instance? Deferred (deferred (+ 2 2))))
  ;; The basic idea here is to ensure that the #expensive-network-call
  ;; tag literal is called (because it increments its value). This
  ;; also tests the Deferred functionality as a consequence.
  (let [config (read-config "test/aero/config.edn")]
    (is (= (get-in config [:network-call])
           8))))

(deftest default-reader-combo-test
  (let [config (read-config "test/aero/default-reader.edn")]
    (is (= #inst "2013-07-09T18:05:53.231-00:00" (:date config)))))

(deftest refs-call-once-test
  ;; The purpose of this test is to defend against naïve copying of references
  ;; instead of resolving it early
  (let [before-call-count @network-call-count
        config (read-config "test/aero/config.edn")]
    (is (= (inc before-call-count) @network-call-count))))

#?(:clj
   (defmacro return-stderr-stream [& body]
     `(let [sw# (java.io.StringWriter.)]
        (binding [*err* sw#]
          ~@body)
        (str sw#))))

#?(:clj
   (deftest deprecation-test
     (is (= (return-stderr-stream
             (read-config "test/aero/deprecated-config.edn"))
            (clojure.string/join "\n"
                                 ["WARNING: #ref is decrecated; use #aero/ref instead."
                                  "WARNING: #ref is decrecated; use #aero/ref instead."
                                  "WARNING: #ref is decrecated; use #aero/ref instead."
                                  "WARNING: #ref is decrecated; use #aero/ref instead."
                                  "WARNING: #ref is decrecated; use #aero/ref instead."
                                  "WARNING: #ref is decrecated; use #aero/ref instead."
                                  "WARNING: #ref is decrecated; use #aero/ref instead."
                                  "WARNING: #long is decrecated; use #aero/long instead."
                                  "WARNING: #ref is decrecated; use #aero/ref instead."
                                  "WARNING: #ref is decrecated; use #aero/ref instead."
                                  "WARNING: #ref is decrecated; use #aero/ref instead."
                                  "WARNING: #ref is decrecated; use #aero/ref instead."
                                  "WARNING: #include is decrecated; use #aero/include instead."
                                  "WARNING: #env is decrecated; use #aero/env instead."
                                  "WARNING: #or is decrecated; use #aero/or instead."
                                  "WARNING: #join is decrecated; use #aero/join instead."
                                  "WARNING: #double is decrecated; use #aero/double instead."
                                  "WARNING: #hostname is decrecated; use #aero/hostname instead."
                                  "WARNING: #profile is decrecated; use #aero/profile instead."
                                  "WARNING: #merge is decrecated; use #aero/merge instead."
                                  "WARNING: #keyword is decrecated; use #aero/keyword instead."
                                  "WARNING: #read-edn is decrecated; use #aero/read-edn instead."
                                  "WARNING: #prop is decrecated; use #aero/prop instead."
                                  "WARNING: #envf is decrecated; use #aero/envf instead."
                                  "WARNING: #user is decrecated; use #aero/user instead."
                                  "WARNING: #boolean is decrecated; use #aero/boolean instead."
                                  ""])))))
