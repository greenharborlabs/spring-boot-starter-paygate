# When to Mock

Mock at **system boundaries** only:

- External APIs (Lightning backends -- LND gRPC, LNbits REST)
- Time/randomness (`SecureRandom`, `Instant.now()`)
- File system (root key store)
- Network (gRPC stubs, MockWebServer)

Don't mock:
- Your own classes/modules
- Internal collaborators
- Anything you control

## Designing for Mockability

At system boundaries, design interfaces that are easy to mock:

**1. Use dependency injection**

Pass external dependencies in rather than creating them internally:

```java
// Easy to mock -- dependency injected
public class L402ChallengeService {
    private final LightningClient lightningClient;

    public L402ChallengeService(LightningClient lightningClient) {
        this.lightningClient = lightningClient;
    }

    public Invoice createInvoice(long amountSats) {
        return lightningClient.createInvoice(amountSats);
    }
}

// Hard to mock -- dependency created internally
public class L402ChallengeService {
    public Invoice createInvoice(long amountSats) {
        var client = new LndGrpcClient(config);
        return client.createInvoice(amountSats);
    }
}
```

**2. Prefer specific interfaces over generic ones**

```java
// GOOD: Each method is independently mockable
public interface LightningClient {
    Invoice createInvoice(long amountSats, String memo);
    boolean verifyPayment(byte[] paymentHash);
    HealthStatus checkHealth();
}

// BAD: Generic call requires conditional logic in mock
public interface LightningClient {
    Object call(String method, Object... args);
}
```

## Test Doubles for This Project

- **LND:** Mock the gRPC stubs directly
- **LNbits:** Use OkHttp MockWebServer to simulate REST responses
- **Root key store:** Use in-memory implementation
- **SecureRandom:** Inject a seeded instance for deterministic tests
