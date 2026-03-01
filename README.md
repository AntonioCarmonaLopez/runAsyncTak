# runAsyncTak

> **@Transactional + Multithreading: lo que debes saber en Spring**

A Spring Boot demo project illustrating how `@Transactional` and multithreading interact — and the pitfalls you must avoid.

---

## The core rule: transactions are thread-local

Spring stores the active transaction in a `ThreadLocal` variable on each thread.  
That means **a transaction never crosses a thread boundary automatically**.

---

## The four scenarios

### Scenario 1 — `@Transactional` in a single thread ✅

```java
@Transactional
public void createOrderSingleThread(String description) {
    Order order = new Order(description, "PENDING");
    orderRepository.save(order);
    order.setStatus("CONFIRMED");
    orderRepository.save(order);  // same transaction — atomic
}
```

Both saves share a single transaction. If anything throws, the whole unit of work rolls back.

---

### Scenario 2 — Raw `new Thread(…)` inside `@Transactional` ⚠️

```java
@Transactional
public void createOrderWithRawThread(String description) throws InterruptedException {
    orderRepository.save(new Order(description + " (outer)", "PENDING"));

    Thread innerThread = new Thread(() -> {
        // TransactionSynchronizationManager.isActualTransactionActive() == false here!
        // This save runs in AUTO_COMMIT mode — outside the outer transaction.
        orderRepository.save(new Order(description + " (inner)", "AUTO_COMMIT"));
    });
    innerThread.start();
    innerThread.join();
}
```

**The inner thread has no transaction context.**  
- Its save is committed immediately in auto-commit mode.  
- If the outer transaction rolls back, the inner save is **not** rolled back.  
- The outer transaction cannot see uncommitted changes made by the inner thread.

---

### Scenario 3 — `@Async` + `@Transactional` ℹ️

```java
// Caller
@Transactional
public void createOrderWithAsync(String description) {
    Order order = new Order(description, "PENDING");
    orderRepository.save(order);
    asyncOrderService.processOrderAsync(order.getId(), description);  // fires async
}

// AsyncOrderService — separate bean so the Spring proxy works
@Async("taskExecutor")
@Transactional
public CompletableFuture<Void> processOrderAsync(Long orderId, String description) {
    // Runs in a pool thread with a FRESH transaction.
    // The orderId must already be committed, otherwise findById returns empty.
    orderRepository.findById(orderId).ifPresent(o -> {
        o.setStatus("PROCESSED");
        orderRepository.save(o);
    });
    return CompletableFuture.completedFuture(null);
}
```

Key points:
- The `@Async` method runs in a different thread → it gets a **new** transaction.
- If the caller's transaction has not committed yet, the async method won't find the record.
- The two transactions are **independent**: a rollback in one does not affect the other.
- The `@Async` proxy only activates when the call comes from **outside** the bean — always place async methods in a **separate service**.

---

### Scenario 4 — `afterCommit` hook (correct pattern) ✅

```java
@Transactional
public void createOrderWithAfterCommitAsync(String description) {
    Order order = new Order(description, "PENDING");
    orderRepository.save(order);
    Long orderId = order.getId();

    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            // Fires AFTER the outer transaction commits → data is visible in DB.
            asyncOrderService.processOrderAsync(orderId, description);
        }
    });
}
```

`TransactionSynchronizationManager.registerSynchronization` schedules a callback for **after the current transaction commits**. The async task is only dispatched once the data is safely in the database.

---

## Summary table

| Scenario | Thread boundary | Transaction shared? | Recommended? |
|---|---|---|---|
| Same-thread `@Transactional` | No | ✅ Yes | ✅ Yes |
| `new Thread(…)` inside `@Transactional` | Yes (raw) | ❌ No — auto-commit | ❌ No |
| `@Async` + `@Transactional` | Yes (pool) | ❌ No — fresh transaction | ⚠️ With care |
| `afterCommit` → `@Async` | Yes (pool, after commit) | ❌ No — intended | ✅ Yes |

---

## Project structure

```
src/
  main/java/com/example/runasynctak/
    RunAsyncTakApplication.java       # @SpringBootApplication + @EnableAsync
    config/AsyncConfig.java           # ThreadPoolTaskExecutor bean
    entity/Order.java                 # JPA entity
    repository/OrderRepository.java   # Spring Data repository
    service/OrderService.java         # Scenarios 1–4
    service/AsyncOrderService.java    # @Async + @Transactional methods
  main/resources/application.properties
  test/java/com/example/runasynctak/
    TransactionalMultithreadingTest.java  # Integration tests for all scenarios
```

## Running the tests

```bash
mvn test
```
