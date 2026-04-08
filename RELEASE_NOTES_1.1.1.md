# Photo Finish Camera 1.1.1 Release Notes

## Changes since 1.0.0

### Monetization and access control
- Added Google Play Billing integration for Pro access.
- Introduced subscription-based entitlement checks for locked Pro features.
- Added one-time `Lifetime unlock` purchase support.
- Added ad-supported flow for users without active Pro entitlement.

### Subscription model update
- Migrated to a single subscription product: `photofinish_pro`.
- Configured app support for two base plans on that product:
  - `monthly`
  - `yearly-v2`
- Kept legacy subscription product IDs (`photofinish_monthly`, `photofinish_yearly`) as entitlement-compatible so existing subscribers keep access.

### Purchase and cancellation UX
- Added post-purchase prompt after `Lifetime unlock` purchase to guide users to Google Play subscription management.
- Added direct navigation to Play subscription management from the app.
- Improved subscription offer text behavior in launcher (hide empty offer text).

### Subscription screen improvements
- Reworked pricing display to build localized monthly/yearly/lifetime offer text dynamically from Play product details.
- Removed redundant manual "Restore purchases" button from the subscription screen.

### Build and release
- Updated app version to `1.1.1` (`versionCode 102`).

## Suggested Google Play "What’s new" text (short)
- New Pro subscription structure with monthly/yearly plans under one product.
- Improved lifetime unlock flow with direct subscription management guidance.
- Better pricing display and subscription UI polish.
