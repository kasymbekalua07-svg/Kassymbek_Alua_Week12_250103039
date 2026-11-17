package lecture_practiceFacade;

public class FlightBookingFacade {

    // Encapsulated subsystem references
    private final SeatInventorySystem seatSystem;
    private final BaggagePricingEngine baggageEngine;
    private final CurrencyConverter currencyConverter;
    private final PaymentProcessor paymentProcessor;
    private final TicketIssuer ticketIssuer;

    public FlightBookingFacade() {
        this.seatSystem = new SeatInventorySystem();
        this.baggageEngine = new BaggagePricingEngine();
        this.currencyConverter = new CurrencyConverter();
        this.paymentProcessor = new PaymentProcessor();
        this.ticketIssuer = new TicketIssuer();
    }

    /**
     * Backward-compatible 8-parameter entry point.
     * Delegates to the loyalty-aware overload with the "STANDARD" tier.
     */
    public String bookFlight(String passengerName,
                             String flightCode,
                             String seatNumber,
                             double baseFareUSD,
                             int bagCount,
                             double bagWeightKg,
                             String currency,
                             String cardNumber) {
        return bookFlight(passengerName, flightCode, seatNumber, baseFareUSD,
                bagCount, bagWeightKg, currency, cardNumber, "STANDARD");
    }

    /**
     * Single entry point for booking a complete flight itinerary.
     * Coordinates seat locking, baggage pricing, currency conversion,
     * payment execution, rollback on failure, and ticket issuance.
     *
     * Loyalty rules:
     *  - SILVER / GOLD: baggage fees waived (BaggagePricingEngine is skipped).
     *  - SILVER: promo code "SILVER-FREE-BAG" applied after gateway init,
     *            before charging the card.
     */
    public String bookFlight(String passengerName,
                             String flightCode,
                             String seatNumber,
                             double baseFareUSD,
                             int bagCount,
                             double bagWeightKg,
                             String currency,
                             String cardNumber,
                             String loyaltyTier) {
        String tier = (loyaltyTier == null) ? "STANDARD" : loyaltyTier.trim().toUpperCase();
        String lockToken = null;

        try {
            // Step 1: connect and lock the seat
            seatSystem.connectToReservationServer();
            lockToken = seatSystem.lockSeat(flightCode, seatNumber, passengerName);

            // Step 2: baggage fee (skipped entirely for SILVER / GOLD)
            double baggageFeeUSD = 0.0;
            if (!tier.equals("SILVER") && !tier.equals("GOLD")) {
                baggageFeeUSD = baggageEngine.calculateBaggageFeeUSD(bagCount, bagWeightKg);
            }
            double totalUSD = baseFareUSD + baggageFeeUSD;

            // Step 3: currency conversion
            double convertedTotal = currencyConverter.convertFromUSD(totalUSD, currency);

            // Step 4: payment (gateway -> optional promo -> charge)
            paymentProcessor.initializeGateway();
            if (tier.equals("SILVER")) {
                paymentProcessor.applyPromoCode("SILVER-FREE-BAG");
            }
            String receiptId = paymentProcessor.chargeCard(cardNumber, convertedTotal, currency);

            // Step 5: issue the ticket
            return ticketIssuer.generateTicket(passengerName, flightCode, lockToken, receiptId);

        } catch (RuntimeException ex) {
            // Roll back the seat lock if one was acquired
            if (lockToken != null) {
                seatSystem.releaseSeatLock(lockToken);
            }
            System.out.println("[Facade] Booking failed for " + passengerName + ": " + ex.getMessage());
            return "BOOKING_FAILED: " + ex.getMessage();
        } finally {
            // Always close the seat server connection
            seatSystem.disconnect();
        }
    }
}
