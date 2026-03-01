package com.example.runasynctak.service;

import com.example.runasynctak.entity.Order;
import com.example.runasynctak.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service that illustrates the key rules of {@code @Transactional} + multithreading in Spring.
 *
 * <h2>Rule 1 — Transactions are thread-local</h2>
 * Spring stores the active transaction in a {@link ThreadLocal}. Any new thread you
 * spawn manually (e.g. {@code new Thread(...)}) inside a {@code @Transactional} method
 * will NOT participate in the caller's transaction. If that thread tries to use a
 * repository it will either run outside any transaction (auto-commit mode) or throw a
 * {@code JpaSystemException}, depending on the flush mode.
 *
 * <h2>Rule 2 — {@code @Async} methods start a fresh transaction</h2>
 * When Spring invokes an {@code @Async} method the call crosses a thread boundary.
 * Therefore, a {@code @Transactional @Async} method receives a brand-new transaction
 * that is completely independent of the caller's transaction. Data saved by the caller
 * is only visible to the async method <em>after</em> the caller's transaction commits.
 *
 * <h2>Rule 3 — Use {@code afterCommit} to safely chain async work</h2>
 * {@link TransactionSynchronizationManager#registerSynchronization} lets you schedule
 * an action to run <em>after</em> the current transaction commits. This guarantees
 * that the async task reads already-committed data.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final AsyncOrderService asyncOrderService;

    public OrderService(OrderRepository orderRepository, AsyncOrderService asyncOrderService) {
        this.orderRepository = orderRepository;
        this.asyncOrderService = asyncOrderService;
    }

    // -------------------------------------------------------------------------
    // Scenario 1: @Transactional in a single thread — works correctly
    // -------------------------------------------------------------------------

    /**
     * <strong>Scenario 1 — Correct usage in a single thread.</strong>
     *
     * <p>Both saves happen in the same transaction/thread. If any step throws an
     * exception the whole unit of work is rolled back.</p>
     */
    @Transactional
    public void createOrderSingleThread(String description) {
        log.info("[Scenario 1] thread={} — saving order inside @Transactional",
                Thread.currentThread().getName());

        Order order = new Order(description, "PENDING");
        orderRepository.save(order);

        // Any further DB work here shares the same transaction.
        order.setStatus("CONFIRMED");
        orderRepository.save(order);

        log.info("[Scenario 1] order saved with status CONFIRMED — will commit on method exit");
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Manual new thread inside @Transactional — transaction NOT shared
    // -------------------------------------------------------------------------

    /**
     * <strong>Scenario 2 — Spawning a raw thread inside a transaction (anti-pattern).</strong>
     *
     * <p>The spawned thread does NOT inherit the caller's {@link ThreadLocal} transaction
     * context. The repository call inside the new thread runs in auto-commit mode (or
     * outside any transaction), so its changes are committed immediately and independently,
     * bypassing the outer transaction's rollback semantics.</p>
     *
     * <p><em>Anti-pattern</em>: never rely on the new thread seeing uncommitted changes
     * from the outer transaction — they won't be visible.</p>
     */
    @Transactional
    public void createOrderWithRawThread(String description) throws InterruptedException {
        log.info("[Scenario 2] thread={} — outer transaction active={}",
                Thread.currentThread().getName(),
                TransactionSynchronizationManager.isActualTransactionActive());

        Order outerOrder = new Order(description + " (outer)", "PENDING");
        orderRepository.save(outerOrder);

        Thread innerThread = new Thread(() -> {
            // This thread has NO transaction context — isActualTransactionActive() == false.
            log.info("[Scenario 2] inner thread={} — transaction active={}",
                    Thread.currentThread().getName(),
                    TransactionSynchronizationManager.isActualTransactionActive());

            // This save runs outside the outer transaction (auto-commit).
            Order innerOrder = new Order(description + " (inner-raw-thread)", "AUTO_COMMIT");
            orderRepository.save(innerOrder);
        });

        innerThread.start();
        innerThread.join(); // wait so the test can assert deterministically

        log.info("[Scenario 2] outer thread continues — outer transaction still active={}",
                TransactionSynchronizationManager.isActualTransactionActive());
    }

    // -------------------------------------------------------------------------
    // Scenario 3: @Async + @Transactional — each async call gets its own transaction
    // -------------------------------------------------------------------------

    /**
     * <strong>Scenario 3 — {@code @Async} method with its own {@code @Transactional}.</strong>
     *
     * <p>The caller saves an order and then fires an async notification. Because the
     * async method runs in a different thread it cannot see the caller's uncommitted
     * save. The async method starts its own transaction when it begins.</p>
     *
     * <p>If the caller's transaction rolls back <em>after</em> the async method has
     * already committed, the async work is <em>not</em> rolled back — these are two
     * independent transactions.</p>
     */
    @Transactional
    public void createOrderWithAsync(String description) {
        log.info("[Scenario 3] thread={} — saving order before async call",
                Thread.currentThread().getName());

        Order order = new Order(description, "PENDING");
        orderRepository.save(order);

        // Fire async work — this crosses a thread boundary. The async method will
        // NOT see 'order' until the current transaction commits.
        asyncOrderService.processOrderAsync(order.getId(), description);

        log.info("[Scenario 3] async task dispatched; current transaction will commit on return");
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Correct pattern — schedule async work *after* commit
    // -------------------------------------------------------------------------

    /**
     * <strong>Scenario 4 — Correct pattern: register an after-commit hook.</strong>
     *
     * <p>{@link TransactionSynchronizationManager#registerSynchronization} allows us
     * to schedule a callback that runs <em>after</em> the transaction commits. Only
     * then is the async task fired, guaranteeing that the data is visible in the
     * database before the async method tries to read it.</p>
     */
    @Transactional
    public void createOrderWithAfterCommitAsync(String description) {
        log.info("[Scenario 4] thread={} — saving order with after-commit hook",
                Thread.currentThread().getName());

        Order order = new Order(description, "PENDING");
        orderRepository.save(order);
        Long orderId = order.getId();

        // Register a callback that fires AFTER this transaction commits.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("[Scenario 4] afterCommit — data is now visible; firing async task for orderId={}",
                        orderId);
                asyncOrderService.processOrderAsync(orderId, description);
            }
        });

        log.info("[Scenario 4] synchronization registered; transaction will commit on return");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public List<Order> findByStatus(String status) {
        return orderRepository.findByStatus(status);
    }

    public List<Order> findAll() {
        return orderRepository.findAll();
    }
}
