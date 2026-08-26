package edu.cmu.cs214.booking.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.cmu.cs214.booking.domain.Booking;
import edu.cmu.cs214.booking.domain.Room;
import edu.cmu.cs214.booking.domain.TimeInterval;
import edu.cmu.cs214.booking.domain.User;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryBookingStoreTest {

    @Test
    void bookingsForRoomReturnsOnlyBookingsForRequestedRoom() {
        InMemoryBookingStore store = new InMemoryBookingStore();
        Room roomA = new Room("A", "Alpha", 10);
        Room roomB = new Room("B", "Beta", 4);
        Booking bookingA = new Booking(
            "b1", roomA, new User("u1", "Alice"), new TimeInterval(600, 660));
        Booking bookingB = new Booking(
            "b2", roomB, new User("u2", "Bob"), new TimeInterval(600, 660));

        store.addBooking(bookingA);
        store.addBooking(bookingB);

        assertEquals(List.of(bookingA), store.bookingsForRoom(roomA));
        assertEquals(List.of(bookingB), store.bookingsForRoom(roomB));
    }
}
