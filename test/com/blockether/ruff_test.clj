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

(deftest lint-reports-unused-import
  (let [ds   (ruff/lint "import os\nx = 1\n")
        f401 (first (filter #(= "F401" (:code %)) ds))]
    (is (contains? (set (map :code ds)) "F401"))
    (is (= {:code "F401" :row 1 :col 8 :end-row 1 :end-col 10 :is-fixable true}
           (dissoc f401 :message)))
    (is (str/includes? (:message f401) "imported but unused"))))

(deftest lint-clean-source-is-empty
  (is (= [] (ruff/lint "x = 1\n"))))

(deftest lint-select-replaces-default-rule-set
  (testing "E711 is not in ruff's default selection"
    (is (empty? (ruff/lint "x = 1\nif x == None: pass\n"))))
  (testing "explicit :select turns it on — string, keyword and collection forms"
    (doseq [sel ["E711" :E711 ["E711"] ["F" "E711"]]]
      (is (= ["E711"] (map :code (ruff/lint "x = 1\nif x == None: pass\n" {:select sel})))
          (str "select " (pr-str sel))))))

(deftest lint-ignore-subtracts
  (is (empty? (ruff/lint "import os\n" {:ignore "F401"})))
  (is (empty? (ruff/lint "import os\n" {:ignore [:F]}))))

(deftest lint-line-length-drives-e501
  (let [long-line "import os_this_is_a_very_long_module_name_indeed_yes\n"]
    (is (empty? (ruff/lint long-line {:select "E501"})))
    (is (= ["E501"] (map :code (ruff/lint long-line {:select "E501" :line-length 20}))))))

(deftest lint-honours-inline-noqa
  (is (empty? (ruff/lint "import os  # noqa: F401\n"))))

(deftest lint-reports-syntax-errors-as-diagnostics
  (let [ds (ruff/lint "def (((broken")]
    (is (seq ds))
    (is (every? #(= "invalid-syntax" (:code %)) ds))
    (is (every? #(false? (:is-fixable %)) ds))))

(deftest lint-throws-on-unknown-selector
  (is (thrown? clojure.lang.ExceptionInfo (ruff/lint "x = 1\n" {:select "NOPE9"}))))

(deftest lint-or-returns-the-default-on-failure
  (is (= :fallback (ruff/lint-or "x = 1\n" {:select "NOPE9"} :fallback)))
  (is (nil? (ruff/lint-or "x = 1\n" {:select "NOPE9"})))
  (testing "and the real diagnostics otherwise"
    (is (= ["F401"] (map :code (ruff/lint-or "import os\n"))))))

(deftest lint-messages-survive-escaping
  ;; the cdylib escapes \\, TAB and NEWLINE so each diagnostic is one TSV line;
  ;; backticks/quotes in ruff messages must come back verbatim.
  (let [msg (:message (first (ruff/lint "import os\n")))]
    (is (= "`os` imported but unused" msg))))

(deftest isolated-ignores-cwd-config
  ;; `--isolated` means a stray ruff.toml/pyproject.toml in the working dir
  ;; cannot change the result — line-length here is the only authority.
  (let [out (ruff/format "a_very_long_variable_name_here = another_long_name + yet_another_long_name_x"
                         {:line-length 40})]
    (is (str/includes? out "(\n"))))
