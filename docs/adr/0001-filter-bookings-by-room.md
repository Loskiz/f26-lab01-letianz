# ADR 0001: Filter Confirmed Bookings by Room

- Status: Accepted
- Date: 2026-08-26

## Context

`BookingServiceTest.sameSlotInDifferentRoomsAreBothConfirmed` failed because the
second booking returned `Waitlisted` instead of `Confirmed`, even though it was for
a different room from the first booking.

The failing call path was:

1. The test confirmed Alice's `[600, 660)` booking in room A.
2. The test called `BookingService.book` for Bob in room B over `[600, 660)`.
3. `BookingService.book` asked the store for `bookingsForRoom(roomB)`.
4. `InMemoryBookingStore.bookingsForRoom` returned a copy of the complete global
   booking list, including Alice's room A booking.
5. `BookingService.book` correctly detected that the returned interval overlapped
   Bob's interval and waitlisted Bob.
6. The test assertion consequently received `Waitlisted` rather than `Confirmed`.

The root cause was therefore
`src/main/java/edu/cmu/cs214/booking/repo/InMemoryBookingStore.java`, specifically
`bookingsForRoom`. Its implementation returned `List.copyOf(bookings)` and never
used the `room` parameter. This violated the `BookingStore` contract that the method
returns confirmed bookings held for the requested room.

`BookingService.book` was not at fault: it requested the correct room and applied
the overlap rule to the records supplied by the repository. `TimeInterval.overlaps`
was also correct: two identical intervals do overlap. The defect was that a booking
from the wrong room reached that calculation.

## Decision

Filter the stored bookings by room ID in `bookingsForRoom`:

```java
return bookings.stream()
    .filter(booking -> booking.room().id().equals(room.id()))
    .toList();
```

Room ID is used because it is the repository's existing room-identity convention;
`waitlistForRoom` already uses the same comparison. `Stream.toList()` preserves the
previous method's immutable-result behavior while returning only the requested
room's records. No public API changes are required.

## Correctness Evidence

The direct repository regression test stores same-time bookings for rooms A and B,
then verifies that each `bookingsForRoom` query returns only its matching booking.
This proves the filtering contract at the layer where it was broken.

The existing cross-room service test proves that a room A booking no longer creates
a false conflict in room B. The existing same-room conflict test still expects a
waitlist result, proving that overlap enforcement remains active. The interval tests
continue to cover intersecting, disjoint, touching, and contained intervals.

The acceptance commands are:

```text
mvn -Dtest=InMemoryBookingStoreTest test
mvn -Dtest=BookingServiceTest#sameSlotInDifferentRoomsAreBothConfirmed test
mvn -Dtest=BookingServiceTest#bookWaitlistsWhenSlotIsTaken test
mvn test
```

On 2026-08-26, the repository regression, the original cross-room regression, and
the existing same-room waitlisting test each passed independently. The complete
suite then reported 11 tests, zero failures, and zero errors. These results are
evidence about the corrected data partition and preserved conflict behavior, rather
than merely evidence that an agent changed code until the build became green.

## Consequences

- Bookings in other rooms no longer affect availability or listing results for the
  requested room.
- Same-room overlapping bookings continue to be waitlisted.
- `allBookings` remains the explicit operation for retrieving the global list.
