;; Copyright © 2015, JUXT LTD.

(ns aero.core-test
  (:require [aero.core :refer :all]
            [clojure
             [edn :as edn]
             [test :refer :all]]
            [clojure.java.io :as io]
            [schema.core :as s]))

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

(deftest path-test
  (let [config (read-config "test/aero/config.edn" {:path true
                                                    :transforms [:path]
                                                    :profile :test
                                                    :schema clojure.core/identity})]
    (is (= (get-in config [:greeting])
           (:test config)))))

(deftest schema-test
  (let [config (read-config "test/aero/withschema.edn"
                            {:transforms [:path :schema]
                             :schema {:greeting s/Str
                                      :hello [s/Any]}})]
    (is config)))

(deftest remote-file-test
  (let [config (read-config "test/aero/config.edn" {:path true
                                                    :transforms [:path]
                                                    :profile :test})]
    (is (= (get-in config [:remote :greeting])
           "str"))))
