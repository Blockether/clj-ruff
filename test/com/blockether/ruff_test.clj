(ns com.blockether.ruff-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [com.blockether.ruff :as ruff]))

;; These run against the REAL ruff binary resolved from resources/prebuilds
;; (CI stages it before `clojure -X:test`) or a RUFF_NATIVE_PATH override.

(deftest resolves-binary
  (is (ruff/available?))
  (is (str/includes? (ruff/version) "ruff")))

(deftest wraps-long-call
  (let [out (ruff/format
              "result = some_function(argument_one, argument_two, argument_three, argument_four, keyword=value)"
              {:line-length 60})]
    (is (str/includes? out "some_function(\n"))
    (is (str/includes? out "    argument_one,"))
    ;; trailing magic comma on the wrapped call
    (is (str/includes? out "    keyword=value,\n)"))))

(deftest normalizes-style
  ;; black/ruff style: spaces around dict colons + after commas, double quotes.
  (is (= "x = {\"a\": 1, \"b\": 2}\n"
        (ruff/format "x={'a':1,'b':2}" {:line-length 88}))))

(deftest leaves-short-code-essentially-alone
  (is (= "y = 1 + 2\n" (ruff/format "y = 1 + 2" {}))))

(deftest format-throws-on-invalid-python
  (is (thrown? clojure.lang.ExceptionInfo (ruff/format "def (((broken" {}))))

(deftest format-or-falls-back-verbatim
  (testing "invalid python returns the original source unchanged"
    (is (= "def (((broken" (ruff/format-or "def (((broken" {})))))

(deftest isolated-ignores-cwd-config
  ;; `--isolated` means a stray ruff.toml/pyproject.toml in the working dir
  ;; cannot change the result — line-length here is the only authority.
  (let [out (ruff/format "a_very_long_variable_name_here = another_long_name + yet_another_long_name_x"
                         {:line-length 40})]
    (is (str/includes? out "(\n"))))
