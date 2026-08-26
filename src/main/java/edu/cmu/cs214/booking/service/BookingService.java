package edu.cmu.cs214.booking.service;

import edu.cmu.cs214.booking.domain.Booking;
import edu.cmu.cs214.booking.domain.Room;
import edu.cmu.cs214.booking.domain.TimeInterval;
import edu.cmu.cs214.booking.domain.User;
import edu.cmu.cs214.booking.domain.WaitlistEntry;
import edu.cmu.cs214.booking.repo.BookingStore;
import java.util.Comparator;
import java.util.List;

/**
 * Coordinates bookings and the waitlist. Enforces the core invariant: a room
 * never holds two confirmed bookings whose intervals overlap. Persistence is
 * delegated to a {@link BookingStore}.
 */
public class BookingService {

    private final BookingStore store;
    private int nextBookingSeq = 1;
    private int nextWaitlistSeq = 1;

    public BookingService(BookingStore store) {
        this.store = store;
    }

    /**
     * Attempts to book {@code room} for {@code user} over {@code interval}. If the
     * room is free over that interval the booking is confirmed; otherwise the user
     * is placed on the room's waitlist.
     */
    public BookingResult book(Room room, User user, TimeInterval interval) {
        for (Booking existing : store.bookingsForRoom(room)) {
            if (existing.interval().overlaps(interval)) {
                int position = store.waitlistForRoom(room).size() + 1;
                int seq = nextWaitlistSeq++;
                store.addWaitlistEntry(new WaitlistEntry("w" + seq, room, user, interval, seq));
                return new BookingResult.Waitlisted(position);
            }
        }
        Booking booking = new Booking("b" + nextBookingSeq++, room, user, interval);
        store.addBooking(booking);
        return new BookingResult.Confirmed(booking);
    }

    /**
     * Cancels the confirmed booking with {@code bookingId}, if it exists, and
     * promotes the earliest waiter whose requested interval is free.
     */
    public void cancelBooking(String bookingId) {
        Booking cancelled = store.findBooking(bookingId).orElse(null);
        if (cancelled == null) {
            return;
        }
        store.removeBooking(bookingId);

        List<WaitlistEntry> waiters = store.waitlistForRoom(cancelled.room()).stream()
            .sorted(Comparator.comparingInt(WaitlistEntry::seq))
            .toList();
        for (WaitlistEntry waiter : waiters) {
            boolean overlaps = store.bookingsForRoom(cancelled.room()).stream()
                .anyMatch(booking -> booking.interval().overlaps(waiter.interval()));
            if (overlaps) {
                continue;
            }

            Booking promoted = new Booking(
                "b" + nextBookingSeq++, waiter.room(), waiter.user(), waiter.interval());
            store.addBooking(promoted);
            store.removeWaitlistEntry(waiter.id());
            return;
        }
    }

    /**
     * Reports whether {@code room} is free over {@code interval}, so callers can
     * check availability before attempting to book.
     */
    public boolean isAvailable(Room room, TimeInterval interval) {
        for (Booking booking : store.bookingsForRoom(room)) {
            TimeInterval existing = booking.interval();
            if (interval.start() < existing.end() && interval.end() > existing.start()) {
                return false;
            }
        }
        return true;
    }

    /** Returns the confirmed bookings for {@code room}. */
    public List<Booking> listBookings(Room room) {
        return store.bookingsForRoom(room);
    }
}
