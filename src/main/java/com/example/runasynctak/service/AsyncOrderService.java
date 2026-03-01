package com.example.runasynctak.service;

import com.example.runasynctak.entity.Order;
import com.example.runasynctak.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CompletableFuture;

/**
 * Dedicated service for async operations.
 *
 * <p>Spring's {@code @Async} proxy only works when the call originates from
 * <em>outside</em> the bean (i.e. through a Spring proxy). That is why async
 * methods live in a <strong>separate bean</strong> from the caller.</p>
 *
 * <p>Each method annotated with {@code @Async} runs in a thread from the
 * {@code taskExecutor} pool (see {@link com.example.runasynctak.config.AsyncConfig}).</p>
 */
@Service
public class AsyncOrderService {

    private static final Logger log = LoggerFactory.getLogger(AsyncOrderService.class);

    private final OrderRepository orderRepository;

    public AsyncOrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Simulates async order processing.
     *
     * <p>This method is annotated with both {@code @Async} and {@code @Transactional}.
     * Spring will start a <em>new</em> transaction on this thread — completely
     * independent of any transaction the caller may have had. The {@code orderId}
     * passed in must already be committed to the database, otherwise the
     * {@code findById} call won't find it.</p>
     *
     * @param orderId     primary key of the already-saved order
     * @param description description for logging
     * @return a {@link CompletableFuture} so callers can optionally await completion
     */
    @Async("taskExecutor")
    @Transactional
    public CompletableFuture<Void> processOrderAsync(Long orderId, String description) {
        log.info("[Async] thread={} — starting async processing for orderId={}; transaction active={}",
                Thread.currentThread().getName(),
                orderId,
                TransactionSynchronizationManager.isActualTransactionActive());

        orderRepository.findById(orderId).ifPresentOrElse(order -> {
            order.setStatus("PROCESSED");
            orderRepository.save(order);
            log.info("[Async] orderId={} status updated to PROCESSED", orderId);
        }, () -> log.warn("[Async] orderId={} not found — caller's transaction may not have committed yet",
                orderId));

        return CompletableFuture.completedFuture(null);
    }
}
