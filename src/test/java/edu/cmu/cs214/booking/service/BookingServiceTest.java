package edu.cmu.cs214.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import edu.cmu.cs214.booking.domain.Room;
import edu.cmu.cs214.booking.domain.TimeInterval;
import edu.cmu.cs214.booking.domain.User;
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
    void listBookingsReturnsConfirmedBookings() {
        BookingService svc = newService();
        svc.book(roomA, alice, new TimeInterval(600, 660));
        svc.book(roomA, bob, new TimeInterval(660, 720));
        assertEquals(2, svc.listBookings(roomA).size());
    }

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
}
