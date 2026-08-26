package edu.cmu.cs214.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import edu.cmu.cs214.booking.domain.Booking;
import edu.cmu.cs214.booking.domain.Room;
import edu.cmu.cs214.booking.domain.TimeInterval;
import edu.cmu.cs214.booking.domain.User;
import edu.cmu.cs214.booking.domain.WaitlistEntry;
import edu.cmu.cs214.booking.repo.InMemoryBookingStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookingServiceTest {

    private final Room roomA = new Room("A", "Alpha", 10);
    private final Room roomB = new Room("B", "Beta", 4);
    private final User alice = new User("u1", "Alice");
    private final User bob = new User("u2", "Bob");

    private BookingService newService() {
        return new BookingService(new InMemoryBookingStore());
    }

    @Test
    void bookConfirmsWhenRoomIsFree() {
        BookingService svc = newService();
        BookingResult r = svc.book(roomA, alice, new TimeInterval(600, 660));
        assertInstanceOf(BookingResult.Confirmed.class, r);
    }

    @Test
    void bookWaitlistsWhenSlotIsTaken() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(600, 660));
        BookingResult r = svc.book(roomA, bob, new TimeInterval(630, 700));
        assertInstanceOf(BookingResult.Waitlisted.class, r);
    }

    @Test
    void backToBackBookingsAreConfirmed() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(600, 660));
        BookingResult r = svc.book(roomA, bob, new TimeInterval(660, 720));
        assertInstanceOf(BookingResult.Confirmed.class, r);
    }

    @Test
    void sameSlotInDifferentRoomsAreBothConfirmed() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(600, 660));
        BookingResult r = svc.book(roomB, bob, new TimeInterval(600, 660));
        assertInstanceOf(BookingResult.Confirmed.class, r);
    }

    @Test
    void isAvailableReturnsFalseWhenRequestedIntervalStartsDuringBooking() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(600, 660));

        // Regression: the original one-sided check missed this overlap because
        // the existing booking starts before the requested interval.
        assertFalse(svc.isAvailable(roomA, new TimeInterval(630, 690)));
    }

    @Test
    void listBookingsReturnsConfirmedBookings() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(600, 660));
        svc.book(roomA, bob, new TimeInterval(660, 720));
        assertEquals(2, svc.listBookings(roomA).size());
    }

    // Proves cancellation removes the matching confirmed booking from the store.
    @Test
    void cancelBookingRemovesExistingBooking() {
        InMemoryBookingStore store = new InMemoryBookingStore();
        BookingService svc = new BookingService(store);
        BookingResult.Confirmed confirmed = assertInstanceOf(
            BookingResult.Confirmed.class,
            svc.book(roomA, alice, new TimeInterval(600, 660)));

        svc.cancelBooking(confirmed.booking().id());

        assertEquals(List.of(), store.bookingsForRoom(roomA));
    }

    // Guards the no-op contract, including unchanged confirmed and waitlist state.
    @Test
    void cancelBookingWithUnknownIdDoesNothing() {
        InMemoryBookingStore store = new InMemoryBookingStore();
        BookingService svc = new BookingService(store);
        BookingResult.Confirmed confirmed = assertInstanceOf(
            BookingResult.Confirmed.class,
            svc.book(roomA, alice, new TimeInterval(600, 660)));
        svc.book(roomA, bob, new TimeInterval(630, 700));
        var waitlistBefore = store.waitlistForRoom(roomA);

        svc.cancelBooking("missing-booking");

        assertEquals(List.of(confirmed.booking()), store.bookingsForRoom(roomA));
        assertEquals(waitlistBefore, store.waitlistForRoom(roomA));
    }

    // Proves an eligible waiter is confirmed with the same request and dequeued.
    @Test
    void cancelBookingPromotesEligibleWaiter() {
        InMemoryBookingStore store = new InMemoryBookingStore();
        BookingService svc = new BookingService(store);
        TimeInterval requested = new TimeInterval(630, 700);
        BookingResult.Confirmed confirmed = assertInstanceOf(
            BookingResult.Confirmed.class,
            svc.book(roomA, alice, new TimeInterval(600, 660)));
        assertInstanceOf(BookingResult.Waitlisted.class, svc.book(roomA, bob, requested));

        svc.cancelBooking(confirmed.booking().id());

        assertEquals(1, store.bookingsForRoom(roomA).size());
        var promoted = store.bookingsForRoom(roomA).getFirst();
        assertEquals(roomA, promoted.room());
        assertEquals(bob, promoted.user());
        assertEquals(requested, promoted.interval());
        assertEquals(List.of(), store.waitlistForRoom(roomA));
    }

    // Protects the no-overlap invariant when another booking still blocks the waiter.
    @Test
    void cancelBookingLeavesConflictingWaiterWaiting() {
        InMemoryBookingStore store = new InMemoryBookingStore();
        BookingService svc = new BookingService(store);
        User charlie = new User("u3", "Charlie");
        BookingResult.Confirmed cancelled = assertInstanceOf(
            BookingResult.Confirmed.class,
            svc.book(roomA, alice, new TimeInterval(600, 660)));
        BookingResult.Confirmed blocker = assertInstanceOf(
            BookingResult.Confirmed.class,
            svc.book(roomA, charlie, new TimeInterval(660, 720)));
        assertInstanceOf(
            BookingResult.Waitlisted.class,
            svc.book(roomA, bob, new TimeInterval(630, 690)));
        var waitlistBefore = store.waitlistForRoom(roomA);

        svc.cancelBooking(cancelled.booking().id());

        assertEquals(List.of(blocker.booking()), store.bookingsForRoom(roomA));
        assertEquals(waitlistBefore, store.waitlistForRoom(roomA));
    }

    // Verifies seq, not storage order, determines the single promoted waiter.
    @Test
    void cancelBookingPromotesLowestSequenceWaiterOnly() {
        InMemoryBookingStore store = new InMemoryBookingStore();
        BookingService svc = new BookingService(store);
        User charlie = new User("u3", "Charlie");
        Booking cancelled = new Booking(
            "existing", roomA, alice, new TimeInterval(600, 660));
        WaitlistEntry later = new WaitlistEntry(
            "w2", roomA, charlie, new TimeInterval(620, 630), 2);
        WaitlistEntry earlier = new WaitlistEntry(
            "w1", roomA, bob, new TimeInterval(610, 620), 1);
        store.addBooking(cancelled);
        store.addWaitlistEntry(later);
        store.addWaitlistEntry(earlier);

        svc.cancelBooking(cancelled.id());

        assertEquals(1, store.bookingsForRoom(roomA).size());
        var promoted = store.bookingsForRoom(roomA).getFirst();
        assertEquals(earlier.user(), promoted.user());
        assertEquals(earlier.interval(), promoted.interval());
        assertEquals(List.of(later), store.waitlistForRoom(roomA));
    }

    // Verifies a blocked earlier waiter is skipped for the next eligible waiter.
    @Test
    void cancelBookingSkipsBlockedWaiterAndPromotesNextEligibleWaiter() {
        InMemoryBookingStore store = new InMemoryBookingStore();
        BookingService svc = new BookingService(store);
        User charlie = new User("u3", "Charlie");
        User dana = new User("u4", "Dana");
        TimeInterval laterInterval = new TimeInterval(620, 680);
        BookingResult.Confirmed cancelled = assertInstanceOf(
            BookingResult.Confirmed.class,
            svc.book(roomA, alice, new TimeInterval(600, 660)));
        BookingResult.Confirmed blocker = assertInstanceOf(
            BookingResult.Confirmed.class,
            svc.book(roomA, charlie, new TimeInterval(680, 740)));
        assertInstanceOf(
            BookingResult.Waitlisted.class,
            svc.book(roomA, bob, new TimeInterval(630, 700)));
        assertInstanceOf(
            BookingResult.Waitlisted.class,
            svc.book(roomA, dana, laterInterval));
        WaitlistEntry earlier = store.waitlistForRoom(roomA).getFirst();

        svc.cancelBooking(cancelled.booking().id());

        assertEquals(2, store.bookingsForRoom(roomA).size());
        assertEquals(blocker.booking(), store.bookingsForRoom(roomA).getFirst());
        Booking promoted = store.bookingsForRoom(roomA).get(1);
        assertEquals(dana, promoted.user());
        assertEquals(laterInterval, promoted.interval());
        assertFalse(blocker.booking().interval().overlaps(promoted.interval()));
        assertEquals(List.of(earlier), store.waitlistForRoom(roomA));
    }
}
