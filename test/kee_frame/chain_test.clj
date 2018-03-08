(ns kee-frame.chain-test
  (:require [clojure.test :refer :all]
            [kee-frame.chain :as chain]
            [clojure.string :as str]
            [re-frame.core :as rf])
  (:import (clojure.lang ExceptionInfo)))

(deftest can-translate-instruction-to-event-function
  (let [[do-fn [log-fn] [reg-fn]] (chain/make-step {:id   :event-id
                                                    :data {:db [[:path :to 0]]}})]
    (is (= 'do do-fn))
    (is (= 're-frame.core/console log-fn))
    (is (= 're-frame.core/reg-event-fx reg-fn))))

(deftest fx-event
  (testing "DB as side effect in FX handler"
    (let [handler (eval (chain/make-fx-event {:data `{:db [[:property [:kee-frame.core/params 0]]
                                                           [:property-transformed [:kee-frame.core/params 0 str/capitalize]]]}}))
          effect (handler {:db {}} [:event "badaboom"])]
      (is (= {:db {:property             "badaboom"
                   :property-transformed "Badaboom"}}
             effect))))

  (testing "HTTP without next in chain throws"
    (is (thrown? ExceptionInfo
                 (chain/make-fx-event {:data {:http-xhrio {:method :get
                                                           :uri    "site.com"}}}))))
  (testing "Automatically inserted next on HTTP"
    (let [handler (eval (chain/make-fx-event {:next-id :next/step
                                              :data    {:http-xhrio {:method :get
                                                                     :uri    "site.com"}}}))
          effect (handler {:db {}} [:some-event])]
      (is (= [:next/step] (-> effect :http-xhrio :on-success)))))

  (testing "Automatically inserted next on dispatch"
    (let [handler (eval (chain/make-fx-event {:next-id :next/step
                                              :data    {:db {:something "something"}}}))
          effect (handler {:db {}} [:some-event])]
      (is (= [:next/step] (-> effect :dispatch))))))

(deftest outer-api
  (testing "Can produce simple fx event"
    (let [handler (atom nil)]
      (with-redefs [rf/reg-event-fx (fn [_ _ h] (reset! handler h))]
        (chain/reg-chain :some-chain {:db [[:prop 0]]})
        (is (= {:db {:prop 0}} (@handler {} [:some-event :not-relevant]))))))

  (testing "Will pass on params from first event to rest of steps"
    (let [handlers (atom [])]
      (with-redefs [rf/reg-event-fx (fn [_ _ h] (swap! handlers conj h))]
        (chain/reg-chain :some/chain
                         {:db [[:prop [:kee-frame.core/params 0]]]}
                         {:http-xhrio {:uri (str "http://wg.no/" (name [:kee-frame.core/params 1]))}}
                         {:db [[:prop2 [:kee-frame.core/params 1]]]})

        (is (= {:db       {:prop :first-param}
                :dispatch [:some/chain-1 :first-param :second-param]}
               ((first @handlers) {} [:some-event :first-param :second-param])))

        (is (= {:http-xhrio {:on-success [:some/chain-2 :first-param :second-param]
                             :uri        "http://wg.no/second-param"}}
               ((second @handlers) {} [:some-event :first-param :second-param])))))))