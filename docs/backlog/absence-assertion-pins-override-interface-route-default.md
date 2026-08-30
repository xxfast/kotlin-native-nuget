# No absence assertion pins the `override` and interface-route default-parameter exclusions [ADR-096](docs/adr/096-function-default-parameters.md) added.

> Extracted verbatim from `ROADMAP.md` (Phase 4: Rich type support) in the 2026-08-31 roadmap slim-down.

**No absence assertion pins the `override` and interface-route default-parameter exclusions [ADR-096](docs/adr/096-function-default-parameters.md) added.** No existing fixture has a defaulted parameter on an interface member or on a method carrying `override`, so an assertion that "no synthesized overload exists" would be vacuous today. Worth pinning with a real fixture if an interface or override method ever grows a default parameter, so a future change to either exclusion trips a test rather than silently reappearing. Noted by the test author during ADR-096 implementation, deliberately not manufactured.
