# Webhook Payload Sources

This project uses two kinds of webhook payload samples:

1. Official sample payloads from provider documentation
2. Real sandbox events captured from provider tooling

For Phase 1 development, the JSON files under `src/test/resources/webhook-fixtures/` are doc-derived fixtures shaped to match the handlers and normalization logic in this codebase.

They are useful for:
- unit tests
- local manual replay
- smoke tests before wiring live provider accounts

They are not a substitute for capturing real sandbox payloads from your own account.

## Razorpay

Official docs with sample payloads:

- Payments webhook events:
  [Razorpay Payments Webhook Events](https://razorpay.com/docs/webhooks/payments/)
- Refund webhook events:
  [Razorpay Refunds Webhook Events](https://razorpay.com/docs/webhooks/payloads/refunds/)

Recommended events for this project:

- `payment.authorized`
- `payment.captured`
- `payment.failed`
- `refund.processed`

Best way to get a real Razorpay sandbox payload:

1. Create a test payment in Razorpay test mode.
2. Point the webhook to your local endpoint through ngrok or another tunnel.
3. Capture the raw request body from your app logs or persist it from `webhook_events.payload`.

## Stripe

Official docs:

- Webhook handling:
  [Handle payment events with webhooks](https://docs.stripe.com/payments/handling-payment-events?lang=node)
- PaymentIntent status updates:
  [Payment status updates](https://docs.stripe.com/payments/payment-intents/verifying-status)
- Event types:
  [Types of events](https://docs.stripe.com/api/events/types)
- CLI trigger docs:
  [Trigger webhook events with the Stripe CLI](https://docs.stripe.com/stripe-cli/triggers)

Recommended events for this project:

- `payment_intent.succeeded`
- `payment_intent.payment_failed`
- `charge.refunded`
- `charge.dispute.created`
- `payout.paid`

Best way to get a real Stripe sandbox payload:

1. Run:
   `stripe listen --forward-to localhost:8080/webhooks/stripe`
2. Trigger events:
   `stripe trigger payment_intent.succeeded`
   `stripe trigger payment_intent.payment_failed`
   `stripe trigger charge.refunded`
3. Copy the exact raw event body from your app or from the Stripe Dashboard Events page.

## Included Fixtures

Razorpay:

- `src/test/resources/webhook-fixtures/razorpay/payment.authorized.json`
- `src/test/resources/webhook-fixtures/razorpay/payment.captured.json`
- `src/test/resources/webhook-fixtures/razorpay/payment.failed.json`
- `src/test/resources/webhook-fixtures/razorpay/refund.processed.json`

Stripe:

- `src/test/resources/webhook-fixtures/stripe/payment_intent.succeeded.json`
- `src/test/resources/webhook-fixtures/stripe/payment_intent.payment_failed.json`
- `src/test/resources/webhook-fixtures/stripe/charge.refunded.json`
- `src/test/resources/webhook-fixtures/stripe/charge.dispute.created.json`
- `src/test/resources/webhook-fixtures/stripe/payout.paid.json`

## Replay Script

Use the local replay helper to POST the fixtures into the running app with valid signatures:

- `scripts/replay-webhook.sh --list`
- `scripts/replay-webhook.sh razorpay payment.captured`
- `scripts/replay-webhook.sh stripe payment_intent.succeeded`

Defaults:

- `BASE_URL=http://127.0.0.1:8080`
- `RAZORPAY_WEBHOOK_SECRET=razorpay_webhook_secret`
- `STRIPE_WEBHOOK_SECRET=whsec_test_secret`

You can override those env vars when replaying against a different local setup.

## Important Note

Stripe event payloads vary with API version and enabled product fields.
Razorpay payload snapshots can also include additional nested objects depending on payment method and account setup.

So use these fixtures as stable developer fixtures, and use real sandbox events for final Phase 1 exit checks.
