#!/usr/bin/env bash
# Compiles every solution plus its tests and runs them. No JUnit or Maven needed, only a JDK (17+).
#   ./run_tests.sh                      all test classes
#   ./run_tests.sh LRUCacheTest ATMTest only those
#   VERBOSE=1 ./run_tests.sh            also print every passing test name
cd "$(dirname "$0")" || exit 1
rm -rf out && mkdir out
javac -d out *.java 2>&1 | grep -v "Picked up" && { echo "COMPILE FAILED"; exit 1; }

tests=("$@")
[ ${#tests[@]} -eq 0 ] && tests=($(ls *Test.java | grep -v '^MiniTest\.java$' | sed 's/\.java$//'))

total_pass=0; total_fail=0; failed_classes=()
for t in "${tests[@]}"; do
  out=$(java -cp out "$t" 2>&1 | grep -v "Picked up")
  summary=$(echo "$out" | grep -E "^$t: ")
  p=$(echo "$summary" | sed -E 's/.*: ([0-9]+) passed.*/\1/'); f=$(echo "$summary" | sed -E 's/.*, ([0-9]+) failed.*/\1/')
  if [ -z "$summary" ]; then echo "CRASH $t"; echo "$out" | head -20; failed_classes+=("$t"); continue; fi
  total_pass=$((total_pass + p)); total_fail=$((total_fail + f))
  if [ "$f" -gt 0 ]; then echo "FAIL  $summary"; echo "$out" | grep "FAIL " | sed 's/^/        /'; failed_classes+=("$t")
  else echo "PASS  $summary"; [ -n "$VERBOSE" ] && echo "$out" | grep "PASS " | sed 's/^/        /'; fi
done
echo "----------------------------------------"
echo "$total_pass passed, $total_fail failed across ${#tests[@]} test classes"
[ ${#failed_classes[@]} -eq 0 ]
