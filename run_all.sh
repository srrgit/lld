#!/usr/bin/env bash
# Compiles every solution and runs each demo. Usage: ./run_all.sh [ClassName ...]
set -u
cd "$(dirname "$0")"
rm -rf out && mkdir out
javac -d out *.java || { echo "COMPILE FAILED"; exit 1; }

ALL=(ParkingLot VendingMachine ATM ElevatorSystem TrafficSignal TicTacToe Chess SnakeAndLadder LibraryManagement \
     MovieBooking HotelReservation Splitwise LRUCache RateLimiter LoggerFramework PubSub NotificationSystem \
     FoodDelivery RideSharing ShoppingCart TextEditor)
TARGETS=("$@"); [ ${#TARGETS[@]} -eq 0 ] && TARGETS=("${ALL[@]}")

fail=0
for c in "${TARGETS[@]}"; do
  if timeout 60 java -cp out "$c" > "out/$c.log" 2>&1 && ! grep -qE "Exception in thread|AssertionError" "out/$c.log"; then
    printf "PASS  %-20s (%s lines of output)\n" "$c" "$(wc -l < "out/$c.log")"
  else
    printf "FAIL  %-20s see out/%s.log\n" "$c" "$c"; fail=1
  fi
done
exit $fail
