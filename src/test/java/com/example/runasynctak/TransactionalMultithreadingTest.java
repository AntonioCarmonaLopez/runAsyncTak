package com.example.runasynctak;

import com.example.runasynctak.entity.Order;
import com.example.runasynctak.repository.OrderRepository;
import com.example.runasynctak.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests that verify the behaviour described in each scenario of
 * {@link com.example.runasynctak.service.OrderService}.
 *
 * <p>Each test uses a fresh application context ({@code @DirtiesContext}) so that
 * H2 data from one test does not affect another.</p>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TransactionalMultithreadingTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // Scenario 1
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 1 — @Transactional in single thread: order is saved and committed")
    void scenario1_singleThreadTransaction_savesOrder() {
        orderService.createOrderSingleThread("Test order");

        List<Order> orders = orderRepository.findAll();
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getStatus()).isEqualTo("CONFIRMED");
        assertThat(orders.get(0).getDescription()).isEqualTo("Test order");
    }

    // -------------------------------------------------------------------------
    // Scenario 2
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 2 — Raw thread inside @Transactional: inner thread has no transaction context")
    void scenario2_rawThreadInsideTransaction_innerThreadHasNoTransaction() throws InterruptedException {
        orderService.createOrderWithRawThread("Raw thread order");

        // Both the outer and the inner-raw-thread orders should be persisted.
        List<Order> all = orderRepository.findAll();
        assertThat(all).hasSize(2);

        // The inner-raw-thread order was committed in auto-commit mode
        // (independent of the outer transaction).
        List<Order> autoCommit = orderRepository.findByStatus("AUTO_COMMIT");
        assertThat(autoCommit).hasSize(1);
        assertThat(autoCommit.get(0).getDescription()).contains("inner-raw-thread");

        // The outer order was committed by the outer @Transactional.
        List<Order> pending = orderRepository.findByStatus("PENDING");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getDescription()).contains("outer");
    }

    // -------------------------------------------------------------------------
    // Scenario 3
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 3 — @Async + @Transactional: async method gets its own transaction")
    void scenario3_asyncWithTransaction_asyncGetsOwnTransaction() {
        orderService.createOrderWithAsync("Async order");

        // Wait for async task to complete and update the status.
        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> !orderRepository.findByStatus("PROCESSED").isEmpty());

        List<Order> processed = orderRepository.findByStatus("PROCESSED");
        assertThat(processed).hasSize(1);
        assertThat(processed.get(0).getDescription()).isEqualTo("Async order");
    }

    // -------------------------------------------------------------------------
    // Scenario 4
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 4 — afterCommit hook: async task runs only after outer transaction commits")
    void scenario4_afterCommitHook_asyncRunsAfterCommit() {
        orderService.createOrderWithAfterCommitAsync("AfterCommit order");

        // Wait for the after-commit async task to process the order.
        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> !orderRepository.findByStatus("PROCESSED").isEmpty());

        List<Order> processed = orderRepository.findByStatus("PROCESSED");
        assertThat(processed).hasSize(1);
        assertThat(processed.get(0).getDescription()).isEqualTo("AfterCommit order");
    }
}
