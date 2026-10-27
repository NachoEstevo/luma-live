# Security

Luma is a local development prototype, not a hosted production service.

- Keep the backend bound to loopback and connect Android with an explicit USB reverse tunnel. Do not expose its port to the internet.
- Store the Meta Model API key only in the backend environment. Never put it in an Android build, browser code, screenshot, issue or commit.
- Keep DAT account configuration, signing keys, local SDK paths and build outputs outside Git. Create your own Meta developer integration.
- Paid requests require consent. The request cap is per process, includes failures/cancellations and resets when the server restarts. It is not a monetary spending cap.
- Photos, audio and conversation state remain in application memory; provider and SDK policies apply independently to transmitted data.
- Do not use this prototype for crossing decisions, collision warnings, medical advice or other safety-critical decisions.

Report vulnerabilities privately through this repository's Security tab. Do not include live credentials, personal images or audio in a public issue. If a credential is exposed, revoke it at its provider; deleting a file does not remove it from Git history.
