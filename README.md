# LLD interview solutions in Java

Top 20 low-level design questions, one self-contained file each (Tic-Tac-Toe and Chess are separate files, so 21 in total).
Every file has a `main()` demo that prints what the design guarantees, including edge cases and, where relevant, a multi-threaded race.
Requires Java 17+ (tested on 21). No dependencies.

```bash
./run_all.sh                 # compile everything and run all demos
./run_all.sh LRUCache Chess  # run only some
# or by hand:
javac -d out *.java && java -cp out ParkingLot
```

| # | File | Core pattern(s) | What the demo proves |
|---|------|-----------------|----------------------|
| 1 | ParkingLot | Strategy (pricing, spot assignment) | O(1) atomic spot claim; 100 threads race for 3 fitting spots, exactly 3 win |
| 2 | VendingMachine | State | Illegal actions per state, change-making, jam goes to out-of-service with a refund |
| 3 | ATM | State, Chain of Responsibility | Note dispensing chain, debit/dispense rollback, card retained after 3 bad PINs |
| 4 | ElevatorSystem | State, Strategy (SCAN + dispatcher) | Two cars, stops served in sweep order, nearest-car dispatch |
| 5 | TrafficSignal | State | Conflicting directions never green together (asserted every tick), emergency override |
| 6 | TicTacToe | Counters | O(1) win detection for NxN, draw, invalid moves |
| 6 | Chess | Polymorphism per piece | Self-check prevention, checkmate (fool's mate), illegal moves; castling and en passant intentionally left out |
| 7 | SnakeAndLadder | Uniform "jump" model, Dice interface | Exact-roll-to-win rule, jump validation, seeded full game |
| 8 | LibraryManagement | Strategy (fines), Observer | Book vs BookItem, loan limit, reservation queue with a held copy |
| 9 | MovieBooking | Seat state machine, TTL locks | All-or-nothing seat lock, lazy expiry, payment outside the lock, 50 threads race for one seat |
| 10 | HotelReservation | Interval overlap, per-room atomic check-and-insert | Back-to-back stays allowed, 20 guests race for 2 rooms |
| 11 | Splitwise | Strategy (split types), two-heap simplification | Cents-based math, remainder handling, 12 pairwise debts reduced to 3 payments |
| 12 | LRUCache | HashMap + doubly linked list | O(1) ops, matches `LinkedHashMap` on 10k random ops, thread-safe |
| 13 | RateLimiter | Strategy + Factory | Token bucket, leaky bucket (meter and shaper), sliding window, fixed window (allows 2x at the boundary), per-key isolation |
| 14 | LoggerFramework | Chain of Responsibility, Decorator (async), Strategy | Per-appender levels, JSON/text formatters, async queue drains fully |
| 15 | PubSub | Observer, per-subscriber offsets | Ordering per subscriber, retries, dead-letter queue, replay for late joiners |
| 16 | NotificationSystem | Observer, Strategy, Factory | Preferences, templates, retry on a flaky channel, rate limit with a CRITICAL bypass |
| 17 | FoodDelivery | State machine with transition table, Strategy, Observer | Price snapshot, single-restaurant cart, queued agent assignment |
| 18 | RideSharing | Grid spatial index, State, Strategy + Decorator (surge) | Index matches brute force, decline then re-dispatch, two riders race for one driver |
| 19 | ShoppingCart | Strategy (stacked discounts), reservation with expiry | Discount breakdown, inventory hold expiry, price snapshot, 20 threads race for 5 units |
| 20 | TextEditor | Command (+ Composite, merge) | Typing merges into one undo step, redo cleared by a new edit, replace-all is one undo, bounded history |

## How to use these in an interview
You will not write 150 lines in 45 minutes. Use each file as the answer key for the *shape* of the solution:
the entities, the one or two patterns that matter, and the concurrency or failure point the interviewer is probing for
(the comment block at the top of every file names it). Draw the classes first, then code only the core 2 or 3 classes.

## Unit tests
Every solution class has a test class (`<Name>Test.java`) covering the basic flow and the edge cases. 503 tests in total.

```
./run_tests.sh                       # compile everything and run all tests
./run_tests.sh LRUCacheTest ATMTest  # only some
VERBOSE=1 ./run_tests.sh             # list every passing test name
```

Maven Central was not reachable while building this, so there is no JUnit dependency. `MiniTest.java` is a ~100 line harness
with the same assertions (`assertEquals`, `assertTrue`, `assertThrows`, `assertContains`, ...). Each test method gets a fresh
instance and a 30 second timeout. To move to JUnit 5, add `@Test` to each `test*` method and switch the assertions to `org.junit.jupiter.api.Assertions`.

What the tests concentrate on: boundaries (exactly at a deadline, exactly at a limit, empty and single-element inputs), invalid input
leaving no half-applied state, retries and rollbacks, and the concurrency claims each file makes (no double booking, no oversell, exact limits under contention).

| Test class | Tests |
|---|---|
| ATM | 22 |
| Chess | 25 |
| ElevatorSystem | 19 |
| FoodDelivery | 32 |
| HotelReservation | 23 |
| LRUCache | 17 |
| LibraryManagement | 22 |
| LoggerFramework | 29 |
| MovieBooking | 22 |
| NotificationSystem | 32 |
| ParkingLot | 17 |
| PubSub | 23 |
| RateLimiter | 27 |
| RideSharing | 36 |
| ShoppingCart | 37 |
| SnakeAndLadder | 20 |
| Splitwise | 24 |
| TextEditor | 27 |
| TicTacToe | 17 |
| TrafficSignal | 16 |
| VendingMachine | 16 |

