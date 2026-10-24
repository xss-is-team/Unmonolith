# Java 21 verification runner

Build the deliberately minimal local runner image from the repository root:

```bash
docker build -t java-blueprints-runner:java21 apps/runner
```

The API creates a new container for each revision under test. Runtime flags,
not this image, provide the network, filesystem, capability, resource and PID
restrictions. The image contains only the Java 21 JDK and basic utilities needed
to compile a single source file, locate its class, inspect `main`, and run it.

This Docker boundary reduces risk but is not a formally secure sandbox. Do not
expose this runner to untrusted public traffic without stronger isolation such
as a dedicated host plus a VM or microVM boundary.
