# pShare Data safety worksheet

Status: REVIEW REQUIRED. This is not a Play Console import and does not assert that no data is collected.

## Data flow to review

Selected files, text, pairing information and connection metadata are exchanged with the receiving device. Internet and discovery infrastructure need endpoint review before final disclosure.

## Required decisions

For each data type below, decide whether data leaves the device, whether it is collected/shared under Google’s definitions and exemptions, whether processing is ephemeral, its purpose, whether it is required, its encryption in transit, retention, and deletion process. User-initiated transfers, service providers and local processing require classification under the official guidance; an Internet permission alone does not settle the answers.

Selected files, text, pairing information and connection metadata are exchanged with the receiving device. Internet and discovery infrastructure need endpoint review before final disclosure.

Review account creation and deletion requirements if accounts can be created. Local app-data deletion does not delete provider accounts, recipient copies, exported files or cloud backups. Do not claim encrypted transport or zero collection until the final release and all SDKs have been checked.

See `review.md` for source paths and permission-specific declarations.

Official guidance: https://support.google.com/googleplay/android-developer/answer/10787469
