;; Copyright © 2015, JUXT LTD.

(ns aero.core-test
  (:require [aero.core :refer :all]
            [clojure
             [edn :as edn]
             [test :refer :all]]
            [clojure.java.io :as io]))

(deftest basic-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= "Hello World!" (:greeting config))))
  (testing "Reading empty config returns nil"
    (is (= nil (read-config "test/aero/empty-config.edn")))))

(deftest hostname-test
  (is (=
       (edn/read
        {:default (partial reader {:profile :default
                                   :hostname "emerald"})}
        (java.io.PushbackReader. (io/reader "test/aero/hosts.edn")))
       {:color "green" :weight 10}))
  (is (=
       (edn/read
        {:default (partial reader {:profile :default
                                   :hostname "granite"})}
        (java.io.PushbackReader. (io/reader "test/aero/hosts.edn")))
       {:color "black" :weight nil}))
  (is (=
       (edn/read
        {:default (partial reader {:profile :default
                                   :hostname "diamond"})}
        (java.io.PushbackReader. (io/reader "test/aero/hosts.edn")))
       {:color "white" :weight nil})))

(defmethod reader 'myflavor
  [opts tag value]
  (if (= value :favorite)
    :chocolate
    :vanilla))

(deftest define-new-type-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= :chocolate (:flavor config)))))

(deftest envf-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= (format "Terminal is %s" (System/getenv "TERM"))
           (:dumb-term config)))
    (is (= (format "Terminal is %s" "smart")
           (:smart-term config)))))

(deftest format-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= (format "My favorite flavor is %s %s" (System/getenv "TERM") :chocolate)
           (:flavor-string config)))))

(deftest ref-test
  (let [config (read-config "test/aero/config.edn")]
    (is (= (get-in config [:greeting])
           (:test config)))))

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

(deftest resolver-tests
  (is (= {:hello "world"}
         (read-config "resources/test-rel.edn")
         (read-config "resources/test-root.edn" {:resolver root-resolver})
         (read-config (io/resource "test-res.edn") {:resolver resource-resolver})
         (read-config "resources/test-res.edn"
                      {:resolver
                       {"sub/test-res.edn" "resources/sub/test-res.edn"
                        "hello.edn"        "resources/hello.edn"}}))))

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
                             :color :blue})))))))
