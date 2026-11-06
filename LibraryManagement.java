import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Library Management: Book (the title) vs BookItem (a physical copy), a reservation queue
 * per title, Strategy for fines, and an Observer callback when a reserved copy comes back.
 */
public class LibraryManagement {
    enum ItemStatus { AVAILABLE, LOANED, RESERVED }

    record Book(String isbn, String title, String author) {}

    static class BookItem {
        final String barcode; final Book book; ItemStatus status = ItemStatus.AVAILABLE;
        BookItem(String barcode, Book book) { this.barcode = barcode; this.book = book; }
    }

    static class Member {
        final String id, name; final Set<String> loanedBarcodes = new HashSet<>();
        Member(String id, String name) { this.id = id; this.name = name; }
    }

    static class Loan {
        final BookItem item; final Member member; final LocalDate due;
        Loan(BookItem item, Member member, LocalDate due) { this.item = item; this.member = member; this.due = due; }
    }

    interface FineStrategy { double fine(long daysOverdue); }
    static class PerDayFine implements FineStrategy {
        private final double rate;
        PerDayFine(double rate) { this.rate = rate; }
        public double fine(long d) { return d <= 0 ? 0 : d * rate; }
    }

    interface ReservationListener { void onCopyHeld(Member m, Book b); }

    private final Map<String, Book> catalog = new LinkedHashMap<>();
    private final Map<String, List<BookItem>> itemsByIsbn = new HashMap<>();
    private final Map<String, Loan> loans = new HashMap<>();                 // barcode -> loan
    private final Map<String, LinkedList<Member>> reservations = new HashMap<>();
    private final FineStrategy fines; private final ReservationListener listener;
    private final int maxLoans, loanDays;

    LibraryManagement(FineStrategy fines, ReservationListener l, int maxLoans, int loanDays) {
        this.fines = fines; this.listener = l; this.maxLoans = maxLoans; this.loanDays = loanDays;
    }

    void addBook(Book b, int copies) {
        catalog.put(b.isbn(), b);
        List<BookItem> list = itemsByIsbn.computeIfAbsent(b.isbn(), k -> new ArrayList<>());
        for (int i = 0; i < copies; i++) list.add(new BookItem(b.isbn() + "-" + (list.size() + 1), b));
    }

    List<Book> search(String q) {
        String s = q.toLowerCase();
        return catalog.values().stream()
            .filter(b -> b.title().toLowerCase().contains(s) || b.author().toLowerCase().contains(s)).toList();
    }

    void reserve(Member m, String isbn) {
        LinkedList<Member> q = reservations.computeIfAbsent(isbn, k -> new LinkedList<>());
        if (q.contains(m)) throw new IllegalStateException(m.name + " already reserved this title");
        q.add(m);
    }

    Loan checkout(Member m, String isbn, LocalDate today) {
        if (m.loanedBarcodes.size() >= maxLoans) throw new IllegalStateException(m.name + " reached the loan limit of " + maxLoans);
        List<BookItem> items = itemsByIsbn.get(isbn);
        if (items == null) throw new IllegalArgumentException("Unknown ISBN " + isbn);
        LinkedList<Member> q = reservations.getOrDefault(isbn, new LinkedList<>());
        BookItem pick = items.stream().filter(i -> i.status == ItemStatus.AVAILABLE).findFirst().orElse(null);
        if (pick == null) {
            long held = items.stream().filter(i -> i.status == ItemStatus.RESERVED).count();
            boolean eligible = q.stream().limit(held).anyMatch(x -> x == m);
            if (eligible) { pick = items.stream().filter(i -> i.status == ItemStatus.RESERVED).findFirst().get(); q.remove(m); }
        }
        if (pick == null) throw new IllegalStateException("No copy available; reserve it instead");
        pick.status = ItemStatus.LOANED;
        Loan loan = new Loan(pick, m, today.plusDays(loanDays));
        loans.put(pick.barcode, loan); m.loanedBarcodes.add(pick.barcode);
        return loan;
    }

    /** Returns the fine owed (0 if on time). */
    double returnItem(String barcode, LocalDate today) {
        Loan loan = loans.remove(barcode);
        if (loan == null) throw new IllegalArgumentException("Not on loan: " + barcode);
        loan.member.loanedBarcodes.remove(barcode);
        double fine = fines.fine(ChronoUnit.DAYS.between(loan.due, today));
        BookItem item = loan.item;
        List<BookItem> items = itemsByIsbn.get(item.book.isbn());
        LinkedList<Member> q = reservations.getOrDefault(item.book.isbn(), new LinkedList<>());
        long held = items.stream().filter(i -> i.status == ItemStatus.RESERVED).count();
        if (q.size() > held) {
            item.status = ItemStatus.RESERVED;
            listener.onCopyHeld(q.get((int) held), item.book);
        } else item.status = ItemStatus.AVAILABLE;
        return fine;
    }

    public static void main(String[] args) {
        LibraryManagement lib = new LibraryManagement(new PerDayFine(0.50),
            (m, b) -> System.out.println("  [notify] " + m.name + ": a copy of '" + b.title() + "' is being held for you"), 2, 14);
        lib.addBook(new Book("111", "Clean Code", "Robert Martin"), 1);
        lib.addBook(new Book("222", "Effective Java", "Joshua Bloch"), 2);
        lib.addBook(new Book("333", "Designing Data-Intensive Applications", "Martin Kleppmann"), 1);
        Member alice = new Member("m1", "Alice"), bob = new Member("m2", "Bob");
        LocalDate d0 = LocalDate.of(2026, 11, 1);

        System.out.println("Search 'martin': " + lib.search("martin").stream().map(Book::title).toList());
        Loan l1 = lib.checkout(alice, "111", d0);
        System.out.println("Alice borrowed Clean Code, due " + l1.due);
        lib.checkout(alice, "222", d0);
        try { lib.checkout(alice, "333", d0); } catch (IllegalStateException e) { System.out.println("Limit: " + e.getMessage()); }
        try { lib.checkout(bob, "111", d0); } catch (IllegalStateException e) { System.out.println("Bob: " + e.getMessage()); }
        lib.reserve(bob, "111");

        System.out.println("Alice returns Clean Code 20 days later:");
        double fine = lib.returnItem(l1.item.barcode, d0.plusDays(20));
        System.out.printf("  fine = %.2f%n", fine);
        try { lib.checkout(alice, "111", d0.plusDays(20)); } catch (IllegalStateException e) { System.out.println("Alice re-borrow: " + e.getMessage()); }
        Loan l2 = lib.checkout(bob, "111", d0.plusDays(21));
        System.out.println("Bob borrowed the held copy, due " + l2.due);
    }
}
